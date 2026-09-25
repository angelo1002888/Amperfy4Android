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

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amperfy.data.local.db.mapper.toEntity
import com.amperfy.data.local.db.mapper.toPodcast
import com.amperfy.data.model.Podcast
import com.amperfy.data.model.PodcastEpisodeRemoteStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Podcast/Episode/Radio 域 DAO 契约测试（专题 15 P3 批次 3a）。
 *
 * 纯 DAO 层测试（Store 在 3b 才接线），逐条锁定 3a 新增查询/写的语义：
 * - PodcastDao：observeActive 过滤软删 + sort_key 序；markRemoteDeletedMissing 软标差集（含空
 *   keepIds 全标、跨账户隔离）；两步 upsert（insertIgnore + updateChannelMetadata）保留 episode_count；
 *   recalcEpisodeCount 排除 deleted 单集；observeActiveLimited LIMIT 20；行映射往返。
 * - PodcastEpisodeDao：observeAllOfActivePodcasts INNER JOIN 排除 podcast_id NULL / 软删频道单集，
 *   publish_date DESC；newestOfActivePodcasts LIMIT 20；episode FK 拒插未知频道、允许 NULL。
 * - RadioDao：deleteAllExcept 差集硬删（含空 keepIds 全删、跨账户隔离）。
 *
 * 播客级联删（父频道删 → 子单集 CASCADE）已由 PodcastEpisodeDaoTest.podcastCascade_deletesEpisodes
 * 覆盖，本类不重复。所有用例 runBlocking，时间戳/状态用字面量常量（红线：DAO 不取系统时钟）。
 */
@RunWith(AndroidJUnit4::class)
class RoomPodcastRadioDaoTest {

    private lateinit var db: AmperfyDatabase
    private val podcastDao by lazy { db.podcastDao() }
    private val episodeDao by lazy { db.podcastEpisodeDao() }
    private val radioDao by lazy { db.radioDao() }

    private val deletedRaw = PodcastEpisodeRemoteStatus.DELETED.raw
    private val newRaw = PodcastEpisodeRemoteStatus.NEW.raw

    @Before
    fun setUp() = runBlocking {
        db = DbTestFixture.openDatabase()
        DbTestFixture.seedScopes(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ==================== PodcastDao ====================

    @Test
    fun observeActive_filtersRemoteDeleted_andSortsBySortKey() = runBlocking {
        // charlie/alpha 存活、bravo 软删除（remote_status=1）
        podcastDao.upsertRemote(
            listOf(
                DbTestFixture.podcast(serverId = "c", title = "Charlie"),
                DbTestFixture.podcast(serverId = "a", title = "Alpha"),
                DbTestFixture.podcast(serverId = "b", title = "Bravo"),
            ),
        )
        podcastDao.markRemoteDeletedMissing(DbTestFixture.ACCT_A, listOf("c", "a"))

        val active = podcastDao.observeActive(DbTestFixture.ACCT_A).first().map { it.serverId }
        // 软删除的 bravo 不出现；存活项按 sort_key（Alpha < Charlie）排序
        assertEquals(listOf("a", "c"), active)
    }

    @Test
    fun markRemoteDeletedMissing_diffAndEmptyAndCrossAccount() = runBlocking {
        podcastDao.upsertRemote(
            listOf(
                DbTestFixture.podcast(serverId = "p1"),
                DbTestFixture.podcast(serverId = "p2"),
                DbTestFixture.podcast(serverId = "p3"),
                DbTestFixture.podcast(accountId = DbTestFixture.ACCT_B, serverId = "p1"),
            ),
        )

        // keepIds 外的 p3 标软删除，p1/p2 保留存活
        podcastDao.markRemoteDeletedMissing(DbTestFixture.ACCT_A, listOf("p1", "p2"))
        assertEquals(0, podcastDao.getByServerId(DbTestFixture.ACCT_A, "p1")!!.remoteStatus)
        assertEquals(0, podcastDao.getByServerId(DbTestFixture.ACCT_A, "p2")!!.remoteStatus)
        assertEquals(1, podcastDao.getByServerId(DbTestFixture.ACCT_A, "p3")!!.remoteStatus)
        // 跨账户隔离：acctB/p1 不受影响
        assertEquals(0, podcastDao.getByServerId(DbTestFixture.ACCT_B, "p1")!!.remoteStatus)

        // 空 keepIds：本账户全标软删除（NOT IN () 边界）
        podcastDao.markRemoteDeletedMissing(DbTestFixture.ACCT_A, emptyList())
        assertEquals(1, podcastDao.getByServerId(DbTestFixture.ACCT_A, "p1")!!.remoteStatus)
        assertEquals(1, podcastDao.getByServerId(DbTestFixture.ACCT_A, "p2")!!.remoteStatus)
        // 跨账户仍隔离
        assertEquals(0, podcastDao.getByServerId(DbTestFixture.ACCT_B, "p1")!!.remoteStatus)
    }

    @Test
    fun twoStepUpsert_preservesEpisodeCount() = runBlocking {
        // 建频道并让 episode_count 变成非零（造 2 存活单集后 recalc）
        podcastDao.upsertRemote(listOf(DbTestFixture.podcast(serverId = "pod1", title = "Old")))
        episodeDao.upsertRemote(
            listOf(
                DbTestFixture.episode(serverId = "e1", podcastId = "pod1", status = newRaw),
                DbTestFixture.episode(serverId = "e2", podcastId = "pod1", status = newRaw),
            ),
        )
        podcastDao.recalcEpisodeCount(DbTestFixture.ACCT_A, "pod1", deletedRaw)
        assertEquals(2, podcastDao.getByServerId(DbTestFixture.ACCT_A, "pod1")!!.episodeCount)

        // 两步 upsert：insertIgnore 对已存在频道 IGNORE（不重置 episode_count），
        // updateChannelMetadata 改元数据但不触碰 episode_count
        podcastDao.insertIgnore(listOf(DbTestFixture.podcast(serverId = "pod1", title = "New")))
        podcastDao.updateChannelMetadata(
            DbTestFixture.ACCT_A, "pod1",
            title = "New Title", description = "New Desc", coverArt = "cov",
            searchKey = "new title", sortKey = "13 New Title",
        )

        val updated = podcastDao.getByServerId(DbTestFixture.ACCT_A, "pod1")!!
        assertEquals("New Title", updated.title)
        assertEquals("New Desc", updated.description)
        // episode_count 未被两步 upsert 触碰，保持 2
        assertEquals(2, updated.episodeCount)
    }

    @Test
    fun recalcEpisodeCount_excludesDeleted() = runBlocking {
        podcastDao.upsertRemote(listOf(DbTestFixture.podcast(serverId = "pod1")))
        episodeDao.upsertRemote(
            listOf(
                DbTestFixture.episode(serverId = "e1", podcastId = "pod1", status = newRaw),
                DbTestFixture.episode(serverId = "e2", podcastId = "pod1", status = newRaw),
                DbTestFixture.episode(serverId = "e3", podcastId = "pod1", status = deletedRaw),
            ),
        )

        podcastDao.recalcEpisodeCount(DbTestFixture.ACCT_A, "pod1", deletedRaw)

        // 3 单集中 1 个 deleted，非删除计数 = 2
        assertEquals(2, podcastDao.getByServerId(DbTestFixture.ACCT_A, "pod1")!!.episodeCount)
    }

    @Test
    fun observeActiveLimited_capsAt20() = runBlocking {
        // 造 21 个存活频道，观察前 20
        podcastDao.upsertRemote(
            (0 until 21).map { DbTestFixture.podcast(serverId = "p%02d".format(it), title = "P%02d".format(it)) },
        )

        assertEquals(20, podcastDao.observeActiveLimited(DbTestFixture.ACCT_A).first().size)
    }

    @Test
    fun rowMapper_roundTripsPodcast() = runBlocking {
        // 经生产映射器 Podcast.toEntity 写入、PodcastEntity.toPodcast 读回，验证派生键与字段原子一致
        val domain = Podcast(id = "pod1", title = "语音节目", depiction = "描述", coverArt = "cov", episodeCount = 7)
        podcastDao.upsertRemote(listOf(domain.toEntity(DbTestFixture.ACCT_A)))

        val row = podcastDao.getByServerId(DbTestFixture.ACCT_A, "pod1")!!
        // search_key 由 title 派生（lowercase），保证「显示值与搜索键」不脱节
        assertEquals(com.amperfy.data.local.db.mapper.LibraryTextKeyNormalizer.searchKey("语音节目"), row.searchKey)
        assertEquals(0, row.remoteStatus)

        assertEquals(domain, row.toPodcast())
    }

    // ==================== PodcastEpisodeDao ====================

    @Test
    fun observeAllOfActivePodcasts_excludesNullAndDeletedChannels_orderedDesc() = runBlocking {
        // pod1 存活、pod2 软删除
        podcastDao.upsertRemote(
            listOf(
                DbTestFixture.podcast(serverId = "pod1"),
                DbTestFixture.podcast(serverId = "pod2"),
            ),
        )
        podcastDao.markRemoteDeletedMissing(DbTestFixture.ACCT_A, listOf("pod1"))
        episodeDao.upsertRemote(
            listOf(
                DbTestFixture.episode(serverId = "e1", podcastId = "pod1", publishDate = 100),
                DbTestFixture.episode(serverId = "e2", podcastId = "pod1", publishDate = 300),
                // 软删除频道 pod2 名下单集应被排除
                DbTestFixture.episode(serverId = "e3", podcastId = "pod2", publishDate = 400),
                // podcast_id 为 NULL 的孤儿单集应被 INNER JOIN 排除
                DbTestFixture.episode(serverId = "e4", podcastId = null, publishDate = 500),
            ),
        )

        val ids = episodeDao.observeAllOfActivePodcasts(DbTestFixture.ACCT_A).first().map { it.serverId }
        // 仅 pod1 存活单集，按 publish_date DESC（e2=300 先于 e1=100）
        assertEquals(listOf("e2", "e1"), ids)
    }

    @Test
    fun newestOfActivePodcasts_capsAt20() = runBlocking {
        podcastDao.upsertRemote(listOf(DbTestFixture.podcast(serverId = "pod1")))
        episodeDao.upsertRemote(
            (0 until 21).map {
                DbTestFixture.episode(serverId = "e%02d".format(it), podcastId = "pod1", publishDate = it.toLong())
            },
        )

        assertEquals(20, episodeDao.newestOfActivePodcasts(DbTestFixture.ACCT_A).first().size)
    }

    @Test
    fun episodeFk_rejectsUnknownChannel_allowsNull() = runBlocking {
        podcastDao.upsertRemote(listOf(DbTestFixture.podcast(serverId = "pod1")))

        // podcast_id 指向不存在频道 → 强 FK 拒插
        try {
            episodeDao.upsertRemote(
                listOf(DbTestFixture.episode(serverId = "bad", podcastId = "ghost", status = newRaw)),
            )
            fail("expected SQLiteConstraintException for episode referencing unknown podcast")
        } catch (e: SQLiteConstraintException) {
            // 预期：podcast(account_id, podcast_id→server_id) FK 违约
        }

        // podcast_id 为 NULL：复合 FK 含 NULL 不强制，可插入
        episodeDao.upsertRemote(
            listOf(DbTestFixture.episode(serverId = "orphan", podcastId = null, status = newRaw)),
        )
        assertNotNull(episodeDao.getByServerId(DbTestFixture.ACCT_A, "orphan"))
    }

    // ==================== RadioDao ====================

    @Test
    fun radioDeleteAllExcept_diffAndEmptyAndCrossAccount() = runBlocking {
        radioDao.upsertRemote(
            listOf(
                DbTestFixture.radio(serverId = "r1"),
                DbTestFixture.radio(serverId = "r2"),
                DbTestFixture.radio(serverId = "r3"),
                DbTestFixture.radio(accountId = DbTestFixture.ACCT_B, serverId = "r1"),
            ),
        )

        // 差集硬删除：keepIds 外的 r3 删除，r1/r2 保留
        radioDao.deleteAllExcept(DbTestFixture.ACCT_A, listOf("r1", "r2"))
        assertNotNull(radioDao.getByServerId(DbTestFixture.ACCT_A, "r1"))
        assertNotNull(radioDao.getByServerId(DbTestFixture.ACCT_A, "r2"))
        assertNull(radioDao.getByServerId(DbTestFixture.ACCT_A, "r3"))
        // 跨账户隔离：acctB/r1 不受影响
        assertNotNull(radioDao.getByServerId(DbTestFixture.ACCT_B, "r1"))

        // 空 keepIds：本账户全删（NOT IN () 边界）
        radioDao.deleteAllExcept(DbTestFixture.ACCT_A, emptyList())
        assertTrue(radioDao.observeAll(DbTestFixture.ACCT_A).first().isEmpty())
        // 跨账户仍隔离
        assertNotNull(radioDao.getByServerId(DbTestFixture.ACCT_B, "r1"))
    }
}
