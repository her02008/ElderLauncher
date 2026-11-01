package com.elder.launcher.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.elder.launcher.R
import com.elder.launcher.util.AppUtils
import com.elder.launcher.util.BlockCatalog
import com.elder.launcher.util.HomeBlock
import com.elder.launcher.util.PinnedShortcuts
import kotlin.math.ceil

/** 图标被压到的最小尺寸，再小就看不清了 */
private const val MIN_ICON_DP = 26

/** 文字行高的保险余量 */
private const val LABEL_SLACK_DP = 4

/**
 * 把 HomeBlock 渲染成一块彩色方块（主页网格 / 二级菜单弹窗 / 「更多」弹窗共用）。
 *
 * @param iconResOverride 非 0 时强制使用该图标（用于「添加功能块」「更多」这类占位块）
 * @param badgeText 右上角角标文字，为空时按是否有二级菜单自动显示 ▾
 */
fun bindBlockCell(
    context: Context,
    cell: View,
    block: HomeBlock,
    iconResOverride: Int = 0,
    badgeText: CharSequence? = null
) {
    val icon: ImageView = cell.findViewById(R.id.icon)
    val label: TextView = cell.findViewById(R.id.label)
    val badge: TextView = cell.findViewById(R.id.badge)
    val density = context.resources.displayMetrics.density

    cell.background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 22f * density
        setColor(block.color)
    }

    fitIcon(context, cell, icon, label)
    bindLabel(label, block.label)

    when {
        iconResOverride != 0 -> icon.setImageResource(iconResOverride)
        block.isBuiltin -> {
            val action = block.action
            if (action != null) icon.setImageResource(BlockCatalog.iconRes(action))
            else icon.setImageResource(R.drawable.ic_settings)
        }
        // 微信小程序没有可用的应用图标，统一用内置的小程序图标
        block.isMini -> icon.setImageResource(R.drawable.ic_wxapp)
        // 桌面快捷方式：优先用快捷方式自己的图标（小程序/网页图标），读不到退到宿主 App 图标
        block.isShortcut -> {
            val drawable = PinnedShortcuts.iconOf(context, block.pkg)
                ?: AppUtils.iconOf(context, PinnedShortcuts.pkgOf(block.pkg))
            if (drawable != null) icon.setImageDrawable(drawable)
            else icon.setImageResource(R.drawable.ic_settings)
        }
        else -> {
            val drawable = AppUtils.iconOf(context, block.pkg)
            if (drawable != null) icon.setImageDrawable(drawable)
            else icon.setImageResource(R.drawable.ic_settings)
        }
    }

    badge.text = badgeText ?: context.getString(R.string.block_sub_badge)
    badge.visibility = if (badgeText != null || block.hasSub) View.VISIBLE else View.GONE
}

/**
 * 色块完整放下图标 + 一行文字所需的最小高度。
 *
 * 格子的高度是按宽度推出来的，窄屏或者系统「显示大小 / 字体」调大之后会不够高，
 * 居中的内容会被格子裁掉，表现就是**图标文字只显示上半截**。所以高度不足时统一以这个值为准。
 */
fun minBlockCellHeight(context: Context): Int {
    val probe = LayoutInflater.from(context).inflate(R.layout.item_home_button, null, false)
    val label = probe.findViewById<TextView>(R.id.label)
    val icon = probe.findViewById<ImageView>(R.id.icon)
    val iconBase = icon.layoutParams?.height
        ?.takeIf { it > 0 }
        ?: context.resources.getDimensionPixelSize(R.dimen.icon_normal)
    return reservedHeight(context, probe, label) + iconBase
}

/**
 * 空间分配：格子不够高时先把图标压小，保证文字完整显示——文字被裁掉下半截，
 * 比图标小一圈难看得多，更影响长辈认图标。
 *
 * 上限用资源里的基准尺寸，**不能读 [ImageView.getLayoutParams] 的当前值**：
 * RecyclerView 复用时会拿着上一次压小的结果当新基准，越压越小。
 */
private fun fitIcon(context: Context, cell: View, icon: ImageView, label: TextView) {
    val base = context.resources.getDimensionPixelSize(R.dimen.icon_normal)
    val cellHeight = cell.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT
    val target = if (cellHeight > 0) {
        (cellHeight - reservedHeight(context, cell, label))
            .coerceIn(dp(context, MIN_ICON_DP), base)
    } else {
        base
    }
    val lp = icon.layoutParams
    if (lp != null && lp.height != target) {
        lp.height = target
        icon.layoutParams = lp
    }
}

/** 除图标之外，色块里所有东西（上下内边距 + 图标与文字的间距 + 一行文字）需要的高度 */
private fun reservedHeight(context: Context, cell: View, label: TextView): Int {
    val marginTop = (label.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
    val fm = label.paint.fontMetrics
    val lineHeight = ceil((fm.bottom - fm.top).toDouble()).toInt()
    return cell.paddingTop + cell.paddingBottom + marginTop + lineHeight + dp(context, LABEL_SLACK_DP)
}

/**
 * 文字能完整显示就不动（居中），放不下才切到左对齐并打开跑马灯左右滚动。
 * 只有溢出的那几个色块才会跑动画，不影响列表性能。
 */
private fun bindLabel(label: TextView, text: String) {
    label.text = text
    label.gravity = Gravity.CENTER
    label.isSelected = false
    label.post {
        // 期间可能已经被复用去显示别的色块了，这时不要再改
        if (label.text.toString() != text) return@post
        val available = label.width - label.paddingStart - label.paddingEnd
        if (available <= 0) return@post
        if (label.paint.measureText(text) > available) {
            label.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            label.isSelected = true
        }
    }
}

private fun dp(context: Context, value: Int): Int =
    (value * context.resources.displayMetrics.density).toInt()
