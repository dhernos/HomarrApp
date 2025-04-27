package com.example.homarr

import android.app.Activity
import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebSettings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.io.FileNotFoundException

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.javaScriptEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.domStorageEnabled = true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            webView.settings.forceDark = WebSettings.FORCE_DARK_ON
        }

        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                // Hier wird der Typ des File-Path-Callbacks explizit angegeben.
                this@MainActivity.filePathCallback = filePathCallback
                openFileChooser()
                return true
            }
        }

        webView.loadUrl("http://10.0.0.1:7575") // Landing-Page

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            // **Hole die aktuellen Cookies erst JETZT, direkt vor dem Download**
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie(url)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                lifecycleScope.launch {
                    startDownloadWithMediaStore(url, userAgent, contentDisposition, mimetype, cookies)
                }
            } else {
                startDownload(url, userAgent, contentDisposition, mimetype, cookies)
            }
        }
    }

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data: Intent? = result.data
                val uri = data?.data
                filePathCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else emptyArray())
            } else {
                filePathCallback?.onReceiveValue(null)
            }
        }

    private fun openFileChooser() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*" // Alle Dateitypen erlauben
        }
        fileChooserLauncher.launch(intent)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun startDownloadWithMediaStore(url: String, userAgent: String, contentDisposition: String?, mimetype: String?, cookies: String?) {
        withContext(Dispatchers.IO) {
            try {
                val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)
                val decodedFilename = URLDecoder.decode(filename, "UTF-8")

                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, decodedFilename)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimetype)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/")
                }

                val resolver = contentResolver
                val uri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let {
                    val outputStream: OutputStream? = resolver.openOutputStream(it)
                    outputStream?.use { os ->
                        try {
                            val connection = URL(url).openConnection() as HttpURLConnection
                            connection.requestMethod = "GET"
                            connection.setRequestProperty("User-Agent", userAgent)

                            if (cookies != null) {
                                connection.setRequestProperty("Cookie", cookies)
                            }

                            connection.connect()

                            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                                val inputStream: InputStream = connection.inputStream
                                inputStream.copyTo(os)
                            } else {
                                Log.e("Download", "Fehler beim Herunterladen: ${connection.responseCode}")
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            Log.e("Download", "Fehler: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Log.e("Download", "Fehler beim Starten des Downloads: ${e.message}")
            }
        }
    }

    private fun startDownload(url: String, userAgent: String, contentDisposition: String?, mimetype: String?, cookies: String?) {
        val request = DownloadManager.Request(Uri.parse(url))

        val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)
        val decodedFilename = URLDecoder.decode(filename, "UTF-8")
        request.setMimeType(mimetype)
        request.addRequestHeader("User-Agent", userAgent)

        if (cookies != null) {
            request.addRequestHeader("Cookie", cookies)
        }

        request.setDescription("Download läuft...")
        request.setTitle(decodedFilename)
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        request.setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, decodedFilename)

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.enqueue(request)
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

}
