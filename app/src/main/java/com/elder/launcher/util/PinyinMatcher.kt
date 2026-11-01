package com.elder.launcher.util

import android.icu.text.Transliterator
import java.text.Normalizer
import kotlin.math.max

/**
 * 拼音模糊匹配：语音命令能不能用，全看这一层。
 *
 * ## 为什么不用第三方拼音库
 * `tinypinyin` / `pinyin4j` 都要额外依赖，前者在 mavenCentral 上拉不到。
 * 而 Android 从 **API 24** 起就自带 `android.icu.text.Transliterator`，`Han-Latin` 转换器
 * 就是「汉字 → 带声调拼音」。零依赖、零体积增长，minSdk 24 正好够。
 *
 * ## 为什么要比拼音而不是比汉字
 * 老人说话被识别出来常是同音错字：「打电话」→「打电发」、「收音机」→「收银机」。
 * 汉字一个都对不上，但**发音是对的**。所以先转拼音，再做归一化 + 编辑距离。
 *
 * ## 归一化规则（方言/口音的常见混淆）
 * - 平翘舌：`zh→z`、`ch→c`、`sh→s`
 * - 前后鼻音：`ing→in`、`eng→en`、`ang→an`
 * - 鼻音边音：声母 `n→l`
 *
 * 规则是**逐音节**做的，不是整串替换——整串 `replace("n","l")` 会把 `an/en/in` 里的韵尾也改掉。
 * 所以按字转拼音，一个字一个音节，天然有边界。
 */
object PinyinMatcher {

    /** 低于这个分就算没听出来。真机上调这一条即可。 */
    const val MATCH_THRESHOLD = 0.62

    /** 人名匹配放宽一点：姓名同音字比命令词多得多（「张伟」「章伟」「张玮」） */
    const val NAME_THRESHOLD = 0.55

    private val HAN = '\u4e00'..'\u9fff'

    private val hanLatin: Transliterator? by lazy { safeInstance("Han-Latin") }

    /** 姓名专用转换器：多音字按姓氏/人名习惯读（「单」读 shan 而不是 dan） */
    private val hanLatinNames: Transliterator? by lazy { safeInstance("Han-Latin/Names") }

    private fun safeInstance(id: String): Transliterator? = try {
        Transliterator.getInstance(id)
    } catch (_: Exception) {
        null
    }

    /**
     * 转换结果缓存。识别结果重复率高（老人反复说同一句），缓存能省掉大量 ICU 调用。
     * 上限 256 条，超了淘汰最久未用的。
     */
    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > 256
    }

    /** 整串拼音（无分隔、已归一化）。[forName] 为 true 时按人名读音转。 */
    @Synchronized
    fun pinyin(text: String, forName: Boolean = false): String {
        val key = (if (forName) "1" else "0") + text
        cache[key]?.let { return it }
        val value = syllables(text, forName).joinToString("")
        cache[key] = value
        return value
    }

    /** 每个音节的首字母串，用于「说得太短」时的兜底匹配 */
    fun initials(text: String, forName: Boolean = false): String =
        syllables(text, forName).mapNotNull { it.firstOrNull() }.joinToString("")

    /**
     * 两段话的相似度，0~1。
     *
     * 判定顺序（先严后宽）：
     * 1. 原文直接包含 → 1.0
     * 2. 拼音完全相同 → 0.95
     * 3. 拼音包含（口头语多了几个字）→ 0.9
     * 4. 拼音编辑距离归一化
     * 5. 首字母相同 → 0.6（兜底，低于阈值，只有作为最高分时才可能用到）
     */
    fun score(spoken: String, target: String, forName: Boolean = false): Double {
        val s = clean(spoken)
        val t = clean(target)
        if (s.isEmpty() || t.isEmpty()) return 0.0
        if (s.contains(t)) return 1.0

        val ps = pinyin(s, forName)
        val pt = pinyin(t, forName)
        if (ps.isEmpty() || pt.isEmpty()) return 0.0
        if (ps == pt) return 0.95
        if (ps.contains(pt)) return 0.9

        val distance = levenshtein(ps, pt)
        val ratio = 1.0 - distance.toDouble() / max(ps.length, pt.length)
        // 首字母单独算一次（通讯录里几千条姓名，这里省掉一次重复转换）
        val spokenInitials = initials(s, forName)
        val targetInitials = initials(t, forName)
        val sameInitials = spokenInitials == targetInitials && targetInitials.isNotEmpty()
        return max(ratio, if (sameInitials) 0.6 else 0.0)
    }

    // ---------------- 内部实现 ----------------

    private fun clean(text: String): String =
        text.filter { it.isLetterOrDigit() }.lowercase()

    /** 逐字转拼音；非汉字但属于字母数字的保留（「微信」转完是 wei xin，「WiFi」原样保留） */
    private fun syllables(text: String, forName: Boolean): List<String> {
        val transliterator = if (forName) hanLatinNames else hanLatin
        val out = ArrayList<String>(text.length)
        for (ch in text) {
            when {
                ch in HAN -> {
                    val raw = if (transliterator != null) {
                        try {
                            transliterator.transliterate(ch.toString())
                        } catch (_: Exception) {
                            ch.toString()
                        }
                    } else {
                        ch.toString()
                    }
                    val folded = fold(raw)
                    if (folded.isNotEmpty()) out.add(folded)
                }
                ch.isLetterOrDigit() -> out.add(ch.lowercaseChar().toString())
            }
        }
        return out
    }

    /** 单个音节：去声调 → 小写 → 模糊音归并 */
    private fun fold(raw: String): String {
        var s = stripTones(raw).lowercase().filter { it.isLetterOrDigit() }
        if (s.isEmpty()) return s

        s = when {
            s.startsWith("zh") -> "z" + s.substring(2)
            s.startsWith("ch") -> "c" + s.substring(2)
            s.startsWith("sh") -> "s" + s.substring(2)
            s.startsWith("n") -> "l" + s.substring(1)   // 声母 n→l
            else -> s
        }
        // 韵母的前后鼻音合流。按音节做，不会误伤声母后的 n（那个已经被上面处理过）
        s = s.replace("ing", "in").replace("eng", "en").replace("ang", "an")
        return s
    }

    /**
     * 去掉声调符号，`ü` 转成 `v`。
     *
     * 用 NFD 分解：带调的字母会被拆成「基本字母 + 组合符号」，丢掉组合符号即可。
     * `ü` 的分解是 `u` + diaeresis，直接丢弃会和「u」撞车（女/努 都变 nu），
     * 所以特判成 `v` —— 也是拼音输入法的通行写法。
     */
    private fun stripTones(input: String): String {
        val nfd = Normalizer.normalize(input, Normalizer.Form.NFD)
        val sb = StringBuilder(nfd.length)
        var i = 0
        while (i < nfd.length) {
            val c = nfd[i]
            val umlaut = i + 1 < nfd.length && nfd[i + 1].code == 0x0308
            when {
                c.code in 0x0300..0x036F -> Unit          // 独立的组合符号，丢掉
                umlaut && (c == 'u' || c == 'U') -> {
                    sb.append('v')
                    i++                                   // 连 diaeresis 一起跳过
                }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    /** 标准 Levenshtein，只留两行，避免长串时爆内存 */
    private fun levenshtein(a: String, b: String): Int {
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(
                    cur[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost
                )
            }
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }
}
