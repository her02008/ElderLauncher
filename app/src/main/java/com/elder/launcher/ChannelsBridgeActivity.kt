package com.elder.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.elder.launcher.service.ElderAccessibilityService
import com.elder.launcher.util.ActionExecutor

/**
 * 视频号分享链接的「隐形跳板」。
 *
 * 这个页面**绝不显示网页内容**（WebView 设为 INVISIBLE），只在后台加载分享页，想办法拿到
 * 微信的 `weixin://` 跳转地址，再由我们原生地拉起微信。为什么必须这样：
 *
 * - 甩给系统浏览器，用户要再点一次「打开微信」；
 * - 让自己显示页面更糟——那是普通网页播放器，**不能上下滑切换视频**，返回键还会退掉这一层。
 *
 * 提高命中率的四道网（微信并没有义务配合，所以只能多撒网）：
 * 1. `shouldOverrideUrlLoading`：页面自己跳 `weixin://` 时拦下来；
 * 2. `onConsoleMessage`：WebView 会把「非用户手势不允许跳转 weixin://」这类被拦下的地址
 *    打进控制台，从日志里把它捞出来——**这正是模拟点击拿不到跳转时最有效的一招**；
 * 3. JS 探针：先找 `a[href^="weixin://"]`，再扫整页 HTML，还会异步 fetch 页面自己的
 *    js/json 资源（地址常藏在打包脚本里），命中就记到 `window.__wbLink`；
 * 4. JS 同时派发真实 MouseEvent 点「打开微信」按钮。
 *
 * 约 4 秒还拿不到就收手，交给 [ActionExecutor.openChannelsFallback]，
 * 绝不让用户停在一个空页面上。
 */
class ChannelsBridgeActivity : Activity() {

    companion object {
        const val EXTRA_URL = "url"

        /** 探针轮次与间隔（约 4 秒内出结果） */
        private const val MAX_TRIES = 6
        private const val TRY_INTERVAL_MS = 700L

        /** 硬兜底：页面白屏 / 断网也能收场 */
        private const val GIVE_UP_MS = 5000L

        /**
         * 页面探针，回给 Java 侧：`U|<weixin url>` 找到地址 / `C` 点中了按钮 / `N` 还没有。
         */
        private const val JS = """
(function(){
  try{
    if(window.__wbLink){return 'U|'+window.__wbLink;}
    var as=document.querySelectorAll('a[href^="weixin://"]');
    if(as.length>0){return 'U|'+as[0].getAttribute('href');}
    var html=document.documentElement.outerHTML||'';
    var m=html.match(/weixin:\/\/dl\/business\/[^"'\s<>\\]+/);
    if(m){return 'U|'+m[0];}
    var m1=html.match(/weixin:\/\/[^"'\s<>\\]{6,}/);
    if(m1){return 'U|'+m1[0];}
    if(!window.__wbScan){
      window.__wbScan=true;
      var urls=[];
      var ss=document.querySelectorAll('script[src]');
      for(var i=0;i<ss.length;i++){urls.push(ss[i].src);}
      try{
        var rs=performance.getEntriesByType('resource')||[];
        for(var k=0;k<rs.length;k++){
          if(/\.(js|json)(\?|$)/.test(rs[k].name)){urls.push(rs[k].name);}
        }
      }catch(e){}
      var seen={};
      urls.slice(0,14).forEach(function(u){
        if(!u||seen[u]){return;}
        seen[u]=1;
        try{
          fetch(u).then(function(r){return r.text();}).then(function(t){
            var mm=t.match(/weixin:\/\/[^"'\s<>\\]{6,}/);
            if(mm&&!window.__wbLink){window.__wbLink=mm[0];}
          }).catch(function(){});
        }catch(e){}
      });
    }
    var kw=['打开微信','前往微信','微信中打开','继续访问','点击播放'];
    var els=document.querySelectorAll('a,button,div,span,p');
    for(var i2=0;i2<els.length;i2++){
      var t=(els[i2].textContent||'').trim();
      if(!t||t.length>20){continue;}
      for(var j=0;j<kw.length;j++){
        if(t.indexOf(kw[j])>=0){
          try{els[i2].dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));}catch(e){}
          try{els[i2].click();}catch(e){}
          return 'C';
        }
      }
    }
    return 'N';
  }catch(e){return 'N';}
})()
"""
    }

    private lateinit var web: WebView
    private var launching = false
    private var tries = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.getStringExtra(EXTRA_URL)
        if (url.isNullOrEmpty()) {
            finish()
            return
        }

        val root = FrameLayout(this)
        root.setBackgroundColor(0xDD000000.toInt())

        web = WebView(this)
        web.visibility = View.INVISIBLE   // 关键：只做跳板，绝不显示网页内容
        root.addView(
            web,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val tip = TextView(this).apply {
            text = getString(R.string.channels_bridge_waiting)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 22f
            gravity = Gravity.CENTER
        }
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.gravity = Gravity.CENTER
        root.addView(tip, lp)
        setContentView(root)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val u = request?.url ?: return false
                return intercept(u)
            }

            override fun onPageFinished(view: WebView?, u: String?) {
                probe()
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            /** WebView 拦下「非用户手势的 weixin:// 跳转」时会打印地址，从日志里捡回来 */
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                val text = msg?.message() ?: return false
                if (!launching && text.contains("weixin://")) {
                    val m = Regex("weixin://[^\\s'\"()]+").find(text)
                    if (m != null) {
                        launchWeixin(Uri.parse(m.value))
                        return true
                    }
                }
                return false
            }
        }

        web.loadUrl(url)

        // 硬兜底：页面一直不回调（断网 / 白屏）也能收场
        web.postDelayed({ giveUp() }, GIVE_UP_MS)
    }

    /**
     * 页面请求分流：微信 scheme 直接原生拉起，其余一律放行让 WebView 自己走完跳转链
     * （分享链接常有中转页，拦下来反而会拿不到最终的微信跳转地址）。
     */
    private fun intercept(u: Uri): Boolean {
        if (launching) return true
        when (u.scheme?.lowercase()) {
            "weixin" -> {
                launchWeixin(u)
                return true
            }
            "intent" -> {
                // intent://dl/business/?...#Intent;scheme=weixin;... → 还原成 weixin://
                val s = u.toString()
                val scheme = Regex("scheme=([a-zA-Z0-9.+-]+)").find(s)?.groupValues?.get(1)
                if (scheme.equals("weixin", ignoreCase = true)) {
                    val data = s.substringBefore("#Intent").removePrefix("intent://")
                    launchWeixin(Uri.parse("weixin://$data"))
                }
                return true
            }
            else -> return false
        }
    }

    /** 解析页面探针结果 */
    private fun probe() {
        if (launching || isFinishing || isDestroyed) return
        if (tries >= MAX_TRIES) {
            giveUp()
            return
        }
        tries++
        web.evaluateJavascript(JS) { raw ->
            val text = decode(raw)
            val idx = text.indexOf("U|")
            if (idx >= 0) {
                val link = text.substring(idx + 2)
                if (link.startsWith("weixin://")) {
                    launchWeixin(Uri.parse(link))
                    return@evaluateJavascript
                }
            }
            web.postDelayed({ probe() }, TRY_INTERVAL_MS)
        }
    }

    /** evaluateJavascript 回传的是 JSON 字符串，去掉引号与转义 */
    private fun decode(raw: String?): String {
        var s = raw ?: return ""
        if (s.length >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length - 1)
        }
        return s.replace("\\/", "/").replace("\\\"", "\"").replace("\\n", "")
    }

    private fun launchWeixin(uri: Uri) {
        if (launching) return
        launching = true
        val i = Intent(Intent.ACTION_VIEW, uri)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.wx_not_installed, Toast.LENGTH_SHORT).show()
        }
        finish()
    }

    /** 链接这条路走不通：无障碍在就自动进视频号；不在就回桌面弹「开启无障碍」引导 */
    private fun giveUp() {
        if (launching || isFinishing || isDestroyed) return
        launching = true
        if (ElderAccessibilityService.isReady()) {
            ActionExecutor.openChannelsFallback(this)
        } else {
            // 别静默收场：回桌面弹引导（里面有「只打开微信」「取消」可选）
            val i = Intent(this, MainActivity::class.java)
            i.putExtra(MainActivity.EXTRA_CHANNELS_HINT, true)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            try {
                startActivity(i)
            } catch (_: Exception) {
            }
        }
        finish()
    }

    override fun onDestroy() {
        web.stopLoading()
        web.destroy()
        super.onDestroy()
    }
}
