// 顶层构建脚本：仅声明插件版本（apply false），各模块按需应用。
plugins {
  id("com.android.application") version "8.5.2" apply false
  id("org.jetbrains.kotlin.android") version "2.0.20" apply false
}
