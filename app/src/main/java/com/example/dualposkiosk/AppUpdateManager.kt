package com.example.dualposkiosk

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object AppUpdateManager {

    private const val GITHUB_REPO = "kokkoon/DualPosKiosk"
    private const val RELEASES_API = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    fun getCurrentVersion(context: Context): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    fun checkForUpdate(
        context: Context,
        isManual: Boolean = true,
        onStatus: (String) -> Unit = {}
    ) {
        thread {
            try {
                if (isManual) onStatus("Checking for updates...")

                val url = URL(RELEASES_API)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    setRequestProperty("User-Agent", "DualPosKiosk-App")
                }

                if (conn.responseCode != 200) {
                    val msg = "Update check failed (HTTP ${conn.responseCode})"
                    onStatus(msg)
                    return@thread
                }

                val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                val jsonObj = JSONObject(jsonStr)

                val tagName = jsonObj.optString("tag_name", "").trim()
                val latestVer = tagName.removePrefix("v").removePrefix("V").trim()
                val currentVer = getCurrentVersion(context).removePrefix("v").removePrefix("V").trim()

                // Find .apk in assets
                val assets = jsonObj.optJSONArray("assets")
                var downloadUrl = ""
                var apkName = "update.apk"
                if (assets != null) {
                    for (i in 0 until assets.length()) {
                        val asset = assets.getJSONObject(i)
                        val name = asset.optString("name", "")
                        if (name.endsWith(".apk", ignoreCase = true)) {
                            downloadUrl = asset.optString("browser_download_url", "")
                            apkName = name
                            break
                        }
                    }
                }

                if (isNewerVersion(latestVer, currentVer)) {
                    if (downloadUrl.isNotEmpty()) {
                        onStatus("New version $latestVer found! Downloading...")
                        downloadAndInstallApk(context, downloadUrl, apkName, onStatus)
                    } else {
                        onStatus("Version $latestVer found, but no APK asset attached.")
                    }
                } else {
                    onStatus("App is up to date (v$currentVer)")
                }

            } catch (e: Exception) {
                onStatus("Update check error: ${e.message}")
            }
        }
    }

    private fun isNewerVersion(remote: String, local: String): Boolean {
        if (remote.isBlank()) return false
        val remoteParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val localParts = local.split(".").mapNotNull { it.toIntOrNull() }

        val maxLen = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r > l) return true
            if (r < l) return false
        }
        return false
    }

    private fun downloadAndInstallApk(
        context: Context,
        downloadUrl: String,
        fileName: String,
        onStatus: (String) -> Unit
    ) {
        try {
            val updateFile = File(context.cacheDir, fileName)
            if (updateFile.exists()) updateFile.delete()

            val url = URL(downloadUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "DualPosKiosk-App")
            }

            // Handle potential 302 redirects from GitHub
            var realConn = conn
            if (conn.responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                conn.responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                conn.responseCode == 307
            ) {
                val newUrl = conn.getHeaderField("Location")
                realConn = (URL(newUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("User-Agent", "DualPosKiosk-App")
                }
            }

            val totalSize = realConn.contentLength
            var downloaded = 0

            realConn.inputStream.use { input ->
                FileOutputStream(updateFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                    }
                }
            }

            onStatus("Download completed. Installing update...")

            // Check if device owner / silent install is possible via PackageInstaller
            val installedSilently = installSilentlyIfDeviceOwner(context, updateFile, onStatus)
            if (!installedSilently) {
                // Fallback to standard prompt installation via FileProvider
                installViaIntent(context, updateFile)
            }

        } catch (e: Exception) {
            onStatus("Download failed: ${e.message}")
        }
    }

    private fun installSilentlyIfDeviceOwner(
        context: Context,
        apkFile: File,
        onStatus: (String) -> Unit
    ): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val isOwner = dpm.isDeviceOwnerApp(context.packageName)
            if (!isOwner) {
                onStatus("Not device owner, falling back to package prompt...")
                return false
            }

            val packageInstaller = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            val out = session.openWrite("package", 0, apkFile.length())
            val inStream = FileInputStream(apkFile)
            val buffer = ByteArray(65536)
            var c: Int
            while (inStream.read(buffer).also { c = it } != -1) {
                out.write(buffer, 0, c)
            }
            session.fsync(out)
            inStream.close()
            out.close()

            // Pending intent for completion callback using InstallReceiver
            val intent = Intent(context, InstallReceiver::class.java).apply {
                action = "com.example.dualposkiosk.ACTION_INSTALL_COMMIT"
                setPackage(context.packageName)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)

            session.commit(pendingIntent.intentSender)
            session.close()
            onStatus("Commit sent to system installer...")
            true
        } catch (e: Exception) {
            onStatus("Silent install error: ${e.message}")
            false
        }
    }

    private fun installViaIntent(context: Context, apkFile: File) {
        try {
            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "Install error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
