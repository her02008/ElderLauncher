package com.elder.launcher.util

import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.util.LruCache

/**
 * 系统「固定快捷方式」（pinned shortcut）的读取、启动与图标缓存。
 *
 * 微信小程序、浏览器、其它 App 里的「添加到桌面 / 发送到桌面」，在 Android 8.0 以上都走
 * `ShortcutManager.requestPinShortcut()`：系统会**在默认桌面里**找一个声明了
 * `android.content.pm.action.CONFIRM_PIN_SHORTCUT` 的 Activity 并拉起它
 * （见 [com.elder.launcher.ShortcutPinActivity]）。用户接受后，这条快捷方式就归属本项目
 * （pinned for this launcher），于是可以用 [LauncherApps] 枚举出来、用
 * [LauncherApps.startShortcut] **原样启动**——签名、Intent 全由系统与发布方自己完成，
 * 我们既不伪造也不改写，所以对小程序、网页类快捷方式都成立。
 *
 * 身份编码统一为 `shortcut://<包名>/<快捷方式id>`，存在 [HomeBlock.pkg] 里。
 * 包名不含 `/`，所以第一个 `/` 就是分隔点。
 *
 * ⚠️ 固定关系是**按桌面应用**记的：只有在本项目是默认桌面时被接受的快捷方式才读得到；
 * 从别的桌面换过来时，之前那些要重新「发送到桌面」一次。
 */
object PinnedShortcuts {

    /** 目标串前缀 */
    const val PREFIX = "shortcut://"

    private val iconCache = LruCache<String, Drawable>(48)

    fun targetOf(pkg: String, id: String): String = "$PREFIX$pkg/$id"

    fun isTarget(target: String?): Boolean = target?.startsWith(PREFIX) == true

    fun pkgOf(target: String): String = target.removePrefix(PREFIX).substringBefore('/')

    fun idOf(target: String): String = target.removePrefix(PREFIX).substringAfter('/', "")

    /**
     * `ShortcutInfo.getPackage()`。`package` 在 Kotlin 里是关键字，只能加反引号调用，
     * 所以把这段丑代码收在这里，别处一律用这个函数。
     */
    @Suppress("DEPRECATION")
    fun packageOf(info: ShortcutInfo): String = info.`package`.orEmpty()

    /** 快捷方式显示名：短名优先，其次长名 */
    fun labelOf(info: ShortcutInfo): String =
        (info.shortLabel ?: info.longLabel)?.toString()?.trim().orEmpty()

    /** 目标串的默认名称（读不到快捷方式时兜底） */
    fun defaultLabel(): String = "快捷方式"

    /**
     * 读取已固定到本项目桌面的快捷方式。
     *
     * [pkg] 为 null 时查所有包。只有本项目是默认桌面时系统才放行，
     * 否则（或 API 太低）返回空列表，不抛异常、不让界面崩掉。
     */
    @Suppress("DEPRECATION")
    fun all(ctx: Context, pkg: String? = null): List<ShortcutInfo> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return emptyList()
        if (!ActionExecutor.isDefaultHome(ctx)) return emptyList()
        val la = ctx.getSystemService(LauncherApps::class.java) ?: return emptyList()
        return try {
            val query = LauncherApps.ShortcutQuery()
            if (pkg != null) query.setPackage(pkg)
            query.setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            val list = try {
                la.getShortcuts(query, Process.myUserHandle())
            } catch (_: NoSuchMethodError) {
                null
            }
            list.orEmpty().filter { it.isEnabled && it.id.isNotEmpty() }
        } catch (_: Exception) {
            // 权限不足 / 桌面切换中 / 刚卸载重装等情况都不该让界面崩掉
            emptyList()
        }
    }

    /** 按 `shortcut://包名/id` 启动快捷方式；失败返回 false */
    fun start(ctx: Context, target: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return false
        val pkg = pkgOf(target)
        val id = idOf(target)
        if (pkg.isEmpty() || id.isEmpty()) return false
        val la = ctx.getSystemService(LauncherApps::class.java) ?: return false
        val info = all(ctx, pkg).firstOrNull { it.id == id } ?: return false
        return try {
            la.startShortcut(info, null, null)
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 快捷方式自己的图标（带缓存）；拿不到返回 null，调用方退到宿主应用图标 */
    fun icon(ctx: Context, info: ShortcutInfo): Drawable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return null
        iconCache.get(info.id)?.let { return it }
        val la = ctx.getSystemService(LauncherApps::class.java) ?: return null
        val drawable = try {
            la.getShortcutIconDrawable(info, ctx.resources.displayMetrics.densityDpi)
        } catch (_: Exception) {
            null
        } ?: return null
        iconCache.put(info.id, drawable)
        return drawable
    }

    /** 按 `shortcut://包名/id` 取图标；读不到返回 null */
    fun iconOf(ctx: Context, target: String): Drawable? {
        val pkg = pkgOf(target)
        val id = idOf(target)
        if (pkg.isEmpty() || id.isEmpty()) return null
        val info = all(ctx, pkg).firstOrNull { it.id == id } ?: return null
        return icon(ctx, info)
    }
}
