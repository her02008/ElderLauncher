package com.elder.launcher.util

/**
 * 能听懂的固定命令。
 *
 * **这是一个封闭集合**，不做自由对话。原因很实际：集合越小，拼音容错越敢放宽阈值；
 * 开放域一旦听错就会执行到完全无关的动作上，对老人来说是灾难。
 *
 * 「打电话给某人」「打开某应用」是**带参数**的，不在这里，见 [VoiceCommandMatcher]。
 */
enum class VoiceCommand(val phrases: List<String>) {

    /** 只说「打电话」不带人名：打开大键盘让老人自己按 */
    CALL_PLAIN(
        listOf("打电话", "我要打电话", "打个电话", "拨号", "我要拨号", "打个电话吧")
    ),

    RADIO(
        listOf("收音机", "开收音机", "听收音机", "听广播", "打开收音机", "放收音机")
    ),

    TIME(
        listOf("几点了", "现在几点", "报时", "什么时间", "几时了", "现在时间")
    ),

    TORCH(
        listOf("手电筒", "开灯", "照亮", "太黑了", "手电", "打开手电筒", "开手电")
    ),

    VOLUME_UP(
        listOf("声音大点", "声音大一点", "大点声", "听不清", "声音大些")
    ),

    VOLUME_DOWN(
        listOf("声音小点", "声音小一点", "小点声", "太吵了", "声音小些")
    ),

    FONT_UP(
        listOf("字太小", "字太小了", "字大点", "字大一点", "看不清楚", "放大字")
    ),

    CHANNELS(
        listOf("视频号", "看视频号", "打开视频号", "我要看视频号")
    ),

    HOME(
        listOf("回桌面", "回家", "退出", "返回桌面", "退出应用")
    ),

    /**
     * 紧急求助。**命中后绝不直接执行**——必须走二次确认（见 VoiceActivity.confirmSos）。
     * 电视里演一句、家人聊天提一句都会误触发，直接发短信+打电话的代价太大。
     */
    SOS(
        listOf("救命", "救命啊", "救救我", "快来救我", "我不舒服", "快来人")
    )
}

/** 匹配结果 */
sealed class VoiceIntent {

    /** 没听出来，或者分数不到阈值 */
    object None : VoiceIntent()

    /** 固定命令 */
    data class Simple(val command: VoiceCommand, val score: Double) : VoiceIntent()

    /** 打电话给某人 */
    data class CallName(val name: String, val score: Double) : VoiceIntent()

    /** 打开某个应用 / 内置功能 */
    data class Open(val target: String, val score: Double) : VoiceIntent()
}

/**
 * 把识别候选变成意图。
 *
 * 输入是**多个候选**（`EXTRA_MAX_RESULTS = 3`）：正确说法经常排在第 2、3 位，
 * 只看第一位等于白白浪费一半机会。
 */
object VoiceCommandMatcher {

    /**
     * 「开」开头的短语。顺序有讲究：长前缀必须在前，
     * 否则「打开微信」会被短前缀「开」吃掉，剩下「打微信」去查应用名。
     */
    private val OPEN_PREFIXES = listOf(
        "帮我打开", "我要打开", "我想打开", "打开", "我要看", "我想看", "我要听", "我要用", "开"
    )

    /**
     * 在多条候选里挑最像的那条。
     *
     * 单条候选的匹配是「各自算分取最高」，多候选是「每条各出最高，再取最高」——
     * 不能用第一条的分数去和第二条比细节，那样会把歧义带进来。
     */
    fun match(candidates: List<String>): VoiceIntent {
        var best: VoiceIntent = VoiceIntent.None
        var bestScore = 0.0
        for (text in candidates) {
            val intent = matchOne(text)
            val score = scoreOf(intent)
            if (score > bestScore) {
                best = intent
                bestScore = score
            }
        }
        return if (bestScore >= PinyinMatcher.MATCH_THRESHOLD) best else VoiceIntent.None
    }

    fun scoreOf(intent: VoiceIntent): Double = when (intent) {
        is VoiceIntent.Simple -> intent.score
        is VoiceIntent.CallName -> intent.score
        is VoiceIntent.Open -> intent.score
        VoiceIntent.None -> 0.0
    }

    /** 单条候选的匹配 */
    fun matchOne(text: String): VoiceIntent {
        val spoken = text.trim()
        if (spoken.isEmpty()) return VoiceIntent.None

        // 1. 「打电话给 X」最优先。
        //    必须抢在固定命令前面：否则「打电话给儿子」会被 CALL_PLAIN 的「打电话」以 1.0 抢走，
        //    结果变成打开拨号盘而不是直接拨给儿子。
        extractCallName(spoken)?.let { return VoiceIntent.CallName(it, 0.95) }

        // 2. 固定命令
        var best: VoiceIntent = VoiceIntent.None
        var bestScore = 0.0
        VoiceCommand.values().forEach { command ->
            command.phrases.forEach { phrase ->
                val score = PinyinMatcher.score(spoken, phrase)
                if (score > bestScore) {
                    best = VoiceIntent.Simple(command, score)
                    bestScore = score
                }
            }
        }

        // 3. 固定命令已经很有把握就别再猜「打开 X」了。
        //    「打开手电筒」里的「手电筒」会被 OPEN 抽成目标，但 TORCH 更准确。
        if (bestScore >= 0.9) return best

        // 4. 「打开 X」
        extractOpenTarget(spoken)?.let { target ->
            return VoiceIntent.Open(target, maxOf(bestScore, 0.8))
        }

        return best
    }

    /**
     * 从「打电话给儿子」「给儿子打电话」「打给儿子」里抽人名。
     * 抽不出来（只说「打电话」）就返回 null，交给 CALL_PLAIN。
     */
    private fun extractCallName(text: String): String? {
        if (!text.contains("打") && !text.contains("拨")) return null
        val at = text.indexOf("给")
        if (at < 0 || at >= text.length - 1) return null

        var name = text.substring(at + 1)
        // 先剥长的，再剥短的；否则「打个电话」会留下一个「个」
        listOf("打个电话", "打电话", "电话", "吧", "呀", "啊").forEach { suffix ->
            name = name.removeSuffix(suffix)
        }
        name = name.trim { !it.isLetterOrDigit() }
        return name.ifEmpty { null }
    }

    /** 从「打开微信」「我要看视频号」里抽目标 */
    private fun extractOpenTarget(text: String): String? {
        for (prefix in OPEN_PREFIXES) {
            if (text.startsWith(prefix) && text.length > prefix.length) {
                return trimTarget(text.substring(prefix.length))
            }
        }
        // 前缀不在开头时（「帮我打开微信」已经被上面的长前缀覆盖，这里兜底句中出现的）
        val at = text.indexOf("打开")
        if (at >= 0 && at + 2 < text.length) {
            return trimTarget(text.substring(at + 2))
        }
        return null
    }

    private fun trimTarget(raw: String): String? {
        var target = raw.trim { !it.isLetterOrDigit() }
        listOf("吧", "呀", "啊", "看看").forEach { suffix -> target = target.removeSuffix(suffix) }
        return target.ifEmpty { null }
    }
}
