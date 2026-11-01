package com.elder.launcher.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.provider.ContactsContract
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 快捷拨号的头像：导入、缓存、统一渲染成圆形。
 *
 * - 从相册选的图片会缩放后拷贝到应用私有目录 `filesDir/quick_icons`，
 *   这样不依赖相册权限，用户把原图删了头像也不会变空白
 * - 圆形头像统一在这里画（带 LruCache，key 里带尺寸），列表滚动时不会反复解码，
 *   管理页行头像、编辑弹窗预览、主页头像用不同尺寸也不会互相污染
 */
object QuickIconStore {

    private const val DIR_NAME = "quick_icons"
    private const val MAX_IMPORT_SIDE = 512
    private const val CACHE_COUNT = 100

    private val cache = object : LruCache<String, Bitmap>(CACHE_COUNT) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    /** 这个路径是不是我们自己目录里的图片（删之前判断，避免误删用户相册里的原图） */
    fun isOwned(ctx: Context, path: String?): Boolean =
        !path.isNullOrEmpty() && path.startsWith(dir(ctx).absolutePath)

    /**
     * 「恢复所有设置」时把导入的头像图片一起清掉。
     * 只动自己私有目录（[DIR_NAME]）里的文件，用户相册里的原图一张都不会碰。
     */
    fun clearAll(ctx: Context) {
        try {
            dir(ctx).listFiles()?.forEach { file -> file.delete() }
        } catch (_: Exception) {
        }
        cache.evictAll()
    }

    /**
     * 把相册里选中的图片缩放后存进私有目录，返回保存后的绝对路径；失败返回 null。
     * 调用方负责在用户放弃编辑时删掉（见 QuickDialsActivity 里的 imported 处理）。
     */
    fun importImage(ctx: Context, uri: Uri): String? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_IMPORT_SIDE)
            }
            val src = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null

            val file = File(dir(ctx), "quick_${UUID.randomUUID().toString().take(8)}.png")
            FileOutputStream(file).use { out -> src.compress(Bitmap.CompressFormat.PNG, 100, out) }
            src.recycle()
            file.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    fun delete(path: String?) {
        if (path.isNullOrEmpty()) return
        try {
            File(path).delete()
        } catch (_: Exception) {
        }
        // 缓存 key 里带路径，删文件后整体清一次最省事（删除是低频操作）
        cache.evictAll()
    }

    /** 私有目录里的图片 → 圆形位图 */
    fun circleImage(path: String?, size: Int): Bitmap? {
        if (path.isNullOrEmpty() || size <= 0) return null
        val key = "file:$path@$size"
        cache.get(key)?.let { return it }
        val src = decodeFile(path, size) ?: return null
        val out = circle(src, size)
        src.recycle()
        cache.put(key, out)
        return out
    }

    /** 通讯录头像（content uri）→ 圆形位图，没有头像返回 null */
    fun circleContactPhoto(ctx: Context, uriString: String?, size: Int): Bitmap? {
        if (uriString.isNullOrEmpty() || size <= 0) return null
        val key = "uri:$uriString@$size"
        cache.get(key)?.let { return it }
        val src = try {
            ContactsContract.Contacts.openContactPhotoInputStream(
                ctx.contentResolver, Uri.parse(uriString)
            )?.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        } ?: return null
        val out = circle(src, size)
        src.recycle()
        cache.put(key, out)
        return out
    }

    /** 彩色圆底 + 首字（既没图片也没通讯录头像时用） */
    fun circleText(text: String, color: Int, size: Int): Bitmap {
        val key = "text:$text:$color@$size"
        cache.get(key)?.let { return it }
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = color
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        if (text.isNotEmpty()) {
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.textSize = size * 0.46f
            val fm = paint.fontMetrics
            canvas.drawText(text, size / 2f, size / 2f - (fm.ascent + fm.descent) / 2f, paint)
        }
        cache.put(key, bitmap)
        return bitmap
    }

    // ---------------- 内部实现 ----------------

    private fun decodeFile(path: String, target: Int): Bitmap? {
        if (!File(path).exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, target)
        }
        return try {
            BitmapFactory.decodeFile(path, options)
        } catch (_: Exception) {
            null
        }
    }

    /** 居中裁剪成正方形后画进圆里 */
    private fun circle(src: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val side = minOf(src.width, src.height).toFloat()
        val scale = size / side
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(-(src.width - side) / 2f * scale, -(src.height - side) / 2f * scale)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(matrix)
            }
        }
        Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return out
    }

    private fun sampleSize(width: Int, height: Int, target: Int): Int {
        var sample = 1
        var maxSide = maxOf(width, height)
        while (maxSide / 2 >= target) {
            sample *= 2
            maxSide /= 2
        }
        return sample
    }
}
