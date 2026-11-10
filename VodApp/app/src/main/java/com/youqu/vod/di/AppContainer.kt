package com.youqu.vod.di

import android.content.Context
import android.content.pm.ApplicationInfo
import com.youqu.vod.data.local.SettingsRepository
import com.youqu.vod.data.remote.HttpFactory
import com.youqu.vod.data.repo.DomainRepository
import com.youqu.vod.data.repo.UpdateRepository
import com.youqu.vod.data.repo.VodRepository
import com.youqu.vod.update.ApkDownloader

/**
 * 手写依赖容器（刻意不用 Hilt）：
 * AGP 9 的内置 Kotlin 与 kapt 不兼容、对 KSP 版本也有约束，
 * 本项目规模不需要注解处理器，去掉它能显著降低「打开工程编译不过」的概率。
 */
class AppContainer(context: Context) {

    val settings: SettingsRepository
    val repository: VodRepository
    val updateRepository: UpdateRepository
    val apkDownloader: ApkDownloader
    val domainRepository: DomainRepository

    init {
        val appContext = context.applicationContext
        settings = SettingsRepository(appContext)

        val debuggable = (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val okHttp = HttpFactory.createOkHttp(debug = debuggable)
        val moshi = HttpFactory.createMoshi()
        val api = HttpFactory.createApi(settings.baseUrl, okHttp, moshi)

        domainRepository = DomainRepository(settings, HttpFactory.createDownloadOkHttp())
        repository = VodRepository(api, settings, domainRepository)
        updateRepository = UpdateRepository(appContext, settings, HttpFactory.createDownloadOkHttp())
        apkDownloader = ApkDownloader(appContext, HttpFactory.createDownloadOkHttp())
    }
}

/** 通用 ViewModel 工厂，省去每个页面写一遍 Factory */
@Suppress("UNCHECKED_CAST")
fun <VM : androidx.lifecycle.ViewModel> viewModelFactory(create: () -> VM): androidx.lifecycle.ViewModelProvider.Factory =
    object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
            create() as T
    }
