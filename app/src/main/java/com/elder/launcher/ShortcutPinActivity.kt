package com.elder.launcher

import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.elder.launcher.util.BlockCatalog
import com.elder.launcher.util.BlockType
import com.elder.launcher.util.PinnedShortcuts
import com.elder.launcher.util.PrefsManager

/**
 * 「添加到桌面」的确认页 —— 别家 App 发来的固定快捷方式请求就落在这里。
 *
 * 微信小程序、浏览器、其它平台小程序的「发送到桌面」在 Android 8.0 以上都走
 * `ShortcutManager.requestPinShortcut()`。系统**不会自己弹确认框**，而是去**默认桌面**里找
 * 一个声明了 `android.content.pm.action.CONFIRM_PIN_SHORTCUT` 的 Activity 并把它拉起来，
 * 由这个 Activity 负责问用户。找不到就直接失败
 *（系统日志：`Launcher doesn't support requestPinnedShortcut(). Shortcut not created.`）——
 * 所以**没有本页，微信/浏览器的「添加到桌面」在鸿蒙上点了是没有任何反应的**。
 *
 * 用户点「添加」后：
 * 1. `PinItemRequest.accept()`：系统把这条快捷方式记到本项目名下（pinned for this launcher），
 *    之后 [PinnedShortcuts] 就能枚举到并用 `LauncherApps.startShortcut` 原样启动；
 * 2. 顺手往主页加一个 [BlockType.SHORTCUT] 色块——「发送到桌面」本来就该落到主页上；
 *    不想要了可以在「主页功能块」里删掉。
 *
 * 这里**不设管理密码**：请求是长辈点「添加到桌面」发起的，中间插一道密码就断链了；
 * 而且这个动作只是加一个图标，不破坏任何现有配置。
 *
 * 透明主题的 Activity：本页继承 [BaseActivity]（AppCompatActivity）以拿到长辈字号缩放，
 * 所以主题必须用 AppCompat 系的 `@style/Theme.Elder.Translucent`，
 * **不能**用 `@android:style/Theme.Translucent.NoTitleBar`（AppCompat 主题校验会崩）。
 */
class ShortcutPinActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            finish()
            return
        }
        val launcherApps = getSystemService(LauncherApps::class.java)
        val request = launcherApps?.getPinItemRequest(intent)
        if (request == null || !request.isValid ||
            request.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_SHORTCUT
        ) {
            finish()
            return
        }
        val info = request.shortcutInfo
        if (info == null) {
            finish()
            return
        }
        val label = PinnedShortcuts.labelOf(info).ifEmpty { getString(R.string.pin_default_label) }
        AlertDialog.Builder(this)
            .setTitle(R.string.pin_title)
            .setMessage(getString(R.string.pin_message, label))
            .setPositiveButton(R.string.pin_add) { _, _ -> accept(request, info, label) }
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    /** 接受请求 + 把色块加到主页 + 提示用户「放好了」 */
    private fun accept(request: LauncherApps.PinItemRequest, info: ShortcutInfo, label: String) {
        val accepted = try {
            request.accept()
        } catch (_: Exception) {
            false
        }
        if (!accepted) {
            toast(getString(R.string.pin_failed))
            finish()
            return
        }
        if (addHomeBlock(info, label)) {
            toast(getString(R.string.pin_added, label))
        } else {
            // 色块位满了：快捷方式本身已经加上了（在「桌面快捷方式」列表里能勾回来），
            // 只是主页没地方放，提示一下别让用户以为没成功
            toast(getString(R.string.blocks_full, BlockCatalog.MAX_BLOCKS))
        }
        finish()
    }

    /**
     * 往主页加一个快捷方式色块。
     *
     * @return false = 主页色块已达上限，没加（快捷方式本身已由系统固定好了）
     */
    private fun addHomeBlock(info: ShortcutInfo, label: String): Boolean {
        val target = PinnedShortcuts.targetOf(PinnedShortcuts.packageOf(info), info.id)
        val blocks = PrefsManager.getHomeBlocks(this)
        // 同一条快捷方式已经加过（可能加在二级菜单里）就不重复加
        if (target in BlockCatalog.usedKeys(blocks, BlockType.SHORTCUT)) return true
        if (blocks.size >= BlockCatalog.MAX_BLOCKS) return false
        val palette = BlockCatalog.palette(this)
        val color = palette[blocks.size % palette.size]
        blocks.add(BlockCatalog.shortcut(label, target, color))
        PrefsManager.setHomeBlocks(this, blocks)
        return true
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
