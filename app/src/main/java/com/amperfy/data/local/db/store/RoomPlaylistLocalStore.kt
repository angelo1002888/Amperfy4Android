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

import androidx.room.withTransaction
import com.amperfy.data.local.db.AmperfyDatabase
import com.amperfy.data.local.db.entity.AccountScopeEntity
import com.amperfy.data.local.db.mapper.LibraryTextKeyNormalizer
import com.amperfy.data.local.db.mapper.toEntity
import com.amperfy.data.local.db.mapper.toPlaylist
import com.amperfy.data.local.db.mapper.toSong
import com.amperfy.data.local.store.PlaylistLocalStore
import com.amperfy.data.model.Playlist
import com.amperfy.data.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * PlaylistLocalStore 的 Room 全量实现（专题 15 P3 批次 2a）。
 *
 * P3 批次 2a 只实现不接线（无运行时行为变化）；批次 2b 换绑为 PlaylistLocalStore 的生产实现
 * （首个 playlist 域运行时行为变化）。
 *
 * 接口 15 方法全属 playlist 域，构造只需 [AmperfyDatabase]。
 *
 * 两条合同：
 * 1. **唯一顺序写入口是 PlaylistSongDao.replacePlaylistSongs**（整表替换）——详情同步/追加/删除/
 *    重排全是「读当前有序 id 列表 → 内存变换 → 整表替换」，无任何逐行 position UPDATE。
 * 2. **getPlaylistSongs 经 [observeSongsWithLocalOf] 引用 song_local_state，靠 Room 表级失效**
 *    在成员歌曲缓存状态变化时再发射（2b 换绑合同）。
 *
 * Flow override 一律映射领域模型后 `distinctUntilChanged()`（收敛表级失效带来的等值重发）；
 * 事务用 `db.withTransaction { }`。
 */
class RoomPlaylistLocalStore(
    private val db: AmperfyDatabase,
) : PlaylistLocalStore {

    private val playlistDao get() = db.playlistDao()
    private val playlistSongDao get() = db.playlistSongDao()
    private val playlistLocalStateDao get() = db.playlistLocalStateDao()
    private val songDao get() = db.songDao()

    /** 本地子串搜索 LIKE 模式：normalizer 生成 search_key 再转义通配符，配合 DAO `ESCAPE '\'`。 */
    private fun likePattern(query: String): String =
        "%" + LibraryTextKeyNormalizer.escapeLikePattern(LibraryTextKeyNormalizer.searchKey(query)) + "%"

    /**
     * 保证账户租户根 account_scope 行存在。playlist/playlist_local_state 均 FK→account_scope
     * 级联，缺父行时 sync 写会被 FK 拒绝——每个 sync 写入口（upsertPlaylistsMetadata /
     * replacePlaylists / applyPlaylistDetails）在事务内第一步幂等调用。P4 起改由账户生命周期维护，
     * 届时从各写入口移除本调用（与 RoomLibraryLocalStore.ensureScope 同口径）。
     */
    private suspend fun ensureScope(accountId: String) {
        db.accountScopeDao().upsert(AccountScopeEntity(accountId))
    }

    // ==================== 读 ====================

    override fun getAllPlaylists(accountId: String): Flow<List<Playlist>> =
        playlistDao.observeAllWithState(accountId)
            .map { rows -> rows.map { it.toPlaylist() } }
            .distinctUntilChanged()

    override fun observePlaylistById(accountId: String, playlistId: String): Flow<Playlist?> =
        playlistDao.observeWithStateByServerId(accountId, playlistId)
            .map { it?.toPlaylist() }
            .distinctUntilChanged()

    override suspend fun getPlaylistById(accountId: String, playlistId: String): Playlist? =
        playlistDao.getWithStateByServerId(accountId, playlistId)?.toPlaylist()

    override fun getPlaylistSongs(accountId: String, playlistId: String): Flow<List<Song>> =
        playlistSongDao.observeSongsWithLocalOf(accountId, playlistId)
            .map { rows -> rows.map { it.toSong() } }
            .distinctUntilChanged()

    override fun searchPlaylists(accountId: String, query: String): Flow<List<Playlist>> =
        playlistDao.searchByKey(accountId, likePattern(query))
            .map { rows -> rows.map { it.toPlaylist() } }
            .distinctUntilChanged()

    override fun getCachedPlaylistIds(accountId: String): Flow<Set<String>> =
        playlistDao.observeCachedPlaylistIds(accountId)
            .map { it.toSet() }
            .distinctUntilChanged()

    override fun getFullyCachedPlaylistIds(accountId: String): Flow<Set<String>> =
        playlistDao.observeFullyCachedPlaylistIds(accountId)
            .map { it.toSet() }
            .distinctUntilChanged()

    // ==================== 本地状态写 ====================

    override suspend fun updatePlaylistLastPlayed(accountId: String, playlistId: String, lastPlayedAt: Long) {
        db.withTransaction {
            // 存在守卫：不存在静默跳过，同时防孤儿 state 行——
            // playlist_local_state 不级联 playlist，为不存在的列表写 last_played 会留孤儿行。
            if (playlistDao.getByServerId(accountId, playlistId) == null) return@withTransaction
            playlistLocalStateDao.setLastPlayed(accountId, playlistId, lastPlayedAt)
        }
    }

    // ==================== sync/变更写 ====================

    override suspend fun upsertPlaylistsMetadata(accountId: String, playlists: List<Playlist>) {
        db.withTransaction {
            ensureScope(accountId)
            // 全行 upsert 元数据（含 songCount/duration 快照）；
            // 不触碰 playlist_song，避免覆盖已同步的歌曲列表。
            playlistDao.upsertRemote(playlists.map { it.toEntity(accountId) })
        }
    }

    override suspend fun replacePlaylists(accountId: String, playlists: List<Playlist>) {
        db.withTransaction {
            ensureScope(accountId)
            playlistDao.upsertRemote(playlists.map { it.toEntity(accountId) })
            // prune：服务器已删除的播放列表本地同步删除（限本账户）。
            val keep = playlists.map { it.id }
            // 删 playlist 行 → FK CASCADE 连带清被删列表的 playlist_song；
            // playlist_local_state 不级联 playlist，须显式清（lastPlayed 随播放列表删除一并消失）。
            playlistDao.deleteAllExcept(accountId, keep)
            playlistLocalStateDao.deleteAllExcept(accountId, keep)
        }
    }

    override suspend fun applyPlaylistDetails(
        accountId: String,
        playlistId: String,
        metadata: Playlist,
        songs: List<Song>,
    ) {
        db.withTransaction {
            ensureScope(accountId)
            // 1. upsert 歌曲元数据入 song 表。无需手工保留 isDownloaded/playProgress 等本地字段——
            //    song_local_state 与 song 表物理隔离，远端 upsert 写不到本地状态（结构性保证）。
            songDao.upsertRemote(songs.map { it.toEntity(accountId) })
            // 2. upsert 播放列表元数据：身份用 playlistId 参数；songCount/duration 由
            //    参数歌曲计算（歌曲数 / 时长和），忽略 metadata 自带值。
            playlistDao.upsertRemote(
                listOf(
                    metadata.toEntity(accountId).copy(
                        serverId = playlistId,
                        songCount = songs.size,
                        duration = songs.sumOf { it.duration },
                    ),
                ),
            )
            // 3. 整表替换有序关系（允许重复 id）。
            playlistSongDao.replacePlaylistSongs(accountId, playlistId, songs.map { it.id })
        }
    }

    override suspend fun deletePlaylist(accountId: String, playlistId: String) {
        db.withTransaction {
            // deleteByServerId 不存在时自然 no-op（静默跳过）；FK CASCADE 清 playlist_song，
            // playlist_local_state 显式清（不级联 playlist）。
            playlistDao.deleteByServerId(accountId, playlistId)
            playlistLocalStateDao.deleteByPlaylistId(accountId, playlistId)
        }
    }

    override suspend fun renamePlaylist(accountId: String, playlistId: String, newName: String) {
        db.withTransaction {
            if (playlistDao.getByServerId(accountId, playlistId) == null) return@withTransaction
            // search_key 由 name 派生，rename 时经 normalizer 重算并一并 UPDATE（原子一致）。
            playlistDao.updateName(
                accountId = accountId,
                serverId = playlistId,
                name = newName,
                searchKey = LibraryTextKeyNormalizer.searchKey(newName),
            )
        }
    }

    override suspend fun appendPlaylistSongs(accountId: String, playlistId: String, songIds: List<String>) {
        db.withTransaction {
            val playlist = playlistDao.getByServerId(accountId, playlistId) ?: return@withTransaction
            // 详情已同步：有关系行，或本就是空列表（songCount==0）时才维护 songs 关系；
            // 未同步详情时 songCount 来自服务器元数据，只做增量累加，避免被重置。
            val detailsSynced = playlistSongDao.countFor(accountId, playlistId) > 0 || playlist.songCount == 0

            // 本地存在歌曲的时长表（按 id 去重查询）。
            val durationById = songDao.getRowsByServerIds(accountId, songIds.distinct())
                .associate { it.song.serverId to it.song.duration }
            // 按 songIds 原顺序（含重复）过滤出本地存在的 id 序列。
            val orderedFound = songIds.filter { durationById.containsKey(it) }
            // 本地缺失的歌只计入 songCount 总数，不建关系；时长按出现次数累计。
            val addedDuration = orderedFound.sumOf { durationById[it] ?: 0 }

            if (detailsSynced) {
                // 追加到现有有序关系尾部，整表替换（唯一写入口）。
                val current = playlistSongDao.getSongIdsOrdered(accountId, playlistId)
                playlistSongDao.replacePlaylistSongs(accountId, playlistId, current + orderedFound)
            }
            // 服务器已接受全部添加：songCount 累加 songIds.size（含本地缺失，待下次详情同步补全关系），
            // duration 累加本地存在歌曲的时长。
            playlistDao.updateCounts(
                accountId = accountId,
                serverId = playlistId,
                songCount = playlist.songCount + songIds.size,
                duration = playlist.duration + addedDuration,
            )
        }
    }

    override suspend fun removePlaylistSongAt(accountId: String, playlistId: String, index: Int) {
        db.withTransaction {
            playlistDao.getByServerId(accountId, playlistId) ?: return@withTransaction
            val current = playlistSongDao.getSongIdsOrdered(accountId, playlistId)
            if (index !in current.indices) return@withTransaction // 越界静默跳过
            val next = current.toMutableList().apply { removeAt(index) }
            playlistSongDao.replacePlaylistSongs(accountId, playlistId, next)
            // 重算 songCount/duration = 关系行数 / 时长和。
            playlistDao.updateCounts(
                accountId = accountId,
                serverId = playlistId,
                songCount = playlistSongDao.countFor(accountId, playlistId),
                duration = playlistSongDao.sumDurationFor(accountId, playlistId),
            )
        }
    }

    override suspend fun reorderPlaylistSongs(accountId: String, playlistId: String, orderedSongIds: List<String>) {
        db.withTransaction {
            playlistDao.getByServerId(accountId, playlistId) ?: return@withTransaction
            // 只保留本地存在的歌（mapNotNull 镜像——用 getRowsByServerIds 判存在），保持原顺序与重复。
            val present = songDao.getRowsByServerIds(accountId, orderedSongIds.distinct())
                .map { it.song.serverId }
                .toSet()
            val reordered = orderedSongIds.filter { it in present }
            playlistSongDao.replacePlaylistSongs(accountId, playlistId, reordered)
            playlistDao.updateCounts(
                accountId = accountId,
                serverId = playlistId,
                songCount = playlistSongDao.countFor(accountId, playlistId),
                duration = playlistSongDao.sumDurationFor(accountId, playlistId),
            )
        }
    }
}
