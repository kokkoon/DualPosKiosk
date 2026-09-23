package com.example.dualposkiosk

import android.R
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import java.io.OutputStream
import java.net.Socket
import java.util.UUID
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbInterface
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.UserManager
import android.content.IntentFilter
import android.content.ComponentName
import kotlin.concurrent.thread
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private lateinit var mainWebView: WebView
    private var secondaryPresentation: SecondaryDisplay? = null
    private var printWebView: WebView? = null
    
    // Hold a strong reference to the bridge to prevent Garbage Collection
    private val kioskInterface = KioskJavaScriptInterface()

    private var tapCount = 0
    private var lastTapTime: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = getSharedPreferences("KioskSettings", MODE_PRIVATE)
        val defaultUrl = "https://neohotels.dalemao.com/page/public/kds/all"
        val mainUrl = prefs.getString("main_url", defaultUrl) ?: defaultUrl
        val secondaryUrl: String = prefs.getString("sec_url", "https://neohotels.dalemao.com/page/public/kds/all") ?: "https://neohotels.dalemao.com/page/public/kds/all"

        // Enable WebView debugging via Chrome on your MacBook (chrome://inspect)
        WebView.setWebContentsDebuggingEnabled(true)

        mainWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            
            // --- OFFLINE SUPPORT ENHANCEMENTS ---
            settings.cacheMode = WebSettings.LOAD_DEFAULT 
            settings.allowFileAccess = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
            
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Fix "non-injected object" by wrapping the bridge
                    view?.loadUrl("javascript:(function() { if (window._androidBridge) { window.posLauncher = { silentPrint: function(h) { window._androidBridge.silentPrint(h); } }; } })()")
                    
                    // Force a cookie sync to ensure offline persistence
                    CookieManager.getInstance().flush()
                }
                
                // Automatically fallback to cache if there's a connection error
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    if (request?.isForMainFrame == true) {
                        view?.settings?.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                    }
                }
            }
            
            webChromeClient = WebChromeClient()
            
            // Name the bridge something unique to avoid collisions
            addJavascriptInterface(kioskInterface, "_androidBridge")
            
            // Step 3: Register the new 'androidKiosk' bridge as requested
            addJavascriptInterface(WebAppInterface(this@MainActivity), "androidKiosk")
            
            loadUrl(mainUrl)
        }

        setContentView(mainWebView)
        setupSecondaryDisplay(secondaryUrl)
        enableKioskMode()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            detectFiveTaps()
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun detectFiveTaps() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastTapTime < 500) {
            tapCount++
        } else {
            tapCount = 1
        }
        lastTapTime = currentTime

        if (tapCount >= 5) {
            tapCount = 0
            showSettingsDialog()
        }
    }

    private fun hasSecondaryDisplay(): Boolean {
        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val presentationDisplays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        return presentationDisplays.isNotEmpty()
    }

    private fun setupSecondaryDisplay(url: String) {
        val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
        val presentationDisplays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)

        if (presentationDisplays.isNotEmpty()) {
            val customerDisplay = presentationDisplays[0]
            secondaryPresentation = SecondaryDisplay(this, customerDisplay, url)
            secondaryPresentation?.show()
        }
    }

    private fun generateUrls(tenant: String, station: String, branch: String, kitchen: String, mode: String): Pair<String, String> {
        return when (mode) {
            "Kiosk" -> {
                Pair(
                    "https://${tenant}.dalemao.com/page/public/menu-kiosk/${station}/${branch}",
                    "https://${tenant}.dalemao.com/page/public/kds/${kitchen}"
                )
            }
            "POS" -> {
                Pair(
                    "https://${tenant}.dalemao.com/page/public/pos/${station}/${branch}",
                    "https://${tenant}.dalemao.com/page/public/pos-cust"
                )
            }
            "KDS" -> {
                Pair(
                    "https://${tenant}.dalemao.com/page/public/kds/${kitchen}",
                    "https://${tenant}.dalemao.com/page/public/kds-cust"
                )
            }
            else -> Pair("https://${tenant}.dalemao.com", "https://${tenant}.dalemao.com")
        }
    }

    private fun showSettingsDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Kiosk Settings")

        val scrollContainer = ScrollView(this)
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        // --- 1. TOP: CLOSE KEYBOARD ---
        val hideKeyboardBtn = Button(this).apply {
            text = "Close Keyboard"
            setOnClickListener { hideKeyboard(this) }
        }
        rootLayout.addView(hideKeyboardBtn)

        // --- 2. STATION MODE (Top, full width) ---
        rootLayout.addView(TextView(this).apply { 
            text = "Station Mode"
            setPadding(0, 20, 0, 0)
            typeface = Typeface.DEFAULT_BOLD 
        })
        
        // Define IDs first for logic access
        val kitchenLabel = TextView(this).apply { text = "Kitchen ID" }
        val kitchenInput = EditText(this).apply { hint = "Kitchen"; setText(prefs.getString("kitchen", "all")) }

        val modeSpinner = Spinner(this).apply {
            val modes = arrayOf("Kiosk", "POS", "KDS")
            adapter = ArrayAdapter(this@MainActivity, R.layout.simple_spinner_dropdown_item, modes)
            val savedMode = prefs.getString("station_mode", "Kiosk")
            setSelection(modes.indexOf(savedMode))
            
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p0: AdapterView<*>?, p1: View?, pos: Int, p3: Long) {
                    val mode = modes[pos]
                    if (mode == "KDS" || mode == "Kiosk") {
                        kitchenLabel.visibility = View.VISIBLE
                        kitchenInput.visibility = View.VISIBLE
                    } else {
                        kitchenLabel.visibility = View.GONE
                        kitchenInput.visibility = View.GONE
                    }
                }
                override fun onNothingSelected(p0: AdapterView<*>?) {}
            }
        }
        rootLayout.addView(modeSpinner)

        // --- 3. TWO COLUMN LAYOUT ---
        val columnContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 20)
            weightSum = 2f
        }

        // LEFT COLUMN (IDs)
        val leftColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(0, 0, 20, 0)
        }

        val tenantInput = EditText(this).apply { hint = "Tenant"; setText(prefs.getString("tenant", "neohotels")) }
        val stationInput = EditText(this).apply { hint = "Station"; setText(prefs.getString("station", "all")) }
        val branchInput = EditText(this).apply { hint = "Branch"; setText(prefs.getString("branch", "")) }

        leftColumn.addView(TextView(this).apply { text = "Tenant ID" })
        leftColumn.addView(tenantInput)
        leftColumn.addView(TextView(this).apply { text = "Station ID" })
        leftColumn.addView(stationInput)
        leftColumn.addView(TextView(this).apply { text = "Branch ID" })
        leftColumn.addView(branchInput)
        leftColumn.addView(kitchenLabel)
        leftColumn.addView(kitchenInput)

        // RIGHT COLUMN (Printing)
        val rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(20, 0, 0, 0)
        }

        val printMethodSpinner = Spinner(this).apply {
            val methods = arrayOf("System Dialog", "Direct Silent")
            adapter = ArrayAdapter(this@MainActivity, R.layout.simple_spinner_dropdown_item, methods)
            setSelection(if(prefs.getBoolean("silent_mode", false)) 1 else 0)
        }
        val printerConnSpinner = Spinner(this).apply {
            val types = arrayOf("Network", "USB", "Bluetooth")
            adapter = ArrayAdapter(this@MainActivity, R.layout.simple_spinner_dropdown_item, types)
            val saved = prefs.getString("printer_type", "Network")
            setSelection(types.indexOf(saved))
        }

        val usbStatusLabel = TextView(this).apply {
            text = "Checking USB..."
            setPadding(10, 5, 0, 10)
            setTextColor(Color.GRAY)
        }

        val paperSizeSpinner = Spinner(this).apply {
            val sizes = arrayOf("80mm", "58mm")
            adapter = ArrayAdapter(this@MainActivity, R.layout.simple_spinner_dropdown_item, sizes)
            val saved = prefs.getString("paper_size", "80mm")
            setSelection(sizes.indexOf(saved))
        }

        val printerPathLabel = TextView(this).apply { text = "Printer IP (Net) or Name (BT)" }
        val printerPathInput = EditText(this).apply {
            hint = "192.168.1.100 or BT-Printer-Name"
            setText(prefs.getString("printer_path", ""))
        }

        // Real-time USB Status Logic
        val updateUsbStatus = {
            val usbManager = getSystemService(USB_SERVICE) as UsbManager
            val deviceList = usbManager.deviceList
            val printer = deviceList.values.firstOrNull { it.deviceClass == 7 || it.getInterface(0).interfaceClass == 7 }
            if (printer != null) {
                usbStatusLabel.text = "● USB Printer Ready: ${printer.productName ?: "Thermal Printer"}"
                usbStatusLabel.setTextColor(Color.parseColor("#2E7D32")) // Green
            } else {
                usbStatusLabel.text = "○ USB Printer NOT Connected"
                usbStatusLabel.setTextColor(Color.RED)
            }
        }
        updateUsbStatus()

        printerConnSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = printerConnSpinner.selectedItem.toString()
                usbStatusLabel.visibility = if (selected == "USB") View.VISIBLE else View.GONE
                if (selected == "Network" || selected == "Bluetooth") {
                    printerPathLabel.visibility = View.VISIBLE
                    printerPathInput.visibility = View.VISIBLE
                } else {
                    printerPathLabel.visibility = View.GONE
                    printerPathInput.visibility = View.GONE
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        rightColumn.addView(TextView(this).apply { text = "Print Method" })
        rightColumn.addView(printMethodSpinner)
        rightColumn.addView(TextView(this).apply { text = "Connection" })
        rightColumn.addView(printerConnSpinner)
        rightColumn.addView(usbStatusLabel)
        rightColumn.addView(TextView(this).apply { text = "Paper Size" })
        rightColumn.addView(paperSizeSpinner)
        rightColumn.addView(printerPathLabel)
        rightColumn.addView(printerPathInput)

        columnContainer.addView(leftColumn)
        columnContainer.addView(rightColumn)
        rootLayout.addView(columnContainer)

        // --- 4. BOTTOM: TEST PRINT ---
        val testPrintBtn = Button(this).apply {
            text = "Test Print (Android Side)"
            setOnClickListener {
                printHtml("<html><body><h1>Test Print Success</h1><p>The Android Print system is working correctly!</p></body></html>")
            }
        }
        rootLayout.addView(testPrintBtn)

        scrollContainer.addView(rootLayout)
        builder.setView(scrollContainer)

        val dialog = builder.create()

        // --- 5. CUSTOM BUTTON ROW (Bottom) ---
        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(40, 0, 40, 20)
            weightSum = 4f
        }

        val setupBtn = Button(this).apply {
            text = "Setup"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                hideKeyboard(rootLayout)
                val tenant = tenantInput.text.toString().trim().replace(" ", "")
                if (tenant.isNotEmpty()) {
                    val setupUrl = "https://$tenant.dalemao.com/page/business/business"
                    mainWebView.loadUrl(setupUrl)
                    dialog.dismiss()
                } else {
                    Toast.makeText(this@MainActivity, "Please enter Tenant ID first", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val androidSettingsBtn = Button(this).apply {
            text = "Android"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                hideKeyboard(rootLayout)
                try { stopLockTask() } catch (e: Exception) {}
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }

        val cancelBtn = Button(this).apply {
            text = "Cancel"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { 
                hideKeyboard(rootLayout)
                dialog.dismiss() 
            }
        }

        val saveBtn = Button(this).apply {
            text = "Save & Reload"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                hideKeyboard(rootLayout)
                fun sanitize(s: String) = s.trim().replace(" ", "").replace("loopon", "")

                val tenant = sanitize(tenantInput.text.toString())
                val station = sanitize(stationInput.text.toString())
                val branch = sanitize(branchInput.text.toString())
                val kitchen = sanitize(kitchenInput.text.toString())
                val mode = modeSpinner.selectedItem.toString()
                
                val isSilent = printMethodSpinner.selectedItemPosition == 1
                val pType = printerConnSpinner.selectedItem.toString()
                val pSize = paperSizeSpinner.selectedItem.toString()
                val pPath = sanitize(printerPathInput.text.toString())

                val urls = generateUrls(tenant, station, branch, kitchen, mode)
                
                prefs.edit()
                    .putString("tenant", tenant)
                    .putString("station", station)
                    .putString("branch", branch)
                    .putString("kitchen", kitchen)
                    .putString("station_mode", mode)
                    .putBoolean("silent_mode", isSilent)
                    .putString("printer_type", pType)
                    .putString("paper_size", pSize)
                    .putString("printer_path", pPath)
                    .putString("main_url", urls.first)
                    .putString("sec_url", urls.second)
                    .apply()
                
                mainWebView.loadUrl(urls.first)
                secondaryPresentation?.updateUrl(urls.second)
                dialog.dismiss()
            }
        }

        buttonRow.addView(setupBtn)
        buttonRow.addView(androidSettingsBtn)
        buttonRow.addView(cancelBtn)
        buttonRow.addView(saveBtn)
        rootLayout.addView(buttonRow)

        dialog.show()
        
        val displayMetrics = resources.displayMetrics
        val width = if (displayMetrics.widthPixels > displayMetrics.heightPixels) {
            (displayMetrics.widthPixels * 0.90).toInt() 
        } else {
            (displayMetrics.widthPixels * 0.95).toInt() 
        }
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun enableKioskMode() {
        val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = MyDeviceAdminReceiver.getComponentName(this)
        if (dpm.isDeviceOwnerApp(packageName)) {
            val allowedPackages = arrayOf(
                packageName, 
                "com.android.settings",
                "com.android.printspooler",
                "com.google.android.gms"
            )
            dpm.setLockTaskPackages(adminComponent, allowedPackages)
            val flags = DevicePolicyManager.LOCK_TASK_FEATURE_NONE
            dpm.setLockTaskFeatures(adminComponent, flags)

            // --- FORCE APP TO BE THE LAUNCHER ---
            val intentFilter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(adminComponent, intentFilter, 
                ComponentName(packageName, MainActivity::class.java.name))

            // Disable Volume Adjustments
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_ADJUST_VOLUME)
            
            // Start pinning
            startLockTask()

            // Immediate Immersive Fullscreen (Hides bars)
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        } else {
            Toast.makeText(this, "APP IS NOT DEVICE OWNER", Toast.LENGTH_LONG).show()
        }
    }

    fun printHtml(htmlContent: String) {
        runOnUiThread {
            Toast.makeText(this, "Preparing Print Preview...", Toast.LENGTH_SHORT).show()
            val webView = WebView(this)
            printWebView = webView
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    val printManager = getSystemService(PRINT_SERVICE) as PrintManager
                    val jobName = "Kiosk Print Job"
                    val printAdapter = view.createPrintDocumentAdapter(jobName)
                    printManager.print(jobName, printAdapter, PrintAttributes.Builder().build())
                    Handler(Looper.getMainLooper()).postDelayed({ printWebView = null }, 10000)
                }
                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    Toast.makeText(this@MainActivity, "Print Error: ${error?.description}", Toast.LENGTH_LONG).show()
                    printWebView = null
                }
            }
            webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
        }
    }

    fun printHtmlSilently(htmlContent: String, ip: String) {
        runOnUiThread {
            val density = resources.displayMetrics.density
            val paperSize = prefs.getString("paper_size", "80mm")
            
            // 80mm = 640px wide, 58mm = 384px wide
            val printerWidthPx = if (paperSize == "58mm") 384 else 640
            
            // Virtual width in DP to force consistent font sizing
            val renderWidthDp = if (paperSize == "58mm") 280 else 300 

            val webView = WebView(this)
            val rootLayout = findViewById<ViewGroup>(R.id.content)
            webView.visibility = View.INVISIBLE
            rootLayout.addView(webView, 0)
            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.textZoom = 100
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = true

            val styledHtml = """
                <html>
                <head>
                    <meta name="viewport" content="width=${renderWidthDp}, initial-scale=1.0">
                    <style>
                        body { 
                            width: ${renderWidthDp}px !important; 
                            margin: 0 !important; 
                            padding: 0 !important; 
                            background-color: white !important;
                        }
                        * { word-wrap: break-word; box-sizing: border-box; }
                        img, table, div { max-width: 100% !important; }
                    </style>
                </head>
                <body>$htmlContent</body>
                </html>
            """.trimIndent()

            val renderWidthPx = (renderWidthDp * density).toInt()
            webView.layout(0, 0, renderWidthPx, 10000)

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    // Reduce wait to 1s for faster response
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            val contentHeightDp = view.contentHeight
                            val renderHeightPx = (contentHeightDp * density).toInt()
                            
                            if (renderHeightPx > 0) {
                                val sourceBitmap = Bitmap.createBitmap(renderWidthPx, renderHeightPx, Bitmap.Config.ARGB_8888)
                                val canvas = Canvas(sourceBitmap)
                                canvas.drawColor(Color.WHITE)
                                view.draw(canvas)
                                
                                val scaledHeight = (renderHeightPx * (printerWidthPx.toFloat() / renderWidthPx)).toInt()
                                val scaledBitmap = Bitmap.createScaledBitmap(sourceBitmap, printerWidthPx, scaledHeight, true)
                                
                                val trimmedBitmap = trimBitmap(scaledBitmap)
                                
                                val pType = prefs.getString("printer_type", "Network")
                                when (pType) {
                                    "Network" -> sendBitmapToNetworkPrinter(trimmedBitmap, ip)
                                    "USB" -> sendBitmapToUsbPrinter(trimmedBitmap)
                                    "Bluetooth" -> sendBitmapToBluetoothPrinter(trimmedBitmap, ip)
                                }
                            }
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Print Error: ${e.message}", Toast.LENGTH_LONG).show()
                        } finally {
                            rootLayout.removeView(webView)
                            printWebView = null
                        }
                    }, 1000)
                }
            }
            webView.loadDataWithBaseURL(null, styledHtml, "text/html", "UTF-8", null)
            printWebView = webView
        }
    }

    private fun trimBitmap(bmp: Bitmap): Bitmap {
        val width = bmp.width
        val height = bmp.height
        var lastContentRow = height - 1
        rowLoop@ for (y in height - 1 downTo 0) {
            for (x in 0 until width) {
                if (bmp.getPixel(x, y) != Color.WHITE) {
                    lastContentRow = y
                    break@rowLoop
                }
            }
        }
        val finalHeight = Math.min(lastContentRow + 20, height)
        return Bitmap.createBitmap(bmp, 0, 0, width, finalHeight)
    }

    private fun sendBitmapToNetworkPrinter(bitmap: Bitmap, ip: String) {
        thread {
            try {
                val port = 9100
                val socket = Socket(ip, port)
                socket.soTimeout = 5000
                runOnUiThread { Toast.makeText(this, "Connected to $ip:$port", Toast.LENGTH_SHORT).show() }
                val out = socket.getOutputStream()
                writeEscPosData(out, bitmap)
                out.flush()
                Thread.sleep(500)
                out.close()
                socket.close()
                runOnUiThread { Toast.makeText(this, "Silent Print Success", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Network Print Failed: ${e.message}", Toast.LENGTH_LONG).show() }
            } finally {
                printWebView = null
            }
        }
    }

    private fun sendBitmapToUsbPrinter(bitmap: Bitmap) {
        val usbManager = getSystemService(USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList
        val printer = deviceList.values.firstOrNull { it.deviceClass == 7 || it.getInterface(0).interfaceClass == 7 }

        if (printer == null) {
            runOnUiThread { Toast.makeText(this, "No USB Printer Detected", Toast.LENGTH_LONG).show() }
            printWebView = null
            return
        }

        thread {
            try {
                val connection = usbManager.openDevice(printer)
                val usbInterface = printer.getInterface(0)
                val endpoint = (0 until usbInterface.endpointCount)
                    .map { usbInterface.getEndpoint(it) }
                    .firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }

                if (connection != null && endpoint != null) {
                    connection.claimInterface(usbInterface, true)
                    connection.bulkTransfer(endpoint, byteArrayOf(0x1B, 0x40), 2, 5000)
                    val data = convertBitmapToEscPos(bitmap)
                    connection.bulkTransfer(endpoint, data, data.size, 15000)
                    val cutCmd = byteArrayOf(0x0A, 0x0A, 0x1D, 0x56, 0x41, 0x00)
                    connection.bulkTransfer(endpoint, cutCmd, cutCmd.size, 5000)
                    connection.releaseInterface(usbInterface)
                    connection.close()
                    runOnUiThread { Toast.makeText(this, "Silent Print Success", Toast.LENGTH_SHORT).show() }
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "USB Print Error: ${e.message}", Toast.LENGTH_LONG).show() }
            } finally {
                printWebView = null
            }
        }
    }

    private fun sendBitmapToBluetoothPrinter(bitmap: Bitmap, name: String) {
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter == null || !btAdapter.isEnabled) {
            runOnUiThread { Toast.makeText(this, "Bluetooth Disabled", Toast.LENGTH_LONG).show() }
            printWebView = null
            return
        }

        val device = try {
            btAdapter.bondedDevices.firstOrNull { it.name?.contains(name, true) == true || it.address.equals(name, true) }
        } catch (e: SecurityException) { null }

        if (device == null) {
            runOnUiThread { Toast.makeText(this, "Bluetooth Printer Not Found", Toast.LENGTH_LONG).show() }
            printWebView = null
            return
        }

        thread {
            try {
                val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
                socket.connect()
                val out = socket.outputStream
                writeEscPosData(out, bitmap)
                out.flush()
                Thread.sleep(500)
                socket.close()
                runOnUiThread { Toast.makeText(this, "Silent Print Success", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Bluetooth Failed: ${e.message}", Toast.LENGTH_LONG).show() }
            } finally {
                printWebView = null
            }
        }
    }

    private fun writeEscPosData(out: OutputStream, bitmap: Bitmap) {
        out.write(byteArrayOf(0x1B, 0x40))
        Thread.sleep(100)
        val data = convertBitmapToEscPos(bitmap)
        out.write(data)
        out.write(byteArrayOf(0x0A, 0x0A, 0x0A, 0x1D, 0x56, 0x41, 0x00))
    }

    private fun convertBitmapToEscPos(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val widthBytes = (width + 7) / 8
        val data = ByteArray(8 + widthBytes * height)
        data[0] = 0x1D
        data[1] = 0x76
        data[2] = 0x30
        data[3] = 0x00
        data[4] = (widthBytes % 256).toByte()
        data[5] = (widthBytes / 256).toByte()
        data[6] = (height % 256).toByte()
        data[7] = (height / 256).toByte()

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var index = 8
        for (y in 0 until height) {
            for (x in 0 until widthBytes) {
                var byte = 0
                for (b in 0 until 8) {
                    val px = x * 8 + b
                    if (px < width) {
                        val pixel = pixels[y * width + px]
                        // If not white, it's black
                        if (Color.red(pixel) < 128 || Color.green(pixel) < 128 || Color.blue(pixel) < 128) {
                            byte = byte or (0x80 shr b)
                        }
                    }
                }
                data[index++] = byte.toByte()
            }
        }
        return data
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(view.windowToken, 0)
    }

    inner class KioskJavaScriptInterface {
        @JavascriptInterface
        fun silentPrint(htmlContent: String) {
            runOnUiThread {
                val isSilent = prefs.getBoolean("silent_mode", false)
                val pPath = prefs.getString("printer_path", "")
                val pType = prefs.getString("printer_type", "Network")
                val hasPathIfRequired = if (pType == "USB") true else !pPath.isNullOrBlank()

                if (isSilent && hasPathIfRequired) {
                    Toast.makeText(this@MainActivity, "Silent Print request ($pType)", Toast.LENGTH_SHORT).show()
                    printHtmlSilently(htmlContent, pPath ?: "")
                } else {
                    Toast.makeText(this@MainActivity, "Print request (System Dialog)", Toast.LENGTH_SHORT).show()
                    printHtml(htmlContent)
                }
            }
        }
    }
}