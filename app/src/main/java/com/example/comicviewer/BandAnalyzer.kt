package com.example.comicviewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 이미지를 미리 내려받아 '단색 띠'(거의 검은 연속 행) 위치를 이미지 높이 대비 비율(0~1)로 저장.
 * 완전한 검정이 아니어도(JPEG 잡음 등) tol 이하의 어두운 픽셀이 대부분이면 검은 행으로 판정.
 * 내려받아 축소한 비트맵은 LRU로 보관해, 밝기/영역 설정을 바꿔도 다시 내려받지 않고 재분석.
 */
class BandAnalyzer {
    private val pool = Executors.newFixedThreadPool(2)
    private val cache = ConcurrentHashMap<String, List<FloatArray>>() // key -> [[startFrac, endFrac], ...]
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val failures = ConcurrentHashMap<String, LongArray>() // key -> [실패 횟수, 마지막 실패 시각]
    private val bitmaps = object : LruCache<String, Bitmap>(64 * 1024 * 1024) { // RGB_565 축소본, 최대 64MB
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun key(url: String, tol: Int, left: Int, width: Int) = "$tol|$left|$width|$url"
    fun cached(url: String, tol: Int, left: Int, width: Int): List<FloatArray>? = cache[key(url, tol, left, width)]
    fun analyzedCount() = cache.size
    fun shutdown() { pool.shutdownNow() }

    /** left/width: 검은색 판정에 쓸 가로 영역(이미지 너비 대비 %). 오른쪽 워터마크 등을 제외하려고 사용 */
    fun enqueue(url: String, referer: String, tol: Int, left: Int, width: Int) {
        val k = key(url, tol, left, width)
        if (cache.containsKey(k)) return
        failures[k]?.let { if (it[0] >= 3 || System.currentTimeMillis() - it[1] < 30_000) return } // 일시적 실패는 30초 뒤 최대 3회까지 재시도
        if (pool.isShutdown || !pending.add(k)) return
        try { pool.execute {
            try {
                cache[k] = bands(bitmapFor(url, referer), tol, left, width)
                failures.remove(k)
            } catch (_: Throwable) {
                val f = failures.getOrPut(k) { longArrayOf(0, 0) }; f[0]++; f[1] = System.currentTimeMillis()
            } finally { pending.remove(k) }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { pending.remove(k) }
    }

    /** 결과만 비움(내려받은 비트맵은 유지) */
    fun clear() { cache.clear(); failures.clear() }

    private fun bitmapFor(url: String, referer: String): Bitmap {
        bitmaps.get(url)?.let { if (!it.isRecycled) return it }
        val bytes = download(url, referer)
        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
        val w = opt.outWidth; val h = opt.outHeight
        if (w <= 0 || h <= 0) throw IllegalStateException("decode")
        var sample = 1
        while (w / sample > 400 || h / sample > 12000) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.RGB_565
        }) ?: throw IllegalStateException("decode")
        bitmaps.put(url, bmp)
        return bmp
    }

    /** http→https 등 리다이렉트를 직접 따라가고, 응답 코드/크기(25MB)를 읽는 도중에 검사 */
    private fun download(url0: String, referer: String): ByteArray {
        var url = url0
        for (hop in 0..5) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10000; conn.readTimeout = 20000
            conn.setRequestProperty("User-Agent", ImageUa)
            if (referer.isNotEmpty()) conn.setRequestProperty("Referer", referer)
            CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw IllegalStateException("redirect")
                url = URL(URL(url), loc).toString(); conn.disconnect(); continue
            }
            if (code != 200) { conn.disconnect(); throw IllegalStateException("http $code") }
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            conn.inputStream.use { ins ->
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > 25_000_000) throw IllegalStateException("too big")
                }
            }
            return out.toByteArray()
        }
        throw IllegalStateException("too many redirects")
    }

    /**
     * 단색 띠 검출: 가로 판정 영역 안의 한 줄이 '거의 한 색'(97% 이상의 픽셀이 줄 평균색과 tol% 이내)이고,
     * 연속된 줄의 평균색이 띠 시작 줄의 색과 tol% 이내면 같은 띠로 묶음. 검정/흰색/그 외 단색 모두 해당.
     */
    private fun bands(bmp: Bitmap, tolPct: Int, left: Int, width: Int): List<FloatArray> {
        val bw = bmp.width; val bh = bmp.height
        val row = IntArray(bw)
        val x0 = (bw * left / 100f).toInt().coerceIn(0, bw - 1)
        val x1 = (x0 + (bw * width / 100f).toInt()).coerceIn(x0 + 1, bw)
        val n = x1 - x0
        val tol = tolPct * 255 / 100f
        val out = ArrayList<FloatArray>()
        var start = -1
        var rr = 0f; var rg = 0f; var rb = 0f // 현재 띠의 기준색(시작 줄 평균)
        for (y in 0..bh) {
            var blank = false; var mr = 0f; var mg = 0f; var mb = 0f
            if (y < bh) {
                bmp.getPixels(row, 0, bw, 0, y, bw, 1)
                var sr = 0L; var sg = 0L; var sb = 0L
                for (x in x0 until x1) { val p = row[x]; sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255 }
                mr = sr.toFloat() / n; mg = sg.toFloat() / n; mb = sb.toFloat() / n
                var near = 0
                for (x in x0 until x1) {
                    val p = row[x]
                    if (Math.abs(((p shr 16) and 255) - mr) <= tol && Math.abs(((p shr 8) and 255) - mg) <= tol && Math.abs((p and 255) - mb) <= tol) near++
                }
                blank = near >= n * 0.97f
                if (blank && start >= 0 && (Math.abs(mr - rr) > tol || Math.abs(mg - rg) > tol || Math.abs(mb - rb) > tol)) {
                    // 색이 달라지면 이전 띠를 끝내고 이 줄에서 새 띠 시작
                    if (y - start >= 2) out.add(floatArrayOf(start.toFloat() / bh, y.toFloat() / bh))
                    start = y; rr = mr; rg = mg; rb = mb
                    continue
                }
            }
            if (blank && start < 0) { start = y; rr = mr; rg = mg; rb = mb }
            else if (!blank && start >= 0) {
                if (y - start >= 2) out.add(floatArrayOf(start.toFloat() / bh, y.toFloat() / bh))
                start = -1
            }
        }
        return out
    }

    companion object {
        const val ImageUa = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
