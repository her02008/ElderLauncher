package com.elder.launcher.util

import android.content.Context
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable

/**
 * 微信「添加到桌面」生成的系统快捷方式的读取与启动。
 *
 * 思路：微信小程序自带的「添加到桌面」会往系统注册一个**固定（pinned）快捷方式**，
 * Intent 里带微信自己签名字段，第三方无法伪造——但本项目注册了手机桌面（HOME + DEFAULT），
 * 系统允许默认桌面用 [android.content.pm.LauncherApps] 枚举这些快捷方式并原样启动
 * （签名校验全由系统/微信自己完成），于是就能间接直达小程序。
 *
 * 读取 / 启动 / 图标全部由 [PinnedShortcuts] 提供，这里只保留「微信」这一层前缀
 * （历史数据里存的都是 `wxshortcut://<id>`，不能改），以及微信专属的包名过滤。
 *
 * 其它 App（浏览器、别的平台小程序）发来的快捷方式走 [BlockType.SHORTCUT] +
 * `shortcut://<包名>/<id>`，与这里互不干扰。
 */
object WxShortcuts {

    /** 快捷方式 target 的统一前缀（历史格式，别改） */
    const val TARGET_PREFIX = "wxshortcut://"

    fun targetOf(id: String): String = TARGET_PREFIX + id

    fun idOf(target: String): String = target.removePrefix(TARGET_PREFIX)

    fun isShortcutTarget(target: String?): Boolean = target?.startsWith(TARGET_PREFIX) == true

    /** 读取微信已固定到桌面的快捷方式；不是默认桌面（或 API < 25）时返回空列表 */
    fun pinned(ctx: Context): List<ShortcutInfo> = PinnedShortcuts.all(ctx, ActionExecutor.PKG_WEIXIN)

    /** 按 id 启动微信的快捷方式（原样交给系统，直达小程序）；失败返回 false */
    fun start(ctx: Context, id: String): Boolean =
        PinnedShortcuts.start(ctx, PinnedShortcuts.targetOf(ActionExecutor.PKG_WEIXIN, id))

    /** 取快捷方式的图标（列表 / 桌面色块想用真图标时用），拿不到返回 null */
    fun icon(ctx: Context, info: ShortcutInfo): Drawable? = PinnedShortcuts.icon(ctx, info)
}
