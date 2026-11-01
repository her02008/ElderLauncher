package com.elder.launcher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.elder.launcher.databinding.ActivitySettingsBinding
import com.elder.launcher.util.FontSizeManager
import com.elder.launcher.util.PrefsManager

/**
 * 普通设置页：只放日常项——字号、首页板块顺序、快捷拨号、紧急联系人、语音开关、关于。
 *
 * 「会改变桌面形态 / 涉及系统权限」的高级项（主页功能块、系统权限、手机桌面、管理密码、
 * 恢复默认）**全部收进 [AdminActivity]**（高级设置），统一在那边过管理密码后设置，
 * 这里只留一张「高级设置」入口卡片，避免同一件事在两个页面各有一个入口。
 */
class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val sosPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val d = result.data!!
            val name = d.getStringExtra(ContactsPickerActivity.EXTRA_NAME) ?: ""
            val phone = d.getStringExtra(ContactsPickerActivity.EXTRA_PHONE) ?: ""
            if (phone.isNotEmpty()) {
                PrefsManager.setSosContact(this, name, phone)
                refreshSosContact()
                Toast.makeText(this, "已设置紧急联系人：$name", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }
        setupFont()
        setupHomeOrder()
        setupQuickDials()
        setupSos()
        setupVoice()
        setupAdminEntry()
        binding.tvAbout.text = getString(
            R.string.settings_about_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE
        )
    }

    /**
     * 首页板块顺序：调整主页上「紧急求助 / 功能块 / 快捷拨号」三块内容的上下位置。
     * 只是显示顺序、随时能改回来，所以不设密码，家属自己就能调。
     */
    private fun setupHomeOrder() {
        refreshHomeOrder()
        binding.rowHomeOrder.setOnClickListener {
            startActivity(Intent(this, HomeOrderActivity::class.java))
        }
    }

    /** 副标题直接显示当前顺序，一眼看出现在谁在上谁在下 */
    private fun refreshHomeOrder() {
        val order = PrefsManager.getHomeOrder(this)
            .joinToString(" › ") { key -> HomeOrderActivity.labelOf(this, key) }
        binding.tvHomeOrder.text = getString(R.string.settings_home_order_current, order)
    }

    /** 高级设置聚合页：功能块、系统权限、手机桌面、管理密码、恢复默认都在里面，进去要先过管理密码 */
    private fun setupAdminEntry() {
        binding.rowAdminEntry.setOnClickListener {
            if (PasswordActivity.require(this, PasswordActivity.TARGET_ADMIN)) return@setOnClickListener
            startActivity(Intent(this, AdminActivity::class.java))
        }
    }

    /** 快捷拨号子菜单：添加家人、设置头像图片或颜色 */
    private fun setupQuickDials() {
        binding.rowQuickDials.setOnClickListener {
            startActivity(Intent(this, QuickDialsActivity::class.java))
        }
    }

    private fun refreshQuickDials() {
        val count = PrefsManager.getQuickContacts(this).size
        binding.tvQuickDialsCount.text = if (count == 0)
            getString(R.string.settings_quick_dials_none)
        else
            getString(R.string.settings_quick_dials_count, count)
    }

    private fun setupFont() {
        val level = PrefsManager.getFontLevel(this)
        when (level) {
            FontSizeManager.LEVEL_NORMAL -> binding.rbNormal.isChecked = true
            FontSizeManager.LEVEL_LARGE -> binding.rbLarge.isChecked = true
            FontSizeManager.LEVEL_XLARGE -> binding.rbXLarge.isChecked = true
        }
        binding.rgFont.setOnCheckedChangeListener { _, checkedId ->
            val newLevel = when (checkedId) {
                R.id.rbNormal -> FontSizeManager.LEVEL_NORMAL
                R.id.rbLarge -> FontSizeManager.LEVEL_LARGE
                R.id.rbXLarge -> FontSizeManager.LEVEL_XLARGE
                else -> FontSizeManager.LEVEL_NORMAL
            }
            if (newLevel != PrefsManager.getFontLevel(this)) {
                PrefsManager.setFontLevel(this, newLevel)
                recreate()
            }
        }
    }

    private fun setupSos() {
        refreshSosContact()
        binding.rowSos.setOnClickListener {
            sosPicker.launch(Intent(this, ContactsPickerActivity::class.java))
        }
    }

    private fun refreshSosContact() {
        val (name, phone) = PrefsManager.getSosContact(this)
        binding.tvSosContact.text = if (phone.isEmpty())
            getString(R.string.settings_sos_contact_none) else "$name $phone"
    }

    private fun setupVoice() {
        binding.swVoice.isChecked = PrefsManager.isVoiceEnabled(this)
        binding.swVoice.setOnCheckedChangeListener { _, on ->
            PrefsManager.setVoiceEnabled(this, on)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshQuickDials()
        refreshHomeOrder()
    }
}
