package com.elder.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.widget.CheckBox
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.elder.launcher.databinding.ActivityDialBinding
import com.elder.launcher.util.ActionExecutor
import com.elder.launcher.util.AdminGate
import com.elder.launcher.util.PrefsManager
import com.elder.launcher.util.QuickIconStore
import com.elder.launcher.util.TtsManager

/**
 * 老人专用拨号盘：超大按键，自己按号码，按「拨打电话」直接拨出，不跳系统拨号界面。
 *
 * - 每按一个数字都会语音播报，按下去了让人听得见
 * - 号码放得下就居中静止，放不下就左对齐跑马灯滚动
 * - 左下角的通讯录按钮打开通讯录，选好联系人后把号码填进号码框，由用户再按「拨打电话」
 * - 有 CALL_PHONE 权限就直接拨号（ACTION_CALL），没权限才退回系统拨号界面并带上号码
 * - 管理密码重置暗号是 [AdminGate.RESET_CODE]（`*#0454#*`）；`*`/`#` 开头的一律不交给系统拨号，
 *   免得被当成 MMI 服务码弹英文报错
 */
class DialActivity : BaseActivity() {

    private lateinit var binding: ActivityDialBinding
    private val number = StringBuilder()

    private val callPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) placeCall() else fallbackToSystemDialer()
    }

    private val contactPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openContactPicker() else toast(getString(R.string.perm_need))
    }

    private val contactPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val picked = result.data?.getStringExtra(ContactsPickerActivity.EXTRA_PHONE).orEmpty()
        val cleaned = picked.replace(" ", "").replace("-", "")
        if (cleaned.isEmpty()) return@registerForActivityResult
        number.setLength(0)
        number.append(cleaned.take(MAX_LENGTH))
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDialBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        bindKeys()
        binding.btnDelete.setOnClickListener { deleteLast() }
        binding.btnDelete.setOnLongClickListener { clearAll(); true }
        binding.btnClear.setOnClickListener { clearAll() }
        binding.btnContacts.setOnClickListener { onContacts() }
        binding.btnCall.setOnClickListener { onCall() }
        refresh()
    }

    private fun bindKeys() {
        val keys = listOf(
            binding.key1 to "1", binding.key2 to "2", binding.key3 to "3",
            binding.key4 to "4", binding.key5 to "5", binding.key6 to "6",
            binding.key7 to "7", binding.key8 to "8", binding.key9 to "9",
            binding.keyStar to "*", binding.key0 to "0", binding.keyHash to "#"
        )
        keys.forEach { (key, digit) ->
            key.setOnClickListener { append(digit) }
        }
    }

    private fun append(digit: String) {
        if (number.length >= MAX_LENGTH) return
        number.append(digit)
        TtsManager.speak(this, digit)
        refresh()
    }

    private fun deleteLast() {
        if (number.isEmpty()) return
        number.deleteCharAt(number.length - 1)
        refresh()
    }

    private fun clearAll() {
        if (number.isEmpty()) return
        number.setLength(0)
        refresh()
    }

    private fun refresh() {
        binding.tvNumber.text = number.toString()
        // 空号时把拨号键变淡一点，提醒还没输入
        binding.btnCall.alpha = if (number.isEmpty()) 0.5f else 1f
        applyNumberMarquee()
    }

    /**
     * 号码能完整显示就居中不动（不跑动画），放不下才切左对齐并打开跑马灯。
     *
     * 注意：跑马灯是从布局左边缘开始平移的，`gravity=center` 时文字本来就从负数位置画起，
     * 滚起来首尾永远看不全，所以溢出时必须先切成左对齐。
     */
    private fun applyNumberMarquee() {
        val tv = binding.tvNumber
        val text = number.toString()
        tv.gravity = Gravity.CENTER
        tv.isSelected = false
        if (text.isEmpty()) return
        tv.post {
            // 期间可能已经改过号码了，这时不要再动
            if (binding.tvNumber.text.toString() != text) return@post
            val available = tv.width - tv.paddingStart - tv.paddingEnd
            if (available <= 0) return@post
            if (tv.paint.measureText(text) > available) {
                tv.gravity = Gravity.START or Gravity.CENTER_VERTICAL
                tv.isSelected = true
            }
        }
    }

    /** 通讯录：有权限直接打开，没权限先申请 */
    private fun onContacts() {
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) openContactPicker() else contactPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
    }

    /** 打开通讯录选联系人，选完把号码填进号码框（不直接拨出，让用户自己按拨打键确认） */
    private fun openContactPicker() {
        contactPickerLauncher.launch(Intent(this, ContactsPickerActivity::class.java))
    }

    private fun onCall() {
        if (number.isEmpty()) {
            toast(getString(R.string.dial_empty))
            return
        }
        val typed = number.toString()
        // 重置暗号：不拨号，改成「重置管理密码」
        // （拨号直接进后台的入口已取消，高级设置的入口在「设置 → 高级设置」）
        if (AdminGate.isResetCode(typed)) {
            clearAll()
            confirmResetPassword()
            return
        }
        // 「*」「#」开头的一律不进系统拨号：这些是运营商服务码 / 工程码，系统会按 MMI 指令处理，
        // 弹「connection problem or invalid MMI code」这类英文报错，长辈看不懂也不知道自己按错了什么。
        // 老人拨号盘本来就只拨普通号码，直接拦下来给一句中文说明。
        if (typed.startsWith("*") || typed.startsWith("#")) {
            toast(getString(R.string.dial_service_code))
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            placeCall()
        } else {
            callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
        }
    }

    /**
     * 拨号盘重置：一个勾选框 + 确认/取消。
     *
     * - 不勾 → 只清空管理密码（清空后等于没设过密码，下次进「设置 → 高级设置」会直接让设置新密码）；
     * - 勾上「同时将所有设置恢复出厂」→ 再清掉功能块、快捷拨号、小程序、视频号链接、紧急联系人、
     *   字号语音、首页板块顺序，并删掉导入的快捷拨号头像图片（只删私有目录副本，不动相册原图）。
     *
     * 恢复出厂不可撤销，所以勾上时再弹一层确认（把要清的东西逐条列出来）才真动手。
     */
    private fun confirmResetPassword() {
        val content = layoutInflater.inflate(R.layout.dialog_dial_reset, null)
        val cbResetAll = content.findViewById<CheckBox>(R.id.cbResetAll)
        AlertDialog.Builder(this)
            .setTitle(R.string.dial_reset_title)
            .setMessage(R.string.dial_reset_message)
            .setView(content)
            .setPositiveButton(R.string.confirm) { _, _ ->
                if (cbResetAll.isChecked) confirmResetEverything() else resetPasswordOnly()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun resetPasswordOnly() {
        AdminGate.clearPassword(this)
        toast(getString(R.string.dial_reset_done))
    }

    /** 恢复所有设置不可撤销，所以再确认一次 */
    private fun confirmResetEverything() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dial_reset_title)
            .setMessage(R.string.dial_reset_all_confirm)
            .setPositiveButton(R.string.dial_reset_all_confirm_yes) { _, _ ->
                AdminGate.clearPassword(this)
                PrefsManager.clearAll(this)
                // 快捷拨号里从相册导入的头像图片也存在私有目录，一并清掉
                QuickIconStore.clearAll(this)
                toast(getString(R.string.dial_reset_all_done))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun placeCall() {
        if (number.isEmpty()) return
        TtsManager.speak(this, getString(R.string.dial_calling))
        try {
            ActionExecutor.call(this, number.toString())
            finish()
        } catch (_: Exception) {
            fallbackToSystemDialer()
        }
    }

    private fun fallbackToSystemDialer() {
        if (number.isEmpty()) return
        try {
            ActionExecutor.dial(this, number.toString())
            finish()
        } catch (_: Exception) {
            toast(getString(R.string.perm_need))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** 号码最长长度，够长号用，也避免显示区被撑坏 */
        const val MAX_LENGTH = 20
    }
}
