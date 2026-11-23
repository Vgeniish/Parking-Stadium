package ru.scbarena.parking

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import ru.scbarena.parking.databinding.ActivityMainBinding

/**
 * Нативная обёртка над официальной страницей оплаты парковки стадиона.
 *
 * Что приложение делает:
 *  - открывает https://scbarena-nn.ru/parking/ в WebView на весь экран;
 *  - сохраняет cookies и состояние формы, поддерживает переходы «назад»;
 *  - всё, что не http(s) (tel:, mailto:, geo: и т.п.), отдаёт системным приложениям.
 *
 * Чего приложение НЕ делает:
 *  - не внедряет JS, не подменяет DOM, цены, номера заказов и ответы сервера;
 *  - не перехватывает и не обходит платежи — оплата идёт на стороне сайта, как в браузере;
 *  - не запрашивает лишних разрешений (нет доступа к контактам, камере, геолокации).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        setupSwipeToRefresh()
        setupBackNavigation()

        if (savedInstanceState != null) {
            // Возврат после сворачивания: восстанавливаем историю, не теряя корзину/форму.
            binding.webView.restoreState(savedInstanceState)
        } else {
            showProgress()
            binding.webView.loadUrl(START_URL)
        }
    }

    // ------------------------------------------------------------------ WebView

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val webView = binding.webView
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.surface))

        webView.settings.apply {
            // Сайт — обычный адаптивный одностраничник с формой: нужен JS и DOM storage.
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true

            loadWithOverviewMode = true
            useWideViewPort = true
            supportZoom = true
            builtInZoomControls = true
            displayZoomControls = false

            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT

            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            // User-Agent не подменяем: сервер должен видеть обычный запрос.
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.settings.safeBrowsingEnabled = true
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            // Платёжные шлюзы иногда используют сторонние cookies — не ломаем их.
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = ArenaWebViewClient()
        webView.webChromeClient = ArenaChromeClient()

        // Ссылки на файлы (если появятся) открываем системно, а не «висим» в WebView.
        webView.setDownloadListener { url, _, _, _, _ ->
            openInExternalApp(Uri.parse(url))
        }
    }

    private inner class ArenaWebViewClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean = handleUrl(request.url)

        @Deprecated("Нужен для API < 24/веб-вью без WebResourceRequest")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
            handleUrl(Uri.parse(url))

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            showProgress()
            binding.errorView.visibility = View.GONE
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            binding.swipeRefresh.isRefreshing = false
            binding.webView.visibility = View.VISIBLE
            hideProgress()
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (request?.isForMainFrame == true) {
                binding.swipeRefresh.isRefreshing = false
                hideProgress()
                showError()
            }
        }

        override fun onRenderProcessGone(
            view: WebView?,
            detail: RenderProcessGoneDetail?
        ): Boolean {
            // Не даём приложению упасть: пересоздаём WebView с чистого листа.
            val webView = view ?: return true
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
            recreate()
            return true
        }
    }

    private inner class ArenaChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            binding.progress.progress = newProgress
            if (newProgress >= 100) hideProgress() else binding.progress.visibility = View.VISIBLE
        }

        /** window.open / target="_blank": грузим в этом же WebView, ничего не подменяя. */
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
            val temp = WebView(this@MainActivity)
            temp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    v: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val uri = request.url
                    // Тот же разбор адресов, что и в основном окне:
                    // внешние приложения — наружу, страницы сайта — в основной WebView.
                    if (!handleUrl(uri)) {
                        binding.webView.loadUrl(uri.toString())
                    }
                    v.destroy()
                    return true
                }
            }
            transport.webView = temp
            resultMsg.sendToTarget()
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            // Камера/микрофон/геолокация сайту для оплаты парковки не нужны.
            request?.deny()
        }
    }

    // ------------------------------------------------------------------ URL router

    private fun handleUrl(uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase()
        return when {
            scheme == "http" || scheme == "https" -> {
                if (isExternalHost(uri.host)) {
                    openInExternalApp(uri)
                    true
                } else {
                    // Остаёмся в приложении: официальный сайт и его платёжные шаги.
                    false
                }
            }
            scheme == "intent" -> {
                openInExternalApp(uri)
                true
            }
            else -> {
                openInExternalApp(uri)
                true
            }
        }
    }

    private fun isExternalHost(host: String?): Boolean {
        val h = host?.lowercase() ?: return false
        return EXTERNAL_HOSTS.any { h == it || h.endsWith(".$it") }
    }

    private fun openInExternalApp(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    // ------------------------------------------------------------------ UI helpers

    private fun setupSwipeToRefresh() {
        binding.swipeRefresh.setOnRefreshListener {
            binding.errorView.visibility = View.GONE
            binding.webView.reload()
        }
    }

    private fun showProgress() {
        binding.progress.progress = 0
        binding.progress.visibility = View.VISIBLE
    }

    private fun hideProgress() {
        binding.progress.visibility = View.GONE
    }

    private fun showError() {
        binding.webView.visibility = View.INVISIBLE
        binding.errorView.visibility = View.VISIBLE
        binding.errorRetry.setOnClickListener {
            binding.errorView.visibility = View.GONE
            binding.webView.visibility = View.VISIBLE
            val url = binding.webView.url ?: START_URL
            showProgress()
            binding.webView.loadUrl(url)
        }
    }

    // ------------------------------------------------------------------ lifecycle

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webView.canGoBack()) {
                    binding.webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onPause() {
        binding.webView.onPause()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        binding.webView.saveState(outState)
    }

    override fun onDestroy() {
        (binding.webView.parent as? ViewGroup)?.removeView(binding.webView)
        binding.webView.destroy()
        super.onDestroy()
    }

    companion object {
        /** Единственный адрес, который открывает приложение. */
        const val START_URL = "https://scbarena-nn.ru/parking/"

        /** Соцсети и видео со страницы — их логичнее отдать в отдельные приложения. */
        private val EXTERNAL_HOSTS = setOf(
            "vk.com",
            "vk.ru",
            "t.me",
            "telegram.me",
            "youtube.com",
            "youtu.be"
        )
    }
}
