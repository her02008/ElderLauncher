package com.elder.launcher.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.elder.launcher.MainActivity
import com.elder.launcher.util.AliveCenter

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // 闹钟在关机后全部失效，开机必须重排，否则「恰了么？」从此不再检查
            AliveCenter.schedule(context)

            val i = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
        }
    }
}
