package com.elder.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.elder.launcher.databinding.ActivitySosBinding
import com.elder.launcher.util.ActionExecutor
import com.elder.launcher.util.PrefsManager
import com.elder.launcher.util.TtsManager

class SosActivity : BaseActivity() {

    private lateinit var binding: ActivitySosBinding
    private val handler = Handler(Looper.getMainLooper())
    private var canceled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySosBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val (name, phone) = PrefsManager.getSosContact(this)
        if (phone.isEmpty()) {
            Toast.makeText(this, R.string.sos_no_contact, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        TtsManager.speak(this, getString(R.string.sos_title))
        binding.btnCancel.setOnClickListener {
            canceled = true
            finish()
        }

        binding.tvStatus.text = getString(R.string.sos_locating)
        val loc = getLastLocation()
        sendSosSms(phone, name, loc)
    }

    @Suppress("DEPRECATION")
    private fun sendSosSms(phone: String, name: String, loc: Location?) {
        if (canceled) return
        val msg = if (loc != null) {
            val url = "https://maps.baidu.com/?lat=${loc.latitude}&lon=${loc.longitude}"
            "我是${name.ifEmpty { "家中老人" }}，遇到紧急情况需要帮助！我的位置：${loc.latitude},${loc.longitude}\n地图：$url"
        } else {
            "我是${name.ifEmpty { "家中老人" }}，遇到紧急情况需要帮助！请尽快联系我。"
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) ==
            PackageManager.PERMISSION_GRANTED) {
            try {
                val sm = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                    getSystemService(SmsManager::class.java) else SmsManager.getDefault()
                sm.sendTextMessage(phone, null, msg, null, null)
                Toast.makeText(this, R.string.sos_sms_sent, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "短信发送失败：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
        binding.tvStatus.text = getString(R.string.sos_calling)
        handler.postDelayed({
            if (!canceled && ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) ==
                PackageManager.PERMISSION_GRANTED) {
                ActionExecutor.call(this, phone)
            } else {
                ActionExecutor.dial(this, phone)
            }
        }, 1500)
    }

    @SuppressLint("MissingPermission")
    private fun getLastLocation(): Location? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED) return null
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = lm.getProviders(true)
        for (p in providers) {
            val l = try { lm.getLastKnownLocation(p) } catch (_: SecurityException) { null }
            if (l != null) return l
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
    }
}
