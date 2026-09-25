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

package com.amperfy.data.local.db

import androidx.test.core.app.ApplicationProvider
import androidx.room.Room
import com.amperfy.data.local.db.entity.AccountScopeEntity
import com.amperfy.data.local.db.entity.AlbumEntity
import com.amperfy.data.local.db.entity.ArtistEntity
import com.amperfy.data.local.db.entity.DirectoryEntity
import com.amperfy.data.local.db.entity.DownloadEntryEntity
import com.amperfy.data.local.db.entity.GenreEntity
import com.amperfy.data.local.db.entity.MusicFolderEntity
import com.amperfy.data.local.db.entity.PlaybackQueueItemEntity
import com.amperfy.data.local.db.entity.PlaylistEntity
import com.amperfy.data.local.db.entity.PodcastEntity
import com.amperfy.data.local.db.entity.PodcastEpisodeEntity
import com.amperfy.data.local.db.entity.RadioEntity
import com.amperfy.data.local.db.entity.SearchHistoryEntity
import com.amperfy.data.local.db.entity.SongEntity
import com.amperfy.data.local.db.mapper.LibraryTextKeyNormalizer

/**
 * DAO instrumentation 测试共用夹具（专题 15 P2 批次 3）。
 *
 * 与 Repository 边界的 RepositoryFixture 平行——本夹具面向 Room `data/local/db/` 全 21 表 DAO：
 * - [openDatabase]：`Room.inMemoryDatabaseBuilder` 建内存库（Room 默认开启 FK 约束，级联测试依赖此）；
 * - [seedScopes]：预插 account_scope 两租户根 [ACCT_A]/[ACCT_B]（账户表 FK 父行，缺则插入被 FK 拒绝）；
 * - 行构造助手：给必填派生键的实体（artist/album/song/genre/radio/podcast/directory/playlist）
 *   一律经 [LibraryTextKeyNormalizer] 生成 search/section/sort 键——**同一规则源**，不在测试里手拼键，
 *   否则排序/搜索断言会与生产映射脱节；
 * - 原始 SQL 助手 [execSql]/[countRows]/[queryPositions]：DAO 未暴露删表/计数方法时的测试内联查询
 *   （允许用 openHelper execSQL 删父行验证级联）。
 *
 * 所有用例 runBlocking；时间戳一律用字面量常量，DAO 不取系统时钟（本批红线）。
 */
object DbTestFixture {

    const val ACCT_A = "acctA"
    const val ACCT_B = "acctB"

    /** PodcastEpisodeRemoteStatus.UNDEFINED.raw 的本地镜像（保持 fixture 不隐式耦合 model 层）。 */
    private const val PODCAST_EPISODE_STATUS_UNDEFINED = 0

    /** 建内存 Room 库（进程结束即销毁，@After 仍显式 close 释放连接）。 */
    fun openDatabase(): AmperfyDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AmperfyDatabase::class.java,
        ).build()

    /** 预插两租户根：账户级表 FK→account_scope，父行缺失会令后续插入被 FK 拒绝。 */
    suspend fun seedScopes(db: AmperfyDatabase) {
        db.accountScopeDao().upsert(AccountScopeEntity(ACCT_A))
        db.accountScopeDao().upsert(AccountScopeEntity(ACCT_B))
    }

    // ---- 行构造助手（派生键统一经 LibraryTextKeyNormalizer，单一规则源）----

    fun artist(
        accountId: String = ACCT_A,
        serverId: String,
        name: String,
    ): ArtistEntity = ArtistEntity(
        accountId = accountId,
        serverId = serverId,
        name = name,
        searchKey = LibraryTextKeyNormalizer.searchKey(name),
        sectionKey = LibraryTextKeyNormalizer.sectionKey(name),
        sortKey = LibraryTextKeyNormalizer.sortKey(name),
    )

    fun album(
        accountId: String = ACCT_A,
        serverId: String,
        name: String = "Album $serverId",
        artistId: String? = null,
        artistName: String = "",
    ): AlbumEntity = AlbumEntity(
        accountId = accountId,
        serverId = serverId,
        name = name,
        artistId = artistId,
        artistName = artistName,
        searchKey = LibraryTextKeyNormalizer.searchKey(name),
        sectionKey = LibraryTextKeyNormalizer.sectionKey(name),
        sortKey = LibraryTextKeyNormalizer.sortKey(name),
        artistSortKey = LibraryTextKeyNormalizer.sortKey(artistName),
    )

    fun song(
        accountId: String = ACCT_A,
        serverId: String,
        title: String = "Song $serverId",
        albumId: String? = null,
        artistId: String? = null,
    ): SongEntity = SongEntity(
        accountId = accountId,
        serverId = serverId,
        title = title,
        albumId = albumId,
        artistId = artistId,
        searchKey = LibraryTextKeyNormalizer.searchKey(title),
        sectionKey = LibraryTextKeyNormalizer.sectionKey(title),
        sortKey = LibraryTextKeyNormalizer.sortKey(title),
    )

    fun genre(
        accountId: String = ACCT_A,
        name: String,
    ): GenreEntity = GenreEntity(
        accountId = accountId,
        name = name,
        searchKey = LibraryTextKeyNormalizer.searchKey(name),
        sectionKey = LibraryTextKeyNormalizer.sectionKey(name),
        sortKey = LibraryTextKeyNormalizer.sortKey(name),
    )

    fun radio(
        accountId: String = ACCT_A,
        serverId: String,
        name: String = "Radio $serverId",
    ): RadioEntity = RadioEntity(
        accountId = accountId,
        serverId = serverId,
        name = name,
        searchKey = LibraryTextKeyNormalizer.searchKey(name),
        sectionKey = LibraryTextKeyNormalizer.sectionKey(name),
        sortKey = LibraryTextKeyNormalizer.sortKey(name),
    )

    fun podcast(
        accountId: String = ACCT_A,
        serverId: String,
        title: String = "Podcast $serverId",
    ): PodcastEntity = PodcastEntity(
        accountId = accountId,
        serverId = serverId,
        title = title,
        searchKey = LibraryTextKeyNormalizer.searchKey(title),
        sortKey = LibraryTextKeyNormalizer.sortKey(title),
    )

    fun episode(
        accountId: String = ACCT_A,
        serverId: String,
        podcastId: String?,
        title: String = "Episode $serverId",
        publishDate: Long = 0,
        status: Int = PODCAST_EPISODE_STATUS_UNDEFINED,
    ): PodcastEpisodeEntity = PodcastEpisodeEntity(
        accountId = accountId,
        serverId = serverId,
        title = title,
        publishDate = publishDate,
        status = status,
        podcastId = podcastId,
    )

    fun directory(
        accountId: String = ACCT_A,
        serverId: String,
        name: String = "Dir $serverId",
        parentId: String? = null,
        musicFolderId: String? = null,
    ): DirectoryEntity = DirectoryEntity(
        accountId = accountId,
        serverId = serverId,
        name = name,
        parentId = parentId,
        musicFolderId = musicFolderId,
        sortKey = LibraryTextKeyNormalizer.sortKey(name),
    )

    fun musicFolder(
        accountId: String = ACCT_A,
        serverId: String,
        name: String = "Folder $serverId",
    ): MusicFolderEntity = MusicFolderEntity(
        accountId = accountId,
        serverId = serverId,
        name = name,
    )

    fun playlist(
        accountId: String = ACCT_A,
        serverId: String,
        name: String = "Playlist $serverId",
    ): PlaylistEntity = PlaylistEntity(
        accountId = accountId,
        serverId = serverId,
        name = name,
        searchKey = LibraryTextKeyNormalizer.searchKey(name),
    )

    fun download(
        accountId: String = ACCT_A,
        songId: String,
        creationDate: Long = 0,
    ): DownloadEntryEntity = DownloadEntryEntity(
        accountId = accountId,
        songId = songId,
        creationDate = creationDate,
    )

    fun searchHistory(
        accountId: String = ACCT_A,
        type: String,
        entityId: String,
        searchedAt: Long = 0,
    ): SearchHistoryEntity = SearchHistoryEntity(
        accountId = accountId,
        type = type,
        entityId = entityId,
        searchedAt = searchedAt,
    )

    fun queueItem(
        queueType: String,
        position: Int,
        itemType: String = "SONG",
        accountId: String = ACCT_A,
        entityId: String,
    ): PlaybackQueueItemEntity = PlaybackQueueItemEntity(
        queueType = queueType,
        position = position,
        itemType = itemType,
        accountId = accountId,
        entityId = entityId,
    )

    // ---- 原始 SQL 助手（DAO 未暴露删表/计数时的测试内联查询）----

    /** 直接执行 SQL（如删父行验证级联）。 */
    fun execSql(db: AmperfyDatabase, sql: String, vararg bindArgs: Any?) {
        // 显式收敛为 Array<Any?>，兼容 execSQL 的两种签名（Kotlin 版 Array<out Any?> / 旧 Java 平台类型）
        db.openHelper.writableDatabase.execSQL(sql, arrayOf<Any?>(*bindArgs))
    }

    /** 统计某账户在某表的行数（账户表均含 account_id 列）。 */
    fun countRows(db: AmperfyDatabase, table: String, accountId: String): Int {
        db.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM $table WHERE account_id = ?", arrayOf<Any?>(accountId))
            .use { cursor ->
                cursor.moveToFirst()
                return cursor.getInt(0)
            }
    }

    /** 统计全局表（无 account_id）总行数。 */
    fun countAllRows(db: AmperfyDatabase, table: String): Int {
        db.openHelper.readableDatabase
            .query("SELECT COUNT(*) FROM $table")
            .use { cursor ->
                cursor.moveToFirst()
                return cursor.getInt(0)
            }
    }

    /** 读某 playlist 的 position 列表（按 position 升序），验证连续性。 */
    fun queryPositions(db: AmperfyDatabase, accountId: String, playlistId: String): List<Int> {
        val result = mutableListOf<Int>()
        db.openHelper.readableDatabase
            .query(
                "SELECT position FROM playlist_song " +
                    "WHERE account_id = ? AND playlist_id = ? ORDER BY position",
                arrayOf<Any?>(accountId, playlistId),
            )
            .use { cursor ->
                while (cursor.moveToNext()) {
                    result.add(cursor.getInt(0))
                }
            }
        return result
    }
}
