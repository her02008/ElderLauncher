package com.elder.launcher.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * 主页「快捷拨号」里的一项。
 *
 * 头像优先级：自定义图片 [iconPath] > 通讯录头像 [photoUri] > 彩色圆底 + 名字首字 [color]。
 */
data class QuickContact(
    val name: String,
    val phone: String,
    /** 通讯录里的头像（content uri），从通讯录选人时带过来 */
    val photoUri: String? = null,
    /** 从相册选的自定义图片，已拷贝到应用私有目录，见 [QuickIconStore] */
    val iconPath: String? = null,
    /** 自定义颜色（ARGB）；0 表示还没设过，渲染时按位置从调色板取 */
    val color: Int = 0
) {
    /** 没有名字时退回显示号码 */
    fun displayName(): String = name.ifEmpty { phone }

    /** 彩色圆底上显示的首字 */
    fun initial(): String = name.trim().takeIf { it.isNotEmpty() }?.substring(0, 1) ?: "人"

    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("phone", phone)
        if (!photoUri.isNullOrEmpty()) put("photo", photoUri)
        if (!iconPath.isNullOrEmpty()) put("icon", iconPath)
        if (color != 0) put("color", color)
    }

    companion object {
        /** 快捷拨号数量上限（防呆，主页横排放太多也不合适） */
        const val MAX_ITEMS = 12

        /** 没有图片头像时的圆底色，按位置轮换 */
        val PALETTE = intArrayOf(
            0xFFE74C3C.toInt(), 0xFF3498DB.toInt(), 0xFF1ABC9C.toInt(),
            0xFF9B59B6.toInt(), 0xFFF1C40F.toInt(), 0xFFE67E22.toInt(),
            0xFF16A085.toInt(), 0xFFD35400.toInt()
        )

        fun colorAt(index: Int): Int {
            val size = PALETTE.size
            return PALETTE[((index % size) + size) % size]
        }

        fun listToJson(list: List<QuickContact>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun jsonToList(json: String): List<QuickContact> {
            val out = ArrayList<QuickContact>()
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    out.add(
                        QuickContact(
                            name = o.optString("name"),
                            phone = o.optString("phone"),
                            photoUri = o.optString("photo").ifEmpty { null },
                            iconPath = o.optString("icon").ifEmpty { null },
                            color = o.optInt("color", 0)
                        )
                    )
                }
            } catch (_: Exception) {
                // 历史数据损坏时当作空列表，不要让主页崩掉
            }
            return out
        }
    }
}
