package com.example.comicviewer

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray

/** 숨김 WebView로 페이지를 로드 → 끝까지 자동 스크롤(lazy-load 유도) → img URL 수집 */
class ImageExtractor(private val host: ViewGroup) {
    private val handler = Handler(Looper.getMainLooper())
    private var web: WebView? = null

    companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        private const val COLLECT_JS = """(function(){var o=[],s={};
document.querySelectorAll('img').forEach(function(i){
 var u=i.getAttribute('data-src')||i.getAttribute('data-original')||i.getAttribute('data-lazy-src')||i.currentSrc||i.src;
 if(!u||u.indexOf('data:')==0)return;
 try{u=new URL(u,location.href).href}catch(e){return}
 var w=i.naturalWidth||0,h=i.naturalHeight||0;
 if(w>0&&(w<200||h<200))return;
 if(s[u])return;s[u]=1;o.push(u)});
return JSON.stringify(o)})()"""
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        web?.let { host.removeView(it); it.destroy() }
        web = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun extract(url: String, onStatus: (String) -> Unit, onDone: (List<String>) -> Unit, onError: (String) -> Unit) {
        cancel()
        val w = WebView(host.context)
        web = w
        w.alpha = 0f
        w.settings.javaScriptEnabled = true
        w.settings.domStorageEnabled = true
        w.settings.userAgentString = UA
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        host.addView(w, 0, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        w.webViewClient = object : WebViewClient() {
            override fun onPageFinished(v: WebView, u: String) {
                handler.postDelayed({ autoScroll(v, 0, 0, 0, onStatus, onDone) }, 800)
            }
            override fun onReceivedError(v: WebView, c: Int, d: String?, f: String?) {
                if (f == url) onError(d ?: "로드 실패")
            }
        }
        onStatus("페이지 로딩 중...")
        w.loadUrl(url)
    }

    private fun autoScroll(v: WebView, step: Int, lastH: Int, stable: Int, onStatus: (String) -> Unit, onDone: (List<String>) -> Unit) {
        if (web !== v) return
        v.evaluateJavascript("window.scrollBy(0,window.innerHeight*0.9);document.documentElement.scrollHeight") { r ->
            val h = r?.toIntOrNull() ?: 0
            val st = if (h == lastH) stable + 1 else 0
            onStatus("이미지 탐색 중... ${step + 1}")
            if (st >= 4 || step >= 150) {
                v.evaluateJavascript(COLLECT_JS) { json ->
                    val s = JSONArray(JSONArrayUnquote.unquote(json))
                    onDone(List(s.length()) { s.getString(it) })
                }
            } else handler.postDelayed({ autoScroll(v, step + 1, h, st, onStatus, onDone) }, 250)
        }
    }
}

private object JSONArrayUnquote {
    /** evaluateJavascript는 문자열 결과를 JSON 문자열로 한 번 더 감싸 반환 */
    fun unquote(s: String?): String =
        if (s == null || s == "null") "[]" else org.json.JSONTokener(s).nextValue() as? String ?: "[]"
}
