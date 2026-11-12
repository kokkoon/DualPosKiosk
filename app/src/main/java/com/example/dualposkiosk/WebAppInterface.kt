package com.example.dualposkiosk

import android.content.Context
import android.webkit.JavascriptInterface
import android.widget.Toast
import android.os.Handler
import android.os.Looper
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import org.json.JSONObject

class WebAppInterface(private val context: Context) {

    @JavascriptInterface
    fun silentPrint(htmlContent: String) {
        val prefs = context.getSharedPreferences("KioskSettings", Context.MODE_PRIVATE)
        val isSilentMode = prefs.getBoolean("silent_mode", false)
        val printerPath = prefs.getString("printer_path", "")
        val printerType = prefs.getString("printer_type", "Network")

        Handler(Looper.getMainLooper()).post {
            val mainActivity = context as? MainActivity
            val hasPathIfRequired = if (printerType == "USB") true else !printerPath.isNullOrBlank()

            if (isSilentMode && hasPathIfRequired) {
                Toast.makeText(context, "Silent Print request ($printerType)", Toast.LENGTH_SHORT).show()
                mainActivity?.printHtmlSilently(htmlContent, printerPath ?: "")
            } else {
                Toast.makeText(context, "Print request (System Dialog)", Toast.LENGTH_SHORT).show()
                mainActivity?.printHtml(htmlContent)
            }
        }
    }

    /**
     * Web page can invoke window.androidKiosk.reloadPage() or window.posLauncher.reloadPage()
     * to trigger a clean reload of the active page without user interruption.
     */
    @JavascriptInterface
    fun reloadPage() {
        Handler(Looper.getMainLooper()).post {
            val mainActivity = context as? MainActivity
            mainActivity?.reloadKioskWebViews()
        }
    }

    /**
     * Optional reload to a specific URL (e.g. switching stations or resetting to home)
     */
    @JavascriptInterface
    fun loadUrl(url: String) {
        Handler(Looper.getMainLooper()).post {
            val mainActivity = context as? MainActivity
            mainActivity?.loadUrlInMainWebView(url)
        }
    }

    /**
     * Controls the 7-color USB Alarm Light directly from web JavaScript!
     * color: "red", "yellow", "green", "white", "cyan", "purple", "blue", "off"
     * mode: "solid", "slow", "fast"
     * buzzer: true / false
     */
    @JavascriptInterface
    fun setAlarmLight(color: String, mode: String = "solid", buzzer: Boolean = false) {
        val c = color.lowercase().trim()
        val m = mode.lowercase().trim()

        when (c) {
            "off" -> UsbAlarmLightManager.turnOff(context)
            "red" -> when (m) {
                "fast" -> UsbAlarmLightManager.flashFastRed(context, buzzer)
                "slow" -> UsbAlarmLightManager.flashSlowRed(context, buzzer)
                else -> UsbAlarmLightManager.solidRed(context, buzzer)
            }
            "yellow" -> when (m) {
                "fast" -> UsbAlarmLightManager.flashFastYellow(context)
                "slow" -> UsbAlarmLightManager.flashSlowYellow(context)
                else -> UsbAlarmLightManager.solidYellow(context)
            }
            "green" -> when (m) {
                "fast" -> UsbAlarmLightManager.flashFastGreen(context)
                "slow" -> UsbAlarmLightManager.flashSlowGreen(context)
                else -> UsbAlarmLightManager.solidGreen(context)
            }
            "blue" -> when (m) {
                "fast" -> UsbAlarmLightManager.flashFastBlue(context)
                "slow" -> UsbAlarmLightManager.flashSlowBlue(context)
                else -> UsbAlarmLightManager.solidBlue(context)
            }
            "white" -> UsbAlarmLightManager.solidWhite(context)
            "cyan" -> UsbAlarmLightManager.solidCyan(context)
            "purple" -> UsbAlarmLightManager.solidPurple(context)
            "buzzer_on" -> UsbAlarmLightManager.buzzerOn(context)
            "buzzer_off" -> UsbAlarmLightManager.buzzerOff(context)
            else -> UsbAlarmLightManager.turnOff(context)
        }
    }

    /**
     * Web page calls this to check if a hardware EDC terminal is attached and ready.
     * Returns JSON: { "enabled": true/false, "status": "READY"|"DISCONNECTED"|"DISABLED", "model": "..." }
     */
    @JavascriptInterface
    fun getEdcStatus(): String {
        return HexaEcrDriver.getStatus(context).toString()
    }

    /**
     * Web page calls this to initiate a payment on the physical EDC terminal.
     * @param jsonPayload { "amount": 50000, "transType": "SALE"|"QRIS", "invoiceNo": "INV-123" }
     */
    @JavascriptInterface
    fun initEdcPayment(jsonPayload: String) {
        val mainActivity = context as? MainActivity
        HexaEcrDriver.processPayment(context, jsonPayload) { resultJson ->
            Handler(Looper.getMainLooper()).post {
                // Deliver result back to the webview JavaScript callback: window.onEdcPaymentResult(resultJson)
                val safeJs = "if (window.onEdcPaymentResult) { window.onEdcPaymentResult(${JSONObject.quote(resultJson)}); }"
                mainActivity?.runJavaScript(safeJs)
            }
        }
    }

    private fun isPrinter(device: UsbDevice): Boolean {
        // Standard USB Printer Class is 7
        return device.deviceClass == 7 || device.getInterface(0).interfaceClass == 7
    }
}