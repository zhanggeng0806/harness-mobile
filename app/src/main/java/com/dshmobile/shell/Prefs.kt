package com.dshmobile.shell

import android.content.Context
import android.content.SharedPreferences

/** 用户可配置项（更新服务器地址等）的轻量存储。 */
class Prefs(context: Context) {
  private val sp: SharedPreferences =
    context.getSharedPreferences("dsh-mobile", Context.MODE_PRIVATE)

  var manifestUrl: String
    get() = sp.getString(KEY_MANIFEST_URL, RuntimeConfig.DEFAULT_MANIFEST_URL) ?: ""
    set(value) = sp.edit().putString(KEY_MANIFEST_URL, value).apply()

  companion object {
    private const val KEY_MANIFEST_URL = "manifest_url"
  }
}
