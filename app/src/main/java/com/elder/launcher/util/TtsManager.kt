package com.elder.launcher.util

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 全局语音播报。整个 App 只有这一个 TTS 实例（[com.elder.launcher.ElderApp] 里初始化）。
 *
 * 这个类是**异步就绪**的：`TextToSpeech` 的构造函数立刻返回，真正的引擎连接结果在回调里。
 * 所以有两个坑必须在这里处理掉，否则表现就是「点了没声音」：
 *
 * ① **冷启动第一下点击**：用户打开 App 马上点快捷拨号，此时引擎还没连上。
 *    早先的实现会直接把这次播报丢掉，听不到任何声音；现在改成先存下来，就绪后补念一次。
 * ② **初始化失败**：`status != SUCCESS` 时早先的实现仍然把 `tts` 留着，而 `init()`
 *    看到 `tts != null` 就早退，于是**整场会话永久静音**。现在失败会把实例销毁掉，下次点击自动重试。
 *
 * 另外中文语音数据缺失时不再默默不出声，而是退回系统默认语言（至少能念出来），并打一条日志。
 */
object TtsManager {

    private const val TAG = "TtsManager"

    private var tts: TextToSpeech? = null

    /** 引擎已连上、可以播报 */
    private var ready = false

    /** 正在初始化中，避免重复创建实例 */
    private var initializing = false

    /** 还没就绪时先把要念的内容存这里，就绪后补念（见类注释 ①） */
    private var pending: String? = null

    fun init(ctx: Context) {
        if (tts != null || initializing) return
        initializing = true
        val app = ctx.applicationContext
        tts = TextToSpeech(app) { status ->
            initializing = false
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                val chineseOk = result != TextToSpeech.LANG_NOT_SUPPORTED &&
                        result != TextToSpeech.LANG_MISSING_DATA
                if (!chineseOk) {
                    // 没装中文语音数据：退回系统默认语言，否则整台机器上的播报全都是静音
                    Log.w(TAG, "中文语音数据不可用（$result），退回系统默认语言")
                    tts?.setLanguage(Locale.getDefault())
                }
                ready = true
                flushPending(app)
            } else {
                // 关键：销毁实例，让下一次点击能重新初始化（否则一次失败 = 永久静音）
                Log.w(TAG, "TTS 初始化失败：$status，下次播报时重试")
                tts?.shutdown()
                tts = null
            }
        }
    }

    fun speak(ctx: Context, text: String) {
        if (text.isEmpty()) return
        if (!PrefsManager.isVoiceEnabled(ctx)) return

        val engine = tts
        if (engine == null || !ready) {
            // 引擎还没准备好：记下来，初始化完成后再念，不要丢
            pending = text
            init(ctx)
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "elder_${text.hashCode()}")
    }

    /** 就绪后把等待中的那一条补念出来 */
    private fun flushPending(ctx: Context) {
        val text = pending ?: return
        pending = null
        if (!PrefsManager.isVoiceEnabled(ctx)) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "elder_${text.hashCode()}")
    }

    fun shutdown() {
        pending = null
        ready = false
        initializing = false
        tts?.stop()
        tts?.shutdown()
        tts = null
    }
}
