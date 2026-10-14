package com.example.ekeysdk

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Message
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

internal class EkeyWebViewController(
    context: Context,
    private val onCompleted: (Uri, String, EkeyIdentityData) -> Unit,
    private val onFailed: (EkeyLoginError) -> Unit,
    private val onLoadingChanged: (Boolean) -> Unit = {}
) {
    private val brandColor = Color.parseColor("#3B9DC8")
    private val density = context.resources.displayMetrics.density
    private fun dp(value: Int): Int = (value * density).toInt()

    val webView: WebView = WebView(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(Color.WHITE)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
    }

    // ---------- Loader: three bouncing dots + status text ----------

    private val dots: List<View> = List(3) {
        View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(brandColor)
            }
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply {
                setMargins(dp(5), 0, dp(5), 0)
            }
        }
    }

    private val dotAnimators: List<ObjectAnimator> = dots.mapIndexed { index, dot ->
        ObjectAnimator.ofFloat(dot, View.TRANSLATION_Y, 0f, -dp(12).toFloat()).apply {
            duration = 350
            startDelay = index * 120L // stagger so the dots wave
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
    }

    /** Full-screen white overlay that covers the WebView while loading. */
    private val loaderOverlay: FrameLayout = FrameLayout(context).apply {
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(Color.WHITE)
        isClickable = true // block taps on the WebView while loading
        visibility = View.VISIBLE

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )

            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, 0)
                clipToPadding = false   // let dots draw into the padding while bouncing
                clipChildren = false
                dots.forEach { addView(it) }
            })

            addView(TextView(context).apply {
                text = "Connecting to eKey…"
                setTextColor(Color.parseColor("#6B7280"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(16) }
            })
        })
    }

    /**
     * Add THIS to your screen (not `webView`). WebView at the bottom, loader on top.
     */
    val view: FrameLayout = FrameLayout(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(Color.WHITE)
        addView(webView)       // bottom
        addView(loaderOverlay) // top
    }

    private var didOpenEkeyApp = false
    private var expectedState: String? = null
    private var codeVerifier: String? = null
    private var isLoading = false

    init {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return intercept(request.url)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                setLoading(true)
            }

            /** Fires once the new page's content is painted — earlier than onPageFinished. */
            override fun onPageCommitVisible(view: WebView, url: String?) {
                setLoading(false)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                setLoading(false)
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) setLoading(false)
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            /**
             * The eKey login page opens `mobileLogin` via `window.open()`/`target="_blank"`
             * rather than a same-frame navigation, so it routes through onCreateWindow instead
             * of shouldOverrideUrlLoading. We spin up a throwaway WebView just to capture the
             * target URL from its first navigation request, then hand it to intercept()
             * ourselves and discard it — Android has no way to read the URL from onCreateWindow
             * directly.
             */
            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message
            ): Boolean {
                val throwaway = WebView(view.context)
                throwaway.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        popupView: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        intercept(request.url)
                        return true
                    }
                }
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = throwaway
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    fun start() {
        didOpenEkeyApp = false
        setLoading(true)
        val request = EkeyLoginConfig.makeAuthorizationRequest()
        expectedState = request.state
        codeVerifier = request.codeVerifier
        webView.loadUrl(request.uri.toString())
    }

    /**
     * Called when the app resumes via the `focus_uri` custom scheme. The WebView instance
     * (and its session cookies) is kept alive the whole time, so reloading it lets the
     * server-side OIDC interaction continue from consented-login through to redirect_uri,
     * instead of losing the session the way a torn-down WebView would.
     */
    fun resume() {
        setLoading(true)
        webView.reload()
    }

    /** Call from the Activity's onDestroy. Stops the infinite animations and frees the WebView. */
    fun release() {
        dotAnimators.forEach { it.cancel() }
        loaderOverlay.animate().cancel()
        webView.destroy()
    }

    /** Single source of truth for the loader; always applied on the main thread. */
    private fun setLoading(loading: Boolean) {
        if (isLoading == loading) return
        isLoading = loading
        view.post {
            if (loading) showLoader() else hideLoader()
            onLoadingChanged(loading)
        }
    }

    private fun showLoader() {
        loaderOverlay.animate().cancel()
        loaderOverlay.alpha = 1f
        loaderOverlay.visibility = View.VISIBLE
        dotAnimators.forEach { if (!it.isStarted) it.start() }
    }

    private fun hideLoader() {
        loaderOverlay.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction {
                loaderOverlay.visibility = View.GONE
                dotAnimators.forEach { it.cancel() }
                dots.forEach { it.translationY = 0f }
            }
            .start()
    }

    /** Returns true if the URL was handed off and should not be loaded by the WebView itself. */
    private fun intercept(url: Uri): Boolean {
        val urlString = url.toString()

        if (urlString.startsWith(EkeyLoginConfig.REDIRECT_URI)) {
            val verifier = codeVerifier
            val code = url.getQueryParameter("code")
            if (!stateMatches(url) || verifier == null || code == null) {
                setLoading(false)
                onFailed(EkeyLoginError.StateMismatch)
                return true
            }

            // No page loads during token exchange + KYC fetch, so keep the loader up explicitly.
            setLoading(true)
            EkeyTokenExchange.exchangeAndFetchKyc(code = code, codeVerifier = verifier) { result ->
                setLoading(false)
                view.post {
                    when (result) {
                        is EkeyTokenExchangeResult.Success ->
                            onCompleted(url, verifier, result.identity)
                        is EkeyTokenExchangeResult.Failure ->
                            onFailed(EkeyLoginError.TokenExchangeFailed(result.error))
                    }
                }
            }
            return true
        }

        if (!didOpenEkeyApp && EkeyLoginConfig.EKEY_APP_TO_APP_DOMAINS.any { urlString.contains(it) }) {
            didOpenEkeyApp = true
            try {
                webView.context.startActivity(Intent(Intent.ACTION_VIEW, url))
            } catch (e: Exception) {
                // No app/browser available to handle the URL; nothing further to do.
            }
            return true
        }

        return false
    }

    /**
     * Guards against CSRF/session-mixup: redirect_uri's `state` must match the one generated for
     * this specific authorization request.
     */
    private fun stateMatches(url: Uri): Boolean {
        val expected = expectedState ?: return false
        return url.getQueryParameter("state") == expected
    }
}