package dev.frost819.newbv.app.viewmodel.player

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.frost819.newbv.app.data.VideoInfoRepository
import dev.frost819.newbv.app.entity.player.VideoAspectRatio
import dev.frost819.newbv.app.entity.player.VideoListItem
import dev.frost819.newbv.app.ui.action.player.MediaProfileSettingAction
import dev.frost819.newbv.app.ui.component.settings.displayName
import dev.frost819.newbv.app.ui.state.player.MediaProfileState
import dev.frost819.newbv.app.ui.state.player.PlayerState
import dev.frost819.newbv.app.ui.state.player.PlayerUiEffect
import dev.frost819.newbv.app.ui.state.player.PlayerUiState
import dev.frost819.newbv.app.ui.state.player.SeekerState
import dev.frost819.newbv.app.util.PlaybackCandidate
import dev.frost819.newbv.app.util.PlayerConstants
import dev.frost819.newbv.app.util.VideoCapabilityProvider
import dev.frost819.newbv.app.util.VideoDecodeProfile
import dev.frost819.newbv.app.util.cdnHostOf
import dev.frost819.newbv.app.util.cdnLineLabel
import dev.frost819.newbv.app.util.collectCodecs
import dev.frost819.newbv.app.util.findTrack
import dev.frost819.newbv.app.util.isOfficialCdnUrl
import dev.frost819.newbv.app.util.orderQualities
import dev.frost819.newbv.app.util.pickDecodableProfile
import dev.frost819.newbv.app.util.playUrlDeadlineSeconds
import dev.frost819.newbv.app.util.toPlaybackLoadErrorMessage
import dev.frost819.newbv.app.util.trackMatchesCodec
import dev.frost819.newbv.biliapi.entity.ApiType
import dev.frost819.newbv.biliapi.entity.DashVideo
import dev.frost819.newbv.biliapi.entity.PlayData
import dev.frost819.newbv.biliapi.entity.video.HeartbeatVideoType
import dev.frost819.newbv.biliapi.entity.video.VideoPage
import dev.frost819.newbv.biliapi.repositories.AuthRepository
import dev.frost819.newbv.biliapi.repositories.CoinRepository
import dev.frost819.newbv.biliapi.repositories.FavoriteRepository
import dev.frost819.newbv.biliapi.repositories.LikeRepository
import dev.frost819.newbv.biliapi.repositories.OneClickTripleActionRepository
import dev.frost819.newbv.biliapi.repositories.VideoPlayRepository
import dev.frost819.newbv.core.log.Loggers
import dev.frost819.newbv.data.datastore.Audio
import dev.frost819.newbv.data.datastore.Prefs
import dev.frost819.newbv.data.datastore.Resolution
import dev.frost819.newbv.data.datastore.VideoCodec
import dev.frost819.newbv.player.AbstractVideoPlayer
import dev.frost819.newbv.player.CdnSelector
import dev.frost819.newbv.player.VideoPlayerListener
import dev.frost819.newbv.player.VideoPlayerOptions
import dev.frost819.newbv.player.failedRequestUrl
import dev.frost819.newbv.player.httpResponseCode
import dev.frost819.newbv.player.impl.exo.ExoPlayerFactory
import dev.frost819.newbv.player.isAddressInvalidHttpCode
import dev.frost819.newbv.player.isNetworkError
import dev.frost819.newbv.player.isOutOfMemory
import dev.frost819.newbv.player.toPlaybackErrorDetail
import dev.frost819.newbv.player.toPlaybackErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Calendar
import javax.inject.Inject
import dev.frost819.newbv.data.datastore.ApiType as DataApiType

private const val PLAYER_ACTION_TIMEOUT_MS = 10_000L

/** 同时观看人数刷新间隔（cid 就绪后周期轮询）。 */
private const val ONLINE_WATCH_REFRESH_MS = 60_000L

/** 故障自救时重新获取播放地址的超时。 */
private const val PLAY_DATA_REFRESH_TIMEOUT_MS = 10_000L

/**
 * 一轮故障自救的时间窗。
 *
 * 距上次自救超过该时长再出错，视为新一轮故障，重新获得一次「重新获取播放地址」的机会；
 * 窗口内每轮最多重拉一次，避免地址接口被连续请求。
 */
private const val RECOVERY_EPISODE_WINDOW_MS = 60_000L

/** 卡顿看门狗的观察周期：持续缓冲这么久且几乎没有进展，判定当前线路吞吐塌陷。 */
private const val STALL_SWITCH_TIMEOUT_MS = 12_000L

/** 一个观察周期内缓冲至少要推进的媒体时长；达不到才换线路（只是网速慢时换线路会白丢已缓冲数据）。 */
private const val STALL_MIN_PROGRESS_MS = 1_000L

/** 播放地址过期判定的提前量（秒）：临近过期即视为已过期，避免切过去马上失效。 */
private const val PLAY_URL_EXPIRY_MARGIN_S = 30L

/**
 * 音频解码失败时的回退顺序。
 *
 * 只回退 AAC 三档：Dolby Atmos / Hi-Res 的高码率压缩帧才是设备解码器输入缓冲
 * 溢出的来源（实测 32KB），换画质或视频编码都无效。
 */
private val AUDIO_FALLBACK_ORDER = listOf(Audio.A192K, Audio.A132K, Audio.A64K)

/**
 * 播放器主 ViewModel。
 *
 * 管理视频播放器生命周期、URL 解析与播放、进度同步、心跳上报、
 * 播放结束动作、上下集切换。
 *
 * 不管理弹幕播放器（由 [DanmakuViewModel] 管理）和字幕（由 [SubtitleViewModel] 管理），
 * 但会通过 UI 层协调与它们同步。
 *
 * @param videoPlayRepository 播放数据仓库（URL、弹幕、字幕、蒙版、心跳、缩略图）
 * @param videoInfoRepository 视频信息共享仓库（分集列表、详情）
 * @param authRepository 鉴权仓库（会话凭证）
 * @param likeRepository 视频点赞仓库
 * @param coinRepository 视频投币仓库
 * @param favoriteRepository 视频收藏仓库
 * @param oneClickTripleActionRepository 一键三连仓库
 * @param exoPlayerFactory ExoPlayer 工厂
 * @param videoCapabilityProvider 设备视频解码能力查询器（选流时过滤超能力组合）
 * @param cdnSelector CDN 测速选择器（开启自动选源时用于排序候选地址）
 */
@HiltViewModel
class PlayerViewModel
    @Inject
    constructor(
        private val videoPlayRepository: VideoPlayRepository,
        private val videoInfoRepository: VideoInfoRepository,
        private val authRepository: AuthRepository,
        private val exoPlayerFactory: ExoPlayerFactory,
        private val videoCapabilityProvider: VideoCapabilityProvider,
        private val likeRepository: LikeRepository,
        private val coinRepository: CoinRepository,
        private val favoriteRepository: FavoriteRepository,
        private val oneClickTripleActionRepository: OneClickTripleActionRepository,
        private val cdnSelector: CdnSelector,
    ) : ViewModel() {
        private val logger = Loggers.get("PlayerViewModel")

        /** 视频播放器实例，供 Compose `AndroidView` 绑定。 */
        var videoPlayer: AbstractVideoPlayer? by mutableStateOf(null)
            private set

        /**
         * 本播放会话是否已完成首次加载（视频详情、弹幕、播放地址）。
         *
         * 播放页被详情/UP 主空间覆盖后再返回时，页面会重新组合，但 ViewModel 与播放器仍然存活，
         * 据此跳过重复加载，避免从头播放。
         */
        var isSessionStarted: Boolean = false
            private set

        /** 标记播放会话已完成首次加载，见 [isSessionStarted]。 */
        fun markSessionStarted() {
            isSessionStarted = true
        }

        private var playData: PlayData? = null

        /** 本次播放已尝试过的「画质|编码」组合，用于解码回退去重与终止。 */
        private val attemptedDecodeProfiles = mutableSetOf<String>()

        /** 本次播放已尝试过的音轨，用于音频解码回退去重与终止。 */
        private val attemptedAudioFallbacks = mutableSetOf<Audio>()

        /** 当前视频轨道按推荐顺序排列的 CDN 候选（开启自动选源时按测速排序）。 */
        private var videoCdnCandidates: List<String> = emptyList()

        /** 当前音频轨道按推荐顺序排列的 CDN 候选。 */
        private var audioCdnCandidates: List<String> = emptyList()

        /** 当前使用的视频候选下标（指向 [videoCdnCandidates]）。 */
        private var videoCdnIndex = 0

        /** 当前使用的音频候选下标（指向 [audioCdnCandidates]）。 */
        private var audioCdnIndex = 0

        /** 本轮故障自救是否已重新获取过播放地址（每轮最多一次）。 */
        private var playDataRefreshedInEpisode = false

        /** 最近一次故障自救的时间（毫秒时间戳），用于划分「轮」。 */
        private var lastRecoveryAtMs = 0L

        private val detachedWorkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private val _uiState = MutableStateFlow(PlayerUiState())
        val uiState = _uiState.asStateFlow()

        private val _seekerState = MutableStateFlow(SeekerState())
        val seekerState = _seekerState.asStateFlow()

        /** 当前视频共享状态（交互 + 历史）。 */
        val videoSharedState = videoInfoRepository.videoSharedState

        private val _uiEffect = MutableSharedFlow<PlayerUiEffect>()
        val uiEffect = _uiEffect.asSharedFlow()

        /**
         * 切换视频时发出的事件流。
         *
         * UI 层（[VideoPlayerScreen]）收集此流，在切换视频时协调
         * [DanmakuViewModel] 和 [SubtitleViewModel] 的重载。
         */
        private val _videoSwitchEvent = MutableSharedFlow<VideoSwitchEvent>(extraBufferCapacity = 1)
        val videoSwitchEvent = _videoSwitchEvent.asSharedFlow()

        private var seekerUpdateJob: Job? = null
        private var debugInfoUpdateJob: Job? = null
        private var clockUpdateJob: Job? = null
        private var heartbeatJob: Job? = null
        private var loadVideoJob: Job? = null
        private var backToStartCountdownJob: Job? = null
        private var playNextCountdownJob: Job? = null
        private var shortcutTipJob: Job? = null
        private var previewTipCountdownJob: Job? = null
        private var onlineWatchJob: Job? = null

        /** 故障自救中「重新获取播放地址并续播」的任务。 */
        private var recoverJob: Job? = null

        /** 卡顿看门狗任务（缓冲期间存活）。 */
        private var stallWatchJob: Job? = null

        /**
         * 切换视频事件。
         *
         * @param aid 新视频 AV 号
         * @param cid 新视频 CID
         */
        data class VideoSwitchEvent(
            val aid: Long,
            val cid: Long,
        )

        private val videoPlayerListener =
            object : VideoPlayerListener {
                override fun onError(error: Exception) {
                    // 连同原因链一起记录：「Source error」只是外壳，真正的原因
                    // （HTTP 状态码 / 超时 / 内存耗尽）在 cause 里
                    logger.error(error) { "onError: $error" }
                    stopStallWatch()
                    // 加载线程内存耗尽会被 media3 包装成 Source error，换线路无济于事
                    if (error.isOutOfMemory()) {
                        failPlayback("内存不足，请降低画质后重试")
                        return
                    }
                    // 仅网络类错误换线路 / 重拉地址有意义：解码 / 音频管线错误换源地址不会改善，
                    // 直接进入错误态（音频回退由 onAudioDecodeUnsupported 单独处理）
                    if (error.isNetworkError() && tryRecoverFromNetworkError(error)) return
                    // 其余错误进入错误态
                    failPlayback(error.toDetailedErrorMessage())
                }

                override fun onVideoDecodeUnsupported() {
                    logger.info {
                        "onVideoDecodeUnsupported: qn=${_uiState.value.mediaProfileState.qualityId}, " +
                            "codec=${_uiState.value.mediaProfileState.videoCodec}"
                    }
                    viewModelScope.launch { handleDecodeUnsupported() }
                }

                override fun onAudioDecodeUnsupported() {
                    logger.info {
                        "onAudioDecodeUnsupported: audio=${_uiState.value.mediaProfileState.audio}, " +
                            "codec=${_uiState.value.mediaProfileState.videoCodec}"
                    }
                    viewModelScope.launch { handleAudioUnsupported() }
                }

                override fun onReady() {
                    logger.info { "onReady" }
                    stopStallWatch()
                    _uiState.update { it.copy(playerState = PlayerState.Ready) }
                    updatePlaySpeed(forceUpdate = true)
                    startSeekerUpdater()
                    startDebugInfoUpdater()
                }

                override fun onPlay() {
                    logger.info { "onPlay" }
                    stopStallWatch()
                    _uiState.update { it.copy(playerState = PlayerState.Playing, isBuffering = false) }
                    if (_uiState.value.lastPlayed > 0) {
                        seekToLastPlayed()
                        _uiState.update { it.copy(lastPlayed = 0) }
                    }
                }

                override fun onPause() {
                    logger.info { "onPause" }
                    // 出错后 ExoPlayer 会紧跟着回调 isPlaying=false，不能把错误态覆盖成暂停态
                    if (_uiState.value.playerState is PlayerState.Error) return
                    _uiState.update { it.copy(playerState = PlayerState.Paused) }
                }

                override fun onBuffering() {
                    logger.info { "onBuffering" }
                    _uiState.update { it.copy(isBuffering = true) }
                    startStallWatch()
                }

                override fun onEnd() {
                    logger.info { "onEnd" }
                    stopStallWatch()
                    stopSeekerUpdater()
                    stopDebugInfoUpdater()
                    _uiState.update { it.copy(playerState = PlayerState.Ended) }
                    viewModelScope.launch { _uiEffect.emit(PlayerUiEffect.PlayEnded) }
                }

                override fun onSeekBack(seekBackIncrementMs: Long) {}

                override fun onSeekForward(seekForwardIncrementMs: Long) {}
            }

        /**
         * 初始化播放器状态。
         *
         * @param aid 视频 AV 号
         * @param cid 视频 CID
         * @param epid 番剧分集 ID（UGC 为 null）
         * @param title 视频标题
         * @param lastPlayed 上次播放位置（秒）
         * @param fromSeason 是否为番剧播放
         * @param subType 番剧子类型
         * @param seasonId 番剧 season ID
         * @param authorMid UP 主 mid
         * @param authorName UP 主名称
         */
        fun init(
            aid: Long,
            cid: Long,
            epid: Int?,
            title: String,
            lastPlayed: Int,
            fromSeason: Boolean,
            subType: Int,
            seasonId: Int,
            authorMid: Long = 0,
            authorName: String,
        ) {
            _uiState.update {
                it.copy(
                    aid = aid,
                    cid = cid,
                    epid = epid.takeIf { it != null && it != 0 },
                    seasonId = seasonId,
                    title = title,
                    lastPlayed = lastPlayed,
                    fromSeason = fromSeason,
                    subType = subType,
                    authorMid = authorMid,
                    authorName = authorName,
                    // 按用户偏好重置媒体格式，避免沿用上一个视频的画质/编码
                    mediaProfileState =
                        MediaProfileState(
                            qualityId = Prefs.defaultQuality.code,
                            videoCodec = Prefs.defaultVideoCodec,
                            audio = Prefs.defaultAudio,
                        ),
                )
            }

            startClockUpdater()
            // 同时观看人数观察者：监听 cid 变化即时拉取，就绪后周期刷新
            startOnlineWatchingObserver()
        }

        /**
         * 加载视频详情（相关视频、历史进度等）。
         *
         * 视频列表由详情页在导航前通过 [VideoInfoRepository] 填充。
         * 若播放器直接打开（无详情页上下文），列表为空，仅加载详情用于相关视频和历史。
         *
         * 分 P 目标解析（与原版 BV 直进逻辑一致）：
         * - 进入时已指定分 P（cid != 0）→ 严格保留，避免把其它分 P 的进度套到所选分 P；
         * - 未指定分 P（cid == 0，如首页/推荐封面直进）→ 采用历史分 P，无历史时回退默认第一个分 P。
         * 当最终目标正是历史分 P 时才应用断点进度（[PlayerUiState.lastPlayed]）。
         *
         * @param aid 视频 AV 号
         * @param bvid 视频 BV 号（可选）
         */
        suspend fun loadVideoDetail(
            aid: Long,
            bvid: String = "",
        ) {
            videoInfoRepository.loadVideoDetail(aid, getApiType(), bvid)
            val detail = videoInfoRepository.videoDetail.value
            // 仅采纳属于当前视频的历史，避免误用其它 aid 的进度
            val matched = videoInfoRepository.videoSharedState.value?.takeIf { it.aid == aid }
            val historyCid = matched?.lastPlayedCid ?: 0L
            val historyTime = matched?.lastPlayedTime ?: 0

            _uiState.update { state ->
                val targetCid =
                    when {
                        // 已指定分 P：保留（点击指定分 P 进入时必须播放所选分 P）
                        state.cid != 0L -> state.cid
                        // 未指定且存在历史：恢复到上次看的分 P
                        historyCid != 0L -> historyCid
                        // 无历史：使用详情默认（第一个）分 P
                        else -> detail?.cid ?: state.cid
                    }
                state.copy(
                    cid = targetCid,
                    // 仅当目标就是历史分 P（且未看完）时才应用断点进度
                    lastPlayed = if (targetCid == historyCid && historyTime > 0) historyTime else 0,
                    authorMid = detail?.author?.mid ?: state.authorMid,
                    authorName = detail?.author?.name ?: state.authorName,
                )
            }
        }

        /**
         * 启动同时观看人数观察者（[init] 时启动，随播放器会话存续）。
         *
         * 响应式监听 uiState 的 cid 变化：cid 就绪（直进时详情返回、
         * init 携带有效 cid、播放器内切集）时立即拉取，之后按
         * [ONLINE_WATCH_REFRESH_MS] 周期刷新。
         * [collectLatest] 保证切集瞬间取消旧 cid 的在途请求与等待。
         * 每轮循环读取实时 uiState，防御 cid 中途失效的边界情况。
         * 请求失败静默忽略（保留现有文案），绝不影响播放（参考 blbl 降级策略）。
         */
        private fun startOnlineWatchingObserver() {
            onlineWatchJob?.cancel()
            onlineWatchJob =
                viewModelScope.launch {
                    _uiState
                        .map { it.cid }
                        .filter { it > 0L }
                        .distinctUntilChanged()
                        .collectLatest {
                            while (isActive) {
                                val state = _uiState.value
                                if (state.cid > 0L && state.aid > 0L) {
                                    fetchOnlineWatching(state)
                                }
                                delay(ONLINE_WATCH_REFRESH_MS)
                            }
                        }
                }
        }

        /**
         * 拉取一次同时观看人数并更新 uiState。
         *
         * @param state 当前 UI 状态（提供 aid/cid）
         */
        private suspend fun fetchOnlineWatching(state: PlayerUiState) {
            runCatching {
                withTimeout(PLAYER_ACTION_TIMEOUT_MS) {
                    videoInfoRepository.getOnlineWatchingText(
                        aid = state.aid,
                        cid = state.cid,
                        preferApiType = getApiType(),
                    )
                }
            }.onSuccess { text ->
                if (text != null) _uiState.update { it.copy(onlineWatching = text) }
            }.onFailure { error ->
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                logger.error(error) { "Failed to fetch online watching count" }
            }
        }

        /** 停止同时观看人数观察者。 */
        private fun stopOnlineWatchingPolling() {
            onlineWatchJob?.cancel()
            onlineWatchJob = null
        }

        /**
         * 初始化视频播放器实例。
         *
         * 根据 Prefs 中的 API 类型设置 User-Agent 和 Referer，
         * 创建 ExoPlayer 并绑定事件监听器。
         */
        fun initVideoPlayer(context: Context) {
            if (videoPlayer != null) return
            val apiType = Prefs.apiType
            val options =
                VideoPlayerOptions(
                    userAgent =
                        PlayerConstants.getUserAgent(
                            if (apiType == DataApiType.App) ApiType.App else ApiType.Web,
                        ),
                    referer =
                        PlayerConstants.getReferer(
                            if (apiType == DataApiType.App) ApiType.App else ApiType.Web,
                        ),
                    enableSoftwareVideoDecoder = Prefs.enableSoftwareVideoDecoder,
                )

            val newPlayer = exoPlayerFactory.create(context.applicationContext, options)
            newPlayer.setPlayerEventListener(videoPlayerListener)
            videoPlayer = newPlayer

            val initialSpeed = Prefs.defaultPlaySpeed.speed
            _uiState.update { it.copy(playSpeed = initialSpeed) }
            newPlayer.speed = initialSpeed
        }

        /** 释放播放器资源，同步进度到 B 站。 */
        fun detachPlayer() {
            syncProgress(scope = detachedWorkScope, isDetaching = true)
            cancelRecovery()
            videoPlayer?.release()
            videoPlayer = null
            stopSeekerUpdater()
            stopDebugInfoUpdater()
            stopOnlineWatchingPolling()
            clockUpdateJob?.cancel()
        }

        /**
         * 播放/暂停切换。
         *
         * 错误态下播放器已停在空闲状态，单纯「继续播放」不会重新加载，
         * 因此此时按播放键等同于重试（见 [retryPlayback]）。
         */
        fun togglePlayPause() {
            val player = videoPlayer ?: return
            if (_uiState.value.playerState is PlayerState.Error) {
                retryPlayback()
                return
            }
            if (player.isPlaying) {
                player.pause()
            } else {
                player.start()
            }
        }

        /** 暂停视频，不改变其它播放器设置。 */
        fun pausePlayback() {
            if (videoPlayer?.isPlaying == true) videoPlayer?.pause()
        }

        /** 恢复视频播放。 */
        fun resumePlayback() {
            if (videoPlayer?.isPlaying != true) videoPlayer?.start()
        }

        /** 切换当前视频点赞状态，并同步详情页。 */
        fun toggleVideoLike() {
            val aid = _uiState.value.aid.takeIf { it > 0L } ?: return
            val current =
                videoInfoRepository.videoSharedState.value
                    ?.takeIf { it.aid == aid }
                    ?.liked ?: false
            viewModelScope.launch {
                runCatching {
                    withTimeout(PLAYER_ACTION_TIMEOUT_MS) {
                        likeRepository.updateVideoLiked(aid = aid, like = !current, preferApiType = getApiType())
                    }
                }.onSuccess {
                    videoInfoRepository.updateVideoActionState(aid = aid, liked = !current)
                }.onFailure { error ->
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    logger.error(error) { "Failed to toggle video like: aid=$aid" }
                    _uiEffect.emit(PlayerUiEffect.ShowToast("点赞失败: ${error.message ?: "未知错误"}"))
                }
            }
        }

        /** 为当前视频投一枚硬币，并同步详情页。 */
        fun sendVideoCoin() {
            val aid = _uiState.value.aid.takeIf { it > 0L } ?: return
            viewModelScope.launch {
                runCatching {
                    withTimeout(PLAYER_ACTION_TIMEOUT_MS) {
                        coinRepository.sendVideoCoin(aid = aid, preferApiType = getApiType())
                    }
                }.onSuccess {
                    videoInfoRepository.updateVideoActionState(aid = aid, coined = true)
                }.onFailure { error ->
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    logger.error(error) { "Failed to send video coin: aid=$aid" }
                    _uiEffect.emit(PlayerUiEffect.ShowToast("投币失败: ${error.message ?: "未知错误"}"))
                }
            }
        }

        /** 收藏当前视频到默认收藏夹，并同步详情页。 */
        fun toggleVideoFavorite() {
            val aid = _uiState.value.aid.takeIf { it > 0L } ?: return
            val current =
                videoInfoRepository.videoSharedState.value
                    ?.takeIf { it.aid == aid }
                    ?.favorited ?: false
            viewModelScope.launch {
                runCatching {
                    withTimeout(PLAYER_ACTION_TIMEOUT_MS) {
                        val folders =
                            favoriteRepository.getAllFavoriteFolderMetadataList(
                                mid = authRepository.mid ?: error("未登录"),
                                rid = aid,
                                preferApiType = getApiType(),
                            )
                        val selected = folders.filter { it.videoInThisFav }.map { it.id }
                        val defaultFolder = folders.firstOrNull { it.title == "默认收藏夹" }?.id
                        if (current) {
                            favoriteRepository.updateVideoToFavoriteFolder(
                                aid = aid,
                                addMediaIds = emptyList(),
                                delMediaIds = selected,
                                preferApiType = getApiType(),
                            )
                        } else {
                            favoriteRepository.updateVideoToFavoriteFolder(
                                aid = aid,
                                addMediaIds = listOfNotNull(defaultFolder),
                                delMediaIds = emptyList(),
                                preferApiType = getApiType(),
                            )
                        }
                    }
                }.onSuccess {
                    videoInfoRepository.updateVideoActionState(aid = aid, favorited = !current)
                }.onFailure { error ->
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    logger.error(error) { "Failed to toggle video favorite: aid=$aid" }
                    _uiEffect.emit(PlayerUiEffect.ShowToast("收藏失败: ${error.message ?: "未知错误"}"))
                }
            }
        }

        /** 执行当前视频一键三连，并同步详情页。 */
        fun oneClickTripleAction() {
            val aid = _uiState.value.aid.takeIf { it > 0L } ?: return
            val bvid = videoInfoRepository.videoDetail.value?.bvid
            viewModelScope.launch {
                runCatching {
                    withTimeout(PLAYER_ACTION_TIMEOUT_MS) {
                        oneClickTripleActionRepository.sendVideoOneClickTripleAction(
                            aid = aid,
                            bvid = bvid,
                            preferApiType = getApiType(),
                        )
                    }
                }.onSuccess { result ->
                    if (result != null) {
                        videoInfoRepository.updateVideoActionState(
                            aid = aid,
                            liked = result.like,
                            coined = result.coin,
                            favorited = result.fav,
                        )
                    }
                    _uiEffect.emit(PlayerUiEffect.ShowToast("一键三连"))
                }.onFailure { error ->
                    if (error is CancellationException && error !is TimeoutCancellationException) throw error
                    logger.error(error) { "Failed to send one-click triple action: aid=$aid" }
                    _uiEffect.emit(PlayerUiEffect.ShowToast("一键三连失败: ${error.message ?: "未知错误"}"))
                }
            }
        }

        /** 跳转到指定位置（毫秒）。 */
        fun seekToTime(time: Long) {
            videoPlayer?.seekTo(time)
            _seekerState.update { it.copy(currentTime = time) }
        }

        /** 回到开头。 */
        fun backToStart() {
            backToStartCountdownJob?.cancel()
            _uiState.update { it.copy(showBackToStart = false) }
            videoPlayer?.seekTo(0)
        }

        /** 立即播放下一集。 */
        fun playNextNow() {
            cancelPlayNext()
            val target = findNextPlayTarget()
            if (target != null) {
                playNextTarget(target)
            } else {
                showShortcutTip("没有下一集")
            }
        }

        /** 立即播放上一集。 */
        fun playPreviousNow() {
            cancelPlayNext()
            val target = findPreviousPlayTarget()
            if (target != null) {
                playNextTarget(target)
            } else {
                showShortcutTip("没有上一集")
            }
        }

        /** 显示快捷键提示；新的提示会覆盖旧提示并重新计时。 */
        fun showShortcutTip(text: String) {
            shortcutTipJob?.cancel()
            _uiState.update { it.copy(shortcutTipText = text) }
            shortcutTipJob =
                viewModelScope.launch {
                    delay(PlayerConstants.PLAYER_TIP_DURATION_MS)
                    _uiState.update { it.copy(shortcutTipText = null) }
                }
        }

        /** 取消自动播放下一集。 */
        fun cancelPlayNext() {
            playNextCountdownJob?.cancel()
            _uiState.update { it.copy(showSkipToNextEp = false) }
        }

        /**
         * 播放结束后的统一入口。
         *
         * 循环模式回到开头，否则根据 [Prefs.actionAfterPlay] 决定后续动作。
         */
        fun onPlaybackEnded() {
            if (_uiState.value.isLooping) {
                backToStart()
            } else {
                checkAndPlayNext()
            }
        }

        /**
         * 播放结束后的检查逻辑。
         *
         * 根据 Prefs.actionAfterPlay 决定：暂停 / 播放下一集 / 退出 / 播放相关视频。
         */
        fun checkAndPlayNext() {
            when (Prefs.actionAfterPlay) {
                dev.frost819.newbv.data.datastore.ActionAfterPlay.Pause -> return
                dev.frost819.newbv.data.datastore.ActionAfterPlay.Exit -> {
                    viewModelScope.launch { _uiEffect.emit(PlayerUiEffect.FinishActivity) }
                    return
                }
                dev.frost819.newbv.data.datastore.ActionAfterPlay.PlayRelated -> {
                    val firstRelated = videoInfoRepository.relatedVideos.value.firstOrNull()
                    if (firstRelated != null) {
                        startNextEpisodeCountdown(
                            NextPlayTarget.VideoItem(
                                VideoListItem(
                                    aid = firstRelated.aid,
                                    cid = firstRelated.cid,
                                    title = firstRelated.title,
                                ),
                            ),
                        )
                        return
                    }
                }
                dev.frost819.newbv.data.datastore.ActionAfterPlay.PlayNext -> { /* 继续执行 */ }
            }

            val nextTarget = findNextPlayTarget()
            if (nextTarget != null) {
                startNextEpisodeCountdown(nextTarget)
            } else {
                viewModelScope.launch { _uiEffect.emit(PlayerUiEffect.FinishActivity) }
            }
        }

        /**
         * 切换播放视频。
         *
         * 同步旧视频进度，更新 UI 状态，重新加载资源。
         * 通过 [videoSwitchEvent] 通知 UI 层协调弹幕/字幕重载。
         */
        fun playNewVideo(newVideo: VideoListItem) {
            videoPlayer?.pause()

            val state = _uiState.value
            val shouldUpdateDetail = state.aid != newVideo.aid
            val shouldUpdateList = videoInfoRepository.videoList.value.none { it.aid == newVideo.aid }

            syncProgress(viewModelScope)

            // 清空旧视频状态，防止新视频加载失败时残留旧数据
            playData = null
            cancelRecovery()
            videoCdnCandidates = emptyList()
            audioCdnCandidates = emptyList()
            videoCdnIndex = 0
            audioCdnIndex = 0
            stopSeekerUpdater()
            stopDebugInfoUpdater()

            _uiState.update {
                it.copy(
                    aid = newVideo.aid,
                    cid = newVideo.cid,
                    epid = newVideo.epid,
                    seasonId = newVideo.seasonId ?: 0,
                    title = newVideo.title,
                    // 作者信息立即取列表项携带值（相关视频卡片已有），详情加载完成后以详情为准覆盖；
                    // 缺失时置空而不是沿用，避免 UP 主页仍指向上一个视频的 UP 主
                    authorMid = newVideo.authorMid ?: 0L,
                    authorName = newVideo.authorName.orEmpty(),
                    lastPlayed = 0,
                    isBuffering = true,
                    playerState = PlayerState.Ready,
                    videoShot = null,
                    danmakuMask = null,
                    subtitleList = emptyList(),
                    subtitleData = emptyList(),
                    subtitleId = -1L,
                    availableQuality = emptyMap(),
                    availableVideoCodec = emptyList(),
                    availableAudio = emptyList(),
                    cdnLines = emptyList(),
                    cdnLineIndex = 0,
                    onlineWatching = "",
                    showPreviewTip = false,
                    showSkipToNextEp = false,
                    showBackToStart = false,
                    shortcutTipText = null,
                    mediaProfileState =
                        MediaProfileState(
                            qualityId = Prefs.defaultQuality.code,
                            videoCodec = Prefs.defaultVideoCodec,
                            audio = Prefs.defaultAudio,
                        ),
                )
            }
            resetFallbackState()

            // 通知 UI 层重载弹幕/字幕（非阻塞，避免卡住 playNewVideo）
            viewModelScope.launch { _videoSwitchEvent.emit(VideoSwitchEvent(newVideo.aid, newVideo.cid)) }

            // 异步加载详情 + 历史进度（不阻塞 playVideoWithResources）
            if (shouldUpdateDetail) {
                viewModelScope.launch {
                    videoInfoRepository.loadVideoDetail(newVideo.aid, getApiType())
                    val detail = videoInfoRepository.videoDetail.value?.takeIf { it.aid == newVideo.aid }
                    // 仅当历史 cid 与当前播放 cid 一致时才应用断点续播
                    val sharedState = videoInfoRepository.videoSharedState.value
                    val historyCid = sharedState?.lastPlayedCid ?: 0L
                    val historyTime = sharedState?.lastPlayedTime ?: 0
                    _uiState.update { state ->
                        // 快速连续切换时，过期视频的详情不得覆盖当前视频的状态
                        if (state.aid != newVideo.aid) return@update state
                        state.copy(
                            lastPlayed =
                                if (historyCid == newVideo.cid && historyTime > 0) {
                                    historyTime
                                } else {
                                    state.lastPlayed
                                },
                            // 详情中的作者信息最权威，覆盖列表项携带的初值
                            authorMid = detail?.author?.mid ?: state.authorMid,
                            authorName = detail?.author?.name ?: state.authorName,
                        )
                    }
                }
            }
            if (shouldUpdateList) {
                videoInfoRepository.updateVideoList(listOf(newVideo))
            }

            loadVideoWithResources()
        }

        /** 发送心跳（进度上报）。 */
        fun trySendHeartbeat() {
            syncProgress(scope = viewModelScope, updateLocal = false)
        }

        /**
         * 加载视频资源并开始播放。
         *
         * 并行加载：播放地址 + 弹幕 + 字幕 + 蒙版 + 缩略图 + 分 P。
         */
        fun loadVideoWithResources() {
            val state = _uiState.value
            val aid = state.aid
            val cid = state.cid
            val epid = state.epid

            loadVideoJob?.cancel()
            loadVideoJob =
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        resolveUrlsAndPlay(aid, cid, epid ?: 0)
                        launch { updateVideoShot() }
                        launch { updateVideoPages() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error(e) { "Loading video data error: $e" }
                        failPlayback(e.toPlaybackLoadErrorMessage())
                    }
                }
        }

        /** 更新播放速度。 */
        fun updatePlaySpeed(
            speed: Float? = null,
            forceUpdate: Boolean = false,
        ) {
            val currentSpeed = _uiState.value.playSpeed
            val targetSpeed = speed ?: currentSpeed
            if (!forceUpdate && currentSpeed == targetSpeed) return
            _uiState.update { it.copy(playSpeed = targetSpeed) }
            videoPlayer?.speed = targetSpeed
        }

        /** 更新宽高比。 */
        fun updateVideoAspectRatio(ratio: VideoAspectRatio) {
            _uiState.update { it.copy(aspectRatio = ratio) }
        }

        /** 切换循环播放。 */
        fun toggleLoop() {
            _uiState.update { it.copy(isLooping = !it.isLooping) }
        }

        /**
         * 更新媒体格式（画质/编码/音轨）或手动切换线路。
         *
         * 如果当前正在播放，会暂停、重新解析 URL、恢复进度后继续播放。
         * 出错后或播放地址已过期时，会先重新获取一批播放地址再切换——
         * 缓存的那批地址共用过期时间与签名，其中一条失效时其余的大概率同样失效。
         */
        fun updateMediaProfile(action: MediaProfileSettingAction) {
            val old = _uiState.value.mediaProfileState
            val new =
                when (action) {
                    is MediaProfileSettingAction.SetQuality -> old.copy(qualityId = action.qualityId)
                    is MediaProfileSettingAction.SetVideoCodec -> old.copy(videoCodec = action.codec)
                    is MediaProfileSettingAction.SetAudio -> old.copy(audio = action.audio)
                    is MediaProfileSettingAction.SetCdnLine -> {
                        selectCdnLine(action.index)
                        return
                    }
                }
            if (old == new) return

            // 用户手动切换，重置回退状态，避免误判为「已尝试」
            resetFallbackState()
            cancelRecovery()
            val needFreshAddress = _uiState.value.playerState is PlayerState.Error || isPlayDataExpired()

            // 同步刷新该画质下的可用编码列表（编码菜单随画质联动）
            _uiState.update {
                it.copy(
                    mediaProfileState = new,
                    availableVideoCodec = codecsFor(new.qualityId),
                )
            }

            videoPlayer?.let { player ->
                // resolveMediaUrls 在开启自动选源时会做测速（挂起），需放入协程
                viewModelScope.launch {
                    // 旧地址多半已整体失效：先换一批新的。失败不拦截，继续用旧数据尽力而为
                    if (needFreshAddress) refreshPlayDataQuietly()
                    // 先解析地址（可能测速）再切换：解析期间旧流照常播放，不出现暂停空档
                    val mediaUrls = resolveMediaUrls(new.qualityId, new.videoCodec, new.audio)
                    if (mediaUrls == null) {
                        player.pause()
                        failPlayback("视频源解析失败，请稍后重试")
                        return@launch
                    }
                    replayMedia(mediaUrls, player.currentPosition)
                }
            }
        }

        // ── 解码回退 ──────────────────────────────────────────────

        /**
         * 清除回退状态（切集 / 手动切换媒体格式时调用）。
         *
         * 视频编码回退与音轨回退都按「一次播放意图」计数，换片或换档后应重新获得机会。
         */
        private fun resetFallbackState() {
            attemptedDecodeProfiles.clear()
            attemptedAudioFallbacks.clear()
            playDataRefreshedInEpisode = false
            lastRecoveryAtMs = 0L
        }

        /** 取消进行中的故障自救任务与卡顿看门狗（切集 / 手动操作 / 释放播放器时调用）。 */
        private fun cancelRecovery() {
            recoverJob?.cancel()
            recoverJob = null
            stopStallWatch()
        }

        /**
         * 用新的媒体地址在指定位置续播。
         *
         * 手动换档、编码/音轨回退、CDN 回退都走这里：进入缓冲态、清掉旧错误态都在此处完成，
         * 调用方不必再自己维护 `isBuffering`。
         *
         * @param urls 重新解析出的视频 / 音频地址
         * @param position 续播位置（毫秒），0 表示从头播放
         */
        private fun replayMedia(
            urls: MediaUrls,
            position: Long,
        ) {
            val player = videoPlayer ?: return
            _uiState.update {
                it.copy(
                    playerState = PlayerState.Ready,
                    isBuffering = true,
                    cdnLineIndex = videoCdnIndex,
                )
            }
            player.pause()
            player.playUrl(urls.videoUrl, urls.audioUrl)
            player.prepare()
            if (position > 0) player.seekTo(position)
            player.start()
            // 换源时播放器可能本就处于缓冲态，不会再回调 onBuffering：这里显式重启看门狗
            stopStallWatch()
            startStallWatch()
        }

        /** 用当前选中的候选地址（[videoCdnIndex] / [audioCdnIndex]）在指定位置续播。 */
        private fun replayCurrentCandidates(position: Long) {
            val videoUrl = videoCdnCandidates.getOrNull(videoCdnIndex) ?: return
            val audioUrl = audioCdnCandidates.getOrNull(audioCdnIndex)
            replayMedia(MediaUrls(videoUrl = videoUrl, audioUrl = audioUrl), position)
        }

        /**
         * 处理「当前编码本机解码不了」：按候选顺序回退到下一个未尝试的组合并重播。
         *
         * 每次回退 toast 提示用户；候选组合（画质 × 编码）被穷尽后上报播放错误。
         * 由于每个组合只会被尝试一次（[attemptedDecodeProfiles] 去重），
         * 而候选空间有限，循环必然终止，无需额外次数上限。
         */
        private suspend fun handleDecodeUnsupported() {
            val state = _uiState.value
            val current = state.mediaProfileState
            attemptedDecodeProfiles.add(profileKey(current.qualityId, current.videoCodec))

            val next = nextDecodeCandidate(current)
            if (next == null) {
                failPlayback("解码器不支持该视频")
                return
            }

            val qualityName = Resolution.fromCode(next.quality).displayName
            val codecName = next.codec.displayName
            _uiEffect.emit(PlayerUiEffect.ShowToast("当前编码不受支持，正在回退到 $qualityName · $codecName"))

            val urls = resolveMediaUrls(next.quality, next.codec, current.audio)
            val player =
                videoPlayer ?: run {
                    failPlayback("播放器未初始化")
                    return
                }
            if (urls == null) {
                failPlayback("视频源解析失败")
                return
            }

            val position = player.currentPosition
            _uiState.update {
                it.copy(
                    mediaProfileState =
                        it.mediaProfileState.copy(
                            qualityId = next.quality,
                            videoCodec = next.codec,
                        ),
                    availableVideoCodec = codecsFor(next.quality),
                )
            }
            replayMedia(urls, position)
        }

        /**
         * 处理「当前音轨本机解码不了」：回退到下一档未尝试的 AAC 音轨并从原位置续播。
         *
         * 触发来源是 [VideoPlayerListener.onAudioDecodeUnsupported]，典型场景是
         * Dolby Atmos / Hi-Res 的压缩帧超出设备解码器输入缓冲。每个音轨只会被尝试一次
         * （[attemptedAudioFallbacks] 去重），候选穷尽后上报播放错误。
         */
        private suspend fun handleAudioUnsupported() {
            val current = _uiState.value.mediaProfileState
            attemptedAudioFallbacks.add(current.audio)

            val next =
                nextAudioCandidate(current.audio) ?: run {
                    failPlayback("音频解码失败，请在播放器菜单中切换音轨")
                    return
                }
            val player =
                videoPlayer ?: run {
                    failPlayback("播放器未初始化")
                    return
                }
            val urls = resolveMediaUrls(current.qualityId, current.videoCodec, next)
            if (urls == null) {
                failPlayback("视频源解析失败")
                return
            }

            _uiEffect.emit(PlayerUiEffect.ShowToast("当前音轨解码失败，已切换到 ${next.displayName}"))
            val position = player.currentPosition
            _uiState.update {
                it.copy(
                    mediaProfileState = it.mediaProfileState.copy(audio = next),
                )
            }
            replayMedia(urls, position)
        }

        /**
         * 计算下一个可回退的音轨。
         *
         * 按 [AUDIO_FALLBACK_ORDER] 取第一个「未尝试过且服务端确实提供」的音轨；
         * `availableAudio` 尚未填充时（早期状态）退化为按编码顺序尝试。
         */
        private fun nextAudioCandidate(current: Audio): Audio? {
            val available = _uiState.value.availableAudio
            return AUDIO_FALLBACK_ORDER.firstOrNull { candidate ->
                candidate != current &&
                    candidate !in attemptedAudioFallbacks &&
                    (available.isEmpty() || candidate in available)
            }
        }

        /**
         * 计算下一个可回退的「画质 + 编码」组合。
         *
         * 优先返回本机判定可解码且未尝试过的组合；若能力判定认为全部不可解码，
         * 则退而求其次返回任意未尝试过的组合（能力判定可能因厂商实现而偏差）。
         */
        private fun nextDecodeCandidate(current: MediaProfileState): PlaybackCandidate? {
            val data = playData ?: return null
            val qualities = orderQualities(data.dashVideos.map { it.quality }, Prefs.defaultQuality.code)
            val codecOrder =
                listOf(
                    Prefs.defaultVideoCodec,
                    VideoCodec.HEVC,
                    VideoCodec.AV1,
                    VideoCodec.AVC,
                    VideoCodec.DVH1,
                ).distinct()

            // 按画质序 × 编码序生成候选，排除当前项与已尝试项；
            // requireDecodable=true 时再排除本机判定不可解码的项
            fun filteredCandidates(requireDecodable: Boolean): List<PlaybackCandidate> =
                qualities.flatMap { quality ->
                    val available = codecsFor(quality).toSet()
                    codecOrder
                        .filter { it in available }
                        .mapNotNull { codec ->
                            if (quality == current.qualityId && codec == current.videoCodec) return@mapNotNull null
                            if (profileKey(quality, codec) in attemptedDecodeProfiles) return@mapNotNull null
                            if (requireDecodable) {
                                val track = trackFor(quality, codec) ?: return@mapNotNull null
                                val decodable =
                                    videoCapabilityProvider.isDecodable(
                                        VideoDecodeProfile(
                                            codec = codec,
                                            width = track.width,
                                            height = track.height,
                                            frameRate = track.frameRate.toFloatOrNull(),
                                            codecs = track.codecs,
                                        ),
                                    )
                                if (!decodable) return@mapNotNull null
                            }
                            PlaybackCandidate(quality, codec)
                        }
                }

            return filteredCandidates(requireDecodable = true).firstOrNull()
                ?: filteredCandidates(requireDecodable = false).firstOrNull()
        }

        /** 置播放错误状态。 */
        private fun failPlayback(message: String) {
            stopStallWatch()
            _uiState.update {
                it.copy(
                    playerState = PlayerState.Error(message),
                    isBuffering = false,
                )
            }
        }

        /** 「画质|编码」组合键。 */
        private fun profileKey(
            quality: Int,
            codec: VideoCodec,
        ): String = "$quality|${codec.name}"

        // ── 私有方法 ──────────────────────────────────────────────

        private suspend fun resolveUrlsAndPlay(
            aid: Long,
            cid: Long,
            epid: Int = 0,
        ) {
            try {
                val mediaUrls = fetchMediaUrls(aid, cid, epid)
                withContext(Dispatchers.Main) { executePlayback(mediaUrls) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error(e) { "Failed to load media: ${e.message}" }
                throw IllegalStateException(e.message, e)
            }
        }

        private suspend fun fetchMediaUrls(
            aid: Long,
            cid: Long,
            epid: Int,
        ): MediaUrls {
            val config = loadPlaybackConfig(aid, cid, epid)
            return resolveMediaUrls(config.qn, config.codec, config.audio)
                ?: throw IllegalStateException("视频源解析失败")
        }

        private suspend fun loadPlaybackConfig(
            aid: Long,
            cid: Long,
            epid: Int = 0,
        ): PlaybackConfig {
            val apiType = getApiType()
            val playData = fetchPlayData(aid, cid, epid, apiType)
            this.playData = playData

            val resolutionMap =
                playData.dashVideos.associate { video ->
                    video.quality to Resolution.fromCode(video.quality).name
                }

            val availableAudioList =
                buildList {
                    addAll(playData.dashAudios.map { Audio.fromCode(it.codecId) })
                    playData.dolby?.let { add(Audio.fromCode(it.codecId)) }
                    playData.flac?.let { add(Audio.fromCode(it.codecId)) }
                }.distinct()

            val requestedQualityId = calculateTargetQuality(resolutionMap.keys, Prefs.defaultQuality.code)
            // 能力感知选流：目标画质优先、逐档降级；同档按编码偏好，取第一个本机可解码的组合
            val candidate = pickProfile(requestedQualityId)
            val targetQualityId = candidate?.quality ?: requestedQualityId
            val availableCodecs = codecsFor(targetQualityId)
            val targetCodec =
                candidate?.codec
                    ?: Prefs.defaultVideoCodec.takeIf { it in availableCodecs }
                    ?: availableCodecs.minByOrNull { it.ordinal }
            val targetAudio = calculateTargetAudio(availableAudioList, Prefs.defaultAudio)

            // 因设备解码能力而降档时提示用户实际使用的组合（服务端缺档不提示）
            decodeFallbackNotice(
                requestedQualityId = requestedQualityId,
                selected = candidate,
                preferredCodec = Prefs.defaultVideoCodec,
                targetQualityCodecs = availableCodecs,
            )?.let { message ->
                viewModelScope.launch { _uiEffect.emit(PlayerUiEffect.ShowToast(message)) }
            }

            _uiState.update {
                it.copy(
                    availableQuality = resolutionMap,
                    availableVideoCodec = availableCodecs,
                    availableAudio = availableAudioList,
                    mediaProfileState =
                        it.mediaProfileState.copy(
                            qualityId = targetQualityId,
                            videoCodec = targetCodec ?: VideoCodec.AVC,
                            audio = targetAudio,
                        ),
                )
            }

            if (playData.needPay) startShowPreviewTipCountdown()

            return PlaybackConfig(qn = targetQualityId, codec = targetCodec, audio = targetAudio)
        }

        private suspend fun fetchPlayData(
            aid: Long,
            cid: Long,
            epid: Int,
            apiType: ApiType,
        ): PlayData =
            if (_uiState.value.fromSeason) {
                videoPlayRepository.getPgcPlayData(
                    aid = aid,
                    cid = cid,
                    epid = epid,
                    preferCodec =
                        Prefs.defaultVideoCodec.let {
                            when (it) {
                                VideoCodec.AVC -> dev.frost819.newbv.biliapi.entity.CodeType.Code264
                                VideoCodec.HEVC -> dev.frost819.newbv.biliapi.entity.CodeType.Code265
                                VideoCodec.AV1 -> dev.frost819.newbv.biliapi.entity.CodeType.CodeAv1
                                else -> dev.frost819.newbv.biliapi.entity.CodeType.NoCode
                            }
                        },
                    preferApiType = apiType,
                )
            } else {
                videoPlayRepository.getPlayData(aid = aid, cid = cid, preferApiType = apiType)
            }

        private fun calculateTargetQuality(
            available: Set<Int>,
            default: Int,
        ): Int {
            if (available.contains(default)) return default
            val sorted = available.sorted()
            return sorted.findLast { it <= default } ?: sorted.firstOrNull() ?: 0
        }

        private fun calculateTargetAudio(
            available: List<Audio>,
            default: Audio,
        ): Audio {
            if (available.contains(default)) return default
            return when {
                default == Audio.ADolbyAtoms && available.contains(Audio.AHiRes) -> Audio.AHiRes
                default == Audio.AHiRes && available.contains(Audio.ADolbyAtoms) -> Audio.ADolbyAtoms
                available.contains(Audio.A192K) -> Audio.A192K
                available.contains(Audio.A132K) -> Audio.A132K
                available.contains(Audio.A64K) -> Audio.A64K
                else -> available.firstOrNull() ?: Audio.A132K
            }
        }

        /** 指定画质下的可用编码（对当前 [playData] 取，空播放数据返回空）。 */
        private fun codecsFor(qualityId: Int): List<VideoCodec> =
            playData?.let { collectCodecs(it, qualityId) } ?: emptyList()

        /** 查找指定画质 + 编码的 DASH 流（对当前 [playData] 取）。 */
        private fun trackFor(
            quality: Int,
            codec: VideoCodec,
        ): DashVideo? = playData?.let { findTrack(it, quality, codec) }

        /** 能力感知选流（对当前 [playData] 取）。 */
        private fun pickProfile(requestedQualityId: Int): PlaybackCandidate? =
            playData?.let {
                pickDecodableProfile(
                    data = it,
                    requestedQualityId = requestedQualityId,
                    preferredCodec = Prefs.defaultVideoCodec,
                    capabilityProvider = videoCapabilityProvider,
                )
            }

        private suspend fun resolveMediaUrls(
            qn: Int? = null,
            codec: VideoCodec? = null,
            audio: Audio? = null,
        ): MediaUrls? {
            val data = playData ?: return null
            val state = _uiState.value
            val targetQn = qn ?: state.mediaProfileState.qualityId
            val targetCodec = codec ?: state.mediaProfileState.videoCodec
            val targetAudio = audio ?: state.mediaProfileState.audio

            // 优先按目标画质 + 目标编码匹配（Web 用 codecs 串，App 退用 codecId），
            // 再退到同画质任意流，最后才全局兜底。
            val foundVideo =
                data.dashVideos.firstOrNull { it.quality == targetQn && trackMatchesCodec(it, targetCodec) }
                    ?: data.dashVideos.firstOrNull { it.quality == targetQn }
                    ?: data.dashVideos.firstOrNull()
                    ?: return null

            val videoUrls = mutableListOf<String?>()
            videoUrls.add(foundVideo.baseUrl)
            videoUrls.addAll(foundVideo.backUrl)

            val audioItem =
                data.dashAudios.find { it.codecId == targetAudio.code }
                    ?: data.dolby.takeIf { it?.codecId == targetAudio.code }
                    ?: data.flac.takeIf { it?.codecId == targetAudio.code }
                    ?: data.dashAudios.minByOrNull { it.codecId }

            val audioUrls = mutableListOf<String>()
            audioItem?.baseUrl?.let { audioUrls.add(it) }
            audioUrls.addAll(audioItem?.backUrl ?: emptyList())

            // 开启自动选源时对候选测速排序（结果按 host 缓存），否则沿用接口顺序、仅把已知故障节点后移
            videoCdnCandidates = arrangeCdnCandidates(videoUrls.filterNotNull())
            audioCdnCandidates = arrangeCdnCandidates(audioUrls)
            videoCdnIndex = 0
            audioCdnIndex = 0

            val videoUrl = videoCdnCandidates.firstOrNull() ?: return null
            val audioUrl = audioCdnCandidates.firstOrNull()

            _uiState.update {
                it.copy(
                    videoHeight = foundVideo.height,
                    videoWidth = foundVideo.width,
                    cdnLines =
                        videoCdnCandidates.mapIndexed { index, url ->
                            cdnLineLabel(index, url, cdnSelector.speedOf(url))
                        },
                    cdnLineIndex = 0,
                )
            }
            return MediaUrls(videoUrl, audioUrl)
        }

        /**
         * 整理某条轨道的候选地址顺序。
         *
         * 官方 CDN 在前：开启自动选源时按测速结果排序，否则保持接口顺序、仅把已知故障节点后移；
         * PCDN / 裸 IP 地址不参与测速，追加在最后作为兜底（官方节点全部不可用时仍有路可走）。
         */
        private suspend fun arrangeCdnCandidates(urls: List<String>): List<String> {
            val distinct = urls.filter { it.isNotBlank() }.distinct()
            val (official, others) = distinct.partition { isOfficialCdnUrl(it) }
            val preferred = official.ifEmpty { others }
            val lastResort = if (official.isEmpty()) emptyList() else others
            if (preferred.isEmpty()) return emptyList()
            val ordered =
                if (Prefs.autoSelectCdn) {
                    cdnSelector.rank(preferred)
                } else {
                    cdnSelector.deprioritizeFailed(preferred)
                }
            // 选择器异常返回空列表时退回原顺序，绝不因排序丢掉可用地址
            return ordered.ifEmpty { preferred } + lastResort
        }

        private fun executePlayback(mediaUrls: MediaUrls) {
            val player =
                videoPlayer ?: run {
                    logger.error { "VideoPlayer is not initialized!" }
                    return
                }
            player.playUrl(mediaUrls.videoUrl, mediaUrls.audioUrl)
            player.prepare()
            player.start()
        }

        private suspend fun updateVideoShot() {
            val state = _uiState.value
            runCatching {
                val shot =
                    videoPlayRepository.getVideoShot(
                        aid = state.aid,
                        cid = state.cid,
                        preferApiType = getApiType(),
                    )
                _uiState.update { it.copy(videoShot = shot) }
            }.onFailure { logger.warn { "Load video shot failed: $it" } }
        }

        private suspend fun updateVideoPages() {
            videoInfoRepository.updateUgcPages(getApiType())
        }

        private fun syncProgress(
            scope: CoroutineScope,
            updateLocal: Boolean = true,
            isDetaching: Boolean = false,
        ) {
            val player = videoPlayer ?: return
            val state = _uiState.value
            val currentTime = (player.currentPosition.coerceAtLeast(0) / 1000).toInt()
            val totalTime = (player.duration.coerceAtLeast(0) / 1000).toInt()
            val reportTime =
                if (totalTime > 0 && currentTime >= totalTime) {
                    -1
                } else {
                    currentTime
                }

            if (updateLocal) {
                videoInfoRepository.updateHistory(reportTime, state.cid)
            }

            if (!Prefs.incognitoMode) {
                heartbeatJob?.cancel()
                heartbeatJob =
                    scope.launch(Dispatchers.IO) {
                        try {
                            if (isDetaching) {
                                withTimeout(PlayerConstants.HEARTBEAT_DETACH_TIMEOUT_MS) {
                                    uploadHistory(state, reportTime)
                                }
                            } else {
                                uploadHistory(state, reportTime)
                            }
                        } catch (e: Exception) {
                            logger.warn { "Failed to upload history: $e" }
                        }
                    }
            }
        }

        private suspend fun uploadHistory(
            state: PlayerUiState,
            time: Int,
        ) {
            try {
                val apiType = getApiType()
                if (!state.fromSeason) {
                    videoPlayRepository.sendHeartbeat(
                        aid = state.aid,
                        cid = state.cid,
                        time = time,
                        preferApiType = apiType,
                    )
                } else {
                    videoPlayRepository.sendHeartbeat(
                        aid = state.aid,
                        cid = state.cid,
                        time = time,
                        type = HeartbeatVideoType.Season,
                        subType = state.subType,
                        epid = state.epid,
                        seasonId = state.seasonId,
                        preferApiType = apiType,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn { "Send heartbeat failed: $e" }
            }
        }

        private fun seekToLastPlayed() {
            // 历史接口和 UiState 以秒保存进度，播放器 seek API 使用毫秒。
            val time = _uiState.value.lastPlayed.toLong() * 1000L
            videoPlayer?.seekTo(time)
            _uiState.update { it.copy(showBackToStart = true) }
            backToStartCountdownJob?.cancel()
            backToStartCountdownJob =
                viewModelScope.launch {
                    delay(PlayerConstants.COUNTDOWN_DURATION_MS)
                    _uiState.update { it.copy(showBackToStart = false) }
                }
        }

        private fun startSeekerUpdater() {
            if (seekerUpdateJob?.isActive == true) return
            seekerUpdateJob =
                viewModelScope.launch(Dispatchers.Main) {
                    while (isActive) {
                        updateSeekerState()
                        delay(PlayerConstants.SEEKER_UPDATE_INTERVAL_MS)
                    }
                }
        }

        private fun stopSeekerUpdater() {
            seekerUpdateJob?.cancel()
            seekerUpdateJob = null
        }

        /**
         * 启动调试信息轮询（仅 [Prefs.showPlayerDebugInfo] 开启时）。
         *
         * 独立于 seekerUpdater，以 500ms 间隔运行，避免无谓开销。
         */
        private fun startDebugInfoUpdater() {
            if (!Prefs.showPlayerDebugInfo) return
            if (debugInfoUpdateJob?.isActive == true) return
            debugInfoUpdateJob =
                viewModelScope.launch(Dispatchers.Main) {
                    while (isActive) {
                        val player = videoPlayer ?: break
                        _seekerState.update { it.copy(debugInfo = player.debugInfo) }
                        delay(500)
                    }
                }
        }

        private fun stopDebugInfoUpdater() {
            debugInfoUpdateJob?.cancel()
            debugInfoUpdateJob = null
        }

        private fun updateSeekerState() {
            val player = videoPlayer ?: return
            _seekerState.update {
                it.copy(
                    totalDuration = player.duration.coerceAtLeast(0L),
                    currentTime = player.currentPosition.coerceAtLeast(0L),
                    bufferedPercentage = player.bufferedPercentage,
                )
            }
        }

        private fun startClockUpdater() {
            clockUpdateJob?.cancel()
            clockUpdateJob =
                viewModelScope.launch(Dispatchers.Main) {
                    while (isActive) {
                        val cal = Calendar.getInstance()
                        _uiState.update {
                            it.copy(
                                clock = Pair(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)),
                            )
                        }
                        delay(PlayerConstants.CLOCK_UPDATE_INTERVAL_MS)
                    }
                }
        }

        // ── 线路故障自救 ──────────────────────────────────────────

        /**
         * 网络类错误的自救入口，返回是否已接管（`false` 表示无计可施，由调用方上报错误）。
         *
         * 依次尝试：
         * 1. 换到出错那条流（视频 / 音频）的下一个候选地址，从原位置续播；
         * 2. 候选用尽，或错误表明地址本身已失效（HTTP 403/404 等、已过期）时，
         *    重新获取一批播放地址后续播——每轮故障最多一次；
         * 3. 重拉之后仍失败：把新一批的候选试完，全部失败才放弃。
         *
         * 候选下标只增不减、每轮最多重拉一次，因此流程必然终止。
         */
        private fun tryRecoverFromNetworkError(error: Throwable): Boolean {
            val player = videoPlayer ?: return false
            val position = player.currentPosition

            val now = System.currentTimeMillis()
            if (now - lastRecoveryAtMs > RECOVERY_EPISODE_WINDOW_MS) playDataRefreshedInEpisode = false
            lastRecoveryAtMs = now

            val failedUrl = error.failedRequestUrl()
            val failedTrack = trackOf(failedUrl)
            val httpCode = error.httpResponseCode()
            // 记下故障节点：后续排序时排到最后（含之后打开的其它视频）
            when (failedTrack) {
                FailedTrack.Video -> videoCdnCandidates.getOrNull(videoCdnIndex)
                FailedTrack.Audio -> audioCdnCandidates.getOrNull(audioCdnIndex)
                FailedTrack.Unknown -> failedUrl
            }?.let { cdnSelector.reportFailure(it) }

            val addressInvalid = isAddressInvalidHttpCode(httpCode) || isPlayDataExpired()
            logger.info {
                "Recover from network error: track=$failedTrack, http=$httpCode, " +
                    "addressInvalid=$addressInvalid, refreshed=$playDataRefreshedInEpisode, " +
                    "video=${videoCdnIndex + 1}/${videoCdnCandidates.size}, " +
                    "audio=${audioCdnIndex + 1}/${audioCdnCandidates.size}, url=$failedUrl"
            }

            // 地址本身失效时同批候选大概率一起失效，轮换只会白等：跳过，直接重拉
            if (!addressInvalid && switchToNextCandidate(failedTrack)) {
                announceLineSwitch("当前播放线路异常，已切换到备用线路")
                replayCurrentCandidates(position)
                return true
            }
            if (!playDataRefreshedInEpisode) {
                playDataRefreshedInEpisode = true
                launchRefreshAndReplay(
                    position = position,
                    notice =
                        if (addressInvalid) {
                            "播放地址已失效，正在重新获取"
                        } else {
                            "备用线路已用尽，正在重新获取播放地址"
                        },
                )
                return true
            }
            // 本轮已重拉过：把剩余候选试完再放弃
            if (addressInvalid && switchToNextCandidate(failedTrack)) {
                announceLineSwitch("当前播放线路异常，已切换到备用线路")
                replayCurrentCandidates(position)
                return true
            }
            return false
        }

        /** 根据失败请求的地址判断出错的是哪条流（比较时忽略查询参数）。 */
        private fun trackOf(failedUrl: String?): FailedTrack {
            val key = failedUrl?.substringBefore('?') ?: return FailedTrack.Unknown
            return when (key) {
                videoCdnCandidates.getOrNull(videoCdnIndex)?.substringBefore('?') -> FailedTrack.Video
                audioCdnCandidates.getOrNull(audioCdnIndex)?.substringBefore('?') -> FailedTrack.Audio
                else -> FailedTrack.Unknown
            }
        }

        /**
         * 把出错的那条流切到下一个候选地址。
         *
         * 音视频多数在同一节点上：节点整体故障时另一条流紧接着也会出错，
         * 因此另一条流若与出错的流同节点，就一起换，省掉一轮「报错 → 重播」。
         * 分不清是哪条流出错时两条都换。
         *
         * @return 是否确实换到了新的候选；出错的那条流已无候选时返回 `false`
         */
        private fun switchToNextCandidate(track: FailedTrack): Boolean {
            val hasNextVideo = videoCdnIndex + 1 < videoCdnCandidates.size
            val hasNextAudio = audioCdnIndex + 1 < audioCdnCandidates.size
            val sameHost = isAudioOnVideoHost()
            return when (track) {
                FailedTrack.Video -> {
                    if (hasNextVideo) {
                        videoCdnIndex++
                        if (sameHost && hasNextAudio) audioCdnIndex++
                    }
                    hasNextVideo
                }
                FailedTrack.Audio -> {
                    if (hasNextAudio) {
                        audioCdnIndex++
                        if (sameHost && hasNextVideo) videoCdnIndex++
                    }
                    hasNextAudio
                }
                FailedTrack.Unknown -> {
                    if (hasNextVideo) videoCdnIndex++
                    if (hasNextAudio) audioCdnIndex++
                    hasNextVideo || hasNextAudio
                }
            }
        }

        /** 当前音频与视频是否在同一个 CDN 节点上。 */
        private fun isAudioOnVideoHost(): Boolean {
            val video = videoCdnCandidates.getOrNull(videoCdnIndex) ?: return false
            val audio = audioCdnCandidates.getOrNull(audioCdnIndex) ?: return false
            return cdnHostOf(video) == cdnHostOf(audio)
        }

        /** 提示用户线路已切换（非阻塞）。 */
        private fun announceLineSwitch(message: String) {
            logger.info {
                "CDN switch -> video #${videoCdnIndex + 1} ${videoCdnCandidates.getOrNull(videoCdnIndex)}, " +
                    "audio #${audioCdnIndex + 1}"
            }
            viewModelScope.launch { _uiEffect.emit(PlayerUiEffect.ShowToast(message)) }
        }

        /**
         * 出错后重试：重新获取播放地址并从出错位置续播。
         *
         * 错误态下的播放键 / 确认键走这里。连播放地址都没拿到（起播阶段失败）时，
         * 重走完整加载流程。
         */
        fun retryPlayback() {
            val player = videoPlayer ?: return
            cancelRecovery()
            resetFallbackState()
            if (playData == null) {
                _uiState.update { it.copy(playerState = PlayerState.Ready, isBuffering = true) }
                loadVideoWithResources()
                return
            }
            // 手动重试本身就是一次重拉：紧接着再出错时不再自动重拉第二次
            playDataRefreshedInEpisode = true
            lastRecoveryAtMs = System.currentTimeMillis()
            launchRefreshAndReplay(player.currentPosition, notice = null)
        }

        /**
         * 启动「重新获取播放地址并续播」任务，并立即进入加载态（清掉旧错误文案）。
         *
         * @param position 续播位置（毫秒）
         * @param notice 给用户的提示；null 表示不提示（用户主动重试时）
         */
        private fun launchRefreshAndReplay(
            position: Long,
            notice: String?,
        ) {
            recoverJob?.cancel()
            _uiState.update { it.copy(playerState = PlayerState.Ready, isBuffering = true) }
            recoverJob =
                viewModelScope.launch {
                    if (notice != null) {
                        launch { _uiEffect.emit(PlayerUiEffect.ShowToast(notice)) }
                    }
                    try {
                        reloadPlayData()
                    } catch (e: TimeoutCancellationException) {
                        failPlayback("重新获取播放地址超时，请按确认键重试")
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.error(e) { "Refresh play data failed: $e" }
                        failPlayback(e.toPlaybackLoadErrorMessage())
                        return@launch
                    }
                    val profile = _uiState.value.mediaProfileState
                    val urls = resolveMediaUrls(profile.qualityId, profile.videoCodec, profile.audio)
                    if (urls == null) {
                        failPlayback("视频源解析失败，请稍后重试")
                        return@launch
                    }
                    replayMedia(urls, position)
                }
        }

        /**
         * 重新获取当前视频的播放地址并替换 [playData]。
         *
         * 不改动用户当前的画质 / 编码 / 音轨选择；获取期间若已切到别的视频则丢弃结果。
         *
         * @throws Exception 获取失败或超时（[TimeoutCancellationException]）时抛出
         */
        private suspend fun reloadPlayData() {
            val state = _uiState.value
            val fresh =
                withTimeout(PLAY_DATA_REFRESH_TIMEOUT_MS) {
                    fetchPlayData(state.aid, state.cid, state.epid ?: 0, getApiType())
                }
            // 接口偶尔会返回不含任何视频流的结果（如触发风控）：当作失败，保留旧数据
            check(fresh.dashVideos.isNotEmpty()) { "未获取到可用的播放地址" }
            if (_uiState.value.cid == state.cid) playData = fresh
        }

        /**
         * 尽力重新获取播放地址，失败时保留旧数据。
         *
         * @return 是否成功换到了新数据
         */
        private suspend fun refreshPlayDataQuietly(): Boolean =
            try {
                reloadPlayData()
                true
            } catch (e: TimeoutCancellationException) {
                logger.warn { "Refresh play data timed out" }
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn { "Refresh play data failed: $e" }
                false
            }

        /**
         * 当前这批播放地址是否已过期（或即将过期）。
         *
         * B 站播放地址的 `deadline` 参数是过期时间（秒级时间戳）；地址里没有该参数时无法判断，返回 `false`。
         */
        private fun isPlayDataExpired(): Boolean {
            val url = videoCdnCandidates.getOrNull(videoCdnIndex) ?: return false
            val deadline = playUrlDeadlineSeconds(url) ?: return false
            return System.currentTimeMillis() / 1000 >= deadline - PLAY_URL_EXPIRY_MARGIN_S
        }

        /**
         * 手动切换视频线路（播放器菜单「线路」）。
         *
         * 音频尽量跟随到同一节点。错误态下重选当前线路也会重新加载，相当于指定线路重试。
         *
         * @param index 目标线路在 [PlayerUiState.cdnLines] 中的下标
         */
        private fun selectCdnLine(index: Int) {
            val player = videoPlayer ?: return
            if (index !in videoCdnCandidates.indices) return
            val inError = _uiState.value.playerState is PlayerState.Error
            if (index == videoCdnIndex && !inError) return

            cancelRecovery()
            resetFallbackState()
            videoCdnIndex = index
            val host = cdnHostOf(videoCdnCandidates[index])
            audioCdnCandidates
                .indexOfFirst { cdnHostOf(it) == host }
                .takeIf { it >= 0 }
                ?.let { audioCdnIndex = it }
            logger.info { "Manual CDN line #${index + 1} -> ${videoCdnCandidates[index]}" }
            replayCurrentCandidates(player.currentPosition)
        }

        // ── 卡顿看门狗 ────────────────────────────────────────────

        /**
         * 启动卡顿看门狗（进入缓冲态 / 换源时调用，就绪 / 出错 / 结束时停止）。
         *
         * 起播测速只能说明节点「起步快不快」，有的节点长连接一段时间后吞吐会塌陷，
         * 表现为长时间转圈却迟迟不报错。看门狗每个周期检查一次缓冲是否还在推进：
         * 几乎不动就判定线路塌陷，主动切到下一条线路；仍在推进（只是网速慢）则继续等，
         * 避免换线路白白丢掉已缓冲的数据。线路只向后切，候选用尽即停，不会来回横跳。
         */
        private fun startStallWatch() {
            if (stallWatchJob?.isActive == true) return
            val initialBuffered = videoPlayer?.bufferedPosition ?: return
            stallWatchJob =
                viewModelScope.launch {
                    var lastBuffered = initialBuffered
                    while (isActive) {
                        delay(STALL_SWITCH_TIMEOUT_MS)
                        val player = videoPlayer ?: return@launch
                        if (_uiState.value.playerState is PlayerState.Error) return@launch
                        // 用户主动暂停时的缓冲不算卡顿
                        if (!player.isPlayRequested) return@launch
                        val buffered = player.bufferedPosition
                        if (buffered - lastBuffered >= STALL_MIN_PROGRESS_MS) {
                            lastBuffered = buffered
                            continue
                        }
                        switchLineOnStall(player)
                        return@launch
                    }
                }
        }

        private fun stopStallWatch() {
            stallWatchJob?.cancel()
            stallWatchJob = null
        }

        /**
         * 当前线路吞吐塌陷：标记该节点并切到下一条视频线路；没有下一条时保持现状。
         *
         * 音频若在同一节点上则一并切走——缓冲进度取音视频中较慢的一条，卡住的可能正是音频。
         */
        private fun switchLineOnStall(player: AbstractVideoPlayer) {
            val current = videoCdnCandidates.getOrNull(videoCdnIndex) ?: return
            if (videoCdnIndex + 1 >= videoCdnCandidates.size) return
            val audioFollows = isAudioOnVideoHost() && audioCdnIndex + 1 < audioCdnCandidates.size
            cdnSelector.reportSlow(current)
            videoCdnIndex++
            if (audioFollows) audioCdnIndex++
            // 本协程即看门狗自身：先摘掉引用，replayMedia 才能为新线路重新启动看门狗
            stallWatchJob = null
            announceLineSwitch("当前线路卡顿，已切换到备用线路")
            replayCurrentCandidates(player.currentPosition)
        }

        private fun findNextPlayTarget(): NextPlayTarget? {
            val state = _uiState.value
            val videoList = videoInfoRepository.videoList.value
            val index = videoList.indexOfFirst { it.aid == state.aid }
            if (index == -1) return null

            val current = videoList.getOrNull(index)
            if (current?.ugcPages?.isNotEmpty() == true) {
                val innerIndex = current.ugcPages.indexOfFirst { it.cid == state.cid }
                if (innerIndex != -1 && innerIndex + 1 < current.ugcPages.size) {
                    return NextPlayTarget.UgcPage(current, current.ugcPages[innerIndex + 1])
                }
            }
            if (index + 1 < videoList.size) return NextPlayTarget.VideoItem(videoList[index + 1])
            return null
        }

        private fun findPreviousPlayTarget(): NextPlayTarget? {
            val state = _uiState.value
            val videoList = videoInfoRepository.videoList.value
            val index = videoList.indexOfFirst { it.aid == state.aid }
            if (index == -1) return null

            val current = videoList.getOrNull(index)
            if (current?.ugcPages?.isNotEmpty() == true) {
                val innerIndex = current.ugcPages.indexOfFirst { it.cid == state.cid }
                if (innerIndex > 0) return NextPlayTarget.UgcPage(current, current.ugcPages[innerIndex - 1])
            }
            if (index > 0) {
                val prev = videoList[index - 1]
                val prevLastPage = prev.ugcPages?.lastOrNull()
                return if (prevLastPage != null) {
                    NextPlayTarget.UgcPage(prev, prevLastPage)
                } else {
                    NextPlayTarget.VideoItem(prev)
                }
            }
            return null
        }

        private fun startNextEpisodeCountdown(target: NextPlayTarget) {
            playNextCountdownJob?.cancel()
            playNextCountdownJob =
                viewModelScope.launch {
                    _uiState.update { it.copy(showSkipToNextEp = true) }
                    delay(PlayerConstants.COUNTDOWN_DURATION_MS)
                    playNextTarget(target)
                    _uiState.update { it.copy(showSkipToNextEp = false) }
                }
        }

        private fun startShowPreviewTipCountdown() {
            previewTipCountdownJob?.cancel()
            previewTipCountdownJob =
                viewModelScope.launch {
                    _uiState.update { it.copy(showPreviewTip = true) }
                    delay(PlayerConstants.COUNTDOWN_DURATION_MS)
                    _uiState.update { it.copy(showPreviewTip = false) }
                }
        }

        private fun playNextTarget(target: NextPlayTarget) {
            when (target) {
                is NextPlayTarget.UgcPage ->
                    playNewVideo(
                        VideoListItem(aid = target.parentVideo.aid, cid = target.page.cid, title = target.title),
                    )
                is NextPlayTarget.VideoItem ->
                    playNewVideo(
                        VideoListItem(
                            aid = target.video.aid,
                            cid = target.video.cid,
                            title = target.title,
                            epid = target.video.epid,
                            seasonId = target.video.seasonId,
                        ),
                    )
            }
        }

        /** 将 DataApiType 映射为 bili-api 的 ApiType。 */
        private fun getApiType(): ApiType = if (Prefs.apiType == DataApiType.App) ApiType.App else ApiType.Web

        private sealed interface NextPlayTarget {
            val title: String

            data class UgcPage(
                val parentVideo: VideoListItem,
                val page: VideoPage,
            ) : NextPlayTarget {
                override val title: String = page.title
            }

            data class VideoItem(
                val video: VideoListItem,
            ) : NextPlayTarget {
                override val title: String = video.title
            }
        }

        private data class PlaybackConfig(
            val qn: Int,
            val codec: VideoCodec?,
            val audio: Audio,
        )

        private data class MediaUrls(
            val videoUrl: String,
            val audioUrl: String?,
        )

        /** 出错的是哪条流。 */
        private enum class FailedTrack {
            Video,
            Audio,

            /** 无法从异常中判断（非 HTTP 层错误，或地址对不上当前候选）。 */
            Unknown,
        }

        /** 面向用户的错误提示，能识别出技术原因时附在括号里（如 `HTTP 403`），便于反馈定位。 */
        private fun Throwable.toDetailedErrorMessage(): String {
            val message = toPlaybackErrorMessage()
            val detail = toPlaybackErrorDetail() ?: return message
            return "$message（$detail）"
        }

        override fun onCleared() {
            // 退出页面时才真正释放播放器（进入详情等覆盖页面不会走到这里）
            detachPlayer()
            super.onCleared()
            // 不取消 detachedWorkScope：退出前的最后一次进度上报需要在 VM 清理后继续完成，由超时兜底
        }
    }

/**
 * 生成起播自动选流因设备解码能力受限而降档时的提示文案。
 *
 * 只在「服务端提供、但本机解不了」时提示：
 * - 画质：[requestedQualityId] 取自服务端可用画质，选中画质低于它说明目标画质因能力被放弃；
 * - 编码：偏好编码在选中画质下可选却未被选中，说明是能力限制而非服务端缺档。
 *
 * 服务端本身不提供的画质 / 编码不提示，避免把资源缺失误报成设备限制。
 *
 * @param requestedQualityId 服务端可用且最接近用户偏好的画质（qn）
 * @param selected 能力感知选流选中的组合；null 表示能力判定无可用组合（交由运行时回退提示）
 * @param preferredCodec 用户偏好的编码
 * @param targetQualityCodecs 选中画质下服务端提供的编码
 * @return 提示文案；无需提示时返回 null
 */
internal fun decodeFallbackNotice(
    requestedQualityId: Int,
    selected: PlaybackCandidate?,
    preferredCodec: VideoCodec,
    targetQualityCodecs: List<VideoCodec>,
): String? {
    if (selected == null) return null
    val qualityDowngraded = selected.quality < requestedQualityId
    val codecDowngraded = selected.codec != preferredCodec && preferredCodec in targetQualityCodecs
    if (!qualityDowngraded && !codecDowngraded) return null

    val unsupported =
        buildList {
            if (qualityDowngraded) add(Resolution.fromCode(requestedQualityId).displayName)
            if (codecDowngraded) add(preferredCodec.displayName)
        }
    val actual =
        buildList {
            if (qualityDowngraded) add(Resolution.fromCode(selected.quality).displayName)
            if (codecDowngraded) add(selected.codec.displayName)
        }
    return "当前设备不支持 ${unsupported.joinToString(" · ")}，已切换到 ${actual.joinToString(" · ")}"
}
