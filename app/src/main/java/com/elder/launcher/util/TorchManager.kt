package com.elder.launcher.util

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

object TorchManager {
    @Volatile private var on = false
    private var cameraId: String? = null
    private var cameraManager: CameraManager? = null

    private fun resolveCamera(ctx: Context): String? {
        if (cameraId != null) return cameraId
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraManager = cm
        for (id in cm.cameraIdList) {
            val c = cm.getCameraCharacteristics(id)
            val has = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (has) {
                cameraId = id
                return id
            }
        }
        return null
    }

    fun isOn(): Boolean = on

    fun toggle(ctx: Context): Boolean {
        val id = resolveCamera(ctx) ?: run {
            Log.w("TorchManager", "no flash camera")
            return false
        }
        val cm = cameraManager ?: return false
        return try {
            on = !on
            cm.setTorchMode(id, on)
            on
        } catch (e: Exception) {
            Log.e("TorchManager", "toggle failed", e)
            on = false
            false
        }
    }

    fun off(ctx: Context) {
        if (!on) return
        val id = cameraId ?: return
        try {
            (ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager)
                .setTorchMode(id, false)
            on = false
        } catch (_: Exception) {}
    }
}
