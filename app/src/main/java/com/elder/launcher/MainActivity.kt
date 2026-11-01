package com.elder.launcher

import android.Manifest
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.elder.launcher.databinding.ActivityMainBinding
import com.elder.launcher.service.ElderAccessibilityService
import com.elder.launcher.ui.BlockMenuDialog
import com.elder.launcher.ui.HomeAdapter
import com.elder.launcher.ui.MissedCallDialog
import com.elder.launcher.ui.QuickContactAdapter
import com.elder.launcher.ui.RemotePrompt
import com.elder.launcher.util.ActionExecutor
import com.elder.launcher.util.AliveCenter
import com.elder.launcher.util.BlockCatalog
import com.elder.launcher.util.HomeAction
import com.elder.launcher.util.HomeBlock
import com.elder.launcher.util.MissedCall
import com.elder.launcher.util.MissedCallCenter
import com.elder.launcher.util.PermissionHelper
import com.elder.launcher.util.PrefsManager
import com.elder.launcher.util.QuickContact
import com.elder.launcher.util.QuickIconStore
import com.elder.launcher.util.TorchManager
import com.elder.launcher.util.TtsManager
import com.elder.launcher.util.WxShortcuts
import java.util.Calendar

class MainActivity : BaseActivity() {

    companion object {
        /** 视频号链接没能直开、无障碍又没开时，中转页用它把「开启无障碍」引导带回桌面 */
        const val EXTRA_CHANNELS_HINT = "channels_hint"

        /** 快捷联系人滑动结束后多久内的点击算误触 */
        private const val QUICK_TAP_GUARD_MS = 350L
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var homeAdapter: HomeAdapter
    private lateinit var quickAdapter: QuickContactAdapter
    private var quickList: MutableList<QuickContact> = mutableListOf()
    private var blockList: MutableList<HomeBlock> = mutableListOf()
    private var appliedFontLevel: Int = 0

    /** 已应用的区域顺序，顺序没变就不重复摘挂 View（避免白白重排、把滚动位置顶回顶部） */
    private var appliedOrder = ""

    /** 快捷联系人那一排上一次滑动的时间，用于过滤滑动过程中的误点 */
    private var lastQuickScrollAt = 0L

    /** 未接来电弹窗；跨多次 onResume 复用同一个实例 */
    private var missedDialog: MissedCallDialog? = null

    private val weekNames = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")

    /** 每分钟刷一次日期，跨零点也能自动更新 */
    private val timeTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateDate()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* result ignored, handled per action */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        requestEssentialPermissions()
        setupBlocks()
        setupQuick()
        applySectionOrder()
        binding.btnSos.setOnClickListener {
            TtsManager.speak(this, getString(R.string.act_sos))
            startActivity(Intent(this, SosActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        // 顶部时间 → 系统时钟；顶部日期 → 系统日历。老人看时间/日期顺手就能点到。
        binding.clockTime.setOnClickListener { ActionExecutor.openClock(this) }
        binding.tvDate.setOnClickListener { ActionExecutor.openCalendar(this) }
        updateDate()
        appliedFontLevel = PrefsManager.getFontLevel(this)
        setupBackBehavior()
        if (intent?.getBooleanExtra(EXTRA_CHANNELS_HINT, false) == true) {
            intent.removeExtra(EXTRA_CHANNELS_HINT)
            promptChannelsAccessibility()
        }
    }

    /**
     * 本应用可以注册成手机桌面。作为默认桌面时返回键不能退出（会闪一下黑屏再被系统拉起来），
     * 所以这里只在「不是默认桌面」时才放行默认的返回逻辑。
     */
    private fun setupBackBehavior() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (ActionExecutor.isDefaultHome(this@MainActivity)) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    /** 已经在桌面时再按 Home 键 / 点桌面图标：回到内容顶部 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        binding.scrollRoot.scrollTo(0, 0)
        // 视频号链接没能直开、且无障碍没开时，中转页会把引导请求发到这里
        if (intent.getBooleanExtra(EXTRA_CHANNELS_HINT, false)) {
            intent.removeExtra(EXTRA_CHANNELS_HINT)
            promptChannelsAccessibility()
        }
    }

    private fun requestEssentialPermissions() {
        val needed = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val toAsk = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (toAsk.isNotEmpty()) permissionLauncher.launch(toAsk)
    }

    private fun setupBlocks() {
        homeAdapter = HomeAdapter(
            this,
            onClick = { block -> onBlockClick(block) },
            onLongClick = { block -> onBlockLongClick(block) },
            onAddClick = { openBlockEditor() },
            onMoreClick = { onMoreClick() }
        )
        binding.gridActions.layoutManager = GridLayoutManager(this, columnsFor(0))
        binding.gridActions.adapter = homeAdapter
        reloadBlocks()
    }

    /**
     * 色块自适应列数：只有 1 个时占满一行，2 个两列，4 个排成 2×2（块更大），
     * 其余按三列排列。
     */
    private fun columnsFor(count: Int): Int = when (count) {
        0, 1 -> 1
        2 -> 2
        4 -> 2
        else -> 3
    }

    /** 主页色块可能在管理页被改过，回到主页时重新按数量排版 */
    private fun reloadBlocks() {
        blockList = PrefsManager.getHomeBlocks(this)
        // 实际要摆的格子数：超过 9 个时首页只放 8 个 + 1 个「更多」
        val slots = minOf(blockList.size, BlockCatalog.MAX_GRID_SLOTS)
        val columns = columnsFor(slots)
        (binding.gridActions.layoutManager as? GridLayoutManager)?.spanCount = columns
        homeAdapter.submit(blockList, columns)
    }

    private fun onBlockClick(block: HomeBlock) {
        if (block.hasSub) {
            TtsManager.speak(this, block.label)
            BlockMenuDialog.show(this, block) { sub -> performBlock(sub) }
            return
        }
        performBlock(block)
    }

    /** 主页第 9 格的「更多」：把放不下的色块用大图标列出来 */
    private fun onMoreClick() {
        val hidden = BlockCatalog.homeHidden(blockList)
        if (hidden.isEmpty()) return
        TtsManager.speak(this, getString(R.string.block_more))
        BlockMenuDialog.show(
            this,
            getString(R.string.block_more),
            hidden,
            getString(R.string.block_more_hint, hidden.size)
        ) { block -> onBlockClick(block) }
    }

    private fun performBlock(block: HomeBlock) {
        when {
            block.isBuiltin -> {
                val action = block.action
                if (action != null) onAction(action) else toast(R.string.not_installed)
            }
            block.isMini -> {
                TtsManager.speak(this, block.label)
                ActionExecutor.openMiniProgram(this, block.pkg, block.label)
            }
            // 桌面快捷方式（别家 App「发送到桌面」来的）：原样交给系统启动
            block.isShortcut -> {
                TtsManager.speak(this, block.label)
                ActionExecutor.openShortcut(this, block.pkg)
            }
            else -> {
                TtsManager.speak(this, block.label)
                ActionExecutor.openApp(this, block.pkg)
            }
        }
    }

    /**
     * 「微信小程序」内置功能。入口列表由两部分组成：
     * ① 小程序管理页里配置的；
     * ② 微信自己「添加到桌面」生成的系统快捷方式（本应用是默认桌面时读到，原样启动间接直达）。
     * 一个都没有就只打开微信；只有一个直接打开；多个弹大图标列表让长辈挑。
     */
    private fun openMiniPrograms() {
        val minis = PrefsManager.getMiniApps(this)
        val color = ContextCompat.getColor(this, R.color.btn_wxapp)
        // 微信快捷方式：只展示还没被配置项覆盖的，避免同名出现两次
        val shortcutItems = WxShortcuts.pinned(this)
            .filter { shortcut -> minis.none { it.target == WxShortcuts.targetOf(shortcut.id) } }
            .map { shortcut ->
                BlockCatalog.mini(
                    shortcut.shortLabel?.toString().orEmpty().ifEmpty { "微信小程序" },
                    WxShortcuts.targetOf(shortcut.id),
                    color
                )
            }
        val total = minis.size + shortcutItems.size
        if (total == 0) {
            ActionExecutor.openWeixinHome(this)
            return
        }
        if (total == 1) {
            val mini = minis.firstOrNull()
            if (mini != null) {
                TtsManager.speak(this, mini.displayName())
                ActionExecutor.openMiniProgram(this, mini.target, mini.displayName())
            } else {
                val item = shortcutItems.first()
                TtsManager.speak(this, item.label)
                ActionExecutor.openMiniProgram(this, item.pkg, item.label)
            }
            return
        }
        val items = minis.map { mini -> BlockCatalog.mini(mini.displayName(), mini.target, color) } + shortcutItems
        BlockMenuDialog.show(
            this,
            getString(R.string.wx_mini_pick_title),
            items,
            getString(R.string.wx_mini_pick_hint, total)
        ) { block ->
            TtsManager.speak(this, block.label)
            ActionExecutor.openMiniProgram(this, block.pkg, block.label)
        }
    }

    /**
     * 打开主页功能块管理。
     *
     * 这是高级功能（能添加系统应用、改整个桌面排版），未解锁时先去管理密码页；
     * 密码页校验通过后会自己把管理页拉起来，这里就不要再动了。
     */
    private fun openBlockEditor() {
        if (PasswordActivity.require(this, PasswordActivity.TARGET_HOME_BLOCKS)) return
        startActivity(Intent(this, HomeBlocksActivity::class.java))
    }

    /** 长按色块：去编辑，或直接删掉 */
    private fun onBlockLongClick(block: HomeBlock) {        val options = arrayOf(
            getString(R.string.home_edit_blocks),
            getString(R.string.home_delete_block)
        )
        AlertDialog.Builder(this)
            .setTitle(block.label)
            .setItems(options) { _, which ->
                if (which == 0) openBlockEditor()
                else confirmRemoveBlock(block)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRemoveBlock(block: HomeBlock) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.block_remove_confirm, block.label))
            .setPositiveButton(R.string.confirm) { _, _ ->
                val list = PrefsManager.getHomeBlocks(this)
                list.removeAll { item -> item.id == block.id }
                PrefsManager.setHomeBlocks(this, list)
                reloadBlocks()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------- 快捷拨号 ----------------
    // 增删改统一在「设置 → 快捷拨号」里做（QuickDialsActivity），主页只负责显示和拨打

    private fun setupQuick() {
        quickList = PrefsManager.getQuickContacts(this)
        quickAdapter = QuickContactAdapter(
            this, quickList,
            onClick = { contact -> onQuickClick(contact) },
            onLongClick = { contact, _ -> onQuickLongClick(contact) }
        )
        binding.listQuick.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.listQuick.adapter = quickAdapter
        // 横排头像在滑动时容易被误点：把「正在滑 / 刚滑完」这段时间的点击忽略掉
        binding.listQuick.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) {
                    lastQuickScrollAt = SystemClock.elapsedRealtime()
                }
            }
        })
        refreshQuickTitle()
        binding.tvQuickTitle.setOnClickListener { openQuickDials() }
    }

    private fun openQuickDials() {
        startActivity(Intent(this, QuickDialsActivity::class.java))
    }

    private fun refreshQuickTitle() {
        binding.tvQuickTitle.text = if (quickList.isEmpty())
            getString(R.string.quick_empty) else getString(R.string.quick_title_manage)
    }

    // ---------------- 区域顺序 ----------------

    /**
     * 按「设置 → 首页板块顺序」调整三大区域（紧急求助 / 功能块 / 快捷拨号）的上下位置。
     *
     * 三个区域都是 homeRoot 的直接子 View：先全部摘下来，再按配置顺序追加到顶部区之后。
     * ViewBinding 持有的始终是同一批 View 对象，所以 adapter、点击监听都不需要重设。
     */
    private fun applySectionOrder() {
        val sections = mapOf(
            PrefsManager.SECTION_SOS to binding.sectionSos,
            PrefsManager.SECTION_GRID to binding.sectionGrid,
            PrefsManager.SECTION_QUICK to binding.sectionQuick
        )
        val order = PrefsManager.getHomeOrder(this)
        val key = order.joinToString(",")
        if (key == appliedOrder) return
        appliedOrder = key

        sections.values.forEach { binding.homeRoot.removeView(it) }
        order.forEach { sectionKey -> sections[sectionKey]?.let { binding.homeRoot.addView(it) } }
        // 「快捷拨号」排在最上面时，它自带的那条分隔线就多余了
        binding.divider.visibility =
            if (order.firstOrNull() == PrefsManager.SECTION_QUICK) View.GONE else View.VISIBLE
    }

    /**
     * 点快捷联系人：先念一遍这个名字（看不清屏幕也能确认点的是谁），
     * 再弹「打电话 / 发短信」让用户选。
     * 横排头像在滑动时很容易被误点，多这一步就不会误拨；点弹窗外也能关掉。
     */
    private fun onQuickClick(contact: QuickContact) {
        // 滑动中/刚滑完的点击一律不算，避免手指还在划的时候弹出选择框（顺便也别误播报）
        if (binding.listQuick.scrollState != RecyclerView.SCROLL_STATE_IDLE ||
            SystemClock.elapsedRealtime() - lastQuickScrollAt < QUICK_TAP_GUARD_MS
        ) {
            return
        }
        // 播报联系人名称（没写名字时念号码）；受「设置 → 语音播报」总开关控制
        TtsManager.speak(this, contact.displayName())
        val view = layoutInflater.inflate(R.layout.dialog_quick_action, null)
        view.findViewById<TextView>(R.id.tvQuickActionName).text = contact.displayName()
        view.findViewById<TextView>(R.id.tvQuickActionPhone).text = contact.phone
        val dialog = AlertDialog.Builder(this)
            .setView(view)
            .create()
        dialog.setCanceledOnTouchOutside(true)
        view.findViewById<View>(R.id.btnQuickCall).setOnClickListener {
            dialog.dismiss()
            if (hasPermission(Manifest.permission.CALL_PHONE)) {
                ActionExecutor.call(this, contact.phone)
            } else {
                ActionExecutor.dial(this, contact.phone)
            }
        }
        view.findViewById<View>(R.id.btnQuickSms).setOnClickListener {
            dialog.dismiss()
            ActionExecutor.sendSms(this, contact.phone)
        }
        view.findViewById<TextView>(R.id.btnQuickCancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 长按：去管理页改内容/头像，或直接移除 */
    private fun onQuickLongClick(contact: QuickContact) {
        val options = arrayOf(
            getString(R.string.quick_dial_edit),
            getString(R.string.quick_dial_remove)
        )
        AlertDialog.Builder(this)
            .setTitle(contact.displayName())
            .setItems(options) { _, which ->
                if (which == 0) openQuickDials() else confirmRemoveQuick(contact)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRemoveQuick(contact: QuickContact) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.quick_dial_remove_confirm, contact.displayName()))
            .setPositiveButton(R.string.confirm) { _, _ ->
                val list = PrefsManager.getQuickContacts(this)
                val removed = list.firstOrNull { it.name == contact.name && it.phone == contact.phone }
                list.removeAll { it.name == contact.name && it.phone == contact.phone }
                // 自定义头像文件只有在我们自己的私有目录里才删，别碰用户的相册原图
                if (removed != null && QuickIconStore.isOwned(this, removed.iconPath)) {
                    QuickIconStore.delete(removed.iconPath)
                }
                PrefsManager.setQuickContacts(this, list)
                applyQuickList(list)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun applyQuickList(newList: List<QuickContact>) {
        // 注意：quickList 与 adapter 内部的 items 是同一个实例（setupQuick 里传进去的），
        // update() 内部会先 clear 再 addAll——如果把 quickList 自己传进去，等于“清空后从空列表恢复”，
        // 数据会被整个清掉，只能重启才恢复。这里必须传外部传入的 newList（不同实例）。
        quickList.clear()
        quickList.addAll(newList)
        quickAdapter.update(newList)
        refreshQuickTitle()
    }

    private fun onAction(action: HomeAction) {
        when (action) {
            // 语音页进来就自己开始听，这里**不要**再 TTS 播报：播报声会被麦克风收进去，
            // 变成老人必须先等它念完才能说话。
            HomeAction.VOICE -> startActivity(Intent(this, VoiceActivity::class.java))
            HomeAction.PHONE -> {
                TtsManager.speak(this, getString(R.string.act_phone))
                startActivity(Intent(this, DialActivity::class.java))
            }
            HomeAction.CONTACTS -> {
                TtsManager.speak(this, getString(R.string.act_contacts))
                ActionExecutor.openContacts(this)
            }
            HomeAction.DOUYIN -> {
                TtsManager.speak(this, getString(R.string.act_douyin))
                ActionExecutor.openDouyin(this)
            }
            HomeAction.WXAPP -> {
                TtsManager.speak(this, getString(R.string.act_wxapp))
                openMiniPrograms()
            }
            HomeAction.CHANNELS -> {
                TtsManager.speak(this, getString(R.string.act_channels))
                // 配置了分享链接就无需无障碍：直接走链接直达
                val hasLink = PrefsManager.getChannelsLink(this).isNotEmpty()
                if (hasLink || ElderAccessibilityService.isReady()) {
                    ActionExecutor.openChannels(this)
                } else {
                    promptChannelsAccessibility()
                }
            }
            HomeAction.CLEAN -> {
                TtsManager.speak(this, getString(R.string.act_clean))
                confirmDanger(R.string.clean_confirm) { doClean() }
            }
            HomeAction.REBOOT -> {
                TtsManager.speak(this, getString(R.string.act_reboot))
                confirmDanger(R.string.reboot_confirm) { doReboot() }
            }
            HomeAction.TORCH -> {
                val on = TorchManager.toggle(this)
                TtsManager.speak(this, if (on) getString(R.string.torch_on) else getString(R.string.torch_off))
                toast(if (on) R.string.torch_on else R.string.torch_off)
            }
            HomeAction.WIFI -> {
                TtsManager.speak(this, getString(R.string.act_wifi))
                ActionExecutor.openWifiSettings(this)
            }
            HomeAction.DATA -> {
                TtsManager.speak(this, getString(R.string.act_data))
                ActionExecutor.openDataSettings(this)
                toast(R.string.data_hint)
            }
        }
    }

    private fun doClean() {
        ElderAccessibilityService.killBackground(this)
        toast(R.string.clean_done)
        TtsManager.speak(this, getString(R.string.clean_done))
    }

    /** 视频号只能靠无障碍自动点进去，没开启时先引导用户去开 */
    private fun promptChannelsAccessibility() {
        AlertDialog.Builder(this)
            .setTitle(R.string.act_channels)
            .setMessage(R.string.channels_need_accessibility)
            .setPositiveButton(R.string.perm_open_settings) { _, _ ->
                ActionExecutor.openAccessibilitySettings(this)
            }
            .setNeutralButton(R.string.channels_open_wechat) { _, _ ->
                ActionExecutor.openApp(this, ActionExecutor.PKG_WEIXIN)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doReboot() {
        if (ElderAccessibilityService.isReady()) {
            val ok = ElderAccessibilityService.triggerPowerDialog()
            if (!ok) toast(R.string.perm_open_settings)
        } else {
            AlertDialog.Builder(this)
                .setMessage("需要先开启无障碍服务才能弹出重启菜单")
                .setPositiveButton(R.string.perm_open_settings) { _, _ ->
                    ActionExecutor.openAccessibilitySettings(this)
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun confirmDanger(messageRes: Int, onConfirm: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(messageRes)
            .setPositiveButton(R.string.confirm) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 顶部只显示时间、日期和星期，星期用汉字，避免英文系统显示成英文 */
    private fun updateDate() {
        val calendar = Calendar.getInstance()
        val month = calendar.get(Calendar.MONTH) + 1
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val week = weekNames[calendar.get(Calendar.DAY_OF_WEEK) - 1]
        binding.tvDate.text = "${month}月${day}日 $week"
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            timeTickReceiver,
            IntentFilter(Intent.ACTION_TIME_TICK),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        try {
            unregisterReceiver(timeTickReceiver)
        } catch (_: Exception) {
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        val lvl = PrefsManager.getFontLevel(this)
        if (lvl != appliedFontLevel) {
            appliedFontLevel = lvl
            recreate()
            return
        }
        reloadBlocks()
        applySectionOrder()
        updateDate()
        val newList = PrefsManager.getQuickContacts(this)
        if (newList != quickList) {
            applyQuickList(newList)
        }
        TorchManager.off(this)

        // 兜底：无障碍没开时，至少「打开桌面」也算一次活跃
        AliveCenter.touch(this)

        // 桌面回到前台才接弹窗请求：来电界面或别的应用在前台时，不该被我们盖一层
        MissedCallCenter.onNew = { binding.root.post { maybeShowMissedCalls() } }
        // 两件事同时挤在桌面上，老人会不知道先看哪个：未接来电优先，远程确认留到下次回桌面
        if (!maybeShowMissedCalls()) {
            RemotePrompt.showIfNeeded(this)
        }
    }

    override fun onPause() {
        MissedCallCenter.onNew = null
        super.onPause()
    }

    /**
     * 有新的未接来电就在桌面上弹一次（方案 B：回到桌面弹，不常驻占位）。
     *
     * 只弹「还没提醒过的」：离开桌面去别的应用再回来，不会一遍遍重复弹；
     * 新来电才重新变回未提醒，这时弹窗内容会整体刷新（多人时直接变成列表）。
     */
    /**
     * @return 这次有没有真的弹出来。没弹（没人未接 / 没开权限 / 都提醒过了）时调用方可以接着弹别的。
     */
    private fun maybeShowMissedCalls(): Boolean {
        if (isFinishing || isDestroyed) return false
        // 没开通知使用权就收不到任何通知，弹了也是空数据
        if (!MissedCallCenter.isEnabled(this)) return false
        val all = MissedCallCenter.active(this)
        if (all.isEmpty()) return false
        if (all.none { !it.notified }) return false

        MissedCallCenter.markNotified(this, all.map { it.number })
        announceMissed(all)
        if (missedDialog == null) missedDialog = MissedCallDialog(this)
        missedDialog?.show(all)
        return true
    }

    /** 念一遍，看不清屏幕的人也能知道；多人只念数量，不念一长串名字 */
    private fun announceMissed(list: List<MissedCall>) {
        if (!PrefsManager.isVoiceEnabled(this)) return
        val text = if (list.size == 1) {
            getString(
                R.string.missed_call_announce,
                list.first().displayName(getString(R.string.missed_call_unknown))
            )
        } else {
            getString(R.string.missed_call_announce_multi, list.size)
        }
        TtsManager.speak(this, text)
    }

    private fun hasPermission(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }
}
