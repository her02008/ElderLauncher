package com.elder.launcher.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.elder.launcher.FallActivity
import com.elder.launcher.R
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * 跌倒检测：手机摔落（自由落体 + 撞击）之后没人捡起时，先响铃提醒机主；
 * 机主还没反应就短信**并拨打**家人电话；家人回拨时自动接听（让家人听到现场）。
 * 响铃节奏按电量调整：电量越紧，响得越短、停得越长，把电留给真正的求助通话。
 *
 * 检测原理（纯加速度计，不需要传感器权限）：
 * - 自由落体：合加速度掉到接近 0g（<3 m/s²）持续 ≥100ms
 * - 撞击：随后 600ms 内出现 ≥30 m/s²（约 3g）的尖峰
 * - 捡起：撞击后等 2 秒让手机稳定，记下当时的重力方向；之后重力方向
 *   偏转超过 40° 就算被捡起（桌面振动、被人碰一下都到不了 40°）
 *
 * 省电设计：只在**亮屏时**监听传感器（摔落几乎都发生在拿在手里的时段），
 * 熄屏即注销；唯一例外是检测到摔落后的观察期——那时手机躺在地上，屏幕多半
 * 已经熄了，必须继续听传感器才能发现「被捡起」。
 *
 * 自动接听说明（分层降级，都尽力而为）：
 * 1. API 26+ 申请了 ANSWER_PHONE_CALLS → TelecomManager.acceptRingingCall()（部分 ROM 会拒绝）
 * 2. 兜底：模拟耳机接听键（KEYCODE_HEADSETHOOK），不少机型可用，不保证
 *
 * 局限（要有预期，别当保险柜卖）：
 * - 掉到床上、沙发等软面上往往没有明显的 0g 段，可能不触发
 * - 手机进程被 ROM 杀掉后检测就停了，下次打开桌面才会重启
 * - 后台直接拨号/接听在部分 ROM 上会被拦，失败时短信仍在，家人会回拨
 */
object FallDetector : SensorEventListener {

    private const val TAG = "FallDetector"

    /** 合加速度低于这个值（m/s²）视为自由落体段 */
    private const val FREEFALL_MAX = 3.0f

    /** 自由落体至少持续这么久才算（几十毫秒的抖动不算） */
    private const val FREEFALL_MIN_MS = 100L

    /** 撞击尖峰下限（m/s²），约 3g */
    private const val IMPACT_MIN = 30f

    /** 撞击必须发生在自由落体结束后这个窗口内 */
    private const val IMPACT_WINDOW_MS = 600L

    /** 重力方向偏转超过这个角度（度）算被捡起 */
    private const val PICKUP_ANGLE_DEG = 40.0

    /** 撞击后先等这么久再取基准重力方向，避开弹跳晃动 */
    private const val SETTLE_MS = 2_000L

    /** 摔落后没人捡起多久 → 响铃提醒机主。要「及时」，给机主留取消误报的窗口 */
    private const val PICKUP_WINDOW_MS = 60_000L

    /** 响铃后机主多久没回应 → 短信 + 拨打家人电话 */
    private const val RESPONSE_WINDOW_MS = 180_000L

    /** 短信先发出，隔这么久再拨电话（给短信留送达时间） */
    private const val CALL_DELAY_MS = 8_000L

    /**
     * 来电接通满这么久才算「家人已核实情况」，通话结束即退出预警。
     * 太短（误接、秒挂）不算，避免推销电话把预警悄悄消掉。
     */
    private const val CALL_EXIT_MIN_MS = 15_000L

    /** 一次流程结束后的静默期，防止连环误报 */
    private const val COOLDOWN_MS = 10 * 60_000L

    // ---------------- 电量自适应的响铃节奏 ----------------

    /** 低于此电量算「电量紧张」 */
    private const val BATTERY_LOW = 20

    /** 高于此电量（且没开省电模式）持续响铃不打折 */
    private const val BATTERY_FULL = 50

    /** 中等电量的节奏：响 30 秒 / 停 60 秒 */
    private const val CYCLE_MID_RING_MS = 30_000L
    private const val CYCLE_MID_SILENCE_MS = 60_000L

    /** 低电量/省电模式的节奏：响 10 秒 / 停 120 秒 */
    private const val CYCLE_LOW_RING_MS = 10_000L
    private const val CYCLE_LOW_SILENCE_MS = 120_000L

    private const val CHANNEL_ID = "fall_alert"
    private const val NOTIF_ID = 4001

    private val STATE_IDLE = 0
    private val STATE_WATCH = 1   // 撞击已发生，等「被捡起」
    private val STATE_ALERT = 2   // 已响铃提醒，等机主回应

    private var appCtx: Context? = null
    private var sensorManager: SensorManager? = null
    private var screenReceiverRegistered = false
    private var phoneReceiverRegistered = false
    private var listening = false

    private var state = STATE_IDLE
    private var lowSince = 0L        // 本次低于自由落体阈值的起点，0 = 不在低段
    private var watchStart = 0L      // 撞击时刻
    private var baseline: FloatArray? = null
    private var gravity = FloatArray(3)   // 低通滤波后的重力方向
    private var gravityInit = false
    private var alertStart = 0L
    private var smsSent = false
    private var callPlaced = false
    private var alarmOn = false      // 当前是否处于响铃相位（电量自适应会切换）
    private var autoAnswered = false // 本次流程里只自动接听一次
    private var cooldownUntil = 0L
    private var inCall = false       // 通话进行中（来电/去电都算），响铃必须静音
    private var incomingCall = false // 本次通话是家人打进来的（退出机制只认来电）
    private var offhookAt = 0L       // 接通时刻，用于算通话时长

    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())

    // ---------------- 开关与生命周期 ----------------

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        if (PrefsManager.isFallEnabled(appCtx!!)) start(appCtx!!)
    }

    fun setEnabled(ctx: Context, enabled: Boolean) {
        PrefsManager.setFallEnabled(ctx, enabled)
        val app = ctx.applicationContext
        if (enabled) {
            start(app)
        } else {
            cancelAll(true)
            stopAll(app)
            appCtx = null
        }
    }

    fun isEnabled(ctx: Context): Boolean = PrefsManager.isFallEnabled(ctx)

    private fun start(app: Context) {
        if (appCtx != null) return
        appCtx = app
        // 熄屏即停传感器；亮屏才恢复。注册的是 app 进程级接收器，与页面生命周期无关
        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        app.registerReceiver(screenReceiver, screenFilter)
        screenReceiverRegistered = true

        // 来电状态：家人看到短信回拨时自动接听
        val phoneFilter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        app.registerReceiver(phoneReceiver, phoneFilter)
        phoneReceiverRegistered = true

        val sm = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        sensorManager = sm
        if (sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) == null) {
            Log.w(TAG, "no accelerometer, fall detection unavailable")
            return
        }
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isInteractive == true) startListening()
    }

    private fun stopAll(app: Context) {
        if (screenReceiverRegistered) {
            try { app.unregisterReceiver(screenReceiver) } catch (_: Exception) {}
            screenReceiverRegistered = false
        }
        if (phoneReceiverRegistered) {
            try { app.unregisterReceiver(phoneReceiver) } catch (_: Exception) {}
            phoneReceiverRegistered = false
        }
        stopListening()
    }

    private fun startListening() {
        val sm = sensorManager ?: return
        if (listening) return
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            // SENSOR_DELAY_UI ≈ 60Hz，检测自由落体够用，功耗也可接受
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            listening = true
        }
    }

    private fun stopListening() {
        if (!listening) return
        sensorManager?.unregisterListener(this)
        listening = false
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> startListening()
                Intent.ACTION_SCREEN_OFF ->
                    // 观察期/响铃期必须继续听，否则永远发现不了「被捡起」
                    if (state == STATE_IDLE) stopListening()
            }
        }
    }

    private val phoneReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (state != STATE_ALERT) return
            when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
                TelephonyManager.EXTRA_STATE_RINGING -> {
                    incomingCall = true
                    enterCall()
                    if (!autoAnswered) {
                        autoAnswered = true
                        Log.i(TAG, "incoming call during alert, auto-answer")
                        attemptAnswer()
                    }
                }
                TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                    if (offhookAt == 0L) offhookAt = System.currentTimeMillis()
                    enterCall()
                }
                TelephonyManager.EXTRA_STATE_IDLE -> onCallEnded()
            }
        }
    }

    /** 通话期间必须静音：家人接通后要能听清现场，不是听我们的警铃 */
    private fun enterCall() {
        if (!inCall) {
            inCall = true
            stopAlarmSound()
            Log.i(TAG, "call active, alarm muted")
        }
    }

    /**
     * 高级退出机制：通话结束后结算。
     * - **来电**且接通时长 ≥15 秒（自动接听后，不论谁先挂断）→ 视为家人已核实情况，
     *   整个预警流程退出并进入冷却。
     * - 通话太短 / 没接通 → 恢复提醒节奏（下一秒 tick 按电量继续），家人可以再打。
     */
    private fun onCallEnded() {
        if (!inCall) return
        inCall = false
        val now = System.currentTimeMillis()
        val duration = if (offhookAt > 0) now - offhookAt else 0L
        val wasIncoming = incomingCall
        incomingCall = false
        offhookAt = 0L
        autoAnswered = false   // 恢复后家人再打仍可自动接听
        if (wasIncoming && duration >= CALL_EXIT_MIN_MS) {
            Log.i(TAG, "family call lasted ${duration}ms, exit alert via call")
            cancelAll(true)
            return
        }
        Log.i(TAG, "call ended (${duration}ms), resume alert")
        alarmOn = false
    }

    // ---------------- 传感器 ----------------

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        val mag = sqrt(
            event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]
        )

        // 低通滤波取重力方向：捡起判定的基准
        if (!gravityInit) {
            gravity[0] = event.values[0]; gravity[1] = event.values[1]; gravity[2] = event.values[2]
            gravityInit = true
        } else {
            gravity[0] = gravity[0] * 0.8f + event.values[0] * 0.2f
            gravity[1] = gravity[1] * 0.8f + event.values[1] * 0.2f
            gravity[2] = gravity[2] * 0.8f + event.values[2] * 0.2f
        }

        when (state) {
            STATE_IDLE -> detectFall(mag, now)
            STATE_WATCH -> checkPickedUp(now)
            STATE_ALERT -> checkPickedUp(now)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun detectFall(mag: Float, now: Long) {
        if (mag < FREEFALL_MAX) {
            if (lowSince == 0L) lowSince = now
            return
        }
        val lowMs = if (lowSince == 0L) 0L else now - lowSince
        lowSince = 0L
        // 自由落体 ≥100ms 之后紧跟一记 ≥3g 的尖峰 → 判定摔落
        if (lowMs in FREEFALL_MIN_MS..IMPACT_WINDOW_MS && mag > IMPACT_MIN) {
            if (now < cooldownUntil) return
            Log.i(TAG, "fall detected (low=${lowMs}ms, impact=${mag}m/s2)")
            beginWatch(now)
        }
    }

    private fun beginWatch(now: Long) {
        state = STATE_WATCH
        watchStart = now
        baseline = null
        gravityInit = false
        smsSent = false
        callPlaced = false
        autoAnswered = false
        inCall = false
        incomingCall = false
        offhookAt = 0L
        // 观察期保持监听（哪怕熄屏），由 screenReceiver 保证
        beginTickLoop()
    }

    /**
     * 阶段推进的内部定时器：WATCH 到点拉响提醒，ALERT 里推进电量自适应的
     * 响/停节奏、到点发短信和拨号。**不能依赖页面驱动**——响铃之前提醒页
     * 还不存在，进程里只有这个循环在数时间。
     */
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (state == STATE_IDLE) return
            val now = System.currentTimeMillis()
            when (state) {
                STATE_WATCH -> fireAlertIfNeeded()
                STATE_ALERT -> {
                    checkPickedUp(now)
                    checkResponseTimeout(now)
                    handleAlertCycle(now)
                }
            }
            if (state != STATE_IDLE) handler.postDelayed(this, 1000)
        }
    }

    private fun beginTickLoop() {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, 1000)
    }

    private fun checkPickedUp(now: Long) {
        val base = baseline
        if (base == null) {
            // 还在稳定期；过了稳定期就把当前重力方向记下来当基准
            if (now - watchStart >= SETTLE_MS) baseline = gravity.copyOf()
            return
        }
        if (angleBetween(base, gravity) > PICKUP_ANGLE_DEG) {
            Log.i(TAG, "picked up, cancel")
            cancelAll(true)
        }
    }

    // ---------------- 提醒与升级 ----------------

    private fun fireAlertIfNeeded() {
        if (state != STATE_WATCH) return
        val now = System.currentTimeMillis()
        if (now - watchStart < PICKUP_WINDOW_MS) return
        state = STATE_ALERT
        alertStart = now
        alarmOn = false
        showNotification()
        Log.i(TAG, "alert fired")
    }

    /** 电量自适应的响/停节奏。持续响 = 中途永远不切换 */
    private fun handleAlertCycle(now: Long) {
        if (inCall) return   // 通话中不响（enterCall 已静音，别再被 tick 拉起来）
        val cycle = alertCycle()
        if (cycle.first <= 0) {   // 持续响
            if (!alarmOn) startAlarmSound()
            return
        }
        val position = (now - alertStart) % (cycle.first + cycle.second)
        val wantRing = position < cycle.first
        if (wantRing != alarmOn) {
            if (wantRing) startAlarmSound() else stopAlarmSound()
        }
    }

    private fun alertCycle(): Pair<Long, Long> {
        val ctx = appCtx ?: return Pair(0L, 0L)
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val pct = batteryPct(ctx)
        val low = (pct in 0..BATTERY_LOW) || pm?.isPowerSaveMode == true
        val mid = pct in 0..BATTERY_FULL
        return when {
            low -> Pair(CYCLE_LOW_RING_MS, CYCLE_LOW_SILENCE_MS)
            mid -> Pair(CYCLE_MID_RING_MS, CYCLE_MID_SILENCE_MS)
            else -> Pair(0L, 0L)   // 电量充足（或读不到）：持续响
        }
    }

    /** @return 0–100；读不到返回 -1（调用方按「充足」处理，别因为读数失败就不响了） */
    private fun batteryPct(ctx: Context): Int {
        return try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (level in 0..100) level else -1
        } catch (_: Exception) {
            -1
        }
    }

    /** 响铃到点且机主还没反应：先短信，隔几秒再直接拨打家人电话 */
    private fun checkResponseTimeout(now: Long) {
        if (now - alertStart < RESPONSE_WINDOW_MS || smsSent) return
        if (inCall) return   // 已经跟家人通话了，别再发短信/拨号打断
        smsSent = true
        val ctx = appCtx ?: return
        val phone = AliveCenter.notifyPhone(ctx)
        if (phone == null) {
            Log.w(TAG, "no notify phone configured, sms skipped")
            return
        }
        val minutes = ((RESPONSE_WINDOW_MS + PICKUP_WINDOW_MS) / 60_000L).toInt()
        val model = Build.MODEL.ifBlank { ctx.getString(R.string.fall_sms_device) }
        // 带上当前电量：预警本身耗电，家人要能判断「还有多少时间能打通」
        val text = batteryPct(ctx).takeIf { it >= 0 }?.let {
            ctx.getString(R.string.fall_sms_battery, model, minutes, it)
        } ?: ctx.getString(R.string.fall_sms, model, minutes)
        val ok = SmsSender.send(ctx, phone, text)
        Log.i(TAG, "alert sms sent=$ok")

        // 短信先走，隔一小会儿拨号（后台拨号是尽力而为：被 ROM 拦时短信仍会送达，
        // 家人看到短信会回拨，回拨会被自动接听）
        handler.postDelayed({
            if (state == STATE_ALERT) placeCall(phone)
        }, CALL_DELAY_MS)
    }

    private fun placeCall(phone: String) {
        val ctx = appCtx ?: return
        if (callPlaced) return
        callPlaced = true
        val granted = ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        val action = if (granted) Intent.ACTION_CALL else Intent.ACTION_DIAL
        try {
            ctx.startActivity(
                Intent(action, Uri.parse("tel:$phone"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            Log.i(TAG, "escalation call placed ($action)")
        } catch (e: Exception) {
            Log.w(TAG, "place call failed: ${e.message}")
        }
    }

    /** 家人来电时自动接听：先走 TelecomManager，被拒再模拟耳机接听键 */
    private fun attemptAnswer() {
        val ctx = appCtx ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val tm = ctx.getSystemService(TelecomManager::class.java)
                val granted = ContextCompat.checkSelfPermission(
                    ctx, android.Manifest.permission.ANSWER_PHONE_CALLS
                ) == PackageManager.PERMISSION_GRANTED
                if (tm != null && granted) {
                    @Suppress("DEPRECATION")
                    tm.acceptRingingCall()
                    Log.i(TAG, "answered via TelecomManager")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "acceptRingingCall failed: ${e.message}")
            }
        }
        // 兜底：模拟耳机接听键（媒体键路由，很多机型可用，不保证）
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK))
            Log.i(TAG, "answered via headset hook")
        } catch (e: Exception) {
            Log.w(TAG, "headset hook failed: ${e.message}")
        }
    }

    // ---------------- 响铃与震动 ----------------

    private fun startAlarmSound() {
        val ctx = appCtx ?: return
        stopAlarmSound()
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setDataSource(ctx, uri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "alarm sound failed", e)
        }
        try {
            // Android 12 起 VIBRATOR_SERVICE 弃用，改走 VibratorManager
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                    ?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            val pattern = longArrayOf(0, 600, 400, 600, 400)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "vibrate failed", e)
        }
        alarmOn = true
    }

    private fun stopAlarmSound() {
        try { player?.stop(); player?.release() } catch (_: Exception) {}
        player = null
        try { vibrator?.cancel() } catch (_: Exception) {}
        vibrator = null
        alarmOn = false
    }

    private fun showNotification() {
        val ctx = appCtx ?: return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, ctx.getString(R.string.fall_channel),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { setBypassDnd(true) }
            )
        }
        val launch = Intent(ctx, FallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            ctx, NOTIF_ID, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(ctx, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(ctx).setPriority(Notification.PRIORITY_MAX)
        }
        val notif = builder
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(ctx.getString(R.string.fall_notif_title))
            .setContentText(ctx.getString(R.string.fall_notif_text))
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pi)
            .setFullScreenIntent(pi, true)
            .build()
        try {
            nm.notify(NOTIF_ID, notif)
        } catch (e: Exception) {
            Log.w(TAG, "notify failed: ${e.message}")
        }
        try { ctx.startActivity(launch) } catch (e: Exception) {
            Log.w(TAG, "startActivity failed: ${e.message}")
        }
    }

    fun clearNotification() {
        val ctx = appCtx ?: return
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
            ?.cancel(NOTIF_ID)
    }

    private fun cancelAll(cooldown: Boolean) {
        stopAlarmSound()
        clearNotification()
        handler.removeCallbacks(tickRunnable)
        if (cooldown) cooldownUntil = System.currentTimeMillis() + COOLDOWN_MS
        state = STATE_IDLE
        lowSince = 0L
        baseline = null
        inCall = false
        incomingCall = false
        offhookAt = 0L
        // 响铃期若已熄屏，这里恢复「熄屏不监听」的省电规则
        val ctx = appCtx ?: return
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isInteractive != true) stopListening()
    }

    private fun angleBetween(a: FloatArray, b: FloatArray): Double {
        val la = sqrt((a[0] * a[0] + a[1] * a[1] + a[2] * a[2]).toDouble())
        val lb = sqrt((b[0] * b[0] + b[1] * b[1] + b[2] * b[2]).toDouble())
        if (la < 1e-3 || lb < 1e-3) return 0.0
        var dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]) / (la * lb)
        if (dot > 1.0) dot = 1.0 else if (dot < -1.0) dot = -1.0
        return Math.toDegrees(acos(dot))
    }

    // ---------------- 给 FallActivity 的只读状态 ----------------

    fun inAlert(): Boolean = state == STATE_ALERT

    /** 短信这一步是否已处理过（发了或没号可发），给提醒页显示状态用 */
    fun isSmsHandled(): Boolean = smsSent

    /** 升级电话是否已拨（或尝试过） */
    fun isCallPlaced(): Boolean = callPlaced

    fun alertRemainingSeconds(): Int {
        if (state != STATE_ALERT) return 0
        val left = RESPONSE_WINDOW_MS - (System.currentTimeMillis() - alertStart)
        return (left / 1000L).toInt().coerceAtLeast(0)
    }

    /** 机主点了「我没事」 */
    fun ownerOk() {
        Log.i(TAG, "owner confirmed ok")
        cancelAll(true)
    }
}
