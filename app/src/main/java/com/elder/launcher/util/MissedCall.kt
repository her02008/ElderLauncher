package com.elder.launcher.util

import org.json.JSONArray
import org.json.JSONObject

/**
 * 一条未接来电。
 *
 * [number] 是身份（同一个号码只保留一条，重复来电累加 [count]），
 * [notified] 表示「已经弹过窗提醒了」——离开桌面再回来时不会重复弹，只有新来电才会重新变成 false。
 */
data class MissedCall(
    val number: String,
    val name: String = "",
    val time: Long = 0L,
    val count: Int = 1,
    val notified: Boolean = false
) {

    /** 有名字念名字，没名字念号码；都没有就是陌生号码 */
    fun displayName(fallback: String): String =
        name.ifEmpty { number.ifEmpty { fallback } }

    fun toJson(): JSONObject = JSONObject()
        .put("number", number)
        .put("name", name)
        .put("time", time)
        .put("count", count)
        .put("notified", notified)

    companion object {

        fun fromJson(obj: JSONObject): MissedCall = MissedCall(
            number = obj.optString("number"),
            name = obj.optString("name"),
            time = obj.optLong("time"),
            count = obj.optInt("count", 1),
            notified = obj.optBoolean("notified", false)
        )

        fun listToJson(list: List<MissedCall>): String {
            val arr = JSONArray()
            list.forEach { arr.put(it.toJson()) }
            return arr.toString()
        }

        fun jsonToList(raw: String): List<MissedCall> = try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
