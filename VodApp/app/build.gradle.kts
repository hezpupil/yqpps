import java.util.Properties

plugins {
    // AGP 9 内置 Kotlin，不能再应用 org.jetbrains.kotlin.android（会冲突）
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 正式签名配置从 keystore.properties 读取（该文件不入库，见 .gitignore）。
// 若文件缺失（例如别人 clone 了工程），release 仍可构建，只是产出未签名包。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val hasSigningConfig = keystorePropsFile.exists()

// 版本号集中定义，defaultConfig 与产物命名共用，避免两处不同步。
// 升级时改这里 + tools/update.json 的 versionCode/versionName/apkUrl/iosUrl/changelog；
// CI 编译完会自动把 update.json 的 size 回填成本次 APK 真实字节数，
// 再连同两个安装包一起上传到自有服务器（不创建 GitHub Release）。
val appVersionCode = 30
val appVersionName = "1.0.25"

android {
    namespace = "com.youqu.vod"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.youqu.vod"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // versionCode 必须大于服务器 update.json 里声明的值，否则会被判定为「有更新」而回退覆盖。
        // 1.0.16 用 21：新增设备激活门禁（云端验证），未激活设备首次启动需输入激活码。
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")

                // 正式包启用 V1+V2+V3 签名，兼容 Android 7 以下与 Android 9+
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            // 便于同机安装对比正式包
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 9 起使用 compilerOptions DSL（kotlinOptions 已废弃并会导致脚本编译失败）
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// 正式包按版本号命名：youqu-<versionName>.apk（如 youqu-1.0.25.apk），
// 与 CI 交付名、服务器 /app/apk/ 下的文件名、update.json 的 apkUrl 完全一致。
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("youqu-$appVersionName.apk")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Compose（BOM 统一版本）
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    // 网络
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.moshi.kotlin)

    // 图片
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // 播放：Media3 ExoPlayer（官方维护，原生库支持 16KB 页）+ 本地取流代理（OkHttp）
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
}
