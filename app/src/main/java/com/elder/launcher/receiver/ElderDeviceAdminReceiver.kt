package com.elder.launcher.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

class ElderDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
    }
}
