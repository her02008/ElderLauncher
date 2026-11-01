package com.elder.launcher

import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.elder.launcher.databinding.ActivityHomeBlocksBinding
import com.elder.launcher.ui.BlockRowAdapter
import com.elder.launcher.util.AdminGate
import com.elder.launcher.util.AppEntry
import com.elder.launcher.util.AppUtils
import com.elder.launcher.util.BlockCatalog
import com.elder.launcher.util.BlockType
import com.elder.launcher.util.HomeBlock
import com.elder.launcher.util.HomeAction
import com.elder.launcher.util.PinnedShortcuts
import com.elder.launcher.util.PrefsManager
import com.elder.launcher.util.WxShortcuts

/** 「添加功能块 → 其他应用 / 微信小程序」多选列表里的一项：key 是包名或小程序标识 */
private class PickEntry(val key: String, val label: String)

/**
 * 主页功能色块管理。
 *
 * - 色块总数上限 [BlockCatalog.MAX_BLOCKS]；主页一屏只放 9 格，
 *   超出 [BlockCatalog.MAX_GRID_SLOTS] 后前 8 个留在主页、第 9 格变成「更多」，其余在「更多」弹窗里
 * - 每个色块可挂最多 [BlockCatalog.MAX_SUB_ITEMS] 个二级菜单项，二级项可以是内置功能或别的 APP
 * - 只有首次进页面才从 SharedPreferences 反序列化，之后一律复用内存对象，
 *   避免弹窗里持有的对象引用失效
 */
class HomeBlocksActivity : BaseActivity() {

    private lateinit var binding: ActivityHomeBlocksBinding
    private lateinit var adapter: BlockRowAdapter

    private val blocks = mutableListOf<HomeBlock>()
    private var appsCache: List<AppEntry>? = null

    /** 点「管理小程序」时记下当时在给谁加小程序，回来好接着弹多选列表 */
    private var pendingMiniOwner: HomeBlock? = null
    private val manageMinis = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { pickMinis(pendingMiniOwner) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 这是「高级功能」（能加系统应用、改整个桌面形态），必须先在管理密码页解锁。
        // 页面自己守一道，避免从别的路径被绕进来。
        if (!AdminGate.isUnlocked()) {
            PasswordActivity.require(this, PasswordActivity.TARGET_HOME_BLOCKS)
            finish()
            return
        }

        binding = ActivityHomeBlocksBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = BlockRowAdapter(
            this,
            onClick = { block -> showEditDialog(block) },
            onRemove = { block -> confirmRemoveBlock(block) },
            onMoveUp = { position -> moveAt(position, position - 1) },
            onMoveDown = { position -> moveAt(position, position + 1) }
        )
        binding.rvBlocks.layoutManager = LinearLayoutManager(this)
        binding.rvBlocks.adapter = adapter
        binding.btnBack.setOnClickListener { finish() }
        binding.btnAdd.setOnClickListener { showAddDialog() }
        attachDragReorder()

        blocks.addAll(PrefsManager.getHomeBlocks(this))
        refresh()
    }

    override fun onResume() {
        super.onResume()
        // 密码会话可能在跳去通讯录/相册等界面时过期，过期就回密码页
        if (!AdminGate.isUnlocked()) {
            PasswordActivity.require(this, PasswordActivity.TARGET_HOME_BLOCKS)
            finish()
            return
        }
        // 人还在这儿操作 → 顺延免密时间
        AdminGate.touch()
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
                blocks.add(to, blocks.removeAt(from))
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun isLongPressDragEnabled(): Boolean = true

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // 列表本身已经是新顺序，这里只负责存盘 + 刷新「在「更多」里」的标记
                persist()
                recyclerView.post(this@HomeBlocksActivity::refresh)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.rvBlocks)
    }

    /** ↑ ↓ 按钮：把第 from 项挪到 to 的位置 */
    private fun moveAt(from: Int, to: Int) {
        if (from == to || from < 0 || to < 0 || from >= blocks.size || to >= blocks.size) return
        blocks.add(to, blocks.removeAt(from))
        refresh()
    }

    // ---------------- 基础读写 ----------------

    /** 刷新界面 + 落盘，不重新反序列化，保证外部持有的 HomeBlock 引用依然有效 */
    private fun refresh() {
        persist()
        adapter.submit(blocks)
    }

    /** 只落盘（拖动排序结束后用，避免在 clearView 里 notifyDataSetChanged） */
    private fun persist() {
        PrefsManager.setHomeBlocks(this, blocks)
        binding.tvHint.text = getString(R.string.blocks_hint, blocks.size, BlockCatalog.MAX_GRID_SLOTS)
    }

    private fun loadApps(): List<AppEntry> {
        appsCache?.let { return it }
        val apps = AppUtils.loadLaunchableApps(this)
        appsCache = apps
        return apps
    }

    // ---------------- 添加色块 / 二级项 ----------------

    /**
     * 「添加什么？」的选项：内置功能 / 其他应用 / 微信小程序 / 视频号直达 / 桌面快捷方式。
     * 微信小程序、视频号直达原来在设置页各有一张卡片，现已收进这里，与其它功能块并列。
     */
    private fun addKindOptions(): Array<String> = arrayOf(
        getString(R.string.block_kind_builtin),
        getString(R.string.block_kind_app),
        getString(R.string.block_kind_mini),
        getString(R.string.block_kind_channels),
        getString(R.string.block_kind_shortcut)
    )

    /** 选项下标 → 具体动作，一级色块与二级菜单共用 */
    private fun onKindPicked(owner: HomeBlock?, which: Int) {
        when (which) {
            0 -> pickBuiltin(owner)
            1 -> pickApps(owner)
            2 -> pickMinis(owner)
            3 -> pickChannels(owner)
            else -> pickShortcuts(owner)
        }
    }

    /** owner 为 null 表示加一级色块，否则加到该色块的二级菜单里 */
    private fun showAddDialog() {
        if (blocks.size >= BlockCatalog.MAX_BLOCKS) {
            toast(getString(R.string.blocks_full, BlockCatalog.MAX_BLOCKS))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.blocks_add_title)
            .setItems(addKindOptions()) { _, which -> onKindPicked(null, which) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun limitFor(owner: HomeBlock?): Int =
        if (owner == null) BlockCatalog.MAX_BLOCKS else BlockCatalog.MAX_SUB_ITEMS

    private fun targetOf(owner: HomeBlock?): MutableList<HomeBlock> =
        if (owner == null) blocks else owner.subItems

    private fun afterAdd(owner: HomeBlock?) {
        refresh()
        if (owner != null) manageSub(owner)
    }

    /** 添加内置功能（单选的） */
    private fun pickBuiltin(owner: HomeBlock?) {
        val target = targetOf(owner)
        val limit = limitFor(owner)
        val used = BlockCatalog.usedActions(blocks)
        // 「微信小程序」和「视频号」不再作为内置功能提供：它们各有专门的入口
        // （微信小程序 / 视频号直达），重复列出会让人不知道该点哪个。
        // 已存在的 WXAPP / CHANNELS 色块照常显示、打开，只是不能再从这里新增。
        val available = BlockCatalog.ORDER.filter { action ->
            action != HomeAction.WXAPP && action != HomeAction.CHANNELS && action !in used
        }
        if (available.isEmpty()) {
            toast(getString(R.string.blocks_builtin_all_used))
            return
        }
        val labels = available.map { action -> BlockCatalog.label(this, action) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.blocks_pick_builtin)
            .setItems(labels) { _, which ->
                if (target.size >= limit) {
                    toast(getString(R.string.blocks_full, limit))
                    return@setItems
                }
                target.add(BlockCatalog.builtin(this, available[which]))
                afterAdd(owner)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> if (owner != null) manageSub(owner) }
            .show()
    }

    /**
     * 多选管理其他 APP 快捷方式。
     * 已经加在这一层的 APP 会保持打勾状态，取消打勾就是把它移掉，
     * 所以反复进来不会重复添加；已经加在别处（主页或其它二级菜单）的会额外标注出来。
     */
    private fun pickApps(owner: HomeBlock?) {
        val entries = loadApps().map { app -> PickEntry(app.pkg, app.label) }
        if (entries.isEmpty()) {
            toast(getString(R.string.blocks_no_app))
            return
        }
        pickItems(owner, BlockType.APP, entries, R.string.blocks_pick_app)
    }

    /**
     * 多选管理微信小程序。来源有两类：
     * ① 「管理小程序」里手工配置的；
     * ② 微信自己「添加到桌面」生成的系统快捷方式（本应用是默认桌面时能读到，原样启动间接直达）。
     * 都没有时引导去管理页；弹窗里也有「管理小程序」按钮，随时能回去增删改。
     */
    private fun pickMinis(owner: HomeBlock?) {
        val entries = mutableListOf<PickEntry>()
        PrefsManager.getMiniApps(this).forEach { mini ->
            entries += PickEntry(mini.target, mini.displayName())
        }
        WxShortcuts.pinned(this).forEach { shortcut ->
            val key = WxShortcuts.targetOf(shortcut.id)
            if (entries.none { it.key == key }) {
                entries += PickEntry(key, shortcut.shortLabel?.toString().orEmpty().ifEmpty { "微信小程序" })
            }
        }
        if (entries.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage(R.string.blocks_no_mini)
                .setPositiveButton(R.string.wx_mini_manage) { _, _ -> openMiniManager(owner) }
                .setNegativeButton(R.string.cancel) { _, _ -> if (owner != null) manageSub(owner) }
                .show()
            return
        }
        pickItems(
            owner = owner,
            type = BlockType.MINI,
            entries = entries,
            titleRes = R.string.blocks_pick_mini,
            neutralRes = R.string.wx_mini_manage,
            onNeutral = { openMiniManager(owner) }
        )
    }

    /** 去「管理小程序」页增删改，回来后再把多选列表弹出来，不用用户重新点一遍 */
    private fun openMiniManager(owner: HomeBlock?) {
        pendingMiniOwner = owner
        manageMinis.launch(Intent(this, WxMiniActivity::class.java))
    }

    /**
     * 多选管理「桌面快捷方式」——别家 App（浏览器、其它平台小程序）点「发送到桌面」时，
     * 由 [ShortcutPinActivity] 接受后固定到本项目名下的那些快捷方式。
     *
     * 一条都没有时告诉用户怎么弄出来（去哪儿点「添加到桌面」），而不是干巴巴一句「没有」。
     */
    private fun pickShortcuts(owner: HomeBlock?) {
        val entries = PinnedShortcuts.all(this).map { info ->
            val label = PinnedShortcuts.labelOf(info).ifEmpty { getString(R.string.pin_default_label) }
            PickEntry(PinnedShortcuts.targetOf(PinnedShortcuts.packageOf(info), info.id), label)
        }
        if (entries.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage(R.string.blocks_no_shortcut)
                .setPositiveButton(R.string.done, null)
                .setOnDismissListener { if (owner != null) manageSub(owner) }
                .show()
            return
        }
        pickItems(owner, BlockType.SHORTCUT, entries, R.string.blocks_pick_shortcut)
    }

    /**
     * 「视频号直达」：先把「视频号」色块加到目标层，再带用户去保存一条视频号分享链接。
     * 主页点「视频号」会优先用这条链接直达具体视频，失败仍退回无障碍自动导航。
     * 二级菜单里只加块，不跳设置页（免得正在编辑的弹窗被顶掉）。
     */
    private fun pickChannels(owner: HomeBlock?) {
        val target = targetOf(owner)
        val limit = limitFor(owner)
        val exists = target.any { block ->
            block.type == BlockType.BUILTIN && block.action == HomeAction.CHANNELS
        }
        if (!exists) {
            if (target.size >= limit) {
                toast(getString(R.string.blocks_full, limit))
                return
            }
            target.add(BlockCatalog.builtin(this, HomeAction.CHANNELS))
            toast(getString(R.string.blocks_channels_added))
        }
        afterAdd(owner)
        if (owner == null) startActivity(Intent(this, ChannelsLinkActivity::class.java))
    }

    /**
     * 通用多选列表：打勾＝这一层里要有它，取消打勾＝从这一层移除。
     * [type] 决定操作的是 [BlockType.APP] 还是 [BlockType.MINI]。
     */
    private fun pickItems(
        owner: HomeBlock?,
        type: String,
        entries: List<PickEntry>,
        titleRes: Int,
        neutralRes: Int? = null,
        onNeutral: (() -> Unit)? = null
    ) {
        val target = targetOf(owner)
        val selected = target.filter { block -> block.type == type }.map { block -> block.pkg }.toSet()
        val elsewhere = BlockCatalog.usedKeys(blocks, type) - selected
        val labels = entries.map { entry ->
            if (entry.key in elsewhere) entry.label + getString(R.string.blocks_elsewhere) else entry.label
        }.toTypedArray()
        val checked = BooleanArray(entries.size) { index -> entries[index].key in selected }
        val builder = AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMultiChoiceItems(labels, checked) { _, index, isChecked -> checked[index] = isChecked }
            .setPositiveButton(R.string.blocks_apps_apply) { _, _ ->
                syncPickedItems(owner, type, entries, checked)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> if (owner != null) manageSub(owner) }
        if (neutralRes != null) {
            builder.setNeutralButton(neutralRes) { _, _ -> onNeutral?.invoke() }
        }
        builder.show()
    }

    /** 把勾选结果同步到目标列表：保留勾选的（含原顺序）、去掉取消勾选的、追加新勾选的 */
    private fun syncPickedItems(
        owner: HomeBlock?,
        type: String,
        entries: List<PickEntry>,
        checked: BooleanArray
    ) {
        val target = targetOf(owner)
        val pickedKeys = entries.filterIndexed { index, _ -> checked[index] }.map { entry -> entry.key }.toSet()

        val kept = mutableListOf<HomeBlock>()
        val keptKeys = mutableSetOf<String>()
        target.forEach { block ->
            when {
                // 别的类型（内置功能、另一种来源）原样保留
                block.type != type -> kept.add(block)
                // 勾选中的只保留第一个，重复的（历史数据）丢掉
                block.pkg in pickedKeys && keptKeys.add(block.pkg) -> kept.add(block)
            }
        }
        val removed = target.size - kept.size

        val room = (limitFor(owner) - kept.size).coerceAtLeast(0)
        val adding = entries.filterIndexed { index, entry -> checked[index] && entry.key !in keptKeys }
        if (adding.size > room) toast(getString(R.string.blocks_room_limit, room))
        val palette = BlockCatalog.palette(this)
        var added = 0
        for (entry in adding) {
            if (added >= room) break
            kept.add(newBlock(type, entry, palette[kept.size % palette.size]))
            added++
        }

        if (added == 0 && removed == 0) {
            if (owner != null) manageSub(owner) else refresh()
            return
        }
        target.clear()
        target.addAll(kept)
        toast(
            when {
                added > 0 && removed > 0 -> getString(R.string.blocks_apps_diff, added, removed)
                added > 0 -> getString(R.string.blocks_apps_added, added)
                else -> getString(R.string.blocks_apps_removed, removed)
            }
        )
        afterAdd(owner)
    }

    private fun newBlock(type: String, entry: PickEntry, color: Int): HomeBlock =
        when (type) {
            // 小程序统一用微信绿，一眼能认出来；想要别的颜色可以在「编辑功能块」里改
            BlockType.MINI -> BlockCatalog.mini(entry.label, entry.key, ContextCompat.getColor(this, R.color.btn_wxapp))
            BlockType.SHORTCUT -> BlockCatalog.shortcut(entry.label, entry.key, color)
            else -> BlockCatalog.app(entry.label, entry.key, color)
        }

    // ---------------- 编辑一级色块 ----------------

    private fun showEditDialog(block: HomeBlock) {
        val view = layoutInflater.inflate(R.layout.dialog_edit_block, null)
        val etLabel: EditText = view.findViewById(R.id.etBlockLabel)
        val colorRow: LinearLayout = view.findViewById(R.id.llColors)
        val tvSubInfo: TextView = view.findViewById(R.id.tvSubInfo)
        val btnManageSub: Button = view.findViewById(R.id.btnManageSub)
        val btnMoveUp: Button = view.findViewById(R.id.btnMoveUp)
        val btnMoveDown: Button = view.findViewById(R.id.btnMoveDown)

        etLabel.setText(block.label)
        var pickedColor = block.color
        buildColorRow(colorRow, block.color) { newColor -> pickedColor = newColor }
        tvSubInfo.text = if (block.hasSub)
            getString(R.string.block_sub_count, block.subItems.size)
        else
            getString(R.string.block_sub_none)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.block_edit_title)
            .setView(view)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .create()

        btnManageSub.setOnClickListener {
            applyEdits(block, etLabel, pickedColor)
            dialog.dismiss()
            manageSub(block)
        }
        btnMoveUp.setOnClickListener {
            applyEdits(block, etLabel, pickedColor)
            if (moveBlock(block, -1)) {
                dialog.dismiss()
                showEditDialog(block)
            } else {
                toast(getString(R.string.block_move_edge))
            }
        }
        btnMoveDown.setOnClickListener {
            applyEdits(block, etLabel, pickedColor)
            if (moveBlock(block, 1)) {
                dialog.dismiss()
                showEditDialog(block)
            } else {
                toast(getString(R.string.block_move_edge))
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (etLabel.text.toString().trim().isEmpty()) {
                    toast(getString(R.string.block_name_empty))
                    return@setOnClickListener
                }
                applyEdits(block, etLabel, pickedColor)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun applyEdits(block: HomeBlock, etLabel: EditText, color: Int) {
        val name = etLabel.text.toString().trim()
        if (name.isNotEmpty()) block.label = name
        block.color = color
        refresh()
    }

    private fun moveBlock(block: HomeBlock, delta: Int): Boolean {
        val index = blocks.indexOf(block)
        val target = index + delta
        if (index < 0 || target < 0 || target >= blocks.size) return false
        blocks.removeAt(index)
        blocks.add(target, block)
        refresh()
        return true
    }

    private fun confirmRemoveBlock(block: HomeBlock) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.block_remove_confirm, block.label))
            .setPositiveButton(R.string.confirm) { _, _ ->
                blocks.remove(block)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- 二级菜单管理 ----------------

    private fun manageSub(block: HomeBlock) {
        val subs = block.subItems
        val names = if (subs.isEmpty())
            arrayOf(getString(R.string.block_sub_empty))
        else
            subs.map { sub -> sub.label }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.block_sub_title, block.label))
            .setMessage(R.string.block_sub_tip)
            .setItems(names) { _, which ->
                if (subs.isNotEmpty()) confirmRemoveSub(block, which)
            }
            .setNeutralButton(R.string.block_sub_add) { _, _ -> pickSubKind(block) }
            .setPositiveButton(R.string.done) { _, _ -> showEditDialog(block) }
            .show()
    }

    private fun confirmRemoveSub(block: HomeBlock, index: Int) {
        val sub = block.subItems[index]
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.block_sub_remove_confirm, sub.label))
            .setPositiveButton(R.string.confirm) { _, _ ->
                block.subItems.removeAt(index)
                refresh()
                manageSub(block)
            }
            .setNegativeButton(R.string.cancel) { _, _ -> manageSub(block) }
            .show()
    }

    private fun pickSubKind(block: HomeBlock) {
        if (block.subItems.size >= BlockCatalog.MAX_SUB_ITEMS) {
            toast(getString(R.string.block_sub_full, BlockCatalog.MAX_SUB_ITEMS))
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.block_sub_add)
            .setItems(addKindOptions()) { _, which -> onKindPicked(block, which) }
            .setNegativeButton(R.string.cancel) { _, _ -> manageSub(block) }
            .show()
    }

    // ---------------- 颜色选择 ----------------

    private fun buildColorRow(container: LinearLayout, selected: Int, onPick: (Int) -> Unit) {
        container.removeAllViews()
        val colors = BlockCatalog.palette(this)
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
                        setStroke(stroke, ContextCompat.getColor(this@HomeBlocksActivity, R.color.text_primary))
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
