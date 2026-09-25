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

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.amperfy.MainActivity
import com.amperfy.data.local.SettingsManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    @Inject
    lateinit var player: ExoPlayer

    @Inject
    lateinit var settingsManager: SettingsManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 上次推送的快捷方式状态键（"hasItem:isPlaying"），去重用 */
    private var lastShortcutKey: String? = null

    override fun onCreate() {
        super.onCreate()

        // Manual Playback（Settings→Player→Manual Playback）：曲目播完暂停、不自动切下一首
        // 对应 iOS AudioPlayer.didItemFinishedPlaying 中 !isPlaybackStartOnlyOnPlay 才 playNext()
        // （AudioPlayer.swift:134）；pauseAtEndOfMediaItems 是 ExoPlayer 专有 API，需在服务侧设置
        serviceScope.launch {
            settingsManager.isPlaybackStartOnlyOnPlay.collect { manualPlayback ->
                player.pauseAtEndOfMediaItems = manualPlayback
            }
        }

        val sessionActivityIntent = packageManager?.getLaunchIntentForPackage(packageName)
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        // 暂停/播放 Quick Action：监听 isPlaying 跃迁，
        // 播放中注册「Pause」快捷方式、暂停注册「Play」；仅有曲目时显示。
        // onIsPlayingChanged 天然只在状态跃迁时回调，规避 ShortcutManager rate limit。
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updatePlaybackShortcut(isPlaying)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // 曲目变化（含清空 → null）时同步快捷方式显隐
                updatePlaybackShortcut(player.isPlaying)
            }
        })
    }

    /**
     * 依当前播放状态维护单个动态快捷方式：
     * - 无当前曲目 → 移除快捷方式；
     * - 播放中 → 「Pause」；暂停 → 「Play」。
     * 快捷方式 Intent 用显式 ComponentName 指向 MainActivity（免注册 intent-filter），
     * action = [ACTION_TOGGLE_PLAYBACK]，由 MainActivity 经播放入口 play/pause。
     */
    private fun updatePlaybackShortcut(isPlaying: Boolean) {
        val hasItem = player.currentMediaItem != null
        // 去重：仅在（有无曲目 / 播放态）真正变化时才动快捷方式，进一步规避 rate limit
        val key = "$hasItem:$isPlaying"
        if (key == lastShortcutKey) return
        lastShortcutKey = key
        if (!hasItem) {
            ShortcutManagerCompat.removeDynamicShortcuts(this, listOf(SHORTCUT_ID_TOGGLE))
            return
        }
        val label = if (isPlaying) "Pause" else "Play"
        val iconRes = if (isPlaying) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        val intent = Intent(ACTION_TOGGLE_PLAYBACK).apply {
            component = ComponentName(this@PlaybackService, MainActivity::class.java)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val shortcut = ShortcutInfoCompat.Builder(this, SHORTCUT_ID_TOGGLE)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(this, iconRes))
            .setIntent(intent)
            .build()
        try {
            ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
        } catch (e: Exception) {
            // rate limit 等异常吞掉，不影响播放（下次跃迁再试）
            android.util.Log.w("PlaybackService", "pushDynamicShortcut failed", e)
        }
    }

    companion object {
        /** 暂停/播放 Quick Action 的 Intent action（MainActivity 消费） */
        const val ACTION_TOGGLE_PLAYBACK = "com.amperfy.TOGGLE_PLAYBACK"
        private const val SHORTCUT_ID_TOGGLE = "toggle_playback"
    }
    
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }
    
    override fun onDestroy() {
        serviceScope.cancel()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
