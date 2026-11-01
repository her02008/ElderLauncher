package com.elder.launcher.ui

import android.app.Activity
import android.app.AlertDialog
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.elder.launcher.R
import com.elder.launcher.util.HomeBlock

/**
 * 大图标菜单弹窗：二级菜单、「更多」列表共用同一套样式。
 * 弹窗是给长辈看的，所以按钮做得和主页一样大；条目多时由 AlertDialog 自带的滚动容器滚动。
 */
object BlockMenuDialog {

    private const val COLUMNS = 3

    /** 色块的二级菜单 */
    fun show(activity: Activity, block: HomeBlock, onPick: (HomeBlock) -> Unit) {
        show(activity, block.label, block.subItems, onPick = onPick)
    }

    /** 任意大图标列表（例如主页收起的那一堆「更多」色块） */
    fun show(
        activity: Activity,
        title: CharSequence,
        items: List<HomeBlock>,
        hint: CharSequence = activity.getString(R.string.block_sub_hint),
        onPick: (HomeBlock) -> Unit
    ) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_block_menu, null)
        val titleView: TextView = view.findViewById(R.id.tvMenuTitle)
        titleView.text = title
        val hintView: TextView = view.findViewById(R.id.tvMenuHint)
        hintView.text = hint

        val container: LinearLayout = view.findViewById(R.id.llSubContainer)
        container.removeAllViews()

        val density = activity.resources.displayMetrics.density
        val gap = (4 * density).toInt()
        // 弹窗左右有边距和 padding，按屏宽估一个接近正方形的按钮高度；
        // 但不能低于「图标 + 一行文字」的高度，否则图标文字会被裁掉下半截
        val cellHeight = maxOf(
            ((activity.resources.displayMetrics.widthPixels - 96 * density) / COLUMNS).toInt(),
            minBlockCellHeight(activity)
        )

        val dialog = AlertDialog.Builder(activity).setView(view).create()

        // 限高 + 滚动：条目多的时候弹窗不会顶出屏幕
        val rows = (items.size + COLUMNS - 1) / COLUMNS
        val needed = rows * (cellHeight + gap * 2) + gap
        val maxHeight = activity.resources.displayMetrics.heightPixels - (260 * density).toInt()
        val scroll: ScrollView = view.findViewById(R.id.svSub)
        scroll.layoutParams = scroll.layoutParams.apply { height = minOf(needed, maxHeight) }

        var index = 0
        while (index < items.size) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            for (column in 0 until COLUMNS) {
                if (index + column < items.size) {
                    val item = items[index + column]
                    val cell = activity.layoutInflater.inflate(R.layout.item_home_button, row, false)
                    val lp = LinearLayout.LayoutParams(0, cellHeight, 1f)
                    lp.setMargins(gap, gap, gap, gap)
                    cell.layoutParams = lp
                    bindBlockCell(activity, cell, item)
                    cell.setOnClickListener {
                        dialog.dismiss()
                        onPick(item)
                    }
                    row.addView(cell)
                } else {
                    val spacer = View(activity)
                    spacer.layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                    row.addView(spacer)
                }
            }
            container.addView(row)
            index += COLUMNS
        }

        view.findViewById<Button>(R.id.btnSubCancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
