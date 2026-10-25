package com.example.comicviewer

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/** 웹툰 전용 브라우저 */
class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var web: WebView
    private lateinit var urlInput: EditText
    private lateinit var topBar: View
    private lateinit var autoBtn: Button
    private lateinit var twoBtn: Button
    private lateinit var nextBtn: Button
    private lateinit var track: View
    private lateinit var thumb: View
    private val handler = Handler(Looper.getMainLooper())
    private val density get() = resources.displayMetrics.density
    private var rootW = 0 // 실제 레이아웃 너비(px). 접힘/펼침 전환 시 설정값이 늦게 갱신되는 문제를 피하려고 이것을 기준으로 판단
    private val expanded get() = (if (rootW > 0) rootW / density else resources.configuration.screenWidthDp.toFloat()) >= 600f // 폴드 펼침(태블릿 폭)

    // ---- 설정 ----
    private var two get() = prefs.getBoolean("two", false); set(v) = prefs.edit().putBoolean("two", v).apply()
    private var rtl get() = prefs.getBoolean("rtl", false); set(v) = prefs.edit().putBoolean("rtl", v).apply()
    private var gap get() = prefs.getInt("gap", 8); set(v) = prefs.edit().putInt("gap", v).apply()
    private var adblock get() = prefs.getBoolean("adblock", true); set(v) = prefs.edit().putBoolean("adblock", v).apply()
    private var keepOn get() = prefs.getBoolean("keepOn", true); set(v) = prefs.edit().putBoolean("keepOn", v).apply()
    private var autoPageMode get() = prefs.getBoolean("autoPage", false); set(v) = prefs.edit().putBoolean("autoPage", v).apply() // 자동 방식2
    private var autoWait get() = prefs.getInt("autoWait", 30); set(v) = prefs.edit().putInt("autoWait", v).apply() // 방식2 대기(0.1초 단위)
    private var autoNext get() = prefs.getBoolean("autoNext", true); set(v) = prefs.edit().putBoolean("autoNext", v).apply()
    private var foldAutoTwo get() = prefs.getBoolean("foldTwo", false); set(v) = prefs.edit().putBoolean("foldTwo", v).apply()
    private var smoothMs get() = prefs.getInt("ms", 500); set(v) = prefs.edit().putInt("ms", v).apply()
    private var pageRatio get() = prefs.getInt("ratio", 95); set(v) = prefs.edit().putInt("ratio", v).apply()
    private var pageRatio2 get() = prefs.getInt("ratio2", 100); set(v) = prefs.edit().putInt("ratio2", v).apply() // 두쪽 보기 1회 이동 거리(%)
    private var quietUntil = 0L // 두쪽 보기 준비(스윕) 중에는 스크롤 이벤트로 저장/전체화면/다음화 판단 안 함
    private var btnSize get() = prefs.getInt("btnSize", 52); set(v) = prefs.edit().putInt("btnSize", v).apply() // 플로팅 버튼 크기(dp)
    private var holdSpeed get() = prefs.getInt("holdSpeed", 800); set(v) = prefs.edit().putInt("holdSpeed", v).apply() // ▼ 꾹 누름 연속 스크롤 속도(px/초)
    private var snapImg get() = prefs.getBoolean("snapImg", false); set(v) = prefs.edit().putBoolean("snapImg", v).apply() // 검은 영역(이미지 경계) 감지 시 멈춤
    private var blackH get() = prefs.getInt("blackH", 60); set(v) = prefs.edit().putInt("blackH", v).apply() // 검은 영역 감지 높이(dp)
    private var btnAlpha get() = prefs.getInt("alpha", 70); set(v) = prefs.edit().putInt("alpha", v).apply()
    private var autoSpeed get() = prefs.getInt("speed", 120); set(v) = prefs.edit().putInt("speed", v).apply()
    private var imgScale: Int // 이미지 너비(%): 접힘/펼침 상태별로 따로 저장
        get() = prefs.getInt(if (expanded) "scale_e" else "scale_c", 100)
        set(v) = prefs.edit().putInt(if (expanded) "scale_e" else "scale_c", v).apply()

    private var pageAnim: ValueAnimator? = null
    private var auto = false
    private var resumeAuto = false

    private val adHosts = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google",
        "adnxs.com", "taboola.com", "outbrain.com", "criteo.", "adsterra", "popads", "popcash",
        "exoclick", "propellerads", "adsystem", "moatads", "2mdn.net", "adform.net", "adcash",
    )

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // 카메라 컷아웃(펀치홀/노치) 영역까지 화면을 사용 — 전체화면 시 상단 검은 띠 제거
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        }
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("cv", MODE_PRIVATE)
        web = findViewById(R.id.web); urlInput = findViewById(R.id.urlInput)
        topBar = findViewById(R.id.topBar); autoBtn = findViewById(R.id.autoBtn); twoBtn = findViewById(R.id.twoBtn)
        nextBtn = findViewById(R.id.nextBtn)
        applyKeepOn()
        initScrollBar()
        findViewById<View>(R.id.topBar).addOnLayoutChangeListener { v, _, t, _, b, _, _, _, _ ->
            // 메뉴가 표시 중이면 웹페이지 상단 여백을 메뉴 높이에 맞춤(초기 표시/높이 변화 대응)
            if (!full && menuAnim?.isRunning != true && v.visibility == View.VISIBLE) {
                val p2 = web.layoutParams as FrameLayout.LayoutParams
                if (p2.topMargin != b - t) { p2.topMargin = b - t; web.layoutParams = p2 }
            }
        }
        web.isVerticalScrollBarEnabled = false; web.isHorizontalScrollBarEnabled = false
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
            setSupportMultipleWindows(false); javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, u: String, f: Bitmap?) {
                handler.removeCallbacksAndMessages(null)
                curKey = null; saving = false; atBottomShown = false; nextBtn.visibility = View.GONE
                urlInput.setText(u); prefs.edit().putString("last", u).apply()
            }
            override fun onPageFinished(v: WebView, u: String) {
                injectScript()
                handler.postDelayed({ checkTitle() }, 300)
                handler.postDelayed({ checkTitle() }, 2500)
                if (resumeAuto) { resumeAuto = false; handler.postDelayed({ applyAuto(true) }, 2000) }
                else if (auto && autoPageMode) handler.postDelayed(pageAutoRun, 1500) // 페이지 이동으로 지워진 방식2 예약 복구
            }
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
        web.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { touching = true; if (auto) applyAuto(false) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { touching = false; touchEndAt = System.currentTimeMillis() }
            }
            false
        }
        web.setOnScrollChangeListener { _, _, y, _, oy -> onScrolled(y, y - oy) }

        findViewById<View>(R.id.goBtn).setOnClickListener { go(urlInput.text.toString()) }
        urlInput.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_GO) { go(urlInput.text.toString()); true } else false }
        findViewById<View>(R.id.backBtn).setOnClickListener { if (web.canGoBack()) web.goBack() }
        findViewById<View>(R.id.bookmarkBtn).setOnClickListener { showBookmarks() }
        findViewById<View>(R.id.historyBtn).setOnClickListener { showHistory() }
        findViewById<View>(R.id.settingsBtn).setOnClickListener { showSettings() }
        twoBtn.setOnClickListener { applyTwo(!two) }
        autoBtn.setOnClickListener { applyAuto(!auto) }
        nextBtn.setOnClickListener { goNext() }
        findViewById<View>(R.id.editBtn).setOnClickListener {
            editMode = !editMode; applyEditVisual()
            if (editMode) Toast.makeText(this, "우측 버튼을 드래그해 옮기세요. 편집 버튼을 다시 누르면 종료", Toast.LENGTH_LONG).show()
        }
        findViewById<View>(R.id.downBtn).setOnClickListener { pageScroll(1) }
        findViewById<View>(R.id.topBtn).setOnClickListener { pageAnim?.cancel(); applyAuto(false); web.scrollTo(0, 0) }
        updateTwoBtn(); applyBtnAlpha(); applyScale(); applyBtnSize(); initDraggableButtons(); applyEditVisual()
        if (foldAutoTwo) { two = expanded; updateTwoBtn() }
        findViewById<View>(R.id.root).addOnLayoutChangeListener { _, l, _, r, _, ol, _, orr, _ ->
            val w = r - l
            if (w > 0 && w != orr - ol) { val first = rootW == 0; rootW = w; if (!first) onWidthChanged() else { applyScale(); if (foldAutoTwo && two != expanded) { two = expanded; updateTwoBtn() } } }
        }

        val shared = intent?.dataString ?: intent?.getStringExtra(android.content.Intent.EXTRA_TEXT)
        go(shared ?: prefs.getString("last", null) ?: "https://www.google.com")
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c) // 실제 처리는 루트 레이아웃 너비 변경 리스너(onWidthChanged)에서
    }

    /** 접힘↔펼침 등으로 창 너비가 바뀐 뒤(레이아웃 완료 후) 호출 */
    private fun onWidthChanged() {
        applyScale()
        if (foldAutoTwo) {
            val want = expanded
            if (want != two) { two = want; updateTwoBtn(); if (!want) { web.reload(); return } }
        }
        injectScript()
    }

    override fun onDestroy() { applyAuto(false); stopHold(); handler.removeCallbacksAndMessages(null); handler2.removeCallbacksAndMessages(null); web.destroy(); super.onDestroy() }
    override fun onPause() { applyAuto(false); stopHold(); savePosNow(); super.onPause() }
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

    private fun applyKeepOn() {
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    // ---- 플로팅 버튼: 꾹 눌러 이동, 위치 저장 ----
    private fun floatBtns(): List<View> = listOf(R.id.autoBtn, R.id.downBtn).map { findViewById<View>(it) }

    private fun applyBtnSize() {
        val px = (btnSize * density).toInt()
        floatBtns().forEach { v -> val lp = v.layoutParams; if (lp.width != px || lp.height != px) { lp.width = px; lp.height = px; v.layoutParams = lp } }
        findViewById<View>(R.id.btnLayer).post { placeBtns() }
    }

    private fun placeBtns() {
        val layer = findViewById<View>(R.id.btnLayer)
        val w = layer.width.toFloat(); val h = layer.height.toFloat()
        if (w <= 0f) return
        val size = btnSize * density
        val startY = (h - floatBtns().size * (size + 2 * density)) / 2f
        floatBtns().forEachIndexed { i, v ->
            val k = resources.getResourceEntryName(v.id)
            val fx = prefs.getFloat("bx_$k", -1f); val fy = prefs.getFloat("by_$k", -1f)
            val x = if (fx >= 0f) fx * w else w - size - 40 * density
            val y = if (fy >= 0f) fy * h else startY + i * (size + 2 * density)
            v.x = x.coerceIn(0f, (w - size).coerceAtLeast(0f)); v.y = y.coerceIn(0f, (h - size).coerceAtLeast(0f))
        }
    }

    // ---- 버튼 편집 모드: 상단 메뉴 '편집'을 켜면 우측 버튼을 바로 드래그해 이동 ----
    private var editMode = false
    private fun applyEditVisual() { floatBtns().forEach { it.setBackgroundColor(if (editMode) 0x66FFFFFF else 0) }; findViewById<Button>(R.id.editBtn).text = if (editMode) "편집●" else "편집" }

    // ---- ▼ 꾹 누르면 누르는 동안 연속 스크롤(속도 설정) ----
    private var holding = false
    private val holdFrame = object : Choreographer.FrameCallback {
        var last = 0L; var acc = 0f
        override fun doFrame(t: Long) {
            if (!holding) return
            if (last != 0L) {
                acc += holdSpeed * (t - last) / 1e9f
                val d = acc.toInt()
                if (d != 0) { web.scrollBy(0, d); acc -= d }
                if (atBottom()) { stopHold(); return }
            }
            last = t
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private fun startHold() { holding = true; applyAuto(false); pageAnim?.cancel(); setFull(true); holdFrame.last = 0L; holdFrame.acc = 0f; Choreographer.getInstance().postFrameCallback(holdFrame) }
    private fun stopHold() { holding = false; Choreographer.getInstance().removeFrameCallback(holdFrame) }

    @SuppressLint("ClickableViewAccessibility")
    private fun initDraggableButtons() {
        val layer = findViewById<View>(R.id.btnLayer)
        layer.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob -> if (r - l != orr - ol || b - t != ob - ot) placeBtns() }
        layer.post { placeBtns() }
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        floatBtns().forEach { v ->
            var offX = 0f; var offY = 0f; var downX = 0f; var downY = 0f
            var held = false; var cancelled = false
            val holdStart = Runnable { held = true; v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); startHold() }
            v.setOnTouchListener { view, e ->
                if (editMode) { // 편집 모드: 즉시 드래그로 이동, 놓으면 위치 저장 (클릭 동작 없음)
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { offX = view.x - e.rawX; offY = view.y - e.rawY; view.scaleX = 1.15f; view.scaleY = 1.15f }
                        MotionEvent.ACTION_MOVE -> {
                            view.parent.requestDisallowInterceptTouchEvent(true)
                            val pw = layer.width.toFloat(); val ph = layer.height.toFloat()
                            view.x = (e.rawX + offX).coerceIn(0f, pw - view.width); view.y = (e.rawY + offY).coerceIn(0f, ph - view.height)
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            view.scaleX = 1f; view.scaleY = 1f
                            val k = resources.getResourceEntryName(view.id)
                            prefs.edit().putFloat("bx_$k", view.x / layer.width).putFloat("by_$k", view.y / layer.height).apply()
                        }
                    }
                    return@setOnTouchListener true
                }
                if (view.id == R.id.downBtn) { // 탭=1페이지 이동, 꾹=연속 스크롤
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> { held = false; cancelled = false; downX = e.rawX; downY = e.rawY; handler2.postDelayed(holdStart, 400) }
                        MotionEvent.ACTION_MOVE -> if (!held && (abs(e.rawX - downX) > slop * 3 || abs(e.rawY - downY) > slop * 3)) { cancelled = true; handler2.removeCallbacks(holdStart) }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            handler2.removeCallbacks(holdStart)
                            if (held) { stopHold(); held = false }
                            else if (e.actionMasked == MotionEvent.ACTION_UP && !cancelled) view.performClick()
                        }
                    }
                } else if (e.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                true
            }
        }
    }
    private val handler2 = Handler(Looper.getMainLooper()) // onPageStarted의 handler 정리와 분리
    private fun resetBtnPositions() {
        val ed = prefs.edit(); prefs.all.keys.filter { it.startsWith("bx_") || it.startsWith("by_") }.forEach { ed.remove(it) }; ed.apply()
        placeBtns()
    }

    /** 이미지 너비: 웹뷰 좌우에 검정 여백을 주어 페이지를 좁게(작게) 표시 */
    private fun applyScale() {
        val rw = if (rootW > 0) rootW else resources.displayMetrics.widthPixels
        val m = (rw * (100 - imgScale) / 200f).toInt()
        val lp = web.layoutParams as FrameLayout.LayoutParams
        if (lp.leftMargin != m || lp.rightMargin != m) { lp.leftMargin = m; lp.rightMargin = m; web.layoutParams = lp }
    }
    private fun applyBtnAlpha() { floatBtns().forEach { it.alpha = btnAlpha / 100f } }
    private fun updateTwoBtn() { twoBtn.text = if (two) "두쪽●" else "두쪽" }
    private fun applyTwo(v: Boolean) {
        val was = two; two = v; updateTwoBtn()
        if (was && !v) web.reload() else injectScript()
    }
    private fun injectScript() {
        if (two) quietUntil = System.currentTimeMillis() + 9000
        web.evaluateJavascript(
            SCRIPT.replace("__TWO__", "$two").replace("__RTL__", "$rtl").replace("__GAP__", "$gap"), null
        )
    }

    // ---- 전체화면(아래로 스크롤=숨김, 위로 스크롤=표시) ----
    private var full = false
    private var menuAnim: ValueAnimator? = null
    private var fullLockUntil = 0L
    private var dyAcc = 0
    private var touching = false
    private var touchEndAt = 0L
    /** 손가락 스크롤(드래그/플링 관성 포함)인지 — 레이아웃 변화·코드 스크롤로 인한 가짜 '위로 스크롤'을 걸러냄 */
    private fun userScrolling() = touching || System.currentTimeMillis() - touchEndAt < 1500
    /** 창 포커스를 되찾을 때(다이얼로그/알림창 등 이후) 시스템 바가 다시 나타나 있으면 숨김 상태를 복원 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && full) applySystemBars(true)
    }
    private fun applySystemBars(hide: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                if (hide) { it.hide(WindowInsets.Type.systemBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
                else it.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (hide) (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE) else View.SYSTEM_UI_FLAG_VISIBLE
        }
    }
    private fun setFull(v: Boolean) {
        if (v && editMode) return // 버튼 편집 중에는 메뉴를 숨기지 않음
        if (full == v) return
        full = v; fullLockUntil = System.currentTimeMillis() + 500; dyAcc = 0
        // 메뉴 표시/숨김 애니메이션(300ms, One UI 유사 곡선): 메뉴가 나오면 웹페이지가 아래로 밀리고, 숨기면 위로 올라옴
        if (topBar.height == 0) {
            topBar.measure(View.MeasureSpec.makeMeasureSpec(if (rootW > 0) rootW else resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        }
        val h = (if (topBar.height > 0) topBar.height else topBar.measuredHeight).coerceAtLeast(1)
        val lp = web.layoutParams as FrameLayout.LayoutParams
        val from = lp.topMargin; val to = if (v) 0 else h
        menuAnim?.cancel()
        topBar.visibility = View.VISIBLE
        menuAnim = ValueAnimator.ofInt(from, to).apply {
            duration = 300; interpolator = android.view.animation.PathInterpolator(0.33f, 0f, 0.67f, 1f)
            addUpdateListener {
                val m = it.animatedValue as Int
                val p2 = web.layoutParams as FrameLayout.LayoutParams
                p2.topMargin = m; web.layoutParams = p2
                topBar.translationY = (m - h).toFloat(); topBar.alpha = (m.toFloat() / h).coerceIn(0f, 1f)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) { if (!cancelled && full) topBar.visibility = View.GONE }
            })
            start()
        }
        applySystemBars(v)
    }

    private var atBottomShown = false
    private fun atBottom() = web.contentHeight > 0 && web.scrollY > 0 &&
        web.scrollY + web.height >= web.contentHeight * density - 80

    // ---- 스크롤바: 오른쪽 가장자리를 터치/드래그해 원하는 위치로 이동 ----
    @SuppressLint("ClickableViewAccessibility")
    private fun initScrollBar() {
        track = findViewById(R.id.scrollTrack); thumb = findViewById(R.id.scrollThumb)
        thumb.background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 2 * density; setColor(0xFF555555.toInt()) }
        var grab = 0f; var dragging = false
        track.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 손잡이를 직접 잡았을 때만 드래그 시작 (트랙의 다른 곳 터치는 무시)
                    val top = thumb.translationY
                    if (thumb.visibility == View.VISIBLE && e.y >= top - 28 * density && e.y <= top + thumb.height + 28 * density) {
                        dragging = true; grab = e.y - top
                        applyAuto(false); pageAnim?.cancel()
                        track.parent.requestDisallowInterceptTouchEvent(true)
                        true
                    } else false
                }
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    val range = web.contentHeight * density - web.height
                    val avail = (track.height - thumb.height).coerceAtLeast(1)
                    val r = ((e.y - grab) / avail).coerceIn(0f, 1f)
                    if (range > 0) web.scrollTo(0, (r * range).toInt())
                    updateThumb(); true
                } else false
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { val was = dragging; dragging = false; was }
                else -> dragging
            }
        }
        val tick = object : Runnable { override fun run() { updateThumb(); checkTitle(); track.postDelayed(this, 1000) } }
        track.postDelayed(tick, 1000)
    }
    private fun updateThumb() {
        val range = web.contentHeight * density - web.height
        if (range <= 0) { thumb.visibility = View.GONE; return }
        thumb.visibility = View.VISIBLE
        val avail = (track.height - thumb.height).coerceAtLeast(0)
        thumb.translationY = (web.scrollY / range).coerceIn(0f, 1f) * avail
    }

    private val saveRun = Runnable { savePosNow() }
    private fun onScrolled(y: Int, dy: Int) {
        updateThumb()
        if (System.currentTimeMillis() < quietUntil && !userScrolling()) return // 두쪽 준비 중 코드 스크롤만 무시(손가락 스크롤은 처리)
        if (System.currentTimeMillis() > fullLockUntil) { // 아래로 스크롤=메뉴 숨김, 위로 스크롤(또는 맨 위)=메뉴 표시
            dyAcc = if ((dyAcc > 0) == (dy > 0)) dyAcc + dy else dy
            if (dyAcc > 40) setFull(true) else if (userScrolling() && (dyAcc < -60 || y < 20)) setFull(false)
        }
        val b = atBottom()
        if (b != atBottomShown) { atBottomShown = b; nextBtn.visibility = if (b) View.VISIBLE else View.GONE
            if (b && auto && autoNext) { applyAuto(false); resumeAuto = true; handler.postDelayed({ goNext() }, 1200) } }
        if (saving && curKey != null) { handler.removeCallbacks(saveRun); handler.postDelayed(saveRun, 800) }
    }

    // ---- 다음화 ----
    private fun goNext() {
        nextBtn.visibility = View.GONE
        web.evaluateJavascript("window.__cvNext?window.__cvNext(true):''") { r ->
            if (r == null || r == "\"\"" || r == "null") { resumeAuto = false; Toast.makeText(this, "다음화 링크를 찾지 못했습니다", Toast.LENGTH_SHORT).show() }
        }
    }

    // ---- 스크롤 ----
    private fun pageScroll(dir: Int) {
        setFull(dir > 0)
        if (dir > 0 && autoNext && atBottom()) { goNext(); return }
        val dist = (web.height * (if (two) pageRatio2 else pageRatio) / 100f).toInt() * dir
        animateScroll(dist)
    }

    /** 이동 방향 쪽 화면 가장자리의 띠(높이 blackH dp)가 전부 검은색인지 샘플링 (이미지 사이 검은 경계 감지) */
    private var sampleBmp: Bitmap? = null
    private fun nonBlackRatio(top: Boolean): Float {
        val w = web.width; val h = web.height
        if (w <= 0 || h <= 0) return 1f
        val scale = 0.2f
        val sh = (blackH * density).toInt().coerceIn(8, h)
        val bw = (w * scale).toInt().coerceAtLeast(8); val bh = (sh * scale).toInt().coerceAtLeast(2)
        var bmp = sampleBmp
        if (bmp == null || bmp.width != bw || bmp.height != bh) { bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888); sampleBmp = bmp }
        bmp!!.eraseColor(android.graphics.Color.WHITE) // 그리기에 실패하면 흰색 그대로 → '검정 아님'으로 안전하게 처리
        val c = android.graphics.Canvas(bmp)
        c.scale(scale, scale)
        c.translate(0f, if (top) 0f else -(h - sh).toFloat())
        web.draw(c)
        val px = IntArray(bw * bh); bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
        var non = 0
        for (p in px) if (((p shr 16) and 255) >= 30 || ((p shr 8) and 255) >= 30 || (p and 255) >= 30) non++
        return non.toFloat() / px.size
    }
    private fun stripIsBlack(top: Boolean) = nonBlackRatio(top) <= 0.01f

    private fun animateScroll(dist: Int) {
        pageAnim?.cancel()
        if (smoothMs == 0) { web.scrollBy(0, dist); return }
        var done = 0
        val detect = snapImg
        var seenNonBlack = detect && !stripIsBlack(dist < 0) // 시작 위치가 이미 검은 경계면, 경계를 벗어난 뒤부터 감지
        var lastSample = 0L
        pageAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = smoothMs.toLong(); interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                val target = (anim.animatedValue as Float * dist).toInt()
                web.scrollBy(0, target - done); done = target
                val now = System.currentTimeMillis()
                if (detect && now - lastSample >= 16) {
                    lastSample = now
                    if (!stripIsBlack(dist < 0)) seenNonBlack = true
                    else if (seenNonBlack && anim.animatedFraction > 0.03f) anim.cancel() // 검은 경계가 일정 높이 이상 보이면 그 위 이미지에서 멈춤
                }
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

    /** 자동 방식2: 1페이지 이동 → 설정한 시간 대기 → 반복 */
    private val pageAutoRun = object : Runnable {
        override fun run() {
            if (!auto) return
            if (atBottom()) {
                applyAuto(false)
                if (autoNext) { resumeAuto = true; goNext() }
                return
            }
            pageScroll(1)
            handler.postDelayed(this, autoWait * 100L + smoothMs)
        }
    }

    private fun applyAuto(v: Boolean) {
        if (auto == v) return
        auto = v; autoBtn.text = if (v) "stop" else "auto"
        if (v) {
            pageAnim?.cancel()
            if (autoPageMode) handler.post(pageAutoRun)
            else { frame.last = 0L; frame.acc = 0f; Choreographer.getInstance().postFrameCallback(frame) }
        } else {
            Choreographer.getInstance().removeFrameCallback(frame)
            handler.removeCallbacks(pageAutoRun)
        }
    }

    // ================= 웹툰 기록 / 이어보기 =================
    private var curKey: String? = null
    private var saving = false

    private fun norm(s: String) = s.lowercase().replace(Regex("[\\s\\p{Punct}·]"), "")

    /** 탭 제목 → (웹툰명, 화수). 예: "웹툰명 12화 - 사이트" */
    private fun parseTitle(t: String): Pair<String, Int>? {
        val m = Regex("(\\d+)\\s*(화|회|편|話|话)").find(t)
            ?: Regex("(?i)(?:ep\\.?|episode|chapter|ch\\.?)\\s*(\\d+)").find(t) ?: return null
        val ep = m.groupValues[1].toIntOrNull() ?: return null
        val sep = Regex("\\s[-|·–:]\\s")
        var before = t.substring(0, m.range.first).trim().trimEnd('제', ' ', '-', '|', ':', '·', '–')
        before = before.split(sep).lastOrNull()?.trim() ?: ""
        var name = before
        if (name.isEmpty()) name = t.substring(m.range.last + 1).trim(' ', '-', '|', ':', '·', '–').split(sep).firstOrNull()?.trim() ?: ""
        if (name.isEmpty()) return null
        return name to ep
    }

    private class Series(var name: String, val key: String, var last: Int, var max: Int, val eps: MutableSet<Int>, var url: String, var time: Long)

    private fun loadSeries(): MutableList<Series> {
        val a = JSONArray(prefs.getString("series", "[]"))
        return MutableList(a.length()) {
            val o = a.getJSONObject(it)
            val e = o.getJSONArray("eps")
            Series(o.getString("n"), o.getString("k"), o.getInt("last"), o.getInt("max"),
                MutableList(e.length()) { i -> e.getInt(i) }.toMutableSet(), o.getString("u"), o.getLong("t"))
        }
    }
    private fun saveSeries(l: List<Series>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("n", it.name).put("k", it.key).put("last", it.last).put("max", it.max)
            .put("eps", JSONArray(it.eps.sorted())).put("u", it.url).put("t", it.time)) }
        prefs.edit().putString("series", a.toString()).apply()
    }

    private fun checkTitle() {
        val title = web.title ?: return
        val (name, ep) = parseTitle(title) ?: return
        val key = norm(name) + "|" + ep
        if (key == curKey) return
        curKey = key; saving = false // 새 화로 바뀌면(SPA 포함) 복원이 끝나기 전엔 저장하지 않음
        val l = loadSeries()
        val s = l.firstOrNull { it.key == norm(name) } ?: Series(name, norm(name), ep, ep, mutableSetOf(), "", 0L).also { l.add(it) }
        s.name = name; s.last = ep; s.max = maxOf(s.max, ep); s.eps.add(ep); s.url = web.url ?: s.url; s.time = System.currentTimeMillis()
        saveSeries(l)
        restorePos(key)
    }

    private class Pos(val key: String, val y: Int, val ratio: Float, val exp: Boolean)
    private fun loadPos(): MutableList<Pos> {
        val a = JSONArray(prefs.getString("pos", "[]"))
        return MutableList(a.length()) { val o = a.getJSONObject(it); Pos(o.getString("k"), o.getInt("y"), o.getDouble("r").toFloat(), o.getBoolean("e")) }
    }
    private fun savePosNow() {
        val k = curKey ?: return
        if (!saving || web.scrollY <= 0) return
        val l = loadPos(); l.removeAll { it.key == k }
        val h = (web.contentHeight * density).coerceAtLeast(1f)
        l.add(Pos(k, web.scrollY, web.scrollY / h, expanded))
        while (l.size > 300) l.removeAt(0)
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("k", it.key).put("y", it.y).put("r", it.ratio.toDouble()).put("e", it.exp)) }
        prefs.edit().putString("pos", a.toString()).apply()
    }
    private fun restorePos(key: String) {
        val p = loadPos().firstOrNull { it.key == key }
        if (p == null || p.y < 50) { saving = true; return }
        var tries = 0; var lastH = -1; var stable = 0
        val r = object : Runnable {
            override fun run() {
                if (curKey != key) return
                val h = (web.contentHeight * density).toInt()
                stable = if (h == lastH) stable + 1 else 0; lastH = h
                val target = if (p.exp == expanded) p.y else (p.ratio * h).toInt()
                val ready = h >= target + web.height * 0.5 && (p.exp == expanded || stable >= 3)
                if (ready || ++tries > 30) {
                    if (ready) web.scrollTo(0, target)
                    saving = true
                    return
                }
                handler.postDelayed(this, 400)
            }
        }
        handler.postDelayed(r, 400)
    }

    private fun showHistory() {
        val l = loadSeries().sortedByDescending { it.time }.toMutableList()
        if (l.isEmpty()) { Toast.makeText(this, "기록 없음 (제목에 'N화'가 있는 페이지만 기록)", Toast.LENGTH_LONG).show(); return }
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.KOREA)
        fun label(s: Series) = "${s.name}\n마지막 ${s.last}화 · 최대 ${s.max}화 · 읽은 화 ${s.eps.size}개 · ${fmt.format(java.util.Date(s.time))}"
        val ad = ArrayAdapter(this, android.R.layout.simple_list_item_1, l.map { label(it) }.toMutableList())
        val dlg = AlertDialog.Builder(this).setTitle("최근 기록 (길게 눌러 삭제)")
            .setAdapter(ad) { _, i -> if (l[i].url.isNotEmpty()) go(l[i].url) }
            .setNegativeButton("닫기", null).create()
        dlg.show()
        dlg.listView.setOnItemLongClickListener { _, _, i, _ ->
            AlertDialog.Builder(this).setMessage("기록 삭제: ${l[i].name}").setPositiveButton("삭제") { _, _ ->
                val all = loadSeries(); all.removeAll { it.key == l[i].key }; saveSeries(all)
                l.removeAt(i); ad.clear(); ad.addAll(l.map { label(it) }); ad.notifyDataSetChanged()
            }.setNegativeButton("취소", null).show()
            true
        }
    }

    // ---- 북마크 ----
    private fun loadBookmarks(): MutableList<Pair<String, String>> {
        val a = JSONArray(prefs.getString("bookmarks", "[]"))
        return MutableList(a.length()) { a.getJSONObject(it).let { o -> o.getString("t") to o.getString("u") } }
    }
    private fun saveBookmarks(l: List<Pair<String, String>>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("t", it.first).put("u", it.second)) }
        prefs.edit().putString("bookmarks", a.toString()).apply()
    }
    private fun showBookmarks() {
        val list = loadBookmarks()
        val ad = ArrayAdapter(this, android.R.layout.simple_list_item_1, list.map { it.first }.toMutableList())
        val dlg = AlertDialog.Builder(this).setTitle("북마크 (길게 눌러 삭제)")
            .setAdapter(ad) { _, i -> go(list[i].second) }
            .setPositiveButton("현재 페이지 추가") { _, _ ->
                val u = web.url ?: return@setPositiveButton
                val t = web.title?.takeIf { it.isNotBlank() } ?: u
                val parsed = parseTitle(t)
                val label = parsed?.let { (n, e) -> "$n ${e}화" } ?: t // 예: "웹툰명 12화"
                // 같은 웹툰(이름 동일)이면 덮어쓰기. 주소가 바뀌어도 이름으로 식별
                val key = parsed?.let { norm(it.first) }
                val idx = list.indexOfFirst { b -> b.second == u || (key != null && parseTitle(b.first)?.let { norm(it.first) } == key) }
                if (idx >= 0) { list[idx] = label to u; Toast.makeText(this, "북마크 덮어쓰기: $label", Toast.LENGTH_SHORT).show() }
                else { list.add(label to u); Toast.makeText(this, "북마크 추가: $label", Toast.LENGTH_SHORT).show() }
                saveBookmarks(list)
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

    // ---- 설정 ----
    private fun showSettings() {
        val pad = (16 * density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        var needInject = false; var needReload = false
        fun head(t: String) = box.addView(TextView(this).apply { text = "\n$t"; setTypeface(null, android.graphics.Typeface.BOLD) })
        fun sw(t: String, v: Boolean, f: (Boolean) -> Unit) = box.addView(Switch(this).apply { text = t; isChecked = v; setOnCheckedChangeListener { _, c -> f(c) } })
        fun bar(t: String, min: Int, max: Int, v: Int, fmt: (Int) -> String, f: (Int) -> Unit) {
            val l = TextView(this).apply { text = t + fmt(v) }
            box.addView(l)
            box.addView(SeekBar(this).apply {
                this.max = max - min; progress = v - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, u: Boolean) { l.text = t + fmt(p + min); f(p + min) }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            })
        }
        head("[보기]")
        sw("두쪽 보기", two) { val was = two; two = it; if (was && !it) needReload = true else needInject = true }
        sw("오른쪽→왼쪽 (오른쪽이 윗부분)", rtl) { rtl = it; needInject = true }
        bar("두쪽 중간 여백 ", 0, 60, gap, { "${it}px" }) { gap = it; needInject = true }
        bar("이미지 너비 [현재 화면: ${if (expanded) "펼침" else "접힘"}] ", 30, 100, imgScale, { "$it%" }) { imgScale = it; applyScale() }
        sw("펼침 화면에서 자동으로 두쪽 보기 (접으면 해제)", foldAutoTwo) { foldAutoTwo = it }
        head("[화면]")
        sw("화면 켜짐 유지", keepOn) { keepOn = it; applyKeepOn() }
        bar("우측 버튼 크기 ", 32, 96, btnSize, { "${it}dp" }) { btnSize = it; applyBtnSize() }
        bar("우측 버튼 불투명도 ", 20, 100, btnAlpha, { "$it%" }) { btnAlpha = it; applyBtnAlpha() }
        head("[▲▼ 버튼 / 볼륨키]")
        bar("1회 이동 거리 - 일반 보기 (화면 높이 대비) ", 10, 300, pageRatio, { "$it%" }) { pageRatio = it }
        bar("1회 이동 거리 - 두쪽 보기 (100%=한 칸 만큼) ", 10, 300, pageRatio2, { "$it%" }) { pageRatio2 = it }
        sw("이동 중 검은 경계(이미지 사이 검은색)가 보이면 그 위 이미지에서 멈춤", snapImg) { snapImg = it }
        bar("검은 영역 감지 높이(이 높이 이상 검게 보이면 멈춤) ", 10, 300, blackH, { "${it}dp" }) { blackH = it }
        box.addView(Button(this).apply {
            text = "검은 영역 감지 테스트 (현재 화면 위/아래 띠 확인)"
            setOnClickListener {
                val bot = nonBlackRatio(false); val top = nonBlackRatio(true)
                Toast.makeText(this@MainActivity, "아래 띠 비검정 ${(bot * 100).toInt()}% / 위 띠 비검정 ${(top * 100).toInt()}% (1% 이하=검정으로 판정)", Toast.LENGTH_LONG).show()
            }
        })
        bar("이동 소요 시간(길수록 천천히 부드럽게) ", 0, 2000, smoothMs, { if (it == 0) "즉시" else "${it}ms" }) { smoothMs = it / 100 * 100 }
        bar("▼ 꾹 누름 연속 스크롤 속도 ", 100, 5000, holdSpeed, { "${it}px/초" }) { holdSpeed = it / 50 * 50 }
        box.addView(Button(this).apply { text = "버튼 위치 초기화 (이동은 상단 메뉴 '편집')"; setOnClickListener { resetBtnPositions() } })
        head("[자동 버튼: 계속 흐르는 스크롤]")
        sw("자동 방식2: 1페이지 이동 후 대기를 반복 (끄면 연속 스크롤)", autoPageMode) { autoPageMode = it }
        bar("[방식1: 연속] 속도 ", 20, 2000, autoSpeed, { "${it}px/초" }) { autoSpeed = it / 10 * 10 }
        box.addView(TextView(this).apply { text = "[방식2] 1페이지 이동 후 대기 시간(초, 예: 2.3)" })
        box.addView(EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(java.util.Locale.US, "%.1f", autoWait / 10f))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) {
                    e?.toString()?.toFloatOrNull()?.let { autoWait = (it * 10).toInt().coerceIn(1, 3600) } // 0.1초~360초
                }
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            })
        })
        sw("끝에 도달하면 다음화로 자동 이동", autoNext) { autoNext = it }
        box.addView(Button(this).apply {
            text = "두쪽 보기 진단 (문제 시 결과를 알려주세요)"
            setOnClickListener {
                web.evaluateJavascript("window.__cvInfo?window.__cvInfo():'스크립트 미실행'") { r ->
                    AlertDialog.Builder(this@MainActivity).setTitle("진단")
                        .setMessage((r ?: "").trim('"').replace("\\u003C", "<")).setPositiveButton("확인", null).show()
                }
            }
        })
        head("[광고]")
        sw("광고 도메인 차단", adblock) { adblock = it }
        box.addView(Button(this).apply {
            text = "광고 제거 모드 시작 (박스 크기 조절)"
            setOnClickListener {
                (tag as? AlertDialog)?.dismiss()
                injectScript(); setFull(true)
                web.evaluateJavascript("window.__cvEx&&window.__cvEx(true)", null)
            }
        })
        val sv = ScrollView(this).apply { addView(box) }
        val dlg = AlertDialog.Builder(this).setTitle("설정 (v" + packageManager.getPackageInfo(packageName, 0).versionName + ")").setView(sv).setPositiveButton("확인") { _, _ ->
            updateTwoBtn()
            if (needReload) web.reload() else if (needInject) injectScript()
        }.create()
        (box.getChildAt(box.childCount - 1) as Button).tag = dlg
        dlg.show()
    }

    companion object {
        // 두쪽 보기: 연속 세로 스트립을 좌/우 두 패널로 표시(왼쪽=현재 위치, 오른쪽=한 화면 아래).
        // 문서 스크롤(native)에 맞춰 두 스트립을 translate. 광고 제외 규칙은 사이트(localStorage)별 저장.
        private const val SCRIPT = """(function(){
var o=window.__cvOpts=window.__cvOpts||{};o.two=__TWO__;o.rtl=__RTL__;o.gap=__GAP__;
if(window.__cvTimer){window.__cvTick();return}
var bx=null,anchor=null,anchorTaken=0,anchorDone=0,anchorTries=0,diag=0,cnt=0,root=null,strips=[],spacer=null,exMode=false,bar=null,menu=null;
function key(u){return (u||'').split('?')[0]}
function rules(){try{var r=JSON.parse(localStorage.getItem('cv_ex')||'{}');r.urls=r.urls||[];r.idx=r.idx||[];r.sels=r.sels||[];return r}catch(e){return {urls:[],idx:[],sels:[]}}}
function saveRules(r){try{localStorage.setItem('cv_ex',JSON.stringify(r))}catch(e){}}
function big(i){var w=i.naturalWidth,h=i.naturalHeight;
 if(!w||!h||w<350||h<100||w/h>8)return false;
 var r=i.getBoundingClientRect();
 return r.width==0||r.width>=window.innerWidth*0.6;}
function takeAnchor(bigs){anchorTaken=1;
 for(var n=0;n<bigs.length;n++){var r=bigs[n].getBoundingClientRect();
  if(r.bottom>1&&r.height>0){anchor={cvi:bigs[n].__cvi,frac:r.top<0?(-r.top/r.height):0};return}}
 var h=document.documentElement.scrollHeight;if(h>0)anchor={ratio:window.scrollY/h}}
function restoreAnchor(){
 if(!anchor||anchorDone||!root)return;anchorTries++;var y=null;
 if(anchor.cvi!==undefined){var cl=null,ready=true,k=strips[0].children;
  for(var n=0;n<k.length;n++){if(!k[n].complete||!k[n].naturalHeight)ready=false;if(k[n].__cvi===anchor.cvi){cl=k[n];break}}
  if(cl&&ready)y=cl.offsetTop+anchor.frac*cl.offsetHeight;}
 else if(strips[0].offsetHeight>window.innerHeight)y=anchor.ratio*strips[0].offsetHeight;
 if(y!==null||anchorTries>40){if(y!==null){window.scrollTo(0,y);upd()}anchorDone=1}}
function lazySrc(i){var n=['data-src','data-original','data-lazy-src','data-lazy','data-url','data-origin','data-echo','lazy-src','data-actualsrc'];
 for(var k=0;k<n.length;k++){var v=i.getAttribute(n[k]);if(v&&/^(https?:)?\/\/|^\/|\.(jpe?g|png|webp|gif)/i.test(v))return v}
 return null}
var swept=0;
function needSweep(imgs){return imgs.some(function(i){return !i.naturalWidth&&(i.getBoundingClientRect().height>=100||lazySrc(i)||i.loading=='lazy')})}
function sweep(){swept=1;var st=box('cv-diag','bottom:0;background:#c00');st.textContent='두쪽 보기: 이미지 불러오는 중...';
 var y=0,n=0;(function step(){var H=window.innerHeight;window.scrollTo(0,y);y+=H*0.8;n++;
  if(y<document.documentElement.scrollHeight&&n<30)setTimeout(step,200);
  else setTimeout(function(){window.scrollTo(0,0);st.remove();swept=2;tick()},800)})();}
function collapse(e){e.style.display='none';var p=e.parentElement;
 while(p&&p!==document.body&&p!==document.documentElement&&!p.closest('.cv-keep')){
  var vis=[].some.call(p.children,function(c){return c.style.display!=='none'&&c.offsetHeight>0&&getComputedStyle(c).display!=='none'});
  var txt=[].some.call(p.childNodes,function(n){return n.nodeType==3&&n.textContent.trim()});
  if(vis||txt)break;p.style.display='none';p=p.parentElement}}
function mk(t,id,c){var d=document.createElement(t);if(id)d.id=id;if(c)d.className=c;return d}
function build(){
 var st=mk('style','cv-style');
 st.textContent='body>*:not(#cv-root):not(#cv-spacer):not(.cv-keep){display:none!important}html,body{margin:0!important;padding:0!important;background:#111!important;overflow:visible!important;height:auto!important;position:static!important;transform:none!important}#cv-root{position:fixed;top:0;height:100vh;z-index:2147483000;display:flex;background:#111;overflow:hidden}.cv-pane{flex:1;min-width:0;height:100%;overflow:hidden;position:relative}.cv-strip{width:100%;will-change:transform}.cv-strip img{width:100%;height:auto;display:block;max-width:none;margin:0}';
 document.head.appendChild(st);
 root=mk('div','cv-root');root.style.pointerEvents=exMode?'auto':'none';
 for(var k=0;k<2;k++){var p=mk('div',null,'cv-pane'),s=mk('div',null,'cv-strip');p.appendChild(s);root.appendChild(p);strips.push(s);}
 spacer=mk('div','cv-spacer');
 document.body.appendChild(root);document.body.appendChild(spacer);
}
function add(i){var src=i.currentSrc||i.src;
 strips.forEach(function(s){var a=new Image();a.src=src;a.__cvi=i.__cvi;a.__cvsrc=src;
  var ref=null,k=s.children;for(var n=0;n<k.length;n++){if(k[n].__cvi>a.__cvi){ref=k[n];break}}
  s.insertBefore(a,ref);});}
function upd(){if(!root)return;
 var H=window.innerHeight,y=window.scrollY;
 root.style.flexDirection=o.rtl?'row-reverse':'row';root.style.gap=o.gap+'px';
 root.style.width='100%';root.style.left='0';
 strips[0].style.transform='translate3d(0,'+(-y)+'px,0)';
 strips[1].style.transform='translate3d(0,'+(-(y+H))+'px,0)';
 spacer.style.height=Math.max(strips[0].offsetHeight,H)+'px';}
function rebuild(){strips.forEach(function(s){s.innerHTML=''});
 [].forEach.call(document.images,function(i){i.__cva=0});tick();}
function tick(){try{tick0()}catch(e){window.__cvErr=String(e&&e.message||e);if(!window.__cvErrShown){window.__cvErrShown=1;var d=box('cv-err','bottom:0;background:#c00');d.textContent='두쪽 보기 오류: '+window.__cvErr;setTimeout(function(){d.remove()},8000)}}}
function tick0(){
 var imgs=[].slice.call(document.images).filter(function(i){return !i.closest('#cv-root')});
 imgs.forEach(function(i){
  if(i.__cvi===undefined)i.__cvi=cnt++;
  var d=lazySrc(i);
  if(d&&!i.__cvl&&(!i.src||i.src.indexOf('data:')==0||i.naturalWidth<2)&&(o.two||i.getBoundingClientRect().top<window.innerHeight*3)){
   i.__cvl=1;i.loading='eager';i.src=d;}
  else if(o.two&&i.loading=='lazy')i.loading='eager';
 });
 var r=rules();
 if(!o.two){imgs.forEach(function(i){if(r.urls.indexOf(key(i.currentSrc||i.src))>=0&&!i.__cvhid){collapse(i);i.__cvhid=1}});
  r.sels.forEach(function(sl){try{[].forEach.call(document.querySelectorAll(sl),function(e){if(e.style.display!=='none')collapse(e)})}catch(x){}})}
 var bigs=imgs.filter(big).sort(function(a,b){return a.__cvi-b.__cvi});
 bigs.forEach(function(i,k){i.__cvk=k;
  var ex=r.urls.indexOf(key(i.currentSrc||i.src))>=0||r.idx.indexOf(k)>=0;i.__cvx=ex;
  if(o.two)return;
  if(ex){if(!i.__cvhid){collapse(i);i.__cvhid=1}return}
  if(i.__cvhid){i.style.display='';i.__cvhid=0}
 });
 if(!o.two)return;
 if(!anchorTaken&&!root)takeAnchor(bigs);
 if(!root){if(swept==0&&(bigs.length<4||needSweep(imgs))){sweep();return}if(swept==1)return;if(!bigs.length)return;build()}
 bigs.forEach(function(i){if(i.__cva||i.__cvx)return;i.__cva=1;add(i);});
 upd();restoreAnchor();
 if(!window.__cvDiag&&++diag>8&&!strips[0].children.length){window.__cvDiag=1;
  var d=box('cv-diag','bottom:0;background:#c00');d.textContent='두쪽 보기: 큰 이미지를 찾지 못했습니다 / '+info();
  setTimeout(function(){d.remove()},6000);}}
function info(){var im=[].slice.call(document.images).filter(function(i){return !i.closest('#cv-root')});
 var sz=im.map(function(i){return i.naturalWidth+'x'+i.naturalHeight+'('+Math.round(i.getBoundingClientRect().width)+')'}).sort(function(a,b){return parseInt(b)*parseInt(b.split('x')[1])-parseInt(a)*parseInt(a.split('x')[1])}).slice(0,5);
 return 'img '+im.length+'개, 큰 이미지 '+im.filter(big).length+'개, 상위: '+sz.join(', ')+(window.__cvErr?' / 오류: '+window.__cvErr:'')+' / 패널 '+(strips[0]?strips[0].children.length:'없음')+' / iframe '+document.querySelectorAll('iframe').length+' / canvas '+document.querySelectorAll('canvas').length;}
window.__cvInfo=info;
window.__cvBounds=function(){var r=[];
 if(root){var k=strips[0].children;for(var n=0;n<k.length;n++)r.push(k[n].offsetTop)}
 else{[].slice.call(document.images).filter(function(i){return !i.closest('#cv-root')&&big(i)}).forEach(function(i){var q=i.getBoundingClientRect();if(q.height>0)r.push(q.top+window.scrollY)})}
 return JSON.stringify(r)};
window.__cvTick=tick;
window.__cvNext=function(click){
 var c=document.querySelector('a[rel=next][href]');
 if(!c){var re=/다음\s*(화|회|편|话|話)|next\s*(ep|episode|chapter)|^\s*(다음|next)\s*${'$'}/i;
  var as=[].slice.call(document.querySelectorAll('a,button'));
  for(var n=0;n<as.length;n++){var a=as[n];if(a.closest('.cv-keep'))continue;
   var t=(a.innerText||a.title||a.getAttribute('aria-label')||'').trim();
   if(t.length>0&&t.length<=14&&re.test(t)){c=a;break}}}
 if(!c)return '';
 if(click)c.click();return c.href||'1';};
// ---- 광고 제거 모드: 크기 조절 가능한 박스를 광고에 맞춰 [제거] ----
function btn(t,f){var b=mk('button');b.textContent=t;b.style.cssText='margin:3px;padding:9px 10px;font-size:14px';b.onclick=function(ev){ev.stopPropagation();f()};return b}
function box(id,css){var d=mk('div',id,'cv-keep');d.style.cssText='position:fixed;left:0;right:0;z-index:2147483647;color:#fff;font:14px sans-serif;text-align:center;padding:8px;'+css;document.body.appendChild(d);return d}
function ovl(a,b){var w=Math.min(a.right,b.right)-Math.max(a.left,b.left),h=Math.min(a.bottom,b.bottom)-Math.max(a.top,b.top);return(w>0&&h>0)?w*h:0}
function setRect(l,t,w,h){var W=window.innerWidth,H=window.innerHeight;w=Math.max(40,Math.min(w,W));h=Math.max(40,Math.min(h,H));
 l=Math.max(0,Math.min(l,W-w));t=Math.max(0,Math.min(t,H-h));
 bx.style.left=l+'px';bx.style.top=t+'px';bx.style.width=w+'px';bx.style.height=h+'px';}
function drag(el,fn){el.addEventListener('pointerdown',function(e){e.stopPropagation();e.preventDefault();el.setPointerCapture(e.pointerId);
 var sx=e.clientX,sy=e.clientY,r=bx.getBoundingClientRect();
 function mv(ev){fn(ev.clientX-sx,ev.clientY-sy,r)}
 function up(){el.removeEventListener('pointermove',mv);el.removeEventListener('pointerup',up);el.removeEventListener('pointercancel',up)}
 el.addEventListener('pointermove',mv);el.addEventListener('pointerup',up);el.addEventListener('pointercancel',up)})}
function mkBox(){var W=window.innerWidth,H=window.innerHeight;
 bx=mk('div','cv-box','cv-keep');
 bx.style.cssText='position:fixed;z-index:2147483646;left:'+(W*0.1)+'px;top:'+(H*0.3)+'px;width:'+(W*0.8)+'px;height:'+(H*0.25)+'px;border:3px solid #f33;background:rgba(255,0,0,.18);touch-action:none;box-sizing:border-box';
 document.body.appendChild(bx);
 drag(bx,function(dx,dy,r){setRect(r.left+dx,r.top+dy,r.width,r.height)});
 [['nw',0,0],['ne',1,0],['sw',0,1],['se',1,1]].forEach(function(c){
  var h=mk('div',null,'cv-keep');
  h.style.cssText='position:absolute;width:44px;height:44px;border-radius:50%;background:#f33;opacity:.9;touch-action:none;'+(c[1]?'right':'left')+':-18px;'+(c[2]?'bottom':'top')+':-18px';
  bx.appendChild(h);
  drag(h,function(dx,dy,r){var l=r.left,t=r.top,w=r.width,hh=r.height;
   if(c[1])w=r.width+dx;else{l=r.left+dx;w=r.width-dx}
   if(c[2])hh=r.height+dy;else{t=r.top+dy;hh=r.height-dy}
   if(w<40){if(!c[1])l=r.right-40;w=40}if(hh<40){if(!c[2])t=r.bottom-40;hh=40}
   setRect(l,t,w,hh)})});}
function selOf(e){var s=e.id?'#'+CSS.escape(e.id):e.tagName.toLowerCase()+(e.classList.length?'.'+[].map.call(e.classList,CSS.escape).join('.'):'');return s}
function applyBox(mode){var b=bx.getBoundingClientRect(),r=rules(),hits=[],seen={};
 [].slice.call(document.images).forEach(function(i){var o2=i;
  if(i.closest('#cv-root')){o2=null;[].forEach.call(document.images,function(x){if(x.__cvi===i.__cvi&&!x.closest('#cv-root'))o2=x});if(o2)o2.__cvsrc=i.__cvsrc}
  if(!o2||o2.closest('.cv-keep'))return;
  var q=i.getBoundingClientRect(),a=q.width*q.height;if(a<=0)return;
  if(ovl(b,q)/a>=0.6&&hits.indexOf(o2)<0)hits.push(o2)});
 var n=0;
 hits.forEach(function(o2){var src=key(o2.__cvsrc||o2.currentSrc||o2.src);
  if(mode=='pos'&&o2.__cvk!==undefined){if(r.idx.indexOf(o2.__cvk)<0)r.idx.push(o2.__cvk)}
  else if(r.urls.indexOf(src)<0)r.urls.push(src);n++});
 if(!n){var cx=b.left+b.width/2,cy=b.top+b.height/2,els=document.elementsFromPoint(cx,cy),e=null;
  for(var k=0;k<els.length;k++){var x=els[k];if(x===document.documentElement||x===document.body||x.closest('.cv-keep')||x.closest('#cv-root'))continue;e=x;break}
  if(e){while(e.parentElement&&e.parentElement!==document.body){var pr=e.parentElement.getBoundingClientRect();
    if(pr.left>=b.left-12&&pr.right<=b.right+12&&pr.top>=b.top-12&&pr.bottom<=b.bottom+12)e=e.parentElement;else break}
   var sl=selOf(e);if(document.querySelectorAll(sl).length<=5){r.sels=r.sels||[];if(r.sels.indexOf(sl)<0)r.sels.push(sl);collapse(e);n=1}}}
 saveRules(r);rebuild();return n}
window.__cvEx=function(on){exMode=on;if(bar){bar.remove();bar=null}if(bx){bx.remove();bx=null}
 if(on){mkBox();bar=box('cv-ui','top:0;background:#c00');
  var msg=document.createElement('div');msg.textContent='광고 제거: 빨간 박스를 광고에 맞춘 뒤 [제거] (스크롤은 박스 밖을 밀어서)';bar.appendChild(msg);
  var res=document.createElement('div');
  bar.appendChild(btn('제거 (이 화)',function(){res.textContent=applyBox('url')+'개 제거'}));
  bar.appendChild(btn('제거 (모든 화, 같은 위치)',function(){res.textContent=applyBox('pos')+'개 제거'}));
  bar.appendChild(btn('종료',function(){window.__cvEx(false)}));
  bar.appendChild(btn('규칙 초기화',function(){saveRules({urls:[],idx:[],sels:[]});location.reload()}));
  bar.appendChild(res);}};
window.addEventListener('scroll',upd,{passive:true});window.addEventListener('resize',upd);
window.__cvTimer=setInterval(tick,500);tick();
})()"""
    }
}
