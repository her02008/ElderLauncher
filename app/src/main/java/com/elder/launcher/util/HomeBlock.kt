package com.elder.launcher.util

import android.content.Context
import androidx.core.content.ContextCompat
import com.elder.launcher.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** 内置功能动作（不可扩展，APP 快捷方式走 BlockType.APP） */
enum class HomeAction {
    /** 语音说话：点开说一句话就能打电话、开收音机、放字、求救 */
    VOICE,

    PHONE, CONTACTS, DOUYIN, WXAPP, CHANNELS, CLEAN, REBOOT, TORCH, WIFI, DATA
}

/** 色块类型 */
object BlockType {
    /** 内置功能 */
    const val BUILTIN = "builtin"

    /** 其他 APP 快捷方式 */
    const val APP = "app"

    /** 微信小程序快捷方式；[HomeBlock.pkg] 里存的是小程序标识/链接，不是包名 */
    const val MINI = "mini"

    /**
     * 系统「固定快捷方式」（别家 App 的「发送到桌面」）；
     * [HomeBlock.pkg] 里存 `shortcut://<包名>/<快捷方式id>`，见 [PinnedShortcuts]。
     */
    const val SHORTCUT = "shortcut"
}

/**
 * 主页功能色块。可以是一级色块，也可以是某个色块的二级菜单项（二级菜单不再嵌套）。
 */
data class HomeBlock(
    var id: String = UUID.randomUUID().toString(),
    var type: String = BlockType.BUILTIN,
    var action: HomeAction? = null,
    var pkg: String = "",
    var label: String = "",
    var color: Int = BlockCatalog.HOME_DEFAULT_COLOR,
    var subItems: MutableList<HomeBlock> = mutableListOf()
) {
    val isBuiltin: Boolean get() = type == BlockType.BUILTIN
    val isMini: Boolean get() = type == BlockType.MINI
    val isShortcut: Boolean get() = type == BlockType.SHORTCUT
    val hasSub: Boolean get() = subItems.isNotEmpty()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type)
        if (action != null) put("action", action!!.name)
        if (pkg.isNotEmpty()) put("pkg", pkg)
        put("label", label)
        put("color", color)
        if (subItems.isNotEmpty()) {
            val arr = JSONArray()
            subItems.forEach { child -> arr.put(child.toJson()) }
            put("sub", arr)
        }
    }

    companion object {

        fun listToJson(list: List<HomeBlock>): String {
            val arr = JSONArray()
            list.forEach { block -> arr.put(block.toJson()) }
            return arr.toString()
        }

        /** 解析失败或数据非法的色块会被丢弃，不会崩溃 */
        fun jsonToList(json: String): MutableList<HomeBlock> {
            val out = mutableListOf<HomeBlock>()
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val block = fromJson(arr.getJSONObject(i))
                    if (block != null) out.add(block)
                }
            } catch (_: Exception) {
            }
            return out
        }

        private fun fromJson(o: JSONObject): HomeBlock? {
            val type = o.optString("type", BlockType.BUILTIN)
            val actionName = o.optString("action", "")
            val pkg = o.optString("pkg", "")
            val action = HomeAction.values().firstOrNull { it.name == actionName }
            if (type == BlockType.BUILTIN && action == null) return null
            if (type == BlockType.APP && pkg.isEmpty()) return null
            if (type == BlockType.MINI && pkg.isEmpty()) return null
            if (type == BlockType.SHORTCUT && !PinnedShortcuts.isTarget(pkg)) return null

            val block = HomeBlock(
                id = o.optString("id", UUID.randomUUID().toString()),
                type = type,
                action = action,
                pkg = pkg,
                label = o.optString("label", ""),
                color = o.optInt("color", BlockCatalog.HOME_DEFAULT_COLOR)
            )
            val sub = o.optJSONArray("sub")
            if (sub != null) {
                val children = mutableListOf<HomeBlock>()
                for (i in 0 until sub.length()) {
                    val child = fromJson(sub.getJSONObject(i))
                    // 只支持二级菜单，子项自身不再带子项
                    if (child != null) {
                        child.subItems = mutableListOf()
                        children.add(child)
                    }
                }
                block.subItems = children
            }
            return block
        }
    }
}

/**
 * 内置功能目录 + 调色板 + 默认色块。
 */
object BlockCatalog {

    /** 色块总数上限（主页一屏只放 9 格，多出来的收进「更多」；这里只是一个防呆上限） */
    const val MAX_BLOCKS = 30

    /** 主页网格一屏的格子数：3 列 × 3 行 */
    const val MAX_GRID_SLOTS = 9

    /** 色块多于 [MAX_GRID_SLOTS] 时，主页只放前 8 个，第 9 格让给「更多」 */
    const val VISIBLE_BLOCKS = MAX_GRID_SLOTS - 1

    /** 单个色块的二级菜单项数上限 */
    const val MAX_SUB_ITEMS = 9

    /** 主页要不要出现「更多」格 */
    fun hasMore(blocks: List<HomeBlock>): Boolean = blocks.size > MAX_GRID_SLOTS

    /** 主页网格上直接显示的色块 */
    fun homeVisible(blocks: List<HomeBlock>): List<HomeBlock> =
        if (hasMore(blocks)) blocks.take(VISIBLE_BLOCKS) else blocks

    /** 收进「更多」弹窗的色块 */
    fun homeHidden(blocks: List<HomeBlock>): List<HomeBlock> =
        if (hasMore(blocks)) blocks.drop(VISIBLE_BLOCKS) else emptyList()

    const val HOME_DEFAULT_COLOR = 0xFF3498DB.toInt()

    /** 内置功能的展示顺序 */
    val ORDER: List<HomeAction> = listOf(
        HomeAction.VOICE,
        HomeAction.PHONE, HomeAction.CONTACTS, HomeAction.DOUYIN, HomeAction.WXAPP,
        HomeAction.CHANNELS, HomeAction.CLEAN, HomeAction.REBOOT, HomeAction.TORCH,
        HomeAction.WIFI, HomeAction.DATA
    )

    private val ICONS = mapOf(
        HomeAction.VOICE to R.drawable.ic_mic,
        HomeAction.PHONE to R.drawable.ic_phone,
        HomeAction.CONTACTS to R.drawable.ic_contacts,
        HomeAction.DOUYIN to R.drawable.ic_douyin,
        HomeAction.WXAPP to R.drawable.ic_wxapp,
        HomeAction.CHANNELS to R.drawable.ic_channels,
        HomeAction.CLEAN to R.drawable.ic_clean,
        HomeAction.REBOOT to R.drawable.ic_reboot,
        HomeAction.TORCH to R.drawable.ic_torch,
        HomeAction.WIFI to R.drawable.ic_wifi,
        HomeAction.DATA to R.drawable.ic_data
    )

    private val LABELS = mapOf(
        HomeAction.VOICE to R.string.act_voice,
        HomeAction.PHONE to R.string.act_phone,
        HomeAction.CONTACTS to R.string.act_contacts,
        HomeAction.DOUYIN to R.string.act_douyin,
        HomeAction.WXAPP to R.string.act_wxapp,
        HomeAction.CHANNELS to R.string.act_channels,
        HomeAction.CLEAN to R.string.act_clean,
        HomeAction.REBOOT to R.string.act_reboot,
        HomeAction.TORCH to R.string.act_torch,
        HomeAction.WIFI to R.string.act_wifi,
        HomeAction.DATA to R.string.act_data
    )

    private val COLORS = mapOf(
        HomeAction.VOICE to R.color.btn_voice,
        HomeAction.PHONE to R.color.btn_phone,
        HomeAction.CONTACTS to R.color.btn_contacts,
        HomeAction.DOUYIN to R.color.btn_douyin,
        HomeAction.WXAPP to R.color.btn_wxapp,
        HomeAction.CHANNELS to R.color.btn_channels,
        HomeAction.CLEAN to R.color.btn_clean,
        HomeAction.REBOOT to R.color.btn_reboot,
        HomeAction.TORCH to R.color.btn_torch,
        HomeAction.WIFI to R.color.btn_wifi,
        HomeAction.DATA to R.color.btn_data
    )

    private val PALETTE = intArrayOf(
        R.color.btn_phone, R.color.btn_contacts, R.color.btn_douyin,
        R.color.btn_wxapp, R.color.btn_channels, R.color.btn_clean,
        R.color.btn_reboot, R.color.btn_torch, R.color.btn_wifi,
        R.color.btn_data, R.color.btn_settings, R.color.accent
    )

    fun iconRes(action: HomeAction): Int = ICONS[action] ?: R.drawable.ic_settings

    fun labelRes(action: HomeAction): Int = LABELS[action] ?: R.string.app_name

    fun colorRes(action: HomeAction): Int = COLORS[action] ?: R.color.btn_settings

    fun label(ctx: Context, action: HomeAction): String = ctx.getString(labelRes(action))

    fun palette(ctx: Context): IntArray = IntArray(PALETTE.size) { ContextCompat.getColor(ctx, PALETTE[it]) }

    fun builtin(
        ctx: Context,
        action: HomeAction,
        label: String? = null,
        color: Int? = null
    ): HomeBlock = HomeBlock(
        id = UUID.randomUUID().toString(),
        type = BlockType.BUILTIN,
        action = action,
        label = label ?: label(ctx, action),
        color = color ?: ContextCompat.getColor(ctx, colorRes(action))
    )

    fun app(label: String, pkg: String, color: Int): HomeBlock = HomeBlock(
        id = UUID.randomUUID().toString(),
        type = BlockType.APP,
        action = null,
        pkg = pkg,
        label = label,
        color = color
    )

    /** 微信小程序色块，[target] 是小程序标识/链接（存在 [HomeBlock.pkg] 里） */
    fun mini(label: String, target: String, color: Int): HomeBlock = HomeBlock(
        id = UUID.randomUUID().toString(),
        type = BlockType.MINI,
        action = null,
        pkg = target,
        label = label,
        color = color
    )

    /** 系统固定快捷方式色块，[target] 是 `shortcut://<包名>/<id>`（存在 [HomeBlock.pkg] 里） */
    fun shortcut(label: String, target: String, color: Int): HomeBlock = HomeBlock(
        id = UUID.randomUUID().toString(),
        type = BlockType.SHORTCUT,
        action = null,
        pkg = target,
        label = label,
        color = color
    )

    /** 已被使用的内置功能（含二级菜单里的），用于「添加」时过滤 */
    fun usedActions(blocks: List<HomeBlock>): Set<HomeAction> {
        val used = mutableSetOf<HomeAction>()
        blocks.forEach { block ->
            block.action?.let { used.add(it) }
            block.subItems.forEach { child -> child.action?.let { used.add(it) } }
        }
        return used
    }

    /**
     * 某一类（[BlockType.APP] / [BlockType.MINI]）已经加过的标识（含二级菜单里的），
     * 用于多选列表标出「已在别处」并去重。
     */
    fun usedKeys(blocks: List<HomeBlock>, type: String): Set<String> {
        val used = mutableSetOf<String>()
        fun collect(block: HomeBlock) {
            if (block.type == type && block.pkg.isNotEmpty()) used.add(block.pkg)
        }
        blocks.forEach { block ->
            collect(block)
            block.subItems.forEach { child -> collect(child) }
        }
        return used
    }

    /**
     * 首次运行的默认色块：9 个内置功能全部保留，其中 5 个收进两个二级菜单，
     * 一级只占 6 格（2 行），既好点又留有 3 个空位可以自己加。
     *
     * 「说话」放在第一格：对老人来说「说一句」比在一排图标里找按钮更自然，
     * 而且它后面还能通过语音打到任意人，是比「拨打」更宽的入口。
     */
    fun defaultBlocks(ctx: Context): MutableList<HomeBlock> {
        val tools = builtin(ctx, HomeAction.TORCH, label = ctx.getString(R.string.block_tools)).apply {
            color = ContextCompat.getColor(ctx, R.color.btn_clean)
            subItems = mutableListOf(
                builtin(ctx, HomeAction.TORCH),
                builtin(ctx, HomeAction.WIFI),
                builtin(ctx, HomeAction.DATA)
            )
        }
        val maintain = builtin(ctx, HomeAction.CLEAN, label = ctx.getString(R.string.block_maintain)).apply {
            color = ContextCompat.getColor(ctx, R.color.btn_reboot)
            subItems = mutableListOf(
                builtin(ctx, HomeAction.CLEAN),
                builtin(ctx, HomeAction.REBOOT)
            )
        }
        return mutableListOf(
            builtin(ctx, HomeAction.VOICE),
            builtin(ctx, HomeAction.PHONE),
            builtin(ctx, HomeAction.CONTACTS),
            builtin(ctx, HomeAction.DOUYIN),
            builtin(ctx, HomeAction.WXAPP),
            tools,
            maintain
        )
    }
}
