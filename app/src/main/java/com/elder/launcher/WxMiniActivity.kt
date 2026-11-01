package com.elder.launcher

import android.app.AlertDialog
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.elder.launcher.databinding.ActivityWxMiniBinding
import com.elder.launcher.ui.WxMiniRowAdapter
import com.elder.launcher.util.ActionExecutor
import com.elder.launcher.util.PrefsManager
import com.elder.launcher.util.WxMiniApp
import java.util.UUID

/**
 * 「微信小程序」管理页。入口在主页功能块管理 →「添加什么？」→ 微信小程序 →「管理小程序」。
 *
 * - 可以一次配置多个小程序，每一个都能自定义名称
 * - 弹窗里能填小程序标识 / 链接，支持从剪贴板粘贴，还能点「试一试」立刻验证能不能打开
 * - 列表顺序就是主页「添加功能块 → 微信小程序」里的顺序
 * - 只有首次进页面才从 SharedPreferences 反序列化，之后一律改内存对象再落盘
 */
class WxMiniActivity : BaseActivity() {

    private lateinit var binding: ActivityWxMiniBinding
    private lateinit var adapter: WxMiniRowAdapter

    private val items = mutableListOf<WxMiniApp>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWxMiniBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = WxMiniRowAdapter(
            this,
            onClick = { position -> showEditDialog(position) },
            onRemove = { position -> confirmRemove(position) },
            onMoveUp = { position -> moveAt(position, position - 1) },
            onMoveDown = { position -> moveAt(position, position + 1) }
        )
        binding.rvMinis.layoutManager = LinearLayoutManager(this)
        binding.rvMinis.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnAdd.setOnClickListener { showEditDialog(-1) }
        attachDragReorder()

        items.addAll(PrefsManager.getMiniApps(this))
        refresh()
    }

    // ---------------- 排序 ----------------

    /** 长按整行可以拖动排序（拖动结束才落盘） */
    private fun attachDragReorder() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                if (from == to) return true
                items.add(to, items.removeAt(from))
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun isLongPressDragEnabled(): Boolean = true

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // 列表本身已经是新顺序，这里只负责存盘 + 刷新
                recyclerView.post(this@WxMiniActivity::refresh)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.rvMinis)
    }

    /** ↑ ↓ 按钮：把第 from 项挪到 to 的位置 */
    private fun moveAt(from: Int, to: Int) {
        if (from == to || from < 0 || to < 0 || from >= items.size || to >= items.size) return
        items.add(to, items.removeAt(from))
        refresh()
    }

    // ---------------- 基础读写 ----------------

    private fun refresh() {
        PrefsManager.setMiniApps(this, items)
        adapter.submit(items)
        binding.tvHint.text = getString(R.string.wx_mini_hint, items.size, WxMiniApp.MAX_ITEMS)
    }

    // ---------------- 新增 / 编辑 ----------------

    /** index = -1 表示新增 */
    private fun showEditDialog(index: Int) {
        val existing = if (index in items.indices) items[index] else null
        if (existing == null && items.size >= WxMiniApp.MAX_ITEMS) {
            toast(getString(R.string.wx_mini_full, WxMiniApp.MAX_ITEMS))
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_edit_mini, null)
        val etName: EditText = view.findViewById(R.id.etMiniName)
        val etTarget: EditText = view.findViewById(R.id.etMiniTarget)
        etName.setText(existing?.name ?: "")
        etTarget.setText(existing?.target ?: "")

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing != null) R.string.wx_mini_edit_title else R.string.wx_mini_add_title)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNeutralButton(R.string.wx_mini_test, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        view.findViewById<Button>(R.id.btnPaste).setOnClickListener {
            val text = clipboardText()
            if (text.isNullOrEmpty()) {
                toast(getString(R.string.wx_mini_paste_empty))
                return@setOnClickListener
            }
            // 剪贴板里是转发文案时，把「#小程序://」口令单独抠出来填
            val passcode = ActionExecutor.extractPasscode(text)
            val fill = passcode ?: text
            etTarget.setText(fill)
            etTarget.setSelection(fill.length)
            // 口令的第二段一般是小程序名，名称没填就顺手带上
            if (passcode != null && etName.text.isNullOrBlank()) {
                val guess = passcode.removePrefix("#小程序://").substringBefore('/')
                if (guess.isNotBlank()) etName.setText(guess.take(12))
            }
        }

        dialog.setOnShowListener {
            // 「试一试」：不关弹窗，直接用当前填的内容试打开一次，打不开可以马上改
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val target = etTarget.text.toString().trim()
                if (target.isEmpty()) {
                    toast(getString(R.string.wx_mini_target_empty))
                    return@setOnClickListener
                }
                ActionExecutor.openMiniProgram(this, target, etName.text.toString().trim())
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = etName.text.toString().trim()
                val target = etTarget.text.toString().trim()
                if (name.isEmpty()) {
                    toast(getString(R.string.wx_mini_name_empty))
                    return@setOnClickListener
                }
                if (target.isEmpty()) {
                    toast(getString(R.string.wx_mini_target_empty))
                    return@setOnClickListener
                }
                // 同一个标识加两遍没有意义，直接拦住
                val duplicated = items.withIndex().any { (i, mini) -> i != index && mini.target == target }
                if (duplicated) {
                    toast(getString(R.string.wx_mini_duplicate))
                    return@setOnClickListener
                }
                val updated = WxMiniApp(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    name = name,
                    target = target
                )
                if (index in items.indices) items[index] = updated else items.add(updated)
                refresh()
                dialog.dismiss()
            }
        }

        dialog.show()
    }

    private fun confirmRemove(index: Int) {
        if (index !in items.indices) return
        val item = items[index]
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.wx_mini_remove_confirm, item.displayName()))
            .setPositiveButton(R.string.confirm) { _, _ ->
                items.removeAt(index)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- 小工具 ----------------

    private fun clipboardText(): String? {
        val cm = getSystemService(ClipboardManager::class.java) ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
