package com.example.dualposkiosk

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Lightweight embedded HTTP Server listening on port 8080 (or custom).
 * Exposes:
 * - GET  /status -> JSON status of server and USB printer
 * - POST /print  -> Accepts JSON payload with { "html": "..." } or { "raw": "base64..." } or { "text": "..." }
 */
class KioskHttpServer(
    private val context: Context,
    private val port: Int = 8080,
    private val onPrintHtmlRequest: (html: String) -> Unit,
    private val onPrintRawBytes: (bytes: ByteArray) -> Boolean
) {

    private val TAG = "KioskHttpServer"
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        thread(name = "KioskHttpServerThread") {
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "Server started on port $port")
                while (isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        thread { handleClient(clientSocket) }
                    } catch (e: Exception) {
                        if (!isRunning) break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {}
        serverSocket = null
        Log.i(TAG, "Server stopped")
    }

    fun isServerRunning(): Boolean = isRunning

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8))

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val path = parts[1]

            // Parse headers
            var contentLength = 0
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrBlank()) break
                val header = line!!
                if (header.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = header.substringAfter(":").trim().toIntOrNull() ?: 0
                }
            }

            if (method == "OPTIONS") {
                sendResponse(output, 204, "No Content", "text/plain", "")
                return
            }

            when {
                path == "/status" && method == "GET" -> {
                    val statusJson = JSONObject().apply {
                        put("status", "ok")
                        put("port", port)
                        put("ip", getDeviceIpAddress(context))
                        put("serverRole", true)
                    }.toString()
                    sendResponse(output, 200, "OK", "application/json", statusJson)
                }

                path == "/print" && method == "POST" -> {
                    // Read request body
                    val bodyBytes = ByteArray(contentLength)
                    var readTotal = 0
                    while (readTotal < contentLength) {
                        val read = input.read(bodyBytes, readTotal, contentLength - readTotal)
                        if (read == -1) break
                        readTotal += read
                    }
                    val body = String(bodyBytes, Charsets.UTF_8)

                    val success = processPrintPayload(body)
                    if (success) {
                        val resp = JSONObject().apply {
                            put("success", true)
                            put("message", "Print job dispatched successfully")
                        }.toString()
                        sendResponse(output, 200, "OK", "application/json", resp)
                    } else {
                        val err = JSONObject().apply {
                            put("success", false)
                            put("error", "No valid 'html', 'raw' (base64), or 'text' payload found")
                        }.toString()
                        sendResponse(output, 400, "Bad Request", "application/json", err)
                    }
                }

                else -> {
                    sendResponse(output, 404, "Not Found", "application/json", "{\"error\":\"Not Found\"}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error handling client: ${e.message}")
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun processPrintPayload(body: String): Boolean {
        if (body.isBlank()) return false
        return try {
            val json = JSONObject(body)
            when {
                // 1. HTML content (renders via WebView and ESC/POS raster)
                json.has("html") -> {
                    val html = json.getString("html")
                    Handler(Looper.getMainLooper()).post { onPrintHtmlRequest(html) }
                    true
                }
                // 2. Raw ESC/POS bytes encoded in Base64
                json.has("raw") -> {
                    val base64Data = json.getString("raw")
                    val rawBytes = Base64.decode(base64Data, Base64.DEFAULT)
                    onPrintRawBytes(rawBytes)
                }
                // 3. Plain text
                json.has("text") -> {
                    val text = json.getString("text")
                    val plainHtml = "<html><body style='font-family:monospace;white-space:pre-wrap;'>$text</body></html>"
                    Handler(Looper.getMainLooper()).post { onPrintHtmlRequest(plainHtml) }
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            // If body is raw plain HTML (not JSON)
            if (body.contains("<html", ignoreCase = true) || body.contains("<div", ignoreCase = true)) {
                Handler(Looper.getMainLooper()).post { onPrintHtmlRequest(body) }
                true
            } else {
                false
            }
        }
    }

    private fun sendResponse(out: OutputStream, code: Int, msg: String, contentType: String, content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $code $msg\r\n" +
                "Content-Type: $contentType; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Access-Control-Allow-Headers: Content-Type, Authorization\r\n\r\n"
        out.write(header.toByteArray(Charsets.UTF_8))
        if (bytes.isNotEmpty()) {
            out.write(bytes)
        }
        out.flush()
    }

    companion object {
        fun getDeviceIpAddress(context: Context): String {
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val intf = interfaces.nextElement()
                    val addrs = intf.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            val ip = addr.hostAddress ?: ""
                            if (ip.startsWith("192.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                                return ip
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            return "127.0.0.1"
        }
    }
}
