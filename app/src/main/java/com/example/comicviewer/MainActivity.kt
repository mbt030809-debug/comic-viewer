package com.example.comicviewer

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*

/** 웹툰 전용 브라우저: 웹페이지 그대로 + 두쪽 보기 + 자동/페이지 스크롤 */
class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var web: WebView
    private lateinit var urlInput: EditText
    private lateinit var topBar: View
    private lateinit var autoBtn: Button
    private lateinit var twoBtn: Button

    private var two get() = prefs.getBoolean("two", false); set(v) = prefs.edit().putBoolean("two", v).apply()
    private var rtl get() = prefs.getBoolean("rtl", false); set(v) = prefs.edit().putBoolean("rtl", v).apply()
    private var cover get() = prefs.getBoolean("cover", false); set(v) = prefs.edit().putBoolean("cover", v).apply()
    private var adblock get() = prefs.getBoolean("adblock", true); set(v) = prefs.edit().putBoolean("adblock", v).apply()
    private var smoothMs get() = prefs.getInt("ms", 500); set(v) = prefs.edit().putInt("ms", v).apply()
    private var autoSpeed get() = prefs.getInt("speed", 120); set(v) = prefs.edit().putInt("speed", v).apply() // px/sec

    private var pageAnim: ValueAnimator? = null
    private var auto = false

    private val adHosts = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google",
        "adnxs.com", "taboola.com", "outbrain.com", "criteo.", "adsterra", "popads", "popcash",
        "exoclick", "propellerads", "adsystem", "moatads", "2mdn.net", "adform.net", "adcash",
    )

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("cv", MODE_PRIVATE)
        web = findViewById(R.id.web); urlInput = findViewById(R.id.urlInput)
        topBar = findViewById(R.id.topBar); autoBtn = findViewById(R.id.autoBtn); twoBtn = findViewById(R.id.twoBtn)

        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
            setSupportMultipleWindows(false); javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, u: String, f: Bitmap?) { urlInput.setText(u); prefs.edit().putString("last", u).apply() }
            override fun onPageFinished(v: WebView, u: String) { injectScript() }
            override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
                if (adblock) {
                    val h = r.url.host ?: ""
                    if (adHosts.any { h.contains(it) }) return WebResourceResponse("text/plain", "utf-8", null)
                }
                return null
            }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val s = r.url.scheme
                return s != "http" && s != "https"
            }
        }
        web.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN && auto) setAuto(false); false }

        findViewById<View>(R.id.goBtn).setOnClickListener { go(urlInput.text.toString()) }
        urlInput.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_GO) { go(urlInput.text.toString()); true } else false }
        findViewById<View>(R.id.backBtn).setOnClickListener { if (web.canGoBack()) web.goBack() }
        findViewById<View>(R.id.settingsBtn).setOnClickListener { showSettings() }
        findViewById<View>(R.id.menuBtn).setOnClickListener { topBar.visibility = if (topBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        twoBtn.setOnClickListener { setTwo(!two) }
        autoBtn.setOnClickListener { setAuto(!auto) }
        findViewById<View>(R.id.downBtn).setOnClickListener { pageScroll(1) }
        findViewById<View>(R.id.upBtn).setOnClickListener { pageScroll(-1) }
        updateTwoBtn()

        val shared = intent?.dataString ?: intent?.getStringExtra(android.content.Intent.EXTRA_TEXT)
        go(shared ?: prefs.getString("last", null) ?: "https://www.google.com")
    }

    override fun onDestroy() { setAuto(false); web.destroy(); super.onDestroy() }
    override fun onPause() { setAuto(false); super.onPause() }
    @Deprecated("") override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }
    override fun onKeyDown(k: Int, e: KeyEvent): Boolean = when (k) {
        KeyEvent.KEYCODE_VOLUME_DOWN -> { pageScroll(1); true }
        KeyEvent.KEYCODE_VOLUME_UP -> { pageScroll(-1); true }
        else -> super.onKeyDown(k, e)
    }

    private fun go(raw: String) {
        var u = raw.trim(); if (u.isEmpty()) return
        if (!u.contains("://")) u = if (u.contains(".") && !u.contains(" ")) "https://$u" else "https://www.google.com/search?q=" + java.net.URLEncoder.encode(u, "utf-8")
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlInput.windowToken, 0)
        web.loadUrl(u)
    }

    // ---- 두쪽 보기 (페이지 DOM 내 큰 이미지를 2열 그리드로 재배치, 작은 배너/광고 이미지는 제외) ----
    private fun updateTwoBtn() { twoBtn.text = if (two) "두쪽●" else "두쪽" }
    private fun setTwo(v: Boolean) {
        val was = two; two = v; updateTwoBtn()
        if (was && !v) web.reload() else injectScript()
    }

    private fun injectScript() {
        web.evaluateJavascript(SCRIPT.replace("__TWO__", "$two").replace("__RTL__", "$rtl").replace("__COVER__", "$cover"), null)
    }

    // ---- 스크롤 ----
    private fun pageScroll(dir: Int) {
        val dist = (web.height * 0.95f).toInt() * dir
        pageAnim?.cancel()
        if (smoothMs == 0) { web.scrollBy(0, dist); return }
        var done = 0
        pageAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = smoothMs.toLong(); interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val target = (it.animatedValue as Float * dist).toInt()
                web.scrollBy(0, target - done); done = target
            }
            start()
        }
    }

    private val frame = object : Choreographer.FrameCallback {
        var last = 0L; var acc = 0f
        override fun doFrame(t: Long) {
            if (!auto) return
            if (last != 0L) {
                acc += autoSpeed * (t - last) / 1e9f
                val d = acc.toInt()
                if (d != 0) { web.scrollBy(0, d); acc -= d }
            }
            last = t
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private fun setAuto(v: Boolean) {
        if (auto == v) return
        auto = v; autoBtn.text = if (v) "정지" else "자동"
        if (v) { pageAnim?.cancel(); frame.last = 0L; frame.acc = 0f; Choreographer.getInstance().postFrameCallback(frame) }
        else Choreographer.getInstance().removeFrameCallback(frame)
    }

    private fun showSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        var needInject = false; var needReload = false
        fun sw(t: String, v: Boolean, f: (Boolean) -> Unit) = box.addView(Switch(this).apply { text = t; isChecked = v; setOnCheckedChangeListener { _, c -> f(c) } })
        fun bar(t: String, max: Int, v: Int, fmt: (Int) -> String, f: (Int) -> Unit) {
            val l = TextView(this).apply { text = t + fmt(v) }
            box.addView(l)
            box.addView(SeekBar(this).apply {
                this.max = max; progress = v
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { l.text = t + fmt(p); f(p) }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            })
        }
        sw("두쪽 보기", two) { val was = two; two = it; if (was && !it) needReload = true else needInject = true }
        sw("오른쪽→왼쪽 (만화책 방식)", rtl) { rtl = it; needInject = true }
        sw("첫 장 단독(표지)", cover) { cover = it; needReload = true }
        sw("광고 도메인 차단", adblock) { adblock = it }
        bar("페이지 스크롤 부드러움 ", 20, smoothMs / 100, { if (it == 0) "즉시" else "${it * 100}ms" }) { smoothMs = it * 100 }
        bar("자동 스크롤 속도 ", 58, (autoSpeed - 20) / 10, { "${20 + it * 10}px/s" }) { autoSpeed = 20 + it * 10 }
        AlertDialog.Builder(this).setTitle("설정").setView(box).setPositiveButton("확인") { _, _ ->
            updateTwoBtn()
            if (needReload) web.reload() else if (needInject) injectScript()
        }.show()
    }

    companion object {
        private const val SCRIPT = """(function(){
window.__cvOpts={two:__TWO__,rtl:__RTL__,cover:__COVER__};
if(window.__cvTimer)return;
var cnt=0;
function big(i){var w=i.naturalWidth,h=i.naturalHeight;
 if(!w||!h||w<400||h<300||w/h>3)return false;
 return i.getBoundingClientRect().width>=window.innerWidth*0.4;}
function tick(){
 var o=window.__cvOpts,imgs=[].slice.call(document.images);
 imgs.forEach(function(i){
  if(i.__cvi===undefined)i.__cvi=cnt++;
  var d=i.getAttribute('data-src')||i.getAttribute('data-original')||i.getAttribute('data-lazy-src');
  if(d&&!i.__cvl&&(!i.src||i.src.indexOf('data:')==0||i.naturalWidth<2)&&i.getBoundingClientRect().top<window.innerHeight*3){
   i.__cvl=1;i.loading='eager';i.src=d;}
 });
 if(!o.two)return;
 var c=imgs.filter(function(i){return !i.closest('#cv-grid')&&big(i)});
 var g=document.getElementById('cv-grid');
 if(!g&&!c.length)return;
 if(!g){g=document.createElement('div');g.id='cv-grid';
  g.style.cssText='display:grid;grid-template-columns:1fr 1fr;gap:0;width:100%;align-items:start';
  c[0].parentNode.insertBefore(g,c[0]);}
 g.style.direction=o.rtl?'rtl':'ltr';
 c.forEach(function(i){
  var ref=null,k=g.children;
  for(var n=0;n<k.length;n++){if(k[n].__cvi>i.__cvi){ref=k[n];break}}
  g.insertBefore(i,ref);
  i.style.cssText='width:100%;height:auto;max-width:none;display:block;margin:0';});
 for(var n=0;n<g.children.length;n++){var e=g.children[n];
  var wide=e.naturalWidth>e.naturalHeight*1.1,cv=(n==0&&o.cover);
  e.style.gridColumn=(wide||cv)?'1 / 3':'auto';
  e.style.width=(cv&&!wide)?'50%':'100%';e.style.margin=(cv&&!wide)?'0 auto':'0';}
}
window.__cvTimer=setInterval(tick,800);tick();
})()"""
    }
}
