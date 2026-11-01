package com.elder.launcher.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.elder.launcher.R
import com.elder.launcher.util.BlockCatalog
import com.elder.launcher.util.HomeBlock

/**
 * 主页功能色块。
 * - 列数由外部按当前色块数量传入（1~3 列自适应），数量少时色块自动变大
 * - 色块宽度按屏幕宽度均分计算，高度不超过上限，避免只有 1 个色块时撑满整屏
 * - 色块数量为 0 时显示一个「添加功能块」占位块
 * - 色块多于 [BlockCatalog.MAX_GRID_SLOTS] 个时只显示前 [BlockCatalog.VISIBLE_BLOCKS] 个，
 *   最后一格变成「更多」（右上角角标显示收起了几个），其余色块交给主页用大图标弹窗列出
 */
class HomeAdapter(
    private val context: Context,
    private val onClick: (HomeBlock) -> Unit,
    private val onLongClick: (HomeBlock) -> Unit,
    private val onAddClick: () -> Unit,
    private val onMoreClick: () -> Unit
) : RecyclerView.Adapter<HomeAdapter.VH>() {

    private val items = mutableListOf<HomeBlock>()
    private var hiddenCount = 0
    private var cellWidth = 0
    private var cellHeight = 0

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val label: TextView = v.findViewById(R.id.label)
        val badge: TextView = v.findViewById(R.id.badge)
    }

    fun submit(list: List<HomeBlock>, columns: Int) {
        items.clear()
        items.addAll(BlockCatalog.homeVisible(list))
        hiddenCount = BlockCatalog.homeHidden(list).size
        val safeColumns = columns.coerceAtLeast(1)
        val available = context.resources.displayMetrics.widthPixels - dp(GRID_MARGIN_DP * 2)
        val cell = available / safeColumns
        cellWidth = cell - dp(GAP_DP * 2)
        // 高度按宽度推，但不能低于「图标 + 一行文字」的高度，否则居中内容会被格子裁掉（文字只剩上半截）
        cellHeight = minOf(cellWidth, dp(MAX_CELL_HEIGHT_DP))
            .coerceAtLeast(minBlockCellHeight(context))
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(context).inflate(R.layout.item_home_button, parent, false)
        val lp = RecyclerView.LayoutParams(cellWidth, cellHeight)
        lp.setMargins(dp(GAP_DP), dp(GAP_DP), dp(GAP_DP), dp(GAP_DP))
        v.layoutParams = lp
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val view = holder.itemView
        if (items.isEmpty()) {
            val placeholder = HomeBlock(
                label = context.getString(R.string.block_add),
                color = ContextCompat.getColor(context, R.color.btn_settings)
            )
            bindBlockCell(context, view, placeholder, R.drawable.ic_add)
            view.setOnClickListener { onAddClick() }
            view.setOnLongClickListener { false }
            return
        }
        // 最后一格留给「更多」
        if (position >= items.size) {
            bindMoreCell(view)
            return
        }
        val item = items[position]
        bindBlockCell(context, view, item)
        view.setOnClickListener { onClick(item) }
        view.setOnLongClickListener { onLongClick(item); true }
    }

    private fun bindMoreCell(view: View) {
        val more = HomeBlock(
            label = context.getString(R.string.block_more),
            color = ContextCompat.getColor(context, R.color.btn_settings)
        )
        bindBlockCell(
            context,
            view,
            more,
            R.drawable.ic_more,
            context.getString(R.string.block_more_badge, hiddenCount)
        )
        view.setOnClickListener { onMoreClick() }
        view.setOnLongClickListener { false }
    }

    override fun getItemCount(): Int =
        if (items.isEmpty()) 1 else items.size + (if (hiddenCount > 0) 1 else 0)

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        /** 与 activity_main.xml 里 gridActions 的 layout_margin 保持一致 */
        private const val GRID_MARGIN_DP = 8
        private const val GAP_DP = 4
        private const val MAX_CELL_HEIGHT_DP = 180
    }
}
