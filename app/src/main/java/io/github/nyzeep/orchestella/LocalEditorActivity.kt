package io.github.nyzeep.orchestella

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import io.github.nyzeep.orchestella.runtime.contract.services.EndpointPolicy
import io.github.nyzeep.orchestella.runtime.contract.services.LocalTcpRelay
import io.github.nyzeep.orchestella.ui.theme.ThemeApplier
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.time.Instant
import java.util.concurrent.Executors

/** A single selected editor origin with temporary, explicit service forwarding. */
class LocalEditorActivity : AppCompatActivity() {
    companion object {
        private val editorWork = Executors.newSingleThreadExecutor()
        @Volatile private var owner: LocalEditorActivity? = null
        @Volatile private var pendingRevocation: LocalEditorActivity? = null
    }
    private val work = editorWork
    private val retired = java.util.concurrent.atomic.AtomicBoolean(false)
    private val healthPending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val lanLock = Any()
    @Volatile private var foreground = false
    @Volatile private var cookiesReady = false
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var web: WebView
    private lateinit var lanButton: MaterialButton
    private var id: String? = null
    @Volatile private var forward: ExecutionSession.ServiceForward? = null
    @Volatile private var lan: LocalTcpRelay? = null
    @Volatile private var policy: EndpointPolicy? = null
    @Volatile private var closed = false
    @Volatile private var authenticated = false
    @Volatile private var ownedSession: ExecutionSession? = null
    private val health = object : Runnable {
        override fun run() {
            if (closed || !foreground || work.isShutdown || !healthPending.compareAndSet(false, true)) return
            work.execute {
                try {
                    if (closed || !foreground) return@execute
                    authenticated = currentAuthentication()
                    val alive = forward?.isActive == true && healthy() && authenticated
                    if (!alive) {
                        cleanup()
                        main.post { if (!closed) { web.stopLoading(); web.loadUrl("about:blank") } }
                    }
                    main.post {
                        if (!closed) {
                            status.text = when {
                                !alive -> "开发服务不可达；局域网监听已关闭。请返回功能页检查 guest 状态。"
                                lan?.binding() != null -> "局域网已显式开启：0.0.0.0:${lan?.binding()?.port()}，十分钟内自动关闭。"
                                else -> "当前只供本机访问。认证由 guest 服务处理。"
                            }
                            lanButton.isEnabled = alive && authenticated
                        }
                    }
                } finally {
                    healthPending.set(false)
                    main.post { if (!closed && foreground) main.postDelayed(this, 2000) }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeApplier.prepare(this)
        super.onCreate(savedInstanceState)
        owner?.takeIf { it !== this }?.retireEditor()
        owner = this
        id = intent.getStringExtra("instanceId")
        if (id.isNullOrBlank()) { finish(); return }
        status = TextView(this).apply { text = "正在核对 guest 服务与本地监听…"; textSize = 14f }
        lanButton = MaterialButton(this).apply {
            text = "开启/关闭局域网协作"; isEnabled = false
            setOnClickListener {
                if (lan?.binding() != null) {
                    work.execute { closeLan() }
                } else AlertDialog.Builder(this@LocalEditorActivity)
                    .setTitle("开启十分钟局域网协作")
                    .setMessage("将临时监听 0.0.0.0:18081。局域网设备可访问开发服务，仍需实例密码登录；关闭页面、服务失败或到期会撤销监听。当前传输未启用 TLS，使用可信网络。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("明确开启") { _, _ -> enableLan() }.show().also(ThemeApplier::apply)
            }
        }
        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !allowed(request.url.toString(), false)
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (allowed(request.url.toString(), true)) null else denied()
            override fun onReceivedSslError(view: WebView, handler: android.webkit.SslErrorHandler,
                error: android.net.http.SslError) { handler.cancel() }
        }
        if (android.os.Build.VERSION.SDK_INT >= 24) android.webkit.ServiceWorkerController.getInstance().apply {
            serviceWorkerWebSettings.allowFileAccess = false
            serviceWorkerWebSettings.allowContentAccess = false
            setServiceWorkerClient(object : android.webkit.ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
                    if (allowed(request.url.toString(), true)) null else denied()
            })
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        column.addView(status)
        column.addView(lanButton)
        column.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(column)
        ThemeApplier.bind(this)
        work.execute {
            try {
                if (closed) error("页面已关闭")
                val previous = pendingRevocation
                if (previous != null && !previous.cleanup()) error("上一次服务端口撤销未确认，请先停止对应实例")
                val session = ExecutionSessionRegistry.get(id) ?: error("实例未运行")
                ownedSession = session
                val client = session.bridgeClient() ?: error("控制通道不可用")
                if (!client.isReady) error("控制通道尚未认证")
                val state = client.feature("STATUS", "code-server")
                if (!state.accepted()) error("开发服务状态未取得")
                val json = JSONObject(state.payload() ?: "{}")
                if (json.optString("state") != "ready") error("开发服务尚未通过 guest 验证，请先启动")
                authenticated = json.optBoolean("authenticationConfigured", false)
                if (!authenticated) error("认证配置尚未核验，未打开访问端口")
                if (!client.feature("EXPOSE", "code-server").accepted()) error("guest 本机服务转发未受理")
                var exposed = false
                for (attempt in 0 until 30) {
                    if (closed) error("页面已关闭")
                    val next = client.feature("STATUS", "code-server")
                    if (next.accepted() && JSONObject(next.payload() ?: "{}").optBoolean("exposed", false)) { exposed = true; break }
                    Thread.sleep(200)
                }
                if (!exposed) error("guest 转发未完成")
                forward = session.openServiceForward(18080)
                policy = EndpointPolicy.loopback("http", forward!!.hostPort())
                if (!healthy()) error("实际 HTTP 健康检查未通过")
                val selected = policy ?: error("服务访问范围已撤销")
                main.post {
                    if (!closed && owner === this && policy === selected) CookieManager.getInstance().removeAllCookies {
                        if (!closed && owner === this && policy === selected) {
                            CookieManager.getInstance().flush()
                            WebStorage.getInstance().deleteAllData()
                            web.clearCache(true)
                            cookiesReady = true
                            web.loadUrl(selected.origin().toString())
                            main.post(health)
                        }
                    }
                }
            } catch (failure: Exception) {
                cleanup()
                main.post { if (!closed) status.text = failure.message ?: "本地服务连接失败" }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        LocalEditorReentry.select(
            currentId = id,
            selectedId = intent.getStringExtra("instanceId"),
            closed = closed,
            retire = ::retireEditor,
            recreate = { recreate() },
            finish = { finish() }
        )
    }

    private fun allowed(address: String, resource: Boolean): Boolean {
        return try {
            if (closed || owner !== this || !cookiesReady) return false
            val expected = policy ?: return false
            if (resource) expected.requireAllowedEditorResource(address) else expected.requireAllowedEditorNavigation(address)
            true
        } catch (_: Exception) { false }
    }

    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(),
        ByteArrayInputStream("访问范围未授权".toByteArray()))

    private fun currentAuthentication(): Boolean = try {
        val session = ownedSession
        val client = if (session != null && ExecutionSessionRegistry.get(id) === session) session.bridgeClient() else null
        val result = client?.feature("STATUS", "code-server")
        result != null && result.accepted() && JSONObject(result.payload() ?: "{}").let {
            it.optString("state") == "ready" && it.optBoolean("authenticationConfigured", false)
        }
    } catch (_: Exception) { false }

    private fun healthy(): Boolean {
        return try {
            val endpoint = policy ?: return false
            val connection = URL(endpoint.origin().toString() + "/healthz").openConnection(Proxy.NO_PROXY) as HttpURLConnection
            try { connection.connectTimeout = 1000; connection.readTimeout = 1000; connection.instanceFollowRedirects = false; connection.useCaches = false; connection.responseCode == 200 }
            finally { connection.disconnect() }
        } catch (_: Exception) { false }
    }

    private fun enableLan() {
        work.execute {
            try {
                authenticated = currentAuthentication()
                if (!authenticated || !healthy() || closed || !foreground) error("认证或服务健康状态未通过")
                val target = forward ?: error("本地转发不存在")
                synchronized(lanLock) {
                    if (closed || !foreground) error("页面已离开前台")
                    closeLan()
                    val next = LocalTcpRelay(target.hostPort())
                    try {
                        next.startLan(18081, LocalTcpRelay.LanLease(true, true, Instant.now().plusSeconds(600)))
                        lan = next
                    } catch (failure: Exception) { next.close(); throw failure }
                }
            } catch (failure: Exception) { main.post { if (!closed) status.text = failure.message ?: "局域网端口未开启" } }
        }
    }

    private fun closeLan() = synchronized(lanLock) {
        lan?.close()
        lan = null
    }

    private fun cleanup(): Boolean {
        cookiesReady = false
        policy = null
        var revoked = true
        try { closeLan() } catch (_: Exception) { revoked = false }
        try { forward?.close(); forward = null } catch (_: Exception) { revoked = false }
        try {
            val session = ownedSession
            if (session != null && ExecutionSessionRegistry.get(id) === session) {
                val client = session.bridgeClient() ?: error("控制连接不可用")
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30)
                var accepted = false
                var terminal = false
                while (System.nanoTime() < deadline) {
                    if (!accepted) accepted = client.feature("UNEXPOSE", "code-server").accepted()
                    val result = client.feature("STATUS", "code-server")
                    if (result.accepted()) {
                        val state = JSONObject(result.payload() ?: "{}")
                        if (!state.optBoolean("exposed", false) && !state.optBoolean("job_active", false)) {
                            terminal = true
                            break
                        }
                    }
                    Thread.sleep(200)
                }
                if (!terminal) revoked = false
            }
        } catch (_: Exception) { revoked = false }
        policy = null
        if (revoked) {
            if (pendingRevocation === this) pendingRevocation = null
        } else {
            pendingRevocation = this
            main.post {
                val message = "服务监听撤销未确认，请停止该实例后再打开编辑器"
                if (!closed) status.text = message
                android.widget.Toast.makeText(applicationContext, message, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        return revoked
    }

    override fun onStart() {
        super.onStart()
        foreground = true
        if (!closed && policy != null && cookiesReady) main.post(health)
    }

    override fun onStop() {
        foreground = false
        main.removeCallbacks(health)
        try { closeLan() } catch (_: Exception) { work.execute { cleanup() } }
        super.onStop()
    }

    private fun retireEditor() {
        if (!retired.compareAndSet(false, true)) return
        closed = true
        foreground = false
        cookiesReady = false
        try { closeLan() } catch (_: Exception) { /* The queued cleanup retains this owner for retry. */ }
        main.removeCallbacksAndMessages(null)
        if (::web.isInitialized) { web.stopLoading(); web.loadUrl("about:blank"); web.destroy() }
        work.execute { cleanup() }
        if (owner === this) owner = null
    }

    override fun onDestroy() {
        retireEditor()
        super.onDestroy()
    }
}
