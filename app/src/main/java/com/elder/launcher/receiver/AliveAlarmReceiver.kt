package com.elder.launcher.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.elder.launcher.util.AliveCenter
import com.elder.launcher.util.RemoteCenter

/**
 * 「恰了么？」的定时检查，顺带做一次远程配置同步。
 *
 * 触发后**无论成功失败都要重新排下一次**：AlarmManager 的闹钟是一次性的，
 * 不在这里续排，检查会在第一个小时之后就永久停掉——这种「跑了一次就没了」的 bug
 * 在真机上很难发现（要等到第二天才暴露）。
 *
 * 远程同步**刻意不单独排闹钟**：多一个 AlarmManager 就多一份电、多一份被 ROM 干掉的机会，
 * 这个闹钟本来每小时就要醒一次，搭车最省。
 */
class AliveAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        try {
            AliveCenter.check(app)
        } catch (_: Exception) {
            // 检查本身出问题不能影响续排
        } finally {
            AliveCenter.schedule(app)
        }

        // 网络请求不能占用 onReceive（主线程）。goAsync 能把进程多留一会儿，
        // 不加它的话同步可能在半路被系统掐掉。
        val pending = goAsync()
        Thread {
            try {
                RemoteCenter.sync(app)
            } catch (_: Exception) {
                // 同步失败不影响主流程，下次闹钟再来
            } finally {
                pending.finish()
            }
        }.start()
    }
}
