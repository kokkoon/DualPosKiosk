package com.example.dualposkiosk

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * Driver for Hexa ECR Integration (Bank Mandiri / Ingenico / Castles EDC).
 * Implements ECR POS <-> EDC Serial Specification Ver 25.1.2.1.
 * Frame structure:
 * STX(0x02) + Version(0x01) + TransType(1B) + SubType(1B) + Data + ETX(0x03) + LRC(1B)
 * Handshake: ACK (0x06), NAK (0x15)
 */
object HexaEcrDriver {

    private const val TAG = "HexaEcrDriver"

    // Common Ingenico / Sagem / Prolific / FTDI / CP210x / CDC-ACM vendor IDs for EDC terminals
    // Ingenico Telium: 0x079B, Prolific: 0x067B, FTDI: 0x0403, CP210x: 0x10C4, CDC: Class 2
    private val KNOWN_EDC_VIDS = intArrayOf(0x079B, 0x067B, 0x0403, 0x10C4, 0x28E9, 0x0B00)

    const val MODEL_NONE = "None"
    const val MODEL_HEXA_ECR = "Hexa ECR (Bank Mandiri / Ingenico)"
    const val MODEL_MOCK = "Mock Simulator"

    @Volatile
    private var isTransactionInProgress = false

    fun findEdcDevice(context: Context): UsbDevice? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val list = usbManager.deviceList.values
        return list.firstOrNull { dev ->
            // Match known USB-serial / Telium VIDs or USB CDC communication device
            KNOWN_EDC_VIDS.contains(dev.vendorId) ||
                    dev.deviceClass == UsbConstants.USB_CLASS_COMM ||
                    (0 until dev.interfaceCount).any { dev.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_COMM }
        }
    }

    fun getStatus(context: Context): JSONObject {
        val prefs = context.getSharedPreferences("KioskSettings", Context.MODE_PRIVATE)
        val selectedModel = prefs.getString("edc_model", MODEL_NONE) ?: MODEL_NONE

        val json = JSONObject()
        json.put("model", selectedModel)

        when (selectedModel) {
            MODEL_NONE -> {
                json.put("enabled", false)
                json.put("status", "DISABLED")
                json.put("message", "EDC Integration disabled")
            }
            MODEL_MOCK -> {
                json.put("enabled", true)
                json.put("status", "READY")
                json.put("message", "Mock Simulator Active")
            }
            else -> {
                val dev = findEdcDevice(context)
                if (dev != null) {
                    val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                    val hasPerm = usbManager.hasPermission(dev)
                    json.put("enabled", true)
                    json.put("attached", true)
                    json.put("deviceName", dev.productName ?: "EDC Terminal")
                    json.put("hasPermission", hasPerm)
                    json.put("status", if (hasPerm) "READY" else "PERMISSION_REQUIRED")
                    json.put("message", if (hasPerm) "EDC Terminal Ready" else "USB permission required")
                } else {
                    json.put("enabled", true)
                    json.put("attached", false)
                    json.put("status", "DISCONNECTED")
                    json.put("message", "No EDC Terminal attached")
                }
            }
        }
        return json
    }

    /**
     * Executes an EDC payment transaction.
     * @param jsonPayload JSON from webview containing { "amount": 50000, "transType": "SALE"|"QRIS"|"VOID", "invoiceNo": "..." }
     * @param onResult Callback delivering JSON result back to caller
     */
    fun processPayment(
        context: Context,
        jsonPayload: String,
        onResult: (resultJson: String) -> Unit
    ) {
        val prefs = context.getSharedPreferences("KioskSettings", Context.MODE_PRIVATE)
        val selectedModel = prefs.getString("edc_model", MODEL_NONE) ?: MODEL_NONE

        if (selectedModel == MODEL_NONE) {
            onResult(makeErrorJson("EDC_DISABLED", "EDC integration is disabled in Kiosk Settings"))
            return
        }

        if (selectedModel == MODEL_MOCK) {
            processMockPayment(jsonPayload, onResult)
            return
        }

        if (isTransactionInProgress) {
            onResult(makeErrorJson("BUSY", "Another transaction is already in progress"))
            return
        }

        val dev = findEdcDevice(context)
        if (dev == null) {
            onResult(makeErrorJson("DEVICE_NOT_FOUND", "No physical EDC terminal detected on USB"))
            return
        }

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usbManager.hasPermission(dev)) {
            onResult(makeErrorJson("NO_PERMISSION", "USB permission not granted for EDC terminal"))
            return
        }

        isTransactionInProgress = true
        thread(name = "HexaEcrTransactionThread") {
            try {
                val conn = usbManager.openDevice(dev)
                if (conn == null) {
                    isTransactionInProgress = false
                    onResult(makeErrorJson("OPEN_FAILED", "Failed to open USB connection to EDC"))
                    return@thread
                }

                // Locate IN and OUT endpoints
                var inEndpoint: UsbEndpoint? = null
                var outEndpoint: UsbEndpoint? = null
                var targetInterface: UsbInterface? = null

                for (i in 0 until dev.interfaceCount) {
                    val uif = dev.getInterface(i)
                    for (e in 0 until uif.endpointCount) {
                        val ep = uif.getEndpoint(e)
                        if (ep.direction == UsbConstants.USB_DIR_IN && inEndpoint == null) {
                            inEndpoint = ep
                        }
                        if (ep.direction == UsbConstants.USB_DIR_OUT && outEndpoint == null) {
                            outEndpoint = ep
                        }
                    }
                    if (inEndpoint != null && outEndpoint != null) {
                        targetInterface = uif
                        break
                    }
                }

                if (targetInterface == null || inEndpoint == null || outEndpoint == null) {
                    conn.close()
                    isTransactionInProgress = false
                    onResult(makeErrorJson("ENDPOINT_ERROR", "Cannot locate communication endpoints on EDC"))
                    return@thread
                }

                conn.claimInterface(targetInterface, true)

                // Build request frame from JSON
                val payloadObj = try { JSONObject(jsonPayload) } catch (_: Exception) { JSONObject() }
                val transType = payloadObj.optString("transType", "SALE").uppercase()
                val amount = payloadObj.optLong("amount", 0L)

                val reqFrame = buildHexaRequestPacket(transType, amount)

                // 1. Send transaction request
                conn.bulkTransfer(outEndpoint, reqFrame, reqFrame.size, 5000)
                Log.d(TAG, "Sent ${reqFrame.size} bytes to EDC")

                // 2. Read ACK from EDC (0x06)
                val ackBuffer = ByteArray(8)
                val ackLen = conn.bulkTransfer(inEndpoint, ackBuffer, ackBuffer.size, 5000)
                if (ackLen <= 0 || ackBuffer[0] != 0x06.toByte()) {
                    Log.w(TAG, "Did not receive ACK from EDC (len=$ackLen)")
                }

                // 3. Wait for Transaction Response packet from EDC (Timeout: 90s for card insert/PIN/bank reply)
                val respBytes = ByteArray(1024)
                val respLen = conn.bulkTransfer(inEndpoint, respBytes, respBytes.size, 90000)

                if (respLen > 0) {
                    // Send ACK (0x06) back to EDC to acknowledge receipt
                    conn.bulkTransfer(outEndpoint, byteArrayOf(0x06), 1, 2000)

                    val parsedResult = parseHexaResponse(respBytes, respLen)
                    onResult(parsedResult.toString())
                } else {
                    onResult(makeErrorJson("TIMEOUT", "Transaction timed out waiting for EDC response"))
                }

                conn.releaseInterface(targetInterface)
                conn.close()

            } catch (e: Exception) {
                onResult(makeErrorJson("EXCEPTION", e.message ?: "Unknown transaction error"))
            } finally {
                isTransactionInProgress = false
            }
        }
    }

    /**
     * Sends an Echo Test (0x3B 0x30) to test connection and handshake.
     */
    fun echoTest(context: Context, onResult: (success: Boolean, message: String) -> Unit) {
        val dev = findEdcDevice(context)
        if (dev == null) {
            onResult(false, "No EDC Terminal detected on USB")
            return
        }

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usbManager.hasPermission(dev)) {
            onResult(false, "USB permission not granted")
            return
        }

        thread {
            try {
                val conn = usbManager.openDevice(dev) ?: run {
                    onResult(false, "Cannot open USB connection")
                    return@thread
                }

                var inEndpoint: UsbEndpoint? = null
                var outEndpoint: UsbEndpoint? = null
                var targetInterface: UsbInterface? = null

                for (i in 0 until dev.interfaceCount) {
                    val uif = dev.getInterface(i)
                    for (e in 0 until uif.endpointCount) {
                        val ep = uif.getEndpoint(e)
                        if (ep.direction == UsbConstants.USB_DIR_IN && inEndpoint == null) inEndpoint = ep
                        if (ep.direction == UsbConstants.USB_DIR_OUT && outEndpoint == null) outEndpoint = ep
                    }
                    if (inEndpoint != null && outEndpoint != null) {
                        targetInterface = uif
                        break
                    }
                }

                if (targetInterface == null || inEndpoint == null || outEndpoint == null) {
                    conn.close()
                    onResult(false, "Endpoints not found")
                    return@thread
                }

                conn.claimInterface(targetInterface, true)

                // Build Echo Test: STX(0x02) + 0x01 + Type(0x3B) + Sub(0x30) + ETX(0x03) + CRC
                val frame = ByteArrayOutputStream()
                frame.write(0x02) // STX
                frame.write(0x01) // Version
                frame.write(0x3B) // Function
                frame.write(0x30) // Echo Test
                frame.write(0x03) // ETX
                val bytes = frame.toByteArray()
                val lrc = calculateLRC(bytes, 1, bytes.size)
                val fullPacket = bytes + byteArrayOf(lrc)

                conn.bulkTransfer(outEndpoint, fullPacket, fullPacket.size, 3000)

                val buf = ByteArray(16)
                val len = conn.bulkTransfer(inEndpoint, buf, buf.size, 3000)

                conn.releaseInterface(targetInterface)
                conn.close()

                if (len > 0) {
                    onResult(true, "Echo Test Success! EDC acknowledged.")
                } else {
                    onResult(false, "Echo Test sent, but no reply from EDC.")
                }

            } catch (e: Exception) {
                onResult(false, "Echo Test Error: ${e.message}")
            }
        }
    }

    private fun buildHexaRequestPacket(transType: String, amount: Long): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x02) // STX
        out.write(0x01) // Version

        // Transaction Type and Subtype per Section 5 / Table 1:
        // SALE: 0x31, 0x30
        // VOID: 0x32, 0x30
        // QRIS: 0x3C, 0x30
        when (transType) {
            "QRIS" -> {
                out.write(0x3C)
                out.write(0x30)
            }
            "VOID" -> {
                out.write(0x32)
                out.write(0x30)
            }
            else -> { // Standard SALE
                out.write(0x31)
                out.write(0x30)
            }
        }

        // Amount: 12-digit ASCII zero-padded (e.g. 5000 -> "000000005000")
        val amtStr = String.format("%012d", amount)
        out.write(amtStr.toByteArray(Charsets.US_ASCII))

        out.write(0x03) // ETX

        val bytes = out.toByteArray()
        val lrc = calculateLRC(bytes, 1, bytes.size) // XOR from byte index 1 up to 0x03 inclusive
        return bytes + byteArrayOf(lrc)
    }

    /**
     * Calculates XOR checksum (LRC) from start index up to end index.
     * Specification: XOR from byte 2 (index 1) through the stop tag (0x03).
     */
    private fun calculateLRC(data: ByteArray, start: Int, end: Int): Byte {
        var lrc: Byte = 0
        for (i in start until end) {
            lrc = (lrc.toInt() xor data[i].toInt()).toByte()
        }
        return lrc
    }

    /**
     * Parses Hexa ECR Response Packet (Section 6.1.1 / 6.1.2).
     * Format: STX(02) + Ver(01) + Type + SubType + ResponseCode(2B: "00") + SlipData + ETX(03) + CRC
     * SlipData is pipe-delimited (|):
     * TerminalID | MerchantID | CardType | PAN | EntryMode | TransType | Batch | Trace | Date | Time | RefNo | ApprovalCode | Amount | BankName
     */
    private fun parseHexaResponse(buffer: ByteArray, length: Int): JSONObject {
        val json = JSONObject()
        try {
            // Find STX (0x02) and ETX (0x03)
            var stxIndex = -1
            var etxIndex = -1
            for (i in 0 until length) {
                if (buffer[i] == 0x02.toByte() && stxIndex == -1) stxIndex = i
                if (buffer[i] == 0x03.toByte() && stxIndex != -1) {
                    etxIndex = i
                    break
                }
            }

            if (stxIndex == -1 || etxIndex == -1 || etxIndex <= stxIndex + 6) {
                json.put("success", false)
                json.put("responseCode", "XX")
                json.put("message", "Invalid packet structure from EDC")
                return json
            }

            val respCodeBytes = ByteArray(2)
            respCodeBytes[0] = buffer[stxIndex + 4]
            respCodeBytes[1] = buffer[stxIndex + 5]
            val respCode = String(respCodeBytes, Charsets.US_ASCII)
            json.put("responseCode", respCode)

            val isApproved = (respCode == "00")
            json.put("success", isApproved)
            json.put("message", if (isApproved) "Transaction Approved" else "Transaction Declined (Code: $respCode)")

            // Extract slip data string between responseCode and ETX
            val slipLen = (etxIndex - (stxIndex + 6))
            if (slipLen > 0) {
                val slipStr = String(buffer, stxIndex + 6, slipLen, Charsets.US_ASCII)
                json.put("rawSlip", slipStr)

                val parts = slipStr.split("|")
                if (parts.isNotEmpty()) json.put("terminalId", parts.getOrElse(0) { "" })
                if (parts.size > 1) json.put("merchantId", parts.getOrElse(1) { "" })
                if (parts.size > 2) json.put("cardType", parts.getOrElse(2) { "" })
                if (parts.size > 3) json.put("cardPan", parts.getOrElse(3) { "" })
                if (parts.size > 4) json.put("entryMode", parts.getOrElse(4) { "" })
                if (parts.size > 5) json.put("transType", parts.getOrElse(5) { "" })
                if (parts.size > 6) json.put("batchNo", parts.getOrElse(6) { "" })
                if (parts.size > 7) json.put("traceNo", parts.getOrElse(7) { "" })
                if (parts.size > 8) json.put("date", parts.getOrElse(8) { "" })
                if (parts.size > 9) json.put("time", parts.getOrElse(9) { "" })
                if (parts.size > 10) json.put("refNo", parts.getOrElse(10) { "" })
                if (parts.size > 11) json.put("approvalCode", parts.getOrElse(11) { "" })
                if (parts.size > 12) json.put("amount", parts.getOrElse(12) { "" })
                if (parts.size > 13) json.put("bankName", parts.getOrElse(13) { "" })
            }

        } catch (e: Exception) {
            json.put("success", false)
            json.put("error", e.message ?: "Failed parsing EDC response")
        }
        return json
    }

    private fun processMockPayment(jsonPayload: String, onResult: (String) -> Unit) {
        Handler(Looper.getMainLooper()).postDelayed({
            val req = try { JSONObject(jsonPayload) } catch (_: Exception) { JSONObject() }
            val amount = req.optLong("amount", 50000L)
            val resp = JSONObject().apply {
                put("success", true)
                put("responseCode", "00")
                put("message", "Approved (Mock Simulator)")
                put("terminalId", "MOCK0001")
                put("merchantId", "MOCK_MERCHANT")
                put("cardType", "VISA_MOCK")
                put("cardPan", "411111******1111")
                put("entryMode", "CHIP")
                put("transType", req.optString("transType", "SALE"))
                put("batchNo", "000001")
                put("traceNo", "000042")
                put("approvalCode", "999888")
                put("refNo", "202611120001")
                put("amount", amount.toString())
                put("bankName", "MOCK_BANK")
            }
            onResult(resp.toString())
        }, 2000)
    }

    private fun makeErrorJson(code: String, message: String): String {
        return JSONObject().apply {
            put("success", false)
            put("responseCode", code)
            put("message", message)
        }.toString()
    }
}
