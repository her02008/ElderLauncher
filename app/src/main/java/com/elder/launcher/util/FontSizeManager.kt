package com.elder.launcher.util

import android.content.Context
import android.content.res.Configuration
import com.elder.launcher.util.PrefsManager.getFontLevel

object FontSizeManager {
    const val LEVEL_NORMAL = 0
    const val LEVEL_LARGE = 1
    const val LEVEL_XLARGE = 2

    fun scaleForLevel(level: Int): Float = when (level) {
        LEVEL_LARGE -> 1.25f
        LEVEL_XLARGE -> 1.6f
        else -> 1.0f
    }

    fun currentScale(ctx: Context): Float = scaleForLevel(getFontLevel(ctx))

    fun apply(ctx: Context): Context {
        val level = getFontLevel(ctx)
        val scale = scaleForLevel(level)
        val configuration = Configuration(ctx.resources.configuration)
        configuration.fontScale = scale
        return ctx.createConfigurationContext(configuration)
    }

    fun levelLabel(level: Int): String = when (level) {
        LEVEL_NORMAL -> "标准"
        LEVEL_LARGE -> "大"
        LEVEL_XLARGE -> "超大"
        else -> "标准"
    }
}
