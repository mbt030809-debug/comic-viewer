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
    private val handler = Handler(Looper.getMainLooper())
    private val density get() = resources.displayMetrics.density
    private val expanded get() = resources.configuration.screenWidthDp >= 600 // 폴드 펼침(태블릿 폭)

    // ---- 설정 ----
    private var two get() = prefs.getBoolean("two", false); set(v) = prefs.edit().putBoolean("two", v).apply()
    private var rtl get() = prefs.getBoolean("rtl", false); set(v) = prefs.edit().putBoolean("rtl", v).apply()
    private var gap get() = prefs.getInt("gap", 8); set(v) = prefs.edit().putInt("gap", v).apply()
    private var adblock get() = prefs.getBoolean("adblock", true); set(v) = prefs.edit().putBoolean("adblock", v).apply()
    private var keepOn get() = prefs.getBoolean("keepOn", true); set(v) = prefs.edit().putBoolean("keepOn", v).apply()
    private var autoFull get() = prefs.getBoolean("autoFull", true); set(v) = prefs.edit().putBoolean("autoFull", v).apply()
    private var autoNext get() = prefs.getBoolean("autoNext", true); set(v) = prefs.edit().putBoolean("autoNext", v).apply()
    private var foldAutoTwo get() = prefs.getBoolean("foldTwo", false); set(v) = prefs.edit().putBoolean("foldTwo", v).apply()
    private var smoothMs get() = prefs.getInt("ms", 500); set(v) = prefs.edit().putInt("ms", v).apply()
    private var pageRatio get() = prefs.getInt("ratio", 95); set(v) = prefs.edit().putInt("ratio", v).apply()
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
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("cv", MODE_PRIVATE)
        web = findViewById(R.id.web); urlInput = findViewById(R.id.urlInput)
        topBar = findViewById(R.id.topBar); autoBtn = findViewById(R.id.autoBtn); twoBtn = findViewById(R.id.twoBtn)
        nextBtn = findViewById(R.id.nextBtn)
        applyKeepOn()

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
        web.setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN && auto) applyAuto(false); false }
        web.setOnScrollChangeListener { _, _, y, _, oy -> onScrolled(y, y - oy) }

        findViewById<View>(R.id.goBtn).setOnClickListener { go(urlInput.text.toString()) }
        urlInput.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_GO) { go(urlInput.text.toString()); true } else false }
        findViewById<View>(R.id.backBtn).setOnClickListener { if (web.canGoBack()) web.goBack() }
        findViewById<View>(R.id.bookmarkBtn).setOnClickListener { showBookmarks() }
        findViewById<View>(R.id.historyBtn).setOnClickListener { showHistory() }
        findViewById<View>(R.id.settingsBtn).setOnClickListener { showSettings() }
        findViewById<View>(R.id.menuBtn).setOnClickListener { setFull(topBar.visibility == View.VISIBLE) }
        twoBtn.setOnClickListener { applyTwo(!two) }
        autoBtn.setOnClickListener { applyAuto(!auto) }
        findViewById<View>(R.id.downBtn).setOnClickListener { pageScroll(1) }
        findViewById<View>(R.id.upBtn).setOnClickListener { pageScroll(-1) }
        nextBtn.setOnClickListener { goNext() }
        findViewById<View>(R.id.topBtn).setOnClickListener { pageAnim?.cancel(); applyAuto(false); web.scrollTo(0, 0); setFull(false) }
        updateTwoBtn(); applyBtnAlpha(); applyScale()
        if (foldAutoTwo && expanded) { two = true; updateTwoBtn() }

        val shared = intent?.dataString ?: intent?.getStringExtra(android.content.Intent.EXTRA_TEXT)
        go(shared ?: prefs.getString("last", null) ?: "https://www.google.com")
    }

    override fun onConfigurationChanged(c: Configuration) {
        super.onConfigurationChanged(c)
        if (foldAutoTwo) { val was = two; two = expanded; updateTwoBtn(); if (was && !two) { web.reload(); return } }
        applyScale(); injectScript()
    }

    override fun onDestroy() { applyAuto(false); web.destroy(); super.onDestroy() }
    override fun onPause() { applyAuto(false); savePosNow(); super.onPause() }
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
    /** 이미지 너비: 웹뷰 좌우에 검정 여백을 주어 페이지를 좁게(작게) 표시 */
    private fun applyScale() {
        val m = (resources.displayMetrics.widthPixels * (100 - imgScale) / 200f).toInt()
        val lp = web.layoutParams as FrameLayout.LayoutParams
        if (lp.leftMargin != m || lp.rightMargin != m) { lp.leftMargin = m; lp.rightMargin = m; web.layoutParams = lp }
    }
    private fun applyBtnAlpha() { findViewById<View>(R.id.btnBox).alpha = btnAlpha / 100f }
    private fun updateTwoBtn() { twoBtn.text = if (two) "두쪽●" else "두쪽" }
    private fun applyTwo(v: Boolean) {
        val was = two; two = v; updateTwoBtn()
        if (was && !v) web.reload() else injectScript()
    }
    private fun injectScript() {
        web.evaluateJavascript(
            SCRIPT.replace("__TWO__", "$two").replace("__RTL__", "$rtl").replace("__GAP__", "$gap"), null
        )
    }

    // ---- 전체화면(아래로 스크롤=숨김, 위로 스크롤=표시) ----
    private var full = false
    private var fullLockUntil = 0L
    private var dyAcc = 0
    private fun setFull(v: Boolean) {
        if (full == v) return
        full = v; fullLockUntil = System.currentTimeMillis() + 500; dyAcc = 0
        topBar.visibility = if (v) View.GONE else View.VISIBLE
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                if (v) { it.hide(WindowInsets.Type.systemBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
                else it.show(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (v) (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE) else View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    private var atBottomShown = false
    private fun atBottom() = web.contentHeight > 0 && web.scrollY > 0 &&
        web.scrollY + web.height >= web.contentHeight * density - 80

    private val saveRun = Runnable { savePosNow() }
    private fun onScrolled(y: Int, dy: Int) {
        if (autoFull && System.currentTimeMillis() > fullLockUntil) {
            dyAcc = if ((dyAcc > 0) == (dy > 0)) dyAcc + dy else dy
            if (dyAcc > 40) setFull(true) else if (dyAcc < -40 || y < 20) setFull(false)
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
        if (dir > 0) { if (autoFull) setFull(true); if (autoNext && atBottom()) { goNext(); return } }
        else if (autoFull) setFull(false)
        val dist = (web.height * pageRatio / 100f).toInt() * dir
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
        curKey = key
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
        sw("스크롤 시 전체화면 (아래=숨김 / 위=표시)", autoFull) { autoFull = it }
        bar("우측 버튼 불투명도 ", 20, 100, btnAlpha, { "$it%" }) { btnAlpha = it; applyBtnAlpha() }
        head("[▲▼ 버튼 / 볼륨키]")
        bar("1회 이동 거리(화면 높이 대비) ", 10, 100, pageRatio, { "$it%" }) { pageRatio = it }
        bar("이동 소요 시간(길수록 천천히 부드럽게) ", 0, 2000, smoothMs, { if (it == 0) "즉시" else "${it}ms" }) { smoothMs = it / 100 * 100 }
        head("[자동 버튼: 계속 흐르는 스크롤]")
        bar("자동 스크롤 속도 ", 20, 600, autoSpeed, { "${it}px/초" }) { autoSpeed = it / 10 * 10 }
        sw("끝에 도달하면 다음화로 자동 이동", autoNext) { autoNext = it }
        head("[광고]")
        sw("광고 도메인 차단", adblock) { adblock = it }
        box.addView(Button(this).apply {
            text = "광고 이미지 제외 모드 시작"
            setOnClickListener {
                (tag as? AlertDialog)?.dismiss()
                injectScript(); setFull(false)
                web.evaluateJavascript("window.__cvEx&&window.__cvEx(true)", null)
            }
        })
        val sv = ScrollView(this).apply { addView(box) }
        val dlg = AlertDialog.Builder(this).setTitle("설정").setView(sv).setPositiveButton("확인") { _, _ ->
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
var o=window.__cvOpts={two:__TWO__,rtl:__RTL__,gap:__GAP__};
if(window.__cvTimer){window.__cvTick();return}
var diag=0,cnt=0,root=null,strips=[],spacer=null,exMode=false,bar=null,menu=null;
function key(u){return (u||'').split('?')[0]}
function rules(){try{var r=JSON.parse(localStorage.getItem('cv_ex')||'{}');r.urls=r.urls||[];r.idx=r.idx||[];return r}catch(e){return {urls:[],idx:[]}}}
function saveRules(r){try{localStorage.setItem('cv_ex',JSON.stringify(r))}catch(e){}}
function big(i){var w=i.naturalWidth,h=i.naturalHeight;
 if(!w||!h||w<350||h<250||w/h>3)return false;
 var r=i.getBoundingClientRect();
 return r.width==0||r.width>=window.innerWidth*0.4;}
function lazySrc(i){var n=['data-src','data-original','data-lazy-src','data-lazy','data-url','data-origin','data-echo','lazy-src','data-actualsrc'];
 for(var k=0;k<n.length;k++){var v=i.getAttribute(n[k]);if(v&&/^(https?:)?\/\/|^\/|\.(jpe?g|png|webp|gif)/i.test(v))return v}
 return null}
var swept=0;
function sweep(){swept=1;var st=box('cv-diag','bottom:0;background:#c00');st.textContent='두쪽 보기: 이미지 불러오는 중...';
 var y=0,n=0;(function step(){var H=window.innerHeight;window.scrollTo(0,y);y+=H*0.8;n++;
  if(y<document.documentElement.scrollHeight&&n<80)setTimeout(step,200);
  else setTimeout(function(){window.scrollTo(0,0);st.remove();swept=2;tick()},1200)})();}
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
function tick(){
 var imgs=[].slice.call(document.images).filter(function(i){return !i.closest('#cv-root')});
 imgs.forEach(function(i){
  if(i.__cvi===undefined)i.__cvi=cnt++;
  var d=lazySrc(i);
  if(d&&!i.__cvl&&(!i.src||i.src.indexOf('data:')==0||i.naturalWidth<2)&&(o.two||i.getBoundingClientRect().top<window.innerHeight*3)){
   i.__cvl=1;i.loading='eager';i.src=d;}
  else if(o.two&&i.loading=='lazy')i.loading='eager';
 });
 var r=rules();
 var bigs=imgs.filter(big).sort(function(a,b){return a.__cvi-b.__cvi});
 bigs.forEach(function(i,k){i.__cvk=k;
  var ex=r.urls.indexOf(key(i.currentSrc||i.src))>=0||r.idx.indexOf(k)>=0;i.__cvx=ex;
  if(o.two)return;
  if(ex){i.style.display='none';i.__cvhid=1;return}
  if(i.__cvhid){i.style.display='';i.__cvhid=0}
 });
 if(!o.two)return;
 if(!root){if(swept==0){sweep();return}if(swept==1)return;build()}
 bigs.forEach(function(i){if(i.__cva||i.__cvx)return;i.__cva=1;add(i);});
 upd();
 if(!window.__cvDiag&&++diag>8&&!strips[0].children.length){window.__cvDiag=1;
  var d=box('cv-diag','bottom:0;background:#c00');d.textContent='두쪽 보기: 큰 이미지를 찾지 못했습니다 (페이지 이미지 '+imgs.length+'개)';
  setTimeout(function(){d.remove()},6000);}}
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
// ---- 광고 제외 모드 ----
function btn(t,f){var b=mk('button');b.textContent=t;b.style.cssText='margin:4px;padding:10px 12px;font-size:15px';b.onclick=function(ev){ev.stopPropagation();f()};return b}
function box(id,css){var d=mk('div',id,'cv-keep');d.style.cssText='position:fixed;left:0;right:0;z-index:2147483647;color:#fff;font:14px sans-serif;text-align:center;padding:8px;'+css;document.body.appendChild(d);return d}
function closeMenu(){if(menu){menu.remove();menu=null}}
function showMenu(i){closeMenu();var k=i.__cvk,src=key(i.__cvsrc||i.currentSrc||i.src);
 menu=box('cv-menu','bottom:0;background:#222;border-top:2px solid #c00');
 menu.appendChild(document.createTextNode((k+1)+'번째 이미지'));menu.appendChild(mk('br'));
 menu.appendChild(btn('이 이미지 제외 (같은 주소)',function(){var r=rules();r.urls.push(src);saveRules(r);closeMenu();rebuild()}));
 menu.appendChild(btn((k+1)+'번째 위치 제외 (이 사이트 모든 화)',function(){var r=rules();r.idx.push(k);saveRules(r);closeMenu();rebuild()}));
 menu.appendChild(btn('취소',closeMenu));}
window.__cvEx=function(on){exMode=on;closeMenu();if(bar){bar.remove();bar=null}
 if(root)root.style.pointerEvents=on?'auto':'none';
 if(on){bar=box('cv-ui','top:0;background:#c00');
  bar.appendChild(document.createTextNode('광고 제외 모드: 제외할 이미지를 탭하세요'));
  bar.appendChild(btn('종료',function(){window.__cvEx(false)}));
  bar.appendChild(btn('규칙 초기화',function(){saveRules({urls:[],idx:[]});location.reload()}));}};
document.addEventListener('click',function(e){
 if(!exMode)return;var t=e.target;if(t.closest&&t.closest('.cv-keep'))return;
 e.preventDefault();e.stopPropagation();
 var img=null;
 if(t.tagName=='IMG')img=t;else{var els=document.elementsFromPoint(e.clientX,e.clientY);for(var n=0;n<els.length;n++){if(els[n].tagName=='IMG'){img=els[n];break}}}
 if(!img)return;
 if(img.closest('#cv-root')){var cs=img.__cvi,orig=null;[].forEach.call(document.images,function(x){if(x.__cvi===cs&&!x.closest('#cv-root'))orig=x});
  if(!orig)return;orig.__cvsrc=img.__cvsrc;img=orig}
 if(img.__cvk===undefined)return;
 showMenu(img);},true);
window.addEventListener('scroll',upd,{passive:true});window.addEventListener('resize',upd);
window.__cvTimer=setInterval(tick,500);tick();
})()"""
    }
}
