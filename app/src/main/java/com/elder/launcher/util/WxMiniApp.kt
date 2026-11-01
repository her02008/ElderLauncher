package com.elder.launcher.util

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 一个微信小程序快捷项（设置 → 微信小程序）。
 *
 * [name] 是自定义名称，会显示在主页色块和选择列表里；
 * [target] 是小程序的「身份」，支持多种写法，交给 [ActionExecutor.openMiniProgram] 决定怎么打开：
 * - 小程序后台生成的明文 / 加密 Scheme：`weixin://dl/business/?...` → 原样打开，最可靠
 * - 小程序 URL Link 或分享链接：`https://...` → 交给系统打开，由浏览器再拉起微信
 * - 原始 ID / AppID / `xxx@app`：包装成 `weixin://dl/business/?username=...`
 */
data class WxMiniApp(
    val id: String = UUID.randomUUID().toString(),
    /** 自定义名称 */
    var name: String = "",
    /** 小程序标识或链接 */
    var target: String = ""
) {
    /** 没有名称时退回显示标识 */
    fun displayName(): String = name.ifEmpty { target }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("target", target)
    }

    companion object {
        /** 小程序数量上限（防呆） */
        const val MAX_ITEMS = 20

        fun listToJson(list: List<WxMiniApp>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun jsonToList(json: String): List<WxMiniApp> {
            val out = ArrayList<WxMiniApp>()
            try {
                val arr = JSONArray(json)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val target = o.optString("target").trim()
                    // 没有标识的项打开不了，直接丢掉
                    if (target.isEmpty()) continue
                    out.add(
                        WxMiniApp(
                            id = o.optString("id").trim().ifEmpty { UUID.randomUUID().toString() },
                            name = o.optString("name").trim(),
                            target = target
                        )
                    )
                }
            } catch (_: Exception) {
                // 历史数据损坏时当作空列表，不要让设置页崩掉
            }
            return out
        }
    }
}
