package com.elder.launcher.util

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 远程配置的 HTTP 客户端。
 *
 * 协议见 `docs/remote-config-protocol.md`。这里只做「发请求 + 验签名」，
 * 不关心对面是 Cloudflare Worker 还是家里 NAS 上的 Node——**协议先行，服务可换**。
 *
 * 没有引入 OkHttp/Retrofit：只有三个接口，`HttpURLConnection` 够用，
 * 少一个依赖就少一份体积和一次升级风险。
 */
object RemoteClient {

    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 12000

    /** 请求结果。`code` 是 HTTP 状态码，-1 表示连都没连上 */
    class Response(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
    }

    /**
     * 规范化用户输入的服务地址。
     *
     * **只对私网放行明文 http**：家里 NAS 用局域网 IP 调试时能省掉证书，
     * 但公网地址必须是 https——不然家庭码和 token 会在路上明文跑。
     *
     * @return 规范化后的地址（去掉结尾斜杠），不合法返回 null
     */
    fun normalizeEndpoint(raw: String): String? {
        val text = raw.trim().trimEnd('/')
        if (text.isEmpty()) return null
        val withScheme = if (text.startsWith("http://") || text.startsWith("https://")) {
            text
        } else {
            "https://$text"
        }
        val url = try {
            URL(withScheme)
        } catch (_: Exception) {
            return null
        }
        if (url.host.isNullOrEmpty()) return null
        if (url.protocol == "http" && !isPrivateHost(url.host)) return null
        return withScheme
    }

    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host == "127.0.0.1" || host == "[::1]") return true
        if (host.endsWith(".local")) return true
        val parts = host.split(".")
        if (parts.size != 4) return false
        val a = parts[0].toIntOrNull() ?: return false
        val b = parts[1].toIntOrNull() ?: return false
        return when (a) {
            10 -> true
            192 -> b == 168
            172 -> b in 16..31
            else -> false
        }
    }

    /**
     * 绑定。返回 token（失败时 token 为空，error 有原因）。
     *
     * 每次绑定服务端都会换一把新钥匙，所以「换手机」「怀疑泄露」都是重新绑一次。
     */
    fun bind(
        endpoint: String,
        code: String,
        deviceId: String,
        name: String
    ): Response {
        val body = org.json.JSONObject().apply {
            put("deviceId", deviceId)
            put("name", name)
            put("model", android.os.Build.MODEL.orEmpty())
            put("version", com.elder.launcher.BuildConfig.VERSION_NAME)
        }.toString()
        return request(endpoint, "/b/$code/bind", "POST", body, token = null, deviceId = null)
    }

    /** 拉取 rev 之后的指令 */
    fun fetchConfig(
        endpoint: String,
        code: String,
        token: String,
        deviceId: String,
        rev: Int
    ): Response = request(endpoint, "/b/$code/cfg?rev=$rev", "GET", "", token, deviceId)

    /** 上报执行结果 + 状态快照 */
    fun ack(
        endpoint: String,
        code: String,
        token: String,
        deviceId: String,
        body: String
    ): Response = request(endpoint, "/b/$code/ack", "POST", body, token, deviceId)

    /**
     * 统一发请求 + 签名。
     *
     * 签名串 = `METHOD \n PATH \n TS \n BODY`，与两套服务端严格一致，
     * 改这里必须同步改 `worker.js` 和 `server.js` 的 `canonical`。
     */
    private fun request(
        endpoint: String,
        path: String,
        method: String,
        body: String,
        token: String?,
        deviceId: String?
    ): Response {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(endpoint + path)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                setRequestProperty("Accept", "application/json")
            }
            if (token != null && deviceId != null) {
                val ts = System.currentTimeMillis() / 1000
                val canonical = listOf(method, path, ts.toString(), body).joinToString("\n")
                conn.setRequestProperty("X-Device", deviceId)
                conn.setRequestProperty("X-Ts", ts.toString())
                conn.setRequestProperty("X-Sig", hmacHex(token, canonical))
            }
            if (method == "POST") {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            }.orEmpty()
            Response(code, text)
        } catch (e: Exception) {
            // 断网、DNS 失败、超时都会走到这里。上层静默处理，不打扰老人
            Response(-1, e.javaClass.simpleName + ": " + (e.message ?: ""))
        } finally {
            conn?.disconnect()
        }
    }

    private fun hmacHex(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val bytes = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
