package com.carlos.fblive

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Ventana flotante con una página de Facebook (el chat de tu directo) para leer los comentarios sin salir de la app.
 * Solo se ve en tu pantalla: no se mezcla con el video que sale en vivo.
 */
class ChatPanel(private val a: MainActivity, private val root: ViewGroup) {

    private var box: LinearLayout? = null
    private var web: WebView? = null
    private var loadedUrl: String? = null

    val visible: Boolean get() = box?.visibility == View.VISIBLE

    fun toggle(url: String) {
        if (visible) hide() else show(url)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun show(url: String) {
        val target = url.trim().ifEmpty { AppSettings.DEFAULT_CHAT_URL }
        if (box == null) build()
        val w = web ?: return
        if (loadedUrl != target) {
            loadedUrl = target
            w.loadUrl(target)
        }
        box?.visibility = View.VISIBLE
    }

    fun hide() {
        box?.visibility = View.GONE
    }

    fun reload() {
        web?.reload()
    }

    fun destroy() {
        web?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        web = null
        box = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun build() {
        val portrait = a.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val screen = a.resources.displayMetrics
        val lp = if (portrait) {
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (screen.heightPixels * 0.42f).toInt()).apply {
                gravity = Gravity.BOTTOM
                bottomMargin = a.dp(150)
                marginStart = a.dp(8)
                marginEnd = a.dp(8)
            }
        } else {
            FrameLayout.LayoutParams((screen.widthPixels * 0.40f).toInt(), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.END
                topMargin = a.dp(56)
                bottomMargin = a.dp(150)
                marginEnd = a.dp(8)
            }
        }
        val frame = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xE610131F.toInt())
            elevation = a.dp(8).toFloat()
        }
        val header = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(a.dp(12), a.dp(2), a.dp(4), a.dp(2))
        }
        header.addView(TextView(a).apply {
            text = "Chat de Facebook"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun action(label: String, onClick: () -> Unit) = TextView(a).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(a.dp(12), a.dp(8), a.dp(12), a.dp(8))
            setOnClickListener { onClick() }
        }
        header.addView(action("⟳") { reload() })
        header.addView(action("✕") { hide(); a.refreshQuickRow() })
        frame.addView(header)

        val w = WebView(a).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webViewClient = WebViewClient()
        }
        frame.addView(w, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(frame, lp)
        box = frame
        web = w
    }
}
