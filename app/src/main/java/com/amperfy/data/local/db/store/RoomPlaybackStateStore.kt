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

package com.amperfy.data.local.db.store

import android.util.Log
import androidx.room.withTransaction
import com.amperfy.data.download.DownloadPathResolver
import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.local.db.AmperfyDatabase
import com.amperfy.data.local.db.dao.EpisodeWithPodcastTitle
import com.amperfy.data.local.db.dao.SongWithLocal
import com.amperfy.data.local.db.entity.PlaybackQueueItemEntity
import com.amperfy.data.local.db.entity.PlaybackStateEntity
import com.amperfy.data.local.db.entity.RadioEntity
import com.amperfy.data.local.store.PlaybackStateStore
import com.amperfy.data.model.LoginCredentials
import com.amperfy.data.model.PlayContextType
import com.amperfy.data.model.Playable
import com.amperfy.data.model.PlaybackState
import com.amperfy.data.model.PlayerMode
import com.amperfy.data.model.RepeatMode
import com.amperfy.data.model.BackendApiType
import com.amperfy.data.remote.SubsonicUrlBuilder
import com.amperfy.data.remote.ampache.AmpacheUrl
import java.io.File

/**
 * PlaybackStateStore 的 Room 全量实现（专题 15 P4 批次 2）。
 *
 * 播放状态三方法（get/save/clear）走 playback_state 单行 + playback_queue_item 有序表，
 * 接口 6 方法全部 override；队列不做 JSON 序列化，无 Gson/TypeToken 与其 R8 泛型签名风险。
 *
 * **保存**（[savePlaybackState]）：五队列（playlist/userQueue/contextQueue/shuffledContextQueue/
 * podcastQueue → PLAYLIST/USER/CONTEXT/CONTEXT_SHUFFLED/PODCAST，后两段为 Batch 2 新增）
 * 按列表下标写连续 position，条目只存**稳定身份**（item_type + account_id + entity_id）与非权威
 * fallback 显示列；**不存 stream_url / cache_path / 已拼接账户 URL 的 coverArt**——
 * 这些值在下载完成、服务器 URL 切换、元数据更新后会过时。五队列 + 单行经
 * [com.amperfy.data.local.db.dao.PlaybackDao.replaceAllQueues] 单事务写入（唯一写入口）。
 *
 * **恢复**（[getPlaybackState]）：按以下六条装配——
 * 1. 优先 JOIN 到的最新实体，stream/cover URL 由该条目所属账户凭证经 [SubsonicUrlBuilder] 现生成；
 * 2. cache_path 现读并验证文件存在，不存在按未缓存处理**并清空陈旧路径**（本方法内唯一允许的写）；
 * 3. 标题/封面/ReplayGain 一律取实体最新值（fallback 列只在实体缺失时用）；
 * 4. SONG 不在库中（在线 Search 播放场景）→ 用 fallback 列 + 按 entity_id 重建 stream URL，视为未缓存；
 * 5. RADIO/PODCAST_EPISODE 引用缺失 → 记日志跳过，随后 position 自然重排并修正索引；
 * 6. （保存侧同事务，见上）。
 *
 * **[getPlaybackState] 不是仅启动路径**——PlaybackStateManager.saveProgress 会周期性
 * 「读→copy→写」，故装配必须轻量：按账户分组的批量 IN 预取（每类至多一次查询）+ 索引点查，
 * 无逐条回表；只有发现陈旧 cache_path 时才产生写。
 *
 * **账户防线不在此层**：PlaybackStateManager.isRestorableForAccounts 已对「队列含未知账户条目」
 * 做整体丢弃，本类不重复判定；凭证解析不到时条目仍保留（URL 降级为空串，普通歌曲播放时
 * PlayerManager.resolveStreamUri 会再按账户重建）。
 *
 * **已知平移语义**：升级不迁移旧播放状态——首启一次性没有可恢复队列，
 * 播放一次并保存后恢复正常。
 */
class RoomPlaybackStateStore(
    private val db: AmperfyDatabase,
    private val credentialsManager: CredentialsManager,
    private val filesDir: File,
) : PlaybackStateStore {

    private val songDao get() = db.songDao()
    private val songLocalStateDao get() = db.songLocalStateDao()
    private val playbackDao get() = db.playbackDao()
    private val radioDao get() = db.radioDao()
    private val podcastEpisodeDao get() = db.podcastEpisodeDao()
    private val episodeLocalStateDao get() = db.podcastEpisodeLocalStateDao()

    // ==================== 播放状态三方法（P4 批次 2 全量 Room） ====================

    /**
     * 读播放状态并装配三队列（恢复六条，见类 KDoc）。单行不存在 → null（无保存状态）。
     * 队列快照读在 [androidx.room.withTransaction] 内，避免与并发保存交错读到半状态
     * （组合读一致性用事务包裹，不为此新增 DAO 方法）。
     */
    override suspend fun getPlaybackState(): PlaybackState? {
        val snapshot = db.withTransaction<QueueSnapshot?> {
            val stateRow = playbackDao.getState() ?: return@withTransaction null
            QueueSnapshot(
                state = stateRow,
                playlist = playbackDao.getQueueItems(QUEUE_PLAYLIST),
                userQueue = playbackDao.getQueueItems(QUEUE_USER),
                contextQueue = playbackDao.getQueueItems(QUEUE_CONTEXT),
                shuffledContextQueue = playbackDao.getQueueItems(QUEUE_CONTEXT_SHUFFLED),
                podcastQueue = playbackDao.getQueueItems(QUEUE_PODCAST),
            )
        } ?: return null

        val context = loadRestoreContext(
            snapshot.playlist + snapshot.userQueue + snapshot.contextQueue +
                snapshot.shuffledContextQueue + snapshot.podcastQueue,
        )

        val playlist = assembleQueue(snapshot.playlist, context)
        val userQueue = assembleQueue(snapshot.userQueue, context)
        val contextQueue = assembleQueue(snapshot.contextQueue, context)
        val shuffledContextQueue = assembleQueue(snapshot.shuffledContextQueue, context)
        val podcastQueue = assembleQueue(snapshot.podcastQueue, context)

        clearStaleCachePaths(context.staleCachePaths)
        clearStaleEpisodeCachePaths(context.staleEpisodeCachePaths)

        val state = snapshot.state
        return PlaybackState(
            id = state.id,
            playlist = playlist.playables,
            currentIndex = adjustIndex(state.musicIndex, playlist),  // iOS: musicIndex
            playProgress = state.playProgress,
            playDuration = state.playDuration,
            contextType = parseContextType(state.contextType),
            contextId = state.contextId,
            contextName = state.contextName,
            playerMode = parsePlayerMode(state.playerMode),
            wasPlaying = state.wasPlaying,
            // prevQueue 动态计算，无需恢复
            userQueue = userQueue.playables,
            contextQueue = contextQueue.playables,
            currentContextIndex = adjustIndex(state.currentContextIndex, contextQueue),
            shuffledContextQueue = shuffledContextQueue.playables,
            podcastQueue = podcastQueue.playables,
            podcastIndex = adjustIndex(state.podcastIndex, podcastQueue),
            repeatMode = RepeatMode.fromRaw(state.repeatSetting),
            isShuffle = state.shuffleSetting == 1,
            // 权威值取新列；null（Batch 2 之前写入的行）才回退旧布尔推导——
            // 旧推导会把 SINGLE 吞成 CONTEXT，故仅作兼容兜底
            playSource = state.playSource
                ?: if (state.isUserQueuePlaying) "USER" else "CONTEXT",
            displayMode = state.displayMode,
            savedAt = state.savedAt,
        )
    }

    /**
     * 保存播放状态：三队列映射为身份引用行 + 标量单行，一次 replaceAllQueues 事务写入。
     * position 即列表下标（整队列替换故天然连续），队列间 position 可重复——主键含 queue_type。
     */
    override suspend fun savePlaybackState(state: PlaybackState) {
        val items = state.playlist.mapIndexed { index, playable -> playable.toQueueItem(QUEUE_PLAYLIST, index) } +
            state.userQueue.mapIndexed { index, playable -> playable.toQueueItem(QUEUE_USER, index) } +
            state.contextQueue.mapIndexed { index, playable -> playable.toQueueItem(QUEUE_CONTEXT, index) } +
            state.shuffledContextQueue.mapIndexed { index, playable ->
                playable.toQueueItem(QUEUE_CONTEXT_SHUFFLED, index)
            } +
            state.podcastQueue.mapIndexed { index, playable -> playable.toQueueItem(QUEUE_PODCAST, index) }
        playbackDao.replaceAllQueues(state.toStateEntity(), items)
    }

    /** 清除播放状态与三条队列（同事务）。 */
    override suspend fun clearPlaybackState() {
        playbackDao.clearAll()
    }

    // ==================== song 级本地统计（P3 批次 1a 起走 Room，本批不动） ====================

    /**
     * 写单曲播放进度（progressMs/updatedAt 同为 null = 清零）。先查 Room 歌曲存在，缺失静默跳过——
     * 既保持「歌曲不在库中不写」语义，又避免播客单集等库外 id 产生孤儿本地状态行。
     */
    override suspend fun saveSongProgress(accountId: String, songId: String, progressMs: Long?, updatedAt: Long?) {
        if (songDao.getByServerId(accountId, songId) == null) return
        songLocalStateDao.setProgressNullable(accountId, songId, progressMs, updatedAt)
    }

    override suspend fun getSongProgress(accountId: String, songId: String): Long? =
        songLocalStateDao.get(accountId, songId)?.playProgressMs

    /**
     * 写播客单集播放进度（Batch 4，镜像 [saveSongProgress]）。先查 podcast_episode 表存在，
     * 缺失静默跳过——同样避免库外 id（如在线搜索得到的临时条目）产生孤儿本地状态行。
     */
    override suspend fun saveEpisodeProgress(
        accountId: String,
        episodeId: String,
        progressMs: Long?,
        updatedAt: Long?,
    ) {
        if (podcastEpisodeDao.getByServerId(accountId, episodeId) == null) return
        episodeLocalStateDao.setProgressNullable(accountId, episodeId, progressMs, updatedAt)
    }

    override suspend fun getEpisodeProgress(accountId: String, episodeId: String): Long? =
        episodeLocalStateDao.get(accountId, episodeId)?.playProgressMs

    /** 本地播放计数 +1。先查歌曲存在，缺失静默跳过（不产生孤儿行）。 */
    override suspend fun incrementPlayCount(accountId: String, songId: String) {
        if (songDao.getByServerId(accountId, songId) == null) return
        songLocalStateDao.incrementPlayCount(accountId, songId)
    }

    // ==================== 保存侧映射（领域 → 实体） ====================

    /** 标量单行映射（musicIndex ← currentIndex 等）。 */
    private fun PlaybackState.toStateEntity(): PlaybackStateEntity = PlaybackStateEntity(
        id = 1,
        musicIndex = currentIndex,  // iOS: musicIndex
        playProgress = playProgress,
        playDuration = playDuration,
        contextType = contextType.name,
        contextId = contextId,
        contextName = contextName,
        playerMode = playerMode.name,
        wasPlaying = wasPlaying,
        isUserQueuePlaying = (playSource == "USER"),  // iOS: isUserQueuePlaying（兼容列，读取以 playSource 为准）
        currentContextIndex = currentContextIndex,
        repeatSetting = repeatMode.rawValue,
        shuffleSetting = if (isShuffle) 1 else 0,
        podcastIndex = podcastIndex,
        playSource = playSource,
        displayMode = displayMode,
        savedAt = savedAt,
    )

    /**
     * 队列条目映射：身份三元组（item_type/account_id/entity_id）+ 非权威 fallback 显示列。
     * **有意不写** streamUrl/coverArt/downloadPath（禁存烘焙 URL 与 cache_path）。
     */
    private fun Playable.toQueueItem(queueType: String, position: Int): PlaybackQueueItemEntity =
        PlaybackQueueItemEntity(
            queueType = queueType,
            position = position,
            itemType = when {
                isRadio -> ITEM_RADIO
                isPodcastEpisode -> ITEM_PODCAST_EPISODE
                else -> ITEM_SONG
            },
            accountId = accountId,
            entityId = id,
            fallbackTitle = title,
            fallbackArtist = artist,
            fallbackAlbum = album,
            fallbackDuration = duration,
            fallbackReplayGainTrackGain = replayGainTrackGain,
            fallbackReplayGainTrackPeak = replayGainTrackPeak,
            fallbackReplayGainAlbumGain = replayGainAlbumGain,
            fallbackReplayGainAlbumPeak = replayGainAlbumPeak,
        )

    // ==================== 恢复侧装配（六条） ====================

    /**
     * 按 (账户, 类型) 分组批量预取实体与凭证——每账户每类至多一次 IN 查询（禁逐条回表，
     * [getPlaybackState] 属周期调用路径）。歌曲的缓存文件存在性在此一并判定，
     * cache_path 指向的文件已消失者进 [RestoreContext.staleCachePaths] 待清。
     */
    private suspend fun loadRestoreContext(items: List<PlaybackQueueItemEntity>): RestoreContext {
        val songs = mutableMapOf<EntityKey, SongRestoreRow>()
        val radios = mutableMapOf<EntityKey, RadioEntity>()
        val episodes = mutableMapOf<EntityKey, EpisodeRestoreRow>()
        val stale = mutableListOf<EntityKey>()
        val staleEpisodes = mutableListOf<EntityKey>()

        val credentials = items.map { it.accountId }
            .distinct()
            .associateWith { credentialsFor(it) }

        items.groupBy { it.accountId }.forEach { (accountId, accountItems) ->
            val byType = accountItems.groupBy { it.itemType }

            byType[ITEM_SONG]?.let { songItems ->
                val ids = songItems.map { it.entityId }.distinct()
                songDao.getRowsByServerIds(accountId, ids).forEach { row ->
                    val cachePath = row.cachePath
                    val fileExists = cachePath != null &&
                        DownloadPathResolver.resolve(filesDir, cachePath).exists()
                    if (cachePath != null && !fileExists) {
                        stale += accountId to row.song.serverId
                    }
                    songs[accountId to row.song.serverId] =
                        SongRestoreRow(row, if (fileExists) cachePath else null)
                }
            }

            byType[ITEM_RADIO]?.let { radioItems ->
                val ids = radioItems.map { it.entityId }.distinct()
                radioDao.getByServerIds(accountId, ids).forEach { row ->
                    radios[accountId to row.serverId] = row
                }
            }

            byType[ITEM_PODCAST_EPISODE]?.let { episodeItems ->
                val ids = episodeItems.map { it.entityId }.distinct()
                podcastEpisodeDao.getByServerIdsWithTitle(accountId, ids).forEach { row ->
                    // Batch 4：单集缓存态与歌曲同口径——现读 cache_path 并验证文件存在，
                    // 已消失者进 staleEpisodes 待清
                    val cachePath = row.cachePath
                    val fileExists = cachePath != null &&
                        DownloadPathResolver.resolve(filesDir, cachePath).exists()
                    if (cachePath != null && !fileExists) {
                        staleEpisodes += accountId to row.episode.serverId
                    }
                    episodes[accountId to row.episode.serverId] =
                        EpisodeRestoreRow(row, if (fileExists) cachePath else null)
                }
            }
        }

        return RestoreContext(songs, radios, episodes, credentials, stale, staleEpisodes)
    }

    /**
     * 条目账户的凭证：ident 为空串（旧单账户数据）→ active 账户；命名空间键缺失 → 同样回退 active
     * （对照 PlayerManager.resolveStreamUri 的兜底模式）。解析不到时返回 null，URL 降级为空/无封面。
     */
    private fun credentialsFor(accountId: String): LoginCredentials? =
        if (accountId.isEmpty()) {
            credentialsManager.getCredentials()
        } else {
            credentialsManager.getCredentials(accountId) ?: credentialsManager.getCredentials()
        }

    /** 逐条重建 Playable，返回 null 的条目被跳过（记下标供索引修正）。 */
    private fun assembleQueue(items: List<PlaybackQueueItemEntity>, context: RestoreContext): AssembledQueue {
        val playables = ArrayList<Playable>(items.size)
        val skipped = mutableListOf<Int>()
        items.forEachIndexed { index, item ->
            val playable = restorePlayable(item, context)
            if (playable == null) skipped += index else playables += playable
        }
        return AssembledQueue(playables, skipped)
    }

    private fun restorePlayable(item: PlaybackQueueItemEntity, context: RestoreContext): Playable? {
        val key = item.accountId to item.entityId
        val credentials = context.credentials[item.accountId]
        return when (item.itemType) {
            ITEM_SONG -> context.songs[key]
                ?.let { restoreSong(item, it, credentials) }
                ?: restoreSongFromFallback(item, credentials)  // 恢复第 4 条：在线搜索播放

            ITEM_RADIO -> context.radios[key]
                ?.let { restoreRadio(item, it) }
                ?: skip(item, "radio not in library")

            ITEM_PODCAST_EPISODE -> context.episodes[key]
                ?.let { restoreEpisode(item, it, credentials) }
                ?: skip(item, "podcast episode not in library")

            else -> skip(item, "unknown item type")
        }
    }

    /** RADIO/PODCAST_EPISODE 引用缺失：记日志跳过，随后由索引修正收尾。 */
    private fun skip(item: PlaybackQueueItemEntity, reason: String): Playable? {
        Log.w(
            TAG,
            "Skip restoring queue item ${item.queueType}[${item.position}] " +
                "(${item.itemType} ${item.accountId}:${item.entityId}): $reason",
        )
        return null
    }

    /**
     * SONG 命中：显示字段/ReplayGain 取实体最新值；stream/cover URL 由该账户
     * 凭证现生成（无凭证 → streamUrl 空串、无封面；普通歌曲播放时 PlayerManager 会再按账户重建）；
     * 缓存态取 song_local_state.cache_path 且文件确实存在（陈旧路径已在预取阶段登记待清）。
     */
    private fun restoreSong(
        item: PlaybackQueueItemEntity,
        row: SongRestoreRow,
        credentials: LoginCredentials?,
    ): Playable {
        val song = row.row.song
        return Playable(
            id = song.serverId,
            title = song.title,
            artist = song.artistName,
            album = song.albumName,
            duration = song.duration,
            coverArt = song.coverArt?.let { artworkUrl(it, credentials) },
            streamUrl = streamUrl(song.serverId, credentials),
            isDownloaded = row.cachePath != null,
            downloadPath = row.cachePath,
            accountId = item.accountId,
            replayGainTrackGain = song.replayGainTrackGain,
            replayGainTrackPeak = song.replayGainTrackPeak,
            replayGainAlbumGain = song.replayGainAlbumGain,
            replayGainAlbumPeak = song.replayGainAlbumPeak,
        )
    }

    /**
     * SONG 不在库中（覆盖在线 Search 直接播放）：显示字段取非权威 fallback 列，
     * stream URL 仍按 entity_id 重建，一律视为未缓存；条目**保留**（账户防线在上游）。
     */
    private fun restoreSongFromFallback(
        item: PlaybackQueueItemEntity,
        credentials: LoginCredentials?,
    ): Playable = Playable(
        id = item.entityId,
        title = item.fallbackTitle ?: "",
        artist = item.fallbackArtist ?: "",
        album = item.fallbackAlbum ?: "",
        duration = item.fallbackDuration ?: 0,
        coverArt = null,
        streamUrl = streamUrl(item.entityId, credentials),
        isDownloaded = false,
        accountId = item.accountId,
        replayGainTrackGain = item.fallbackReplayGainTrackGain,
        replayGainTrackPeak = item.fallbackReplayGainTrackPeak,
        replayGainAlbumGain = item.fallbackReplayGainAlbumGain,
        replayGainAlbumPeak = item.fallbackReplayGainAlbumPeak,
    )

    /**
     * RADIO 命中：用实体 stream_url 原始快照直连（对照 Radio.toPlayable——电台不经服务器 URL 生成），
     * artist/album 空串、duration 0、无封面，isRadio=true 使 Scrobble/自动缓存短路。
     */
    private fun restoreRadio(item: PlaybackQueueItemEntity, row: RadioEntity): Playable = Playable(
        id = row.serverId,
        title = row.name,
        artist = "",          // iOS creatorName = ""
        album = "",
        duration = 0,         // 直播流无时长
        coverArt = null,
        streamUrl = row.streamUrl,
        isDownloaded = false,
        isRadio = true,
        accountId = item.accountId,
    )

    /**
     * PODCAST_EPISODE 命中：流 URL 用 `stream_id ?? server_id` 重建（对照
     * PodcastEpisode.toPlayableWithCredentials / iOS `playableInfo.streamId ?? playableInfo.id`）；
     * artist 显示父频道名（JOIN 带出，缺失兜底 "Unknown Podcast"），isPodcastEpisode=true。
     */
    private fun restoreEpisode(
        item: PlaybackQueueItemEntity,
        row: EpisodeRestoreRow,
        credentials: LoginCredentials?,
    ): Playable {
        val episode = row.row.episode
        val apiId = episode.streamId ?: episode.serverId
        return Playable(
            id = episode.serverId,
            title = episode.title,
            artist = row.row.podcastTitle ?: UNKNOWN_PODCAST_TITLE,
            album = "",
            duration = episode.duration,
            coverArt = episode.coverArt?.let { artworkUrl(it, credentials) },
            streamUrl = streamUrl(apiId, credentials, isSong = false),
            // Batch 4：已缓存单集走本地文件播放（cachePath 已在预取阶段验证过文件存在）
            isDownloaded = row.cachePath != null,
            downloadPath = row.cachePath,
            isPodcastEpisode = true,
            accountId = item.accountId,
        )
    }

    /**
     * 恢复用流 URL。Ampache 账户（Ampache 移植 Batch 2）走各自的端点形态，**token 留空**
     * ——本 Store 在 Room 边界内，拿不到该账户的握手会话；空 auth 由播放侧的
     * `ResolvingDataSource`（[com.amperfy.core.AmpacheUrlAuthRefresher]）在装载时刻补上。
     *
     * 注：歌曲的流 URL 实际上会被 PlayerManager.resolveStreamUri 按所属账户重建，
     * 这里的值只是占位；**播客单集不重建**（走 Playable.streamUrl），故必须形态正确。
     */
    private fun streamUrl(
        id: String,
        credentials: LoginCredentials?,
        isSong: Boolean = true,
    ): String = credentials?.let {
        if (it.backendApi == BackendApiType.AMPACHE) {
            AmpacheUrl.streamUrlWithoutToken(it.serverUrl, id, isSong)
        } else {
            SubsonicUrlBuilder.generateUrlForStreamingPlayable(id, it.username, it.password, it.serverUrl, it.backendApi)
        }
    } ?: ""

    /** 恢复用封面 URL；Ampache 同样 token 留空，由 Coil 的 AmpacheArtworkAuthInterceptor 补 */
    private fun artworkUrl(coverArtId: String, credentials: LoginCredentials?): String? =
        credentials?.let {
            if (it.backendApi == BackendApiType.AMPACHE) {
                AmpacheUrl.artworkUrlWithoutToken(it.serverUrl, coverArtId)
            } else {
                SubsonicUrlBuilder.generateUrlForArtwork(coverArtId, it.username, it.password, it.serverUrl, it.backendApi)
            }
        }

    /**
     * 清空陈旧 cache_path——本读方法内唯一允许的写；无陈旧项时不产生任何写
     * （[getPlaybackState] 被 saveProgress 周期调用，须保持轻量）。
     */
    private suspend fun clearStaleCachePaths(stale: List<EntityKey>) {
        if (stale.isEmpty()) return
        stale.forEach { (accountId, songId) ->
            songLocalStateDao.setCachePath(accountId, songId, null)
        }
        Log.w(TAG, "Cleared ${stale.size} stale cache path(s): cached file missing")
    }

    /** 单集侧的陈旧 cache_path 清理（Batch 4，语义同 [clearStaleCachePaths]）。 */
    private suspend fun clearStaleEpisodeCachePaths(stale: List<EntityKey>) {
        if (stale.isEmpty()) return
        stale.forEach { (accountId, episodeId) ->
            episodeLocalStateDao.setCachePath(accountId, episodeId, null)
        }
        Log.w(TAG, "Cleared ${stale.size} stale episode cache path(s): cached file missing")
    }

    /**
     * 跳过条目后的索引修正：减去该索引之前被跳过的条目数，再 clamp 到 [0, size-1]
     * （空队列取 0）。**无跳过时原值逐字回传**——保持「保存什么读回什么」的往返保真
     * （越界索引由上层 Player 处理，此处不校正）。
     */
    private fun adjustIndex(original: Int, queue: AssembledQueue): Int {
        if (queue.skippedIndices.isEmpty()) return original
        val shifted = original - queue.skippedIndices.count { it < original }
        return shifted.coerceIn(0, maxOf(0, queue.playables.size - 1))
    }

    private fun parseContextType(raw: String): PlayContextType = try {
        PlayContextType.valueOf(raw)
    } catch (e: Exception) {
        PlayContextType.NONE
    }

    private fun parsePlayerMode(raw: String): PlayerMode = try {
        PlayerMode.valueOf(raw)
    } catch (e: Exception) {
        PlayerMode.MUSIC
    }

    // ==================== 内部装配数据结构 ====================

    /** 一致性快照：单行状态 + 五条有序队列（同一事务内读出）。 */
    private data class QueueSnapshot(
        val state: PlaybackStateEntity,
        val playlist: List<PlaybackQueueItemEntity>,
        val userQueue: List<PlaybackQueueItemEntity>,
        val contextQueue: List<PlaybackQueueItemEntity>,
        val shuffledContextQueue: List<PlaybackQueueItemEntity>,
        val podcastQueue: List<PlaybackQueueItemEntity>,
    )

    /** 歌曲实体 + 已验证的缓存路径（[cachePath] 非空 = 文件确实存在）。 */
    private data class SongRestoreRow(
        val row: SongWithLocal,
        val cachePath: String?,
    )

    /** 单集实体（含父频道名）+ 已验证的缓存路径（Batch 4，结构对称 [SongRestoreRow]）。 */
    private data class EpisodeRestoreRow(
        val row: EpisodeWithPodcastTitle,
        val cachePath: String?,
    )

    /** 预取结果：三类实体索引 + 每账户凭证 + 待清理的陈旧缓存路径（歌曲/单集分列）。 */
    private data class RestoreContext(
        val songs: Map<EntityKey, SongRestoreRow>,
        val radios: Map<EntityKey, RadioEntity>,
        val episodes: Map<EntityKey, EpisodeRestoreRow>,
        val credentials: Map<String, LoginCredentials?>,
        val staleCachePaths: List<EntityKey>,
        val staleEpisodeCachePaths: List<EntityKey>,
    )

    /** 装配后的队列 + 被跳过条目在原列表中的下标（升序），供索引修正。 */
    private data class AssembledQueue(
        val playables: List<Playable>,
        val skippedIndices: List<Int>,
    )

    companion object {
        private const val TAG = "RoomPlaybackStateStore"

        // 队列类型与条目类型的字面量（与 PlaybackQueueItemEntity KDoc 的取值域一致）
        private const val QUEUE_PLAYLIST = "PLAYLIST"
        private const val QUEUE_USER = "USER"
        private const val QUEUE_CONTEXT = "CONTEXT"

        /** 打乱后的上下文副本（Batch 2，对应 iOS shuffledContextPlaylist） */
        private const val QUEUE_CONTEXT_SHUFFLED = "CONTEXT_SHUFFLED"

        /** 播客独立队列（Batch 2，对应 iOS podcastPlaylist） */
        private const val QUEUE_PODCAST = "PODCAST"
        private const val ITEM_SONG = "SONG"
        private const val ITEM_RADIO = "RADIO"
        private const val ITEM_PODCAST_EPISODE = "PODCAST_EPISODE"

        /** 父频道名缺失兜底（与 P3 批次 3b 单集映射同口径） */
        private const val UNKNOWN_PODCAST_TITLE = "Unknown Podcast"
    }
}

/** 实体身份键：(accountId, serverId)——恢复装配的进程内索引键，不落库。 */
private typealias EntityKey = Pair<String, String>
