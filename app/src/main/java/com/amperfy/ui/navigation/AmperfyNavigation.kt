/*
 * Amperfy4Android - an unofficial Android port of Amperfy
 * Copyright (c) 2026 angelo
 * Based on Amperfy for iOS, Copyright (c) 2019-2025 Maximilian Bauer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.amperfy.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.amperfy.core.AccountManager
import com.amperfy.core.AppDelegate
import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.local.SettingsManager
import com.amperfy.data.repository.MusicRepository
import com.amperfy.ui.components.AccountMenuButton
import com.amperfy.ui.components.FloatingTabBar
import com.amperfy.ui.components.IOSMiniPlayer
import com.amperfy.ui.components.SearchBottomBar
import com.amperfy.ui.components.SheetSystemBarsFix
import com.amperfy.ui.components.iosOverscroll
import com.amperfy.ui.screens.*
import com.amperfy.ui.screens.home.HomeScreen
import com.amperfy.ui.screens.player.PopupPlayerScreen
import com.amperfy.ui.theme.AmperfyIcons
import com.amperfy.ui.theme.sheetBackground
import com.amperfy.ui.theme.sheetGroupedBackground
import com.amperfy.ui.theme.sheetSecondaryGroupedBackground
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * CompositionLocal用于在整个应用中传递MiniPlayer的高度
 * 当MiniPlayer显示时,包含MiniPlayer的高度(48dp),否则为0dp
 */
val LocalMiniPlayerHeight = compositionLocalOf { 0.dp }

// ==================== 详情路由导航辅助 ====================
// id 经 Uri.encode（Subsonic 不保证 id 无 '/'、'?' 等特殊字符）；
// launchSingleTop 防止双击行时同一详情页压栈两次

private fun NavHostController.navigateToArtistDetail(artistId: String) =
    navigate("artist/${android.net.Uri.encode(artistId)}") { launchSingleTop = true }

private fun NavHostController.navigateToAlbumDetail(albumId: String, from: String) =
    navigate("album/${android.net.Uri.encode(albumId)}?from=$from") { launchSingleTop = true }

private fun NavHostController.navigateToPlaylistDetail(playlistId: String) =
    navigate("playlist/${android.net.Uri.encode(playlistId)}") { launchSingleTop = true }

private fun NavHostController.navigateToGenreDetail(genreName: String) =
    navigate("genre/${android.net.Uri.encode(genreName)}") { launchSingleTop = true }

private fun NavHostController.navigateToPodcastDetail(podcastId: String) =
    navigate("podcast/${android.net.Uri.encode(podcastId)}") { launchSingleTop = true }

// 目录详情不可用 launchSingleTop：目录→子目录是同一路由模式（directory/{directoryId}），
// singleTop 会判定"已在栈顶"而不压栈也不更新参数，导致递归下钻点击无反应；
// iOS 为 DirectoriesVC 递归 push（DirectoriesVC.swift didSelectRowAt），层数不限
// parentName：返回按钮显示的上级页名（iOS 返回按钮自动取上一页 title——
// 上级为 IndexesVC 时是文件夹名，为 DirectoriesVC 时是父目录名）
private fun NavHostController.navigateToDirectoryDetail(directoryId: String, parentName: String) =
    navigate("directory/${android.net.Uri.encode(directoryId)}?parentName=${android.net.Uri.encode(parentName)}")

// ============================================================================
// iOS push/pop 水平滑动转场（对齐 UINavigationController 系统默认转场）：
// push：新页从右侧滑入、旧页向左平移约 1/3（视差）；pop 反向。
// 时长/曲线为 iOS 系统动画的工程近似（≈0.35s easeInEaseOut）。
// 层级（新页盖旧页、pop 时旧页盖父页）由 navigation-compose 按返回栈
// 位置设置 zIndex 自动保证，无需手动处理。
// 三个 Tab 的 NavHost（Library/Search/Settings）共用本组转场。
// ============================================================================

private const val NAV_TRANSITION_MS = 350

private val iosPushEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideIntoContainer(
        AnimatedContentTransitionScope.SlideDirection.Left,
        animationSpec = tween(NAV_TRANSITION_MS, easing = FastOutSlowInEasing)
    )
}

private val iosPushExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutOfContainer(
        AnimatedContentTransitionScope.SlideDirection.Left,
        animationSpec = tween(NAV_TRANSITION_MS, easing = FastOutSlowInEasing),
        targetOffset = { it / 3 }   // 底层页仅移出 1/3，模拟 iOS parallax
    )
}

private val iosPopEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideIntoContainer(
        AnimatedContentTransitionScope.SlideDirection.Right,
        animationSpec = tween(NAV_TRANSITION_MS, easing = FastOutSlowInEasing),
        initialOffset = { it / 3 }  // 父页从左侧 1/3 处滑回
    )
}

private val iosPopExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutOfContainer(
        AnimatedContentTransitionScope.SlideDirection.Right,
        animationSpec = tween(NAV_TRANSITION_MS, easing = FastOutSlowInEasing)
    )
}

/**
 * Amperfy主导航结构
 *
 * 完全模仿iOS版本的TabBar结构:
 * - 首先检查登录状态
 * - 未登录显示LoginScreen
 * - 已登录但未完成初始同步显示InitialSyncScreen
 * - 已登录且完成初始同步显示主界面(底部有三个主标签: Library, Search, Settings)
 * - 底部固定显示Mini Player
 *
 * @param credentialsManager 通过 Hilt 在 MainActivity 中注入的单例实例
 * @param musicRepository 通过 Hilt 在 MainActivity 中注入的单例实例
 * @param settingsManager 通过 Hilt 在 MainActivity 中注入的单例实例
 */
@Composable
fun AmperfyNavigation(
    credentialsManager: CredentialsManager,
    musicRepository: MusicRepository,
    settingsManager: SettingsManager
) {
    // 通过 CompositionLocalProvider 提供全局单例
    // 这样所有子 Composable 都可以通过 LocalXxxManager.current 访问
    CompositionLocalProvider(
        LocalCredentialsManager provides credentialsManager,
        LocalMediaUrlRepository provides musicRepository,
        LocalSettingsManager provides settingsManager
    ) {
        AmperfyNavigationContent()
    }
}

/**
 * 根导航 ViewModel（W6）——仅暴露 AccountManager，驱动登录/同步/主界面的响应式分流。
 * 对齐 iOS：active 账户切换后换根 VC，Android 侧由 activeAccount 流触发重组。
 */
@HiltViewModel
class RootNavViewModel @Inject constructor(
    appDelegate: AppDelegate
) : ViewModel() {
    val accounts: AccountManager = appDelegate.accounts
}

/**
 * 导航内容 - 分离出来以便在 CompositionLocalProvider 内部使用
 *
 * W6：登录态分流改为响应式——由 AccountManager.activeAccount 驱动。
 * - activeAccount == null → LoginScreen
 * - 非 null 且 needsInitialSync(ident) → InitialSyncScreen（以 ident 为 key 重算）
 * - 否则 → MainScreen
 */
@Composable
private fun AmperfyNavigationContent() {
    val rootViewModel: RootNavViewModel = hiltViewModel()

    val accounts = rootViewModel.accounts
    val activeAccount by accounts.activeAccount.collectAsState()

    val account = activeAccount
    if (account == null) {
        // 未登录：登录成功后由 activeAccount 流驱动切换（W5 login 发 ActiveChanged），
        // 故 onLoginSuccess 无需再手动置位本地状态。
        LoginScreen(
            onLoginSuccess = { }
        )
    } else {
        // 以 active 账户 ident 为 remember key：切换账户时重算初始同步需求
        val ident = account.info.ident
        var needsInitialSync by remember(ident) {
            mutableStateOf(accounts.needsInitialSync(ident))
        }
        if (needsInitialSync) {
            // 初始同步屏幕（对应 iOS 的 SyncVC）——完成置位本地状态
            InitialSyncScreen(
                onSyncCompleted = { needsInitialSync = false }
            )
        } else {
            // 对齐 iOS switchAccount → replaceMainRootViewController：账户切换时整个主界面
            // （NavController/ViewModel 全部）随 ident 重建，杜绝构造期冻结旧账户的 Flow
            key(ident) {
                MainScreen(accounts = accounts)
            }
        }
    }
}

/**
 * 主界面 - 已登录后显示
 *
 * W6：三 Tab 换血为 Home / Library / Search（Settings 移出为全屏覆盖层）。
 * Home 默认选中，对齐 iOS selectedTab = homeTab。
 *
 * @param accounts 账户管理器（AmperfyNavigationContent 下传）。账户切换（换根）由外层
 *   `key(ident)` 整体重建本 Composable 承担，本参数仅供 AccountMenuButton 等内部功能使用。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(accounts: AccountManager) {
    // 为每个Tab创建独立的NavController（+Home）
    val homeNavController = rememberNavController()
    val libraryNavController = rememberNavController()
    val searchNavController = rememberNavController()
    // Settings 出栈为全屏覆盖层，仍复用独立 NavController
    val settingsNavController = rememberNavController()

    // Home 默认选中（对齐 iOS selectedTab = homeTab）
    var selectedTab by remember { mutableStateOf(BottomTab.HOME) }

    // 搜索激活的「来源 tab」（对齐 iOS UISearchTab：激活搜索前所在的 Home/Library）：
    // selectedTab 落到 HOME/LIBRARY 时更新；搜索态下左上角圆钮与 X 关闭均回到此 tab。
    var lastNonSearchTab by remember { mutableStateOf(BottomTab.HOME) }
    LaunchedEffect(selectedTab) {
        if (selectedTab != BottomTab.SEARCH) lastNonSearchTab = selectedTab
    }

    // 搜索激活计数器（对应 iOS TabBarVC.automaticallyActivatesSearch = true）：
    // 每次点击底部 Search 圆钮（无论是否已在 Search tab）递增，触发底部搜索条重新聚焦弹键盘。
    var searchActivationTick by remember { mutableIntStateOf(0) }

    // 共享 SearchViewModel（activity 作用域）——底部搜索输入条与 SearchScreen 结果页同用一实例，
    // 保证输入驱动结果。SearchNavGraph 内 SearchScreen 需显式传入本实例（默认 hiltViewModel 为 nav 作用域，另一实例）。
    //
    // Activity 级共享 VM 必须按 active ident 加 key：外层 key(ident) 只重建组合与 NavHost 级 VM，
    // 管不到 Activity 作用域——不加 key 则切账户后仍复用旧实例，其构造期经 appDelegate 域入口
    // 冻结的 Flow（searchHistory/getAllSongs）永久绑定旧账户（搜索历史跨账户串台的根因）。
    // 同一账户内仍是共享单例，跨账户才换新；旧实例滞留 Activity store（每账户一个，量级可忽略）。
    val activeIdent = accounts.activeAccountId ?: ""
    val sharedSearchViewModel: SearchViewModel = hiltViewModel(key = "search:$activeIdent")
    val searchQuery by sharedSearchViewModel.query.collectAsState()
    // 底部搜索输入条焦点请求器（对应 iOS SearchVC.activateSearchBar）
    val bottomSearchFocusRequester = remember { FocusRequester() }

    // 用状态控制播放器显示，而不是通过导航
    var showPlayer by remember { mutableStateOf(false) }
    // Settings 全屏覆盖层显示态（模式同 showPlayer）
    var showSettings by remember { mutableStateOf(false) }
    // Add Account 独立模态显示态（对齐 iOS：账户菜单 Add Account 直接以 formSheet present LoginVC，
    // 不经 Settings 覆盖层——见 CommonScreenOperations.swift；与 showSettings 并列的单页模态）
    var showAddAccount by remember { mutableStateOf(false) }

    // 同款按 ident 加 key（LibraryViewModel.recentSongs 构造期冻结 appDelegate.library，潜伏同族）
    val libraryViewModel: LibraryViewModel = hiltViewModel(key = "library:$activeIdent")
    val currentSong by libraryViewModel.currentPlayingSong.collectAsState()
    val isPlaying by libraryViewModel.isPlaying.collectAsState()
    val currentPosition by libraryViewModel.currentPosition.collectAsState()
    val duration by libraryViewModel.duration.collectAsState()

    // Track current route to hide bottom bar on fullscreen player (保留用于其他用途)
    val currentNavController = when (selectedTab) {
        BottomTab.HOME -> homeNavController
        BottomTab.LIBRARY -> libraryNavController
        BottomTab.SEARCH -> searchNavController
    }

    // 监听导航变化来控制播放器显示
    val currentRoute by currentNavController.currentBackStackEntryFlow
        .map { it.destination.route }
        .collectAsState(initial = null)

    // 当导航到player路由时，显示播放器并立即返回
    LaunchedEffect(currentRoute) {
        if (currentRoute == "player") {
            showPlayer = true
            currentNavController.popBackStack()
        }
    }

    // 关闭 Settings 覆盖层时复位其 NavController 回 settings_main（下次打开不停留深层）；
    // currentDestination 判空避免图未设置时误调 popBackStack
    LaunchedEffect(showSettings) {
        if (!showSettings && settingsNavController.currentDestination != null) {
            settingsNavController.popBackStack("settings_main", inclusive = false)
        }
    }

    // 用户按钮插槽（三个 Tab 顶栏共用；状态留在 MainScreen）
    val accountMenu: @Composable () -> Unit = {
        AccountMenuButton(
            onOpenSettings = { showSettings = true },
            // 对齐 iOS：直接弹独立 Add Account 模态，不再经 Settings 覆盖层内 push
            onAddAccount = { showAddAccount = true }
        )
    }

    // 动态获取TabBar的实际高度
    var tabBarHeight by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current

    // 获取底部导航栏/手势条的高度（适配不同手机的手势区域）
    val navigationBarPadding = WindowInsets.navigationBars
        .asPaddingValues()
        .calculateBottomPadding()

    // 键盘可见态（对齐 iOS：键盘弹出时 accessory 不可见）——用于键盘弹起时隐藏 MiniPlayer 浮层
    val imeVisible = WindowInsets.isImeVisible

    // MiniPlayer的bounds（用于PopupPlayerScreen的dismiss动画）
    var miniPlayerBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

    // MiniPlayer是否显示以及其高度
    // iOS: MiniPlayer always visible (shows "No music playing" when currentSong is null)
    val miniPlayerHeight = if (!showPlayer) 48.dp else 0.dp

    // 底部栏收缩状态（对齐 iOS TabBarVC.tabBarMinimizeBehavior = .onScrollDown）：
    // 统一规则——任何 tab、任何页面深度，内容实际向下滚动即收缩、向上滚动即展开，
    // 滚不动不反应；无按页面 / 按 tab 的例外。具体机制见 BottomBarState（同包）。
    val bottomBarState = rememberBottomBarState()
    // 任何 tab 切换都复位展开（离开一个 tab 时的收缩态不带到下一个 tab）。
    LaunchedEffect(selectedTab) { bottomBarState.expand() }
    // 搜索态锁定滚动收缩：Search tab 下底部栏为独立搜索排，不参与 onScrollDown。
    SideEffect { bottomBarState.scrollLocked = selectedTab == BottomTab.SEARCH }
    val minimizeProgress by animateFloatAsState(
        targetValue = if (bottomBarState.minimized) 1f else 0f,
        animationSpec = spring(
            dampingRatio = 0.85f,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "tabBarMinimize"
    )

    // 使用Box作为根容器，让MiniPlayer可以浮动在content之上
    Box(modifier = Modifier.fillMaxSize()) {
        // 提供MiniPlayer高度给所有子组件
        CompositionLocalProvider(LocalMiniPlayerHeight provides miniPlayerHeight) {
            // 主内容区域（包含TabBar）
            Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),  // 适配系统栏(状态栏+导航栏)
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            bottomBar = {
                // TabBar始终显示；占位高度固定为 expanded 态（TAB_BAR_HEIGHT），
                // 最小化只在其内部做视觉动画，不改变 Scaffold content padding（防列表 reflow）
                Box(
                    modifier = Modifier.onGloballyPositioned { coordinates ->
                        // 将像素高度转换为dp并保存
                        tabBarHeight = with(density) { coordinates.size.height.toDp() }
                    }
                ) {
                    FloatingTabBar(
                        selectedTab = selectedTab,
                        onTabSelected = { tab ->
                            // 点击 Search 圆钮即激活搜索框（含从其他 tab 切来和已在 Search 再点），
                            // 对应 iOS TabBarVC.automaticallyActivatesSearch = true
                            if (tab == BottomTab.SEARCH) {
                                searchActivationTick++
                            }
                            selectedTab = tab
                        },
                        minimizeProgress = minimizeProgress,
                        onExpand = { bottomBarState.expand() },
                        // 搜索激活态：本组件让位，底部搜索条由下方浮层渲染
                        searchActive = selectedTab == BottomTab.SEARCH
                    )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .nestedScroll(bottomBarState.nestedScrollConnection)
            ) {
                // 使用三个独立的NavHost，根据selectedTab控制可见性
                // Home Tab Navigation Stack
                Box(modifier = Modifier.fillMaxSize()) {
                    if (selectedTab == BottomTab.HOME) {
                        HomeNavGraph(
                            navController = homeNavController,
                            accountMenu = accountMenu,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Library Tab Navigation Stack
                Box(modifier = Modifier.fillMaxSize()) {
                    if (selectedTab == BottomTab.LIBRARY) {
                        LibraryNavGraph(
                            navController = libraryNavController,
                            accountMenu = accountMenu,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Search Tab Navigation Stack
                Box(modifier = Modifier.fillMaxSize()) {
                    if (selectedTab == BottomTab.SEARCH) {
                        SearchNavGraph(
                            navController = searchNavController,
                            // 透传共享 SearchViewModel，与底部搜索输入条共用一实例
                            searchViewModel = sharedSearchViewModel,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        // MiniPlayer浮动层 - expanded 态紧贴 TabBar 顶部（间距 6dp，对齐 iOS UITabAccessory）；
        // minimized 态随动画下落到底行、左侧让出 48+12dp 给小圆钮、右侧让出 48+12dp 给常驻 Search 圆（对齐 iOS refreshForTabAccessoryTraitChange）。
        // 始终显示（即使 PopupPlayerScreen 打开或没有歌曲播放）。
        // 键盘弹起时隐藏（对齐 iOS：搜索键盘弹出时 accessory 不可见，输入条左侧亦无按钮）。
        if (tabBarHeight > 0.dp && !imeVisible) {
            // 底部内边距随 minimized 动画在「tab bar 上方」与「底行」之间过渡
            val miniExpandedBottom = tabBarHeight + navigationBarPadding + 6.dp
            val miniMinimizedBottom = navigationBarPadding + 6.dp
            val miniBottomPadding = lerp(miniExpandedBottom, miniMinimizedBottom, minimizeProgress)
            // 左侧留白随 minimized 增大：20dp → 20 + 48(小圆钮) + 12(间距) = 80dp
            val miniStartPadding = lerp(20.dp, 80.dp, minimizeProgress)
            // 右侧留白随 minimized 增大：20dp → 20 + 48(常驻 Search 圆) + 12(间距) = 80dp，避免胶囊与 Search 圆重叠
            val miniEndPadding = lerp(20.dp, 80.dp, minimizeProgress)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = miniBottomPadding)
                    .onGloballyPositioned { coordinates ->
                        val position = coordinates.positionInWindow()
                        val size = coordinates.size
                        miniPlayerBounds = androidx.compose.ui.geometry.Rect(
                            left = position.x,
                            top = position.y,
                            right = position.x + size.width,
                            bottom = position.y + size.height
                        )
                    }
            ) {
                IOSMiniPlayer(
                    song = currentSong,  // Can be null - displays "No music playing"
                    isPlaying = isPlaying,
                    currentPosition = currentPosition,
                    duration = duration,
                    onPlayPauseClick = { libraryViewModel.playPause() },
                    onNextClick = { libraryViewModel.playNext() },
                    onClick = {
                        // 直接显示播放器，不通过导航
                        showPlayer = true
                    },
                    startPadding = miniStartPadding,
                    endPadding = miniEndPadding,
                    // 最小化态收起 Next 按钮（inline 态 nextButton 隐藏、play 贴 trailing）
                    showNextButton = minimizeProgress < 0.5f
                )
            }
        }

        // 全屏播放器浮动层 - 直接显示，不用AnimatedVisibility以支持下滑时显示背后视图
        if (showPlayer) {
            PopupPlayerScreen(
                onBackClick = {
                    // 关闭播放器
                    showPlayer = false
                },
                onNavigateToAlbum = { albumId ->
                    // 导航到 AlbumDetailScreen
                    // 对应iOS: PopupPlayerVC.displayAlbumDetail()
                    val navController = when (selectedTab) {
                        BottomTab.HOME -> homeNavController
                        BottomTab.LIBRARY -> libraryNavController
                        BottomTab.SEARCH -> searchNavController
                    }
                    navController.navigateToAlbumDetail(albumId, "player")
                },
                onNavigateToArtist = { artistId ->
                    // 导航到 ArtistDetailScreen
                    // 对应iOS: PopupPlayerVC.displayArtistDetail()
                    val navController = when (selectedTab) {
                        BottomTab.HOME -> homeNavController
                        BottomTab.LIBRARY -> libraryNavController
                        BottomTab.SEARCH -> searchNavController
                    }
                    navController.navigateToArtistDetail(artistId)
                },
                miniPlayerBounds = miniPlayerBounds
            )
        }
        } // 关闭CompositionLocalProvider

        // ===== 搜索激活态底部搜索条浮层（对齐 iOS UISearchTab 激活态）=====
        // 布局从左到右：来源 tab 小圆钮 + 输入条（占剩余宽度）+ X 关闭钮。
        // 键盘弹出时经 imePadding 浮在键盘上方；MiniPlayer 浮层不带 imePadding，被键盘遮住即可，
        // 键盘收起后重现于本排上方。本排渲染于 MiniPlayer 浮层之后（z 序更高），键盘弹起重叠时覆盖之。
        if (selectedTab == BottomTab.SEARCH) {
            // 进入搜索态 / 再次点击 Search 圆（tick++）即请求焦点弹键盘
            // （对应 iOS TabBarVC.automaticallyActivatesSearch + SearchVC.activateSearchBar）
            LaunchedEffect(selectedTab, searchActivationTick) {
                if (selectedTab == BottomTab.SEARCH) bottomSearchFocusRequester.requestFocus()
            }
            SearchBottomBar(
                returnTab = lastNonSearchTab,
                query = searchQuery,
                onQueryChange = { sharedSearchViewModel.onQueryChange(it) },
                focusRequester = bottomSearchFocusRequester,
                onReturnToTab = { selectedTab = lastNonSearchTab },
                onClose = {
                    sharedSearchViewModel.onQueryChange("")
                    selectedTab = lastNonSearchTab
                },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // Settings 模态覆盖层（W6）——与 Home Preferences 同款 ModalBottomSheet
        // （skipPartiallyExpanded 完全展开 + dragHandle=null + 顶边止于状态栏下缘，对齐 iOS Settings pageSheet 模态），
        // 层级在 MiniPlayer/PopupPlayer 之上。由用户按钮菜单打开（showSettings），内含现有 settingsNavController 全部既有路由。
        if (showSettings) {
            val settingsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ModalBottomSheet(
                onDismissRequest = { showSettings = false },
                sheetState = settingsSheetState,
                dragHandle = null,
                // 关闭系统返回键的默认 dismiss，交由内部 BackHandler 逐级回退（仅根页才关闭覆盖层）
                properties = ModalBottomSheetProperties(shouldDismissOnBackPress = false),
                // 顶边止于状态栏下缘 + 顶圆角 10dp（对齐 iOS pageSheet）：
                // statusBarsPadding 作用于 sheet 的 Surface 节点（变矮的是弹层本体），
                // 并消费状态栏 inset 使内层默认 contentWindowInsets 归零，无双重留白
                modifier = Modifier.statusBarsPadding(),
                shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
                // iOS Settings 为 formSheet 承载的 insetGrouped List，页底 = systemGroupedBackground
                // @elevated（CommonScreenOperations.swift:106-107、SettingsList.swift）
                containerColor = MaterialTheme.colorScheme.sheetGroupedBackground
            ) {
                // sheet 独立窗口的系统栏图标明暗归位（否则状态栏在弹出瞬间变黑）
                SheetSystemBarsFix()
                // 系统返回键：先在 Settings 内部回退；已在根（settings_main）则关闭覆盖层
                BackHandler(enabled = true) {
                    if (settingsNavController.previousBackStackEntry != null) {
                        settingsNavController.popBackStack()
                    } else {
                        showSettings = false
                    }
                }
                // Settings 子树整体切到 elevated 分组配色（对齐 iOS formSheet 内 insetGrouped List：
                // 页底 systemGroupedBackground、分组卡 secondarySystemGroupedBackground，均取 elevated 值）。
                // 收敛做法：各设置页一律是 Scaffold（默认 containerColor = colorScheme.background）
                // + IOSNavTopBar（默认 backgroundColor = colorScheme.background）
                // + SettingsSection 的 Card(containerColor = colorScheme.surface)，
                // 故只在**此一处**改写 colorScheme 的 background / surface 两色即整体落位，
                // 无需逐页改 15 个 Scaffold（新增设置页自动继承）。
                val settingsColorScheme = MaterialTheme.colorScheme.copy(
                    background = MaterialTheme.colorScheme.sheetGroupedBackground,
                    surface = MaterialTheme.colorScheme.sheetSecondaryGroupedBackground
                )
                MaterialTheme(
                    colorScheme = settingsColorScheme,
                    typography = MaterialTheme.typography,
                    shapes = MaterialTheme.shapes
                ) {
                    // 状态栏避让由上方 sheet 的 modifier 承担，内容不再重复 statusBarsPadding
                    // iosOverscroll 挂在整个 Settings 子树的根上：nested scroll 沿树上传，
                    // 一处即覆盖各设置页各自的 verticalScroll（无需逐页接线）；
                    // 代价是过滚位移作用于整页（含导航栏），因页底与 sheet 同色故不可见
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .iosOverscroll()
                    ) {
                        SettingsNavGraph(
                            navController = settingsNavController,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        // Add Account 独立模态覆盖层——对齐 iOS：账户菜单 Add Account 一步自下而上弹出
        // LoginVC（.formSheet），无内部导航栈（单页），故返回键用默认 dismiss 即可。
        // 参数与 Settings sheet 同款（skipPartiallyExpanded 完全展开 + dragHandle=null +
        // 顶边止于状态栏下缘 + 顶圆角 10dp），层级同在 MiniPlayer/PopupPlayer 之上。
        if (showAddAccount) {
            val addAccountSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            val scope = rememberCoroutineScope()
            // 点 X 关闭：先播完 M3 sheet 的 hide 下滑动画，动画结束后再移出组合
            // （若直接置 false 会跳过动画，视觉上瞬间消失）
            val dismissAddAccount: () -> Unit = {
                scope.launch { addAccountSheetState.hide() }
                    .invokeOnCompletion { showAddAccount = false }
            }
            ModalBottomSheet(
                onDismissRequest = { showAddAccount = false },
                sheetState = addAccountSheetState,
                dragHandle = null,
                // 顶边止于状态栏下缘 + 顶圆角 10dp（对齐 iOS pageSheet），同 Settings 覆盖层
                modifier = Modifier.statusBarsPadding(),
                shape = RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp),
                // iOS Add Account 为 formSheet 承载的 LoginVC，页底 = systemBackground @elevated
                // （CommonScreenOperations.swift:95、LoginVC.swift:474）
                containerColor = MaterialTheme.colorScheme.sheetBackground
            ) {
                // sheet 独立窗口的系统栏图标明暗归位（否则状态栏在弹出瞬间变黑）
                SheetSystemBarsFix()
                Box(modifier = Modifier.fillMaxSize()) {
                    LoginScreen(
                        mode = LoginMode.ADD_ACCOUNT,
                        // 账户切换时 MainScreen 经 key(ident) 整体重建，模态随之消失；
                        // 此处即时关闭仅覆盖不切换账户的失败/取消路径
                        onLoginSuccess = { showAddAccount = false },
                        onClose = dismissAddAccount
                    )
                }
            }
        }
    }
}

/**
 * Library标签的导航图
 */
@Composable
fun LibraryNavGraph(
    navController: NavHostController,
    accountMenu: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = "library_main",
        modifier = modifier,
        // iOS push/pop 水平滑动转场（见文件顶部定义）
        enterTransition = iosPushEnter,
        exitTransition = iosPushExit,
        popEnterTransition = iosPopEnter,
        popExitTransition = iosPopExit
    ) {
        composable("library_main") {
            LibraryTabContent(navController = navController, accountMenu = accountMenu)
        }

        // player路由保留用于触发导航状态变化
        composable("player") {
            // 空组件，实际显示由浮动层处理
            Box(modifier = Modifier.fillMaxSize())
        }

        // filter 可选参数：Library 导航项「Favorite Artists」复用本页（filter=favorites）
        composable(
            route = "artists?filter={filter}",
            arguments = listOf(navArgument("filter") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            })
        ) {
            ArtistsScreen(
                onBackClick = { navController.popBackStack() },
                onArtistClick = { artist ->
                    navController.navigateToArtistDetail(artist.id)
                }
            )
        }

        // filter 可选参数：Library 导航项「Favorite/Newest/Recent Albums」复用本页
        composable(
            route = "albums?filter={filter}",
            arguments = listOf(navArgument("filter") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            })
        ) {
            AlbumsScreen(
                onBackClick = { navController.popBackStack() },
                onAlbumClick = { album ->
                    navController.navigateToAlbumDetail(album.id, "albums")
                },
                // 专辑行/网格单元长按菜单 Show Artist（照 songs 路由先例）
                onNavigateToArtist = { artistId ->
                    navController.navigateToArtistDetail(artistId)
                }
            )
        }

        composable("songs") {
            SongsScreen(
                onBackClick = { navController.popBackStack() },
                onNavigateToAlbum = { albumId ->
                    navController.navigateToAlbumDetail(albumId, "songs")
                },
                onNavigateToArtist = { artistId ->
                    navController.navigateToArtistDetail(artistId)
                }
            )
        }

        composable("playlists") {
            PlaylistsScreen(
                onBackClick = { navController.popBackStack() },
                onNavigateToDetail = { playlistId ->
                    navController.navigateToPlaylistDetail(playlistId)
                }
            )
        }

        // Favorite Songs - 对应iOS: LibraryDisplayType.favoriteSongs
        composable("favorite_songs") {
            FavoriteSongsScreen(
                onBackClick = { navController.popBackStack() },
                onNavigateToAlbum = { albumId ->
                    navController.navigateToAlbumDetail(albumId, "favorites")
                },
                onNavigateToArtist = { artistId ->
                    navController.navigateToArtistDetail(artistId)
                }
            )
        }

        // Downloads - 对应iOS: LibraryDisplayType.downloads（Phase 5.2）
        composable("downloads") {
            DownloadsScreen(
                onBackClick = { navController.popBackStack() },
                onNavigateToAlbum = { albumId ->
                    navController.navigateToAlbumDetail(albumId, "downloads")
                },
                onNavigateToArtist = { artistId ->
                    navController.navigateToArtistDetail(artistId)
                }
            )
        }

        // Genres - 对应iOS: LibraryDisplayType.genres（Phase 6.1）
        composable("genres") {
            GenresScreen(
                onBackClick = { navController.popBackStack() },
                onGenreClick = { genre -> navController.navigateToGenreDetail(genre.name) }
            )
        }

        // Radios - 对应iOS: LibraryDisplayType.radios（Phase 6.3）
        composable("radios") {
            RadiosScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        // Podcasts - 对应iOS: LibraryDisplayType.podcasts（Phase 6.4）
        composable("podcasts") {
            PodcastsScreen(
                onBackClick = { navController.popBackStack() },
                onNavigateToPodcastDetail = { podcastId ->
                    navController.navigateToPodcastDetail(podcastId)
                }
            )
        }

        // Directories - 对应iOS: LibraryDisplayType.directories（Phase 6.5，三层导航）
        composable("directories") {
            MusicFoldersScreen(
                onBackClick = { navController.popBackStack() },
                onNavigateToFolder = { folderId ->
                    navController.navigate("musicfolder/${android.net.Uri.encode(folderId)}") {
                        launchSingleTop = true
                    }
                }
            )
        }

        // 详情路由复用集（artist/album/playlist/genre/podcast/directory 等），
        // 由 Library 图与 Home 图共同注册（抽取见 libraryDetailRoutes，避免复制粘贴）
        libraryDetailRoutes(navController)
    }
}

/**
 * 详情路由复用集（W6）——从 Library NavGraph 抽出的共享详情 composable 声明。
 *
 * Library 图与 Home 图共同调用（`libraryDetailRoutes(navController)`），
 * 保证两图详情跳转行为一致、避免复制粘贴。路由字符串/参数/回调与抽取前完全一致。
 *
 * 注：Search 图有自己的一份详情路由（album 的 from 默认值不同：search vs albums），
 * 故不接入本函数（对齐「Search NavGraph 不变」）。
 */
private fun NavGraphBuilder.libraryDetailRoutes(navController: NavHostController) {
    composable(
        route = "artist/{artistId}",
        arguments = listOf(navArgument("artistId") { type = NavType.StringType })
    ) {
        ArtistDetailScreen(
            onBackClick = { navController.popBackStack() },
            onAlbumClick = { album ->
                navController.navigateToAlbumDetail(album.id, "artist")
            },
            onNavigateToAlbum = { albumId ->
                navController.navigateToAlbumDetail(albumId, "artist")
            }
        )
    }

    composable(
        route = "album/{albumId}?from={from}",
        arguments = listOf(
            navArgument("albumId") { type = NavType.StringType },
            navArgument("from") {
                type = NavType.StringType
                defaultValue = "albums"
                nullable = true
            }
        )
    ) {
        AlbumDetailScreen(
            onBackClick = { navController.popBackStack() },
            onNavigateToArtist = { artistId ->
                navController.navigateToArtistDetail(artistId)
            }
        )
    }

    composable(
        route = "playlist/{playlistId}",
        arguments = listOf(navArgument("playlistId") { type = NavType.StringType })
    ) {
        PlaylistDetailScreen(
            onBackClick = { navController.popBackStack() },
            onNavigateToAlbum = { albumId ->
                navController.navigateToAlbumDetail(albumId, "playlist")
            },
            onNavigateToArtist = { artistId ->
                navController.navigateToArtistDetail(artistId)
            }
        )
    }

    composable(
        route = "genre/{name}",
        arguments = listOf(navArgument("name") { type = NavType.StringType })
    ) {
        GenreDetailScreen(
            onBackClick = { navController.popBackStack() },
            onArtistClick = { artist -> navController.navigateToArtistDetail(artist.id) },
            onAlbumClick = { album -> navController.navigateToAlbumDetail(album.id, "genre") },
            // 歌曲行 More 菜单 Show Album / Show Artist
            onNavigateToAlbum = { albumId -> navController.navigateToAlbumDetail(albumId, "genre") },
            onNavigateToArtist = { artistId -> navController.navigateToArtistDetail(artistId) }
        )
    }

    composable(
        route = "podcast/{podcastId}",
        arguments = listOf(navArgument("podcastId") { type = NavType.StringType })
    ) {
        PodcastDetailScreen(
            onBackClick = { navController.popBackStack() }
        )
    }

    composable(
        route = "musicfolder/{folderId}",
        arguments = listOf(navArgument("folderId") { type = NavType.StringType })
    ) {
        IndexesScreen(
            onBackClick = { navController.popBackStack() },
            onNavigateToDirectory = { directoryId, parentName ->
                navController.navigateToDirectoryDetail(directoryId, parentName)
            }
        )
    }

    composable(
        route = "directory/{directoryId}?parentName={parentName}",
        arguments = listOf(
            navArgument("directoryId") { type = NavType.StringType },
            navArgument("parentName") {
                type = NavType.StringType
                defaultValue = "Directories"
            }
        )
    ) { backStackEntry ->
        val parentName = backStackEntry.arguments?.getString("parentName") ?: "Directories"
        DirectoryDetailScreen(
            backTitle = parentName,
            onBackClick = { navController.popBackStack() },
            // 子目录递归下钻（iOS DirectoriesVC.instantiateFromAppStoryboard 递归 push）
            onNavigateToDirectory = { directoryId, subParentName ->
                navController.navigateToDirectoryDetail(directoryId, subParentName)
            },
            // 歌曲行 More 菜单 Show Album / Show Artist（目录无专属 from 取值，
            // 沿用 "songs"——AlbumDetail 据此回退返回按钮文字 "Albums"）
            onNavigateToAlbum = { albumId -> navController.navigateToAlbumDetail(albumId, "songs") },
            onNavigateToArtist = { artistId -> navController.navigateToArtistDetail(artistId) }
        )
    }
}

/**
 * Home标签的导航图（W6，iOS: HomeVC + TabBarVC.homeTab）
 *
 * - home_main：HomeScreen，卡片跳转全量接线（album/artist/playlist/podcast/genre 详情；
 *   Song/Radio/Episode 单击播放已在 HomeViewModel 内处理）；Edit 在 HomeScreen 内弹
 *   HomePreferencesSheet 模态（不再是独立路由）
 * - player：占位路由（模式同其余两图）
 * - 详情路由复用集：libraryDetailRoutes（与 Library 图共享）
 */
@Composable
fun HomeNavGraph(
    navController: NavHostController,
    accountMenu: @Composable () -> Unit = {},
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = "home_main",
        modifier = modifier,
        // iOS push/pop 水平滑动转场（见文件顶部定义）
        enterTransition = iosPushEnter,
        exitTransition = iosPushExit,
        popEnterTransition = iosPopEnter,
        popExitTransition = iosPopExit
    ) {
        composable("home_main") {
            HomeScreen(
                onNavigateToArtist = { artistId -> navController.navigateToArtistDetail(artistId) },
                onNavigateToAlbum = { albumId -> navController.navigateToAlbumDetail(albumId, "home") },
                onNavigateToPlaylist = { playlistId -> navController.navigateToPlaylistDetail(playlistId) },
                onNavigateToPodcast = { podcastId -> navController.navigateToPodcastDetail(podcastId) },
                onNavigateToGenre = { genreName -> navController.navigateToGenreDetail(genreName) },
                accountMenu = accountMenu
            )
        }

        // player路由保留用于触发导航状态变化
        composable("player") {
            // 空组件，实际显示由浮动层处理
            Box(modifier = Modifier.fillMaxSize())
        }

        // 详情路由复用集（与 Library 图共享）
        libraryDetailRoutes(navController)
    }
}

/**
 * Search标签的导航图
 */
@Composable
fun SearchNavGraph(
    navController: NavHostController,
    // 共享 SearchViewModel（activity 作用域）——与 MainScreen 底部搜索输入条同用一实例，
    // 保证底部输入驱动本图 search_main 结果页；焦点激活已由 MainScreen 底部条接管，此处不再收 tick。
    // 注（对齐 iOS 搜索激活隐藏导航栏）：SearchScreen 顶栏已移除，故不再下传 accountMenu 插槽。
    searchViewModel: SearchViewModel,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = "search_main",
        modifier = modifier,
        // iOS push/pop 水平滑动转场（见文件顶部定义）
        enterTransition = iosPushEnter,
        exitTransition = iosPushExit,
        popEnterTransition = iosPopEnter,
        popExitTransition = iosPopExit
    ) {
        composable("search_main") {
            com.amperfy.ui.screens.SearchScreen(
                onNavigateToArtist = { artistId -> navController.navigateToArtistDetail(artistId) },
                onNavigateToAlbum = { albumId -> navController.navigateToAlbumDetail(albumId, "search") },
                onNavigateToPlaylist = { playlistId -> navController.navigateToPlaylistDetail(playlistId) },
                // 显式传入共享实例，与底部搜索输入条同一 ViewModel
                viewModel = searchViewModel
            )
        }

        // player路由保留用于触发导航状态变化
        composable("player") {
            // 空组件，实际显示由浮动层处理
            Box(modifier = Modifier.fillMaxSize())
        }

        // 搜索结果详情页路由（与 Library 标签内的详情页保持一致）
        composable(
            route = "artist/{artistId}",
            arguments = listOf(navArgument("artistId") { type = NavType.StringType })
        ) {
            ArtistDetailScreen(
                onBackClick = { navController.popBackStack() },
                onAlbumClick = { album -> navController.navigateToAlbumDetail(album.id, "artist") },
                onNavigateToAlbum = { albumId -> navController.navigateToAlbumDetail(albumId, "artist") }
            )
        }

        composable(
            route = "album/{albumId}?from={from}",
            arguments = listOf(
                navArgument("albumId") { type = NavType.StringType },
                navArgument("from") {
                    type = NavType.StringType
                    defaultValue = "search"
                    nullable = true
                }
            )
        ) {
            AlbumDetailScreen(
                onBackClick = { navController.popBackStack() },
                onNavigateToArtist = { artistId -> navController.navigateToArtistDetail(artistId) }
            )
        }

        composable(
            route = "playlist/{playlistId}",
            arguments = listOf(navArgument("playlistId") { type = NavType.StringType })
        ) {
            PlaylistDetailScreen(
                onBackClick = { navController.popBackStack() },
                onNavigateToAlbum = { albumId -> navController.navigateToAlbumDetail(albumId, "playlist") },
                onNavigateToArtist = { artistId -> navController.navigateToArtistDetail(artistId) }
            )
        }
    }
}

/**
 * Settings标签的导航图
 */
@Composable
fun SettingsNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = "settings_main",
        modifier = modifier,
        // iOS push/pop 水平滑动转场（见文件顶部定义）
        enterTransition = iosPushEnter,
        exitTransition = iosPushExit,
        popEnterTransition = iosPopEnter,
        popExitTransition = iosPopExit
    ) {
        composable("settings_main") {
            SettingsTabContent(navController = navController)
        }

        // player路由保留用于触发导航状态变化
        composable("player") {
            // 空组件，实际显示由浮动层处理
            Box(modifier = Modifier.fillMaxSize())
        }

        // Settings子页面导航
        composable("settings/display") {
            com.amperfy.ui.screens.settings.DisplaySettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        // 账户设置页（入口行 "Account"，对齐 iOS 2.1.0；路由字符串沿用 settings/server）
        // 注：Add Account 走 MainScreen 的独立模态（showAddAccount），本图不再注册页内路由
        composable("settings/server") {
            com.amperfy.ui.screens.settings.AccountSettingsScreen(
                navController = navController
            )
        }

        composable("settings/server/manage-urls") {
            com.amperfy.ui.screens.settings.ManageServerUrlsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable("settings/library") {
            com.amperfy.ui.screens.settings.LibrarySettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable("settings/player") {
            com.amperfy.ui.screens.settings.PlayerSettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable("settings/equalizer") {
            com.amperfy.ui.screens.settings.EqualizerSettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable("settings/swipe") {
            com.amperfy.ui.screens.settings.SwipeSettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable("settings/artwork") {
            com.amperfy.ui.screens.settings.ArtworkSettingsScreen(
                onBackClick = { navController.popBackStack() },
                onNavigateToArtworkDownload = { navController.navigate("settings/artwork/download") },
                onNavigateToArtworkDisplay = { navController.navigate("settings/artwork/display") }
            )
        }

        composable("settings/artwork/download") {
            com.amperfy.ui.screens.settings.ArtworkDownloadSettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable("settings/artwork/display") {
            com.amperfy.ui.screens.settings.ArtworkDisplaySettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable("settings/support") {
            com.amperfy.ui.screens.settings.SupportSettingsScreen(
                onBackClick = { navController.popBackStack() },
                onNavigateToEventLog = { navController.navigate("settings/eventlog") }
            )
        }

        composable("settings/eventlog") {
            com.amperfy.ui.screens.settings.EventLogScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable("settings/license") {
            com.amperfy.ui.screens.settings.LicenseSettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }

        composable("settings/xcallback") {
            com.amperfy.ui.screens.settings.XCallbackURLSettingsScreen(
                onBackClick = { navController.popBackStack() }
            )
        }
    }
}

/**
 * 底部标签枚举
 * 对应iOS的三个主标签（W6：Settings 出、Home 进；Home 默认选中，对齐 iOS homeTab 居首）
 */
enum class BottomTab(
    val title: String,
    val icon: ImageVector
) {
    HOME("Home", AmperfyIcons.home),
    LIBRARY("Library", AmperfyIcons.musicLibrary),
    SEARCH("Search", AmperfyIcons.search)
}

/**
 * Library标签页内容
 * 显示库的主界面,包含各种分类入口
 */
@Composable
fun LibraryTabContent(
    navController: NavHostController,
    accountMenu: @Composable () -> Unit = {}
) {
    LibraryMainScreen(
        onNavigateToArtists = { navController.navigate("artists") },
        onNavigateToAlbums = { navController.navigate("albums") },
        onNavigateToSongs = { navController.navigate("songs") },
        onNavigateToPlaylists = { navController.navigate("playlists") },
        onNavigateToFavoriteSongs = { navController.navigate("favorite_songs") },
        onNavigateToFavoriteAlbums = { navController.navigate("albums?filter=favorites") },
        onNavigateToFavoriteArtists = { navController.navigate("artists?filter=favorites") },
        onNavigateToNewestAlbums = { navController.navigate("albums?filter=newest") },
        onNavigateToRecentAlbums = { navController.navigate("albums?filter=recent") },
        onNavigateToDownloads = { navController.navigate("downloads") },
        onNavigateToGenres = { navController.navigate("genres") },
        onNavigateToDirectories = { navController.navigate("directories") },
        onNavigateToPodcasts = { navController.navigate("podcasts") },
        onNavigateToRadios = { navController.navigate("radios") },
        accountMenu = accountMenu
    )
}

/**
 * Settings标签页内容
 * 完整的Settings UI,对应iOS的SettingsView
 */
@Composable
fun SettingsTabContent(navController: NavHostController) {
    // 使用完整的SettingsScreen替换占位符
    com.amperfy.ui.screens.settings.SettingsScreen(
        onNavigateToDisplayAndInteraction = { navController.navigate("settings/display") },
        onNavigateToAccount = { navController.navigate("settings/server") },
        onNavigateToLibrary = { navController.navigate("settings/library") },
        onNavigateToPlayer = { navController.navigate("settings/player") },
        onNavigateToEqualizer = { navController.navigate("settings/equalizer") },
        onNavigateToSwipe = { navController.navigate("settings/swipe") },
        onNavigateToArtwork = { navController.navigate("settings/artwork") },
        onNavigateToSupport = { navController.navigate("settings/support") },
        onNavigateToLicense = { navController.navigate("settings/license") },
        onNavigateToXCallback = { navController.navigate("settings/xcallback") }
    )
}
