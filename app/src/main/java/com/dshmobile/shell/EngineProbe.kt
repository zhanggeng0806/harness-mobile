package com.dshmobile.shell

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** 探测本机 dsh web 引擎（127.0.0.1:3080）的可达性与健康度。 */
object EngineProbe {

  /**
   * 一次性可达性探测。任意线程可调（勿在主线程）。
   *
   * dsh web 现在自带浏览器鉴权：未携带会话 Cookie 的请求一律收到 401。
   * 因此只认 200 会把「健康但未鉴权」的引擎判成死亡——看门狗会在 60s 后
   * 杀掉正在正常服务的引擎。这里只区分「有 HTTP 应答」与「连接失败」。
   *
   * @param timeoutMs connect+read 预算（毫秒）。
   * @return {running:Boolean, latencyMs:Int, httpCode:Int?, error:String?}
   */
  fun check(timeoutMs: Int = 800): JSONObject {
    return try {
      val conn = URL(RuntimeConfig.ENGINE_URL).openConnection() as HttpURLConnection
      conn.connectTimeout = timeoutMs
      conn.readTimeout = timeoutMs
      conn.requestMethod = "GET"
      val start = System.currentTimeMillis()
      val code = conn.responseCode
      conn.disconnect()
      JSONObject()
        .put("running", isServingCode(code))
        .put("latencyMs", System.currentTimeMillis() - start)
        .put("httpCode", code)
    } catch (e: Exception) {
      JSONObject().put("running", false).put("error", e.message ?: "unknown")
    }
  }

  /**
   * 200 = 已鉴权（Cookie 有效），401 = 服务在监听但本请求未鉴权。
   * 两者都证明引擎进程活着并在应答，只有连接失败才算不可用。
   */
  private fun isServingCode(code: Int): Boolean =
    code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_UNAUTHORIZED

  /** 引擎是否应答（HTTP 200 或鉴权 401）。 */
  fun isRunning(timeoutMs: Int = 800): Boolean = check(timeoutMs).optBoolean("running", false)
}
