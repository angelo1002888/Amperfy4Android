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

package com.amperfy.player

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.amperfy.data.local.CredentialsManager
import com.amperfy.data.local.SettingsManager
import com.amperfy.data.model.LoginCredentials
import com.amperfy.data.model.Playable
import com.amperfy.data.local.store.PlaybackStateStore
import com.amperfy.data.repository.LibraryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * ScrobbleSyncer「听够」状态机单测（W4）
 *
 * 纯逻辑测试：时间全部虚拟——
 * - 状态机时钟经 [ScrobbleSyncer.clock] 注入 TestCoroutineScheduler.currentTime；
 * - 定时器（delay）由 Dispatchers.setMain(StandardTestDispatcher) 走同一虚拟时间轴。
 * 网络/存储/SharedPreferences 依赖以 mockk 替身注入；提交行为经
 * library.scrobble(...) 的调用次数断言（Ampache 移植 Batch 2 起协议动作走
 * LibraryRepository 分派，本类不再直接持 SubsonicApi）
 *
 * 注意 advanceTimeBy 语义：只执行「早于」目标时刻的任务，恰在目标时刻排定的任务
 * 需再 runCurrent()——测试用例利用这一点模拟「定时器取消与到点竞争」
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScrobbleSyncerTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var library: LibraryRepository
    private lateinit var syncer: ScrobbleSyncer

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // JVM 单测无 Android 框架：静态 mock Log，避免 "not mocked" 异常
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        // SharedPreferences：离线重传队列恒为空（本测试不覆盖离线队列，保留现状逻辑）
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val prefs = mockk<SharedPreferences>()
        every { prefs.getString(any(), any()) } returns null
        every { prefs.edit() } returns editor
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns prefs

        // 在线 + 凭证可用 + scrobble 上报成功：提交路径直达 library.scrobble
        library = mockk()
        coEvery { library.scrobble(any(), any()) } returns Result.success(Unit)
        coEvery { library.reportNowPlaying(any()) } returns Result.success(Unit)

        // W5：ScrobbleSyncer 绑定账户，凭证经 per-ident getCredentials(ident) 读取
        val accountInfo = com.amperfy.data.model.AccountInfo.create("https://example", "u")
        val credentialsManager = mockk<CredentialsManager>()
        every { credentialsManager.getCredentials() } returns LoginCredentials(
            serverUrl = "https://example", username = "u", password = "p", passwordHash = ""
        )
        every { credentialsManager.getCredentials(any()) } returns LoginCredentials(
            serverUrl = "https://example", username = "u", password = "p", passwordHash = ""
        )

        val settingsManager = mockk<SettingsManager>()
        every { settingsManager.isOfflineMode } returns MutableStateFlow(false)

        // W5：isScrobbleStreamedItems 改读账户层——账户设置开启使流播测试曲可达标
        val accountSettingsStore = mockk<com.amperfy.data.local.AccountSettingsStore>()
        every { accountSettingsStore.settings(any()) } returns
            MutableStateFlow(com.amperfy.data.local.AccountSetting(isScrobbleStreamedItems = true))

        // playCount++ 的 incrementPlayCount 与判定逻辑无关（且被 try/catch 包裹），relaxed 即可
        val playbackStateStore = mockk<PlaybackStateStore>(relaxed = true)

        syncer = ScrobbleSyncer(
            context, library, credentialsManager, settingsManager, playbackStateStore,
            accountSettingsStore, accountInfo
        )
        // 时钟注入：与定时器共用同一虚拟时间轴
        syncer.clock = { testDispatcher.scheduler.currentTime }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    /** 构造流播测试曲目（duration 单位：秒） */
    private fun song(id: String, durationSec: Int) = Playable(
        id = id,
        title = "title-$id",
        artist = "artist",
        album = "album",
        duration = durationSec,
        coverArt = null,
        streamUrl = "https://example/stream/$id"
    )

    /**
     * 用例 1：暂停累计——播 30% 暂停很久，再播到阈值 → 恰好提交一次。
     * 100s 曲目阈值 = min(50s, 240s) = 50s；30s + (暂停 10 分钟不计) + 20s = 达标
     */
    @Test
    fun pauseAccumulation_resumeToThreshold_submitsExactlyOnce() = runTest(testDispatcher) {
        syncer.onSongStarted(song("s1", durationSec = 100))
        advanceTimeBy(30_000) // 播 30%
        syncer.onPlaybackPaused()
        advanceTimeBy(600_000) // 暂停 10 分钟：不计入累计
        syncer.onPlaybackResumed() // 以剩余 20s 重启定时器
        advanceTimeBy(20_000)
        runCurrent() // 执行恰在到点时刻的标记任务
        syncer.onPlaybackStopped() // 结算提交
        advanceUntilIdle()

        coVerify(exactly = 1) {
            library.scrobble("s1", any())
        }
    }

    /**
     * 用例 1 补充：暂停必须取消定时器——暂停期间「到点」不得误标记。
     * 旧实现（一次性墙钟定时器）在暂停 10 分钟期间即误达标，本用例回归防护
     */
    @Test
    fun pauseCancelsTimer_noSubmissionIfStoppedBeforeThreshold() = runTest(testDispatcher) {
        syncer.onSongStarted(song("s5", durationSec = 100)) // 阈值 50s
        advanceTimeBy(30_000)
        syncer.onPlaybackPaused()
        advanceTimeBy(600_000) // 若定时器未被取消，此处会误标记
        syncer.onPlaybackResumed()
        advanceTimeBy(10_000) // 累计 40s < 50s，未达标
        runCurrent()
        syncer.onPlaybackStopped()
        advanceUntilIdle()

        coVerify(exactly = 0) {
            library.scrobble(any(), any())
        }
    }

    /**
     * 用例 2：跨暂停达标——resume 时 accumulated 已 >= threshold，立即标记。
     * 恰在阈值时刻（t=50s）暂停：advanceTimeBy 不执行恰等于目标时刻的定时任务，
     * 暂停先取消定时器——模拟「定时器取消与到点竞争」，resume 的立即标记兜底生效
     */
    @Test
    fun resumeWithAccumulatedAtThreshold_marksImmediately() = runTest(testDispatcher) {
        syncer.onSongStarted(song("s2", durationSec = 100)) // 阈值 50s
        advanceTimeBy(50_000) // 定时任务排在 t=50s，尚未执行
        syncer.onPlaybackPaused() // accumulated = 50s >= 阈值，但尚未标记
        advanceTimeBy(60_000)
        syncer.onPlaybackResumed() // 立即标记 listenedEnough，不再依赖定时器
        syncer.onPlaybackStopped()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            library.scrobble("s2", any())
        }
    }

    /**
     * 用例 3：切歌重置——前曲未达标不提交，且累计不带入新曲。
     * A 播 20s（未达标）切到 B 播 30s：若 A 的 20s 泄漏进 B，20+30=50s 会误达标
     */
    @Test
    fun songChange_noSubmissionForUnfinished_andNoStateCarryOver() = runTest(testDispatcher) {
        syncer.onSongStarted(song("a", durationSec = 100)) // 阈值 50s
        advanceTimeBy(20_000) // A 未达标
        syncer.onSongStarted(song("b", durationSec = 100)) // 切歌：结算 A（不提交）+ 状态清零
        advanceTimeBy(30_000) // B 只播 30s；B 自身定时器在 50s 后才到点
        runCurrent()
        syncer.onPlaybackStopped()
        advanceUntilIdle()

        coVerify(exactly = 0) {
            library.scrobble(any(), any())
        }
    }

    /**
     * 用例 4：240s 封顶——600s 长曲 duration/2 = 300s > 240s，以 240s 达标。
     * 在 239s 暂停（未达标）后恢复：剩余定时器应为 1s（阈值若未封顶则剩余为 61s，
     * 1s 后不会达标、不会提交——以此证明封顶生效）
     */
    @Test
    fun longSong_thresholdCappedAt240Seconds() = runTest(testDispatcher) {
        syncer.onSongStarted(song("s4", durationSec = 600))
        advanceTimeBy(239_000)
        syncer.onPlaybackPaused() // 累计 239s < 240s
        syncer.onPlaybackResumed() // 剩余 1s 重启定时器
        advanceTimeBy(1_000)
        runCurrent()
        syncer.onPlaybackStopped()
        advanceUntilIdle()

        coVerify(exactly = 1) {
            library.scrobble("s4", any())
        }
    }
}
