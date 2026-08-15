package com.dshmobile.shell

import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * JS 桥：以 window.androidBridge 注入到引擎页面（协议 v1）。
 * 异步结果（目录选择）经 window.__dshBridge.onDirectoryPicked(callbackId, path)
 * 在主线程回传。
 *
 * 全部方法都能被页面调用；页面侧按 androidBridge.version 做特性检测，
 * 使 APK 与 dsh 版本解耦。
 */
class AndroidBridge(
  private val onPickRequest: (callbackId: String) -> Unit,
  private val onKeepScreen: (enable: Boolean) -> Unit,
  private val onNotify: (title: String, text: String) -> Unit,
  private val onAllFilesAccessRequest: () -> Unit = {},
  private val onPauseEngine: () -> Unit = {},
  private val pickToken: String? = null,
  private val engineToken: String? = null,
) {

  @JavascriptInterface
  fun version(): String = "1.0"

  @JavascriptInterface
  fun checkEngine(): String = EngineProbe.check().toString()

  @JavascriptInterface
  fun keepScreenOn(enable: Boolean) {
    onKeepScreen(enable)
  }

  @JavascriptInterface
  fun showNotification(title: String, text: String) {
    onNotify(title, text)
  }

  @JavascriptInterface
  fun pickDirectory(callbackId: String) {
    onPickRequest(callbackId)
  }

  /** 暂停引擎（省电）：停止引擎进程与前台服务，回到引导页。 */
  @JavascriptInterface
  fun pauseEngine() {
    onPauseEngine()
  }

  /** 是否持有 All Files Access（外部工作区要求）。 */
  @JavascriptInterface
  fun hasAllFilesAccess(): Boolean {
    if (android.os.Build.VERSION.SDK_INT < 30) return false
    return android.os.Environment.isExternalStorageManager()
  }

  /** 打开系统 All Files Access 授权页。 */
  @JavascriptInterface
  fun requestAllFilesAccess() {
    onAllFilesAccessRequest()
  }

  /** 目录选择桥的一次性会话 token（引擎侧 pick 端点校验；null = 未启用）。 */
  @JavascriptInterface
  fun getPickToken(): String? = pickToken

  /** 本地引擎 API 鉴权 token（供 dsh 侧插件对 HTTP 请求校验的钩子）。 */
  @JavascriptInterface
  fun getEngineToken(): String? = engineToken

  companion object {
    /**
     * 把 ACTION_OPEN_DOCUMENT_TREE 结果映射为 Termux 可见的真实路径：
     * "primary:rel/path" → /storage/emulated/0/rel/path；非 primary 卷回退
     * 原始 content:// tree URI（页面仍可当不透明句柄使用）。
     */
    fun resolvePickedPath(uri: Uri): String {
      return try {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val idx = docId.indexOf(':')
        val volume = if (idx > 0) docId.substring(0, idx) else ""
        val rel = if (idx > 0) docId.substring(idx + 1) else docId
        if (volume == "primary" && rel.isNotEmpty()) "/storage/emulated/0/$rel" else uri.toString()
      } catch (_: Exception) {
        uri.toString()
      }
    }
  }
}

/** evaluateJavascript 载荷的 JSON 字符串字面量转义。 */
internal fun jsString(value: String): String = JSONObject.quote(value)
