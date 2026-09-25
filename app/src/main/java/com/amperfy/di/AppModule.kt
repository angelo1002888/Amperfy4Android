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

package com.amperfy.di

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import com.amperfy.data.local.*
import com.amperfy.data.remote.DetailedLoggingInterceptor
import com.amperfy.data.remote.SubsonicApi
import com.amperfy.data.repository.MusicRepository
import com.amperfy.data.repository.MusicRepositoryImpl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * 全局共享 OkHttpClient（W5）：连接池/超时/日志全局共享。
     * 账户级 base URL 重写不在此——每账户 [com.amperfy.core.AccountComponentsRegistry]
     * newBuilder() 挂 [com.amperfy.data.remote.AccountBaseUrlInterceptor]（全局 DynamicBaseUrlInterceptor 已退役）。
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(
        detailedLoggingInterceptor: DetailedLoggingInterceptor
    ): OkHttpClient {
        return OkHttpClient.Builder()
            // 详细日志拦截器 - 记录完整的请求URL和响应内容
            .addInterceptor(detailedLoggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
    
    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient): Retrofit {
        // Note: baseUrl 仅为占位符；实际 scheme/host/port 由每账户的 AccountBaseUrlInterceptor
        // 每请求重写（W5 起，AccountComponentsRegistry 为各账户挂载该拦截器）
        return Retrofit.Builder()
            .baseUrl("http://placeholder.example.com/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }
    
    @Provides
    @Singleton
    fun provideSubsonicApi(retrofit: Retrofit): SubsonicApi {
        return retrofit.create(SubsonicApi::class.java)
    }

    /**
     * compat（未登录兜底）MusicRepository——**恒为 Subsonic 栈**的 active 动态实例。
     *
     * 唯一用途：还没有 active 账户时 [com.amperfy.core.AppDelegate.music] 的回退值
     * （登录页/初始同步前的 URL 构建等）。已登录场景一律走
     * [com.amperfy.core.AccountComponentsRegistry] 的每账户实例——那里才按 backendApi
     * 分派 Subsonic / Ampache 实现族（Ampache 移植 Batch 2）。
     */
    @Provides
    @Singleton
    fun provideMusicRepository(
        @ApplicationContext context: Context,
        subsonicApi: SubsonicApi,
        credentialsManager: CredentialsManager,
        eventLogger: com.amperfy.core.EventLogger,
        settingsManager: com.amperfy.data.local.SettingsManager,
        networkMonitor: com.amperfy.core.NetworkMonitor,
        searchHistoryStore: com.amperfy.data.local.store.SearchHistoryStore,
        libraryLocalStore: com.amperfy.data.local.store.LibraryLocalStore,
        playlistLocalStore: com.amperfy.data.local.store.PlaylistLocalStore
    ): MusicRepository {
        return MusicRepositoryImpl(
            subsonicApi, credentialsManager, eventLogger, settingsManager, networkMonitor,
            // filesDir：歌词落盘缓存根（Library 域 accounts/<sh>/<uh>/lyrics/songs/）
            searchHistoryStore, libraryLocalStore, playlistLocalStore, context.filesDir
        )
    }

    /**
     * 自定义音频链 ExoPlayer（W2）
     *
     * 处理顺序对齐 iOS 节点链：EQ → Gain(ReplayGain×EQ补偿) → VisualizerTap
     * （tap 在末端，可视化反映均衡+增益后的最终信号）。
     *
     * **冻结约束**：本项目不得启用 audio offload / 直通输出——
     * 自定义 AudioProcessor 三件套依赖 PCM 处理路径，offload 会让整条链失效。
     * buildAudioSink 的三参签名对应 media3 1.5.0（offload 参数已在 1.1.0 移除），
     * 透传 enableFloatOutput/enableAudioTrackPlaybackParams 保持工厂默认行为。
     */
    @Provides
    @Singleton
    @androidx.annotation.OptIn(UnstableApi::class)
    fun provideExoPlayer(
        @ApplicationContext context: Context,
        equalizerProcessor: com.amperfy.player.audio.EqualizerProcessor,
        gainProcessor: com.amperfy.player.audio.GainProcessor,
        visualizerTapProcessor: com.amperfy.player.audio.VisualizerTapProcessor,
        ampacheUrlAuthRefresher: com.amperfy.core.AmpacheUrlAuthRefresher
    ): ExoPlayer {
        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(
                        arrayOf(equalizerProcessor, gainProcessor, visualizerTapProcessor)
                    )
                    .build()
            }
        }
        // 开启「恒定码率始终 seek」：转码流（Subsonic stream 端点 format=mp3，默认设置）
        // 为 chunked 传输、无 Content-Length，Mp3Extractor 建不出 seek 表 → 媒体被判不可 seek
        // → MediaController 因 COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM 不可用而静默丢弃 seekTo →
        // 进度条拖动后弹回。开启后 ExoPlayer 按恒定码率估算 seek 位置（对齐 AVPlayer 对
        // chunked mp3 的内建 CBR 估算）。seek 到未缓冲区时底层 DataSource 会带 Range 头重连；
        // 服务器不支持 Range 时退化为从头跳字节读，seek 仍可用只是代价更高。
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingAlwaysEnabled(true)
        // Ampache 会话 token 保鲜（Ampache 移植 Batch 2）：媒体 URL 在**装载时刻**把 auth
        // 换成新鲜 token（会话过期前 5 分钟要重握手，而 getStreamUrl 是非 suspend 的冻结签名，
        // 拼 URL 时只能用内存里现成的 token）。Resolver 回调运行在 loader 线程，
        // 故用 withFreshAuthBlocking；非 Ampache URL（Subsonic/本地文件/电台直连）原样放行，
        // 判定只看路径（server/xml.server.php 或 image.php），零额外开销。
        val dataSourceFactory = ResolvingDataSource.Factory(
            DefaultDataSource.Factory(context),
            ResolvingDataSource.Resolver { dataSpec ->
                val url = dataSpec.uri.toString()
                if (ampacheUrlAuthRefresher.isAmpacheAuthUrl(url)) {
                    dataSpec.withUri(Uri.parse(ampacheUrlAuthRefresher.withFreshAuthBlocking(url)))
                } else {
                    dataSpec
                }
            }
        )
        return ExoPlayer.Builder(context, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory))
            .build()
    }
}
