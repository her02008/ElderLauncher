package com.elder.launcher.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 发一条普通短信（不是打开短信编辑界面）。
 *
 * 「恰了么？」的预警必须用短信而不是推送：
 * - 老人手机没网也能发出去（有信号就行）
 * - 子女不用装任何 App
 * - 运营商级可靠，不会被系统后台限制掐掉
 *
 * 代价是 0.1 元/条，所以调用方都有频率上限（见 [AliveCenter]）。
 */
object SmsSender {

    fun send(ctx: Context, phone: String, text: String): Boolean {
        if (phone.isBlank() || text.isBlank()) return false
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("SmsSender", "SEND_SMS not granted")
            return false
        }
        return try {
            val manager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                ctx.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            } ?: return false

            val parts = manager.divideMessage(text)
            if (parts.size > 1) {
                // 长短信必须分片，否则运营商会截断，家长只收到半句话
                manager.sendMultipartTextMessage(phone, null, parts, null, null)
            } else {
                manager.sendTextMessage(phone, null, text, null, null)
            }
            true
        } catch (e: Exception) {
            Log.e("SmsSender", "send failed", e)
            false
        }
    }
}
