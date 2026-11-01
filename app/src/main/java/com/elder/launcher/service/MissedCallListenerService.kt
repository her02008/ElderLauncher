package com.elder.launcher.service

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import com.elder.launcher.util.MissedCallCenter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 从系统通知里捞「未接来电」。
 *
 * **为什么走通知而不是通话记录**：Android 10 起 `READ_CALL_LOG` 是硬限制权限，
 * 非默认电话应用连设置里都勾不上；为了读个未接去抢默认电话应用更不可能。
 * 电话 App 自己会发一条未接通知，里面有名字、号码和时间——借它最省事，
 * 代价只是家属要在系统设置里开一次「通知使用权」。
 *
 * 匹配规则做了多重兜底：先看 `category == CATEGORY_MISSED_CALL`（Android 8+ 标准写法），
 * 再退回标题/正文里的「未接来电 / 未接电话 / missed call」。各家 ROM 写法不一，
 * 真机对不上时看「高级设置 → 长按未接来电提醒」里的原文记录，再往这里补规则。
 */
class MissedCallListenerService : NotificationListenerService() {

    companion object {
        private val MISSED_WORDS = Regex(
            "未接来电|未接电话|未接听的来电|missed call",
            RegexOption.IGNORE_CASE
        )

        /** 号码：可带 + 和空格/短横线/括号，实际判定时再看数字位数 */
        private val PHONE = Regex("[+]?[0-9][0-9\\s\\-()]{4,24}")

        /** 正文里的时间，如 10:32 / 10：32，提名字时要先去掉 */
        private val CLOCK = Regex("[0-9]{1,2}[:：][0-9]{2}")

        /** 去掉关键词、号码、时间后如果只剩这些字，那不是名字 */
        private val NOT_A_NAME = Regex("^[0-9\\s个次条]*$")

        /** 包名里带这些的，基本就是电话/联系人应用（默认拨号包名另算） */
        private val DIALER_HINTS = listOf("dialer", "incall", "phone", "contacts", "telecom")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val posted = sbn ?: return
        // 任何一条通知解析失败都不该让监听服务崩掉（崩了系统会解绑，表现为功能整个失效）
        try {
            handle(posted)
        } catch (_: Exception) {
        }
    }

    private fun handle(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        // 通话中/正在拨号那条是常驻通知，不是未接
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (!isDialerLike(sbn.packageName)) return

        val parts = texts(n)
        val missed = isMissed(n, parts)
        val number = numberIn(parts)
        val name = nameIn(parts)

        // 只对电话类通知记排查日志，免得把微信消息也塞进来
        appendDebug(sbn, n, missed, number, name)

        if (!missed || number.isEmpty()) return
        MissedCallCenter.record(this, number, name, sbn.postTime)
    }

    // ---------------- 判定 ----------------

    private fun isMissed(n: Notification, parts: List<String>): Boolean {
        if (n.category == Notification.CATEGORY_MISSED_CALL) return true
        return parts.any { MISSED_WORDS.containsMatchIn(it) }
    }

    private fun isDialerLike(pkg: String): Boolean {
        val default = defaultDialer()
        if (!default.isNullOrEmpty() && pkg == default) return true
        val lower = pkg.lowercase(Locale.ROOT)
        return DIALER_HINTS.any { lower.contains(it) }
    }

    private fun defaultDialer(): String? = try {
        (getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage
    } catch (_: Exception) {
        null
    }

    /** 通知里所有可读文字：标题、正文、大文本、副标题都收，顺序即优先级 */
    private fun texts(n: Notification): List<String> {
        val extras = n.extras ?: return emptyList()
        return listOf(
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG),
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT),
            extras.getCharSequence(Notification.EXTRA_INFO_TEXT)
        )
            .mapNotNull { it?.toString()?.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun numberIn(parts: List<String>): String {
        parts.forEach { part ->
            PHONE.findAll(part).forEach { match ->
                if (match.value.count { it.isDigit() } in 5..20) return normalize(match.value)
            }
        }
        return ""
    }

    /** 交给拨号只认「+数字」，空格短横线括号一律去掉 */
    private fun normalize(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return if (raw.trimStart().startsWith("+")) "+$digits" else digits
    }

    /**
     * 从剩余文字里猜名字。
     *
     * 先把「未接来电」这类关键词、号码、时间都去掉，剩下的才可能是名字；
     * 剩下的如果只有数字/「个/次」这种量词，说明原文是「3 个未接来电」，不是名字。
     */
    private fun nameIn(parts: List<String>): String {
        parts.forEach { raw ->
            var text = raw
                .replace(MISSED_WORDS, " ")
                .replace(PHONE, " ")
                .replace(CLOCK, " ")
            text = text.replace(Regex("\\s+"), " ")
                .trim()
                .trim('，', ',', '·', '-', ':', '：', '（', '）', '(', ')', '、')
            if (text.isEmpty()) return@forEach
            if (NOT_A_NAME.matches(text)) return@forEach
            if (text.count { it.isLetter() } == 0) return@forEach
            if (text.length > 20) return@forEach
            // 万一号码没被 PHONE 完整吃掉，别把号码当名字
            if (text.filter { it.isDigit() }.length >= 5) return@forEach
            return text
        }
        return ""
    }

    private fun appendDebug(
        sbn: StatusBarNotification,
        n: Notification,
        missed: Boolean,
        number: String,
        name: String
    ) {
        val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(sbn.postTime))
        val head = if (missed && number.isNotEmpty()) "✓ 已识别" else "✗ 未识别"
        val body = texts(n).joinToString(" ｜ ").ifEmpty { "（没有文字）" }
        MissedCallCenter.appendDebug(
            this,
            "$head  $stamp\n${sbn.packageName}  [${n.category}]\n$body\n号码=$number  名字=$name"
        )
    }
}
