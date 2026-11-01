package com.elder.launcher

import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import com.elder.launcher.databinding.ActivityChannelsLinkBinding
import com.elder.launcher.util.ActionExecutor
import com.elder.launcher.util.PrefsManager

/**
 * 「视频号直达」设置页。
 *
 * 入口：主页功能块管理 →「添加什么？」→ 视频号直达（选中后自动跳到这里）。
 * 保存一条视频号的分享链接后，点主页「视频号」块会优先用这条链接让微信直达具体视频，
 * 失败仍会退回原来的无障碍自动导航（发现 → 视频号）。
 */
class ChannelsLinkActivity : BaseActivity() {

    private lateinit var binding: ActivityChannelsLinkBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChannelsLinkBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        binding.etLink.setText(PrefsManager.getChannelsLink(this))

        binding.btnPaste.setOnClickListener {
            val text = clipboardText()
            if (text.isNullOrEmpty()) {
                toast(getString(R.string.channels_link_paste_empty))
                return@setOnClickListener
            }
            // 转发文案里往往夹着链接，抠出 https 开头那条；没有就整段填
            val url = Regex("https?://\\S+").find(text)?.value ?: text
            binding.etLink.setText(url)
            binding.etLink.setSelection(url.length)
        }

        binding.btnTest.setOnClickListener {
            val link = binding.etLink.text.toString().trim()
            if (link.isEmpty()) {
                toast(getString(R.string.channels_link_empty))
                return@setOnClickListener
            }
            val ok = ActionExecutor.tryOpenChannelsLink(this, link)
            toast(getString(if (ok) R.string.channels_link_test_ok else R.string.channels_link_test_fail))
        }

        binding.btnClear.setOnClickListener {
            binding.etLink.setText("")
            PrefsManager.setChannelsLink(this, "")
            toast(getString(R.string.channels_link_cleared))
        }

        binding.btnSave.setOnClickListener {
            val link = binding.etLink.text.toString().trim()
            PrefsManager.setChannelsLink(this, link)
            toast(getString(if (link.isEmpty()) R.string.channels_link_cleared else R.string.channels_link_saved))
            finish()
        }
    }

    private fun clipboardText(): String? {
        val cm = getSystemService(ClipboardManager::class.java) ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
