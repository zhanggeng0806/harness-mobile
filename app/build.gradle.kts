plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "com.dshmobile.shell"
  compileSdk = 34

  defaultConfig {
    applicationId = "com.dshmobile.shell"
    minSdk = 26
    // targetSdk 34：Android 15+ 禁止 targetSdk 35+ 的应用直接 exec 自身
    // app-data 里的 ELF（内嵌引擎/bash/子命令都会命中）；保持 34 让直连
    // exec 在 Android 15/16 上仍然可用，必要时再叠加 linker64 回退。
    targetSdk = 34
    versionCode = 1
    versionName = "0.1.0"
  }

  // 本机 ~/.android 在沙箱内不可写，debug 签名密钥库固定放工程内。
  signingConfigs {
    getByName("debug") {
      storeFile = file("debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions {
    jvmTarget = "17"
  }
}

dependencies {
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.activity:activity-ktx:1.9.1")
  // tar 解析（快照为 tar.gz：GZIP 用 JDK 自带，tar 用 commons-compress）。
  implementation("org.apache.commons:commons-compress:1.21")
}
