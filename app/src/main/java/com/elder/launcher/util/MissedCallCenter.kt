package com.elder.launcher.util

import android.content.Context
import androidx.core.app.NotificationManagerCompat

/**
 * 未接来电的存取中心（[MissedCallListenerService] 写，主页读）。
 *
 * 设计上的几条硬规则：
 * - **自己存一份**，不跟着系统通知走。老人顺手把通知栏清掉，提醒也不会跟着消失。
 * - 同一个号码只保留一条，重复来电累加次数；行数上限 [MAX_KEEP]，超过 [EXPIRE_MS] 自动过期。
 * - 「已弹过提醒」用 [MissedCall.notified] 记，回桌面再回来不会重复弹；只有新来电才重置成 false。
 *
 * [onNew] 是给前台的主页用的：监听服务收到新未接时直接喊它，不用等下一次 onResume。
 * 主页在 onPause 里把它置空，所以桌面不在前台时不会弹任何东西。
 */
object MissedCallCenter {

    /** 最多留几条（超出丢最旧的；主页最多只显示 3 行） */
    private const val MAX_KEEP = 10

    /** 超过一天就自动过期——第二天还挂着昨天的未接没有意义 */
    private const val EXPIRE_MS = 24 * 60 * 60 * 1000L

    /** 时间差小于这个值就认定是「同一条通知被系统重新投递」，不累加次数 */
    private const val SAME_EVENT_MS = 3_000L

    /** 排查记录最多留几条 */
    private const val DEBUG_KEEP = 8

    /** 主页在前台时挂在这里（onPause 置空） */
    var onNew: (() -> Unit)? = null

    /**
     * 是否已经拿到「通知使用权」。
     *
     * 没开的话监听服务根本不会被系统绑定，收不到任何通知——界面上必须如实告诉用户，
     * 不能让他以为功能已经生效。
     */
    fun isEnabled(ctx: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

    /** 还没处理、且没过期的未接来电，最近的排最前 */
    fun active(ctx: Context, now: Long = System.currentTimeMillis()): List<MissedCall> =
        PrefsManager.getMissedCalls(ctx)
            .filter { it.number.isNotEmpty() && now - it.time < EXPIRE_MS }
            .sortedByDescending { it.time }

    /**
     * 记一条未接来电。
     *
     * 同一个号码已经在列表里就合并：刷新时间、次数 +1（同一条通知重复投递时不加），
     * 并重新变回「未提醒」，下次回到桌面会再弹一次。
     */
    fun record(ctx: Context, number: String, name: String, postedAt: Long) {
        val target = number.trim()
        if (target.isEmpty()) return
        val now = System.currentTimeMillis()
        // postTime 偶尔会是 0 或者未来时间（系统时间被改过），这两种都按「现在」算
        val time = if (postedAt in 1..now) postedAt else now

        val list = active(ctx, now).toMutableList()
        val index = list.indexOfFirst { it.number == target }
        if (index >= 0) {
            val old = list[index]
            val sameEvent = time <= old.time + SAME_EVENT_MS
            list[index] = old.copy(
                name = name.ifEmpty { old.name },
                time = maxOf(time, old.time),
                count = if (sameEvent) old.count else old.count + 1,
                notified = if (sameEvent) old.notified else false
            )
        } else {
            list.add(MissedCall(target, name, time, 1, false))
        }
        PrefsManager.setMissedCalls(ctx, list.sortedByDescending { it.time }.take(MAX_KEEP))
        onNew?.invoke()
    }

    /** 这些号码已经弹过提醒了，别再重复弹 */
    fun markNotified(ctx: Context, numbers: List<String>) {
        val set = numbers.toSet()
        PrefsManager.setMissedCalls(
            ctx,
            PrefsManager.getMissedCalls(ctx).map {
                if (it.number in set && !it.notified) it.copy(notified = true) else it
            }
        )
    }

    /** 处理掉一条（回拨了，或点了「知道了」）。只是不再提醒，通话记录不动。 */
    fun remove(ctx: Context, number: String) {
        PrefsManager.setMissedCalls(
            ctx,
            PrefsManager.getMissedCalls(ctx)
                .filter { it.number != number }
                .sortedByDescending { it.time }
        )
    }

    fun clear(ctx: Context) {
        PrefsManager.clearMissedCalls(ctx)
    }

    // ---------------- 排查用 ----------------

    /**
     * 把抓到的通知原文记一行，供「高级设置 → 长按未接来电提醒」查看。
     *
     * 各家 ROM 的电话 App 通知格式差别很大，落到真机上光看代码猜不准；
     * 有了这几行原文就能照实际格式把匹配规则补准，而不是瞎改正则。
     */
    fun appendDebug(ctx: Context, line: String) {
        val old = PrefsManager.getMissedDebug(ctx)
            .split("\n\n")
            .filter { it.isNotBlank() }
        val merged = (listOf(line) + old).take(DEBUG_KEEP)
        PrefsManager.setMissedDebug(ctx, merged.joinToString("\n\n"))
    }

    fun debugLines(ctx: Context): List<String> =
        PrefsManager.getMissedDebug(ctx).split("\n\n").filter { it.isNotBlank() }
}
