package com.elder.launcher.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.elder.launcher.R
import com.elder.launcher.receiver.AliveAlarmReceiver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 「恰了么？」——被动式超时预警。
 *
 * ## 为什么不照搬「死了么」
 * 那个 App 的核心是**每天主动签到**。老人不会做这件事，装上三天就形同虚设。
 * 这里改成**被动检测**：只要今天解锁过手机，就算「还在」，老人不需要做任何操作。
 *
 * ## 数据从哪来
 * - 无障碍服务的窗口变化事件（主信号，覆盖「正在用手机」）
 * - `ACTION_USER_PRESENT`（解锁，见 [com.elder.launcher.service.ElderAccessibilityService]）
 * - 主页 onResume
 *
 * ⚠️ 所以**无障碍没开时这个功能基本失效**（只剩打开桌面才算活跃）。高级设置里会明说。
 *
 * ## 定时为什么不用 WorkManager
 * Doze 下 `WorkManager` 会被推迟几小时到一天，「24 小时预警」实际可能 30 小时才发。
 * 必须用 `AlarmManager.setExactAndAllowWhileIdle`，每次触发后再排下一次。
 */
object AliveCenter {

    /** 多久记一次「活跃」。窗口变化事件很密集，不做节流会一直写盘 */
    private const val TOUCH_THROTTLE_MS = 60_000L

    /** 一次「超时」最多发几条提醒，避免变成短信轰炸 */
    private const val MAX_ALERTS = 3

    /** 两条提醒之间至少隔这么久 */
    private const val ALERT_GAP_MS = 12 * 3600_000L

    /** 检查间隔：1 小时。24 小时的阈值，1 小时精度完全够，还省电 */
    const val CHECK_INTERVAL_MS = 3600_000L

    private const val ALARM_REQUEST = 1001

    @Volatile
    private var lastWriteAt = 0L

    // ---------------- 活跃记录 ----------------

    /**
     * 标记「刚刚有人用手机」。
     *
     * 写盘做节流，但**恢复判断不受节流影响**——否则刚好在 60 秒窗口内的那次操作
     * 可能让「已恢复」的短信永远发不出去。
     */
    fun touch(ctx: Context) {
        if (!PrefsManager.isAliveEnabled(ctx)) return
        val now = System.currentTimeMillis()

        // 报过警的，恢复时必须**强制**把活跃时间写进去。
        // 如果这里跟着下面的节流一起跳过，lastActive 还是几十小时前那个值，
        // 下一次检查又会判定超时，于是「刚恢复」和「又报警」来回横跳。
        if (PrefsManager.getAliveAlertCount(ctx) > 0) {
            lastWriteAt = now
            PrefsManager.setAliveLastActive(ctx, now)
            recover(ctx)
            return
        }

        if (now - lastWriteAt < TOUCH_THROTTLE_MS) return
        lastWriteAt = now
        PrefsManager.setAliveLastActive(ctx, now)
    }

    fun lastActive(ctx: Context): Long = PrefsManager.getAliveLastActive(ctx)

    fun idleMillis(ctx: Context): Long {
        val last = PrefsManager.getAliveLastActive(ctx)
        if (last <= 0L) return 0L
        return (System.currentTimeMillis() - last).coerceAtLeast(0L)
    }

    fun idleHours(ctx: Context): Int = (idleMillis(ctx) / 3600_000L).toInt()

    fun isPaused(ctx: Context): Boolean =
        PrefsManager.getAlivePauseUntil(ctx) > System.currentTimeMillis()

    fun pauseDays(ctx: Context, days: Int) {
        val until = if (days <= 0) 0L else System.currentTimeMillis() + days * 24 * 3600_000L
        PrefsManager.setAlivePauseUntil(ctx, until)
    }

    /** 预警短信发给谁：单独设的号码优先，其次是紧急联系人 */
    fun notifyPhone(ctx: Context): String? {
        val custom = PrefsManager.getAlivePhone(ctx)
        if (custom.isNotBlank()) return custom
        val sos = PrefsManager.getSosContact(ctx).second
        return sos.ifBlank { null }
    }

    // ---------------- 检查与提醒 ----------------

    /**
     * 定时检查。由 [AliveAlarmReceiver] 每小时调一次。
     *
     * 三种结局：
     * - 还没到阈值 → 如果之前报过警，说明刚刚恢复，发「已恢复」并清零
     * - 到了阈值 → 发提醒（最多 3 条，间隔 12 小时）
     * - 已发满 3 条 → 什么都不做，等恢复或家属处理
     */
    fun check(ctx: Context) {
        if (!PrefsManager.isAliveEnabled(ctx)) return
        if (isPaused(ctx)) return

        val now = System.currentTimeMillis()
        val last = PrefsManager.getAliveLastActive(ctx)
        if (last <= 0L) {
            // 从来没有记录过（刚开启就被检查到）：以现在为起点，别一上来就报警
            PrefsManager.setAliveLastActive(ctx, now)
            return
        }

        val threshold = PrefsManager.getAliveThresholdHours(ctx) * 3600_000L
        val idle = now - last

        if (idle < threshold) {
            if (PrefsManager.getAliveAlertCount(ctx) > 0) recover(ctx)
            return
        }

        val count = PrefsManager.getAliveAlertCount(ctx)
        if (count >= MAX_ALERTS) return
        if (count > 0 && now - PrefsManager.getAliveLastAlert(ctx) < ALERT_GAP_MS) return

        sendAlert(ctx, count, idle)
    }

    private fun sendAlert(ctx: Context, count: Int, idleMs: Long) {
        val phone = notifyPhone(ctx)
        if (phone == null) {
            // 没设号码：不发，也不计数（等家属设好号码后还能正常提醒）
            Log.w("AliveCenter", "no notify phone, skip alert")
            return
        }

        val hours = (idleMs / 3600_000L).toInt()
        val text = if (count == 0) {
            ctx.getString(
                R.string.alive_sms_first,
                hours,
                formatTime(PrefsManager.getAliveLastActive(ctx))
            )
        } else {
            ctx.getString(R.string.alive_sms_again, hours)
        }

        if (!SmsSender.send(ctx, phone, text)) {
            Log.w("AliveCenter", "alert sms failed")
            return
        }
        PrefsManager.setAliveLastAlert(ctx, System.currentTimeMillis())
        PrefsManager.setAliveAlertCount(ctx, count + 1)
    }

    /**
     * 从「已报警」回到活跃。
     *
     * 这条短信很重要：子女收到预警后会一直悬着，必须有一个明确的「结束了」的信号。
     */
    private fun recover(ctx: Context) {
        val phone = notifyPhone(ctx)
        PrefsManager.setAliveAlertCount(ctx, 0)
        if (phone != null) {
            SmsSender.send(ctx, phone, ctx.getString(R.string.alive_sms_recover))
        }
    }

    // ---------------- 闹钟排程 ----------------

    /**
     * 排下一次检查。每次触发后都要重新调一次（AlarmManager 是一次性的）。
     *
     * ⚠️ 这个闹钟现在同时服务两件事：**「恰了么？」的检查**和**远程配置同步**
     * （见 [com.elder.launcher.receiver.AliveAlarmReceiver]）。
     * 所以开关条件必须是「两者任一开启」——只判 `isAliveEnabled` 的话，
     * 用户绑了远程设置却没开「恰了么？」，同步就永远不会跑，看起来像功能坏了。
     */
    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = pendingIntent(ctx)

        if (!PrefsManager.isAliveEnabled(ctx) && !PrefsManager.isRemoteBound(ctx)) {
            am.cancel(pending)
            PrefsManager.setAliveNextCheck(ctx, 0L)
            return
        }

        val at = System.currentTimeMillis() + CHECK_INTERVAL_MS
        // Android 12 起精确闹钟要单独授权；拿不到就降级成不精确的（可能晚一些，但不会不跑）
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
            // 自己记一份，`nextAlarmClock` 读不到 setExactAndAllowWhileIdle 排的时间
            PrefsManager.setAliveNextCheck(ctx, at)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            PrefsManager.setAliveNextCheck(ctx, at)
        }
    }

    fun cancel(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pendingIntent(ctx))
        PrefsManager.setAliveNextCheck(ctx, 0L)
    }

    /** 诊断用：下一次检查大概什么时候；0 = 没排上 */
    fun nextCheckAt(ctx: Context): Long = PrefsManager.getAliveNextCheck(ctx)

    private fun pendingIntent(ctx: Context): PendingIntent {
        val intent = Intent(ctx, AliveAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            ctx,
            ALARM_REQUEST,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun formatTime(at: Long): String {
        if (at <= 0L) return "-"
        return SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(at))
    }
}
