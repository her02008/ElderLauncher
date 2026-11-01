package com.elder.launcher.util

import android.content.Context
import com.elder.launcher.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * 远程指令的**白名单**与落地执行。
 *
 * 两条铁律：
 * 1. **只认表里的 op**。未知指令一律拒绝，不猜测、不做兼容。永远不下发可执行代码或任意 Intent。
 * 2. **危险指令不在这里直接执行**——[execute] 只碰安全项，
 *    改紧急联系人 / 换色块 / 换快捷拨号必须走 [applyDanger]，由老人本机点过「同意」之后才调。
 *
 * 服务端也有一份同样的白名单（`worker.js` / `server.js` 里的 `SAFE_OPS`/`DANGER_OPS`），
 * 改这里必须同步改那边。
 */
object RemoteCommand {

    const val OP_FONT = "set_font"
    const val OP_VOICE = "set_voice"
    const val OP_ALIVE = "set_alive"
    const val OP_HOME_ORDER = "set_home_order"
    const val OP_PUSH_MSG = "push_msg"
    const val OP_SOS = "set_sos"
    const val OP_BLOCKS = "set_blocks"
    const val OP_QUICK = "set_quick"

    val SAFE_OPS = setOf(OP_FONT, OP_VOICE, OP_ALIVE, OP_HOME_ORDER, OP_PUSH_MSG)
    val DANGER_OPS = setOf(OP_SOS, OP_BLOCKS, OP_QUICK)
    val ALL_OPS = SAFE_OPS + DANGER_OPS

    /** 危险指令最多挂这么久，超时自动作废（老人可能一直没回桌面） */
    private const val DANGER_TTL_MS = 24 * 3600_000L

    private const val MAX_MSG_LEN = 200

    data class Incoming(
        val id: String,
        val op: String,
        val args: JSONObject,
        val danger: Boolean,
        val at: Long
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("op", op)
            put("args", args)
            put("danger", danger)
            put("at", at)
        }

        fun expired(now: Long = System.currentTimeMillis()): Boolean =
            danger && at > 0 && now - at > DANGER_TTL_MS
    }

    class Outcome(val ok: Boolean, val error: String = "")

    // ---------------- 解析 ----------------

    fun parseArray(text: String): List<Incoming> {
        val out = mutableListOf<Incoming>()
        val arr = try {
            JSONArray(text)
        } catch (_: Exception) {
            return out
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val op = o.optString("op")
            if (id.isEmpty() || op !in ALL_OPS) continue
            out.add(
                Incoming(
                    id = id,
                    op = op,
                    args = o.optJSONObject("args") ?: JSONObject(),
                    danger = op in DANGER_OPS,
                    at = o.optLong("at", 0L)
                )
            )
        }
        return out
    }

    fun listToJson(list: List<Incoming>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        return arr.toString()
    }

    // ---------------- 执行（安全项） ----------------

    /**
     * 执行一条安全指令。危险指令到这里会被**原样拒绝**，
     * 调用方应先看 `cmd.danger` 决定走确认流程。
     */
    fun execute(ctx: Context, cmd: Incoming): Outcome {
        if (cmd.op in DANGER_OPS) return Outcome(false, "danger requires confirm")
        return when (cmd.op) {
            OP_FONT -> setFont(ctx, cmd.args)
            OP_VOICE -> {
                PrefsManager.setVoiceEnabled(ctx, cmd.args.optBoolean("on", true))
                Outcome(true)
            }

            OP_ALIVE -> setAlive(ctx, cmd.args)
            OP_HOME_ORDER -> setHomeOrder(ctx, cmd.args)
            OP_PUSH_MSG -> {
                val text = cmd.args.optString("text").trim()
                if (text.isEmpty()) return Outcome(false, "empty text")
                PrefsManager.setRemoteMessage(ctx, text.take(MAX_MSG_LEN))
                Outcome(true)
            }

            else -> Outcome(false, "unknown op")
        }
    }

    private fun setFont(ctx: Context, args: JSONObject): Outcome {
        val level = args.optInt("level", -1)
        if (level !in 0..2) return Outcome(false, "bad level")
        PrefsManager.setFontLevel(ctx, level)
        // 主页 onResume 会比对 appliedFontLevel 自己 recreate，这里不用管界面
        return Outcome(true)
    }

    private fun setAlive(ctx: Context, args: JSONObject): Outcome {
        val on = args.optBoolean("on", false)
        if (!on) {
            PrefsManager.setAliveEnabled(ctx, false)
            // schedule 而不是 cancel：闹钟还兼着远程同步，直接 cancel 会连同步一起停掉
            AliveCenter.schedule(ctx)
            return Outcome(true)
        }
        val hours = args.optInt("hours", 24)
        if (hours !in listOf(12, 24, 48)) return Outcome(false, "bad hours")
        // 没有接收号码就等于白开，明确报错回去，别让子女以为已经生效了
        if (AliveCenter.notifyPhone(ctx) == null) return Outcome(false, "no notify phone")
        PrefsManager.setAliveThresholdHours(ctx, hours)
        PrefsManager.setAliveEnabled(ctx, true)
        AliveCenter.schedule(ctx)
        return Outcome(true)
    }

    private fun setHomeOrder(ctx: Context, args: JSONObject): Outcome {
        val arr = args.optJSONArray("order") ?: return Outcome(false, "bad order")
        val wanted = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val key = arr.optString(i)
            if (key in PrefsManager.SECTION_KEYS && key !in wanted) wanted.add(key)
        }
        if (wanted.isEmpty()) return Outcome(false, "bad order")
        // 缺的补到末尾，保证三项齐全（读取时也会补，这里显式对齐更直观）
        PrefsManager.SECTION_KEYS.forEach { if (it !in wanted) wanted.add(it) }
        PrefsManager.setHomeOrder(ctx, wanted)
        return Outcome(true)
    }

    // ---------------- 危险项：只在老人点过「同意」之后调 ----------------

    fun applyDanger(ctx: Context, cmd: Incoming): Outcome {
        return when (cmd.op) {
            OP_SOS -> {
            val name = cmd.args.optString("name").trim()
            val phone = cmd.args.optString("phone").trim()
            if (name.isEmpty() || !looksLikePhone(phone)) {
                Outcome(false, "bad sos")
            } else {
                PrefsManager.setSosContact(ctx, name, phone)
                Outcome(true)
            }
        }

        OP_BLOCKS -> {
            val arr = cmd.args.optJSONArray("blocks") ?: return Outcome(false, "bad blocks")
            val parsed = HomeBlock.jsonToList(arr.toString())
            if (parsed.isEmpty() || parsed.size > 30) return Outcome(false, "bad blocks")
            PrefsManager.setHomeBlocks(ctx, parsed)
            Outcome(true)
        }

        OP_QUICK -> {
            val arr = cmd.args.optJSONArray("contacts") ?: return Outcome(false, "bad contacts")
            val parsed = QuickContact.jsonToList(arr.toString())
            if (parsed.isEmpty() || parsed.size > 12) return Outcome(false, "bad contacts")
            PrefsManager.setQuickContacts(ctx, parsed)
            Outcome(true)
        }

        else -> Outcome(false, "not a danger op")
        }
    }

    private fun looksLikePhone(s: String): Boolean =
        s.length in 3..20 && s.any { it.isDigit() }

    // ---------------- 给老人看的描述 ----------------

    fun describe(ctx: Context, cmd: Incoming): String = when (cmd.op) {
        OP_SOS -> ctx.getString(
            R.string.remote_cmd_sos,
            cmd.args.optString("name"),
            cmd.args.optString("phone")
        )

        OP_BLOCKS -> ctx.getString(
            R.string.remote_cmd_blocks,
            cmd.args.optJSONArray("blocks")?.length() ?: 0
        )

        OP_QUICK -> ctx.getString(
            R.string.remote_cmd_quick,
            cmd.args.optJSONArray("contacts")?.length() ?: 0
        )

        else -> ctx.getString(R.string.remote_cmd_unknown, cmd.op)
    }
}
