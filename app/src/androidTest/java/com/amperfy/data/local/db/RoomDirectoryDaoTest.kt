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

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.amperfy.data.local.db.mapper.LibraryTextKeyNormalizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MusicFolder/Directory/song_directory 域 DAO 契约测试（专题 15 P3 批次 4a）。
 *
 * 纯 DAO 层测试（Store 在 4b 才接线），逐条锁定 4a 新增查询/写的语义：
 * - DirectoryDao 两组两步 upsert：顶层路径写不清 parent_id、子目录路径写不清 music_folder_id
 *   （@Upsert 整行覆盖会互相抹掉挂载列，本批关键忠实性点）；
 * - 三个差集/级联删除：deleteByMusicFolderIds（replaceMusicFolders 级联清顶层目录）、
 *   deleteInFolderExcept、deleteChildrenExcept（含空 keepIds 全删边界与作用域限定）；
 * - MusicFolderDao：observeAll 按 **server_id** 排序 +
 *   getServerIdsExcept/deleteAllExcept 差集；
 * - SongDirectoryDao.observeSongsWithLocalIn：JOIN 带出 cache_path/play_count、按 track 升序
 *   （null 在前）、未关联歌曲不出现；clearForSongs 只断关系不删歌曲实体；
 * - 跨账户隔离（全部方法带 account_id）。
 *
 * 所有用例 runBlocking，派生键统一经 [LibraryTextKeyNormalizer]（单一规则源，不在测试里手拼）。
 */
@RunWith(AndroidJUnit4::class)
class RoomDirectoryDaoTest {

    private lateinit var db: AmperfyDatabase
    private val musicFolderDao by lazy { db.musicFolderDao() }
    private val directoryDao by lazy { db.directoryDao() }
    private val songDirectoryDao by lazy { db.songDirectoryDao() }
    private val songDao by lazy { db.songDao() }
    private val songLocalStateDao by lazy { db.songLocalStateDao() }

    @Before
    fun setUp() = runBlocking {
        db = DbTestFixture.openDatabase()
        DbTestFixture.seedScopes(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ==================== DirectoryDao 两步 upsert ====================

    @Test
    fun twoStepUpsert_topLevel_keepsParentId() = runBlocking {
        // 既有行是「某父目录的子目录」（parent_id 已挂载，music_folder_id 为空）
        directoryDao.upsertRemote(
            listOf(DbTestFixture.directory(serverId = "d1", name = "Old", parentId = "parent1")),
        )

        // 顶层目录路径：insertIgnoreAll 对已存在行 IGNORE（不整行覆盖），再定向 UPDATE 只写自己那组列
        directoryDao.insertIgnoreAll(
            listOf(DbTestFixture.directory(serverId = "d1", name = "New", musicFolderId = "mf1")),
        )
        directoryDao.updateTopLevelMetadata(
            DbTestFixture.ACCT_A, "d1",
            name = "New", coverArt = "cov1", musicFolderId = "mf1",
            sortKey = LibraryTextKeyNormalizer.sortKey("New"),
        )

        val row = directoryDao.getByServerId(DbTestFixture.ACCT_A, "d1")!!
        assertEquals("New", row.name)
        assertEquals("cov1", row.coverArt)
        assertEquals("mf1", row.musicFolderId)
        // 关键：另一组挂载列 parent_id 未被触碰
        assertEquals("parent1", row.parentId)
        assertEquals(LibraryTextKeyNormalizer.sortKey("New"), row.sortKey)

        // 行不存在时 insertIgnoreAll 正常建新行（新行只带本路径挂载列）
        directoryDao.insertIgnoreAll(
            listOf(DbTestFixture.directory(serverId = "d2", name = "Fresh", musicFolderId = "mf1")),
        )
        val fresh = directoryDao.getByServerId(DbTestFixture.ACCT_A, "d2")!!
        assertEquals("mf1", fresh.musicFolderId)
        assertNull(fresh.parentId)
    }

    @Test
    fun twoStepUpsert_child_keepsMusicFolderId() = runBlocking {
        // 既有行是「某音乐文件夹的顶层目录」（music_folder_id 已挂载，parent_id 为空）
        directoryDao.upsertRemote(
            listOf(DbTestFixture.directory(serverId = "d1", name = "Old", musicFolderId = "mf1")),
        )

        directoryDao.insertIgnoreAll(
            listOf(DbTestFixture.directory(serverId = "d1", name = "New", parentId = "parent1")),
        )
        directoryDao.updateChildMetadata(
            DbTestFixture.ACCT_A, "d1",
            name = "New", coverArt = "cov1", parentId = "parent1",
            sortKey = LibraryTextKeyNormalizer.sortKey("New"),
        )

        val row = directoryDao.getByServerId(DbTestFixture.ACCT_A, "d1")!!
        assertEquals("New", row.name)
        assertEquals("cov1", row.coverArt)
        assertEquals("parent1", row.parentId)
        // 关键：另一组挂载列 music_folder_id 未被触碰
        assertEquals("mf1", row.musicFolderId)
        assertEquals(LibraryTextKeyNormalizer.sortKey("New"), row.sortKey)
    }

    // ==================== DirectoryDao 差集/级联删除 ====================

    @Test
    fun deleteByMusicFolderIds_removesOnlyThatFolderTopDirs() = runBlocking {
        directoryDao.upsertRemote(
            listOf(
                DbTestFixture.directory(serverId = "t1", musicFolderId = "mf1"),
                DbTestFixture.directory(serverId = "t2", musicFolderId = "mf1"),
                DbTestFixture.directory(serverId = "t3", musicFolderId = "mf2"),
                // 深层子目录（无 music_folder_id）不受影响
                DbTestFixture.directory(serverId = "c1", parentId = "t1"),
            ),
        )

        directoryDao.deleteByMusicFolderIds(DbTestFixture.ACCT_A, listOf("mf1"))

        assertNull(directoryDao.getByServerId(DbTestFixture.ACCT_A, "t1"))
        assertNull(directoryDao.getByServerId(DbTestFixture.ACCT_A, "t2"))
        assertNotNull(directoryDao.getByServerId(DbTestFixture.ACCT_A, "t3"))
        assertNotNull(directoryDao.getByServerId(DbTestFixture.ACCT_A, "c1"))
    }

    @Test
    fun deleteInFolderExcept_diffAndEmptyKeepIds() = runBlocking {
        directoryDao.upsertRemote(
            listOf(
                DbTestFixture.directory(serverId = "t1", musicFolderId = "mf1"),
                DbTestFixture.directory(serverId = "t2", musicFolderId = "mf1"),
                DbTestFixture.directory(serverId = "t3", musicFolderId = "mf1"),
                DbTestFixture.directory(serverId = "o1", musicFolderId = "mf2"),
            ),
        )

        // 差集：mf1 下 keepIds 外的 t3 删除，t1/t2 保留，别的文件夹不受影响
        directoryDao.deleteInFolderExcept(DbTestFixture.ACCT_A, "mf1", listOf("t1", "t2"))
        assertNotNull(directoryDao.getByServerId(DbTestFixture.ACCT_A, "t1"))
        assertNotNull(directoryDao.getByServerId(DbTestFixture.ACCT_A, "t2"))
        assertNull(directoryDao.getByServerId(DbTestFixture.ACCT_A, "t3"))
        assertNotNull(directoryDao.getByServerId(DbTestFixture.ACCT_A, "o1"))

        // 空 keepIds：mf1 下全删（NOT IN () 边界），mf2 仍不受影响
        directoryDao.deleteInFolderExcept(DbTestFixture.ACCT_A, "mf1", emptyList())
        assertTrue(directoryDao.observeByMusicFolder(DbTestFixture.ACCT_A, "mf1").first().isEmpty())
        assertNotNull(directoryDao.getByServerId(DbTestFixture.ACCT_A, "o1"))
    }

    @Test
    fun deleteChildrenExcept_scopedToParent() = runBlocking {
        directoryDao.upsertRemote(
            listOf(
                DbTestFixture.directory(serverId = "c1", parentId = "p1"),
                DbTestFixture.directory(serverId = "c2", parentId = "p1"),
                DbTestFixture.directory(serverId = "c3", parentId = "p1"),
                // 另一父目录下的同名差集不应被波及
                DbTestFixture.directory(serverId = "x1", parentId = "p2"),
                // 顶层目录（parent_id 为 null）不在范围内
                DbTestFixture.directory(serverId = "t1", musicFolderId = "mf1"),
            ),
        )

        directoryDao.deleteChildrenExcept(DbTestFixture.ACCT_A, "p1", listOf("c1"))

        assertNotNull(directoryDao.getByServerId(DbTestFixture.ACCT_A, "c1"))
        assertNull(directoryDao.getByServerId(DbTestFixture.ACCT_A, "c2"))
        assertNull(directoryDao.getByServerId(DbTestFixture.ACCT_A, "c3"))
        assertNotNull(directoryDao.getByServerId(DbTestFixture.ACCT_A, "x1"))
        assertNotNull(directoryDao.getByServerId(DbTestFixture.ACCT_A, "t1"))

        // 空 keepIds：p1 下子目录全删，p2 仍在
        directoryDao.deleteChildrenExcept(DbTestFixture.ACCT_A, "p1", emptyList())
        assertTrue(directoryDao.observeChildren(DbTestFixture.ACCT_A, "p1").first().isEmpty())
        assertNotNull(directoryDao.getByServerId(DbTestFixture.ACCT_A, "x1"))
    }

    // ==================== MusicFolderDao ====================

    @Test
    fun musicFolders_orderedByServerId_andDiffDelete() = runBlocking {
        // 名称序与 id 序刻意相反：断言排序确实按 server_id 而非 name
        musicFolderDao.upsertRemote(
            listOf(
                DbTestFixture.musicFolder(serverId = "3", name = "Alpha"),
                DbTestFixture.musicFolder(serverId = "1", name = "Zulu"),
                DbTestFixture.musicFolder(serverId = "2", name = "Mike"),
                DbTestFixture.musicFolder(accountId = DbTestFixture.ACCT_B, serverId = "1", name = "Other"),
            ),
        )

        val ids = musicFolderDao.observeAll(DbTestFixture.ACCT_A).first().map { it.serverId }
        assertEquals(listOf("1", "2", "3"), ids)

        // 单个文件夹观察
        assertEquals("Mike", musicFolderDao.observeByServerId(DbTestFixture.ACCT_A, "2").first()!!.name)

        // 差集：keepIds 外的 id 集合（供 Store 先级联清目录）
        assertEquals(
            listOf("3"),
            musicFolderDao.getServerIdsExcept(DbTestFixture.ACCT_A, listOf("1", "2")).sorted(),
        )
        musicFolderDao.deleteAllExcept(DbTestFixture.ACCT_A, listOf("1", "2"))
        assertEquals(listOf("1", "2"), musicFolderDao.observeAll(DbTestFixture.ACCT_A).first().map { it.serverId })
        // 跨账户隔离：acctB/1 不受影响
        assertNotNull(musicFolderDao.getByServerId(DbTestFixture.ACCT_B, "1"))

        // 空 keepIds：本账户全删（NOT IN () 边界）
        musicFolderDao.deleteAllExcept(DbTestFixture.ACCT_A, emptyList())
        assertTrue(musicFolderDao.observeAll(DbTestFixture.ACCT_A).first().isEmpty())
        assertNotNull(musicFolderDao.getByServerId(DbTestFixture.ACCT_B, "1"))
    }

    // ==================== SongDirectoryDao ====================

    @Test
    fun observeSongsWithLocalIn_joinsLocalState_ordersByTrack() = runBlocking {
        songDao.upsertRemote(
            listOf(
                DbTestFixture.song(serverId = "s1", title = "Track 2").copy(track = 2),
                DbTestFixture.song(serverId = "s2", title = "Track 1").copy(track = 1),
                // track 为 null：升序排最前（SQLite ORDER BY 语义）
                DbTestFixture.song(serverId = "s3", title = "No Track").copy(track = null),
                // 未关联到任何目录的歌曲不应出现
                DbTestFixture.song(serverId = "s4", title = "Unrelated").copy(track = 0),
            ),
        )
        songDirectoryDao.setDirectory(DbTestFixture.ACCT_A, "s1", "dir1")
        songDirectoryDao.setDirectory(DbTestFixture.ACCT_A, "s2", "dir1")
        songDirectoryDao.setDirectory(DbTestFixture.ACCT_A, "s3", "dir1")
        // 本地状态：s1 已缓存 + 播放 2 次；s2/s3 无本地状态行（走 LEFT JOIN 兜底）
        songLocalStateDao.setCachePath(DbTestFixture.ACCT_A, "s1", "cache/s1.mp3")
        songLocalStateDao.incrementPlayCount(DbTestFixture.ACCT_A, "s1")
        songLocalStateDao.incrementPlayCount(DbTestFixture.ACCT_A, "s1")

        val rows = songDirectoryDao.observeSongsWithLocalIn(DbTestFixture.ACCT_A, "dir1").first()

        // null track 在前，其后按 track 升序；未关联的 s4 不在结果内
        assertEquals(listOf("s3", "s2", "s1"), rows.map { it.song.serverId })
        val s1 = rows.first { it.song.serverId == "s1" }
        assertEquals("cache/s1.mp3", s1.cachePath)
        assertEquals(2, s1.playCount)
        val s2 = rows.first { it.song.serverId == "s2" }
        // 本地状态缺行：cache_path 为 null、play_count 由 COALESCE 兜底 0
        assertNull(s2.cachePath)
        assertEquals(0, s2.playCount)
    }

    @Test
    fun clearForSongs_dropsRelationButKeepsSongRow() = runBlocking {
        songDao.upsertRemote(
            listOf(
                DbTestFixture.song(serverId = "s1").copy(track = 1),
                DbTestFixture.song(serverId = "s2").copy(track = 2),
            ),
        )
        songDirectoryDao.setDirectory(DbTestFixture.ACCT_A, "s1", "dir1")
        songDirectoryDao.setDirectory(DbTestFixture.ACCT_A, "s2", "dir1")

        // 目录差集清理：消失的歌曲仅解除关联
        songDirectoryDao.clearForSongs(DbTestFixture.ACCT_A, listOf("s1"))

        val ids = songDirectoryDao.observeSongsWithLocalIn(DbTestFixture.ACCT_A, "dir1").first()
            .map { it.song.serverId }
        assertEquals(listOf("s2"), ids)
        // 歌曲实体仍在库中（不随关系解除被删）
        assertNotNull(songDao.getByServerId(DbTestFixture.ACCT_A, "s1"))
    }

    // ==================== 跨账户隔离 ====================

    @Test
    fun crossAccountIsolation_directoriesAndRelations() = runBlocking {
        directoryDao.upsertRemote(
            listOf(
                DbTestFixture.directory(serverId = "d1", name = "A dir", musicFolderId = "mf1"),
                DbTestFixture.directory(
                    accountId = DbTestFixture.ACCT_B,
                    serverId = "d1",
                    name = "B dir",
                    musicFolderId = "mf1",
                ),
                DbTestFixture.directory(serverId = "c1", parentId = "d1"),
                DbTestFixture.directory(accountId = DbTestFixture.ACCT_B, serverId = "c1", parentId = "d1"),
            ),
        )
        songDao.upsertRemote(
            listOf(
                DbTestFixture.song(serverId = "s1", title = "A song").copy(track = 1),
                DbTestFixture.song(accountId = DbTestFixture.ACCT_B, serverId = "s1", title = "B song").copy(track = 1),
            ),
        )
        songDirectoryDao.setDirectory(DbTestFixture.ACCT_A, "s1", "d1")
        songDirectoryDao.setDirectory(DbTestFixture.ACCT_B, "s1", "d1")

        // 读侧按账户切分：同 server_id 的两行互不串台
        assertEquals("A dir", directoryDao.observeByServerId(DbTestFixture.ACCT_A, "d1").first()!!.name)
        assertEquals("B dir", directoryDao.observeByServerId(DbTestFixture.ACCT_B, "d1").first()!!.name)
        assertEquals(
            "A song",
            songDirectoryDao.observeSongsWithLocalIn(DbTestFixture.ACCT_A, "d1").first().single().song.title,
        )
        assertEquals(
            "B song",
            songDirectoryDao.observeSongsWithLocalIn(DbTestFixture.ACCT_B, "d1").first().single().song.title,
        )

        // 写侧：A 账户的差集删除/关系清理不波及 B
        directoryDao.deleteInFolderExcept(DbTestFixture.ACCT_A, "mf1", emptyList())
        directoryDao.deleteChildrenExcept(DbTestFixture.ACCT_A, "d1", emptyList())
        songDirectoryDao.clearForSongs(DbTestFixture.ACCT_A, listOf("s1"))

        assertNull(directoryDao.getByServerId(DbTestFixture.ACCT_A, "d1"))
        assertNull(directoryDao.getByServerId(DbTestFixture.ACCT_A, "c1"))
        assertTrue(songDirectoryDao.observeSongsWithLocalIn(DbTestFixture.ACCT_A, "d1").first().isEmpty())
        assertNotNull(directoryDao.getByServerId(DbTestFixture.ACCT_B, "d1"))
        assertNotNull(directoryDao.getByServerId(DbTestFixture.ACCT_B, "c1"))
        assertEquals(1, songDirectoryDao.observeSongsWithLocalIn(DbTestFixture.ACCT_B, "d1").first().size)
    }
}
