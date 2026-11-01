package com.elder.launcher.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * 打开「收音机」。
 *
 * ## 先说清楚现实
 * 2020 年以后的手机大多**砍掉了 FM 芯片**，系统里连收音机 App 都没有；有的机型即使有，
 * 也要求插耳机当天天线。所以这个类只做「尽力而为」，找不到就返回 false，
 * 由调用方明确告诉老人「这台手机没有收音机」，**不能静默失败**。
 *
 * ## 三级探测（先精确，后模糊）
 * 1. 已知包名 —— 各家 ROM 的收音机包名
 * 2. 已知 Intent action —— 部分 ROM 私有
 * 3. 扫所有桌面应用，按标签里的「收音机 / 广播 / FM / Radio」找
 */
object RadioLauncher {

    private val KNOWN_PACKAGES = listOf(
        "com.android.fmradio",
        "com.mediatek.fmradio",
        "com.huawei.android.fmradio",
        "com.miui.fm",
        "com.oppo.fmradio",
        "com.heytap.fmradio",
        "com.vivo.fm",
        "com.bbk.fmradio",
        "com.samsung.android.fmm",
        "com.android.stk.fm"
    )

    private val KNOWN_ACTIONS = listOf(
        "android.intent.action.FM",
        "android.intent.action.RADIO"
    )

    private val LABEL_HINTS = listOf("收音机", "广播", "fm", "radio")

    /** 打开了返回 true；这台手机确实没有收音机返回 false */
    fun open(ctx: Context): Boolean {
        val pm = ctx.packageManager

        // 1. 已知包名
        for (pkg in KNOWN_PACKAGES) {
            val intent = pm.getLaunchIntentForPackage(pkg) ?: continue
            if (tryStart(ctx, intent)) return true
        }

        // 2. 已知 action
        for (action in KNOWN_ACTIONS) {
            val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null &&
                tryStart(ctx, intent)
            ) {
                return true
            }
        }

        // 3. 扫标签。QUERY_ALL_PACKAGES 已经在清单里声明过，这里能拿到全部应用
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(launcher, 0)
        for (info in apps) {
            val label = info.loadLabel(pm)?.toString()?.lowercase().orEmpty()
            if (LABEL_HINTS.none { label.contains(it) }) continue
            val intent = pm.getLaunchIntentForPackage(info.activityInfo.packageName) ?: continue
            if (tryStart(ctx, intent)) return true
        }

        return false
    }

    private fun tryStart(ctx: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }
}
