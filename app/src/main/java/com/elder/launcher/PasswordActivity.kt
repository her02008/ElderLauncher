package com.elder.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.elder.launcher.databinding.ActivityPasswordBinding
import com.elder.launcher.util.ActionExecutor
import com.elder.launcher.util.AdminGate

/**
 * 管理密码页：进入高级功能前的一道门。
 *
 * 三种模式（同一套大键盘，靠标题/提示区分）：
 * - [Mode.SETUP]   还没设过密码 → 设置（输两遍确认）
 * - [Mode.CONFIRM] 设置后第二遍，比对一致才落盘
 * - [Mode.VERIFY]  已设过密码 → 校验；连错 [AdminGate.FAILS_BEFORE_RESET] 次后亮出「忘记密码？」
 *                  （忘记密码时也可在老人拨号盘输入 [AdminGate.RESET_CODE] 重置）
 *
 * 校验通过后就地跳 [target]（高级设置页 / 功能块管理 / 系统桌面设置），调用方无需再动作。
 */
class PasswordActivity : BaseActivity() {

    private enum class Mode { SETUP, CONFIRM, VERIFY }

    private lateinit var binding: ActivityPasswordBinding
    private val input = StringBuilder()
    private var mode = Mode.VERIFY
    private var firstEntry = ""
    private var fails = 0
    private var target = TARGET_ADMIN

    /** 已经走到跳转就不再受理按键，避免连点重复发起 */
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        target = intent.getStringExtra(EXTRA_TARGET) ?: TARGET_ADMIN
        val forceSetup = intent.getBooleanExtra(EXTRA_SETUP, false)

        binding.btnBack.setOnClickListener { finish() }
        bindKeys()
        binding.btnDelete.setOnClickListener { deleteLast() }
        binding.btnDelete.setOnLongClickListener { clearInput(); true }
        binding.btnOk.setOnClickListener { submit() }
        binding.btnForgot.setOnClickListener { confirmReset() }

        mode = if (forceSetup || !AdminGate.hasPassword(this)) Mode.SETUP else Mode.VERIFY
        render()
    }

    private fun bindKeys() {
        val keys = listOf(
            binding.key1 to "1", binding.key2 to "2", binding.key3 to "3",
            binding.key4 to "4", binding.key5 to "5", binding.key6 to "6",
            binding.key7 to "7", binding.key8 to "8", binding.key9 to "9",
            binding.keyStar to "*", binding.key0 to "0", binding.keyHash to "#"
        )
        keys.forEach { (key, value) -> key.setOnClickListener { append(value) } }
    }

    private fun append(value: String) {
        if (done || input.length >= AdminGate.PWD_LENGTH) return
        input.append(value)
        render()
        // 输满 6 位就自动提交，少按一次「确定」
        if (input.length == AdminGate.PWD_LENGTH) submit()
    }

    private fun deleteLast() {
        if (done || input.isEmpty()) return
        input.deleteCharAt(input.length - 1)
        render()
    }

    private fun clearInput() {
        input.setLength(0)
        render()
    }

    /** 按模式刷新标题、提示和输入区（校验时用圆点遮挡，设置时显示数字方便核对） */
    private fun render() {
        when (mode) {
            Mode.SETUP -> {
                binding.tvTitle.setText(R.string.password_set_title)
                binding.tvHint.setText(R.string.password_set_hint)
            }
            Mode.CONFIRM -> {
                binding.tvTitle.setText(R.string.password_confirm_title)
                binding.tvHint.setText(R.string.password_confirm_hint)
            }
            Mode.VERIFY -> {
                binding.tvTitle.setText(R.string.password_verify_title)
                binding.tvHint.setText(R.string.password_verify_hint)
            }
        }

        binding.tvInput.text = if (mode == Mode.VERIFY) {
            "●".repeat(input.length)
        } else {
            val remain = (AdminGate.PWD_LENGTH - input.length).coerceAtLeast(0)
            input.toString() + "○".repeat(remain)
        }

        binding.btnForgot.visibility =
            if (mode == Mode.VERIFY && fails >= AdminGate.FAILS_BEFORE_RESET) View.VISIBLE
            else View.GONE
        binding.btnOk.alpha = if (input.isEmpty()) 0.5f else 1f
    }

    /**
     * 「忘记密码？」：确认后清空密码并回到设置流程。
     * 与拨号盘 [AdminGate.RESET_CODE] 是同一条重置路径，不需要再输别的暗号。
     * 这里只重置密码（人正等着进门），「连所有设置一起恢复」只放在拨号盘那条入口里。
     */
    private fun confirmReset() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dial_reset_title)
            .setMessage(R.string.password_forgot_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                AdminGate.clearPassword(this)
                toast(getString(R.string.password_cleared))
                fails = 0
                firstEntry = ""
                mode = Mode.SETUP
                clearInput()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun submit() {
        if (done) return
        val text = input.toString()
        if (text.isEmpty()) {
            toast(getString(R.string.password_too_short))
            return
        }
        when (mode) {
            Mode.SETUP -> {
                firstEntry = text
                mode = Mode.CONFIRM
                clearInput()
            }
            Mode.CONFIRM -> {
                if (text == firstEntry) {
                    AdminGate.setPassword(this, text)
                    AdminGate.unlock()
                    toast(getString(R.string.password_saved))
                    goTarget()
                } else {
                    toast(getString(R.string.password_mismatch))
                    firstEntry = ""
                    mode = Mode.SETUP
                    clearInput()
                }
            }
            Mode.VERIFY -> {
                if (AdminGate.verify(this, text)) {
                    AdminGate.unlock()
                    goTarget()
                } else {
                    fails++
                    toast(getString(R.string.password_wrong))
                    clearInput()
                }
            }
        }
    }

    /** 校验通过后就地跳到目标页面，然后关掉自己 */
    private fun goTarget() {
        done = true
        when (target) {
            TARGET_HOME_BLOCKS -> startActivity(Intent(this, HomeBlocksActivity::class.java))
            TARGET_HOME_SETTINGS -> ActionExecutor.openHomeSettings(this)
            else -> startActivity(Intent(this, AdminActivity::class.java))
        }
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_TARGET = "target"
        const val EXTRA_SETUP = "setup"

        /** 高级设置聚合页 */
        const val TARGET_ADMIN = "admin"

        /** 主页功能块管理 */
        const val TARGET_HOME_BLOCKS = "home_blocks"

        /** 系统的「默认应用 → 桌面」设置页 */
        const val TARGET_HOME_SETTINGS = "home_settings"

        /**
         * 高级入口的统一守门人。
         *
         * @return true = 已经弹了密码页，调用方不要再自己动作；false = 已解锁，照常继续
         */
        fun require(activity: Activity, target: String): Boolean {
            if (AdminGate.isUnlocked()) return false
            activity.startActivity(
                Intent(activity, PasswordActivity::class.java).putExtra(EXTRA_TARGET, target)
            )
            return true
        }

        /** 强制走「设置新密码」流程（高级设置页里改密码用） */
        fun openForSetup(activity: Activity) {
            activity.startActivity(
                Intent(activity, PasswordActivity::class.java)
                    .putExtra(EXTRA_TARGET, TARGET_ADMIN)
                    .putExtra(EXTRA_SETUP, true)
            )
        }
    }
}
