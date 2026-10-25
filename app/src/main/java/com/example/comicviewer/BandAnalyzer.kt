package com.example.comicviewer

import android.graphics.BitmapFactory
import android.webkit.CookieManager
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 이미지를 미리 내려받아 '검은 띠'(거의 검은 연속 행) 위치를 이미지 높이 대비 비율(0~1)로 저장.
 * 완전한 검정이 아니어도(JPEG 잡음 등) level 이하의 어두운 픽셀이 대부분이면 검은 행으로 판정.
 */
class BandAnalyzer {
    private val pool = Executors.newFixedThreadPool(2)
    private val cache = ConcurrentHashMap<String, List<FloatArray>>() // key -> [[startFrac, endFrac], ...]
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val failed = ConcurrentHashMap.newKeySet<String>()

    private fun key(url: String, level: Int) = "$level|$url"
    fun cached(url: String, level: Int): List<FloatArray>? = cache[key(url, level)]
    fun analyzedCount() = cache.size

    fun enqueue(url: String, referer: String, level: Int) {
        val k = key(url, level)
        if (cache.containsKey(k) || failed.contains(k) || !pending.add(k)) return
        pool.execute {
            try { cache[k] = analyze(url, referer, level) } catch (_: Throwable) { failed.add(k) } finally { pending.remove(k) }
        }
    }

    fun clear() { cache.clear(); failed.clear() }

    private fun analyze(url: String, referer: String, level: Int): List<FloatArray> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 20000
        conn.setRequestProperty("User-Agent", ImageUa)
        if (referer.isNotEmpty()) conn.setRequestProperty("Referer", referer)
        CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }
        val bytes = conn.inputStream.use { it.readBytes() }
        if (bytes.size > 25_000_000) throw IllegalStateException("too big")
        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
        val w = opt.outWidth; val h = opt.outHeight
        if (w <= 0 || h <= 0) throw IllegalStateException("decode")
        var sample = 1
        while (w / sample > 400 || h / sample > 12000) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("decode")
        val bw = bmp.width; val bh = bmp.height
        val row = IntArray(bw)
        val bands = ArrayList<FloatArray>()
        var start = -1
        for (y in 0..bh) {
            var black = false
            if (y < bh) {
                bmp.getPixels(row, 0, bw, 0, y, bw, 1)
                var dark = 0
                for (p in row) if (((p shr 16) and 255) <= level && ((p shr 8) and 255) <= level && (p and 255) <= level) dark++
                black = dark >= bw * 0.97f
            }
            if (black && start < 0) start = y
            else if (!black && start >= 0) {
                if (y - start >= 2) bands.add(floatArrayOf(start.toFloat() / bh, y.toFloat() / bh))
                start = -1
            }
        }
        bmp.recycle()
        return bands
    }

    companion object {
        const val ImageUa = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}
