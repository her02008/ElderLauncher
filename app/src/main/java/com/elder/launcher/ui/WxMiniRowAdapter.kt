package com.elder.launcher.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.elder.launcher.R
import com.elder.launcher.util.WxMiniApp

/**
 * 「微信小程序」管理列表：点一行改名称/标识，右侧 ↑ ↓ 调顺序、× 删除。
 *
 * 和 BlockRowAdapter 一样持有外面传进来的**同一个列表实例**，
 * 这样长按拖动时直接改列表 + notifyItemMoved 就能生效。
 */
class WxMiniRowAdapter(
    private val context: Context,
    private val onClick: (Int) -> Unit,
    private val onRemove: (Int) -> Unit,
    private val onMoveUp: (Int) -> Unit,
    private val onMoveDown: (Int) -> Unit
) : RecyclerView.Adapter<WxMiniRowAdapter.VH>() {

    private var items: List<WxMiniApp> = emptyList()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.rowName)
        val target: TextView = v.findViewById(R.id.rowTarget)
        val moveUp: ImageButton = v.findViewById(R.id.btnMoveUp)
        val moveDown: ImageButton = v.findViewById(R.id.btnMoveDown)
        val remove: ImageButton = v.findViewById(R.id.btnRemove)
    }

    fun submit(list: List<WxMiniApp>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(context).inflate(R.layout.item_wx_mini_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.displayName()
        holder.target.text = item.target
        holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }

        // 第一行不能再上移、最后一行不能再下移，置灰避免点了没反应
        val canUp = position > 0
        holder.moveUp.alpha = if (canUp) 1f else DISABLED_ALPHA
        holder.moveUp.isEnabled = canUp
        holder.moveUp.setOnClickListener { onMoveUp(holder.bindingAdapterPosition) }

        val canDown = position < items.size - 1
        holder.moveDown.alpha = if (canDown) 1f else DISABLED_ALPHA
        holder.moveDown.isEnabled = canDown
        holder.moveDown.setOnClickListener { onMoveDown(holder.bindingAdapterPosition) }

        holder.remove.setOnClickListener { onRemove(holder.bindingAdapterPosition) }
    }

    override fun getItemCount(): Int = items.size

    private companion object {
        const val DISABLED_ALPHA = 0.25f
    }
}
