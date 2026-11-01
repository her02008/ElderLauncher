package com.elder.launcher

import android.app.Application
import com.elder.launcher.util.FallDetector
import com.elder.launcher.util.TtsManager

class ElderApp : Application() {
    override fun onCreate() {
        super.onCreate()
        TtsManager.init(this)
        // 跌倒检测是进程级的：开关联动亮屏/熄屏注册，不依赖任何页面
        FallDetector.init(this)
    }
}
