package com.example.comicviewer

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
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
    private var keepOn get() = prefs.getBoolean("keepOn", true); set(v) = prefs.edit().putBoolean("keepOn", v).apply()
    private var autoPageMode get() = prefs.getBoolean("autoPage", false); set(v) = prefs.edit().putBoolean("autoPage", v).apply() // 자동 방식2
    private var autoWait get() = prefs.getInt("autoWait", 30); set(v) = prefs.edit().putInt("autoWait", v).apply() // 방식2 대기(0.1초 단위)
    private var autoWaitScale get() = prefs.getBoolean("autoWaitScale", false); set(v) = prefs.edit().putBoolean("autoWaitScale", v).apply() // 경계 때문에 덜 이동했으면 대기도 그 비율만큼 단축
    private var autoWaitMin get() = prefs.getInt("autoWaitMin", 5); set(v) = prefs.edit().putInt("autoWaitMin", v).apply() // 경계로 덜 이동했을 때의 최소 대기(0.1초 단위)
    private var autoNext get() = prefs.getBoolean("autoNext", true); set(v) = prefs.edit().putBoolean("autoNext", v).apply()
    private var foldAutoTwo get() = prefs.getBoolean("foldTwo", false); set(v) = prefs.edit().putBoolean("foldTwo", v).apply()
    private var smoothMs get() = prefs.getInt("ms", 500); set(v) = prefs.edit().putInt("ms", v).apply()
    private var pageRatio get() = prefs.getInt("ratio", 95); set(v) = prefs.edit().putInt("ratio", v).apply()
    private var pageRatio2 get() = prefs.getInt("ratio2", 100); set(v) = prefs.edit().putInt("ratio2", v).apply() // 두쪽 보기 1회 이동 거리(%)
    private var insetL = 0; private var insetR = 0 // 가로 모드의 노치/내비게이션 바 좌우 여백(px)
    private var navInset = 0 // 마지막으로 확인한 하단 내비게이션 바 높이(px)
    private var barInset = 0 // 마지막으로 확인한 상태바 높이(px). 메뉴를 다시 열 때 미리 반영
    private var quietUntil = 0L // 두쪽 보기 준비(스윕) 중에는 스크롤 이벤트로 저장/전체화면/다음화 판단 안 함
    private var btnSize get() = prefs.getInt("btnSize", 52); set(v) = prefs.edit().putInt("btnSize", v).apply() // 플로팅 버튼 크기(dp)
    private var holdSpeed get() = prefs.getInt("holdSpeed", 800); set(v) = prefs.edit().putInt("holdSpeed", v).apply() // ▼ 꾹 누름 연속 스크롤 속도(px/초)
    private var snapImg get() = prefs.getBoolean("snapImg", false); set(v) = prefs.edit().putBoolean("snapImg", v).apply() // 이미지 사이 단색 경계 직전에서 멈춤
    private var colorTol get() = prefs.getInt("colorTol", 8); set(v) = prefs.edit().putInt("colorTol", v).apply() // 같은 색으로 볼 색상 차이 허용(%)
    private var ignorePct get() = prefs.getInt("ignorePct", 30); set(v) = prefs.edit().putInt("ignorePct", v).apply() // 이동 거리 중 이 % 이내에서 시작되는 단색 띠는 무시
    private var stopBottomPct get() = prefs.getInt("stopBottomPct", 5); set(v) = prefs.edit().putInt("stopBottomPct", v).apply() // 경계에서 멈출 때 화면 아래에 남길 여백(화면 높이 %)
    private var skipTopPct get() = prefs.getInt("skipTopPct", 10); set(v) = prefs.edit().putInt("skipTopPct", v).apply() // 건너뛴 뒤 화면 위쪽을 비워둘 비율(%): 카메라(컷아웃)에 가려지는 윗부분 확보
    private var skipBand get() = prefs.getBoolean("skipBand", true); set(v) = prefs.edit().putBoolean("skipBand", v).apply() // 경계에 멈춘 뒤 다음 이동은 경계를 건너뜀
    private var blackL get() = prefs.getInt("blackL", 0); set(v) = prefs.edit().putInt("blackL", v).apply() // 단색 판정 영역: 왼쪽 위치(이미지 너비 %)
    private var blackW get() = prefs.getInt("blackW", 100); set(v) = prefs.edit().putInt("blackW", v).apply() // 단색 판정 영역: 가로 폭(%)
    private var blackH get() = prefs.getInt("blackH", 60); set(v) = prefs.edit().putInt("blackH", v).apply() // 단색 띠로 볼 최소 높이(dp)
    private var btnAlpha get() = prefs.getInt("alpha", 70); set(v) = prefs.edit().putInt("alpha", v).apply()
    private var autoSpeed get() = prefs.getInt("speed", 120); set(v) = prefs.edit().putInt("speed", v).apply()
    private var imgScale: Int // 이미지 너비(%): 접힘/펼침 상태별로 따로 저장
        get() = prefs.getInt(if (expanded) "scale_e" else "scale_c", 100)
        set(v) = prefs.edit().putInt(if (expanded) "scale_e" else "scale_c", v).apply()

    private var pageAnim: ValueAnimator? = null
    private var auto = false
    private var resumeAuto = false
    private val clearResume = Runnable { resumeAuto = false }
    /** 다음화 이동 후 자동 스크롤을 이어갈 예약. 페이지 로드가 없으면 10초 뒤 자동 해제 */
    private fun armResume() { resumeAuto = true; handler2.removeCallbacks(clearResume); handler2.postDelayed(clearResume, 10000) }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        // 카메라 컷아웃(펀치홀/노치) 영역까지 화면을 사용 — 전체화면 시 상단 검은 띠 제거
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xFF000000.toInt())) // 시스템 바 뒤/가장자리가 회색으로 보이지 않게
        setContentView(R.layout.activity_main)
        setupEdgeToEdge()
        prefs = getSharedPreferences("cv", MODE_PRIVATE)
        web = findViewById(R.id.web); urlInput = findViewById(R.id.urlInput)
        topBar = findViewById(R.id.topBar); autoBtn = findViewById(R.id.autoBtn); twoBtn = findViewById(R.id.twoBtn)
        nextBtn = findViewById(R.id.nextBtn)
        applyKeepOn()
        initScrollBar()
        findViewById<View>(R.id.topBar).addOnLayoutChangeListener { v, _, t, _, bottom, _, _, _, _ ->
            // 메뉴가 표시 중이면 웹페이지 상단 여백을 메뉴 높이에 맞춤(초기 표시/높이 변화 대응)
            if (!full && menuAnim?.isRunning != true && v.visibility == View.VISIBLE) {
                val p2 = web.layoutParams as FrameLayout.LayoutParams
                if (p2.topMargin != bottom - t) { p2.topMargin = bottom - t; web.layoutParams = p2 }
            }
        }
        web.isVerticalScrollBarEnabled = false; web.isHorizontalScrollBarEnabled = false
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        BandAnalyzer.userAgent = web.settings.userAgentString // 분석용 이미지 요청도 페이지와 같은 UA 사용
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(v: WebView, u: String, f: Bitmap?) {
                handler.removeCallbacksAndMessages(null)
                snapInfo = null
                analyzer.cancelPending() // 이전 페이지 이미지 분석이 새 페이지 로딩과 대역폭/연결을 다투지 않게 중단
                curKey = null; saving = false; atBottomShown = false; nextBtn.visibility = View.GONE
                syncUrl(u)
            }
            override fun doUpdateVisitedHistory(v: WebView, u: String, isReload: Boolean) { syncUrl(u) } // 리다이렉트·SPA 이동 포함, 실제 현재 주소 반영
            override fun onPageFinished(v: WebView, u: String) {
                syncUrl(u)
                injectScript()
                handler.postDelayed({ checkTitle() }, 300)
                if (resumeAuto) { resumeAuto = false; handler.postDelayed({ applyAuto(true) }, 2000) }
                else if (auto && autoPageMode) { handler.removeCallbacks(pageAutoRun); handler.postDelayed(pageAutoRun, 1500) } // 페이지 이동으로 지워진 방식2 예약 복구
            }
            override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                if (r.isForMainFrame) Toast.makeText(this@MainActivity, "로드 실패 · ${e.description}", Toast.LENGTH_SHORT).show()
            }
            override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                val s = r.url.scheme
                return s != "http" && s != "https"
            }
        }
        web.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { touching = true; resumeAuto = false; pageAnim?.cancel(); if (auto) applyAuto(false) }
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
            if (editMode) Toast.makeText(this, "버튼을 끌어 이동", Toast.LENGTH_SHORT).show()
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

    /** 접힘↔펼침 등으로 창 너비가 바뀐 뒤(레이아웃 완료 후) 호출 */
    private fun onWidthChanged() {
        closeRegionEditor() // 회전/접힘으로 좌표가 달라졌으므로 편집기 닫음
        applyScale()
        if (foldAutoTwo) {
            val want = expanded
            if (want != two) { two = want; updateTwoBtn(); if (!want) { web.reload(); return } }
        }
        injectScript()
    }

    override fun onDestroy() { analyzer.shutdown(); applyAuto(false); stopHold(); handler.removeCallbacksAndMessages(null); handler2.removeCallbacksAndMessages(null); web.destroy(); super.onDestroy() }
    override fun onPause() { syncUrl(web.url); touching = false; applyAuto(false); stopHold(); savePosNow(); web.onPause(); CookieManager.getInstance().flush(); super.onPause() }
    override fun onResume() { super.onResume(); web.onResume() }
    @Deprecated("") override fun onBackPressed() { if (closeRegionEditor()) return; if (web.canGoBack()) web.goBack() else super.onBackPressed() }
    override fun onKeyDown(k: Int, e: KeyEvent): Boolean = when (k) {
        KeyEvent.KEYCODE_VOLUME_DOWN -> { if (e.repeatCount == 0 && regionScrim == null) pageScroll(1); true } // 길게 눌러 반복되는 키 입력은 무시(애니메이션 재시작 방지)
        KeyEvent.KEYCODE_VOLUME_UP -> { if (e.repeatCount == 0 && regionScrim == null) pageScroll(-1); true }
        else -> super.onKeyDown(k, e)
    }

    /** 현재 웹페이지 주소를 주소창과 '마지막 페이지'로 저장(주소 입력 중이면 주소창은 건드리지 않음) */
    private fun syncUrl(u: String?) {
        if (u.isNullOrEmpty() || u == "about:blank") return
        prefs.edit().putString("last", u).apply()
        if (!urlInput.hasFocus() && urlInput.text.toString() != u) urlInput.setText(u)
    }

    private fun go(raw: String, notify: Boolean = false) {
        resumeAuto = false
        urlInput.clearFocus()
        var u = raw.trim(); if (u.isEmpty()) return
        if (!u.contains("://")) u = if (u.contains(".") && !u.contains(" ")) "https://$u" else "https://www.google.com/search?q=" + java.net.URLEncoder.encode(u, "utf-8")
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlInput.windowToken, 0)
        if (notify) Toast.makeText(this, "열기 · ${android.net.Uri.parse(u).host ?: u}", Toast.LENGTH_SHORT).show() // 북마크/기록 탭 반응 확인용
        web.stopLoading()
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
        layer.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol) placeBtns() // 폭이 바뀔 때(접힘/펼침·회전)만 저장된 비율로 재배치
            else if (b - t != ob - ot) floatBtns().forEach { v -> v.y = v.y.coerceIn(0f, ((b - t) - v.height).toFloat().coerceAtLeast(0f)) } // 시스템바 토글 등은 위치 유지
        }
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
        if (lp.leftMargin != m + insetL || lp.rightMargin != m + insetR) { lp.leftMargin = m + insetL; lp.rightMargin = m + insetR; web.layoutParams = lp }
    }
    private fun applyBtnAlpha() { floatBtns().forEach { it.alpha = btnAlpha / 100f } }
    private fun updateTwoBtn() { twoBtn.text = if (two) "두쪽●" else "두쪽" }
    private fun applyTwo(v: Boolean) {
        val was = two; two = v; updateTwoBtn()
        if (was && !v) web.reload() else injectScript()
    }
    private fun injectScript() {
        snapInfo = null // 레이아웃이 바뀔 수 있으므로 이미지 위치 정보 폐기
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
    /** 화면 전체(상태바·카메라 영역 포함)를 앱이 그리고, 시스템 바가 보일 때만 그 높이만큼 메뉴/웹뷰 여백을 둠 */
    @Suppress("DEPRECATION")
    private fun setupEdgeToEdge() {
        val rootV = findViewById<View>(R.id.root)
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        rootV.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int; val bottom: Int; val left: Int; val right: Int
            if (Build.VERSION.SDK_INT >= 30) { val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()); top = b.top; bottom = b.bottom; left = b.left; right = b.right }
            else { top = insets.systemWindowInsetTop; bottom = insets.systemWindowInsetBottom; left = insets.systemWindowInsetLeft; right = insets.systemWindowInsetRight }
            if (insetL != left || insetR != right) { insetL = left; insetR = right; applyScale() }
            (track.layoutParams as FrameLayout.LayoutParams).let { if (it.bottomMargin != bottom + (16 * density).toInt() || it.rightMargin != right) { it.bottomMargin = bottom + (16 * density).toInt(); it.rightMargin = right; track.layoutParams = it } }
            (nextBtn.layoutParams as FrameLayout.LayoutParams).let { val nb = bottom + (24 * density).toInt(); if (it.bottomMargin != nb) { it.bottomMargin = nb; nextBtn.layoutParams = it } }
            val bar = findViewById<View>(R.id.topBar)
            if (Build.VERSION.SDK_INT >= 30) { if (insets.isVisible(WindowInsets.Type.statusBars())) barInset = insets.getInsets(WindowInsets.Type.statusBars()).top }
            else if (!full && top > 0) barInset = top
            if (bottom > 0) navInset = bottom
            if (bar.paddingTop != top) bar.setPadding(0, top, 0, 0)
            val lp = web.layoutParams as FrameLayout.LayoutParams
            if (lp.bottomMargin != bottom) { lp.bottomMargin = bottom; web.layoutParams = lp }
            insets
        }
        rootV.requestApplyInsets()
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
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION) else (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        }
    }
    private fun setFull(v: Boolean) {
        if (v && editMode) return // 버튼 편집 중에는 메뉴를 숨기지 않음
        if (full == v) return
        full = v; fullLockUntil = System.currentTimeMillis() + 500; dyAcc = 0
        if (v) urlInput.clearFocus() // 메뉴가 숨겨지면 주소창 편집 상태 해제(이후 주소 자동 갱신)
        // 메뉴 표시/숨김 애니메이션(300ms, One UI 유사 곡선): 메뉴가 나오면 웹페이지가 아래로 밀리고, 숨기면 위로 올라옴
        if (!v) topBar.setPadding(0, barInset, 0, 0) // 메뉴를 열면 상태바도 다시 나오므로 그 높이를 미리 포함
        run { // 숨김(GONE) 상태의 이전 높이가 낡았을 수 있어 항상 다시 측정
            topBar.measure(View.MeasureSpec.makeMeasureSpec(if (rootW > 0) rootW else resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        }
        val h = topBar.measuredHeight.coerceAtLeast(1)
        val lp = web.layoutParams as FrameLayout.LayoutParams
        val from = (lp.topMargin + web.translationY).toInt() // 현재 눈에 보이는 웹페이지 상단 위치
        val to = if (v) 0 else h
        menuAnim?.cancel()
        topBar.visibility = View.VISIBLE
        // 웹뷰 크기는 한 번만 바꾸고(레이아웃 1회), 이동은 translationY로 보여줘 애니메이션 중 끊김을 줄임
        lp.topMargin = to; web.layoutParams = lp
        web.translationY = (from - to).toFloat()
        menuAnim = ValueAnimator.ofFloat(web.translationY, 0f).apply {
            duration = 300; interpolator = android.view.animation.PathInterpolator(0.33f, 0f, 0.67f, 1f)
            addUpdateListener {
                val ty = it.animatedValue as Float
                web.translationY = ty
                val m = to + ty // 눈에 보이는 웹페이지 상단 위치
                topBar.translationY = m - h; topBar.alpha = (m / h).coerceIn(0f, 1f)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    web.translationY = 0f
                    if (!cancelled && full) topBar.visibility = View.GONE
                    else if (!cancelled && topBar.height > 0) { val p3 = web.layoutParams as FrameLayout.LayoutParams; if (p3.topMargin != topBar.height) { p3.topMargin = topBar.height; web.layoutParams = p3 } }
                }
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
        val tick = object : Runnable { override fun run() {
            try { updateThumb(); checkTitle(); if (++analyzeTick % 3 == 0) scheduleAnalysis() } catch (_: Exception) { /* 저장 데이터 이상 등으로 1초 주기 작업이 앱을 종료시키지 않게 */ }
            track.postDelayed(this, 1000)
        } }
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
        if (snapImg) { handler.removeCallbacks(analyzeRun); handler.postDelayed(analyzeRun, 500) } // 스크롤이 멈춘 뒤 갱신
        if (System.currentTimeMillis() < quietUntil && !userScrolling()) return // 두쪽 준비 중 코드 스크롤만 무시(손가락 스크롤은 처리)
        if (System.currentTimeMillis() > fullLockUntil) { // 아래로 스크롤=메뉴 숨김, 위로 스크롤(또는 맨 위)=메뉴 표시
            dyAcc = if ((dyAcc > 0) == (dy > 0)) dyAcc + dy else dy
            if (dyAcc > 40) setFull(true) else if (userScrolling() && (dyAcc < -60 || y < 20)) setFull(false)
        }
        val b = atBottom()
        if (b != atBottomShown) { atBottomShown = b; nextBtn.visibility = if (b) View.VISIBLE else View.GONE
            if (b && auto && autoNext) { applyAuto(false); armResume(); handler.postDelayed({ if (resumeAuto) goNext() }, 1200) } }
        if (saving && curKey != null) { handler.removeCallbacks(saveRun); handler.postDelayed(saveRun, 800) }
    }

    // ---- 다음화 ----
    private var lastNextAt = 0L
    private fun goNext() {
        if (System.currentTimeMillis() - lastNextAt < 3000) return // 연속 호출로 두 화를 건너뛰는 것 방지
        lastNextAt = System.currentTimeMillis()
        nextBtn.visibility = View.GONE
        web.evaluateJavascript("window.__cvNext?window.__cvNext(true):''") { r ->
            if (r == null || r == "\"\"" || r == "null") { resumeAuto = false; Toast.makeText(this, "다음화 없음", Toast.LENGTH_SHORT).show() }
        }
    }

    // ---- 스크롤 ----
    /** 메뉴를 열었을 때의 높이(상태바 포함) */
    private fun menuHeightPx(): Int {
        topBar.setPadding(0, barInset, 0, 0)
        topBar.measure(View.MeasureSpec.makeMeasureSpec(if (rootW > 0) rootW else resources.displayMetrics.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        return topBar.measuredHeight
    }

    /** onDecided(이동 비율(0~1, 설정 이동 거리 대비 실제), 이동 시간 ms): 실제 이동이 정해진 시점에 호출(자동 방식2 대기 계산용) */
    private fun pageScroll(dir: Int, onDecided: ((Float, Int) -> Unit)? = null) {
        // 메뉴 상태가 바뀐 뒤의 최종 웹뷰 높이를 setFull 호출 전에 계산(setFull이 여백을 즉시 바꾸므로)
        val wasFull = full
        val finalH = if (editMode) web.height
            else if (dir > 0 && !wasFull) web.height + (web.layoutParams as FrameLayout.LayoutParams).let { it.topMargin + it.bottomMargin } // 메뉴·시스템바가 숨겨져 높이가 늘어남
            else if (dir < 0 && wasFull) (web.height - menuHeightPx() - navInset).coerceAtLeast(1) // 메뉴가 다시 나와 높이가 줄어듦
            else web.height
        setFull(dir > 0)
        if (dir > 0 && autoNext && atBottom()) { goNext(); return }
        val dist = (finalH * (if (two) pageRatio2 else pageRatio) / 100f).toInt() * dir
        if (!snapImg) { animateScroll(dist); onDecided?.invoke(1f, smoothMs); return }
        // 이동은 바로 시작: 직전에 갱신해 둔 이미지 위치 정보가 신선하면 그것으로 경계를 계산하고, 없으면 일반 이동 후 정보를 갱신
        val info = snapInfo
        if (info == null || System.currentTimeMillis() - snapInfoAt > 6000) {
            animateScroll(dist); onDecided?.invoke(1f, smoothMs); scheduleAnalysis(); return
        }
        val d = computeSnap(info, dist, finalH)
        // 이동 시간 설정은 '기본 이동 거리' 기준이므로, 실제 이동한 거리 비율만큼 보정해 속도가 일정하게 느껴지게 함
        val ms = if (d == null || dist == 0) smoothMs else (smoothMs * abs(d).toFloat() / abs(dist)).toInt().coerceIn(minOf(smoothMs, 120), smoothMs * 8)
        animateScroll(d ?: dist, ms)
        onDecided?.invoke(if (d == null || dist == 0) 1f else (abs(d).toFloat() / abs(dist)).coerceIn(0f, 1f), ms)
    }

    private var animTargetY = 0 // 진행 중인 페이지 이동 애니메이션의 최종 scrollY(px)
    private fun animateScroll(dist: Int, ms: Int = smoothMs) {
        pageAnim?.cancel()
        animTargetY = web.scrollY + dist
        if (ms == 0) { web.scrollBy(0, dist); return }
        var done = 0
        pageAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms.toLong(); interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val target = (it.animatedValue as Float * dist).toInt()
                web.scrollBy(0, target - done); done = target
            }
            start()
        }
    }

    /**
     * 단색 판정 영역 편집기: 화면에 사각형을 띄워 드래그(이동)·모서리(크기)로 지정.
     * 가로 위치/길이는 웹페이지 너비 대비 %, 세로 길이는 dp(=단색 띠 최소 높이)로 저장.
     */
    private var settingsDlg: AlertDialog? = null
    private var regionScrim: View? = null
    private fun closeRegionEditor(): Boolean { val v = regionScrim ?: return false; (v.parent as? ViewGroup)?.removeView(v); regionScrim = null; return true }
    @SuppressLint("ClickableViewAccessibility")
    private fun showRegionEditor() {
        val root = findViewById<FrameLayout>(R.id.root)
        val lpw = web.layoutParams as FrameLayout.LayoutParams
        val webL = lpw.leftMargin.toFloat(); val fullW = web.width.toFloat()
        // 두쪽 보기에서는 이미지 폭 = 한 칸 폭이므로 왼쪽 칸 기준 %로 저장
        val webW = if (two) (fullW - gap * density) / 2f else fullW
        val scrim = FrameLayout(this).apply { setBackgroundColor(0x66000000); isClickable = true; elevation = 30f; clipChildren = false }
        val rect = FrameLayout(this).apply {
            clipChildren = false // 모서리 핸들이 사각형 밖으로 나가도 터치 가능
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0x44FF0000); setStroke((3 * density).toInt(), 0xFFFF3333.toInt()) }
        }
        val minPx = 20 * density
        var rl = webL + webW * blackL / 100f; var rw = webW * blackW / 100f
        var rh = blackH * density; var rt = (root.height - rh) / 2f
        var infoRef: TextView? = null
        fun apply() {
            val lp = FrameLayout.LayoutParams(rw.toInt(), rh.toInt()); lp.leftMargin = rl.toInt(); lp.topMargin = rt.toInt(); rect.layoutParams = lp
            infoRef?.text = "${((rl - webL) / webW * 100).toInt().coerceIn(0, 100)}% · ${(rw / webW * 100).toInt().coerceIn(0, 100)}% · ${(rh / density).toInt()}dp"
        }
        fun clamp() {
            rw = rw.coerceIn(minPx, webW); rh = rh.coerceIn(minPx, root.height.toFloat())
            rl = rl.coerceIn(webL, webL + webW - rw); rt = rt.coerceIn(0f, root.height - rh)
        }
        clamp(); scrim.addView(rect); apply()
        val hs = (44 * density).toInt()
        fun drag(v: View, onMove: (dx: Float, dy: Float, l: Float, t: Float, w: Float, h: Float) -> Unit) {
            var sx = 0f; var sy = 0f; var l0 = 0f; var t0 = 0f; var w0 = 0f; var h0 = 0f
            v.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; l0 = rl; t0 = rt; w0 = rw; h0 = rh }
                    MotionEvent.ACTION_MOVE -> { onMove(e.rawX - sx, e.rawY - sy, l0, t0, w0, h0); clamp(); apply() }
                }
                true
            }
        }
        drag(rect) { dx, dy, l, t, _, _ -> rl = l + dx; rt = t + dy }
        listOf("nw", "ne", "sw", "se").forEach { c ->
            val h = View(this).apply { background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(0xFFFF3333.toInt()) } }
            val lp = FrameLayout.LayoutParams(hs, hs)
            lp.gravity = (if (c[0] == 'n') Gravity.TOP else Gravity.BOTTOM) or (if (c[1] == 'w') Gravity.START else Gravity.END)
            lp.leftMargin = -hs / 3; lp.rightMargin = -hs / 3; lp.topMargin = -hs / 3; lp.bottomMargin = -hs / 3
            rect.addView(h, lp)
            drag(h) { dx, dy, l, t, w, hh ->
                // 서쪽/북쪽 핸들은 반대편 변을 고정하고 이 변만 이동(최소 크기·경계 안에서)
                if (c[1] == 'e') rw = w + dx else { val r = l + w; rl = (l + dx).coerceIn(webL, maxOf(webL, r - minPx)); rw = r - rl }
                if (c[0] == 's') rh = hh + dy else { val b = t + hh; rt = (t + dy).coerceIn(0f, maxOf(0f, b - minPx)); rh = b - rt }
            }
        }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xEE222222.toInt()) }
        val info = TextView(this).apply { setTextColor(0xFFFFFFFF.toInt()); setPadding(24, 0, 24, 0); gravity = Gravity.CENTER_VERTICAL }
        infoRef = info; apply()
        bar.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        bar.addView(Button(this).apply { text = "확인"; setOnClickListener {
            blackL = ((rl - webL) / webW * 100f).toInt().coerceIn(0, 90)
            blackW = (rw / webW * 100f).toInt().coerceIn(10, 100)
            blackH = (rh / density).toInt().coerceIn(10, 300)
            analyzer.clear(); scheduleAnalysis()
            closeRegionEditor()
            Toast.makeText(this@MainActivity, "영역 ${blackL}% · ${blackW}% · ${blackH}dp", Toast.LENGTH_SHORT).show()
        } })
        bar.addView(Button(this).apply { text = "취소"; setOnClickListener { closeRegionEditor() } })
        scrim.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (56 * density).toInt(), Gravity.BOTTOM).apply { bottomMargin = (web.layoutParams as FrameLayout.LayoutParams).bottomMargin })
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        regionScrim = scrim
    }

    // ---- 경계 멈춤: 이미지를 미리 내려받아 분석한 단색 띠 위치로 목표 지점을 정함(이동 중 되돌림 없음) ----
    private val analyzer = BandAnalyzer()
    private val rescan = Runnable { analyzer.clear(); scheduleAnalysis() } // 슬라이더 조작 중 연속 재분석 방지(디바운스)
    private var analyzeTick = 0
    private class Img(val src: String, val t: Float, val h: Float)

    private class Parsed(val imgs: List<Img>, val blk: List<FloatArray>)

    private fun parseImgs(r: String?): Parsed? = try {
        val o = JSONObject(org.json.JSONTokener(r ?: "").nextValue() as String)
        val a = o.getJSONArray("imgs")
        val bk = o.optJSONArray("blk")
        Parsed(
            List(a.length()) { a.getJSONObject(it).let { j -> Img(j.getString("s"), j.getDouble("t").toFloat(), j.getDouble("h").toFloat()) } },
            if (bk == null) emptyList() else List(bk.length()) { bk.getJSONObject(it).let { j -> floatArrayOf(j.getDouble("t").toFloat(), j.getDouble("h").toFloat()) } })
    } catch (_: Exception) { null }

    /** 주기적으로 페이지의 큰 이미지를 찾아 아직 분석하지 않은 것을 백그라운드로 분석 요청 */
    private var snapInfo: Parsed? = null // 마지막으로 읽은 이미지 위치 정보(이동 시 즉시 계산용)
    private var snapInfoAt = 0L
    private val analyzeRun = Runnable { scheduleAnalysis() }
    private fun scheduleAnalysis() {
        if (!snapImg || web.progress < 100) return // 페이지 로딩이 끝난 뒤에만 분석(로딩을 방해하지 않음)
        web.evaluateJavascript("window.__cvImgs?window.__cvImgs():''") { r ->
            val p = parseImgs(r) ?: return@evaluateJavascript
            snapInfo = p; snapInfoAt = System.currentTimeMillis()
            // 현재 화면 + 다음에 이동할 화면 범위의 이미지만 미리 내려받아 분석(나머지는 이동해 가면서)
            val h = web.height / density; val cur = web.scrollY / density
            val vis = if (two) 2 * h else h
            val d = minOf(h * (if (two) pageRatio2 else pageRatio) / 100f, 3f * h)
            val lo = cur - 0.5f * h; val hi = cur + vis + d + 0.5f * h
            p.imgs.filter { it.t + it.h >= lo && it.t <= hi && it.src.startsWith("http") }
                .sortedBy { abs(it.t - cur) }
                .forEach { analyzer.enqueue(it.src, web.url ?: "", colorTol, blackL, blackW) }
        }
    }

    /** css px 단위의 단색 띠 목록 [top, bottom, 최소높이] : 이미지 안의 단색 행 + 이미지 사이 빈틈(안에 다른 요소가 없을 때만) */
    private fun bandsOf(p: Parsed): List<FloatArray> {
        // raw: [top, bottom, 필요한 최소 높이, 색 변화로 시작한 띠(1)]. 가장자리에 닿은 띠/이미지 사이 빈틈은 blackH, 이미지 안쪽 단색 구간(하늘·말풍선 등)은 더 크게 요구
        val raw = ArrayList<FloatArray>()
        var maxBottom = Float.NEGATIVE_INFINITY
        val gapCap = maxOf(blackH * 6f, 400f)
        p.imgs.forEachIndexed { i, im ->
            analyzer.cached(im.src, colorTol, blackL, blackW)?.forEach {
                val edge = it[0] * im.h <= 4f || (1f - it[1]) * im.h <= 4f // 가장자리 판정은 px 기준(이미지 높이와 무관)
                val req = if (edge) blackH.toFloat() else maxOf(2f * blackH, 0.15f * im.h)
                raw.add(floatArrayOf(im.t + it[0] * im.h, im.t + it[1] * im.h, req, it[2]))
            }
            if (i > 0) {
                val gap = im.t - maxBottom
                // 빈틈이 너무 크거나(내용이 있을 가능성), 그 안에 광고/작은 조각/미로딩 이미지가 있으면 경계로 보지 않음
                if (gap > 0f && gap <= gapCap && p.blk.none { it[0] < im.t && it[0] + it[1] > maxBottom })
                    raw.add(floatArrayOf(maxBottom, im.t, blackH.toFloat(), 0f))
            }
            maxBottom = maxOf(maxBottom, im.t + im.h)
        }
        raw.sortBy { it[0] }
        val merged = ArrayList<FloatArray>()
        for (b in raw) {
            val last = merged.lastOrNull()
            // 색이 바뀌며 이어진 띠(그라데이션)는 합치지 않아 하나의 큰 띠로 오인되지 않게 함
            if (last != null && b[0] - last[1] <= 8f && b[3] == 0f) { last[1] = maxOf(last[1], b[1]); last[2] = minOf(last[2], b[2]) }
            else merged.add(floatArrayOf(b[0], b[1], b[2], b[3]))
        }
        return merged.filter { it[1] - it[0] >= it[2] }
    }

    /** 이동 목표를 단색 띠 앞/뒤로 조정(멈춤·건너뜀). 설정한 무시 구간(%) 이내의 띠는 무시. 해당 없으면 null(일반 이동) */
    private fun computeSnap(parsed: Parsed, dist: Int, viewH: Int): Int? {
        val bands = bandsOf(parsed)
        // 이동 애니메이션이 진행 중이면 그 목표 위치를 현재 위치로 사용(연타 시 어긋남 방지)
        val curPx = if (pageAnim?.isRunning == true) animTargetY else web.scrollY
        val cur = curPx / density; val hCss = viewH / density
        val visH = if (two) 2 * hCss else hCss // 두쪽 보기는 오른쪽 칸 끝까지가 보이는 범위
        val d = abs(dist) / density
        val tol = 12f
        val m = skipTopPct / 100f * hCss // 화면 높이의 N%만큼 위를 비워 다음 이미지 윗부분이 카메라에 가려지지 않게
        val mb = stopBottomPct / 100f * hCss // 경계에서 멈출 때 화면 아래에 남길 여백(경계 띠를 그만큼 보여줌)
        // 이미 경계 바로 앞에서 멈춘 상태라면, 다음 이동은 경계(단색 띠)를 건너뛰어 다음 이미지 맨 위가 보이게 함
        if (skipBand) {
            if (dist > 0) {
                // 경계 뛰어넘기가 x% 이동보다 우선: (1) 경계 바로 앞에 멈춰 있거나 (2) 경계가 화면 안에 보이고 건너뛰는 이동량이 최소 y%(무시 구간) 이상이면 경계를 건너뜀
                val minMove = (ignorePct / 100f) * d
                val b = bands.firstOrNull { abs(it[0] + mb - (cur + visH)) <= tol }
                    ?: bands.firstOrNull { it[0] > cur && it[0] < cur + visH - tol && (it[1] - m - cur) >= minMove }
                if (b != null) { val t = ((b[1] - m - cur) * density).toInt(); if (t > 4) return t }
            } else bands.lastOrNull { abs(it[1] - cur) <= tol || abs(it[1] - m - cur) <= tol }?.let { b ->
                val t = ((b[0] + m - visH - cur) * density).toInt(); if (t < -4) return t // 위로: 윗 이미지의 끝이 화면 아래에 오도록
            }
        }
        // 건너뜀 직후(띠 아래끝이 화면 위 여백 위치)에는 무시 구간 없이 다음 경계에 멈춤(그렇지 않으면 짧은 이미지에서 다음 이미지로 넘어가 버림)
        val landed = skipBand && bands.any { abs(it[1] - m - cur) <= tol }
        val ig = if (landed) 0f else (ignorePct / 100f) * d
        val res = if (dist > 0) {
            val b = bands.filter { it[0] + mb >= cur + visH + maxOf(ig, tol + 1f) && it[0] + mb <= cur + visH + d }.minByOrNull { it[0] } ?: return null
            ((b[0] + mb - visH - cur) * density).toInt()
        } else {
            val b = bands.filter { it[1] <= cur - maxOf(ig, tol + 1f) && it[1] >= cur - d }.maxByOrNull { it[1] } ?: return null
            -((cur - b[1]) * density).toInt()
        }
        return if (abs(res) < 4) null else res // 이동량이 거의 0이면(멈춤 위치에 이미 있음) 일반 이동
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
                if (autoNext) { armResume(); goNext() }
                return
            }
            pageScroll(1) { ratio, ms -> // 설정 거리만큼 이동했으면 설정 대기, 경계로 덜 이동했으면(옵션) 줄어든 비율만큼 덜 대기
                // 대기 = 최소 + (최대 - 최소) × 이동 비율  (최대 = 설정 대기시간, 최소 = 최소 대기시간)
                val maxW = autoWait * 100L; val minW = minOf(autoWaitMin * 100L, maxW)
                val wait = if (autoWaitScale) minW + (maxW - minW) * ratio else maxW.toFloat()
                if (auto && autoPageMode) { handler.removeCallbacks(this); handler.postDelayed(this, wait.toLong() + ms) } // 비동기 콜백이 겹쳐 두 갈래로 도는 것 방지
            }
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
        var before = t.substring(0, m.range.first).trim().trimEnd(' ', '-', '|', ':', '·', '–').replace(Regex("\\s제$"), "").trim()
        before = before.split(sep).lastOrNull()?.trim() ?: ""
        var name = before
        if (name.isEmpty()) name = t.substring(m.range.last + 1).trim(' ', '-', '|', ':', '·', '–').split(sep).firstOrNull()?.trim() ?: ""
        if (name.isEmpty()) return null
        return name to ep
    }

    private class Series(var name: String, val key: String, var last: Int, var max: Int, val eps: MutableSet<Int>, var url: String, var time: Long)

    private fun loadSeries(): MutableList<Series> = try { loadSeriesRaw() } catch (_: Exception) { mutableListOf() }
    private fun loadSeriesRaw(): MutableList<Series> {
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

    private class Pos(val key: String, val y: Int, val ratio: Float, val exp: Boolean, val two: Boolean, val sc: Int)
    private fun loadPos(): MutableList<Pos> = try { loadPosRaw() } catch (_: Exception) { mutableListOf() }
    private fun loadPosRaw(): MutableList<Pos> {
        val a = JSONArray(prefs.getString("pos", "[]"))
        return MutableList(a.length()) { val o = a.getJSONObject(it); Pos(o.getString("k"), o.getInt("y"), o.getDouble("r").toFloat(), o.getBoolean("e"), o.optBoolean("t", false), o.optInt("s", 100)) }
    }
    private fun savePosNow() {
        val k = curKey ?: return
        if (!saving || web.scrollY <= 0) return
        val l = loadPos(); l.removeAll { it.key == k }
        val h = (web.contentHeight * density).coerceAtLeast(1f)
        l.add(Pos(k, web.scrollY, web.scrollY / h, expanded, two, imgScale))
        while (l.size > 300) l.removeAt(0)
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("k", it.key).put("y", it.y).put("r", it.ratio.toDouble()).put("e", it.exp).put("t", it.two).put("s", it.sc)) }
        prefs.edit().putString("pos", a.toString()).apply()
    }
    private fun restorePos(key: String) {
        val p = loadPos().firstOrNull { it.key == key }
        if (p == null || p.y < 50) { saving = true; return }
        var tries = 0; var lastH = -1; var stable = 0
        val sameLayout = p.exp == expanded && p.two == two && p.sc == imgScale // 레이아웃이 같을 때만 픽셀 위치, 다르면 비율로 복원
        val r = object : Runnable {
            fun step() {
                val h = (web.contentHeight * density).toInt()
                stable = if (h == lastH) stable + 1 else 0; lastH = h
                val target = if (sameLayout) p.y else (p.ratio * h).toInt()
                val ready = h >= target + web.height * 0.5 && (sameLayout || stable >= 3)
                if (ready || ++tries > 30) {
                    if (ready) web.scrollTo(0, target)
                    saving = true
                    return
                }
                handler.postDelayed(this, 400)
            }
            override fun run() {
                if (curKey != key) return
                if (two) { // 두쪽 보기는 스크립트가 위치 앵커 복원을 마친 뒤(__cvReady)에 복원해야 덮어써지지 않음
                    web.evaluateJavascript("(window.__cvReady?1:0)") { v ->
                        if (curKey == key) { if (v?.trim() == "1" || tries > 25) step() else { tries++; handler.postDelayed(this, 400) } }
                    }
                } else step()
            }
        }
        handler.postDelayed(r, 400)
    }

    private fun showHistory() {
        val l = loadSeries().sortedByDescending { it.time }.toMutableList()
        if (l.isEmpty()) { Toast.makeText(this, "기록 없음", Toast.LENGTH_SHORT).show(); return }
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.KOREA)
        fun label(s: Series) = "${s.name}\n마지막 ${s.last}화 · 최대 ${s.max}화 · 읽은 화 ${s.eps.size}개 · ${fmt.format(java.util.Date(s.time))}"
        val ad = ArrayAdapter(this, android.R.layout.simple_list_item_1, l.map { label(it) }.toMutableList())
        val dlg = AlertDialog.Builder(this).setTitle("기록")
            .setAdapter(ad) { _, i -> if (l[i].url.isNotEmpty()) go(l[i].url, true) else Toast.makeText(this, "저장된 주소 없음", Toast.LENGTH_SHORT).show() }
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
    private fun loadBookmarks(): MutableList<Pair<String, String>> = try { loadBookmarksRaw() } catch (_: Exception) { mutableListOf() }
    private fun loadBookmarksRaw(): MutableList<Pair<String, String>> {
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
        val dlg = AlertDialog.Builder(this).setTitle("북마크")
            .setAdapter(ad) { _, i -> go(list[i].second, true) }
            .setPositiveButton("추가") { _, _ ->
                val u = web.url ?: return@setPositiveButton
                val t = web.title?.takeIf { it.isNotBlank() } ?: u
                val parsed = parseTitle(t)
                val label = parsed?.let { (n, e) -> "$n ${e}화" } ?: t // 예: "웹툰명 12화"
                // 같은 웹툰(이름 동일)이면 덮어쓰기. 주소가 바뀌어도 이름으로 식별
                val key = parsed?.let { norm(it.first) }
                val idx = list.indexOfFirst { b -> b.second == u || (key != null && parseTitle(b.first)?.let { norm(it.first) } == key) }
                if (idx >= 0) { list[idx] = label to u; Toast.makeText(this, "덮어씀 · $label", Toast.LENGTH_SHORT).show() }
                else { list.add(label to u); Toast.makeText(this, "추가 · $label", Toast.LENGTH_SHORT).show() }
                saveBookmarks(list)
            }
            .setNegativeButton("닫기", null).create()
        dlg.show()
        dlg.listView.setOnItemLongClickListener { _, _, i, _ ->
            AlertDialog.Builder(this).setMessage("삭제: ${list[i].first}").setPositiveButton("삭제") { _, _ ->
                list.removeAt(i); saveBookmarks(list); ad.clear(); ad.addAll(list.map { it.first }); ad.notifyDataSetChanged()
            }.setNegativeButton("취소", null).show()
            true
        }
    }

    // ---- 설정 ----
    private fun showSettings() {
        val pad = (16 * density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, 0, pad, pad) }
        var needInject = false; var needReload = false
        val dp = { v: Int -> (v * density).toInt() }
        fun group(t: String) {
            box.addView(View(this).apply { setBackgroundColor(0x33888888) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = dp(18) })
            box.addView(TextView(this).apply { text = t; textSize = 12f; setTextColor(0xFF888888.toInt()); setPadding(0, dp(8), 0, 0) })
        }
        fun sw(t: String, v: Boolean, f: (Boolean) -> Unit) = box.addView(Switch(this).apply { text = t; isChecked = v; setPadding(0, dp(6), 0, dp(6)); setOnCheckedChangeListener { _, c -> f(c) } })
        fun bar(t: String, min: Int, max: Int, v: Int, fmt: (Int) -> String, f: (Int) -> Unit) {
            val name = TextView(this).apply { text = t }
            val value = TextView(this).apply { text = fmt(v); setTextColor(0xFF888888.toInt()) }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
            row.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)); row.addView(value)
            box.addView(row)
            box.addView(SeekBar(this).apply {
                this.max = max - min; progress = v - min
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, u: Boolean) { value.text = fmt(p + min); f(p + min) }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            })
        }
        fun btn(t: String, onClick: (Button) -> Unit) = box.addView(Button(this).apply { text = t; isAllCaps = false; setOnClickListener { onClick(this) } })

        group("보기")
        sw("두쪽", two) { val was = two; two = it; if (was && !it) needReload = true else needInject = true }
        sw("오른쪽 → 왼쪽", rtl) { rtl = it; needInject = true }
        bar("여백", 0, 60, gap, { "${it}px" }) { gap = it; needInject = true }
        bar(if (expanded) "이미지 너비 · 펼침" else "이미지 너비 · 접힘", 30, 100, imgScale, { "$it%" }) { imgScale = it; applyScale() }
        sw("펼치면 두쪽", foldAutoTwo) { foldAutoTwo = it }

        group("화면")
        sw("켜짐 유지", keepOn) { keepOn = it; applyKeepOn() }
        bar("버튼 크기", 32, 96, btnSize, { "${it}dp" }) { btnSize = it; applyBtnSize() }
        bar("버튼 투명도", 20, 100, btnAlpha, { "$it%" }) { btnAlpha = it; applyBtnAlpha() }
        btn("버튼 위치 초기화") { resetBtnPositions() }

        group("이동")
        bar("거리", 10, 300, pageRatio, { "$it%" }) { pageRatio = it }
        bar("거리 · 두쪽", 10, 300, pageRatio2, { "$it%" }) { pageRatio2 = it }
        bar("시간", 0, 2000, smoothMs, { if (it == 0) "즉시" else "${it}ms" }) { smoothMs = it / 100 * 100 }
        bar("꾹 누름 속도", 100, 5000, holdSpeed, { "${it}px/s" }) { holdSpeed = it / 50 * 50 }

        group("자동")
        sw("페이지 단위", autoPageMode) { autoPageMode = it }
        bar("속도", 20, 2000, autoSpeed, { "${it}px/s" }) { autoSpeed = it / 10 * 10 }
        box.addView(EditText(this).apply {
            hint = "대기 (초)"
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
        sw("경계 이동은 대기 단축", autoWaitScale) { autoWaitScale = it }
        box.addView(EditText(this).apply {
            hint = "최소 대기 (초)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(String.format(java.util.Locale.US, "%.1f", autoWaitMin / 10f))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(e: android.text.Editable?) {
                    e?.toString()?.toFloatOrNull()?.let { autoWaitMin = (it * 10).toInt().coerceIn(0, 3600) }
                }
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            })
        })
        sw("끝에서 다음화", autoNext) { autoNext = it }

        group("경계")
        sw("경계에서 멈춤", snapImg) { snapImg = it; if (it) scheduleAnalysis() }
        bar("멈춤 아래 여백", 0, 30, stopBottomPct, { "$it%" }) { stopBottomPct = it }
        sw("멈춘 뒤 건너뜀", skipBand) { skipBand = it }
        bar("건너뜀 위 여백", 0, 30, skipTopPct, { "$it%" }) { skipTopPct = it }
        bar("무시 구간", 0, 90, ignorePct, { "${it}%" }) { ignorePct = it }
        bar("색 차이 허용", 1, 40, colorTol, { "${it}%" }) { colorTol = it; handler2.removeCallbacks(rescan); handler2.postDelayed(rescan, 700) }
        btn("영역 ${blackL}% · ${blackW}% · ${blackH}dp") { settingsDlg?.dismiss(); showRegionEditor() }

        group("광고")
        btn("광고 제거") {
            settingsDlg?.dismiss()
            handler2.postDelayed({ // 닫힘 리스너(변경 적용/새로고침)가 먼저 끝난 뒤 실행
                injectScript(); setFull(true)
                web.evaluateJavascript("window.__cvEx&&window.__cvEx(true)", null)
            }, 400)
        }
        box.isFocusableInTouchMode = true; box.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS; box.requestFocus() // 처음 포커스가 입력칸으로 가서 아래로 스크롤되는 것 방지
        val sv = ScrollView(this).apply { addView(box) }
        val dlg = AlertDialog.Builder(this).setTitle("설정 v" + packageManager.getPackageInfo(packageName, 0).versionName).setView(sv).setPositiveButton("확인", null).create()
        dlg.setOnDismissListener { // 확인/바깥 터치/뒤로가기/다른 편집기로 전환 등 어떤 경로로 닫아도 변경 사항 적용
            updateTwoBtn()
            if (needReload) web.reload() else if (needInject) injectScript()
        }
        settingsDlg = dlg
        dlg.show()
    }

    companion object {
        // 두쪽 보기: 연속 세로 스트립을 좌/우 두 패널로 표시(왼쪽=현재 위치, 오른쪽=한 화면 아래).
        // 문서 스크롤(native)에 맞춰 두 스트립을 translate. 광고 제외 규칙은 사이트(localStorage)별 저장.
        private const val SCRIPT = """(function(){
var o=window.__cvOpts=window.__cvOpts||{};o.two=__TWO__;o.rtl=__RTL__;o.gap=__GAP__;
if(window.__cvTimer){window.__cvTick();return}
var bx=null,anchor=null,anchorTaken=0,anchorDone=0,anchorTries=0,cnt=0,root=null,strips=[],spacer=null,bar=null;
function key(u){return (u||'').split('?')[0]}
function rules(){try{var r=JSON.parse(localStorage.getItem('cv_ex')||'{}');r.urls=r.urls||[];r.idx=r.idx||[];r.sels=r.sels||[];return r}catch(e){return {urls:[],idx:[],sels:[]}}}
function saveRules(r){try{localStorage.setItem('cv_ex',JSON.stringify(r))}catch(e){}}
function big(i){var src=i.currentSrc||i.src;
 if(i.__cvb!==undefined&&i.__cvbs===src)return i.__cvb; // 같은 주소의 판정은 재사용(레이아웃 계산 절약). 자리표시 이미지→실제 이미지로 바뀌면 다시 판정
 var w=i.naturalWidth,h=i.naturalHeight;
 if(!w||!h)return false;
 i.__cvbs=src;
 if(w<350||h<100||w/h>8)return(i.__cvb=false);
 var r=i.getBoundingClientRect();
 if(r.width==0){i.__cvbs=null;return true} // 숨김/미배치 상태는 캐시하지 않음
 return(i.__cvb=(r.width>=window.innerWidth*0.6));}
function takeAnchor(bigs){anchorTaken=1;
 for(var n=0;n<bigs.length;n++){var r=bigs[n].getBoundingClientRect();
  if(r.bottom>1&&r.height>0){anchor={cvi:bigs[n].__cvi,frac:r.top<0?(-r.top/r.height):0};return}}
 var h=document.documentElement.scrollHeight;if(h>0)anchor={ratio:window.scrollY/h}}
function restoreAnchor(){
 if(!anchor||anchorDone){window.__cvReady=1;return}
 anchorTries++;var y=null;
 if(anchor.cvi!==undefined){var cl=null,ready=true,k=strips[0].children;
  for(var n=0;n<k.length;n++){if(!k[n].complete||!k[n].naturalHeight)ready=false;if(k[n].__cvi===anchor.cvi){cl=k[n];break}}
  if(cl&&ready)y=cl.offsetTop+anchor.frac*cl.offsetHeight;}
 else if(strips[0].offsetHeight>window.innerHeight)y=anchor.ratio*strips[0].offsetHeight;
 if(y!==null||anchorTries>40){if(y!==null){window.scrollTo(0,y);upd()}anchorDone=1;window.__cvReady=1}}
function lazySrc(i){var n=['data-src','data-original','data-lazy-src','data-lazy','data-url','data-origin','data-echo','lazy-src','data-actualsrc'];
 for(var k=0;k<n.length;k++){var v=i.getAttribute(n[k]);if(v&&/^(https?:)?\/\/|^\/|\.(jpe?g|png|webp|gif)/i.test(v))return v}
 return null}
var swept=0;
function needSweep(imgs){return imgs.some(function(i){return !i.naturalWidth&&(i.getBoundingClientRect().height>=100||lazySrc(i)||i.loading=='lazy')})}
function sweep(){swept=1;var st=box('cv-diag','bottom:0;background:#c00');st.textContent='두쪽 보기: 이미지 불러오는 중...';
 var y=0,n=0;(function step(){var H=window.innerHeight;window.scrollTo(0,y);y+=H*0.8;n++;
  if(y<document.documentElement.scrollHeight&&n<30)setTimeout(step,200);
  else setTimeout(function(){window.scrollTo(0,0);st.remove();swept=2;tick()},800)})();}
function collapse(e){e.style.display='none';var hs=[e];e.__cvhs=hs;var p=e.parentElement;
 while(p&&p!==document.body&&p!==document.documentElement&&!p.closest('.cv-keep')){
  var vis=[].some.call(p.children,function(c){if(c===e||c.style.display==='none'||getComputedStyle(c).display==='none')return false;
   return c.offsetHeight>0||/^(IMG|IFRAME|CANVAS|VIDEO)$/.test(c.tagName)||!!c.querySelector('img,iframe,canvas,video')});
  var txt=[].some.call(p.childNodes,function(n){return n.nodeType==3&&n.textContent.trim()});
  if(vis||txt)break;p.style.display='none';hs.push(p);p=p.parentElement}}
function mk(t,id,c){var d=document.createElement(t);if(id)d.id=id;if(c)d.className=c;return d}
function build(){
 var st=mk('style','cv-style');
 st.textContent='body>*:not(#cv-root):not(#cv-spacer):not(.cv-keep){display:none!important}html,body{margin:0!important;padding:0!important;background:#000!important;overflow:visible!important;height:auto!important;position:static!important;transform:none!important}#cv-root{position:fixed;top:0;height:100vh;z-index:2147483000;display:flex;background:#000;overflow:hidden}.cv-pane{flex:1;min-width:0;height:100%;overflow:hidden;position:relative}.cv-strip{width:100%;will-change:transform}.cv-strip img{width:100%;height:auto;display:block;max-width:none;margin:0}';
 document.head.appendChild(st);
 root=mk('div','cv-root');root.style.pointerEvents='none';
 for(var k=0;k<2;k++){var p=mk('div',null,'cv-pane'),s=mk('div',null,'cv-strip');p.appendChild(s);root.appendChild(p);strips.push(s);}
 spacer=mk('div','cv-spacer');
 document.body.appendChild(root);document.body.appendChild(spacer);
}
function add(i){var src=i.currentSrc||i.src;
 strips.forEach(function(s){var a=new Image();a.src=src;a.__cvi=i.__cvi;a.__cvsrc=src;
  var ref=null,k=s.children;for(var n=0;n<k.length;n++){if(k[n].__cvi>a.__cvi){ref=k[n];break}}
  s.insertBefore(a,ref);});}
function upd(full){if(!root)return;
 var H=window.innerHeight,y=window.scrollY;
 root.style.flexDirection=o.rtl?'row-reverse':'row';root.style.gap=o.gap+'px';
 root.style.width='100%';root.style.left='0';
 strips[0].style.transform='translate3d(0,'+(-y)+'px,0)';
 strips[1].style.transform='translate3d(0,'+(-(y+H))+'px,0)';
 if(full!==false)spacer.style.height=Math.max(strips[0].offsetHeight,H)+'px';} // 스크롤 중에는 offsetHeight(강제 레이아웃) 읽지 않음
function rebuild(){strips.forEach(function(s){s.innerHTML=''});
 [].forEach.call(document.images,function(i){i.__cva=0});tick();}
var lastScroll=0,lastTick=0,t0=Date.now();
function tick(){var nw=Date.now();if(root&&nw-lastScroll<300&&nw-lastTick<1500)return; // 스크롤 중에는 건너뛰되 1.5초에 한 번은 반드시 실행
 lastTick=nw;
 try{tick0()}catch(e){window.__cvErr=String(e&&e.message||e);if(!window.__cvErrShown){window.__cvErrShown=1;var d=box('cv-err','bottom:0;background:#c00');d.textContent='두쪽 보기 오류: '+window.__cvErr;setTimeout(function(){d.remove()},8000)}}}
function tick0(){
 var imgs=[].slice.call(document.images).filter(function(i){return !i.closest('#cv-root')});
 imgs.forEach(function(i){
  if(i.__cvi===undefined)i.__cvi=cnt++;
  var d=lazySrc(i);
  if(d&&Date.now()-t0>4000&&!i.__cvl&&(!i.src||i.src.indexOf('data:')==0||i.naturalWidth<2)&& // 사이트가 세션/토큰을 준비하기 전에 이미지를 강제로 요청하지 않도록 4초 대기
   (o.two||i.getBoundingClientRect().top<window.innerHeight*2)){
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
  if(i.__cvhid){(i.__cvhs||[i]).forEach(function(el){el.style.display=''});i.__cvhid=0}
 });
 if(!o.two)return;
 if(root){var live={};imgs.forEach(function(i){live[i.__cvi]=1});strips.forEach(function(s){[].slice.call(s.children).forEach(function(c){if(!live[c.__cvi])c.remove()})})}
 if(!anchorTaken)takeAnchor(bigs);
 if(!root){if(swept==0&&(bigs.length<4||needSweep(imgs))){sweep();return}if(swept==1)return;if(!bigs.length)return;build()}
 bigs.forEach(function(i){if(i.__cva||i.__cvx)return;i.__cva=1;add(i);});
 upd();restoreAnchor();
}
window.__cvImgs=function(){var out=[],y=window.scrollY;
 if(root){var k=strips[0].children;for(var n=0;n<k.length;n++)out.push({s:k[n].__cvsrc||k[n].src,t:k[n].offsetTop,h:k[n].offsetHeight})}
 else{[].slice.call(document.images).filter(function(i){return !i.closest('#cv-root')&&big(i)}).forEach(function(i){var q=i.getBoundingClientRect();if(q.height>0)out.push({s:i.currentSrc||i.src,t:q.top+y,h:q.height})})
  out.sort(function(a,b){return a.t-b.t})}
 var blk=[];if(!root){[].slice.call(document.querySelectorAll('img,iframe,canvas,video')).forEach(function(e){if(e.closest('#cv-root')||e.closest('.cv-keep')||(e.tagName=='IMG'&&big(e)))return;var q=e.getBoundingClientRect();if(q.height>=10&&q.width>0)blk.push({t:q.top+y,h:q.height})})} // 큰 이미지가 아닌 보이는 요소(광고·작은 조각·미로딩 이미지): 이미지 사이 빈틈 판정에서 '내용 있음'으로 취급
 return JSON.stringify({imgs:out,blk:blk})};
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
function applyBox(mode){var b=bx.getBoundingClientRect(),r=rules(),hits=[];
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
window.__cvEx=function(on){if(bar){bar.remove();bar=null}if(bx){bx.remove();bx=null}
 if(on){mkBox();bar=box('cv-ui','top:0;background:#c00');
  var msg=document.createElement('div');msg.textContent='광고에 박스를 맞추기';bar.appendChild(msg);
  var res=document.createElement('div');
  bar.appendChild(btn('이 화',function(){res.textContent=applyBox('url')+'개 제거'}));
  bar.appendChild(btn('모든 화',function(){res.textContent=applyBox('pos')+'개 제거'}));
  bar.appendChild(btn('종료',function(){window.__cvEx(false)}));
  bar.appendChild(btn('초기화',function(){saveRules({urls:[],idx:[],sels:[]});location.reload()}));
  bar.appendChild(res);}};
window.addEventListener('scroll',function(){lastScroll=Date.now();upd(false)},{passive:true});window.addEventListener('resize',function(){upd()});
window.__cvTimer=setInterval(tick,800);tick();
})()"""
    }
}
