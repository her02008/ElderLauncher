package com.elder.launcher.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.elder.launcher.R
import com.elder.launcher.util.QuickContact
import com.elder.launcher.util.QuickIconStore

/**
 * 「快捷拨号」管理列表：点一行改名称/号码/头像，右侧 ↑ ↓ 调顺序、× 移除。
 * 播报试听在编辑弹窗里（`dialog_edit_quick.xml` 的「试听播报」按钮）——
 * 这一行右侧已经有三个按钮，再塞一个喇叭会把号码挤到换行。
 *
 * 和 BlockRowAdapter 一样，这里持有外面传进来的**同一个列表实例**，
 * 这样长按拖动时直接改列表 + notifyItemMoved 就能生效。
 */
class QuickDialRowAdapter(
    private val context: Context,
    private val onClick: (Int) -> Unit,
    private val onRemove: (Int) -> Unit,
    private val onMoveUp: (Int) -> Unit,
    private val onMoveDown: (Int) -> Unit
) : RecyclerView.Adapter<QuickDialRowAdapter.VH>() {

    private var items: List<QuickContact> = emptyList()

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.rowAvatar)
        val name: TextView = v.findViewById(R.id.rowName)
        val phone: TextView = v.findViewById(R.id.rowPhone)
        val moveUp: ImageButton = v.findViewById(R.id.btnMoveUp)
        val moveDown: ImageButton = v.findViewById(R.id.btnMoveDown)
        val remove: ImageButton = v.findViewById(R.id.btnRemove)
    }

    fun submit(list: List<QuickContact>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(context).inflate(R.layout.item_quick_dial_row, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val size = context.resources.getDimensionPixelSize(R.dimen.avatar_row)
        val picture = QuickIconStore.circleImage(item.iconPath, size)
            ?: QuickIconStore.circleContactPhoto(context, item.photoUri, size)
        holder.avatar.background = null
        if (picture != null) {
            holder.avatar.setImageBitmap(picture)
        } else {
            val color = if (item.color != 0) item.color else QuickContact.colorAt(position)
            holder.avatar.setImageBitmap(QuickIconStore.circleText(item.initial(), color, size))
        }
        holder.name.text = item.displayName()
        holder.phone.text = item.phone
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
