package com.example.dualposkiosk

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.concurrent.thread

object WebAssetCache {

    private const val TAG = "WebAssetCache"
    private const val CACHE_DIR_NAME = "pos_asset_cache"
    private const val MAX_CACHE_SIZE_BYTES = 250L * 1024 * 1024 // 250 MB disk cache limit

    private var cacheDir: File? = null

    fun init(context: Context) {
        val dir = File(context.cacheDir, CACHE_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        cacheDir = dir
        trimCacheIfNeeded()
    }

    private fun getMimeType(url: String): String? {
        val cleanUrl = url.substringBefore('?').substringBefore('#').lowercase()
        return when {
            cleanUrl.endsWith(".png") -> "image/png"
            cleanUrl.endsWith(".jpg") || cleanUrl.endsWith(".jpeg") -> "image/jpeg"
            cleanUrl.endsWith(".webp") -> "image/webp"
            cleanUrl.endsWith(".svg") -> "image/svg+xml"
            cleanUrl.endsWith(".gif") -> "image/gif"
            cleanUrl.endsWith(".ico") -> "image/x-icon"
            cleanUrl.endsWith(".woff2") -> "font/woff2"
            cleanUrl.endsWith(".woff") -> "font/woff"
            cleanUrl.endsWith(".ttf") -> "font/ttf"
            cleanUrl.endsWith(".otf") -> "font/otf"
            else -> null
        }
    }

    private fun isStaticAsset(url: String): Boolean {
        return getMimeType(url) != null
    }

    private fun hashUrl(url: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val digest = md.digest(url.toByteArray())
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            url.hashCode().toString()
        }
    }

    fun interceptRequest(request: WebResourceRequest?): WebResourceResponse? {
        if (request == null || request.method.uppercase() != "GET") return null

        val uri = request.url ?: return null
        val urlStr = uri.toString()

        // Only cache static assets (images, fonts) - NEVER intercept HTML / API / dynamic pages
        val mimeType = getMimeType(urlStr) ?: return null
        val targetDir = cacheDir ?: return null

        val cacheKey = hashUrl(urlStr)
        val ext = urlStr.substringBefore('?').substringAfterLast('.', "")
        val cachedFile = File(targetDir, "$cacheKey.$ext")

        // 1. HIT: Serve directly from fast flash storage
        if (cachedFile.exists() && cachedFile.length() > 0) {
            return try {
                val stream = FileInputStream(cachedFile)
                val headers = mapOf(
                    "Access-Control-Allow-Origin" to "*",
                    "Cache-Control" to "max-age=2592000, public"
                )
                WebResourceResponse(mimeType, "UTF-8", 200, "OK", headers, stream)
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading cached file: ${e.message}")
                null
            }
        }

        // 2. MISS: Download, write to cache, and serve
        try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 10000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "DualPosKiosk-AssetCache")
                // Forward request headers
                request.requestHeaders?.forEach { (k, v) ->
                    if (!k.equals("Host", ignoreCase = true)) {
                        setRequestProperty(k, v)
                    }
                }
            }

            if (conn.responseCode == 200) {
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.isNotEmpty()) {
                    // Save asynchronously or directly to disk
                    thread {
                        try {
                            FileOutputStream(cachedFile).use { it.write(bytes) }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed saving asset to disk: ${e.message}")
                        }
                    }

                    val headers = mapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "Cache-Control" to "max-age=2592000, public"
                    )
                    return WebResourceResponse(mimeType, "UTF-8", 200, "OK", headers, ByteArrayInputStream(bytes))
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Cache miss download failed for $urlStr: ${e.message}")
        }

        // Fallback to default WebView pipeline if fetch fails
        return null
    }

    private fun trimCacheIfNeeded() {
        thread {
            try {
                val dir = cacheDir ?: return@thread
                val files = dir.listFiles() ?: return@thread
                val totalSize = files.sumOf { it.length() }

                if (totalSize > MAX_CACHE_SIZE_BYTES) {
                    // Sort by last modified (oldest first)
                    files.sortBy { it.lastModified() }
                    var currentSize = totalSize
                    for (file in files) {
                        if (currentSize <= (MAX_CACHE_SIZE_BYTES * 0.75)) break
                        val len = file.length()
                        if (file.delete()) {
                            currentSize -= len
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Cache trim error: ${e.message}")
            }
        }
    }
}
