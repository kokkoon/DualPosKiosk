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

class WebAppInterface(private val context: Context) {

    @JavascriptInterface
    fun silentPrint(htmlContent: String) {
        val prefs = context.getSharedPreferences("KioskSettings", Context.MODE_PRIVATE)
        val isSilentMode = prefs.getBoolean("silent_mode", false)
        val printerPath = prefs.getString("printer_path", "")
        val printerType = prefs.getString("printer_type", "Network")

        Handler(Looper.getMainLooper()).post {
            val mainActivity = context as? MainActivity
            if (isSilentMode && !printerPath.isNullOrBlank()) {
                if (printerType == "Network") {
                    Toast.makeText(context, "Sending Silent Print to $printerPath...", Toast.LENGTH_SHORT).show()
                    mainActivity?.printHtmlSilently(htmlContent, printerPath)
                } else {
                    // Fallback for USB/BT until specific drivers added
                    Toast.makeText(context, "Direct Silent via $printerType not yet fully implemented. Falling back.", Toast.LENGTH_SHORT).show()
                    mainActivity?.printHtml(htmlContent)
                }
            } else {
                // Not in silent mode or not configured, show the modal
                mainActivity?.printHtml(htmlContent)
            }
        }
    }

    private fun isPrinter(device: UsbDevice): Boolean {
        // Standard USB Printer Class is 7
        return device.deviceClass == 7 || device.getInterface(0).interfaceClass == 7
    }
}