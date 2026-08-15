package com.dshmobile.shell

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** 探测本机 dsh web 引擎（127.0.0.1:3080）的可达性与健康度。 */
object EngineProbe {

  /**
   * 一次性可达性探测。任意线程可调（勿在主线程）。
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
        .put("running", code == 200)
        .put("latencyMs", System.currentTimeMillis() - start)
        .put("httpCode", code)
    } catch (e: Exception) {
      JSONObject().put("running", false).put("error", e.message ?: "unknown")
    }
  }

  /** 引擎是否应答（HTTP 200）。 */
  fun isRunning(timeoutMs: Int = 800): Boolean = check(timeoutMs).optBoolean("running", false)
}
