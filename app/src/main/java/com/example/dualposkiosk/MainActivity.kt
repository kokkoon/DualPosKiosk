package com.example.dualposkiosk

import android.R
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.Settings
import android.view.MotionEvent
import android.view.View
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

        prefs = getSharedPreferences("KioskSettings", Context.MODE_PRIVATE)
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
            // 1. Use cache if network is down
            settings.cacheMode = WebSettings.LOAD_DEFAULT 
            // 2. Ensure wide-viewport and file access for complex local layouts
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
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val tenantInput = EditText(this).apply {
            hint = "Tenant"
            setText(prefs.getString("tenant", "neohotels"))
        }
        val stationInput = EditText(this).apply {
            hint = "Station"
            setText(prefs.getString("station", "all"))
        }
        val branchInput = EditText(this).apply {
            hint = "Branch"
            setText(prefs.getString("branch", ""))
        }
        val kitchenInput = EditText(this).apply {
            hint = "Kitchen (for Kiosk mode)"
            setText(prefs.getString("kitchen", "all"))
        }

        val modeSpinner = Spinner(this).apply {
            val modes = arrayOf("Kiosk", "POS", "KDS")
            adapter = ArrayAdapter(this@MainActivity, R.layout.simple_spinner_dropdown_item, modes)
            val savedMode = prefs.getString("station_mode", "Kiosk")
            setSelection(modes.indexOf(savedMode))
        }

        layout.addView(TextView(this).apply { text = "Tenant ID" })
        layout.addView(tenantInput)
        layout.addView(TextView(this).apply { text = "Station ID" })
        layout.addView(stationInput)
        layout.addView(TextView(this).apply { text = "Branch ID" })
        layout.addView(branchInput)
        layout.addView(TextView(this).apply { text = "Kitchen ID" })
        layout.addView(kitchenInput)
        layout.addView(TextView(this).apply { text = "Station Mode" })
        layout.addView(modeSpinner)

        val testPrintBtn = Button(this).apply {
            text = "Test Print (Android Side)"
            setOnClickListener {
                printHtml("<html><body><h1>Test Print Success</h1><p>If you see this, the Android Print system is working!</p></body></html>")
            }
        }
        layout.addView(testPrintBtn)

        scrollContainer.addView(layout)
        builder.setView(scrollContainer)

        builder.setPositiveButton("Save & Reload") { _, _ ->
            val tenant = tenantInput.text.toString().trim()
            val station = stationInput.text.toString().trim()
            val branch = branchInput.text.toString().trim()
            val kitchen = kitchenInput.text.toString().trim()
            val mode = modeSpinner.selectedItem.toString()

            val urls = generateUrls(tenant, station, branch, kitchen, mode)
            
            prefs.edit()
                .putString("tenant", tenant)
                .putString("station", station)
                .putString("branch", branch)
                .putString("kitchen", kitchen)
                .putString("station_mode", mode)
                .putString("main_url", urls.first)
                .putString("sec_url", urls.second)
                .apply()
            
            mainWebView.loadUrl(urls.first)
            secondaryPresentation?.updateUrl(urls.second)
        }

        builder.setNeutralButton("Android Settings") { _, _ ->
            try {
                stopLockTask() // Release lockdown so settings can be opened
            } catch (e: Exception) {}
            val intent = Intent(Settings.ACTION_SETTINGS)
            startActivity(intent)
        }

        builder.setNegativeButton("Cancel", null)
        builder.create().show()
    }

    private fun enableKioskMode() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = MyDeviceAdminReceiver.getComponentName(this)
        if (dpm.isDeviceOwnerApp(packageName)) {
            // 1. Define which apps are allowed to be "pinned"
            val allowedPackages = arrayOf(packageName, "com.android.settings")
            dpm.setLockTaskPackages(adminComponent, allowedPackages)

            // 2. Disable system UI features (Status bar, Home button, Power Menu, etc.)
            // Note: We use 0 (LOCK_TASK_FEATURE_NONE) for maximum lockdown, 
            // or specific flags to hide bars.
            val flags = DevicePolicyManager.LOCK_TASK_FEATURE_NONE
            dpm.setLockTaskFeatures(adminComponent, flags)

            // 3. Start the actual pining
            startLockTask()
        }
    }

    private fun printHtml(htmlContent: String) {
        val webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                val printManager = getSystemService(PRINT_SERVICE) as PrintManager
                val jobName = "Kiosk Receipt"
                val printAdapter = view.createPrintDocumentAdapter(jobName)
                printManager.print(jobName, printAdapter, PrintAttributes.Builder().build())
                printWebView = null
            }
        }
        webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
        printWebView = webView
    }

    inner class KioskJavaScriptInterface {
        @JavascriptInterface
        fun silentPrint(htmlContent: String) {
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Print request received", Toast.LENGTH_SHORT).show()
                printHtml(htmlContent)
            }
        }
    }
}