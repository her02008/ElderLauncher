package com.elder.launcher.util

import android.content.Context
import android.content.SharedPreferences
import com.elder.launcher.R

object PrefsManager {
    private const val PREFS = "elder_prefs"
    private const val KEY_FONT_LEVEL = "font_level"
    private const val KEY_VOICE = "voice_enabled"
    private const val KEY_SOS_NAME = "sos_name"
    private const val KEY_SOS_PHONE = "sos_phone"
    private const val KEY_QUICK_CONTACTS = "quick_contacts"

    /** 旧版只能存一个小程序标识，仅用于一次性迁移 */
    private const val KEY_WX_MINI_LEGACY = "wx_mini_program"

    /** 微信小程序列表（名称 + 标识/链接），JSON 数组 */
    private const val KEY_WX_MINIS = "wx_mini_apps"

    /** 视频号分享链接（设置 → 视频号直达） */
    private const val KEY_CHANNELS_LINK = "channels_link"

    private const val KEY_HOME_BLOCKS = "home_blocks"

    /** 主页三大区域（紧急求助 / 功能块 / 快捷拨号）的上下顺序 */
    private const val KEY_HOME_ORDER = "home_order"

    /** 高级功能管理密码：只存随机盐和 SHA-256 摘要，绝不存明文 */
    private const val KEY_ADMIN_SALT = "admin_salt"
    private const val KEY_ADMIN_HASH = "admin_hash"

    /** 未接来电（只存还没处理掉的，JSON 数组，见 MissedCall） */
    private const val KEY_MISSED_CALLS = "missed_calls"

    /** 最近抓到的通知原文，纯排查用（文件头带判定结果），不进任何界面逻辑 */
    private const val KEY_MISSED_DEBUG = "missed_debug"

    // ---------------- 「恰了么？」超时预警 ----------------
    private const val KEY_ALIVE_ENABLED = "alive_enabled"
    private const val KEY_ALIVE_LAST_ACTIVE = "alive_last_active"
    private const val KEY_ALIVE_LAST_ALERT = "alive_last_alert"
    private const val KEY_ALIVE_ALERT_COUNT = "alive_alert_count"
    private const val KEY_ALIVE_PAUSE_UNTIL = "alive_pause_until"
    private const val KEY_ALIVE_THRESHOLD_HOURS = "alive_threshold_hours"
    private const val KEY_ALIVE_PHONE = "alive_notify_phone"

    /** 下一次检查闹钟的时间（自己记的，AlarmManager 读不回来） */
    private const val KEY_ALIVE_NEXT_CHECK = "alive_next_check"

    // ---------------- 跌倒检测 ----------------

    /** 只有一个总开关；观察窗/响应窗等参数固定在 FallDetector 的常量里，不给长辈调 */
    private const val KEY_FALL_ENABLED = "fall_enabled"

    // ---------------- 远程设置（子女端） ----------------
    private const val KEY_REMOTE_ENDPOINT = "remote_endpoint"
    private const val KEY_REMOTE_CODE = "remote_code"
    private const val KEY_REMOTE_TOKEN = "remote_token"
    private const val KEY_REMOTE_DEVICE_ID = "remote_device_id"
    private const val KEY_REMOTE_REV = "remote_rev"
    private const val KEY_REMOTE_LAST_SYNC = "remote_last_sync"
    private const val KEY_REMOTE_LAST_ERROR = "remote_last_error"
    private const val KEY_REMOTE_PENDING = "remote_pending"
    private const val KEY_REMOTE_ACK_QUEUE = "remote_ack_queue"
    private const val KEY_REMOTE_MSG = "remote_msg"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getFontLevel(ctx: Context): Int = sp(ctx).getInt(KEY_FONT_LEVEL, 0)
    fun setFontLevel(ctx: Context, level: Int) {
        sp(ctx).edit().putInt(KEY_FONT_LEVEL, level).apply()
    }

    fun isVoiceEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_VOICE, true)
    fun setVoiceEnabled(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_VOICE, enabled).apply()
    }

    fun getSosContact(ctx: Context): Pair<String, String> {
        val s = sp(ctx)
        return s.getString(KEY_SOS_NAME, "")!! to s.getString(KEY_SOS_PHONE, "")!!
    }
    fun setSosContact(ctx: Context, name: String, phone: String) {
        sp(ctx).edit()
            .putString(KEY_SOS_NAME, name)
            .putString(KEY_SOS_PHONE, phone)
            .apply()
    }
    fun hasSosContact(ctx: Context): Boolean = getSosContact(ctx).second.isNotEmpty()

    /** 主页快捷拨号；顺序就是主页横排的显示顺序 */
    fun getQuickContacts(ctx: Context): MutableList<QuickContact> =
        QuickContact.jsonToList(sp(ctx).getString(KEY_QUICK_CONTACTS, "[]")!!).toMutableList()

    fun setQuickContacts(ctx: Context, list: List<QuickContact>) {
        sp(ctx).edit().putString(KEY_QUICK_CONTACTS, QuickContact.listToJson(list)).apply()
    }

    /**
     * 微信小程序列表。
     *
     * 新键不存在时会去读旧版的单个标识做一次迁移（取到非空值就变成列表里的第一项），
     * 所以老用户升级上来原来配的那个小程序不会丢。
     */
    fun getMiniApps(ctx: Context): MutableList<WxMiniApp> {
        val s = sp(ctx)
        if (s.contains(KEY_WX_MINIS)) {
            return WxMiniApp.jsonToList(s.getString(KEY_WX_MINIS, "[]")!!).toMutableList()
        }
        val legacy = s.getString(KEY_WX_MINI_LEGACY, "")!!.trim()
        if (legacy.isEmpty()) return mutableListOf()
        return mutableListOf(
            WxMiniApp(name = ctx.getString(R.string.wx_mini_default_name), target = legacy)
        )
    }

    fun setMiniApps(ctx: Context, list: List<WxMiniApp>) {
        sp(ctx).edit().putString(KEY_WX_MINIS, WxMiniApp.listToJson(list)).apply()
    }

    /**
     * 主页功能色块。从未保存过（首次运行）返回默认色块；
     * 保存过但用户把色块全删了，则返回空列表。
     */
    fun getHomeBlocks(ctx: Context): MutableList<HomeBlock> {
        if (!sp(ctx).contains(KEY_HOME_BLOCKS)) return BlockCatalog.defaultBlocks(ctx)
        return HomeBlock.jsonToList(sp(ctx).getString(KEY_HOME_BLOCKS, "[]")!!)
    }

    fun setHomeBlocks(ctx: Context, blocks: List<HomeBlock>) {
        sp(ctx).edit().putString(KEY_HOME_BLOCKS, HomeBlock.listToJson(blocks)).apply()
    }

    /**
     * 视频号分享链接（设置 → 视频号直达）。
     * 保存后主页「视频号」块优先用这条链接让微信直达，失败再退回无障碍自动导航。
     */
    fun getChannelsLink(ctx: Context): String =
        sp(ctx).getString(KEY_CHANNELS_LINK, "")!!.trim()

    fun setChannelsLink(ctx: Context, link: String) {
        sp(ctx).edit().putString(KEY_CHANNELS_LINK, link.trim()).apply()
    }

    /** 恢复默认功能块 = 把键删掉，读取时就会回落到默认色块 */
    fun clearHomeBlocks(ctx: Context) {
        sp(ctx).edit().remove(KEY_HOME_BLOCKS).apply()
    }

    /**
     * 恢复所有设置：清空整个偏好文件——主页面块、快捷拨号、微信小程序、视频号链接、
     * 紧急联系人、字号、语音、首页板块顺序、管理密码（盐 + 摘要）全在里面，
     * 清掉之后所有读取都会回落到出厂默认。
     *
     * 用 `commit()` 而不是 `apply()`：重置完用户可能马上按 Home 键看效果，
     * 立刻落盘才不会出现「显示恢复了、重启又回来」。
     */
    fun clearAll(ctx: Context) {
        sp(ctx).edit().clear().commit()
    }

    // ---------------- 主页区域顺序 ----------------

    /**
     * 主页三大区域的显示顺序，返回的列表**一定**包含 [SECTION_KEYS] 里全部三项且各出现一次：
     * 存过的合法项按原顺序保留，重复/非法项丢弃，缺的按 [SECTION_KEYS] 顺序补到末尾。
     * 这样即使以后加了新区域，老数据也不会漏掉它。
     */
    fun getHomeOrder(ctx: Context): MutableList<String> {
        val stored = sp(ctx).getString(KEY_HOME_ORDER, "")!!
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val result = mutableListOf<String>()
        stored.forEach { key -> if (key in SECTION_KEYS && key !in result) result.add(key) }
        SECTION_KEYS.forEach { key -> if (key !in result) result.add(key) }
        return result
    }

    fun setHomeOrder(ctx: Context, order: List<String>) {
        sp(ctx).edit().putString(KEY_HOME_ORDER, order.joinToString(",")).apply()
    }

    // ---------------- 未接来电 ----------------

    fun getMissedCalls(ctx: Context): List<MissedCall> =
        MissedCall.jsonToList(sp(ctx).getString(KEY_MISSED_CALLS, "[]")!!)

    fun setMissedCalls(ctx: Context, list: List<MissedCall>) {
        sp(ctx).edit().putString(KEY_MISSED_CALLS, MissedCall.listToJson(list)).apply()
    }

    fun clearMissedCalls(ctx: Context) {
        sp(ctx).edit().remove(KEY_MISSED_CALLS).apply()
    }

    /** 排查用的通知原文（多条用 \n 分隔，只保留最近几条） */
    fun getMissedDebug(ctx: Context): String = sp(ctx).getString(KEY_MISSED_DEBUG, "")!!

    fun setMissedDebug(ctx: Context, text: String) {
        sp(ctx).edit().putString(KEY_MISSED_DEBUG, text).apply()
    }

    // ---------------- 「恰了么？」超时预警 ----------------

    fun isAliveEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ALIVE_ENABLED, false)

    /**
     * 打开时把「最后活跃」重置成现在。
     *
     * 不重置的话，第一次开启会拿一条很旧的时间去比阈值，**当场就报警**。
     * 顺带清零报警计数，代表「从这一刻重新开始观察」。
     */
    fun setAliveEnabled(ctx: Context, enabled: Boolean) {
        val editor = sp(ctx).edit().putBoolean(KEY_ALIVE_ENABLED, enabled)
        if (enabled) {
            editor.putLong(KEY_ALIVE_LAST_ACTIVE, System.currentTimeMillis())
                .putInt(KEY_ALIVE_ALERT_COUNT, 0)
                .putLong(KEY_ALIVE_LAST_ALERT, 0L)
        }
        editor.apply()
    }

    fun getAliveLastActive(ctx: Context): Long = sp(ctx).getLong(KEY_ALIVE_LAST_ACTIVE, 0L)

    fun setAliveLastActive(ctx: Context, at: Long) {
        sp(ctx).edit().putLong(KEY_ALIVE_LAST_ACTIVE, at).apply()
    }

    /** 多久没操作就提醒，默认 24 小时 */
    fun getAliveThresholdHours(ctx: Context): Int =
        sp(ctx).getInt(KEY_ALIVE_THRESHOLD_HOURS, 24)

    fun setAliveThresholdHours(ctx: Context, hours: Int) {
        sp(ctx).edit().putInt(KEY_ALIVE_THRESHOLD_HOURS, hours).apply()
    }

    /** 预警短信接收号；空 = 回落紧急联系人 */
    fun getAlivePhone(ctx: Context): String = sp(ctx).getString(KEY_ALIVE_PHONE, "")!!.trim()

    fun setAlivePhone(ctx: Context, phone: String) {
        sp(ctx).edit().putString(KEY_ALIVE_PHONE, phone.trim()).apply()
    }

    /** 暂停到这个时间点（毫秒），0 = 没暂停 */
    fun getAlivePauseUntil(ctx: Context): Long = sp(ctx).getLong(KEY_ALIVE_PAUSE_UNTIL, 0L)

    fun setAlivePauseUntil(ctx: Context, at: Long) {
        sp(ctx).edit().putLong(KEY_ALIVE_PAUSE_UNTIL, at).apply()
    }

    fun getAliveLastAlert(ctx: Context): Long = sp(ctx).getLong(KEY_ALIVE_LAST_ALERT, 0L)

    fun setAliveLastAlert(ctx: Context, at: Long) {
        sp(ctx).edit().putLong(KEY_ALIVE_LAST_ALERT, at).apply()
    }

    fun getAliveAlertCount(ctx: Context): Int = sp(ctx).getInt(KEY_ALIVE_ALERT_COUNT, 0)

    fun setAliveAlertCount(ctx: Context, count: Int) {
        sp(ctx).edit().putInt(KEY_ALIVE_ALERT_COUNT, count).apply()
    }

    fun getAliveNextCheck(ctx: Context): Long = sp(ctx).getLong(KEY_ALIVE_NEXT_CHECK, 0L)

    fun setAliveNextCheck(ctx: Context, at: Long) {
        sp(ctx).edit().putLong(KEY_ALIVE_NEXT_CHECK, at).apply()
    }

    // ---------------- 跌倒检测 ----------------

    fun isFallEnabled(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_FALL_ENABLED, false)

    fun setFallEnabled(ctx: Context, enabled: Boolean) {
        sp(ctx).edit().putBoolean(KEY_FALL_ENABLED, enabled).apply()
    }

    // ---------------- 管理密码（盐 + 摘要，见 AdminGate） ----------------

    fun getAdminSalt(ctx: Context): String = sp(ctx).getString(KEY_ADMIN_SALT, "")!!

    fun setAdminSalt(ctx: Context, salt: String) {
        sp(ctx).edit().putString(KEY_ADMIN_SALT, salt).apply()
    }

    fun getAdminHash(ctx: Context): String = sp(ctx).getString(KEY_ADMIN_HASH, "")!!

    fun setAdminHash(ctx: Context, hash: String) {
        sp(ctx).edit().putString(KEY_ADMIN_HASH, hash).apply()
    }

    // ---------------- 远程设置（子女端） ----------------

    /**
     * 设备标识：首次用到时生成并落盘。
     *
     * 服务端把它和 token 绑在一起，换手机就是换 deviceId，旧 token 自然对不上。
     * 恢复出厂不会删它（`clearBinding` 保留），免得重绑后又变成一台"新设备"。
     */
    fun getDeviceId(ctx: Context): String {
        val s = sp(ctx)
        val existed = s.getString(KEY_REMOTE_DEVICE_ID, "")!!
        if (existed.isNotEmpty()) return existed
        val id = java.util.UUID.randomUUID().toString()
        s.edit().putString(KEY_REMOTE_DEVICE_ID, id).apply()
        return id
    }

    fun getRemoteEndpoint(ctx: Context): String = sp(ctx).getString(KEY_REMOTE_ENDPOINT, "")!!

    fun getRemoteCode(ctx: Context): String = sp(ctx).getString(KEY_REMOTE_CODE, "")!!

    fun getRemoteToken(ctx: Context): String = sp(ctx).getString(KEY_REMOTE_TOKEN, "")!!

    /** 三项齐全才算绑好；少一项就当没绑，避免半截状态去发请求 */
    fun isRemoteBound(ctx: Context): Boolean =
        getRemoteEndpoint(ctx).isNotEmpty() &&
            getRemoteCode(ctx).isNotEmpty() &&
            getRemoteToken(ctx).isNotEmpty()

    fun setRemoteBinding(ctx: Context, endpoint: String, code: String, token: String) {
        sp(ctx).edit()
            .putString(KEY_REMOTE_ENDPOINT, endpoint)
            .putString(KEY_REMOTE_CODE, code)
            .putString(KEY_REMOTE_TOKEN, token)
            .apply()
    }

    /** 解除绑定：只清绑定相关的，deviceId 和上一次同步时间留着便于诊断 */
    fun clearRemoteBinding(ctx: Context) {
        sp(ctx).edit()
            .remove(KEY_REMOTE_ENDPOINT)
            .remove(KEY_REMOTE_CODE)
            .remove(KEY_REMOTE_TOKEN)
            .remove(KEY_REMOTE_REV)
            .remove(KEY_REMOTE_PENDING)
            .remove(KEY_REMOTE_ACK_QUEUE)
            .remove(KEY_REMOTE_MSG)
            .apply()
    }

    /** 已经执行到哪个修订号。下次只拉这个之后的东西 */
    fun getRemoteRev(ctx: Context): Int = sp(ctx).getInt(KEY_REMOTE_REV, -1)

    fun setRemoteRev(ctx: Context, rev: Int) {
        sp(ctx).edit().putInt(KEY_REMOTE_REV, rev).apply()
    }

    fun getRemoteLastSync(ctx: Context): Long = sp(ctx).getLong(KEY_REMOTE_LAST_SYNC, 0L)

    fun setRemoteLastSync(ctx: Context, at: Long) {
        sp(ctx).edit().putLong(KEY_REMOTE_LAST_SYNC, at).apply()
    }

    /** 最近一次失败原因；空串 = 上次是成功的 */
    fun getRemoteError(ctx: Context): String = sp(ctx).getString(KEY_REMOTE_LAST_ERROR, "")!!

    fun setRemoteError(ctx: Context, error: String) {
        sp(ctx).edit().putString(KEY_REMOTE_LAST_ERROR, error).apply()
    }

    /** 等老人确认的危险指令（JSON 数组，见 RemoteCommand.Incoming） */
    fun getRemotePending(ctx: Context): String = sp(ctx).getString(KEY_REMOTE_PENDING, "[]")!!

    fun setRemotePending(ctx: Context, json: String) {
        sp(ctx).edit().putString(KEY_REMOTE_PENDING, json).apply()
    }

    /** 待上报的执行结果（JSON 数组），下次同步一起 ack 掉 */
    fun getRemoteAckQueue(ctx: Context): String = sp(ctx).getString(KEY_REMOTE_ACK_QUEUE, "[]")!!

    fun setRemoteAckQueue(ctx: Context, json: String) {
        sp(ctx).edit().putString(KEY_REMOTE_ACK_QUEUE, json).apply()
    }

    /** 子女留言，等回到桌面时弹一次 */
    fun getRemoteMessage(ctx: Context): String = sp(ctx).getString(KEY_REMOTE_MSG, "")!!

    fun setRemoteMessage(ctx: Context, text: String) {
        sp(ctx).edit().putString(KEY_REMOTE_MSG, text).apply()
    }

    // ---------------- 主页区域标识 ----------------

    /** 紧急求助 */
    const val SECTION_SOS = "sos"

    /** 功能色块九宫格 */
    const val SECTION_GRID = "grid"

    /** 快捷拨号 */
    const val SECTION_QUICK = "quick"

    /** 全部区域；列在前面的就是默认顺序 */
    val SECTION_KEYS = listOf(SECTION_SOS, SECTION_GRID, SECTION_QUICK)
}
