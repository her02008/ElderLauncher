package com.elder.launcher.util

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 高级功能的「管理密码」门。
 *
 * 定位是**防误操作**，不是防黑客——挡住长辈随手改坏桌面配置（删功能块、换默认桌面、开无障碍）。
 * 所以：6 位纯数字、本地校验；但密码本身只落盘**随机盐 + SHA-256 摘要**，
 * 绝不存明文（`SharedPreferences` 里的东西 root 就能看到，存明文等于没设）。
 *
 * 解锁状态只保存在进程内存里（[unlockedAt]），所以杀掉进程 / 重启手机自动上锁，
 * 不会出现「解过一次就永久敞开」。
 *
 * 入口在「设置 → 高级设置」（以及功能块管理、设为默认桌面这些高级项本身）；
 * 忘了密码就在拨号盘输 [RESET_CODE] 重置。
 */
object AdminGate {

    /**
     * 重置暗号：在老人拨号盘里输入这一串再按「拨打电话」，确认后即可清空管理密码重新设置。
     *
     * **它只负责「重置」，不负责「进入」**：高级设置的入口在「设置 → 高级设置」，
     * 那种拨号直接进后台的暗号入口已经取消了（长辈误按的风险不值当，家属从设置里进更直白）。
     *
     * ⚠️ 结尾必须是 `#*`（即 `*#0454#*`）。`*#...*#` 那种「`*` 开头、`*#` 结尾」的形态就是
     * 运营商服务码（MMI/USSD）的格式，一旦漏判被交给系统拨号，会弹
     * 「connection problem or invalid MMI code」这种英文报错，长辈完全看不懂。
     * **只有这一套暗号**，不要再加第二套（对外只有一个说法，用户不用记两串）。
     */
    const val RESET_CODE = "*#0454#*"

    /**
     * 这一串是不是重置暗号。
     *
     * 顺手把空格、短横线去掉再比——从别处粘过来或者手抖带了符号也能认。
     */
    fun isResetCode(input: String): Boolean =
        input.replace(" ", "").replace("-", "") == RESET_CODE

    /** 密码位数（纯数字，老人/家属都好输） */
    const val PWD_LENGTH = 6

    /** 解锁后免再输密码的时长 */
    private const val UNLOCK_TTL_MS = 5 * 60 * 1000L

    /** 连错这么多次之后，密码页才亮出「忘记密码？」入口 */
    const val FAILS_BEFORE_RESET = 3

    /** 解锁时刻；0 = 当前处于锁定状态 */
    private var unlockedAt = 0L

    // ---------------- 密码存取 ----------------

    fun hasPassword(ctx: Context): Boolean = PrefsManager.getAdminHash(ctx).isNotEmpty()

    fun setPassword(ctx: Context, pwd: String) {
        val salt = randomSalt()
        PrefsManager.setAdminSalt(ctx, salt)
        PrefsManager.setAdminHash(ctx, hash(pwd, salt))
    }

    fun clearPassword(ctx: Context) {
        PrefsManager.setAdminSalt(ctx, "")
        PrefsManager.setAdminHash(ctx, "")
        lock()
    }

    fun verify(ctx: Context, pwd: String): Boolean {
        val salt = PrefsManager.getAdminSalt(ctx)
        val stored = PrefsManager.getAdminHash(ctx)
        if (salt.isEmpty() || stored.isEmpty()) return false
        return equalsConstantTime(stored, hash(pwd, salt))
    }

    // ---------------- 解锁会话 ----------------

    fun unlock() {
        unlockedAt = System.currentTimeMillis()
    }

    /** 已经在高级页里操作时调用，把免密时间往后顺延（避免正改着突然又要密码） */
    fun touch() {
        if (isUnlocked()) unlock()
    }

    fun lock() {
        unlockedAt = 0L
    }

    fun isUnlocked(): Boolean {
        if (unlockedAt == 0L) return false
        val elapsed = System.currentTimeMillis() - unlockedAt
        return elapsed in 0..UNLOCK_TTL_MS
    }

    // ---------------- 摘要 ----------------

    private fun randomSalt(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.toHex()
    }

    private fun hash(pwd: String, salt: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$salt|$pwd".toByteArray(Charsets.UTF_8))
            .toHex()

    private fun ByteArray.toHex(): String {
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /** 比较摘要时逐位走完，不用 `==` 提前短路（本地场景影响很小，但顺手做对） */
    private fun equalsConstantTime(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
