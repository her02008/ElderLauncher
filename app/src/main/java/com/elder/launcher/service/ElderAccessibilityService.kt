package com.elder.launcher.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Path
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.elder.launcher.R
import com.elder.launcher.util.AliveCenter

class ElderAccessibilityService : AccessibilityService() {

    companion object {
        private const val WECHAT_PKG = "com.tencent.mm"

        /** 单步最多等这么久，超时就算这一步失败 */
        private const val STEP_TIMEOUT_MS = 12_000L

        /** 两步之间的最小间隔，留给界面渲染时间 */
        private const val STEP_INTERVAL_MS = 600L

        /** 找不到底部「发现」时最多连按几次返回，用来退出微信里停留的聊天详情页 */
        private const val MAX_BACK = 3

        /** 拉起微信后至少等这么久才允许按返回，等界面切过来 */
        private const val BACK_GUARD_MS = 1_800L

        private const val MAX_TREE_DEPTH = 40
        private const val MAX_TREE_NODES = 3000

        @Volatile
        var instance: ElderAccessibilityService? = null

        fun isReady(): Boolean = instance != null

        fun triggerPowerDialog(): Boolean {
            val s = instance ?: return false
            return s.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        }

        fun triggerRecents(): Boolean {
            val s = instance ?: return false
            return s.performGlobalAction(GLOBAL_ACTION_RECENTS)
        }

        fun goHome(): Boolean {
            val s = instance ?: return false
            return s.performGlobalAction(GLOBAL_ACTION_HOME)
        }

        fun killBackground(ctx: Context): Int {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val pm = ctx.packageManager
            val selfPkg = ctx.packageName
            var count = 0
            try {
                val apps = pm.getInstalledApplications(0)
                for (app in apps) {
                    val pkg = app.packageName
                    if (pkg == selfPkg) continue
                    if (app.flags and ApplicationInfo.FLAG_SYSTEM != 0) continue
                    try {
                        am.killBackgroundProcesses(pkg)
                        count++
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
            return count
        }

        /**
         * 打开微信「视频号」。
         *
         * 微信没有开放视频号的 scheme（`weixin://channels` 在多数版本上根本不认，点了只会到微信首页
         * 或者毫无反应），所以这里改用无障碍：先把微信拉到前台，再自动点底部「发现」→「视频号」。
         *
         * @return false 表示无障碍服务没开启，调用方需要走别的兜底路径
         */
        fun openChannels(ctx: Context): Boolean {
            val service = instance ?: return false
            val intent = ctx.packageManager.getLaunchIntentForPackage(WECHAT_PKG) ?: return false
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            return try {
                ctx.startActivity(intent)
                service.startNavigate(listOf("发现", "视频号"))
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /** 待点击的文案序列，走完就清空 */
    private val steps = mutableListOf<String>()
    private var stepIndex = 0
    private var stepDeadline = 0L
    private var lastStepAt = 0L
    private var backRetries = 0
    private var navigatedAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        registerAlive()
    }

    /**
     * 解锁/亮屏时记一笔「有人在用手机」。
     *
     * 无障碍的窗口事件已经能覆盖大部分操作，但「解锁后没切窗口」（比如回到桌面后原地不动）
     * 只有这个广播能捕捉到。**这两个都是 protected broadcast**，用 EXPORTED 才收得到，
     * 第三方应用无法伪造。
     */
    private val aliveReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            AliveCenter.touch(this@ElderAccessibilityService)
        }
    }

    private var aliveRegistered = false

    private fun registerAlive() {
        if (aliveRegistered) return
        try {
            ContextCompat.registerReceiver(
                this,
                aliveReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_USER_PRESENT)
                    addAction(Intent.ACTION_SCREEN_ON)
                },
                ContextCompat.RECEIVER_EXPORTED
            )
            aliveRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun unregisterAlive() {
        if (!aliveRegistered) return
        try {
            unregisterReceiver(aliveReceiver)
        } catch (_: Exception) {
        }
        aliveRegistered = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 「恰了么？」只关心「有没有人在操作」，所以要在所有业务判断之前记一笔。
        // 内部有 60 秒节流，这里每来一个事件都调不会写盘。
        AliveCenter.touch(this)

        val ev = event ?: return
        if (stepIndex >= steps.size) return
        if (ev.packageName?.toString() != WECHAT_PKG) return

        val type = ev.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        val now = SystemClock.uptimeMillis()
        if (now > stepDeadline) {
            notifyFailed(steps[stepIndex])
            resetNavigation()
            return
        }
        if (now - lastStepAt < STEP_INTERVAL_MS) return

        val step = steps[stepIndex]
        if (clickByLabel(step)) {
            lastStepAt = now
            stepIndex++
            backRetries = 0
            if (stepIndex >= steps.size) {
                resetNavigation()
            } else {
                stepDeadline = now + STEP_TIMEOUT_MS
            }
            return
        }

        // 第一步找不到底部「发现」，多半是微信停在聊天详情页，先退几次回到微信首页；
        // 后续步骤找不到就老老实实等界面变化（不能乱按返回，否则会退出微信）
        if (stepIndex == 0 && backRetries < MAX_BACK && canPressBack(now)) {
            backRetries++
            lastStepAt = now
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    /**
     * 只在「微信已经真的在前台」时才敢按返回键：
     * 动画切换期间前台窗口可能还是我们自己，这时候按返回会把微信直接退掉。
     */
    private fun canPressBack(now: Long): Boolean {
        if (now - navigatedAt < BACK_GUARD_MS) return false
        val root = rootInActiveWindow ?: return false
        return root.packageName?.toString() == WECHAT_PKG
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        unregisterAlive()
        resetNavigation()
        return super.onUnbind(intent)
    }

    // ---------------- 导航内核 ----------------

    private fun startNavigate(sequence: List<String>) {
        steps.clear()
        steps.addAll(sequence)
        stepIndex = 0
        backRetries = 0
        lastStepAt = 0L
        navigatedAt = SystemClock.uptimeMillis()
        stepDeadline = navigatedAt + STEP_TIMEOUT_MS
    }

    private fun resetNavigation() {
        steps.clear()
        stepIndex = 0
        backRetries = 0
        lastStepAt = 0L
        navigatedAt = 0L
        stepDeadline = 0L
    }

    private fun notifyFailed(step: String) {
        val msg = if (step == "视频号") R.string.wx_channels_not_found else R.string.wx_channels_failed
        Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
    }

    /**
     * 在所有可交互窗口里找文案等于 label 的节点，点击它（或它最近的可点击父节点）。
     * 优先精确匹配，找不到才退化为「以 label 开头且长度接近」的宽松匹配。
     */
    private fun clickByLabel(label: String): Boolean {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { roots.add(it) }
        try {
            windows?.forEach { window ->
                val root = window?.root ?: return@forEach
                if (roots.none { it == root }) roots.add(root)
            }
        } catch (_: Exception) {
        }

        for (root in roots) {
            val exact = mutableListOf<AccessibilityNodeInfo>()
            val loose = mutableListOf<AccessibilityNodeInfo>()
            collectByLabel(root, label, exact, loose, 0, intArrayOf(0))
            val pool = if (exact.isNotEmpty()) exact else loose
            // 深度优先收集，越靠后的层级越深，优先点最里层那个
            for (i in pool.indices.reversed()) {
                if (clickNode(pool[i])) return true
            }
        }
        return false
    }

    private fun collectByLabel(
        node: AccessibilityNodeInfo,
        label: String,
        exact: MutableList<AccessibilityNodeInfo>,
        loose: MutableList<AccessibilityNodeInfo>,
        depth: Int,
        visited: IntArray
    ) {
        if (depth > MAX_TREE_DEPTH || visited[0] > MAX_TREE_NODES) return
        visited[0]++
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        when {
            text == label || desc == label -> exact.add(node)
            isLooseMatch(text, label) || isLooseMatch(desc, label) -> loose.add(node)
        }
        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            collectByLabel(child, label, exact, loose, depth + 1, visited)
        }
    }

    /** 宽松匹配：以目标开头且最多多两个字，用来兜「视频号·」这类带小尾巴的文案 */
    private fun isLooseMatch(value: String?, label: String): Boolean {
        if (value.isNullOrEmpty()) return false
        return value.startsWith(label) && value.length <= label.length + 2
    }

    /** 自己是可点击的就直接点，否则往上找最近的父节点；全都不行就用坐标手势兜底 */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable) {
                try {
                    if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                } catch (_: Exception) {
                }
            }
            current = current.parent
            depth++
        }
        return tapByGesture(node)
    }

    private fun tapByGesture(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        try {
            node.getBoundsInScreen(rect)
        } catch (_: Exception) {
            return false
        }
        if (rect.width() <= 0 || rect.height() <= 0) return false
        val path = Path().apply { moveTo(rect.exactCenterX(), rect.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 40))
            .build()
        return try {
            dispatchGesture(gesture, null, null)
        } catch (_: Exception) {
            false
        }
    }
}
