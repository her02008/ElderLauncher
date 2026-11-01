package com.elder.launcher.util

import android.content.Context
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import com.elder.launcher.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * 远程设置的本地枢纽：绑定、同步、待确认队列、回执上报。
 *
 * ## 同步时机
 * 不新增任何保活机制，搭「恰了么？」那班车：
 * 1. `AliveAlarmReceiver` 每小时一次的闹钟里顺手调 [syncAsync]
 * 2. 绑定成功后立刻同步一次
 * 3. 用户在 [com.elder.launcher.RemoteActivity] 里手动点「立即同步」
 *
 * 多排一个 AlarmManager 就多一份电、多一份被 ROM 干掉的机会，「恰了么？」本来就要醒，搭车最省。
 *
 * ## 指令分两种
 * - 安全项：直接执行，结果进回执队列
 * - 危险项：进 `remote_pending`，等老人回桌面弹窗确认（见 [com.elder.launcher.ui.RemotePrompt]）
 */
object RemoteCenter {

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "elder-remote").apply { isDaemon = true }
    }

    @Volatile
    private var running = false

    class SyncResult(
        val ok: Boolean,
        val applied: Int = 0,
        val pending: Int = 0,
        val error: String = "",
        val skipped: Boolean = false
    )

    // ---------------- 绑定 ----------------

    /**
     * 绑定到服务端。
     *
     * 服务端每次绑定都会换一把新 token，所以「换手机」「怀疑家庭码泄露」重新绑一次就行。
     */
    fun bind(ctx: Context, rawEndpoint: String, rawCode: String, name: String): SyncResult {
        val app = ctx.applicationContext
        val endpoint = RemoteClient.normalizeEndpoint(rawEndpoint)
            ?: return SyncResult(false, error = "endpoint")

        // 家庭码只允许大写字母数字，避免全角/空格导致的「明明填对了却 404」
        val code = rawCode.trim().uppercase().filter { it.isLetterOrDigit() }
        if (code.length !in 4..16) return SyncResult(false, error = "code")

        val deviceId = PrefsManager.getDeviceId(app)
        val resp = RemoteClient.bind(endpoint, code, deviceId, name.ifBlank { "长辈手机" })
        if (!resp.ok) {
            val err = if (resp.code == -1) resp.body else "http ${resp.code}"
            PrefsManager.setRemoteError(app, err)
            return SyncResult(false, error = err)
        }

        val token = try {
            JSONObject(resp.body).optString("token")
        } catch (_: Exception) {
            ""
        }
        if (token.isEmpty()) {
            PrefsManager.setRemoteError(app, "no token")
            return SyncResult(false, error = "no token")
        }

        PrefsManager.setRemoteBinding(app, endpoint, code, token)
        PrefsManager.setRemoteRev(app, -1)
        PrefsManager.setRemotePending(app, "[]")
        PrefsManager.setRemoteError(app, "")
        // 把每小时那个闹钟排上。它同时服务「恰了么？」和远程同步，
        // 不在这里排的话，没开「恰了么？」的用户绑完就再也不会有第二次同步
        AliveCenter.schedule(app)
        return sync(app)
    }

    /** 绑定也要发网络请求，同样得离开主线程 */
    fun bindAsync(
        ctx: Context,
        endpoint: String,
        code: String,
        name: String,
        onDone: (SyncResult) -> Unit
    ) {
        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        io.execute {
            val result = try {
                bind(app, endpoint, code, name)
            } catch (e: Exception) {
                SyncResult(false, error = e.javaClass.simpleName)
            }
            main.post { onDone(result) }
        }
    }

    // ---------------- 同步 ----------------

    fun syncAsync(ctx: Context, onDone: ((SyncResult) -> Unit)? = null) {        val app = ctx.applicationContext
        val main = Handler(Looper.getMainLooper())
        io.execute {
            val result = try {
                sync(app)
            } catch (e: Exception) {
                SyncResult(false, error = e.javaClass.simpleName)
            }
            if (onDone != null) main.post { onDone(result) }
        }
    }

    /**
     * 同步一次。**会阻塞**，只能在后台线程调。
     *
     * 一条指令失败不影响其它指令——回执是数组，就是为了不搞「一错全废」。
     */
    @Synchronized
    fun sync(ctx: Context): SyncResult {
        val app = ctx.applicationContext
        if (!PrefsManager.isRemoteBound(app)) return SyncResult(false, error = "unbound")

        // 前一次还没跑完就不叠加，闹钟和手动点击撞在一起时省一次请求
        if (running) return SyncResult(false, error = "busy", skipped = true)
        running = true
        try {
            val endpoint = PrefsManager.getRemoteEndpoint(app)
            val code = PrefsManager.getRemoteCode(app)
            val token = PrefsManager.getRemoteToken(app)
            val deviceId = PrefsManager.getDeviceId(app)

            var applied = 0
            val resp = RemoteClient.fetchConfig(endpoint, code, token, deviceId, PrefsManager.getRemoteRev(app))

            when {
                resp.code == 401 -> {
                    // 绑定失效（服务端换了 token / 家庭码被别的手机顶掉）。不自动重绑，
                    // 让家属自己发现并处理，否则两台手机互相顶来顶去
                    PrefsManager.setRemoteError(app, "auth")
                    return SyncResult(false, error = "auth")
                }

                !resp.ok -> {
                    val err = if (resp.code == -1) resp.body else "http ${resp.code}"
                    PrefsManager.setRemoteError(app, err)
                    return SyncResult(false, error = err)
                }
            }

            val root = try {
                JSONObject(resp.body)
            } catch (_: Exception) {
                PrefsManager.setRemoteError(app, "bad json")
                return SyncResult(false, error = "bad json")
            }

            val serverRev = root.optInt("rev", PrefsManager.getRemoteRev(app))
            val cmds = RemoteCommand.parseArray(root.optJSONArray("cmds")?.toString() ?: "[]")

            val now = System.currentTimeMillis()
            val pending = mutableListOf<RemoteCommand.Incoming>()
            // 先把本地挂太久的危险指令清掉。这一步**不能只依赖服务端重发**：
            // 客户端 rev 早就推进了，服务端不会再送同一条，不在这里清理就永远躺在待确认里。
            for (old in pendingList(app)) {
                if (old.expired(now)) {
                    queueResult(app, old.id, false, "expired")
                } else {
                    pending.add(old)
                }
            }

            // 已经处理过、只是回执还没送达的指令不要重跑。
            // 服务端在「修订号判断失误」或「多个子女同时下发」时可能重发同一条，
            // 回执队列里留着的 id 就是「已处理但服务端还不知道」的那批，用它兜底。
            val handled = ackIdSet(app)

            for (cmd in cmds) {
                if (cmd.id in handled) continue
                if (cmd.danger) {
                    if (cmd.expired(now)) {
                        // 老人一天都没回桌面，指令作废，回执告诉子女一声
                        queueResult(app, cmd.id, false, "expired")
                    } else if (pending.none { it.id == cmd.id }) {
                        pending.add(cmd)
                    }
                    continue
                }
                val outcome = RemoteCommand.execute(app, cmd)
                if (outcome.ok) applied++
                queueResult(app, cmd.id, outcome.ok, outcome.error)
            }

            PrefsManager.setRemotePending(app, RemoteCommand.listToJson(pending))
            PrefsManager.setRemoteRev(app, serverRev)
            PrefsManager.setRemoteLastSync(app, System.currentTimeMillis())

            flushAck(app, endpoint, code, token, deviceId, serverRev)
            PrefsManager.setRemoteError(app, "")
            return SyncResult(true, applied, pending.size)
        } finally {
            running = false
        }
    }

    /**
     * 上报回执。失败不抛：队列留着，下次同步再发一遍。
     * 指令都是幂等的，重复上报不会造成二次执行。
     */
    private fun flushAck(
        ctx: Context,
        endpoint: String,
        code: String,
        token: String,
        deviceId: String,
        rev: Int
    ) {
        val queue = ackQueue(ctx)
        val body = JSONObject().apply {
            put("rev", rev)
            put("results", queue)
            put("state", stateJson(ctx))
        }.toString()

        if (RemoteClient.ack(endpoint, code, token, deviceId, body).ok) {
            PrefsManager.setRemoteAckQueue(ctx, "[]")
        } else {
            PrefsManager.setRemoteError(ctx, "ack failed")
        }
    }

    private fun queueResult(ctx: Context, id: String, ok: Boolean, error: String) {
        val queue = ackQueue(ctx)
        // JSONArray 不是 Iterable，只能自己按下标找
        for (i in 0 until queue.length()) {
            if (queue.optJSONObject(i)?.optString("id") == id) return
        }
        queue.put(JSONObject().apply {
            put("id", id)
            put("ok", ok)
            put("error", error)
        })
        // 队列别无限长：留最近 50 条，超出的丢弃（服务端也会按 id 摘指令）
        while (queue.length() > 50) {
            queue.remove(0)
        }
        PrefsManager.setRemoteAckQueue(ctx, queue.toString())
    }

    private fun ackQueue(ctx: Context): JSONArray = try {
        JSONArray(PrefsManager.getRemoteAckQueue(ctx))
    } catch (_: Exception) {
        JSONArray()
    }

    /** 回执队列里的 id 集合 = 已经执行过、但服务端还没收到确认的指令 */
    private fun ackIdSet(ctx: Context): Set<String> {
        val queue = ackQueue(ctx)
        val out = HashSet<String>(queue.length())
        for (i in 0 until queue.length()) {
            val id = queue.optJSONObject(i)?.optString("id").orEmpty()
            if (id.isNotEmpty()) out.add(id)
        }
        return out
    }

    // ---------------- 危险指令确认 ----------------

    fun pendingList(ctx: Context): List<RemoteCommand.Incoming> =
        RemoteCommand.parseArray(PrefsManager.getRemotePending(ctx))

    /**
     * 老人在弹窗上做了选择。
     *
     * 同意就执行，不同意也回一条 `declined` —— 子女那边必须知道结果，
     * 否则「点了没反应」和「被拒绝」在他眼里是一样的。
     */
    fun resolvePending(ctx: Context, ids: Set<String>, approve: Boolean): Int {
        val app = ctx.applicationContext
        val all = pendingList(app)
        if (all.isEmpty()) return 0

        var done = 0
        val keep = mutableListOf<RemoteCommand.Incoming>()
        for (cmd in all) {
            if (cmd.id !in ids) {
                keep.add(cmd)
                continue
            }
            if (approve) {
                val outcome = RemoteCommand.applyDanger(app, cmd)
                queueResult(app, cmd.id, outcome.ok, outcome.error)
                if (outcome.ok) done++
            } else {
                queueResult(app, cmd.id, false, "declined")
            }
        }
        PrefsManager.setRemotePending(app, RemoteCommand.listToJson(keep))
        return done
    }

    // ---------------- 遗留留言 ----------------

    /** 取走子女留言（取完即清，保证只弹一次） */
    fun takeMessage(ctx: Context): String? {
        val text = PrefsManager.getRemoteMessage(ctx)
        if (text.isBlank()) return null
        PrefsManager.setRemoteMessage(ctx, "")
        return text
    }

    fun hasMessage(ctx: Context): Boolean = PrefsManager.getRemoteMessage(ctx).isNotBlank()

    // ---------------- 状态快照 ----------------

    /** 上报给子女端看的字段。别往里塞通讯录、位置这类东西。 */
    fun stateJson(ctx: Context): JSONObject = JSONObject().apply {
        put("lastActive", PrefsManager.getAliveLastActive(ctx))
        put("alive", if (PrefsManager.isAliveEnabled(ctx)) "on" else "off")
        put("fontLevel", PrefsManager.getFontLevel(ctx))
        put("voice", PrefsManager.isVoiceEnabled(ctx))
        put("version", BuildConfig.VERSION_NAME)
        put("battery", batteryPercent(ctx))
        put("pending", pendingList(ctx).size)
    }

    private fun batteryPercent(ctx: Context): Int {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            ?: return -1
        return bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    fun lastSyncText(ctx: Context): String =
        AliveCenter.formatTime(PrefsManager.getRemoteLastSync(ctx))
}
