package com.elder.launcher.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.widget.Toast
import com.elder.launcher.ChannelsBridgeActivity
import com.elder.launcher.R
import com.elder.launcher.service.ElderAccessibilityService
import com.elder.launcher.util.PermissionHelper.isAppInstalled

object ActionExecutor {

    const val PKG_DOUYIN = "com.ss.android.ugc.aweme"
    const val PKG_DOUYIN_LITE = "com.ss.android.ugc.aweme.lite"
    const val PKG_WEIXIN = "com.tencent.mm"

    fun dial(ctx: Context, phone: String) {
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    fun call(ctx: Activity, phone: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone"))
        ctx.startActivity(intent)
    }

    /**
     * 给指定号码发短信：打开短信应用的**编辑界面**并带好号码，内容由用户自己写。
     *
     * 不发 `SEND_SMS` 静默发送——长辈场景里"以为在看、其实发出去了"风险太大，
     * 一定要让用户看到内容再按发送。`smsto:` 是各家短信应用都认的标准写法。
     */
    fun sendSms(ctx: Context, phone: String) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
            return
        } catch (_: Exception) {
        }
        try {
            val fallback = Intent(Intent.ACTION_VIEW, Uri.parse("sms:$phone"))
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(fallback)
        } catch (_: Exception) {
            toast(ctx, R.string.not_installed)
        }
    }

    fun openContacts(ctx: Context) {
        val intent = Intent(Intent.ACTION_DEFAULT, Uri.parse("content://com.android.contacts/contacts"))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    fun openDouyin(ctx: Context) {
        val pkg = if (isAppInstalled(ctx, PKG_DOUYIN)) PKG_DOUYIN
        else if (isAppInstalled(ctx, PKG_DOUYIN_LITE)) PKG_DOUYIN_LITE
        else null
        if (pkg == null) {
            toast(ctx, R.string.not_installed)
            return
        }
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
        intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent?.let { ctx.startActivity(it) }
    }

    /**
     * 打开微信小程序。
     *
     * [target] 支持几种写法，按下面顺序判断：
     * 1. `#小程序://名称/口令`：微信「Short Link 分享口令」——先从整段文案里把口令抠出来，
     *    尝试包装成 `weixin://dl/business/?t=口令` 直接拉起；官方口令设计为「在微信里点击」，
     *    这一步猜不中时退回微信首页并提示用户把口令发到聊天里点；
     * 2. `https://channels.weixin.qq.com/...`：视频号分享链接——先让微信自己接
     *    （部分版本注册了自家域名的 AppLink），不行再交给浏览器中转拉微信，最后退回「无障碍自动点」；
     * 3. `weixin://...`：小程序后台生成的**明文 / 加密 Scheme**，原样打开，这是最可靠的一种；
     * 4. 其余 `http(s)://...`：URL Link / 分享链接，交给系统（浏览器会再拉起微信）；
     * 5. 其余（`gh_` 开头的原始 ID、`wx` 开头的 AppID、`xxx@app`）：包装成
     *    `weixin://dl/business/?username=...`。
     *
     * @param name 只用来组织提示文案，可以为空
     */
    fun openMiniProgram(ctx: Context, target: String?, name: String?) {
        if (!isAppInstalled(ctx, PKG_WEIXIN)) {
            toast(ctx, R.string.wx_not_installed)
            return
        }
        val raw = target?.trim().orEmpty()
        if (raw.isEmpty()) return

        // 0. 微信「添加到桌面」生成的系统快捷方式 → LauncherApps 原样启动（间接直达小程序）
        if (WxShortcuts.isShortcutTarget(raw)) {
            if (!WxShortcuts.start(ctx, WxShortcuts.idOf(raw))) {
                // 快捷方式已被用户从系统桌面删掉，或本项目不是默认桌面
                toast(ctx, R.string.wx_shortcut_missing)
                openWeixinHome(ctx)
            }
            return
        }
        // 0.5 别家 App（浏览器、其它平台）发来的系统快捷方式，同样原样启动
        if (PinnedShortcuts.isTarget(raw)) {
            if (!PinnedShortcuts.start(ctx, raw)) toast(ctx, R.string.shortcut_open_failed)
            return
        }
        // 1. 「#小程序://」分享口令
        val passcode = extractPasscode(raw)
        if (passcode != null) {
            openPasscode(ctx, passcode)
            return
        }
        // 2. 视频号分享链接
        if (raw.contains("channels.weixin.qq.com")) {
            if (!tryOpenChannelsLink(ctx, raw)) {
                // 链接打不开就退回「无障碍自动点」进视频号
                openChannels(ctx)
            }
            return
        }

        val uri = miniUri(raw)
        if (uri != null) {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        val label = name?.trim().takeUnless { it.isNullOrEmpty() } ?: ctx.getString(R.string.act_wxapp)
        Toast.makeText(ctx, ctx.getString(R.string.wx_mini_fallback, label), Toast.LENGTH_LONG).show()
        openWeixinHome(ctx)
    }

    /** 从一整段分享文案里抠出 `#小程序://...` 口令；没有返回 null */
    fun extractPasscode(text: String): String? {
        if (!text.contains("#小程序://")) return null
        val start = text.indexOf("#小程序://")
        // 口令后面跟着的空白/标点都算结束，防止把转发文案一起带进来
        val rest = text.substring(start)
        val end = rest.indexOfFirst { it.isWhitespace() || it == '，' || it == '。' || it == '、' || it == '；' }
        val token = if (end < 0) rest else rest.substring(0, end)
        return token.ifEmpty { null }
    }

    /**
     * 尝试打开微信 Short Link 口令（`#小程序://名称/口令`）。
     * 官方口令是给「微信内点击」用的，这里先猜一次拉起 Scheme，猜不中就回微信首页并教用户怎么用。
     */
    private fun openPasscode(ctx: Context, passcode: String) {
        val ticket = passcode.substringAfterLast('/')
        if (ticket.isNotEmpty()) {
            val candidates = listOf(
                "weixin://dl/business/?t=$ticket",
                "weixin://dl/business/?ticket=$ticket"
            )
            for (url in candidates) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    ctx.startActivity(intent)
                    return
                } catch (_: Exception) {
                }
            }
        }
        toast(ctx, R.string.wx_mini_passcode_fallback)
        openWeixinHome(ctx)
    }

    /**
     * 尝试用视频号分享链接直达：微信直接接 → 自带 WebView 中转（自动点「打开微信」）→ 浏览器。
     * @return true = 已经发起跳转；false = 三条路都失败，调用方自行兜底
     */
    fun tryOpenChannelsLink(ctx: Context, link: String): Boolean {
        val uri = Uri.parse(link)
        // 微信部分版本给自己域名注册了 AppLink，先让它自己接
        val direct = Intent(Intent.ACTION_VIEW, uri).setPackage(PKG_WEIXIN)
        direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(direct)
            return true
        } catch (_: Exception) {
        }
        // 自带透明 WebView 中转：加载 H5 后自动点「打开微信」并拦截 weixin:// 跳转，
        // 省掉「甩给系统浏览器 → 用户再点一次」那两步
        try {
            val bridge = Intent(ctx, ChannelsBridgeActivity::class.java)
            bridge.putExtra(ChannelsBridgeActivity.EXTRA_URL, link)
            bridge.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(bridge)
            return true
        } catch (_: Exception) {
        }
        // 浏览器中转兜底：H5 页会再拉起微信（部分机型需要点一下「打开微信」）
        val web = Intent(Intent.ACTION_VIEW, uri)
        web.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(web)
            return true
        } catch (_: Exception) {
        }
        return false
    }

    /** 把用户填的小程序标识/链接拼成可以 startActivity 的 uri；拼不出来返回 null */
    private fun miniUri(target: String): Uri? {
        if (target.isEmpty()) return null
        val lower = target.lowercase()
        return when {
            lower.startsWith("weixin:") ||
                lower.startsWith("http://") ||
                lower.startsWith("https://") -> Uri.parse(target)
            // 老写法：原始 ID / AppID / gh_xxx@app
            else -> Uri.parse("weixin://dl/business/?username=$target")
        }
    }

    /** 只把微信拉到前台（没有配置小程序、或者直达失败时的兜底） */
    fun openWeixinHome(ctx: Context) {
        if (!isAppInstalled(ctx, PKG_WEIXIN)) {
            toast(ctx, R.string.wx_not_installed)
            return
        }
        val intent = ctx.packageManager.getLaunchIntentForPackage(PKG_WEIXIN) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    /** 打开任意已安装 APP（主页自定义的快捷方式） */
    fun openApp(ctx: Context, pkg: String) {
        if (pkg.isEmpty()) {
            toast(ctx, R.string.not_installed)
            return
        }
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            toast(ctx, R.string.not_installed)
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (_: Exception) {
            toast(ctx, R.string.not_installed)
        }
    }

    /**
     * 打开一条系统「固定快捷方式」（别家 App「发送到桌面」来的，[PinnedShortcuts] 那套）。
     * 失效（发布方删了快捷方式 / 换了默认桌面）时给一句人话提示，别让长辈以为手机坏了。
     */
    fun openShortcut(ctx: Context, target: String) {
        if (!PinnedShortcuts.start(ctx, target)) toast(ctx, R.string.shortcut_open_failed)
    }

    /**
     * 直达微信「视频号」。
     *
     * 优先级：
     * 1. 用户在「设置 → 视频号直达」里保存的分享链接——微信若肯接就直达具体视频；
     * 2. 都不用/都不行时走 [openChannelsFallback]。
     */
    fun openChannels(ctx: Context) {
        if (!isAppInstalled(ctx, PKG_WEIXIN)) {
            toast(ctx, R.string.wx_not_installed)
            return
        }
        val saved = PrefsManager.getChannelsLink(ctx)
        if (saved.isNotEmpty() && tryOpenChannelsLink(ctx, saved)) return

        if (openChannelsFallback(ctx)) return

        // 无障碍没开启：至少把微信打开，别让用户觉得点了没反应
        toast(ctx, R.string.wx_channels_fallback)
        openWeixinHome(ctx)
    }

    /**
     * 兜底路径：无障碍自动点进视频号（拉起微信 → 点「发现」→「视频号」）。
     *
     * 与 [openChannels] 分开，是因为中转页 [com.elder.launcher.ChannelsBridgeActivity] 失败后
     * 要直接走这里——否则会拿着同一条链接再转一圈，死循环。
     *
     * **不要再往这里加 `weixin://channels` 之类的 scheme 试探**：微信对这类私有 scheme
     * 一律不响应，而 `resolveActivity` 又能解析到微信，于是 `startActivity` 静默失败、
     * 我们还以为跳转成功了直接 return——用户看到的就是「弹了个提示，然后什么都没打开」。
     *
     * @return true = 无障碍已接管（会自己导航进视频号）；false = 无障碍没开，调用方自行兜底
     */
    fun openChannelsFallback(ctx: Context): Boolean {
        if (!isAppInstalled(ctx, PKG_WEIXIN)) {
            toast(ctx, R.string.wx_not_installed)
            return true
        }
        return ElderAccessibilityService.openChannels(ctx)
    }

    /** 当前 App 是不是系统的默认桌面 */
    fun isDefaultHome(ctx: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = try {
            ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (_: Exception) {
            null
        }
        return resolved?.activityInfo?.packageName == ctx.packageName
    }

    /**
     * 跳到系统的「默认应用 → 桌面」设置页（所有 ROM 都可用，鸿蒙 4 实测能跳转）。
     * 注意：不要用 RoleManager 的 HOME 角色弹窗——鸿蒙 4 上发出后界面无响应（静默失败）。
     */
    fun openHomeSettings(ctx: Context) {
        try {
            val intent = Intent(Settings.ACTION_HOME_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            return
        } catch (_: Exception) {
        }
        try {
            val fallback = Intent(Settings.ACTION_SETTINGS)
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(fallback)
        } catch (_: Exception) {
        }
    }

    fun openWifiSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val intent = Intent(Settings.Panel.ACTION_WIFI)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    fun openDataSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val intent = Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        val intent = Intent(Settings.ACTION_DATA_USAGE_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_SETTINGS)
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(fallback)
        }
    }

    fun openAccessibilitySettings(ctx: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }

    /**
     * 打开系统的「通知使用权」页。
     *
     * 未接来电要读电话 App 的通知，必须由用户在这里手动勾选本应用——没有别的办法
     * （它跟运行时权限不是一回事，申请不来）。个别 ROM 没有这个入口时退到系统设置首页。
     */
    fun openNotificationListenerSettings(ctx: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Settings.ACTION_SETTINGS)
            fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(fallback)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 打开系统时钟（落在闹钟页）。
     *
     * 系统**没有**「打开时钟」这种标准 Intent，`CATEGORY_APP_CLOCK` 也不存在。
     * 各家 ROM 通用的是 `AlarmClock.ACTION_SHOW_ALARMS`（跳闹钟列表），先试它；
     * 少数 ROM 不注册这个 action，再退到已知桌面时钟包的启动页。
     */
    fun openClock(ctx: Context) {
        if (startIfResolved(ctx, Intent(AlarmClock.ACTION_SHOW_ALARMS))) return
        for (pkg in CLOCK_PACKAGES) {
            val launch = ctx.packageManager.getLaunchIntentForPackage(pkg) ?: continue
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                ctx.startActivity(launch)
                return
            } catch (_: Exception) {
            }
        }
        toast(ctx, R.string.not_installed)
    }

    /**
     * 打开系统日历。
     *
     * `ACTION_MAIN + CATEGORY_APP_CALENDAR` 是官方推荐写法，交给用户设的默认日历应用；
     * 有些 ROM 不注册这个类别，就退到「查看当前时刻的日程」——日历应用都认这个 VIEW。
     */
    fun openCalendar(ctx: Context) {
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_CALENDAR)
        if (startIfResolved(ctx, main)) return

        val atTime = CalendarContract.CONTENT_URI.buildUpon()
            .appendPath("time")
            .appendPath(System.currentTimeMillis().toString())
            .build()
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(atTime, "vnd.android.cursor.item/event")
        if (startIfResolved(ctx, view)) return
        toast(ctx, R.string.not_installed)
    }

    /** 能解析到宿主就启动并返回 true；解析不到或启动抛异常返回 false（调用方换下一条路） */
    private fun startIfResolved(ctx: Context, intent: Intent): Boolean {
        val resolved = try {
            ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (_: Exception) {
            null
        }
        if (resolved == null) return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 常见桌面时钟包名，`ACTION_SHOW_ALARMS` 没人接时按顺序碰运气 */
    private val CLOCK_PACKAGES = listOf(
        "com.android.deskclock",
        "com.huawei.android.deskclock",
        "com.coloros.alarmclock",
        "com.android.alarmclock",
        "com.sec.android.app.clockpackage"
    )

    private fun toast(ctx: Context, resId: Int) {
        Toast.makeText(ctx, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toast(ctx: Context, resId: Int, arg: String) {
        Toast.makeText(ctx, ctx.getString(resId, arg), Toast.LENGTH_SHORT).show()
    }
}
