package com.elder.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.widget.TextView
import android.widget.Toast
import com.elder.launcher.R
import com.elder.launcher.util.RemoteCenter
import com.elder.launcher.util.RemoteCommand

/**
 * 远程设置落到老人眼前的两个弹窗：
 * 1. 危险指令的**本机确认**（改紧急联系人 / 换色块 / 换快捷拨号）
 * 2. 子女留言
 *
 * 为什么确认必须在本机：远程改紧急联系人等于能让「救命」打到陌生号码上，
 * 远程改快捷拨号等于能让老人一点就拨给别人。这类改动不能静默生效，
 * 否则这个功能就从「协助」变成了「家庭成员之间的远程控制通道」。
 *
 * 触发点是 [com.elder.launcher.MainActivity.onResume]——只有真回到桌面才弹，
 * 老人正在打电话或看别的应用时不该被我们盖一层。
 */
object RemotePrompt {

    @Volatile
    private var showing = false

    fun showIfNeeded(activity: Activity) {
        if (showing) return
        if (activity.isFinishing || activity.isDestroyed) return

        val pending = RemoteCenter.pendingList(activity).filter { !it.expired() }
        if (pending.isNotEmpty()) {
            showConfirm(activity, pending)
            return
        }

        val message = RemoteCenter.takeMessage(activity) ?: return
        showMessage(activity, message)
    }

    private fun showConfirm(activity: Activity, list: List<RemoteCommand.Incoming>) {
        showing = true
        val body = list.joinToString("\n\n") { RemoteCommand.describe(activity, it) }

        AlertDialog.Builder(activity)
            .setTitle(R.string.remote_confirm_title)
            .setMessage(body + "\n\n" + activity.getString(R.string.remote_confirm_hint))
            .setPositiveButton(R.string.remote_confirm_yes) { dialog, _ ->
                val done = RemoteCenter.resolvePending(
                    activity,
                    list.map { it.id }.toSet(),
                    approve = true
                )
                dialog.dismiss()
                Toast.makeText(
                    activity,
                    if (done > 0) R.string.remote_confirm_applied else R.string.remote_confirm_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton(R.string.remote_confirm_no) { dialog, _ ->
                RemoteCenter.resolvePending(activity, list.map { it.id }.toSet(), approve = false)
                dialog.dismiss()
            }
            // 误点空白不能让它悄悄消失：留着直到老人做出选择
            .setCancelable(false)
            .setOnDismissListener { showing = false }
            .show()
    }

    private fun showMessage(activity: Activity, text: String) {
        showing = true
        val view = TextView(activity).apply {
            this.text = text
            textSize = 26f
            setPadding(dp(activity, 24), dp(activity, 20), dp(activity, 24), dp(activity, 20))
            setLineSpacing(dp(activity, 6).toFloat(), 1f)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.remote_msg_title)
            .setView(view)
            .setPositiveButton(R.string.remote_msg_ok, null)
            .setCancelable(false)
            .setOnDismissListener { showing = false }
            .show()
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
