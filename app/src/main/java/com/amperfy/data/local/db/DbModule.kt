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

import android.content.Context
import androidx.room.Room
import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.local.db.store.RoomAccountLocalStore
import com.amperfy.data.local.db.store.RoomDownloadLocalStore
import com.amperfy.data.local.db.store.RoomEventLogStore
import com.amperfy.data.local.db.store.RoomLibraryLocalStore
import com.amperfy.data.local.db.store.RoomPlaybackStateStore
import com.amperfy.data.local.db.store.RoomPlaylistLocalStore
import com.amperfy.data.local.db.store.RoomSearchHistoryStore
import com.amperfy.data.local.store.AccountLocalStore
import com.amperfy.data.local.store.DownloadLocalStore
import com.amperfy.data.local.store.EventLogStore
import com.amperfy.data.local.store.LibraryLocalStore
import com.amperfy.data.local.store.PlaybackStateStore
import com.amperfy.data.local.store.PlaylistLocalStore
import com.amperfy.data.local.store.SearchHistoryStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Room 数据库与全部持久化 Store 的 Hilt 提供者（专题 15 P3 批次 1a 引入，P5 收编为唯一）。
 *
 * **P5 起本模块是唯一的数据库与持久化边界 Store 提供者**：过渡期的旧引擎装配模块随旧引擎
 * 代码一并删除，其余 7 个 Store provider（EventLog/Playlist/Download/PlaybackState/
 * Account/SearchHistory/Library）平移至此，全部绑定 `data/local/db/store/` 下的 Room 实现。
 *
 * 各 Store 均为单例：接口方法自带 accountId 参数，无须每账户实例化。
 *
 * 严禁 `fallbackToDestructiveMigration()`：Room 上线后 Schema 变化必须走显式
 * Migration + 迁移测试，破坏性迁移会掩盖后续迁移问题并丢失下载/进度/队列等不可由服务器恢复的状态。
 */
@Module
@InstallIn(SingletonComponent::class)
object DbModule {

    @Provides
    @Singleton
    fun provideAmperfyDatabase(
        @ApplicationContext context: Context,
    ): AmperfyDatabase =
        Room.databaseBuilder(
            context,
            AmperfyDatabase::class.java,
            AmperfyDatabase.DATABASE_NAME,
        ).build()

    /**
     * EventLogStore = Room 全量实现（P4 批次 1 换绑）。event_log 为全局表（无 account_id）；
     * 平移语义：旧日志不迁移，升级后 Event Log 首启为空。
     */
    @Provides
    @Singleton
    fun provideEventLogStore(db: AmperfyDatabase): EventLogStore =
        RoomEventLogStore(db)

    /**
     * PlaylistLocalStore = Room 全量实现（P3 批次 2b 换绑）。playlist 域读写全部走 Room。
     */
    @Provides
    @Singleton
    fun providePlaylistLocalStore(db: AmperfyDatabase): PlaylistLocalStore =
        RoomPlaylistLocalStore(db)

    /**
     * DownloadLocalStore = Room 全量实现（P4 批次 1 换绑）。下载状态机（requested/finished/
     * failed/cancel/retry/clear/启动恢复）走 download_entry 表、歌曲装配/缓存标记走 Room
     * （缓存态在 song_local_state.cache_path）。
     * 平移语义：旧下载记录不迁移，升级后 Downloads 列表首启为空。
     */
    @Provides
    @Singleton
    fun provideDownloadLocalStore(db: AmperfyDatabase): DownloadLocalStore =
        RoomDownloadLocalStore(db)

    /**
     * PlaybackStateStore = Room 全量实现（P4 批次 2 换绑）。playback_state 全局单行 + 三条队列走
     * playback_queue_item 身份引用表（恢复时 JOIN 最新实体、按账户凭证现生成 stream/cover URL），
     * 单曲播放进度/播放计数仍走 song_local_state。
     * 构造需 [CredentialsManager]（按条目账户解析凭证）与 filesDir（验证 cache_path 文件存在）。
     * 平移语义：旧播放状态不迁移，升级后首启一次性无可恢复队列。
     */
    @Provides
    @Singleton
    fun providePlaybackStateStore(
        db: AmperfyDatabase,
        credentialsManager: CredentialsManager,
        @ApplicationContext context: Context,
    ): PlaybackStateStore =
        RoomPlaybackStateStore(db, credentialsManager, context.filesDir)

    /**
     * AccountLocalStore = Room 全量实现（P4 批次 1 换绑）。账户元数据**不落库**（租户根
     * 不存资料，唯一来源 CredentialsManager），两个写方法收敛为租户根 account_scope 行的生命
     * 周期维护：登录建行（幂等）、登出删行并经 FK CASCADE 级联清光该账户全部账户级表数据。
     */
    @Provides
    @Singleton
    fun provideAccountLocalStore(db: AmperfyDatabase): AccountLocalStore =
        RoomAccountLocalStore(db)

    /**
     * SearchHistoryStore = Room 全量实现（P3 批次 5 换绑）。搜索历史读写全部走 Room。
     */
    @Provides
    @Singleton
    fun provideSearchHistoryStore(db: AmperfyDatabase): SearchHistoryStore =
        RoomSearchHistoryStore(db)

    /**
     * LibraryLocalStore = Room 全量实现（P3 批次 1b 换绑，逐批收缩委托：2b playlist 相关读、
     * 3b podcast/episode/radio 域、4b directory/musicFolder 域）。接口方法全部由 Room 承接，
     * P5 起构造只需 [AmperfyDatabase]。
     */
    @Provides
    @Singleton
    fun provideLibraryLocalStore(db: AmperfyDatabase): LibraryLocalStore =
        RoomLibraryLocalStore(db)
}
