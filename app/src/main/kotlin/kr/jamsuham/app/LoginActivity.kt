package kr.jamsuham.app

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

/**
 * 앱 안에서 마이크로소프트 로그인 페이지를 띄운다. 비밀번호는 마이크로소프트 페이지에만 입력되고
 * 앱은 로그인이 끝난 뒤 돌아오는 일회용 코드만 받아서 토큰으로 바꾼다.
 */
class LoginActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var accounts: AccountStore
    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private var finishing = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        accounts = AccountStore(this)

        val root = ui.vertical()
        val header = ui.horizontal()
        header.addView(ui.button("←", "ghost") { cancel() }, ui.lp(ui.dp(48), ui.dp(44)))
        header.addView(ui.text(tr("마이크로소프트 로그인", "Microsoft sign-in"), 18f, bold = true), ui.lp(0, Ui.WRAP, 1f))
        root.addView(header)
        status = ui.text(tr("마인크래프트를 구매한 마이크로소프트 계정으로 로그인하세요", "Sign in with the Microsoft account that owns Minecraft"), 13f, ui.p.muted).apply {
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(8))
        }
        root.addView(status)

        val frame = FrameLayout(this)
        web = WebView(this)
        progress = ProgressBar(this)
        frame.addView(web, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        frame.addView(progress, FrameLayout.LayoutParams(ui.dp(48), ui.dp(48), Gravity.CENTER))
        root.addView(frame, ui.lp(Ui.MATCH, 0, 1f))
        setupWindow(root, ui.p, 8)
        setContentView(root)

        // 예전 로그인 흔적이 남아 있으면 다른 계정을 고를 수 없어서 지운다
        CookieManager.getInstance().removeAllCookies(null)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            setSupportMultipleWindows(false)
        }
        val redirect = accounts.redirectPrefix()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return handle(request.url.toString(), redirect)
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = handle(url, redirect)

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                progress.visibility = View.VISIBLE
                handle(url, redirect)
            }

            override fun onPageFinished(view: WebView, url: String) {
                progress.visibility = View.GONE
            }
        }
        web.loadUrl(accounts.loginUrl())
    }

    /** 로그인이 끝나 돌아오는 주소면 코드를 꺼낸다 */
    private fun handle(url: String, redirect: String): Boolean {
        if (finishing) return false
        // 마이크로소프트는 로그인이 끝나면 oauth20_desktop.srf 또는 ms-xal-<클라이언트ID>://auth 로 돌려보낸다
        val isRedirect = url.startsWith(redirect) || url.startsWith("ms-xal-")
        if (!isRedirect) return false
        val uri = Uri.parse(url)
        val error = uri.getQueryParameter("error")
        if (error != null) {
            val desc = uri.getQueryParameter("error_description") ?: error
            if (error == "access_denied") cancel() else fail(desc)
            return true
        }
        val code = uri.getQueryParameter("code") ?: return false
        finishing = true
        web.stopLoading()
        web.visibility = View.INVISIBLE
        progress.visibility = View.VISIBLE
        status.text = tr("계정 정보를 확인하는 중…", "Checking account information…")
        Thread {
            try {
                val acc = accounts.finishMicrosoftLogin(code)
                runOnUiThread {
                    Toast.makeText(this, tr("${acc.name} 계정으로 로그인했습니다", "Signed in as ${acc.name}"), Toast.LENGTH_SHORT).show()
                    setResult(RESULT_OK, intent.putExtra(EXTRA_ACCOUNT_ID, acc.id))
                    finish()
                }
            } catch (t: Throwable) {
                SessionHub.log.w(tr("로그인 실패", "Sign-in failed"), t)
                runOnUiThread { fail(t.message ?: t.javaClass.simpleName) }
            }
        }.apply { isDaemon = true; start() }
        return true
    }

    private fun fail(message: String) {
        if (isFinishing) return
        android.app.AlertDialog.Builder(this)
            .setTitle(tr("로그인 실패", "Sign-in failed"))
            .setMessage(message)
            .setPositiveButton(tr("확인", "OK")) { _, _ -> cancel() }
            .setCancelable(false)
            .show()
    }

    private fun cancel() {
        setResult(RESULT_CANCELED)
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!finishing && web.canGoBack()) web.goBack() else cancel()
    }

    override fun onDestroy() {
        (web.parent as? android.view.ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ACCOUNT_ID = "account_id"
    }
}
