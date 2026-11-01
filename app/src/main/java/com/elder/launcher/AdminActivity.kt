package com.elder.launcher

import android.Manifest
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.elder.launcher.databinding.ActivityAdminBinding
import com.elder.launcher.receiver.ElderDeviceAdminReceiver
import com.elder.launcher.util.ActionExecutor
import com.elder.launcher.util.AdminGate
import com.elder.launcher.util.AliveCenter
import com.elder.launcher.util.FallDetector
import com.elder.launcher.util.MissedCallCenter
import com.elder.launcher.util.PermissionHelper
import com.elder.launcher.util.PrefsManager
import com.elder.launcher.util.RemoteCenter

/**
 * 高级设置页：把「会改变桌面形态 / 涉及系统权限」的操作集中起来。
 *
 * 入口是普通设置页里的「高级设置」卡片（也要先过管理密码）；
 * 页面自身也会校验解锁状态（[AdminGate.isUnlocked]），避免被别的路径绕进来。
 */
class AdminActivity : BaseActivity() {

    private lateinit var binding: ActivityAdminBinding

    /** 开启「恰了么？」要发短信，SEND_SMS 是运行时权限，得当场申请 */
    private val smsPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // 权限弹窗期间用户可能已经退出本页，此时再操作界面会崩
            if (isFinishing || isDestroyed) return@registerForActivityResult
            if (granted) {
                // 弹权限前记下来是哪个功能要的，回来才不会发错地方
                if (smsRequestForFall) enableFall() else enableAlive()
            } else {
                Toast.makeText(this, R.string.fall_sms_failed, Toast.LENGTH_LONG).show()
            }
        }

    /** 这次 SEND_SMS 申请是为跌倒检测发的（否则回调默认走「恰了么？」） */
    private var smsRequestForFall = false

    /**
     * 跌倒检测要两个运行时权限：SEND_SMS（通知家人）+ ANSWER_PHONE_CALLS
     * （家人回拨自动接听，26+ 才有）。任一被拒功能都会降级，所以一起申请。
     */
    private val fallPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
            // 权限弹窗期间用户可能已经退出本页，此时再操作界面会崩
            if (isFinishing || isDestroyed) return@registerForActivityResult
            enableFall()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 没解锁就别停留，直接去要密码
        if (!AdminGate.isUnlocked()) {
            PasswordActivity.require(this, PasswordActivity.TARGET_ADMIN)
            finish()
            return
        }

        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnLock.setOnClickListener { onLock() }

        binding.rowAdminBlocks.setOnClickListener {
            startActivity(Intent(this, HomeBlocksActivity::class.java))
        }
        binding.rowAdminHome.setOnClickListener {
            // 已经是默认桌面时也允许再进系统设置——不然用户没法从这里换回原来的桌面
            if (ActionExecutor.isDefaultHome(this)) {
                Toast.makeText(this, R.string.admin_home_switch_hint, Toast.LENGTH_LONG).show()
            }
            ActionExecutor.openHomeSettings(this)
        }
        binding.rowAdminAccessibility.setOnClickListener {
            ActionExecutor.openAccessibilitySettings(this)
        }
        binding.rowAdminDeviceAdmin.setOnClickListener { openDeviceAdmin() }
        binding.rowAdminMissed.setOnClickListener {
            if (MissedCallCenter.isEnabled(this)) {
                Toast.makeText(this, R.string.admin_missed_on_toast, Toast.LENGTH_SHORT).show()
            }
            ActionExecutor.openNotificationListenerSettings(this)
        }
        // 长按看抓到的通知原文：各家电话 App 的通知写法不一样，靠这个把匹配规则补准
        binding.rowAdminMissed.setOnLongClickListener {
            showMissedDebug()
            true
        }
        binding.rowAdminPassword.setOnClickListener { PasswordActivity.openForSetup(this) }
        binding.rowAdminResetBlocks.setOnClickListener { confirmResetBlocks() }
        binding.rowAdminRemote.setOnClickListener {
            startActivity(Intent(this, RemoteActivity::class.java))
        }

        binding.rowAdminAlive.setOnClickListener { showAliveMenu() }
        // 长按看「恰了么？」的内部状态：排查「为什么没提醒」时用
        binding.rowAdminAlive.setOnLongClickListener {
            showAliveDebug()
            true
        }
        binding.rowAdminFall.setOnClickListener { showFallMenu() }

        binding.tvAdminCodeTip.text =
            getString(R.string.admin_code_tip, AdminGate.RESET_CODE)
        refreshStates()
    }

    override fun onResume() {
        super.onResume()
        // 密码会话可能已经过期（比如去系统设置页待久了），过期就别继续留在高级页
        if (!AdminGate.isUnlocked()) {
            PasswordActivity.require(this, PasswordActivity.TARGET_ADMIN)
            finish()
            return
        }
        // 人还在高级页里操作 → 顺延免密时间，避免正改到一半又被拦
        AdminGate.touch()
        refreshStates()
    }

    private fun refreshStates() {
        val isHome = ActionExecutor.isDefaultHome(this)
        binding.tvAdminHomeState.text = getString(
            if (isHome) R.string.admin_home_is_default else R.string.admin_home_not_default
        )
        binding.tvAdminAccessibilityState.text = getString(
            if (PermissionHelper.isAccessibilityEnabled(this)) R.string.admin_accessibility_on
            else R.string.admin_accessibility_off
        )
        binding.tvAdminDeviceAdminState.text = getString(
            if (PermissionHelper.isDeviceAdminActive(this)) R.string.admin_dev_admin_on
            else R.string.admin_dev_admin_off
        )
        binding.tvAdminMissedState.text = getString(
            if (MissedCallCenter.isEnabled(this)) R.string.admin_missed_on
            else R.string.admin_missed_off
        )
        binding.tvAdminAliveState.text = aliveStateText()
        binding.tvAdminFallState.text = fallStateText()
        binding.tvAdminRemoteState.text = remoteStateText()
    }

    private fun fallStateText(): String = if (FallDetector.isEnabled(this)) {
        getString(R.string.fall_admin_on)
    } else {
        getString(R.string.fall_admin_off)
    }

    private fun showFallMenu() {
        val enabled = FallDetector.isEnabled(this)
        val items = arrayOf(
            getString(if (enabled) R.string.fall_menu_off else R.string.fall_menu_on)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.fall_title)
            .setItems(items) { _, _ ->
                if (enabled) {
                    disableFall()
                } else {
                    requestEnableFall()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestEnableFall() {
        val toRequest = mutableListOf(Manifest.permission.SEND_SMS)
        // 自动接听是 26+ 的权限；低版本上本来就没有这条路，别去申请
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            toRequest.add(Manifest.permission.ANSWER_PHONE_CALLS)
        }
        val missing = toRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            // 短信是摔落后通知家人的唯一通道，没权限开了也是半残
            smsRequestForFall = true
            fallPermissions.launch(missing.toTypedArray())
            return
        }
        enableFall()
    }

    private fun enableFall() {
        smsRequestForFall = false
        if (AliveCenter.notifyPhone(this) == null) {
            // 预警短信沿用「恰了么？」的号码，没有就先设
            Toast.makeText(this, R.string.fall_need_phone, Toast.LENGTH_LONG).show()
            editAlivePhone()
            return
        }
        FallDetector.setEnabled(this, true)
        Toast.makeText(this, R.string.fall_set_done, Toast.LENGTH_SHORT).show()
        refreshStates()
    }

    private fun disableFall() {
        FallDetector.setEnabled(this, false)
        refreshStates()
    }

    /** 远程设置一行：未绑定 / 已绑定 + 上次同步时间 / 已绑定但上次同步有错 */
    private fun remoteStateText(): String {
        if (!PrefsManager.isRemoteBound(this)) {
            return getString(R.string.remote_admin_state_unbound)
        }
        if (PrefsManager.getRemoteError(this).isNotEmpty()) {
            return getString(R.string.remote_admin_state_error)
        }
        return getString(R.string.remote_admin_state_bound, RemoteCenter.lastSyncText(this))
    }

    // ---------------- 恰了么？（超时预警） ----------------

    private fun aliveStateText(): String = when {
        !PrefsManager.isAliveEnabled(this) -> getString(R.string.alive_off)
        AliveCenter.isPaused(this) -> getString(
            R.string.alive_paused_until,
            AliveCenter.formatTime(PrefsManager.getAlivePauseUntil(this))
        )

        else -> getString(R.string.alive_on) + " · " +
                getString(R.string.alive_hours, PrefsManager.getAliveThresholdHours(this))
    }

    /** 点整行弹这个菜单：开/关、阈值、号码、暂停，都在这一层能改完 */
    private fun showAliveMenu() {
        val enabled = PrefsManager.isAliveEnabled(this)
        val threshold = PrefsManager.getAliveThresholdHours(this)
        val phone = AliveCenter.notifyPhone(this) ?: getString(R.string.alive_phone_empty)
        val pause = if (AliveCenter.isPaused(this)) {
            getString(R.string.alive_pause) + "（" +
                    AliveCenter.formatTime(PrefsManager.getAlivePauseUntil(this)) + "）"
        } else {
            getString(R.string.alive_pause)
        }

        val items = arrayOf(
            getString(if (enabled) R.string.alive_turn_off else R.string.alive_turn_on),
            getString(R.string.alive_threshold) + "：" + getString(R.string.alive_hours, threshold),
            getString(R.string.alive_phone) + "：" + phone,
            pause
        )

        AlertDialog.Builder(this)
            .setTitle(R.string.alive_dialog_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> if (enabled) disableAlive() else requestEnableAlive()
                    1 -> pickAliveThreshold()
                    2 -> editAlivePhone()
                    else -> pickAlivePause()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun requestEnableAlive() {
        smsRequestForFall = false
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            smsPermission.launch(Manifest.permission.SEND_SMS)
            return
        }
        enableAlive()
    }

    private fun enableAlive() {
        // 没有接收号码就等于白开：先逼着把号码设好
        if (AliveCenter.notifyPhone(this) == null) {
            Toast.makeText(this, R.string.alive_need_phone, Toast.LENGTH_LONG).show()
            editAlivePhone()
            return
        }
        PrefsManager.setAliveEnabled(this, true)
        AliveCenter.schedule(this)
        Toast.makeText(this, R.string.alive_set_done, Toast.LENGTH_SHORT).show()
        refreshStates()
    }

    private fun disableAlive() {
        PrefsManager.setAliveEnabled(this, false)
        // 用 schedule 而不是 cancel：闹钟现在也负责远程同步，
        // 直接 cancel 会把绑定了远程设置的手机一起弄哑
        AliveCenter.schedule(this)
        Toast.makeText(this, R.string.alive_off, Toast.LENGTH_SHORT).show()
        refreshStates()
    }

    private fun pickAliveThreshold() {
        val values = intArrayOf(12, 24, 48)
        val labels = values.map { getString(R.string.alive_hours, it) }.toTypedArray()
        val current = values.indexOf(PrefsManager.getAliveThresholdHours(this))
        AlertDialog.Builder(this)
            .setTitle(R.string.alive_threshold)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                PrefsManager.setAliveThresholdHours(this, values[which])
                dialog.dismiss()
                refreshStates()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun editAlivePhone() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_PHONE
            setText(AliveCenter.notifyPhone(this@AdminActivity).orEmpty())
            setSelection(text.length)
            hint = getString(R.string.alive_phone)
        }
        val container = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.alive_phone)
            .setView(container)
            .setPositiveButton(R.string.confirm) { _, _ ->
                PrefsManager.setAlivePhone(this, input.text.toString())
                refreshStates()
            }
            // 「用紧急联系人的号码」= 把单独号码清空，读取时会自动回落到 SOS
            .setNeutralButton(R.string.alive_phone_use_sos) { _, _ ->
                PrefsManager.setAlivePhone(this, "")
                refreshStates()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickAlivePause() {
        val days = intArrayOf(0, 1, 3, 7)
        val labels = arrayOf(
            getString(R.string.alive_pause_off),
            getString(R.string.alive_pause_days, 1),
            getString(R.string.alive_pause_days, 3),
            getString(R.string.alive_pause_days, 7)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.alive_pause)
            .setItems(labels) { _, which ->
                AliveCenter.pauseDays(this, days[which])
                refreshStates()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 长按诊断：把内部状态全摊出来，排查「为什么没提醒」 */
    private fun showAliveDebug() {
        val lastActive = PrefsManager.getAliveLastActive(this)
        val lastAlert = PrefsManager.getAliveLastAlert(this)
        val nextCheck = AliveCenter.nextCheckAt(this)
        val accessibilityOn = PermissionHelper.isAccessibilityEnabled(this)

        val body = listOf(
            getString(
                R.string.alive_debug_switch,
                getString(if (PrefsManager.isAliveEnabled(this)) R.string.alive_on else R.string.alive_off)
            ),
            if (lastActive > 0) {
                getString(
                    R.string.alive_debug_last,
                    AliveCenter.formatTime(lastActive),
                    AliveCenter.idleHours(this)
                )
            } else {
                getString(R.string.alive_debug_last, getString(R.string.alive_never), 0)
            },
            getString(R.string.alive_debug_threshold, PrefsManager.getAliveThresholdHours(this)),
            getString(R.string.alive_debug_count, PrefsManager.getAliveAlertCount(this)),
            getString(R.string.alive_debug_last_alert, AliveCenter.formatTime(lastAlert)),
            getString(
                R.string.alive_debug_pause,
                if (AliveCenter.isPaused(this))
                    AliveCenter.formatTime(PrefsManager.getAlivePauseUntil(this))
                else getString(R.string.alive_pause_off)
            ),
            getString(R.string.alive_debug_next, AliveCenter.formatTime(nextCheck)),
            getString(
                R.string.alive_debug_phone,
                AliveCenter.notifyPhone(this) ?: getString(R.string.alive_phone_empty)
            ),
            getString(
                R.string.alive_debug_accessibility,
                getString(
                    if (accessibilityOn) R.string.alive_on
                    else R.string.alive_debug_acc_off
                )
            )
        ).joinToString("\n")

        showScrollText(getString(R.string.alive_debug_title), body)
    }

    private fun showMissedDebug() {
        val lines = MissedCallCenter.debugLines(this)
        val body = if (lines.isEmpty()) getString(R.string.missed_debug_empty)
        else lines.joinToString("\n\n")
        showScrollText(getString(R.string.missed_debug_title), body)
    }

    /** 诊断类弹窗共用的只读文本视图（可长按复制） */
    private fun showScrollText(title: String, body: String) {
        val text = TextView(this).apply {
            this.text = body
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun onLock() {
        AdminGate.lock()
        Toast.makeText(this, R.string.admin_locked_toast, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun openDeviceAdmin() {
        if (PermissionHelper.isDeviceAdminActive(this)) {
            Toast.makeText(this, R.string.admin_dev_admin_on, Toast.LENGTH_SHORT).show()
            return
        }
        val comp = ComponentName(this, ElderDeviceAdminReceiver::class.java)
        val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
        intent.putExtra(
            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            getString(R.string.device_admin_desc)
        )
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.admin_dev_admin_off, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmResetBlocks() {
        AlertDialog.Builder(this)
            .setMessage(R.string.admin_reset_blocks_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                // 删掉存储键 → 下次读取自动回落到出厂默认色块
                PrefsManager.clearHomeBlocks(this)
                Toast.makeText(this, R.string.admin_reset_blocks_done, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
