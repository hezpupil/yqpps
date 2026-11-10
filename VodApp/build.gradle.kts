// 显式把 Kotlin Gradle Plugin 放到 classpath 上：
// AGP 9 内置 Kotlin 后默认使用自带的 Kotlin 版本，这里锁定 2.4.20，
// 使其与 org.jetbrains.kotlin.plugin.compose 的版本严格一致（两者必须同版本）。
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

// 根构建脚本：只声明插件，不写具体构建逻辑
// 注意：AGP 9 起不再需要 org.jetbrains.kotlin.android（Kotlin 支持已内置）
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
