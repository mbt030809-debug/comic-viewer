package com.example.comicviewer

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.webkit.CookieManager
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.ImageLoader
import okhttp3.OkHttpClient
import kotlin.math.max

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var loader: ImageLoader
    private lateinit var extractor: ImageExtractor
    private lateinit var main: RecyclerView
    private lateinit var preview: RecyclerView
    private lateinit var mainFrame: View
    private lateinit var panel: View
    private lateinit var topBar: View
    private lateinit var status: TextView
    private lateinit var urlInput: EditText

    private var urls: List<String> = emptyList()
    private var pageUrl = ""
    private var anim: ValueAnimator? = null

    // 설정값
    private var twoPage get() = prefs.getBoolean("two", false); set(v) = prefs.edit().putBoolean("two", v).apply()
    private var rtl get() = prefs.getBoolean("rtl", false); set(v) = prefs.edit().putBoolean("rtl", v).apply()
    private var cover get() = prefs.getBoolean("cover", true); set(v) = prefs.edit().putBoolean("cover", v).apply()
    private var scrollMs get() = prefs.getInt("ms", 500); set(v) = prefs.edit().putInt("ms", v).apply()
    private var previewPct get() = prefs.getInt("pv", 25); set(v) = prefs.edit().putInt("pv", v).apply()

    private inner class AheadLM(ctx: Context) : LinearLayoutManager(ctx) {
        override fun calculateExtraLayoutSpace(state: RecyclerView.State, extra: IntArray) {
            extra[1] = max(height, 1) * 2
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("cv", MODE_PRIVATE)
        main = findViewById(R.id.main); preview = findViewById(R.id.preview)
        mainFrame = findViewById(R.id.mainFrame); panel = findViewById(R.id.previewPanel)
        topBar = findViewById(R.id.topBar); status = findViewById(R.id.status)
        urlInput = findViewById(R.id.urlInput)
        extractor = ImageExtractor(findViewById(R.id.root))

        loader = ImageLoader.Builder(this).okHttpClient(
            OkHttpClient.Builder().addInterceptor { c ->
                val r = c.request()
                val nb = r.newBuilder().header("User-Agent", ImageExtractor.UA)
                if (pageUrl.isNotEmpty()) nb.header("Referer", pageUrl)
                CookieManager.getInstance().getCookie(r.url.toString())?.let { nb.header("Cookie", it) }
                c.proceed(nb.build())
            }.build()
        ).crossfade(false).build()

        main.layoutManager = AheadLM(this)
        preview.layoutManager = LinearLayoutManager(this)
        main.setItemViewCacheSize(4)
        main.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) = syncPreview()
        })
        mainFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> layoutPreview() }

        findViewById<View>(R.id.goBtn).setOnClickListener { open(urlInput.text.toString()) }
        urlInput.setOnEditorActionListener { _, a, _ -> if (a == EditorInfo.IME_ACTION_GO) { open(urlInput.text.toString()); true } else false }
        findViewById<View>(R.id.settingsBtn).setOnClickListener { showSettings() }
        findViewById<View>(R.id.menuBtn).setOnClickListener { topBar.visibility = if (topBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        findViewById<View>(R.id.downBtn).setOnClickListener { pageScroll(1) }
        findViewById<View>(R.id.upBtn).setOnClickListener { pageScroll(-1) }
        findViewById<View>(R.id.previewTouch).setOnClickListener { pageScroll(1) }

        val shared = intent?.dataString ?: intent?.getStringExtra(android.content.Intent.EXTRA_TEXT)
        urlInput.setText(shared ?: prefs.getString("last", ""))
        if (shared != null) open(shared)
    }

    override fun onDestroy() { extractor.cancel(); super.onDestroy() }

    override fun onKeyDown(k: Int, e: KeyEvent): Boolean = when (k) {
        KeyEvent.KEYCODE_VOLUME_DOWN -> { pageScroll(1); true }
        KeyEvent.KEYCODE_VOLUME_UP -> { pageScroll(-1); true }
        else -> super.onKeyDown(k, e)
    }

    private fun open(raw: String) {
        var u = raw.trim()
        if (u.isEmpty()) return
        if (!u.startsWith("http")) u = "https://$u"
        urlInput.setText(u); prefs.edit().putString("last", u).apply()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(urlInput.windowToken, 0)
        pageUrl = u
        status.visibility = View.VISIBLE
        if (Regex("\\.(jpe?g|png|webp|gif)(\\?.*)?$", RegexOption.IGNORE_CASE).containsMatchIn(u)) {
            urls = listOf(u); applyRows(0); status.visibility = View.GONE; return
        }
        extractor.extract(u, { status.text = it }, {
            extractor.cancel()
            urls = it
            status.text = "이미지 ${it.size}장"
            if (it.isEmpty()) status.text = "이미지를 찾지 못했습니다" else topBar.visibility = View.GONE
            applyRows(0)
        }, { status.text = "오류: $it" })
    }

    /** 설정/이미지 변경 시 어댑터 재구성. keepIdx: 유지할 첫 이미지 인덱스 */
    private fun applyRows(keepIdx: Int) {
        val two = twoPage
        val cols = if (two) 2 else 1
        val rows = ArrayList<List<String>>()
        var i = 0
        if (two && cover && urls.isNotEmpty()) { rows.add(listOf(urls[0])); i = 1 }
        while (i < urls.size) {
            val r = if (two) urls.subList(i, minOf(i + 2, urls.size)) else listOf(urls[i])
            rows.add(if (rtl && r.size == 2) r.reversed() else r)
            i += cols
        }
        val w = resources.displayMetrics.widthPixels / cols
        val syncCb = { syncPreview() }
        main.adapter = RowAdapter(loader, rows, cols, w, syncCb)
        preview.adapter = RowAdapter(loader, rows, cols, w, {})
        val pos = rows.indexOfFirst { r -> r.contains(urls.getOrNull(keepIdx)) }.coerceAtLeast(0)
        (main.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, 0)
        main.post { layoutPreview(); syncPreview() }
    }

    private fun currentFirstIdx(): Int {
        val a = main.adapter as? RowAdapter ?: return 0
        val p = (main.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
        if (p < 0) return 0
        return urls.indexOf(a.rows.getOrNull(p)?.firstOrNull())
    }

    // ---- 미리보기: 메인 현재 위치에서 한 페이지(메인 높이) 아래 영역을 축소 표시 ----
    private fun layoutPreview() {
        val total = (mainFrame.parent as View).height
        if (total == 0) return
        val ph = if (previewPct == 0 || urls.isEmpty()) 0 else total * previewPct / 100
        val lp = panel.layoutParams
        if (lp.height != ph) { lp.height = ph; panel.layoutParams = lp; return }
        val hm = mainFrame.height
        if (ph == 0 || hm == 0) { panel.visibility = View.GONE; return }
        panel.visibility = View.VISIBLE
        val plp = preview.layoutParams as FrameLayout.LayoutParams
        if (plp.height != hm || plp.width != mainFrame.width) {
            plp.width = mainFrame.width; plp.height = hm; preview.layoutParams = plp
        }
        val k = ph.toFloat() / hm
        preview.pivotX = mainFrame.width / 2f; preview.pivotY = 0f
        preview.scaleX = k; preview.scaleY = k
    }

    private fun syncPreview() {
        if (panel.visibility != View.VISIBLE) return
        val hm = main.height
        for (i in 0 until main.childCount) {
            val c = main.getChildAt(i)
            if (c.top <= hm && hm < c.bottom) {
                val p = main.getChildAdapterPosition(c)
                val off = c.top - hm
                val cur = (preview.layoutManager as LinearLayoutManager).findViewByPosition(p)
                if (cur == null || cur.top != off)
                    (preview.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(p, off)
                return
            }
        }
    }

    // ---- 1페이지 스크롤(부드러움 = 애니메이션 시간) ----
    private fun pageScroll(dir: Int) {
        val dist = (main.height * 0.95f).toInt() * dir
        anim?.cancel()
        val ms = scrollMs
        if (ms == 0) { main.scrollBy(0, dist); return }
        var last = 0f
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ms.toLong(); interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Float
                main.scrollBy(0, ((v - last) * dist).toInt().let { d -> if (v >= 1f) (dist - (last * dist).toInt()) else d })
                last = v
            }
            start()
        }
    }

    private fun showSettings() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        fun sw(t: String, v: Boolean, f: (Boolean) -> Unit) = Switch(this).apply { text = t; isChecked = v; setOnCheckedChangeListener { _, c -> f(c) } }.also { box.addView(it) }
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
        var changed = false
        sw("두쪽 보기", twoPage) { twoPage = it; changed = true }
        sw("오른쪽→왼쪽 (만화책 방식)", rtl) { rtl = it; changed = true }
        sw("첫 장 단독(표지)", cover) { cover = it; changed = true }
        bar("스크롤 부드러움(애니메이션) ", 20, scrollMs / 100, { if (it == 0) "즉시" else "${it * 100}ms" }) { scrollMs = it * 100 }
        bar("다음 페이지 미리보기 높이 ", 40, previewPct, { if (it == 0) "끔" else "$it%" }) { previewPct = it; changed = true }
        AlertDialog.Builder(this).setTitle("설정").setView(box).setPositiveButton("확인") { _, _ ->
            if (changed && urls.isNotEmpty()) { val k = currentFirstIdx().coerceAtLeast(0); applyRows(k) }
        }.show()
    }
}
