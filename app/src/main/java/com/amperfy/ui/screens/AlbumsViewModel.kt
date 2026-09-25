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

package com.amperfy.ui.screens

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amperfy.core.AppDelegate
import com.amperfy.data.model.Album
import com.amperfy.data.model.PlayContextType
import com.amperfy.data.model.Song
import com.amperfy.data.model.SwipeActionType
import com.amperfy.data.model.handleSwipeAction
import com.amperfy.data.model.toPlayableWithCredentials
import com.amperfy.ui.util.SortSectionMode
import com.amperfy.ui.util.SortSectionUtils
import com.amperfy.ui.util.SwipeActionCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.Locale
import javax.inject.Inject

/**
 * 显示类别过滤器 - 对应iOS的DisplayCategoryFilter
 */
enum class DisplayCategoryFilter(val title: String) {
    ALL("Albums"),
    NEWEST("Newest Albums"),
    RECENT("Recently Played Albums"),
    FAVORITES("Favorite Albums")
}

/**
 * 专辑排序类型 - 对应iOS的AlbumElementSortType
 */
enum class AlbumSortType {
    NAME,       // 按名称排序
    RATING,     // 按评分排序
    ARTIST,     // 按艺术家排序
    DURATION,   // 按时长排序
    YEAR,       // 按年份排序
    NEWEST,     // 最新添加
    RECENT;      // 最近播放

    fun getDisplayName(): String {
        return when (this) {
            NAME -> "Name"
            RATING -> "Rating"
            ARTIST -> "Artist"
            DURATION -> "Duration"
            YEAR -> "Year"
            NEWEST -> "Recently Added"
            RECENT -> "Recently Played"
        }
    }

    /**
     * 该排序档的分段模式 —— 对应 iOS `AlbumElementSortType.asSectionIndexType`
     * （FetchedResultsControllers.swift:62-78）。
     *
     * NAME 与 ARTIST 同为字母分段，但**取字母的字段不同**（见 [albumSectionTitle]）。
     */
    val sectionMode: SortSectionMode
        get() = when (this) {
            NAME, ARTIST -> SortSectionMode.ALPHABET
            RATING -> SortSectionMode.RATING
            YEAR -> SortSectionMode.YEAR
            DURATION -> SortSectionMode.DURATION_ALBUM
            NEWEST, RECENT -> SortSectionMode.NONE
        }

    /**
     * 是否隐藏右侧索引条 —— iOS 对 name/artist/rating/duration/year 五档都给索引
     * （AlbumsVC.swift:76-106 的 sectionIndexTitles、AlbumsCollectionVC.swift:95-107 的
     * indexTitles），仅 newest/recent 返回 nil。
     *
     * 2026-08-10：上一批曾按「索引条画不下多字符标签」隐藏 YEAR/DURATION，
     * 已被 iOS 实机观测推翻（该两档真机有索引栏）——现索引条条宽自适应最长标签，
     * 五档一律显示。
     */
    val isIndexTitlesHidden: Boolean
        get() = sectionMode == SortSectionMode.NONE

    /**
     * 行间分割线是否按段边界区分形态（**段间全宽 / 段内 16dp inset**）。
     *
     * iOS 实机观测（2026-08-10）：artist / rating / year / duration 四档的段间线为全宽
     * （= iOS `.grouped` 表的 section 边界线，非 cell separator）；**NAME 档例外**——
     * 字母组之间仍是 16dp inset（与 Genres 页「字母组间全宽」不同，属两页 FRC 分段结构差异，
     * 照判据办不深究）；newest/recent 不分段，全部 16dp。
     */
    val hasFullWidthSectionDividers: Boolean
        get() = when (this) {
            ARTIST, RATING, YEAR, DURATION -> true
            NAME, NEWEST, RECENT -> false
        }

    companion object {
        /** 解析持久化值（枚举名），无效或缺失回退 [NAME]（对齐 iOS defaultValue = .name） */
        fun fromPersisted(value: String?): AlbumSortType =
            entries.find { it.name == value } ?: NAME
    }
}

/**
 * 专辑的**段头标题** —— 对应 iOS `AlbumsVC.tableView(_:titleForHeaderInSection:)`
 * （AlbumsVC.swift:47-74）与 `AlbumsCollectionVC.sectionTitle(for:)`（:133-160）：
 * - name：专辑名首字母（iOS 段名取 `album.name`，展示时经 `sectionTitleToIndexTitle` 取首字母）
 * - artist：**艺术家名**首字母（iOS `case .artist: return album.subtitle`，:149-151；
 *   subtitle = 艺术家名，Album.swift:182）——与 name 档取的字段不同，故分两支实现
 * - rating："N Star(s)" / "Not rated"
 * - year：年份 / "#"
 * - **duration：空串**——iOS LIST 该档 `heightForHeaderInSection` 为 0
 *   （AlbumsVC.swift:285-296）、GRID 为 `display(title: nil)`（AlbumsCollectionVC.swift:85-86），
 *   即分段只服务索引跳转与段边界线，段头不画
 * - newest/recent：不分段（空串）
 */
fun albumSectionTitle(album: Album, sortType: AlbumSortType): String = when (sortType) {
    AlbumSortType.NAME -> SortSectionUtils.alphabetSectionTitle(album.name)
    AlbumSortType.ARTIST -> SortSectionUtils.alphabetSectionTitle(album.artist)
    AlbumSortType.RATING -> SortSectionUtils.ratingSectionTitle(album.rating)
    AlbumSortType.YEAR -> SortSectionUtils.yearSectionTitle(album.year)
    AlbumSortType.DURATION, AlbumSortType.NEWEST, AlbumSortType.RECENT -> ""
}

/**
 * 专辑的**索引条标签** —— 对应 iOS `sectionTitleToIndexTitle`
 * （AlbumsCollectionVC.swift:109-131、AlbumsVC.swift:81-102）：
 * 字母档与段头同值，rating 档取星级数字（未评分 "#"），year 档取年份（无年份 "#"），
 * duration 档取时长桶（`IndexHeaderNameGenerator.sortByDurationAlbum`）。
 */
fun albumIndexLabel(album: Album, sortType: AlbumSortType): String = when (sortType) {
    AlbumSortType.NAME, AlbumSortType.ARTIST -> albumSectionTitle(album, sortType)
    AlbumSortType.RATING -> SortSectionUtils.ratingIndexLabel(album.rating)
    AlbumSortType.YEAR -> SortSectionUtils.yearSectionTitle(album.year)
    AlbumSortType.DURATION -> SortSectionUtils.durationAlbumIndexLabel(album.duration)
    AlbumSortType.NEWEST, AlbumSortType.RECENT -> ""
}

/**
 * **GRID 分组键**（网格按段切行块 + 索引跳转映射用）。
 *
 * 除 duration 外与段头标题同值；duration 取**时长桶**标签——iOS 的 collection section 是按
 * `duration` 精确值分的（每个不同时长自成一段，段头 30pt 但 `title: nil`），
 * Android 不复刻空段头，按桶切块可避免网格被切成近乎每张专辑一块，属刻意简化。
 */
fun albumGridSectionKey(album: Album, sortType: AlbumSortType): String = when (sortType) {
    AlbumSortType.DURATION -> SortSectionUtils.durationAlbumIndexLabel(album.duration)
    else -> albumSectionTitle(album, sortType)
}

/**
 * **LIST 行间线的段边界键**（相邻两行键不同 = 跨段 → 该行下方的线画全宽）。
 *
 * duration 档取**精确时长秒数**而非桶：iOS FRC 该档 `sectionNameKeyPath` 就是 duration 本身
 * （`AlbumsVC.tableView(_:titleForHeaderInSection:)` 的 `.duration` 分支返回
 * `album.duration.description`，AlbumsVC.swift:66-68），故几乎每行都跨段、线几乎全是全宽——
 * 与实机所见「duration 排序下行间线全部全宽」一致。
 * 其余档段边界 = 段头标题（字母 / "N Stars" / 年份）。
 */
fun albumRowSectionKey(album: Album, sortType: AlbumSortType): String = when (sortType) {
    AlbumSortType.DURATION -> album.duration.toString()
    else -> albumSectionTitle(album, sortType)
}

/**
 * 专辑显示样式 - 对应iOS的AlbumsDisplayStyle
 * （FetchedResultsControllers.swift:170-175）
 */
enum class AlbumStyleType {
    TABLE,  // 列表视图
    GRID;   // 网格视图

    companion object {
        /**
         * 解析持久化值（枚举名），无效或缺失回退 [GRID]
         * ——对齐 iOS AlbumsDisplayStyle.defaultValue = .grid
         */
        fun fromPersisted(value: String?): AlbumStyleType =
            entries.find { it.name == value } ?: GRID
    }
}

/**
 * Albums UI状态
 */
data class AlbumsUiState(
    val displayFilter: DisplayCategoryFilter = DisplayCategoryFilter.ALL,
    val sortType: AlbumSortType = AlbumSortType.NAME,
    // 默认网格视图——对齐 iOS AlbumsDisplayStyle.defaultValue = .grid
    val styleType: AlbumStyleType = AlbumStyleType.GRID,
    val gridSize: Int = 3,  // 网格列数, iOS 手机默认 3（Settings.swift:293-299）
    val searchText: String = "",
    val isSearchActive: Boolean = false
) {
    /**
     * 索引条显隐**只由排序档决定**（对应 iOS：索引来自 FRC 的 sectionIndexType，
     * 而 sectionIndexType = sortType.asSectionIndexType，与 displayFilter 无关）。
     * Newest/Recent 两个入口在 [AlbumsViewModel.applyFilter] 内被强制为对应排序档，
     * 天然落到 [AlbumSortType.isIndexTitlesHidden] = true，语义与原按 displayFilter 判定等价。
     */
    val isIndexTitlesHidden: Boolean
        get() = sortType.isIndexTitlesHidden

    // 对应iOS的sceneTitle
    val sceneTitle: String
        get() = when (displayFilter) {
            DisplayCategoryFilter.ALL -> "Albums"
            DisplayCategoryFilter.NEWEST -> "Newest Albums"
            DisplayCategoryFilter.RECENT -> "Recently Played Albums"
            DisplayCategoryFilter.FAVORITES -> "Favorite Albums"
        }
    
    // 对应iOS的filterTitle
    val filterTitle: String
        get() = displayFilter.title
}

/**
 * Albums ViewModel - 对应iOS的AlbumsCommonVCInteractions
 */
@HiltViewModel
class AlbumsViewModel @Inject constructor(
    private val appDelegate: AppDelegate,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    companion object {
        private const val TAG = "AlbumsViewModel"

        // 使用中文Collator进行拼音排序
        private val chineseCollator: Collator = Collator.getInstance(Locale.CHINESE).apply {
            strength = Collator.PRIMARY  // 忽略大小写和重音
        }

        // Newest/Recent 单次抓取条数，对应 iOS: newestElementsFetchCount = 50（AmperfyKit.swift:37）
        private const val FETCH_COUNT = 50

        // 头部 Play/Shuffle 取的专辑数，对应 iOS prefix(5)/randomPick: 5
        // （AlbumsCommonVCInteractions.swift:508/523）
        private const val HEADER_PLAY_ALBUM_COUNT = 5

        // 单次加入播放器的歌曲上限，对应 iOS maxSongsToAddOnce（PlayerFacade.swift:185）
        private const val MAX_SONGS_TO_ADD_ONCE = 500

        /** 将导航参数 filter 解析为显示过滤器（Library 导航项复用本页时传入） */
        fun parseFilter(value: String?): DisplayCategoryFilter = when (value) {
            "favorites" -> DisplayCategoryFilter.FAVORITES
            "newest" -> DisplayCategoryFilter.NEWEST
            "recent" -> DisplayCategoryFilter.RECENT
            else -> DisplayCategoryFilter.ALL
        }
    }

    // 初始显示过滤器 - 来自导航参数（默认 ALL）
    private val initialFilter: DisplayCategoryFilter = parseFilter(savedStateHandle["filter"])

    // UI状态：显示样式与网格列数读设备级持久化
    // （对应 iOS settings.user.albumsStyleSetting / albumsGridSizeSetting）；
    // 排序方式在 applyFilter() 内按过滤器决定（Newest/Recent 强制，其余读持久化）
    private val _uiState = MutableStateFlow(
        AlbumsUiState(
            displayFilter = initialFilter,
            styleType = AlbumStyleType.fromPersisted(appDelegate.settings.albumsStyleSetting.value),
            gridSize = appDelegate.settings.albumsGridSizeSetting.value
        )
    )
    val uiState: StateFlow<AlbumsUiState> = _uiState.asStateFlow()
    
    // 所有专辑数据
    private val allAlbums: StateFlow<List<Album>> = appDelegate.library.getAllAlbums()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    // 过滤和排序后的专辑列表
    val filteredAlbums: StateFlow<List<Album>> = combine(
        allAlbums,
        _uiState
    ) { albums, state ->
        Log.d(TAG, "Filtering albums: total=${albums.size}, searchText='${state.searchText}'")
        filterAndSortAlbums(albums, state)
    }
        // 过滤/拼音排序移出主线程（与 SongsViewModel 同模式）；
        // 否则订阅瞬间（页面切换转场前几帧）在主线程执行，导致转场掉帧
        .flowOn(Dispatchers.Default)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    // 远程同步加载状态（用于特定过滤器的远程同步）
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // 数据加载到UI的状态（用于显示骨架屏）
    private val _isLoadingData = MutableStateFlow(true)
    val isLoadingData: StateFlow<Boolean> = _isLoadingData.asStateFlow()

    // 刷新状态
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // 已同步的最新元素偏移量集合 - 对应iOS的newestElementsOffsetsSynced
    private val newestElementsOffsetsSynced = mutableSetOf<Int>()

    init {
        Log.d(TAG, "AlbumsViewModel initialized")
        applyFilter()

        // 监听 allAlbums 的变化来控制骨架屏
        // 使用 drop(1) 跳过 StateFlow 的初始值，只监听真正的更新
        viewModelScope.launch {
            allAlbums.drop(1).collect { albums ->
                Log.d(TAG, "allAlbums updated (skipped initial): ${albums.size} albums, isLoadingData=${_isLoadingData.value}")

                // 数据已从数据库加载，关闭骨架屏
                if (_isLoadingData.value) {
                    Log.d(TAG, "Data loaded from database, hiding skeleton")
                    _isLoadingData.value = false
                }
            }
        }

        // iOS: viewIsAppearing() -> updateFromRemote()
        // 只在特定过滤器下才同步数据
        updateFromRemote()
    }

    /**
     * 从服务器更新数据（如果需要）
     * iOS: updateFromRemote()
     *
     * 行为说明：
     * - All: 不同步，直接从数据库读取（iOS: break）
     * - Newest: 同步最新专辑
     * - Recent: 同步最近播放的专辑
     * - Favorites: 同步收藏的专辑
     */
    private fun updateFromRemote(offset: Int = 0, count: Int = FETCH_COUNT) {
        viewModelScope.launch {
            try {
                // 离线模式不发起远程同步（对应 iOS: guard isOnlineMode else return）
                if (appDelegate.settings.isOfflineMode.value) {
                    Log.d(TAG, "Offline mode, skipping remote sync")
                    return@launch
                }
                when (_uiState.value.displayFilter) {
                    DisplayCategoryFilter.ALL -> {
                        // iOS: break (不同步，从数据库读取)
                        Log.d(TAG, "Filter is All, using cached data")
                    }
                    DisplayCategoryFilter.NEWEST -> {
                        Log.d(TAG, "Filter is Newest, syncing from server")
                        _isLoading.value = true
                        try {
                            // iOS: requestNewestAlbums (getAlbumList2 type=newest)
                            appDelegate.library.syncNewestAlbums(count = count, offset = offset)
                        } finally {
                            _isLoading.value = false
                        }
                    }
                    DisplayCategoryFilter.RECENT -> {
                        Log.d(TAG, "Filter is Recent, syncing from server")
                        _isLoading.value = true
                        try {
                            // iOS: requestRecentAlbums (getAlbumList2 type=recent)
                            appDelegate.library.syncRecentAlbums(count = count, offset = offset)
                        } finally {
                            _isLoading.value = false
                        }
                    }
                    DisplayCategoryFilter.FAVORITES -> {
                        Log.d(TAG, "Filter is Favorites, syncing from server")
                        _isLoading.value = true
                        try {
                            // iOS: syncFavoriteLibraryElements (getStarred2)
                            appDelegate.library.syncFavoriteElements()
                        } finally {
                            _isLoading.value = false
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating from remote: ${e.message}", e)
            }
        }
    }
    
    /**
     * 应用过滤器 - 对应iOS的applyFilter()
     *
     * 排序方式：Newest/Recent 两个导航入口按服务器返回顺序强制排序（不读持久化）；
     * All/Favorites 读设备级持久化（对应 iOS settings.user.albumsSortSetting）
     */
    fun applyFilter() {
        _uiState.update { currentState ->
            val newSortType = when (currentState.displayFilter) {
                DisplayCategoryFilter.ALL, DisplayCategoryFilter.FAVORITES ->
                    AlbumSortType.fromPersisted(appDelegate.settings.albumsSortSetting.value)
                DisplayCategoryFilter.NEWEST -> AlbumSortType.NEWEST
                DisplayCategoryFilter.RECENT -> AlbumSortType.RECENT
            }

            // 索引条显隐随 sortType 派生（AlbumsUiState.isIndexTitlesHidden），此处不再单独置位
            currentState.copy(sortType = newSortType)
        }
    }
    
    /**
     * 改变排序类型 - 对应iOS的change(sortType:)（并持久化 albumsSortSetting）
     */
    fun changeSortType(sortType: AlbumSortType) {
        Log.d(TAG, "Changing sort type to: $sortType")
        _uiState.update { it.copy(sortType = sortType) }
        appDelegate.settings.setAlbumsSortSetting(sortType.name)

        // 清除已同步的偏移量
        newestElementsOffsetsSynced.clear()
    }

    /**
     * 改变显示样式 - 对应iOS的albumsStyleSetting（并持久化）
     */
    fun changeStyleType(styleType: AlbumStyleType) {
        Log.d(TAG, "Changing style type to: $styleType")
        _uiState.update { it.copy(styleType = styleType) }
        appDelegate.settings.setAlbumsStyleSetting(styleType.name)
    }

    /**
     * 改变网格大小 - 对应iOS的albumsGridSizeSetting（并持久化）
     */
    fun changeGridSize(size: Int) {
        val newSize = size.coerceIn(2, 5)
        Log.d(TAG, "Changing grid size to: $newSize")
        _uiState.update { it.copy(gridSize = newSize) }
        appDelegate.settings.setAlbumsGridSizeSetting(newSize)
    }
    
    /**
     * 更新搜索文本 - 对应iOS的updateSearchResults
     */
    fun updateSearchText(text: String) {
        Log.d(TAG, "Updating search text: '$text'")
        _uiState.update { 
            it.copy(
                searchText = text,
                isSearchActive = text.isNotEmpty()
            ) 
        }
        
        // 如果搜索文本不为空且作用域为"All"，触发远程搜索
        if (text.isNotEmpty()) {
            viewModelScope.launch {
                try {
                    val credentials = appDelegate.credentials.getCredentials() ?: return@launch
                    // 调用远程搜索API
                    // appDelegate.library.searchAlbums(text)
                    Log.d(TAG, "Remote search not yet implemented")
                } catch (e: Exception) {
                    Log.e(TAG, "Error during search: ${e.message}", e)
                }
            }
        }
    }
    
    /**
     * 列表项可见回调 - 对应iOS的listViewWillDisplayCell
     */
    fun listViewWillDisplayCell(index: Int, searchText: String?) {
        val state = _uiState.value
        val albums = filteredAlbums.value
        
        // 仅在newest或recent模式下，且搜索为空时进行增量加载
        if ((state.sortType == AlbumSortType.NEWEST || state.sortType == AlbumSortType.RECENT) &&
            searchText.isNullOrEmpty() &&
            index > 0 &&
            (index == albums.size - 1 || index % FETCH_COUNT == 0) &&
            !newestElementsOffsetsSynced.contains(index)) {

            newestElementsOffsetsSynced.add(index)
            syncIncrementalData(offset = index, count = FETCH_COUNT)
        }
    }

    /**
     * 增量同步数据（用于滚动加载）
     * iOS: listViewWillDisplayCell -> updateFromRemote
     */
    private fun syncIncrementalData(offset: Int, count: Int) {
        viewModelScope.launch {
            try {
                // 离线模式不发起远程同步（对应 iOS: guard isOnlineMode else return）
                if (appDelegate.settings.isOfflineMode.value) return@launch

                when (_uiState.value.displayFilter) {
                    DisplayCategoryFilter.NEWEST -> {
                        Log.d(TAG, "Syncing newest albums at offset $offset")
                        appDelegate.library.syncNewestAlbums(count = count, offset = offset)
                    }
                    DisplayCategoryFilter.RECENT -> {
                        Log.d(TAG, "Syncing recent albums at offset $offset")
                        appDelegate.library.syncRecentAlbums(count = count, offset = offset)
                    }
                    else -> {
                        // 其他情况不需要增量同步
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing incremental data: ${e.message}", e)
            }
        }
    }
    
    /**
     * 处理下拉刷新 - 对应iOS的handleRefresh
     *
     * 按当前过滤器刷新对应数据（iOS: recent → syncRecentAlbums、favorites → getStarred2、
     * 其余 → syncNewestLibraryElements），并清空增量分页偏移让滚动加载从头开始
     */
    fun handleRefresh() {
        Log.d(TAG, "Handling refresh")
        _isRefreshing.value = true
        // 刷新时显示骨架屏（数据将重新加载）
        _isLoadingData.value = true

        viewModelScope.launch {
            try {
                // 离线模式不发起远程同步（对应 iOS: guard isOnlineMode else return）
                if (appDelegate.settings.isOfflineMode.value) {
                    _isLoadingData.value = false
                    return@launch
                }
                newestElementsOffsetsSynced.clear()
                when (_uiState.value.displayFilter) {
                    DisplayCategoryFilter.ALL ->
                        appDelegate.library.syncAlbums()
                    DisplayCategoryFilter.NEWEST ->
                        appDelegate.library.syncNewestAlbums(count = FETCH_COUNT, offset = 0)
                    DisplayCategoryFilter.RECENT ->
                        appDelegate.library.syncRecentAlbums(count = FETCH_COUNT, offset = 0)
                    DisplayCategoryFilter.FAVORITES ->
                        appDelegate.library.syncFavoriteElements()
                }

                // 同步完成后等待一小段时间，让数据库更新传播
                kotlinx.coroutines.delay(300)

                // 如果数据没有变化（allAlbums 不会 emit），需要手动关闭骨架屏
                if (_isLoadingData.value) {
                    Log.d(TAG, "Refresh completed but no data change detected, hiding skeleton")
                    _isLoadingData.value = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during refresh: ${e.message}", e)
                _isLoadingData.value = false  // 出错时关闭骨架屏
            } finally {
                _isRefreshing.value = false
            }
        }
    }
    
    /**
     * 处理头部播放按钮 - 对应 iOS AlbumsCommonVCInteractions.handleHeaderPlay
     * （AlbumsCommonVCInteractions.swift:503-516）：
     * 取当前展示列表前 5 张专辑，展开各自本地已同步的歌曲顺序拼接，
     * 上限 maxSongsToAddOnce=500（PlayerFacade.swift:185）；
     * 歌曲未同步的专辑贡献空列表（iOS album.playables 同语义，渐进同步补齐后即有）。
     * 此前为占位实现（emptyList），点击无任何反应
     */
    fun handleHeaderPlay() {
        viewModelScope.launch {
            val albums = filteredAlbums.value.take(HEADER_PLAY_ALBUM_COUNT)
            val playables = albums.flatMap { album ->
                appDelegate.library.getAlbumSongs(album.id).first()
            }.take(MAX_SONGS_TO_ADD_ONCE).map { it.toPlayable() }
            Log.d(TAG, "Header play: ${albums.size} albums, ${playables.size} songs")
            if (playables.isEmpty()) return@launch
            appDelegate.player.playPlaylist(
                songs = playables,
                startIndex = 0,
                contextType = PlayContextType.NONE,
                contextId = null,
                contextName = _uiState.value.filterTitle
            )
        }
    }

    /**
     * 处理头部随机播放按钮 - 对应 iOS AlbumsCommonVCInteractions.handleHeaderShuffle
     * （AlbumsCommonVCInteractions.swift:518-531）：
     * 随机取 5 张专辑（iOS `displayedAlbumsMO[randomPick: 5]`）展开歌曲、
     * 截断到 `maxSongsToAddOnce`（iOS PlayerFacade.swift:236 = 500，同 [MAX_SONGS_TO_ADD_ONCE]）。
     *
     * **随机只施加在「取哪几张专辑」这一层，专辑内曲序保持原样**、起播 index 0、
     * 且**不开播放器 shuffle**——该页 `isShuffleOnContextNeccessary: false`
     * （AlbumsCommonVCInteractions.swift:560），iOS 明确要求
     * 「In AlbumsVC the albums are shuffled, keep the order when shuffle button is pressed」
     * （LibraryElementDetailTableHeaderView.swift:154），按下只走 `player.play(context:)`。
     */
    fun handleHeaderShuffle() {
        viewModelScope.launch {
            val albums = filteredAlbums.value.shuffled().take(HEADER_PLAY_ALBUM_COUNT)
            val playables = albums.flatMap { album ->
                appDelegate.library.getAlbumSongs(album.id).first()
            }.take(MAX_SONGS_TO_ADD_ONCE).map { it.toPlayable() }
            Log.d(TAG, "Header shuffle: ${albums.size} albums, ${playables.size} songs")
            if (playables.isEmpty()) return@launch
            appDelegate.player.playPlaylist(
                songs = playables,
                startIndex = 0,
                contextType = PlayContextType.NONE,
                contextId = null,
                contextName = _uiState.value.filterTitle
            )
        }
    }

    private fun Song.toPlayable() =
        toPlayableWithCredentials(appDelegate.credentials, appDelegate.mediaUrls)
    
    /**
     * Download <filterTitle>（对应 iOS AlbumsCommonVCInteractions.createActionButtonMenu，
     * AlbumsCommonVCInteractions.swift:457-495）。
     *
     * iOS 语义：按当前 displayFilter 取该过滤集的**专辑**，再取这些专辑的全部歌曲
     * （`albums.compactMap { $0.playables }.joined()`）入下载队列；
     * 歌曲数超过 [MANY_SONGS_WARNING_THRESHOLD] 时由调用方先弹确认。
     *
     * Android 实现取舍：不逐专辑查（N 次查询），而是一次性取全库歌曲后按专辑 id 集合过滤——
     * 结果等价且只有一次查询。**不含搜索框过滤**（对齐 iOS：菜单动作作用于整个过滤集，
     * 与搜索框当前内容无关）。
     */
    suspend fun collectFilteredAlbumSongs(): List<Song> {
        val albumIds = filterAndSortAlbums(
            allAlbums.value,
            // 清空 searchText：菜单动作按过滤集取数，不受搜索框影响
            _uiState.value.copy(searchText = "")
        ).map { it.id }.toSet()
        if (albumIds.isEmpty()) return emptyList()
        return appDelegate.library.getAllSongs().first()
            .filter { it.albumId in albumIds }
    }

    /** 把上面取到的歌曲入下载队列（确认框由调用方按阈值决定是否先弹） */
    fun downloadSongs(songs: List<Song>) {
        appDelegate.downloader.downloadSongs(songs)
    }

    /**
     * 过滤和排序专辑 - 核心逻辑
     */
    private fun filterAndSortAlbums(
        albums: List<Album>,
        state: AlbumsUiState
    ): List<Album> {
        var result = albums
        
        Log.d(TAG, "filterAndSortAlbums: input size=${albums.size}, filter=${state.displayFilter}, sort=${state.sortType}")
        
        // 1. 应用显示过滤器
        // Newest/Recent 只显示在服务器对应列表中的专辑（序号 > 0），顺序由排序阶段的序号决定
        // 对应 iOS：本地按 newestIndex/recentIndex 查询，只显示 index > 0 的集合
        result = when (state.displayFilter) {
            DisplayCategoryFilter.ALL -> result
            DisplayCategoryFilter.NEWEST -> result.filter { it.newestIndex > 0 }
            DisplayCategoryFilter.RECENT -> result.filter { it.recentIndex > 0 }
            DisplayCategoryFilter.FAVORITES -> {
                // 只显示收藏的
                result.filter { it.starred != null }
            }
        }
        
        // 2. 应用搜索过滤
        if (state.searchText.isNotEmpty()) {
            val searchLower = state.searchText.lowercase()
            result = result.filter { album ->
                album.name.lowercase().contains(searchLower) ||
                album.artist.lowercase().contains(searchLower)
            }
        }
        
        // 3. 应用排序
        result = when (state.sortType) {
            // NAME：store 已按 sort_key（拼音分区 + 原文，# 严格最后）排序返回（P3 批次 1c 下沉），无须客户端重排
            AlbumSortType.NAME -> result
            // RATING：星级降序（iOS AlbumMO.ratingSortedFetchRequest 的
            // `NSSortDescriptor(key: rating, ascending: false)`，AlbumMO+CoreDataClass.swift:83-98），
            // 同星级内保持入参既有的 sort_key（专辑名）序——sortedByDescending 稳定，
            // 等价于 iOS 的次级排序键 identifierKey(name) ascending。
            // 修正：原实现按 playCount 降序（"暂时用playCount替代"），与排序档语义无关，
            // 也使按评分分段的段头（"N Stars"/"Not rated"）无法成立
            AlbumSortType.RATING -> result.sortedByDescending { it.rating }
            // 先按索引字母排序（# 排在最后），同一字母内按艺术家名排序
            // 三级序：# 组严格最后 → 索引字母 → 组内 artist 原文（码点序）
            // 修正原 "ZZZ" 拼接 hack：("ZZZ" + artist) 与 ("Z" + artist) 逐字符比较时第 3 位 'Z' 小于小写字母，
            // 导致 # 组越位排到多数 Z 开头艺术家之前（对齐 P3 批次 1c 对 NAME 排序的同类修正）
            AlbumSortType.ARTIST -> result
                .map { album ->
                    com.amperfy.utils.AlphabetIndexUtils.getIndexLetter(album.artist) to album
                }
                .sortedWith(
                    compareBy({ it.first == "#" }, { it.first }, { it.second.artist })
                )
                .map { it.second }
            // DURATION：时长**升序**（iOS AlbumMO.durationSortedFetchRequest 的
            // `NSSortDescriptor(key: duration, ascending: true)`，AlbumMO+CoreDataClass.swift:169-177），
            // 同时长内保持入参 sort_key（专辑名）序 = iOS 次级键 identifierKey ascending。
            // 修正：原为 sortedByDescending（方向与 iOS 相反，且会让时长桶索引倒序）
            AlbumSortType.DURATION -> result.sortedBy { it.duration }
            AlbumSortType.YEAR -> result.sortedByDescending { it.year ?: 0 }
            // 按服务器返回顺序展示（序号 1 起；0 = 不在服务器列表中，排在最后）
            AlbumSortType.NEWEST -> result.sortedBy { if (it.newestIndex > 0) it.newestIndex else Int.MAX_VALUE }
            AlbumSortType.RECENT -> result.sortedBy { if (it.recentIndex > 0) it.recentIndex else Int.MAX_VALUE }
        }
        
        Log.d(TAG, "filterAndSortAlbums: output size=${result.size}")
        return result
    }
    
    /**
     * 是否内容不可用 - 对应iOS的isContentUnavailable
     */
    fun isContentUnavailable(): Boolean {
        return filteredAlbums.value.isEmpty()
    }
    /**
     * Get full cover art URL for an album
     * Equivalent to iOS: album.artwork.url
     */
    fun getCoverArtUrl(album: Album): String? {
        val coverArtId = album.coverArt ?: return null
        val credentials = appDelegate.credentials.getCredentials() ?: return null

        return appDelegate.mediaUrls.getCoverArtUrl(
            coverArtId = coverArtId,
            username = credentials.username,
            password = credentials.password,
            baseUrl = credentials.serverUrl
        )
    }

    /**
     * 滑动动作配置与结果协调（删除缓存确认、播放列表选择器）
     */
    val swipeCoordinator = SwipeActionCoordinator(appDelegate, viewModelScope)
    val swipeActionSettings = appDelegate.settings.swipeActionSettings
    val isOfflineMode = appDelegate.settings.isOfflineMode

    /**
     * 处理滑动动作 - 对应iOS: BasicTableViewController.createSwipeAction
     */
    /**
     * 全部歌曲已缓存的专辑 id 集合（对应 iOS isCachedCompletely）：长按菜单据此隐藏 Download
     */
    val fullyCachedAlbumIds: StateFlow<Set<String>> = appDelegate.library.getFullyCachedAlbumIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun handleSwipeAction(album: Album, action: SwipeActionType) {
        Log.d(TAG, "Handling swipe action: ${action.displayName} for album: ${album.name}")
        viewModelScope.launch {
            try {
                swipeCoordinator.onResult(album.handleSwipeAction(action, appDelegate))
            } catch (e: Exception) {
                Log.e(TAG, "Error handling swipe action: ${e.message}", e)
            }
        }
    }

    /**
     * 设置专辑评分 - 对应 iOS: EntityPreviewActionBuilder.createRatingMenu()
     * （长按上下文菜单 Rating 调色板；滑动动作无对应项，故单列一个方法）
     */
    fun setRating(album: Album, rating: Int) {
        viewModelScope.launch {
            try {
                appDelegate.library.updateAlbumRating(album.id, rating)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating album rating: ${e.message}", e)
            }
        }
    }
}
