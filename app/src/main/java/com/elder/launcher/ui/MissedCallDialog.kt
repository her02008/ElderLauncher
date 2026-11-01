package com.elder.launcher.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.elder.launcher.R
import com.elder.launcher.util.ActionExecutor
import com.elder.launcher.util.MissedCall
import com.elder.launcher.util.MissedCallCenter
import com.elder.launcher.util.QuickContact
import com.elder.launcher.util.QuickIconStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 未接来电弹窗（方案 B：回到桌面时弹一次，不常驻占位）。
 *
 * 人数决定形态：
 * - **1 个人**：一行信息 + 底下大「回拨给 XX」和「知道了」；
 * - **2 个人以上**：标题变「N 个未接来电」，逐行列出（最多 3 行），
 *   每行自带绿色回拨按钮，底部只有「全部知道了」；超过 3 个折成一行「还有 N 个」。
 *
 * 处理掉一个人之后弹窗**不会整体关掉**，剩下的继续列着——不然「多人未接」就变成一个黑洞：
 * 点掉第一个，后面的人再也想不起来。只有全部处理完才消失。
 */
class MissedCallDialog(private val activity: Activity) {

    companion object {
        /** 弹窗里最多列几条，再多就折成「还有 N 个」 */
        private const val MAX_ROWS = 3
    }

    private var dialog: AlertDialog? = null
    private var calls: MutableList<MissedCall> = mutableListOf()

    fun show(list: List<MissedCall>) {
        calls = list.sortedByDescending { it.time }.toMutableList()
        render()
    }

    private fun render() {
        dialog?.dismiss()
        dialog = null
        if (calls.isEmpty()) return

        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_missed_call, null)
        val title = view.findViewById<TextView>(R.id.tvMissedTitle)
        val listBox = view.findViewById<LinearLayout>(R.id.listMissed)
        val more = view.findViewById<TextView>(R.id.tvMissedMore)
        val actions = view.findViewById<LinearLayout>(R.id.missedActions)

        val single = calls.size == 1
        title.text = if (single) {
            activity.getString(R.string.missed_call_title)
        } else {
            activity.getString(R.string.missed_call_title_multi, calls.size)
        }

        calls.take(MAX_ROWS).forEach { call ->
            listBox.addView(buildRow(call, withButton = !single))
        }

        val rest = calls.size - MAX_ROWS
        if (rest > 0) {
            more.text = activity.getString(R.string.missed_call_more, rest)
            more.visibility = View.VISIBLE
        }

        if (single) {
            val call = calls.first()
            actions.addView(
                actionButton(
                    activity.getString(R.string.missed_call_back_to, safeName(call)),
                    R.drawable.bg_call_btn,
                    Color.WHITE,
                    3f,
                    0
                ) { onCall(call) }
            )
            actions.addView(
                actionButton(
                    activity.getString(R.string.missed_call_got_it),
                    R.drawable.bg_dialog_cancel,
                    color(R.color.text_primary),
                    2f,
                    12
                ) { onGotIt(call) }
            )
        } else {
            actions.addView(
                actionButton(
                    activity.getString(R.string.missed_call_all_got_it),
                    R.drawable.bg_dialog_cancel,
                    color(R.color.text_primary),
                    1f,
                    0
                ) { onGotAll() }
            )
        }

        val built = AlertDialog.Builder(activity).setView(view).create()
        // 误点空白处不该把整条提醒悄悄关掉（老人会以为「我已经点过了」）
        built.setCanceledOnTouchOutside(false)
        built.show()
        dialog = built
    }

    private fun buildRow(call: MissedCall, withButton: Boolean): View {
        val row = LayoutInflater.from(activity).inflate(R.layout.item_missed_call, null, false)
        val name = safeName(call)
        row.findViewById<ImageView>(R.id.rowMissedAvatar).setImageBitmap(
            QuickIconStore.circleText(
                name.take(1),
                QuickContact.colorAt(name.hashCode()),
                dp(56)
            )
        )
        row.findViewById<TextView>(R.id.rowMissedName).text = name
        row.findViewById<TextView>(R.id.rowMissedTime).text = timeText(call)

        val button = row.findViewById<ImageView>(R.id.btnMissedRowCall)
        button.visibility = if (withButton) View.VISIBLE else View.GONE
        button.setOnClickListener { onCall(call) }
        // 一个人时没有行内按钮，整行都能点（目标更大，老人好按）
        if (!withButton) row.setOnClickListener { onCall(call) }
        return row
    }

    private fun actionButton(
        label: CharSequence,
        bgRes: Int,
        textColor: Int,
        weight: Float,
        marginStartDp: Int,
        onClick: () -> Unit
    ): TextView = TextView(activity).apply {
        layoutParams = LinearLayout.LayoutParams(0, dp(64), weight).apply {
            marginStart = dp(marginStartDp)
        }
        gravity = Gravity.CENTER
        setBackgroundResource(bgRes)
        setTextColor(textColor)
        textSize = 20f
        text = label
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        setPadding(dp(12), 0, dp(12), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    // ---------------- 动作 ----------------

    private fun onCall(call: MissedCall) {
        MissedCallCenter.remove(activity, call.number)
        calls.removeAll { it.number == call.number }
        // 还有别人就继续列着；都处理完了才关
        if (calls.isEmpty()) {
            dialog?.dismiss()
            dialog = null
        } else {
            render()
        }
        val granted = ContextCompat.checkSelfPermission(
            activity, Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) ActionExecutor.call(activity, call.number)
        else ActionExecutor.dial(activity, call.number)
    }

    private fun onGotIt(call: MissedCall) {
        MissedCallCenter.remove(activity, call.number)
        calls.removeAll { it.number == call.number }
        dialog?.dismiss()
        dialog = null
    }

    private fun onGotAll() {
        MissedCallCenter.clear(activity)
        calls.clear()
        dialog?.dismiss()
        dialog = null
    }

    // ---------------- 小工具 ----------------

    private fun safeName(call: MissedCall): String =
        call.displayName(activity.getString(R.string.missed_call_unknown))

    private fun timeText(call: MissedCall): String {
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(call.time))
        val times = if (call.count > 1) {
            activity.getString(R.string.missed_call_count, call.count)
        } else {
            ""
        }
        return if (times.isEmpty()) clock else "$clock · $times"
    }

    private fun color(res: Int): Int = ContextCompat.getColor(activity, res)

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
