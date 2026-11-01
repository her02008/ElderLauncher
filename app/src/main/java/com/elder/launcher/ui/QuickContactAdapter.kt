package com.elder.launcher.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.elder.launcher.R
import com.elder.launcher.util.QuickContact
import com.elder.launcher.util.QuickIconStore

/**
 * 主页「快捷拨号」横排头像。
 *
 * 头像优先级：自定义图片 > 通讯录头像 > 彩色圆底 + 名字首字。
 * 渲染统一交给 [QuickIconStore]（带缓存），滚动时不会重复解码。
 *
 * 注意：`it` 在 lambda 里是 View 的隐式参数，千万不要用 `val it = items[...]` 这种写法。
 */
class QuickContactAdapter(
    private val context: Context,
    private var items: MutableList<QuickContact>,
    private val onClick: (QuickContact) -> Unit,
    private val onLongClick: (QuickContact, Int) -> Unit
) : RecyclerView.Adapter<QuickContactAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val avatar: ImageView = v.findViewById(R.id.avatar)
        val name: TextView = v.findViewById(R.id.name)
        val root: LinearLayout = v.findViewById(R.id.root)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(context).inflate(R.layout.item_quick_contact, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.displayName()
        bindAvatar(holder.avatar, item, position)
        holder.root.setOnClickListener { onClick(item) }
        holder.root.setOnLongClickListener {
            onLongClick(item, holder.bindingAdapterPosition)
            true
        }
    }

    private fun bindAvatar(view: ImageView, contact: QuickContact, position: Int) {
        val size = context.resources.getDimensionPixelSize(R.dimen.avatar_size)
        val picture = QuickIconStore.circleImage(contact.iconPath, size)
            ?: QuickIconStore.circleContactPhoto(context, contact.photoUri, size)
        view.background = null
        if (picture != null) {
            view.setImageBitmap(picture)
        } else {
            val color = if (contact.color != 0) contact.color else QuickContact.colorAt(position)
            view.setImageBitmap(QuickIconStore.circleText(contact.initial(), color, size))
        }
        view.contentDescription = contact.displayName()
    }

    override fun getItemCount(): Int = items.size

    fun update(newItems: List<QuickContact>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }
}
