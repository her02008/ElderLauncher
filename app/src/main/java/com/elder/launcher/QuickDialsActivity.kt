package com.elder.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.elder.launcher.databinding.ActivityQuickDialsBinding
import com.elder.launcher.ui.QuickDialRowAdapter
import com.elder.launcher.util.PrefsManager
import com.elder.launcher.util.QuickContact
import com.elder.launcher.util.QuickIconStore
import com.elder.launcher.util.TtsManager

/** 编辑弹窗里的临时草稿（保存前的状态） */
private class QuickDraft {
    var name = ""
    var phone = ""
    var photoUri: String? = null
    var iconPath: String? = null
    var color = 0
}

/** 一个正在打开的编辑弹窗：草稿 + 需要跟着刷新的控件 + 本次新导入的图片文件 */
private class EditSession(
    val index: Int,
    val draft: QuickDraft,
    val nameInput: EditText,
    val phoneInput: EditText,
    val preview: ImageView,
    val clearButton: Button
) {
    val imported = mutableListOf<String>()
}

/**
 * 「快捷拨号」管理页（设置 → 快捷拨号）。
 *
 * - 增删改 + 上下移动，列表顺序就是主页横排的显示顺序
 * - 头像三选一：通讯录头像 / 相册图片（拷进私有目录）/ 颜色圆底 + 名字首字
 * - 只有首次进页面才从 SharedPreferences 反序列化，之后一律改内存对象再落盘
 */
class QuickDialsActivity : BaseActivity() {

    private lateinit var binding: ActivityQuickDialsBinding
    private lateinit var adapter: QuickDialRowAdapter

    private val items = mutableListOf<QuickContact>()

    /** 当前打开的编辑弹窗，从通讯录/相册回来时要靠它把结果填进去 */
    private var session: EditSession? = null

    /** 只申请权限、拿到后不自动拉起选择器（重新点一次按钮即可） */
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* 结果不做处理，用户再点一次按钮就好 */ }

    private val contactPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val data = result.data!!
            applyPickedContact(
                data.getStringExtra(ContactsPickerActivity.EXTRA_NAME) ?: "",
                data.getStringExtra(ContactsPickerActivity.EXTRA_PHONE) ?: "",
                data.getStringExtra(ContactsPickerActivity.EXTRA_PHOTO_URI)
            )
        }
    }

    private val imagePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) applyPickedImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQuickDialsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = QuickDialRowAdapter(
            this,
            onClick = { position -> showEditDialog(position) },
            onRemove = { position -> confirmRemove(position) },
            onMoveUp = { position -> moveAt(position, position - 1) },
            onMoveDown = { position -> moveAt(position, position + 1) }
        )
        binding.rvQuickDials.layoutManager = LinearLayoutManager(this)
        binding.rvQuickDials.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnAdd.setOnClickListener { showAddDialog() }
        attachDragReorder()

        items.addAll(PrefsManager.getQuickContacts(this))
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
                recyclerView.post(this@QuickDialsActivity::refresh)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.rvQuickDials)
    }

    /** ↑ ↓ 按钮：把第 from 项挪到 to 的位置 */
    private fun moveAt(from: Int, to: Int) {
        if (from == to || from < 0 || to < 0 || from >= items.size || to >= items.size) return
        items.add(to, items.removeAt(from))
        refresh()
    }

    // ---------------- 基础读写 ----------------

    private fun refresh() {
        PrefsManager.setQuickContacts(this, items)
        adapter.submit(items)
        binding.tvHint.text = getString(R.string.quick_dials_hint, items.size, QuickContact.MAX_ITEMS)
    }

    // ---------------- 添加 / 编辑 ----------------

    private fun showAddDialog() {
        if (items.size >= QuickContact.MAX_ITEMS) {
            toast(getString(R.string.quick_dial_full, QuickContact.MAX_ITEMS))
            return
        }
        val options = arrayOf(
            getString(R.string.quick_dial_add_contact),
            getString(R.string.quick_dial_add_manual)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.quick_dial_add_title)
            .setItems(options) { _, which ->
                showEditDialog(-1, pickContactFirst = which == 0)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * index = -1 表示新增。
     * [pickContactFirst] 为 true 时弹窗打开后立刻拉起通讯录，选完把姓名号码填进来。
     */
    private fun showEditDialog(index: Int, pickContactFirst: Boolean = false) {
        val existing = if (index in items.indices) items[index] else null
        val draft = QuickDraft().apply {
            name = existing?.name ?: ""
            phone = existing?.phone ?: ""
            photoUri = existing?.photoUri
            iconPath = existing?.iconPath
            color = existing?.color?.takeIf { it != 0 }
                ?: QuickContact.colorAt(if (index >= 0) index else items.size)
        }

        val view = layoutInflater.inflate(R.layout.dialog_edit_quick, null)
        val etName: EditText = view.findViewById(R.id.etQuickName)
        val etPhone: EditText = view.findViewById(R.id.etQuickPhone)
        val preview: ImageView = view.findViewById(R.id.ivQuickPreview)
        val btnPickContact: Button = view.findViewById(R.id.btnPickContact)
        val btnPickImage: Button = view.findViewById(R.id.btnPickImage)
        val btnClearImage: Button = view.findViewById(R.id.btnClearImage)
        val colorRow: LinearLayout = view.findViewById(R.id.llQuickColors)

        etName.setText(draft.name)
        etPhone.setText(draft.phone)
        session = EditSession(index, draft, etName, etPhone, preview, btnClearImage)

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (index >= 0) R.string.quick_dial_edit_title else R.string.quick_dial_add)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        var saved = false
        dialog.setOnDismissListener {
            if (!saved) {
                // 取消编辑：把这次新导入的图片删掉，别在私有目录里留垃圾
                session?.imported?.forEach { path -> QuickIconStore.delete(path) }
            }
            session = null
        }

        // 名字边输边更新预览（圆底首字）
        etName.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = renderPreview()
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

        btnPickContact.setOnClickListener {
            if (hasPermission(Manifest.permission.READ_CONTACTS)) {
                contactPicker.launch(Intent(this, ContactsPickerActivity::class.java))
            } else {
                permissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
            }
        }

        btnPickImage.setOnClickListener { imagePicker.launch(arrayOf("image/*")) }

        // 去掉图片：通讯录头像和自定义图片一起清掉，回落到颜色
        btnClearImage.setOnClickListener {
            session?.draft?.iconPath = null
            session?.draft?.photoUri = null
            renderPreview()
        }

        buildColorRow(colorRow, draft.color) { picked ->
            session?.draft?.color = picked
            renderPreview()
        }

        // 试听：直接念输入框里的当前内容，不用先保存
        view.findViewById<View>(R.id.rowQuickSpeak).setOnClickListener {
            previewVoice(
                session?.nameInput?.text?.toString()?.trim().orEmpty(),
                session?.phoneInput?.text?.toString()?.trim().orEmpty()
            )
        }

        dialog.setOnShowListener {
            if (pickContactFirst) {
                if (hasPermission(Manifest.permission.READ_CONTACTS)) {
                    contactPicker.launch(Intent(this, ContactsPickerActivity::class.java))
                } else {
                    permissionLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS))
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val current = session
                if (current == null) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                val phone = current.phoneInput.text.toString().trim()
                if (phone.isEmpty()) {
                    toast(getString(R.string.quick_dial_phone_empty))
                    return@setOnClickListener
                }
                val name = current.nameInput.text.toString().trim()
                val d = current.draft
                val item = QuickContact(name, phone, d.photoUri, d.iconPath, d.color)

                val original = if (current.index in items.indices) items[current.index].iconPath else null
                if (current.index in items.indices) items[current.index] = item else items.add(item)

                // 清理不再使用的图片文件：本次导入里没被选中的、以及被替换掉的旧图
                current.imported.forEach { path -> if (path != d.iconPath) QuickIconStore.delete(path) }
                if (!original.isNullOrEmpty() && original != d.iconPath && QuickIconStore.isOwned(this, original)) {
                    QuickIconStore.delete(original)
                }

                saved = true
                refresh()
                dialog.dismiss()
            }
        }

        dialog.show()
        renderPreview()
    }

    /** 按「图片 > 通讯录头像 > 颜色 + 首字」的顺序刷新弹窗里的头像预览 */
    private fun renderPreview() {
        val current = session ?: return
        val size = resources.getDimensionPixelSize(R.dimen.avatar_size)
        val picture = QuickIconStore.circleImage(current.draft.iconPath, size)
            ?: QuickIconStore.circleContactPhoto(this, current.draft.photoUri, size)
        current.preview.background = null
        if (picture != null) {
            current.preview.setImageBitmap(picture)
        } else {
            val initial = current.nameInput.text.toString().trim()
                .takeIf { it.isNotEmpty() }?.substring(0, 1) ?: "人"
            current.preview.setImageBitmap(
                QuickIconStore.circleText(initial, current.draft.color, size)
            )
        }
        current.clearButton.visibility =
            if (current.draft.iconPath != null || current.draft.photoUri != null) View.VISIBLE else View.GONE
    }

    private fun applyPickedContact(name: String, phone: String, photoUri: String?) {
        val current = session ?: return
        if (name.isNotEmpty()) {
            current.draft.name = name
            current.nameInput.setText(name)
        }
        if (phone.isNotEmpty()) {
            current.draft.phone = phone
            current.phoneInput.setText(phone)
        }
        // 从通讯录选人 => 用通讯录头像，同时丢掉之前选的自定义图片
        current.draft.photoUri = photoUri
        current.draft.iconPath = null
        renderPreview()
    }

    private fun applyPickedImage(uri: Uri) {
        val current = session ?: return
        val path = QuickIconStore.importImage(this, uri)
        if (path == null) {
            toast(getString(R.string.quick_dial_image_failed))
            return
        }
        current.imported.add(path)
        current.draft.iconPath = path
        current.draft.photoUri = null
        renderPreview()
    }

    /**
     * 试听播报：念的内容和主页点击这个联系人时念的完全一样（名字，没写名字时念号码）。
     *
     * 放在编辑弹窗里是因为长名字要改到念得顺口为止，边改边听最方便；
     * 列表行右侧已经有 ↑ ↓ × 三个按钮，再塞一个喇叭会把号码挤到换行。
     */
    private fun previewVoice(name: String, phone: String) {
        if (name.isEmpty() && phone.isEmpty()) {
            toast(getString(R.string.quick_dial_phone_empty))
            return
        }
        if (!PrefsManager.isVoiceEnabled(this)) {
            toast(getString(R.string.voice_off_hint))
            return
        }
        TtsManager.speak(this, name.ifEmpty { phone })
    }

    private fun confirmRemove(index: Int) {
        if (index !in items.indices) return
        val item = items[index]
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.quick_dial_remove_confirm, item.displayName()))
            .setPositiveButton(R.string.confirm) { _, _ ->
                val removed = items.removeAt(index)
                if (QuickIconStore.isOwned(this, removed.iconPath)) QuickIconStore.delete(removed.iconPath)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- 颜色选择 ----------------

    private fun buildColorRow(container: LinearLayout, selected: Int, onPick: (Int) -> Unit) {
        container.removeAllViews()
        val colors = QuickContact.PALETTE
        val size = dp(36)
        val gap = dp(8)
        val stroke = dp(3)
        val swatches = mutableListOf<View>()
        var current = selected

        fun refreshStroke() {
            swatches.forEachIndexed { index, swatch ->
                swatch.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(colors[index])
                    if (colors[index] == current) {
                        setStroke(stroke, ContextCompat.getColor(this@QuickDialsActivity, R.color.text_primary))
                    }
                }
            }
        }

        colors.forEach { color ->
            val swatch = View(this)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.setMargins(0, 0, gap, 0)
            swatch.layoutParams = lp
            swatch.setOnClickListener {
                current = color
                refreshStroke()
                onPick(color)
            }
            swatches.add(swatch)
            container.addView(swatch)
        }
        refreshStroke()
    }

    // ---------------- 小工具 ----------------

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
