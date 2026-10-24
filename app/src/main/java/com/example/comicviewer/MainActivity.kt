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
    private var gap get() = prefs.getInt("gap", 8); set(v) = prefs.edit().putInt("gap", v).apply() // 두쪽 중간 여백(px)
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
        web.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN && auto) applyAuto(false); false }

        findViewById<View>(R.id.goBtn).setOnClickListener { go(urlInput.text.toString()) }
        urlInput.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_GO) { go(urlInput.text.toString()); true } else false }
        findViewById<View>(R.id.backBtn).setOnClickListener { if (web.canGoBack()) web.goBack() }
        findViewById<View>(R.id.bookmarkBtn).setOnClickListener { showBookmarks() }
        findViewById<View>(R.id.settingsBtn).setOnClickListener { showSettings() }
        findViewById<View>(R.id.menuBtn).setOnClickListener { topBar.visibility = if (topBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        twoBtn.setOnClickListener { applyTwo(!two) }
        autoBtn.setOnClickListener { applyAuto(!auto) }
        findViewById<View>(R.id.downBtn).setOnClickListener { pageScroll(1) }
        findViewById<View>(R.id.upBtn).setOnClickListener { pageScroll(-1) }
        updateTwoBtn()

        val shared = intent?.dataString ?: intent?.getStringExtra(android.content.Intent.EXTRA_TEXT)
        go(shared ?: prefs.getString("last", null) ?: "https://www.google.com")
    }

    override fun onDestroy() { applyAuto(false); web.destroy(); super.onDestroy() }
    override fun onPause() { applyAuto(false); super.onPause() }
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
    private fun applyTwo(v: Boolean) {
        val was = two; two = v; updateTwoBtn()
        if (was && !v) web.reload() else injectScript()
    }

    private fun injectScript() {
        web.evaluateJavascript(SCRIPT.replace("__TWO__", "$two").replace("__RTL__", "$rtl").replace("__GAP__", "$gap"), null)
    }

    // ---- 스크롤 ----
    private fun pageScroll(dir: Int) {
        val dist = (web.height * (if (two) 1.0f else 0.95f)).toInt() * dir
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

    private fun applyAuto(v: Boolean) {
        if (auto == v) return
        auto = v; autoBtn.text = if (v) "정지" else "자동"
        if (v) { pageAnim?.cancel(); frame.last = 0L; frame.acc = 0f; Choreographer.getInstance().postFrameCallback(frame) }
        else Choreographer.getInstance().removeFrameCallback(frame)
    }

    // ---- 북마크 (SharedPreferences에 JSON 저장) ----
    private fun loadBookmarks(): MutableList<Pair<String, String>> {
        val a = org.json.JSONArray(prefs.getString("bookmarks", "[]"))
        return MutableList(a.length()) { a.getJSONObject(it).let { o -> o.getString("t") to o.getString("u") } }
    }
    private fun saveBookmarks(l: List<Pair<String, String>>) {
        val a = org.json.JSONArray()
        l.forEach { a.put(org.json.JSONObject().put("t", it.first).put("u", it.second)) }
        prefs.edit().putString("bookmarks", a.toString()).apply()
    }
    private fun showBookmarks() {
        val list = loadBookmarks()
        val ad = ArrayAdapter(this, android.R.layout.simple_list_item_1, list.map { it.first }.toMutableList())
        val dlg = AlertDialog.Builder(this).setTitle("북마크 (길게 눌러 삭제)")
            .setAdapter(ad) { _, i -> go(list[i].second) }
            .setPositiveButton("현재 페이지 추가") { _, _ ->
                val u = web.url ?: return@setPositiveButton
                if (list.none { it.second == u }) {
                    list.add((web.title?.takeIf { it.isNotBlank() } ?: u) to u); saveBookmarks(list)
                    Toast.makeText(this, "북마크 추가", Toast.LENGTH_SHORT).show()
                } else Toast.makeText(this, "이미 있음", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("닫기", null).create()
        dlg.show()
        dlg.listView.setOnItemLongClickListener { _, _, i, _ ->
            AlertDialog.Builder(this).setMessage("삭제: ${list[i].first}").setPositiveButton("삭제") { _, _ ->
                list.removeAt(i); saveBookmarks(list); ad.remove(ad.getItem(i)); ad.notifyDataSetChanged()
            }.setNegativeButton("취소", null).show()
            true
        }
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
        bar("두쪽 중간 여백 ", 60, gap, { "${it}px" }) { gap = it; needInject = true }
        sw("광고 도메인 차단", adblock) { adblock = it }
        bar("페이지 스크롤 부드러움 ", 20, smoothMs / 100, { if (it == 0) "즉시" else "${it * 100}ms" }) { smoothMs = it * 100 }
        bar("자동 스크롤 속도 ", 58, (autoSpeed - 20) / 10, { "${20 + it * 10}px/s" }) { autoSpeed = 20 + it * 10 }
        AlertDialog.Builder(this).setTitle("설정").setView(box).setPositiveButton("확인") { _, _ ->
            updateTwoBtn()
            if (needReload) web.reload() else if (needInject) injectScript()
        }.show()
    }

    companion object {
        // 두쪽 보기: 연속 세로 스트립을 좌/우 두 패널로 표시. 왼쪽=현재 위치, 오른쪽=한 화면 아래.
        // 문서 스크롤(native)을 그대로 쓰고 스크롤 값에 맞춰 두 스트립을 translate → 한 페이지 스크롤 시 오른쪽 내용이 왼쪽으로 이동
        private const val SCRIPT = """(function(){
var o=window.__cvOpts={two:__TWO__,rtl:__RTL__,gap:__GAP__};
if(window.__cvTimer){window.__cvTick();return}
var cnt=0,root=null,strips=[],spacer=null;
function big(i){var w=i.naturalWidth,h=i.naturalHeight;
 if(!w||!h||w<400||h<300||w/h>3)return false;
 var r=i.getBoundingClientRect();
 return r.width==0||r.width>=window.innerWidth*0.4;}
function mk(t,id,c){var d=document.createElement(t);if(id)d.id=id;if(c)d.className=c;return d}
function build(){
 var st=mk('style','cv-style');
 st.textContent='body>*:not(#cv-root):not(#cv-spacer){display:none!important}html,body{margin:0!important;padding:0!important;background:#111!important}#cv-root{position:fixed;left:0;top:0;width:100%;height:100vh;z-index:2147483647;display:flex;background:#111;overflow:hidden;pointer-events:none}.cv-pane{flex:1;min-width:0;height:100%;overflow:hidden;position:relative}.cv-strip{width:100%;will-change:transform}.cv-strip img{width:100%;height:auto;display:block;max-width:none;margin:0}';
 document.head.appendChild(st);
 root=mk('div','cv-root');
 for(var k=0;k<2;k++){var p=mk('div',null,'cv-pane'),s=mk('div',null,'cv-strip');p.appendChild(s);root.appendChild(p);strips.push(s);}
 spacer=mk('div','cv-spacer');
 document.body.appendChild(root);document.body.appendChild(spacer);
}
function add(i){var src=i.currentSrc||i.src;
 strips.forEach(function(s){var a=new Image();a.src=src;a.__cvi=i.__cvi;
  var ref=null,k=s.children;for(var n=0;n<k.length;n++){if(k[n].__cvi>a.__cvi){ref=k[n];break}}
  s.insertBefore(a,ref);});}
function upd(){if(!root)return;
 var H=window.innerHeight,y=window.scrollY;
 root.style.flexDirection=o.rtl?'row-reverse':'row';root.style.gap=o.gap+'px';
 strips[0].style.transform='translate3d(0,'+(-y)+'px,0)';
 strips[1].style.transform='translate3d(0,'+(-(y+H))+'px,0)';
 spacer.style.height=Math.max(strips[0].offsetHeight,H)+'px';}
function tick(){
 var imgs=[].slice.call(document.images).filter(function(i){return !i.closest('#cv-root')});
 imgs.forEach(function(i){
  if(i.__cvi===undefined)i.__cvi=cnt++;
  var d=i.getAttribute('data-src')||i.getAttribute('data-original')||i.getAttribute('data-lazy-src');
  if(d&&!i.__cvl&&(!i.src||i.src.indexOf('data:')==0||i.naturalWidth<2)&&(o.two||i.getBoundingClientRect().top<window.innerHeight*3)){
   i.__cvl=1;i.loading='eager';i.src=d;}
  else if(o.two&&i.loading=='lazy')i.loading='eager';
 });
 if(!o.two)return;
 if(!root)build();
 imgs.forEach(function(i){if(i.__cva||!big(i))return;i.__cva=1;add(i);});
 upd();}
window.__cvTick=tick;
window.addEventListener('scroll',upd,{passive:true});window.addEventListener('resize',upd);
window.__cvTimer=setInterval(tick,500);tick();
})()"""
    }
}
