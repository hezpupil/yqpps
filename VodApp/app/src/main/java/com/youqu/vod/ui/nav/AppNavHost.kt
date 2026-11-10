package com.youqu.vod.ui.nav

import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.youqu.vod.VodApplication
import com.youqu.vod.core.ApiResult
import com.youqu.vod.core.UiState
import com.youqu.vod.data.local.ActivationRepository
import com.youqu.vod.data.model.ShowData
import com.youqu.vod.di.viewModelFactory
import com.youqu.vod.ui.home.HomeScreen
import com.youqu.vod.ui.home.HomeViewModel
import com.youqu.vod.ui.hot.HotScreen
import com.youqu.vod.ui.hot.HotViewModel
import com.youqu.vod.ui.player.PlayerScreen
import com.youqu.vod.ui.player.PlayerViewModel
import com.youqu.vod.ui.search.SearchScreen
import com.youqu.vod.ui.search.SearchViewModel
import com.youqu.vod.ui.settings.SettingsScreen
import com.youqu.vod.ui.theme.AccentOrange
import com.youqu.vod.ui.update.DownloadProgressDialog
import com.youqu.vod.ui.update.UpdateFoundDialog
import com.youqu.vod.ui.update.UpdateViewModel
import com.youqu.vod.ui.theme.OledBlack
import com.youqu.vod.ui.theme.SurfaceDark
import com.youqu.vod.ui.theme.TextPrimary
import com.youqu.vod.ui.theme.TextTertiary
import com.youqu.vod.util.vid

const val ROUTE_HOME = "home"
const val ROUTE_HOT = "hot"
const val ROUTE_SETTINGS = "settings"
const val ROUTE_SEARCH = "search/{kw}/{order}/{page}"
const val ROUTE_PLAY = "play/{vid}"
const val PREFIX_PLAY = "play"
const val PREFIX_SEARCH = "search"

/** 排序与翻页时替换当前搜索页，避免在返回栈里堆出一串相同页面 */
fun NavController.navigateToSearch(keyword: String, order: Int, page: Int) {
    navigate("search/${Uri.encode(keyword)}/$order/$page") {
        popUpTo(ROUTE_SEARCH) { inclusive = true }
    }
}

/** 播放页之间切换（猜你喜欢）时替换当前页，避免返回栈无限增长 */
fun NavController.navigateToPlay(videoId: String) {
    navigate("play/${Uri.encode(videoId)}") {
        popUpTo(ROUTE_PLAY) { inclusive = true }
    }
}

@Composable
fun AppNavHost() {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as VodApplication
    val repository = app.container.repository
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route.orEmpty()

    // 更新检查：整个 APP 共用一个 ViewModel，冷启动静默检查一次
    val updateVm: UpdateViewModel = viewModel(factory = viewModelFactory {
        UpdateViewModel(app.container.updateRepository, app.container.apkDownloader)
    })
    val updateState = updateVm.state.collectAsStateWithLifecycle().value
    val context = LocalContext.current

    LaunchedEffect(Unit) { updateVm.check(fromUser = false) }

    // 只有用户主动触发的结果才弹 Toast，自动检查失败保持静默
    LaunchedEffect(updateState.message) {
        val msg = updateState.message
        if (msg != null) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            updateVm.consumeMessage()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = OledBlack,
        bottomBar = {
            if (!currentRoute.startsWith(PREFIX_PLAY)) {
                BottomBar(navController = navController, currentRoute = currentRoute)
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = ROUTE_HOME,
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .background(OledBlack)
        ) {
            composable(ROUTE_HOME) {
                val vm: HomeViewModel = viewModel(factory = viewModelFactory {
                    HomeViewModel(repository, app.container.domainRepository)
                })
                HomeScreen(
                    hotState = vm.hot.collectAsStateWithLifecycle().value,
                    recentState = vm.recent.collectAsStateWithLifecycle().value,
                    baseUrl = vm.baseUrl,
                    onRetry = vm::load,
                    onSearch = { keyword ->
                        app.container.settings.addHistory(keyword)
                        navController.navigateToSearch(keyword, 9, 1)
                    },
                    onVideoClick = { item -> navController.navigateToPlay(item.vid) }
                )
            }

            composable(ROUTE_HOT) {
                val vm: HotViewModel = viewModel(factory = viewModelFactory { HotViewModel(repository) })
                HotScreen(
                    state = vm.state.collectAsStateWithLifecycle().value,
                    onRetry = vm::load,
                    onWordClick = { word ->
                        app.container.settings.addHistory(word)
                        navController.navigateToSearch(word, 9, 1)
                    }
                )
            }

            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    settings = app.container.settings,
                    versionLabel = updateState.versionLabel,
                    checking = updateState.checking,
                    onCheckUpdate = { updateVm.check(fromUser = true) },
                    deviceCode = remember { ActivationRepository(app).deviceCode() },
                    // 刷新与内置网关共用的同一个 TokenProvider，刷新后新凭证立即生效
                    onRefreshToken = {
                        withContext(Dispatchers.IO) {
                            runCatching {
                                val c = app.tokenProvider.get(forceRefresh = true)
                                "device=${c.device} · ${c.baseUrl}"
                            }.getOrElse { "刷新失败：${it.message ?: "请检查网络"}" }
                        }
                    },
                    onDeviceActive = {
                        withContext(Dispatchers.IO) {
                            app.container.repository.deviceActive() is ApiResult.Ok<*>
                        }
                    },
                    // 点击历史关键词 → 重新搜索（独立搜索页）；点击播放记录 → 重新播放
                    onHistoryClick = { word ->
                        app.container.settings.addHistory(word)
                        navController.navigateToSearch(word, 9, 1)
                    },
                    onPlayClick = { record ->
                        navController.navigateToPlay(record.vid)
                    }
                )
            }

            composable(
                route = ROUTE_SEARCH,
                arguments = listOf(
                    navArgument("kw") { type = NavType.StringType },
                    navArgument("order") { type = NavType.IntType },
                    navArgument("page") { type = NavType.IntType }
                )
            ) { entry ->
                val keyword = entry.arguments?.getString("kw") ?: ""
                val order = entry.arguments?.getInt("order") ?: 9
                val page = entry.arguments?.getInt("page") ?: 1
                val vm: SearchViewModel = viewModel(
                    factory = viewModelFactory { SearchViewModel(repository, keyword, page, order) }
                )
                SearchScreen(
                    state = vm.state.collectAsStateWithLifecycle().value,
                    keyword = keyword,
                    order = order,
                    baseUrl = vm.baseUrl,
                    onRetry = vm::load,
                    onSearchSubmit = { newKeyword ->
                        if (newKeyword.isNotBlank()) {
                            app.container.settings.addHistory(newKeyword)
                            navController.navigateToSearch(newKeyword, order, 1)
                        }
                    },
                    onOrderChange = { navController.navigateToSearch(keyword, it, 1) },
                    onPageChange = { navController.navigateToSearch(keyword, order, it) },
                    onVideoClick = { item -> navController.navigateToPlay(item.vid) },
                    onBack = { navController.popBackStack() }
                )
            }

            composable(
                route = ROUTE_PLAY,
                arguments = listOf(navArgument("vid") { type = NavType.StringType })
            ) { entry ->
                val videoId = entry.arguments?.getString("vid") ?: ""
                val vm: PlayerViewModel = viewModel(
                    factory = viewModelFactory { PlayerViewModel(repository, videoId) }
                )
                val playerState = vm.state.collectAsStateWithLifecycle().value
                // 详情加载成功后记一条播放历史（与 iOS 设置页的播放记录对应）
                LaunchedEffect(playerState, videoId) {
                    val data = (playerState as? UiState.Success<*>)?.data as? ShowData
                        ?: return@LaunchedEffect
                    val title = data.video?.title?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
                    app.container.settings.addPlayHistory(videoId, title)
                }
                PlayerScreen(
                    state = playerState,
                    videoId = vm.videoId,
                    baseUrl = vm.baseUrl,
                    onRetry = vm::load,
                    onHeartbeat = vm::reportPlaytime,
                    onBack = { navController.popBackStack() },
                    onHome = {
                        navController.navigate(ROUTE_HOME) {
                            popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                            launchSingleTop = true
                        }
                    },
                    onVideoClick = { item -> navController.navigateToPlay(item.vid) }
                )
            }
        }
    }

    if (updateState.progress != null) {
        DownloadProgressDialog(progress = updateState.progress)
    } else {
        updateState.available?.let { info ->
            UpdateFoundDialog(
                info = info,
                onConfirm = updateVm::downloadAndInstall
            )
        }
    }
    }
}

@Composable
private fun BottomBar(navController: NavHostController, currentRoute: String) {
    val selectedHome = currentRoute == ROUTE_HOME
    val selectedHot = currentRoute == ROUTE_HOT || currentRoute.startsWith(PREFIX_SEARCH)

    NavigationBar(
        containerColor = SurfaceDark,
        contentColor = TextPrimary
    ) {
        NavigationBarItem(
            selected = selectedHome,
            onClick = { navController.navigateTab(ROUTE_HOME) },
            icon = { Icon(Icons.Default.Home, contentDescription = "首页") },
            label = { Text("首页", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = AccentOrange,
                selectedTextColor = AccentOrange,
                indicatorColor = Color.Transparent,
                unselectedIconColor = TextTertiary,
                unselectedTextColor = TextTertiary
            )
        )
        NavigationBarItem(
            selected = selectedHot,
            onClick = { navController.navigateTab(ROUTE_HOT) },
            icon = { Icon(Icons.AutoMirrored.Default.List, contentDescription = "热搜") },
            label = { Text("热搜", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = AccentOrange,
                selectedTextColor = AccentOrange,
                indicatorColor = Color.Transparent,
                unselectedIconColor = TextTertiary,
                unselectedTextColor = TextTertiary
            )
        )
        NavigationBarItem(
            selected = currentRoute == ROUTE_SETTINGS,
            onClick = { navController.navigateTab(ROUTE_SETTINGS) },
            icon = { Icon(Icons.Default.Settings, contentDescription = "设置") },
            label = { Text("设置", fontSize = 11.sp) },
            colors = NavigationBarItemDefaults.colors(
                selectedIconColor = AccentOrange,
                selectedTextColor = AccentOrange,
                indicatorColor = Color.Transparent,
                unselectedIconColor = TextTertiary,
                unselectedTextColor = TextTertiary
            )
        )
    }
}

private fun NavController.navigateTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
