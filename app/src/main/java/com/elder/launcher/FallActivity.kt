package com.elder.launcher

import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import com.elder.launcher.databinding.ActivityFallBinding
import com.elder.launcher.util.AliveCenter
import com.elder.launcher.util.FallDetector
import com.elder.launcher.util.TtsManager

/**
 * 跌倒检测的提醒页：检测到摔落且约 90 秒没人捡起时全屏拉起（锁屏上也能弹）。
 *
 * 大按钮「我没事」= 机主回应，响铃和后续短信全部取消。
 * 按返回键也视同回应——人能看到这个页面本身就说明没事了；
 * 真正的危险场景是**没人碰手机**，那才是要发短信的。
 */
class FallActivity : BaseActivity() {

    private lateinit var binding: ActivityFallBinding
    private val ui = Handler(Looper.getMainLooper())

    private val uiTick = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            updateState()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 手机可能屏幕朝下躺在地上：要能亮屏、要能盖在锁屏上面
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        binding = ActivityFallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnFallOk.setOnClickListener {
            FallDetector.ownerOk()
            finish()
        }
        TtsManager.speak(this, getString(R.string.fall_tts))
    }

    override fun onResume() {
        super.onResume()
        updateState()
        ui.post(uiTick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(uiTick)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // 能看到这个页面 = 机主已经回应了，按「我没事」同等处理
        FallDetector.ownerOk()
        finish()
    }

    private fun updateState() {
        if (!FallDetector.inAlert()) {
            binding.tvFallState.text = getString(R.string.fall_act_watch)
            binding.tvFallPicked.visibility = android.view.View.GONE
            return
        }
        if (FallDetector.isCallPlaced()) {
            binding.tvFallState.text = getString(R.string.fall_act_called)
        } else if (FallDetector.isSmsHandled()) {
            binding.tvFallState.text =
                if (AliveCenter.notifyPhone(this) == null) {
                    getString(R.string.fall_act_no_phone)
                } else {
                    getString(R.string.fall_act_sent)
                }
        } else {
            binding.tvFallState.text =
                getString(R.string.fall_act_countdown, FallDetector.alertRemainingSeconds())
        }
        binding.tvFallPicked.visibility = android.view.View.VISIBLE
    }
}
