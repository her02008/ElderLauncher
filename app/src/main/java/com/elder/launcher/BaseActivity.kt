package com.elder.launcher

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.elder.launcher.util.FontSizeManager

abstract class BaseActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(FontSizeManager.apply(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }
}
