package com.example.dualposkiosk

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

class SecondaryDisplay(context: Context, display: Display, private val url: String) : Presentation(context, display) {
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(context).apply {
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                allowFileAccess = true
                loadWithOverviewMode = true
                useWideViewPort = true
            }

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val cached = WebAssetCache.interceptRequest(request)
                    if (cached != null) return cached
                    return super.shouldInterceptRequest(view, request)
                }
            }
            loadUrl(this@SecondaryDisplay.url)
        }
        setContentView(webView)
    }

    fun updateUrl(newUrl: String) {
        if (::webView.isInitialized) {
            webView.loadUrl(newUrl)
        }
    }
}