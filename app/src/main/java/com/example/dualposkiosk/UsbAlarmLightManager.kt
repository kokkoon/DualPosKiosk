package com.example.dualposkiosk

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/**
 * Controller for USB 7-color Alarm Light with Buzzer.
 * Communicates via CH340 USB-to-UART Serial (VID: 0x1A86, PID: 0x7523 / 0x5523)
 * Default: 4800 Baud, 8 Data bits, 1 Stop bit, No parity. Modbus RTU protocol.
 */
object UsbAlarmLightManager {

    private const val TAG = "UsbAlarmLight"

    // Supported CH340 / CH341 chips
    private const val CH340_VID = 0x1A86
    private val CH340_PIDS = intArrayOf(0x7523, 0x5523, 0x7522)

    private const val ACTION_USB_PERMISSION = "com.example.dualposkiosk.USB_LIGHT_PERMISSION"

    fun findAlarmLightDevice(context: Context): UsbDevice? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return usbManager.deviceList.values.firstOrNull { isAlarmLightDevice(it) }
    }

    fun isAlarmLightDevice(device: UsbDevice): Boolean {
        return device.vendorId == CH340_VID && CH340_PIDS.contains(device.productId)
    }

    fun hasPermission(context: Context, device: UsbDevice): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return usbManager.hasPermission(device)
    }

    fun requestPermission(context: Context, device: UsbDevice) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val intent = Intent(ACTION_USB_PERMISSION)
        val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        usbManager.requestPermission(device, pendingIntent)
    }

    /**
     * Sends Modbus RTU Command to the Alarm Light.
     * We send to both Station FF (Broadcast default from manual p.4) and Station 01 (default from p.3/p.6)
     * so it works regardless of whether the factory set it to 01 or FF.
     */
    fun sendCommand(context: Context, actionCode: Int): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = findAlarmLightDevice(context) ?: run {
            Log.w(TAG, "No Alarm Light device found")
            return false
        }

        if (!usbManager.hasPermission(device)) {
            requestPermission(context, device)
            return false
        }

        // Try both default stations: 0xFF (broadcast) and 0x01 (standard default)
        val stations = byteArrayOf(0xFF.toByte(), 0x01.toByte())
        var anySuccess = false

        for (st in stations) {
            val rawFrame = byteArrayOf(
                st,
                0x06.toByte(),
                0x00.toByte(),
                0xC2.toByte(),
                ((actionCode shr 8) and 0xFF).toByte(),
                (actionCode and 0xFF).toByte()
            )

            val crc = calculateCRC16(rawFrame)
            val fullPacket = ByteArray(rawFrame.size + 2)
            System.arraycopy(rawFrame, 0, fullPacket, 0, rawFrame.size)
            fullPacket[rawFrame.size] = (crc and 0xFF).toByte()          // CRC Low
            fullPacket[rawFrame.size + 1] = ((crc shr 8) and 0xFF).toByte() // CRC High

            if (sendRawSerial(usbManager, device, fullPacket)) {
                anySuccess = true
            }
        }

        return anySuccess
    }

    // High level helper actions per user manual:
    // Solid colors:
    fun turnOff(context: Context) = sendCommand(context, 0x0060) // 喇叭关，灯关
    fun solidRed(context: Context, buzzer: Boolean = false) = sendCommand(context, if (buzzer) 0x0014 else 0x0011)
    fun solidYellow(context: Context) = sendCommand(context, 0x0012)
    fun solidGreen(context: Context) = sendCommand(context, 0x0013)
    fun solidWhite(context: Context) = sendCommand(context, 0x0015)
    fun solidCyan(context: Context) = sendCommand(context, 0x0016)
    fun solidPurple(context: Context) = sendCommand(context, 0x0017)
    fun solidBlue(context: Context) = sendCommand(context, 0x0018)

    // Flash slow (慢闪):
    fun flashSlowRed(context: Context, buzzer: Boolean = false) = sendCommand(context, if (buzzer) 0x0024 else 0x0021)
    fun flashSlowYellow(context: Context) = sendCommand(context, 0x0022)
    fun flashSlowGreen(context: Context) = sendCommand(context, 0x0023)
    fun flashSlowBlue(context: Context) = sendCommand(context, 0x0028)

    // Flash fast (快闪):
    fun flashFastRed(context: Context, buzzer: Boolean = false) = sendCommand(context, if (buzzer) 0x0034 else 0x0031)
    fun flashFastYellow(context: Context) = sendCommand(context, 0x0032)
    fun flashFastGreen(context: Context) = sendCommand(context, 0x0033)
    fun flashFastBlue(context: Context) = sendCommand(context, 0x0038)

    // Buzzer control:
    fun buzzerOn(context: Context) = sendCommand(context, 0x0040)
    fun buzzerOff(context: Context) = sendCommand(context, 0x0041)

    /**
     * Configures CH340 baud rate (4800), line properties (8N1) and performs bulk write.
     */
    private fun sendRawSerial(usbManager: UsbManager, device: UsbDevice, data: ByteArray): Boolean {
        val connection = usbManager.openDevice(device) ?: run {
            Log.e(TAG, "Cannot open USB connection to Alarm Light")
            return false
        }

        var usbInterface: UsbInterface? = null
        var outEndpoint: UsbEndpoint? = null

        for (i in 0 until device.interfaceCount) {
            val uif = device.getInterface(i)
            for (e in 0 until uif.endpointCount) {
                val ep = uif.getEndpoint(e)
                if (ep.direction == UsbConstants.USB_DIR_OUT) {
                    usbInterface = uif
                    outEndpoint = ep
                    break
                }
            }
            if (outEndpoint != null) break
        }

        if (usbInterface == null || outEndpoint == null) {
            Log.e(TAG, "No OUT endpoint on Alarm Light")
            connection.close()
            return false
        }

        return try {
            connection.claimInterface(usbInterface, true)

            // Initialize CH340 line settings for 4800 Baud, 8 data bits, 1 stop bit, no parity
            initCH340BaudRate(connection, 4800)

            val written = connection.bulkTransfer(outEndpoint, data, data.size, 2000)
            Log.d(TAG, "Sent ${data.size} bytes to Alarm Light, written=$written")
            connection.releaseInterface(usbInterface)
            connection.close()
            written >= 0
        } catch (e: Exception) {
            Log.e(TAG, "Failed sending to Alarm Light: ${e.message}")
            try { connection.close() } catch (_: Exception) {}
            false
        }
    }

    /**
     * Initializes CH340 chip registers per official WCH / Linux driver specification:
     * - Vendor control requests:
     *   0xA1: reset / check
     *   0x9A: set baud rate (reg 0x1312 and 0x0F2C)
     *   0xA4: set modem control / line control (8 data bits, 1 stop bit, no parity, enable RX/TX)
     */
    private fun initCH340BaudRate(conn: UsbDeviceConnection, baudRate: Int) {
        // Standard Linux driver formula for CH340 baud rate prescaler & factor
        val factor: Int
        val divisor: Int
        when (baudRate) {
            4800 -> {
                factor = 0x18
                divisor = 0x64
            }
            9600 -> {
                factor = 0x0C
                divisor = 0xB2
            }
            else -> {
                factor = 0x18
                divisor = 0x64
            }
        }

        // 1. Reset / handshake
        conn.controlTransfer(0x40, 0xA1, 0, 0, null, 0, 1000)

        // 2. Set Baud Rate register 0x1312 and 0x0F2C
        val val1 = (divisor shl 8) or 0x82
        val val2 = factor
        conn.controlTransfer(0x40, 0x9A, 0x1312, val1, null, 0, 1000)
        conn.controlTransfer(0x40, 0x9A, 0x0F2C, val2, null, 0, 1000)

        // 3. Set line control: 8 data bits, 1 stop bit, no parity, DTR & RTS high, enable TX & RX
        // 0x501F / 0xD90A is CH340 line config
        conn.controlTransfer(0x40, 0x9A, 0x2518, 0x00C3, null, 0, 1000) // 8N1
        conn.controlTransfer(0x40, 0xA4, 0x00FF, 0, null, 0, 1000) // DTR & RTS
    }

    /**
     * Standard Modbus CRC16 calculation (Polynomial: 0xA001)
     */
    private fun calculateCRC16(data: ByteArray): Int {
        var crc = 0xFFFF
        for (b in data) {
            crc = crc xor (b.toInt() and 0xFF)
            for (i in 0 until 8) {
                crc = if ((crc and 0x0001) != 0) {
                    (crc ushr 1) xor 0xA001
                } else {
                    crc ushr 1
                }
            }
        }
        return crc
    }
}
