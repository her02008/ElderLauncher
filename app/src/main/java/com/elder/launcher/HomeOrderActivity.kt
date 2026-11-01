package com.elder.launcher

import android.content.Context
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import com.elder.launcher.databinding.ActivityHomeOrderBinding
import com.elder.launcher.util.PrefsManager

/**
 * 设置 → 首页板块顺序。
 *
 * 主页上「紧急求助 / 功能块 / 快捷拨号」三块内容谁在上面谁在下面，由这里用 ↑ ↓ 调整。
 * 每点一下就立刻落盘（[PrefsManager.setHomeOrder]），返回主页时 [MainActivity] 会重新排好，
 * 不需要重启 App。
 */
class HomeOrderActivity : BaseActivity() {

    private lateinit var binding: ActivityHomeOrderBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeOrderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        render()
    }

    /** 按当前顺序重建列表：首行不能上移、末行不能下移，置灰避免点了没反应 */
    private fun render() {
        val order = PrefsManager.getHomeOrder(this)
        binding.orderContainer.removeAllViews()
        order.forEachIndexed { index, key ->
            val row = layoutInflater.inflate(R.layout.item_home_order, binding.orderContainer, false)
            row.findViewById<TextView>(R.id.tvOrderName).text = labelOf(this, key)

            val up = row.findViewById<ImageButton>(R.id.btnOrderUp)
            up.alpha = if (index == 0) DISABLED_ALPHA else 1f
            up.isEnabled = index > 0
            up.setOnClickListener { move(index, index - 1) }

            val down = row.findViewById<ImageButton>(R.id.btnOrderDown)
            down.alpha = if (index == order.size - 1) DISABLED_ALPHA else 1f
            down.isEnabled = index < order.size - 1
            down.setOnClickListener { move(index, index + 1) }

            binding.orderContainer.addView(row)
        }
    }

    private fun move(from: Int, to: Int) {
        val order = PrefsManager.getHomeOrder(this)
        if (from !in order.indices || to !in order.indices) return
        val moved = order.removeAt(from)
        order.add(to, moved)
        PrefsManager.setHomeOrder(this, order)
        render()
    }

    companion object {
        private const val DISABLED_ALPHA = 0.25f

        /** 区域标识 → 中文名（顺序页和设置页副标题共用） */
        fun labelOf(context: Context, key: String): String = when (key) {
            PrefsManager.SECTION_SOS -> context.getString(R.string.home_section_sos)
            PrefsManager.SECTION_GRID -> context.getString(R.string.home_section_grid)
            else -> context.getString(R.string.home_section_quick)
        }
    }
}
