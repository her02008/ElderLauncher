package com.elder.launcher.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat

/** 语音找人的结果 */
data class ContactHit(val name: String, val phone: String, val fromQuickDial: Boolean)

/**
 * 「打电话给 X」时按名字找人。
 *
 * 三个来源，优先级从高到低：
 * 1. **快捷拨号** —— 老人/家属自己配过的，一定是常用的人，同分时优先
 * 2. **紧急联系人** —— 名字可能是「儿子」这种称呼
 * 3. **系统通讯录** —— 兜底，量最大
 *
 * 姓名用 [PinyinMatcher] 的人名转换器（多音字按姓名习惯读），阈值比命令词低：
 * 姓名同音字太多（张伟 / 章伟 / 张玮），要求太高会一个都找不到。
 */
object ContactFinder {

    /** 最多给几个候选，太多老人反而挑不出来 */
    private const val LIMIT = 5

    private class Scored(val hit: ContactHit, val score: Double)

    fun find(ctx: Context, spoken: String): List<ContactHit> {
        if (spoken.isBlank()) return emptyList()

        val collected = ArrayList<Scored>()

        // 1. 快捷拨号
        PrefsManager.getQuickContacts(ctx).forEach { contact ->
            val name = contact.displayName()
            if (contact.phone.isBlank() || name.isBlank()) return@forEach
            val score = PinyinMatcher.score(name, spoken, forName = true)
            if (score >= PinyinMatcher.NAME_THRESHOLD) {
                // 微小加权，同分时快捷拨号排前面
                collected.add(Scored(ContactHit(name, contact.phone, true), score + 0.02))
            }
        }

        // 2. 紧急联系人
        val (sosName, sosPhone) = PrefsManager.getSosContact(ctx)
        if (sosPhone.isNotBlank()) {
            val label = sosName.ifBlank { "紧急联系人" }
            val score = PinyinMatcher.score(label, spoken, forName = true)
            if (score >= PinyinMatcher.NAME_THRESHOLD) {
                collected.add(Scored(ContactHit(label, sosPhone, false), score))
            }
        }

        // 3. 通讯录（没授权就跳过，不影响前两个来源）
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            readContacts(ctx, spoken, collected)
        }

        // 按号码去重：同一个人可能既是快捷拨号又在通讯录里，留下分高的那条
        val byNumber = LinkedHashMap<String, Scored>()
        collected.sortedByDescending { it.score }.forEach { item ->
            val key = normalizeNumber(item.hit.phone)
            if (key.isEmpty()) return@forEach
            if (byNumber[key] == null) byNumber[key] = item
        }

        return byNumber.values
            .sortedByDescending { it.score }
            .take(LIMIT)
            .map { it.hit }
    }

    private fun readContacts(ctx: Context, spoken: String, out: MutableList<Scored>) {
        val nameCol = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
        val numberCol = ContactsContract.CommonDataKinds.Phone.NUMBER
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(nameCol, numberCol),
                null, null, null
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow(nameCol)
                val numberIndex = cursor.getColumnIndexOrThrow(numberCol)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex) ?: continue
                    val number = cursor.getString(numberIndex) ?: continue
                    if (number.isBlank()) continue
                    // 便宜的预筛：长度差太多不可能是同一个人，省掉一次拼音转换
                    if (name.length > spoken.length + 3) continue
                    val score = PinyinMatcher.score(name, spoken, forName = true)
                    if (score >= PinyinMatcher.NAME_THRESHOLD) {
                        out.add(Scored(ContactHit(name, number, false), score))
                    }
                }
            }
        } catch (_: Exception) {
            // 通讯录读不了（权限被撤、厂商限制）就当没有，不影响快捷拨号那条路
        }
    }

    /** 只留数字、去掉 86 前缀，用于判断是不是同一个号 */
    private fun normalizeNumber(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return digits.removePrefix("86").takeLast(11)
    }
}
