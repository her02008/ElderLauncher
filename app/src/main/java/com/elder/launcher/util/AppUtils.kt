package com.elder.launcher.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.LruCache

/** 桌面可见的应用 */
data class AppEntry(val pkg: String, val label: String)

/**
 * 已安装应用枚举与图标缓存。
 * 需要 Manifest 里的 QUERY_ALL_PACKAGES 权限（已声明）。
 */
object AppUtils {

    private val iconCache = LruCache<String, Drawable>(64)

    /** 取出所有带桌面入口的应用，排除自己，按名称排序 */
    fun loadLaunchableApps(ctx: Context): List<AppEntry> {
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(intent, 0)
            }
        } catch (_: Exception) {
            emptyList()
        }
        return resolved.asSequence()
            .filter { info -> info.activityInfo != null }
            .map { info -> AppEntry(info.activityInfo.packageName, info.loadLabel(pm).toString()) }
            .filter { entry -> entry.pkg != ctx.packageName && entry.pkg.isNotEmpty() }
            .distinctBy { entry -> entry.pkg }
            .sortedBy { entry -> entry.label }
            .toList()
    }

    /** 应用图标（带缓存），获取失败返回 null */
    fun iconOf(ctx: Context, pkg: String): Drawable? {
        if (pkg.isEmpty()) return null
        iconCache.get(pkg)?.let { return it }
        return try {
            val icon = ctx.packageManager.getApplicationIcon(pkg)
            iconCache.put(pkg, icon)
            icon
        } catch (_: Exception) {
            null
        }
    }
}
