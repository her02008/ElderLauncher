package com.elder.launcher

import android.app.AlertDialog
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.elder.launcher.databinding.ActivityRemoteBinding
import com.elder.launcher.util.AdminGate
import com.elder.launcher.util.PrefsManager
import com.elder.launcher.util.RemoteCenter
import com.elder.launcher.util.RemoteClient

/**
 * 远程设置：把手机绑到家属自己的服务端。
 *
 * 绑定只做一次。之后不靠这个界面——`AliveAlarmReceiver` 每小时顺带同步一次，
 * 所以家属关掉页面、甚至老人不知道这个功能存在，都不影响生效。
 *
 * 这里和高级设置一样受管理密码保护（`AdminActivity` 进来的，session 过期就退出）。
 */
class RemoteActivity : BaseActivity() {

    private lateinit var binding: ActivityRemoteBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AdminGate.isUnlocked()) {
            // 密码会话过期（比如在系统设置里待久了）。退回高级设置，那边会重新要密码
            finish()
            return
        }

        binding = ActivityRemoteBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnBind.setOnClickListener { onBind() }
        binding.btnSync.setOnClickListener { onSync() }
        binding.btnDiag.setOnClickListener { showDiagnosis() }
        binding.btnUnbind.setOnClickListener { confirmUnbind() }

        loadCurrent()
    }

    override fun onResume() {
        super.onResume()
        if (!AdminGate.isUnlocked()) {
            finish()
            return
        }
        AdminGate.touch()
        loadCurrent()
    }

    // ---------------- 界面状态 ----------------

    private fun loadCurrent() {
        val bound = PrefsManager.isRemoteBound(this)
        binding.tvRemoteState.text = getString(
            if (bound) R.string.remote_state_bound else R.string.remote_state_unbound
        )

        binding.tvRemoteDetail.text = if (!bound) {
            getString(R.string.remote_state_unbound_hint)
        } else {
            val err = PrefsManager.getRemoteError(this)
            buildString {
                append(getString(R.string.remote_detail_endpoint, PrefsManager.getRemoteEndpoint(this@RemoteActivity)))
                append("\n")
                append(getString(R.string.remote_detail_code, PrefsManager.getRemoteCode(this@RemoteActivity)))
                append("\n")
                append(getString(R.string.remote_detail_sync, RemoteCenter.lastSyncText(this@RemoteActivity)))
                append("\n")
                append(
                    if (err.isEmpty()) getString(R.string.remote_detail_ok)
                    else getString(R.string.remote_detail_error, err)
                )
                val pending = RemoteCenter.pendingList(this@RemoteActivity).size
                if (pending > 0) {
                    append("\n")
                    append(getString(R.string.remote_detail_pending, pending))
                }
            }
        }

        if (bound) {
            binding.etEndpoint.setText(PrefsManager.getRemoteEndpoint(this))
            binding.etCode.setText(PrefsManager.getRemoteCode(this))
            binding.btnBind.text = getString(R.string.remote_rebind)
        } else {
            binding.btnBind.text = getString(R.string.remote_bind)
        }
        binding.btnSync.isEnabled = bound
        binding.btnUnbind.isEnabled = bound
        binding.btnSync.alpha = if (bound) 1f else 0.4f
        binding.btnUnbind.alpha = if (bound) 1f else 0.4f
    }

    // ---------------- 绑定 ----------------

    private fun onBind() {
        val rawEndpoint = binding.etEndpoint.text.toString().trim()
        val rawCode = binding.etCode.text.toString().trim()
        val endpoint = RemoteClient.normalizeEndpoint(rawEndpoint)
        if (endpoint == null) {
            toast(R.string.remote_bad_endpoint)
            return
        }
        val code = rawCode.uppercase().filter { it.isLetterOrDigit() }
        if (code.length !in 4..16) {
            toast(R.string.remote_bad_code)
            return
        }

        val name = binding.etName.text.toString().trim()
        setBusy(true, R.string.remote_binding)
        RemoteCenter.bindAsync(applicationContext, endpoint, code, name) { result ->
            if (isFinishing || isDestroyed) return@bindAsync
            setBusy(false, 0)
            if (result.ok) {
                binding.etEndpoint.setText(endpoint)
                binding.etCode.setText(code)
                toast(R.string.remote_bind_ok)
                loadCurrent()
            } else {
                toastText(errorText(result.error))
            }
        }
    }

    // ---------------- 同步 ----------------

    private fun onSync() {
        if (!PrefsManager.isRemoteBound(this)) {
            toast(R.string.remote_state_unbound)
            return
        }
        setBusy(true, R.string.remote_syncing)
        RemoteCenter.syncAsync(applicationContext) { result ->
            if (isFinishing || isDestroyed) return@syncAsync
            setBusy(false, 0)
            when {
                result.skipped -> toast(R.string.remote_busy)
                result.ok && result.pending > 0 -> toastText(
                    getString(R.string.remote_sync_pending, result.pending)
                )

                result.ok -> toast(R.string.remote_sync_ok)
                else -> toastText(errorText(result.error))
            }
            loadCurrent()
        }
    }

    private fun setBusy(busy: Boolean, textRes: Int) {
        binding.btnBind.isEnabled = !busy
        binding.btnSync.isEnabled = !busy && PrefsManager.isRemoteBound(this)
        binding.btnUnbind.isEnabled = !busy && PrefsManager.isRemoteBound(this)
        if (busy && textRes != 0) binding.btnBind.text = getString(textRes)
    }

    private fun errorText(err: String): String = when {
        err == "endpoint" -> getString(R.string.remote_bad_endpoint)
        err == "code" -> getString(R.string.remote_bad_code)
        err == "auth" -> getString(R.string.remote_auth_failed)
        err == "unbound" -> getString(R.string.remote_state_unbound)
        err.startsWith("http 404") || err.startsWith("http 403") -> getString(R.string.remote_err_notfound)
        err.startsWith("http") -> getString(R.string.remote_err_http, err)
        err.isEmpty() -> getString(R.string.remote_err_unknown)
        else -> getString(R.string.remote_err_network, err)
    }

    // ---------------- 解绑 / 诊断 ----------------

    private fun confirmUnbind() {
        AlertDialog.Builder(this)
            .setTitle(R.string.remote_unbind)
            .setMessage(R.string.remote_unbind_confirm)
            .setPositiveButton(R.string.confirm) { _, _ ->
                PrefsManager.clearRemoteBinding(this)
                // 解绑后如果「恰了么？」也没开，这个每小时闹钟就没必要再跑了
                com.elder.launcher.util.AliveCenter.schedule(this)
                toast(R.string.remote_unbind_done)
                binding.etEndpoint.setText("")
                binding.etCode.setText("")
                loadCurrent()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDiagnosis() {
        val body = buildString {
            append(getString(R.string.remote_diag_unbound_state, getString(
                if (PrefsManager.isRemoteBound(this@RemoteActivity)) R.string.alive_on
                else R.string.alive_off
            )))
            append("\n")
            append(getString(R.string.remote_diag_device, PrefsManager.getDeviceId(this@RemoteActivity)))
            append("\n")
            append(getString(R.string.remote_diag_endpoint, PrefsManager.getRemoteEndpoint(this@RemoteActivity).ifEmpty { "-" }))
            append("\n")
            append(getString(R.string.remote_diag_code, PrefsManager.getRemoteCode(this@RemoteActivity).ifEmpty { "-" }))
            append("\n")
            append(getString(R.string.remote_diag_rev, PrefsManager.getRemoteRev(this@RemoteActivity)))
            append("\n")
            append(getString(R.string.remote_detail_sync, RemoteCenter.lastSyncText(this@RemoteActivity)))
            append("\n")
            append(getString(R.string.remote_diag_error, PrefsManager.getRemoteError(this@RemoteActivity).ifEmpty { "-" }))
            append("\n")
            append(getString(R.string.remote_diag_pending, RemoteCenter.pendingList(this@RemoteActivity).size))
            append("\n")
            append(getString(R.string.remote_diag_next, com.elder.launcher.util.AliveCenter.formatTime(
                com.elder.launcher.util.AliveCenter.nextCheckAt(this@RemoteActivity)
            )))
        }
        val text = TextView(this).apply {
            this.text = body
            textSize = 13f
            setTextIsSelectable(true)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val scroll = ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this)
            .setTitle(R.string.remote_diag_title)
            .setView(scroll)
            .setPositiveButton(R.string.confirm, null)
            .show()
    }

    // ---------------- 小工具 ----------------

    private fun toast(resId: Int) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
    }

    private fun toastText(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
