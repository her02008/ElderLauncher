package com.elder.launcher

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.elder.launcher.databinding.ActivityVoiceBinding
import com.elder.launcher.databinding.DialogVoiceSosBinding
import com.elder.launcher.util.ActionExecutor
import com.elder.launcher.util.AppUtils
import com.elder.launcher.util.ContactFinder
import com.elder.launcher.util.ContactHit
import com.elder.launcher.util.FontSizeManager
import com.elder.launcher.util.PinyinMatcher
import com.elder.launcher.util.PrefsManager
import com.elder.launcher.util.RadioLauncher
import com.elder.launcher.util.TorchManager
import com.elder.launcher.util.TtsManager
import com.elder.launcher.util.VoiceCommand
import com.elder.launcher.util.VoiceCommandMatcher
import com.elder.launcher.util.VoiceIntent
import java.util.Calendar

/**
 * 全屏语音页。
 *
 * 交互刻意做成**点一下就开始听**，不用「按住说话」：老人手抖，按住时中途松手就断。
 * 说完由引擎的静音检测（1.2 秒）自动结束；不想等可以再点一下麦克风立即结束。
 *
 * 识别本身交给系统（[SpeechRecognizer]），方言/口音的问题靠 [PinyinMatcher] 在**匹配阶段**兜住，
 * 而不是指望识别器认对——那部分不受我们控制。
 */
class VoiceActivity : BaseActivity() {

    companion object {
        /** 一句话最多听这么久，防止一直挂着 */
        private const val MAX_LISTEN_MS = 8_000L
    }

    private lateinit var binding: ActivityVoiceBinding
    private val handler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var listening = false
    private var closed = false

    /** 关掉之前要 dismiss，否则 Activity 销毁时窗口还挂着会 WindowLeaked */
    private var sosDialog: AlertDialog? = null

    private val timeoutTask = Runnable { stopListening() }

    /** 第一次进来才要权限；拒绝后不再反复弹，直接提示去设置里开 */
    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                beginListening()
            } else {
                announce(R.string.voice_need_mic)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVoiceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnVoiceCancel.setOnClickListener { close() }
        binding.btnVoiceMic.setOnClickListener {
            if (listening) stopListening() else beginListening()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            beginListening()
        } else {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // ---------------- 录音与识别 ----------------

    private fun beginListening() {
        if (closed) return

        // 引擎不存在时必须明说：国产 ROM 有些要在「设置 → 语音输入」里先选引擎
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            announce(R.string.voice_no_engine)
            return
        }

        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).also {
                it.setRecognitionListener(listener)
            }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            // 固定中文，不跟系统语言走：设备设成英文时跟系统走会听不懂
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // 多个候选：正确的说法常常排在第 2、3 位
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_200L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 800L)
        }

        try {
            recognizer?.startListening(intent)
        } catch (_: Exception) {
            announce(R.string.voice_error_generic)
            return
        }

        listening = true
        markListening(true)
        binding.tvVoiceHeard.text = getString(R.string.voice_listening)
        handler.removeCallbacks(timeoutTask)
        handler.postDelayed(timeoutTask, MAX_LISTEN_MS)
    }

    private fun stopListening() {
        if (!listening) return
        listening = false
        handler.removeCallbacks(timeoutTask)
        markListening(false)
        try {
            recognizer?.stopListening()
        } catch (_: Exception) {
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {
            listening = false
            markListening(false)
        }

        override fun onError(error: Int) {
            listening = false
            markListening(false)
            handler.removeCallbacks(timeoutTask)
            val message = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> R.string.voice_not_heard

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> R.string.voice_need_network

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> R.string.voice_need_mic
                SpeechRecognizer.ERROR_AUDIO -> R.string.voice_error_audio
                else -> R.string.voice_error_generic
            }
            announce(message)
        }

        override fun onResults(results: Bundle?) {
            val candidates = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                .orEmpty()
            handleResults(candidates)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val partial = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
            if (!partial.isNullOrBlank()) binding.tvVoiceHeard.text = partial
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    // ---------------- 匹配与执行 ----------------

    private fun handleResults(candidates: List<String>) {
        listening = false
        markListening(false)
        handler.removeCallbacks(timeoutTask)

        if (candidates.isEmpty()) {
            announce(R.string.voice_not_heard)
            return
        }
        // 把识别原文留在屏幕上：家属能看出到底是「没听见」还是「听错了」
        binding.tvVoiceHeard.text = candidates.first()

        when (val intent = VoiceCommandMatcher.match(candidates)) {
            VoiceIntent.None -> announce(R.string.voice_not_understood)
            is VoiceIntent.Simple -> runSimple(intent.command)
            is VoiceIntent.CallName -> runCallName(intent.name)
            is VoiceIntent.Open -> runOpen(intent.target)
        }
    }

    private fun runSimple(command: VoiceCommand) {
        when (command) {
            VoiceCommand.CALL_PLAIN -> {
                TtsManager.speak(this, getString(R.string.voice_reply_dial))
                startActivity(Intent(this, DialActivity::class.java))
                close()
            }

            VoiceCommand.RADIO -> openRadio()
            VoiceCommand.TIME -> tellTime()
            VoiceCommand.TORCH -> setTorch(!TorchManager.isOn())
            VoiceCommand.VOLUME_UP -> adjustVolume(up = true)
            VoiceCommand.VOLUME_DOWN -> adjustVolume(up = false)
            VoiceCommand.FONT_UP -> biggerFont()
            VoiceCommand.CHANNELS -> {
                announce(R.string.voice_reply_channels)
                handler.postDelayed({
                    ActionExecutor.openChannels(this)
                    close()
                }, 700)
            }

            VoiceCommand.HOME -> close()
            VoiceCommand.SOS -> confirmSos()
        }
    }

    /**
     * 「打电话给 X」。
     *
     * 通讯录可能有几千条，匹配放子线程；结果回到主线程再决定「直接拨 / 让老人挑 / 没找到」。
     */
    private fun runCallName(name: String) {
        binding.tvVoiceHeard.text = getString(R.string.voice_searching, name)
        Thread {
            val hits = ContactFinder.find(this, name)
            runOnUiThread {
                if (closed) return@runOnUiThread
                when {
                    hits.isEmpty() -> {
                        announce(R.string.voice_contact_missing)
                    }

                    hits.size == 1 -> {
                        callHit(hits.first())
                    }

                    else -> pickContact(hits)
                }
            }
        }.start()
    }

    private fun callHit(hit: ContactHit) {
        binding.tvVoiceHeard.text = getString(R.string.voice_calling, hit.name)
        TtsManager.speak(this, getString(R.string.voice_calling, hit.name))
        try {
            ActionExecutor.call(this, hit.phone)
        } catch (_: Exception) {
            ActionExecutor.dial(this, hit.phone)
        }
        close()
    }

    /** 同名的可能不止一个：列出来让老人点，别自作主张拨错人 */
    private fun pickContact(hits: List<ContactHit>) {
        val labels = hits.map { hit -> "${hit.name}     ${hit.phone}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.voice_pick_contact)
            .setItems(labels) { _, which -> hits.getOrNull(which)?.let { callHit(it) } }
            .setNegativeButton(R.string.cancel) { _, _ -> close() }
            .show()
    }

    /** 「打开 X」：先认内置功能，再找已装应用 */
    private fun runOpen(target: String) {
        when {
            target.contains("收音机") || target.contains("广播") -> openRadio()
            target.contains("手电") || target == "灯" -> setTorch(true)
            target.contains("视频号") -> runSimple(VoiceCommand.CHANNELS)
            target.contains("设置") -> {
                announce(R.string.voice_reply_settings)
                startActivity(Intent(this, SettingsActivity::class.java))
                close()
            }

            target.contains("时钟") || target.contains("闹钟") -> {
                announce(R.string.voice_reply_clock)
                ActionExecutor.openClock(this)
                close()
            }

            target.contains("日历") -> {
                announce(R.string.voice_reply_calendar)
                ActionExecutor.openCalendar(this)
                close()
            }

            else -> openAppByName(target)
        }
    }

    private fun openAppByName(name: String) {
        binding.tvVoiceHeard.text = getString(R.string.voice_searching, name)
        Thread {
            val apps = AppUtils.loadLaunchableApps(this)
            var bestLabel: String? = null
            var bestPkg: String? = null
            var bestScore = 0.0
            for (app in apps) {
                val score = PinyinMatcher.score(name, app.label)
                if (score > bestScore) {
                    bestScore = score
                    bestLabel = app.label
                    bestPkg = app.pkg
                }
            }
            val matched = if (bestScore >= PinyinMatcher.MATCH_THRESHOLD) bestLabel to bestPkg else null
            runOnUiThread {
                if (closed) return@runOnUiThread
                if (matched == null || matched.second == null) {
                    announce(R.string.voice_app_missing)
                    return@runOnUiThread
                }
                binding.tvVoiceHeard.text = getString(R.string.voice_opening, matched.first)
                TtsManager.speak(this, getString(R.string.voice_opening, matched.first))
                ActionExecutor.openApp(this, matched.second!!)
                close()
            }
        }.start()
    }

    // ---------------- 具体动作 ----------------

    private fun openRadio() {
        if (RadioLauncher.open(this)) {
            TtsManager.speak(this, getString(R.string.voice_reply_radio))
            close()
        } else {
            // 2020 年后的手机大多没有 FM 芯片，这里必须明说，不能让老人以为是坏了
            announce(R.string.voice_no_radio)
        }
    }

    private fun tellTime() {
        val now = Calendar.getInstance()
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)
        val text = if (minute == 0) {
            getString(R.string.voice_time_hour, hour)
        } else {
            getString(R.string.voice_time_hour_minute, hour, minute)
        }
        announceText(text)
        handler.postDelayed({ close() }, 1_800)
    }

    private fun setTorch(on: Boolean) {
        if (TorchManager.isOn() == on) {
            announce(if (on) R.string.voice_torch_already_on else R.string.voice_torch_already_off)
            return
        }
        TorchManager.toggle(this)
        announce(
            when {
                TorchManager.isOn() == on && on -> R.string.voice_torch_on
                TorchManager.isOn() == on -> R.string.voice_torch_off
                else -> R.string.voice_no_flash
            }
        )
    }

    private fun adjustVolume(up: Boolean) {
        val am = getSystemService(AUDIO_SERVICE) as? AudioManager
        if (am == null) {
            announce(R.string.voice_error_generic)
            return
        }
        val current = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (up && current >= max) {
            announce(R.string.voice_volume_max)
            return
        }
        if (!up && current <= 0) {
            announce(R.string.voice_volume_min)
            return
        }
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
        announce(R.string.voice_volume_done)
    }

    private fun biggerFont() {
        val current = PrefsManager.getFontLevel(this)
        if (current >= FontSizeManager.LEVEL_XLARGE) {
            announce(R.string.voice_font_max)
            return
        }
        PrefsManager.setFontLevel(this, current + 1)
        // 不用在这儿处理界面刷新：主页 onResume 会比对 PrefsManager 里的字号，
        // 发现变了就自己 recreate（MainActivity.appliedFontLevel），改完返回桌面即生效。
        announce(R.string.voice_font_done)
        handler.postDelayed({ close() }, 1_400)
    }

    /**
     * 说「救命」后**不直接发求救**，先弹全屏红页倒计时 3 秒。
     *
     * 倒计时默认执行是刻意的：老人真出事时喊完可能没力气再点。3 秒只用来给误触发留撤销窗口。
     * 按返回键等同取消。
     */
    private fun confirmSos() {
        val dialogBinding = DialogVoiceSosBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .setCancelable(true)
            .create()
        sosDialog = dialog
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        var remain = 3
        var fired = false
        val tick = object : Runnable {
            override fun run() {
                if (fired) return
                remain--
                if (remain <= 0) {
                    fired = true
                    dialog.dismiss()
                    startActivity(Intent(this@VoiceActivity, SosActivity::class.java))
                    close()
                } else {
                    dialogBinding.tvSosCount.text = remain.toString()
                    handler.postDelayed(this, 1_000)
                }
            }
        }

        dialogBinding.tvSosCount.text = remain.toString()
        dialogBinding.btnSosCancel.setOnClickListener {
            fired = true
            dialog.dismiss()
            announce(R.string.voice_sos_cancelled)
        }
        dialog.setOnDismissListener {
            if (!fired) {
                fired = true
                close()
            }
        }

        dialog.show()
        handler.postDelayed(tick, 1_000)
    }

    // ---------------- 界面 ----------------

    private fun markListening(active: Boolean) {
        binding.btnVoiceMic.setBackgroundResource(
            if (active) R.drawable.bg_voice_mic_active else R.drawable.bg_voice_mic
        )
        binding.btnVoiceMic.animate().cancel()
        binding.btnVoiceMic.animate()
            .scaleX(if (active) 1.08f else 1f)
            .scaleY(if (active) 1.08f else 1f)
            .setDuration(180)
            .start()
        binding.tvVoiceTitle.setText(if (active) R.string.voice_title_listening else R.string.voice_title)
    }

    private fun announce(resId: Int) = announceText(getString(resId))

    private fun announceText(text: String) {
        binding.tvVoiceHeard.text = text
        TtsManager.speak(this, text)
    }

    private fun close() {
        if (closed) return
        closed = true
        stopListening()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        try {
            sosDialog?.dismiss()
        } catch (_: Exception) {
        }
        sosDialog = null
        recognizer?.destroy()
        recognizer = null
    }
}
