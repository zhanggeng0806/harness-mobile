package com.dshmobile.shell

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 壳 Activity：内嵌 dsh 引擎之上的 WebView + 引擎引导页兜底。 */
class MainActivity : ComponentActivity() {

  private lateinit var webView: WebView
  private lateinit var guideView: LinearLayout
  private lateinit var engineStatus: TextView
  private lateinit var progressText: TextView

  /** 目录选择桥 + 本地引擎 API 鉴权 token（每次进程启动随机；引擎 env 与 JS 桥同源持有）。 */
  private val pickToken: String = java.util.UUID.randomUUID().toString()
  private val engineToken: String = java.util.UUID.randomUUID().toString()

  private val engineManager by lazy { EngineManager(this, pickToken, engineToken) }
  private val prefs by lazy { Prefs(this) }
  private val engineFlowRunning = java.util.concurrent.atomic.AtomicBoolean(false)
  private var pendingPickCallback: String? = null
  private var filePathCallback: ValueCallback<Array<Uri>>? = null
  private var screenWakeLock: PowerManager.WakeLock? = null

  // 目录选择（工作区）：结果经 window.__dshBridge.onDirectoryPicked 回传。
  private val directoryPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
    val callback = pendingPickCallback
    pendingPickCallback = null
    if (callback != null) {
      // 取消也回传 null，让引擎侧 pick() 以取消结算（否则会反复唤起选择器）。
      val path = if (uri != null) jsString(AndroidBridge.resolvePickedPath(uri)) else "null"
      webView.evaluateJavascript(
        "window.__dshBridge?.onDirectoryPicked?.(" + jsString(callback) + ", " + path + ")", null,
      )
    }
  }

  // 文件上传（<input type=file> → onShowFileChooser → 系统文件选择器，可多选）。
  private val filePicker =
    registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
      val callback = filePathCallback
      filePathCallback = null
      if (callback != null) callback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

  private val notificationPermission =
    registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 通知测试通道 */ }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val root = FrameLayout(this)
    webView = WebView(this).apply { id = View.generateViewId() }
    root.addView(webView, FrameLayout.LayoutParams(MATCH, MATCH))
    guideView = buildGuideView()
    root.addView(guideView, FrameLayout.LayoutParams(MATCH, MATCH))
    setContentView(root)

    onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
      override fun handleOnBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else finish()
      }
    })

    configureWebView()

    if (intent?.action == ACTION_UPDATE) runUpdate() else startEngineFlow()
  }

  override fun onResume() {
    super.onResume()
    // 从目录选择/设置页返回：引擎可能已起来，重新路由。
    if (!EngineProbe.isRunning()) startEngineFlow()
    if (::webView.isInitialized) pushSystemDark(webView)
  }

  override fun onDestroy() {
    super.onDestroy()
    engineManager.stopEngine()
  }

  override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
    super.onConfigurationChanged(newConfig)
    if (::webView.isInitialized) pushSystemDark(webView)
  }

  private fun configureWebView() {
    val debuggable = (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    if (debuggable) android.webkit.WebView.setWebContentsDebuggingEnabled(true)
    // IME 弹出时 WebView resize 会闪白，背景设深色避免白屏闪烁。
    webView.setBackgroundColor(0xFF0F1B2D.toInt())
    webView.settings.apply {
      javaScriptEnabled = true
      domStorageEnabled = true
      allowFileAccess = false
      mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
      // 移动端适配：视口按屏幕宽度、超宽内容按视口缩放、允许双指缩放。
      useWideViewPort = true
      loadWithOverviewMode = true
      setSupportZoom(true)
      builtInZoomControls = true
      displayZoomControls = false
      // prefers-color-scheme 跟随系统深色（部分厂商 WebView 默认不跟随）。
      if (Build.VERSION.SDK_INT >= 29) {
        @Suppress("DEPRECATION")
        forceDark = WebSettings.FORCE_DARK_AUTO
      }
    }
    webView.webViewClient = object : WebViewClient() {
      override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        // 会话日志导出：浏览器导航带 Origin:null 会被 dsh 的 browser-trust fence
        // 拒（403）。改走 app 内 HttpURLConnection（无浏览器标记 → fence 放行）。
        if (isSessionExport(url, request.method)) {
          downloadToDownloads(url, null)
          return true
        }
        if (isEngineSource(url)) {
          view.loadUrl(url)
          return true
        }
        openInExternalBrowser(request.url)
        return true
      }

      override fun onReceivedError(view: WebView, errorCode: Int, description: String, failingUrl: String) {
        if (isEngineSource(failingUrl)) showGuide()
      }

      override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        pushSystemDark(view)
        injectMobileCss(view)
        injectMobileJs(view)
      }
    }
    webView.setDownloadListener { url, _userAgent, contentDisposition, _mimeType, _contentLength ->
      downloadToDownloads(url, contentDisposition)
    }
    webView.webChromeClient = object : WebChromeClient() {
      override fun onShowFileChooser(
        webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams,
      ): Boolean {
        this@MainActivity.filePathCallback?.onReceiveValue(null)
        this@MainActivity.filePathCallback = filePathCallback
        filePicker.launch(emptyArray())
        return true
      }

      override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
        result.confirm()
        return true
      }
    }
    webView.addJavascriptInterface(
      AndroidBridge(
        onPickRequest = { callbackId -> pickDirectoryWithPermissionCheck(callbackId) },
        onKeepScreen = { enable -> keepScreenOn(enable) },
        onNotify = { title, text -> showTestNotification(title, text) },
        onAllFilesAccessRequest = { openAllFilesAccessSettings() },
        onPauseEngine = { pauseEngine() },
        pickToken = pickToken,
        engineToken = engineToken,
      ),
      "androidBridge",
    )
    webView.loadUrl(RuntimeConfig.ENGINE_URL)
  }

  /** SAF 目录选择（带 All Files Access 引导）：外部工作区要求 bash 能直接访问真实路径。 */
  private fun pickDirectoryWithPermissionCheck(callbackId: String) {
    if (pendingPickCallback != null) {
      // 并发保护：已有在途选择时拒绝新请求（单槽，避免前一个 pick 永不结算）。
      webView.evaluateJavascript(
        "window.__dshBridge?.onDirectoryPicked?.(" + jsString(callbackId) + ", null)", null,
      )
      return
    }
    if (Build.VERSION.SDK_INT < 30 || !Environment.isExternalStorageManager()) {
      if (Build.VERSION.SDK_INT < 30) {
        showTestNotification("外部工作区不可用", "Android 10 及以下不支持选择外部目录")
        webView.evaluateJavascript(
          "window.__dshBridge?.onDirectoryPicked?.(" + jsString(callbackId) + ", null)", null,
        )
        return
      }
      openAllFilesAccessSettings()
      webView.evaluateJavascript("window.__dshBridge?.onPermissionRequired?.()", null)
      return
    }
    pendingPickCallback = callbackId
    directoryPicker.launch(null)
  }

  private fun openAllFilesAccessSettings() {
    if (Build.VERSION.SDK_INT < 30) return
    try {
      startActivity(
        Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
          .setData(Uri.parse("package:$packageName")),
      )
    } catch (_: Exception) {
      try {
        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
      } catch (_: Exception) {
      }
    }
  }

  private val exportDownloading = java.util.concurrent.atomic.AtomicBoolean(false)

  /** 引擎侧 URL 下载为文件（会话导出/下载监听统一入口），仅接受引擎同源 URL。 */
  private fun downloadToDownloads(url: String, contentDisposition: String?) {
    if (!isEngineSource(url)) {
      showTestNotification("下载被拒绝", "仅支持从本机引擎导出文件")
      pushExportResult(false, "仅支持从本机引擎导出文件")
      return
    }
    if (!exportDownloading.compareAndSet(false, true)) return
    if (Build.VERSION.SDK_INT < 29) {
      pushExportResult(false, "当前系统版本不支持下载，请升级到 Android 10+")
      exportDownloading.set(false)
      return
    }
    val filename = sanitizeFilename(parseDownloadFilename(url, contentDisposition))
    Thread {
      var conn: HttpURLConnection? = null
      try {
        val c = URL(url).openConnection() as HttpURLConnection
        conn = c
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        c.requestMethod = "GET"
        if (c.responseCode != HttpURLConnection.HTTP_OK) throw java.io.IOException("HTTP " + c.responseCode)
        var saved: String? = null
        c.inputStream.use { input -> saved = saveExportToDshData(filename, input) }
        val finalPath = saved
        runOnUiThread {
          showTestNotification("已导出", "已保存到 $finalPath")
          pushExportResult(true, "已保存到 $finalPath")
        }
      } catch (t: Throwable) {
        val message = t.message ?: "未知错误"
        runOnUiThread {
          showTestNotification("导出失败", message)
          pushExportResult(false, message)
        }
      } finally {
        conn?.disconnect()
        exportDownloading.set(false)
      }
    }.start()
  }

  private fun pushExportResult(ok: Boolean, detail: String) {
    val title = if (ok) "导出成功" else "导出失败"
    val payload = "{\"ok\":" + ok + ",\"title\":" + jsString(title) + ",\"detail\":" + jsString(detail) + "}"
    webView.post {
      webView.evaluateJavascript("window.__dshExportResult && window.__dshExportResult($payload)", null)
    }
  }

  private fun saveExportToDshData(filename: String, input: java.io.InputStream): String {
    if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) {
      val exportDir = File(engineManager.dshDataDir, "exports")
      exportDir.mkdirs()
      File(engineManager.dshDataDir, ".nomedia").writeText("")
      val target = uniqueExportFile(exportDir, filename)
      val tmp = File(exportDir, "." + target.name + ".tmp")
      try {
        tmp.outputStream().use { out -> writeCapped(input, out) }
        if (!tmp.renameTo(target)) java.nio.file.Files.move(tmp.toPath(), target.toPath())
      } catch (t: Throwable) {
        tmp.delete()
        throw t
      }
      return "文档/dshdata/exports/" + target.name
    }
    return "下载/" + saveToDownloadsStreamed(filename, input)
  }

  private fun writeCapped(input: java.io.InputStream, out: java.io.OutputStream) {
    val buf = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
      val n = input.read(buf)
      if (n < 0) break
      total += n
      if (total > MAX_DOWNLOAD_BYTES) throw java.io.IOException("导出文件过大")
      out.write(buf, 0, n)
    }
  }

  private fun saveToDownloadsStreamed(filename: String, input: java.io.InputStream): String {
    val values = ContentValues().apply {
      put(MediaStore.Downloads.DISPLAY_NAME, filename)
      put(MediaStore.Downloads.MIME_TYPE, "application/zip")
      put(MediaStore.Downloads.IS_PENDING, 1)
      put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
    }
    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
      ?: throw java.io.IOException("无法创建下载文件")
    try {
      contentResolver.openOutputStream(uri)?.use { out -> writeCapped(input, out) }
        ?: throw java.io.IOException("无法写入下载文件")
      values.clear()
      values.put(MediaStore.Downloads.IS_PENDING, 0)
      contentResolver.update(uri, values, null, null)
    } catch (t: Throwable) {
      contentResolver.delete(uri, null, null)
      throw t
    }
    return filename
  }

  private fun uniqueExportFile(dir: File, name: String): File {
    val dot = name.lastIndexOf('.')
    val base = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var candidate = File(dir, name)
    var i = 1
    while (candidate.exists()) {
      candidate = File(dir, base + " (" + i + ")" + ext)
      i++
    }
    return candidate
  }

  private fun sanitizeFilename(name: String): String {
    val cleaned = name.replace(Regex("[/\\\\\u0000-\u001f]"), "_").take(200)
    return if (cleaned.isBlank()) "dsh-session-export.zip" else cleaned
  }

  private fun parseDownloadFilename(url: String, contentDisposition: String?): String {
    contentDisposition?.let { cd ->
      Regex("filename=\"?([^\";]+)\"?").find(cd)?.groupValues?.get(1)?.let { return it }
    }
    return try {
      val q = URL(url).query ?: ""
      val sid = q.split("&").mapNotNull { seg ->
        val kv = seg.split("=", limit = 2)
        if (kv.size == 2 && kv[0] == "sessionId") kv[1] else null
      }.firstOrNull()
      if (sid != null) "dsh-session-$sid.zip" else "dsh-session-export.zip"
    } catch (_: Exception) {
      "dsh-session-export.zip"
    }
  }

  private fun pushSystemDark(view: WebView) {
    val dark = (resources.configuration.uiMode and
      android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
      android.content.res.Configuration.UI_MODE_NIGHT_YES
    try {
      view.evaluateJavascript("window.__dshThemeBridge && window.__dshThemeBridge.setDark($dark)", null)
      // 兜底桥可能晚于 onPageFinished 才安装；延迟再推一次覆盖时序。
      view.postDelayed({
        view.evaluateJavascript("window.__dshThemeBridge && window.__dshThemeBridge.setDark($dark)", null)
      }, 800)
    } catch (_: Exception) {
    }
  }

  /**
   * 注入移动端 CSS 加固：修复窄屏上长文本/代码块/表格/图片溢出导致「字显示不全」。
   * 用固定 id 幂等注入，SPA 路由切换后仍生效（style 元素常驻 document）。
   */
  private fun injectMobileCss(view: WebView) {
    val css =
      "pre,code{white-space:pre-wrap!important;overflow-wrap:anywhere!important;max-width:100%!important}" +
        "table{display:block!important;max-width:100%!important;overflow-x:auto!important}" +
        "img,video,canvas,svg{max-width:100%!important;height:auto!important}" +
        "*{overflow-wrap:break-word}" +
        "@media(max-width:600px){" +
        // 设置行（Enter键 T1PP_q / Agent预设 _5QVD0a / 权限 oY77xG，三者同构）：
        // 窄屏下描述列占满整行，控件换到下一行，避免描述被挤成很多行。
        ".T1PP_q_row,.oY77xG_row,._5QVD0a_row{flex-wrap:wrap!important}" +
        ".T1PP_q_rowText,.oY77xG_rowText,._5QVD0a_rowText{flex-basis:100%!important;padding-right:0!important}" +
        // 模型选择菜单：默认 right:0 右对齐导致靠右截断，改为居中于触发按钮。
        "._7KE1Ra_menu{right:auto!important;left:50%!important;transform:translateX(-50%)!important}" +
        // 全局间距自适应：窄屏收窄两侧留白，给内容更多可用宽度。
        ".wSkVaW_root{--dsh-composer-side-clearance:8px!important}" +
        // 消息底部操作行：按钮行 + 统计文字行，均右对齐（统计文字独占一行）。
        ".p-xYUq_runTimeDot{margin:0!important}" +
        ".p-xYUq_timeEnd,.p-xYUq_timeStart{padding:0!important;font-size:13px!important}" +
        ".osXY9a_actions{justify-content:flex-end!important;flex-wrap:wrap!important;height:auto!important}" +
        ".osXY9a_actions .p-xYUq_timeEnd,.osXY9a_actions .p-xYUq_timeStart{flex-basis:100%!important;text-align:right!important}" +
        // 反馈补充说明编辑器（输入框+保存+取消）：输入框固定 260px 会溢出截断，改为自适应。
        "._8_XoUG_noteEditor{max-width:100%!important;width:100%!important}" +
        "._8_XoUG_noteInput{width:auto!important;flex:1!important;min-width:0!important}}"
    try {
      view.evaluateJavascript(
        "(function(){if(document.getElementById('__dsh_mobile_css'))return;" +
          "var s=document.createElement('style');s.id='__dsh_mobile_css';" +
          "s.textContent=" + jsString(css) + ";document.head.appendChild(s);})()",
        null,
      )
    } catch (_: Exception) {
    }
  }

  /**
   * 注入移动端 JS 修补：只隐藏侧边栏开关的 tooltip（「打开/收起侧边栏」，
   * 其锚点在关闭的抽屉里导致气泡卡在左上角），保留其它 tooltip（如统计详情）。
   * 用 MutationObserver 按文字精确匹配，避免误伤。
   */
  private fun injectMobileJs(view: WebView) {
    val js =
      "(function(){if(window.__dsh_mobile_js)return;window.__dsh_mobile_js=true;" +
        "var hide=function(){" +
        "document.querySelectorAll('[role=\\\"tooltip\\\"]').forEach(function(t){" +
        "var x=(t.textContent||'').trim();" +
        "if(x==='打开侧边栏'||x==='收起侧边栏'){t.style.display='none';}" +
        "});};" +
        "new MutationObserver(hide).observe(document.body,{childList:true,subtree:true});" +
        "hide();" +
        // 会话切换/新建会话会触发 composer 组件的 useEffect 调用 el.focus({preventScroll:true})
        // 自动聚焦底部输入框从而弹出键盘。monkey-patch HTMLTextAreaElement.focus：只忽略
        // 带 preventScroll 且发生在非用户点击时刻的 composer 自动聚焦；用户主动点击 composer
        // （pointerdown 记录时间）时放行，从源头阻止，不产生 IME 显隐闪烁。
        "var lastComposerTap=0;" +
        "document.addEventListener('pointerdown',function(e){" +
        "var c=e.target&&e.target.closest?e.target.closest('[data-composer-card] textarea'):null;" +
        "if(c){lastComposerTap=Date.now();}" +
        "},true);" +
        "var origFocus=HTMLTextAreaElement.prototype.focus;" +
        "HTMLTextAreaElement.prototype.focus=function(opts){" +
        "if(opts&&opts.preventScroll&&this.closest&&this.closest('[data-composer-card]')){" +
        "if(Date.now()-lastComposerTap>150){return;}" +
        "}" +
        "return origFocus.apply(this,arguments);" +
        "};" +
        "})()"
    try {
      view.evaluateJavascript(js, null)
    } catch (_: Exception) {
    }
  }

  /** 引擎源精确判定（scheme/host/port 全等，防 127.0.0.1:30800 前缀欺骗）。 */
  private fun isEngineSource(url: String): Boolean {
    return try {
      val base = Uri.parse(RuntimeConfig.ENGINE_URL)
      val uri = Uri.parse(url)
      uri.scheme == base.scheme && uri.host == base.host && uri.port == base.port
    } catch (_: Exception) {
      false
    }
  }

  private fun isSessionExport(url: String, method: String): Boolean {
    return method == "GET" && isEngineSource(url) && url.contains(SESSION_EXPORT_PATH)
  }

  private val exportLaunching = java.util.concurrent.atomic.AtomicBoolean(false)

  private fun openInExternalBrowser(uri: Uri): Boolean {
    if (!exportLaunching.compareAndSet(false, true)) return true
    return try {
      startActivity(Intent(Intent.ACTION_VIEW, uri))
      true
    } catch (_: Exception) {
      false
    } finally {
      exportLaunching.set(false)
    }
  }

  private fun keepScreenOn(enable: Boolean) {
    val power = getSystemService(Context.POWER_SERVICE) as PowerManager
    if (enable) {
      if (screenWakeLock == null) {
        screenWakeLock = power.newWakeLock(
          PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "dsh:screen",
        )
      }
      screenWakeLock?.let { if (!it.isHeld) it.acquire() }
    } else {
      screenWakeLock?.let { if (it.isHeld) it.release() }
      screenWakeLock = null
    }
  }

  private fun showTestNotification(title: String, text: String) {
    if (Build.VERSION.SDK_INT >= 33 &&
      checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
      notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
      return
    }
    val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (Build.VERSION.SDK_INT >= 26) {
      manager.createNotificationChannel(NotificationChannel("dsh", "dsh", NotificationManager.IMPORTANCE_DEFAULT))
    }
    val pending = android.app.PendingIntent.getActivity(
      this, 0, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE,
    )
    manager.notify(
      1,
      NotificationCompat.Builder(this, "dsh")
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setContentTitle(title)
        .setContentText(text)
        .setContentIntent(pending)
        .setAutoCancel(true)
        .build(),
    )
  }

  private fun buildGuideView(): LinearLayout {
    val padding = (24 * resources.displayMetrics.density).toInt()
    val guide = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(padding, padding, padding, padding)
      gravity = Gravity.CENTER
      visibility = View.GONE
    }
    engineStatus = TextView(this).apply { textSize = 16f; setPadding(0, 0, 0, padding) }
    progressText = TextView(this).apply { textSize = 13f; setPadding(0, 0, 0, padding); visibility = View.GONE }
    guide.addView(engineStatus)
    guide.addView(progressText)
    guide.addView(button("重试") { startEngineFlow() })
    guide.addView(button("打开 Termux") { launchTermux() })
    guide.addView(button("检查运行时更新") { runUpdate() })
    guide.addView(button("电池优化豁免") { requestBatteryExemption() })
    guide.addView(button("设置更新服务器") { promptManifestUrl() })
    return guide
  }

  private fun button(label: String, onClick: () -> Unit): Button {
    return Button(this).apply {
      text = label
      setOnClickListener { onClick() }
    }
  }

  private fun launchTermux() {
    val intent = packageManager.getLaunchIntentForPackage("com.termux")
    if (intent != null) startActivity(intent) else showTestNotification("未找到 Termux", "请先安装 Termux")
  }

  /** 请求忽略电池优化（降低后台被杀概率）。 */
  private fun requestBatteryExemption() {
    if (Build.VERSION.SDK_INT >= 23) {
      try {
        startActivity(
          Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName")),
        )
      } catch (_: Exception) {
        try {
          startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
        }
      }
    }
  }

  /** 设置更新服务器地址（持久化到 Prefs）。 */
  private fun promptManifestUrl() {
    val input = EditText(this).apply {
      hint = "https://host/manifest.json"
      setText(prefs.manifestUrl)
    }
    android.app.AlertDialog.Builder(this)
      .setTitle("更新服务器 manifest 地址")
      .setView(input)
      .setPositiveButton("保存") { _, _ -> prefs.manifestUrl = input.text.toString().trim() }
      .setNegativeButton("取消", null)
      .show()
  }

  /**
   * 引擎优先流程：已有引擎直接用（Termux 或先前内嵌），否则安装内嵌快照并启动，
   * 轮询直到 Web 服务应答。in-flight 守卫防双线程竞态安装/启动。
   */
  private fun startEngineFlow() {
    if (!engineFlowRunning.compareAndSet(false, true)) return
    Thread {
      try {
        if (EngineProbe.isRunning()) {
          runOnUiThread { showWeb() }
          return@Thread
        }
        if (!engineManager.snapshotFresh()) {
          runOnUiThread {
            progressText.visibility = View.VISIBLE
            showGuide()
            engineStatus.text = "正在安装运行时…"
          }
          val ok = engineManager.installFromAsset { done, _ ->
            runOnUiThread { engineStatus.text = "正在安装运行时… " + done / 1024 / 1024 + " MB" }
          }
          if (!ok) {
            runOnUiThread {
              engineStatus.text = "运行时安装失败：请放入 assets/snapshot.tar.gz 或配置更新服务器。"
              showGuide()
            }
            return@Thread
          }
        }
        if (!engineManager.startEngine()) {
          runOnUiThread {
            engineStatus.text = "引擎启动失败，请重试。"
            showGuide()
          }
          return@Thread
        }
        // 轮询直到 Web 服务应答（上限 BOOT_TIMEOUT_SEC）。
        val deadline = System.currentTimeMillis() + RuntimeConfig.BOOT_TIMEOUT_SEC * 1000
        while (System.currentTimeMillis() < deadline) {
          if (EngineProbe.isRunning()) {
            startEngineService()
            runOnUiThread { showWeb() }
            return@Thread
          }
          Thread.sleep(1000)
        }
        runOnUiThread {
          engineStatus.text = "引擎启动超时，请重试。"
          showGuide()
        }
      } finally {
        engineFlowRunning.set(false)
      }
    }.start()
  }

  /** 触发在线更新（adb 或 UI 按钮）；状态镜像到文件供 adb 校验。 */
  private fun runUpdate() {
    val statusFile = File(filesDir, "update-status.txt")
    val manager = UpdateManager(this)
    manager.checkAndApply({ status ->
      runOnUiThread {
        engineStatus.text = status
        progressText.visibility = View.VISIBLE
        showGuide()
        webView.visibility = View.GONE
      }
      try {
        statusFile.appendText(status + "\n")
      } catch (_: Exception) {
      }
    }) { pct ->
      runOnUiThread { engineStatus.text = "下载快照… $pct%" }
    }
  }

  private fun startEngineService() {
    try {
      startForegroundService(Intent(this, EngineService::class.java))
    } catch (_: Exception) {
      // 前台服务启动限制：下次启动时再补。
    }
  }

  /** 暂停引擎（省电）：停止引擎进程与前台服务，回到引导页。 */
  private fun pauseEngine() {
    engineManager.stopEngine()
    try {
      stopService(Intent(this, EngineService::class.java))
    } catch (_: Exception) {
    }
    runOnUiThread { showGuide() }
  }

  private fun showWeb() {
    guideView.visibility = View.GONE
    webView.visibility = View.VISIBLE
    webView.reload()
  }

  private fun showGuide() {
    webView.visibility = View.GONE
    guideView.visibility = View.VISIBLE
  }

  companion object {
    const val ACTION_UPDATE = "com.dshmobile.shell.action.UPDATE"
    const val MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024
    const val SESSION_EXPORT_PATH = "/api/session.export"
    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
  }
}
