package com.elder.launcher.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.elder.launcher.R
import com.elder.launcher.util.AppUtils
import com.elder.launcher.util.BlockCatalog
import com.elder.launcher.util.HomeBlock
import com.elder.launcher.util.PinnedShortcuts

/**
 * 「主页功能块」管理列表：点一行改内容，右侧 ↑ ↓ 调顺序、× 删除。
 * 列表顺序就是主页顺序，超出主页 9 格的部分会在副标题里标出「在「更多」里」。
 *
 * 注意：这里持有的是外面传进来的**同一个列表实例**，这样长按拖动时
 * 直接改动列表再 notifyItemMoved 就能生效，不需要重新拷贝一份。
 */
class BlockRowAdapter(
    private val context: Context,
    private val onClick: (HomeBlock) -> Unit,
    private val onRemove: (HomeBlock) -> Unit,
    private val onMoveUp: (Int) -> Unit,
    private val onMoveDown: (Int) -> Unit
) : RecyclerView.Adapter<BlockRowAdapter.VH>() {

    private var items: List<HomeBlock> = emptyList()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.rowIcon)
        val label: TextView = v.findViewById(R.id.rowLabel)
        val sub: TextView = v.findViewById(R.id.rowSub)
        val moveUp: ImageButton = v.findViewById(R.id.btnMoveUp)
        val moveDown: ImageButton = v.findViewById(R.id.btnMoveDown)
        val remove: ImageButton = v.findViewById(R.id.btnRemove)
    }

    fun submit(list: List<HomeBlock>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(context).inflate(R.layout.item_home_block_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.icon.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(item.color)
        }
        if (item.isBuiltin) {
            val action = item.action
            if (action != null) holder.icon.setImageResource(BlockCatalog.iconRes(action))
            else holder.icon.setImageResource(R.drawable.ic_settings)
        } else if (item.isMini) {
            // 小程序没有可用的应用图标，统一用内置的小程序图标
            holder.icon.setImageResource(R.drawable.ic_wxapp)
        } else if (item.isShortcut) {
            val drawable = PinnedShortcuts.iconOf(context, item.pkg)
                ?: AppUtils.iconOf(context, PinnedShortcuts.pkgOf(item.pkg))
            holder.icon.setImageDrawable(drawable)
        } else {
            holder.icon.setImageDrawable(AppUtils.iconOf(context, item.pkg))
        }
        holder.label.text = item.label
        holder.sub.text = subtitle(item, position)
        holder.itemView.setOnClickListener { onClick(item) }

        // 第一行不能再上移，最后一行不能再下移，置灰避免点了没反应
        holder.moveUp.alpha = if (position == 0) DISABLED_ALPHA else 1f
        holder.moveUp.isEnabled = position > 0
        holder.moveUp.setOnClickListener { onMoveUp(holder.bindingAdapterPosition) }

        holder.moveDown.alpha = if (position >= items.size - 1) DISABLED_ALPHA else 1f
        holder.moveDown.isEnabled = position < items.size - 1
        holder.moveDown.setOnClickListener { onMoveDown(holder.bindingAdapterPosition) }

        holder.remove.setOnClickListener { onRemove(item) }
    }

    override fun getItemCount(): Int = items.size

    private fun subtitle(block: HomeBlock, position: Int): String {
        val kind = when {
            block.isBuiltin -> context.getString(R.string.block_kind_builtin)
            block.isMini -> context.getString(R.string.block_kind_mini)
            block.isShortcut -> context.getString(R.string.block_kind_shortcut)
            else -> context.getString(R.string.block_kind_app)
        }
        val detail = when {
            block.hasSub -> context.getString(R.string.block_sub_count, block.subItems.size)
            block.pkg.isNotEmpty() -> block.pkg
            else -> ""
        }
        val base = if (detail.isEmpty()) kind else "$kind · $detail"
        val inMore = items.size > BlockCatalog.MAX_GRID_SLOTS && position >= BlockCatalog.VISIBLE_BLOCKS
        return if (inMore) "$base · ${context.getString(R.string.block_in_more)}" else base
    }

    private companion object {
        const val DISABLED_ALPHA = 0.25f
    }
}
