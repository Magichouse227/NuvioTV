package com.nuvio.tv.data.repository

import android.net.Uri
import android.os.Build
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.diagnostics.DiagnosticLog
import com.nuvio.tv.core.diagnostics.DiagnosticReportStore
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.nuvio.tv.data.remote.dto.PlaybackIssueAppDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueContentDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueDeviceDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueDiagnosticsDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueErrorDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueLoadingDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueLoadingEventDto
import com.nuvio.tv.data.remote.dto.PlaybackIssuePlayerDto
import com.nuvio.tv.data.remote.dto.PlaybackIssuePlaybackAnalyticsDto
import com.nuvio.tv.data.remote.dto.PlaybackIssuePlaybackEventDto
import com.nuvio.tv.data.remote.dto.PlaybackIssuePlaybackFormatDto
import com.nuvio.tv.data.remote.dto.PlaybackIssuePlaybackHealthSnapshotDto
import com.nuvio.tv.data.remote.dto.PlaybackIssuePlaybackLoadDto
import com.nuvio.tv.data.remote.dto.PlaybackIssuePlaybackLoadErrorDto
import com.nuvio.tv.data.remote.dto.PlaybackIssuePlaybackSettingsDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueReportRequestDto
import com.nuvio.tv.data.remote.dto.PlaybackIssueStreamDto
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

data class PlaybackIssueReportInput(
    val diagnostics: LastPlaybackDiagnostics,
    val error: PlaybackIssueErrorInput,
    val title: String?,
    val contentName: String?,
    val contentId: String?,
    val contentType: String?,
    val videoId: String?,
    val season: Int?,
    val episode: Int?,
    val episodeTitle: String?,
    val releaseYear: String?,
    val streamUrl: String,
    val streamMimeType: String?,
    val streamName: String?,
    val addonName: String?,
    val videoHash: String?,
    val videoSize: Long?,
    val requestHeaders: Map<String, String>,
    val responseHeaders: Map<String, String>,
    val playerEngine: String,
    val loading: PlaybackIssueLoadingInput,
    val positionMs: Long?,
    val durationMs: Long?,
    val bufferedPositionMs: Long?,
    val selectedAudioTrack: String?,
    val selectedSubtitleTrack: String?,
    val isTorrentStream: Boolean,
    val playbackSettings: PlaybackIssuePlaybackSettingsInput?,
    val playbackAnalytics: PlaybackIssuePlaybackAnalyticsInput?
)

data class PlaybackIssueLoadingInput(
    val phase: String,
    val message: String?,
    val progress: Float?,
    val elapsedMs: Long,
    val phaseElapsedMs: Long,
    val reportReason: String,
    val loadingOverlayVisible: Boolean,
    val loadingStatusVisible: Boolean,
    val hasRenderedFirstFrame: Boolean,
    val exoPlayerCreated: Boolean,
    val exoPlaybackState: Int?,
    val exoPlaybackStateName: String?,
    val exoIsLoading: Boolean?,
    val exoPlayWhenReady: Boolean?,
    val mpvAttached: Boolean,
    val startupRetryCount: Int,
    val errorRetryCount: Int,
    val timeoutRecoveryAttempts: Int,
    val isLoadingAddonSubtitles: Boolean,
    val addonSubtitlesCount: Int,
    val isLoadingSourceStreams: Boolean,
    val isLoadingEpisodeStreams: Boolean,
    val torrentDownloadSpeed: Long,
    val torrentPeers: Int,
    val torrentSeeds: Int,
    val rawEventLines: List<String>,
    val events: List<PlaybackIssueLoadingEventInput>
)

data class PlaybackIssueLoadingEventInput(
    val timeMs: Long,
    val elapsedMs: Long,
    val phase: String,
    val message: String?,
    val progress: Float?,
    val detail: String?
)

data class PlaybackIssueErrorInput(
    val displayMessage: String?,
    val errorCode: Int?,
    val errorCodeName: String?,
    val exceptionClass: String?,
    val causeClass: String?,
    val causeMessage: String?,
    val httpStatus: Int?
)

data class PlaybackIssuePlaybackSettingsInput(
    val playerPreference: String,
    val internalPlayerEngine: String,
    val resolvedInternalPlayerEngine: String,
    val autoSwitchInternalPlayerOnError: Boolean,
    val decoderPriority: Int,
    val decoderPriorityName: String,
    val effectiveDecoderPriority: Int,
    val effectiveDecoderPriorityName: String,
    val downmixEnabled: Boolean,
    val audioOutputChannels: String,
    val maintainOriginalAudioOnDownmix: Boolean,
    val tunnelingEnabled: Boolean,
    val tunnelingEffective: Boolean,
    val forceOpticalPassthrough: Boolean,
    val skipSilence: Boolean,
    val audioAmplificationDb: Int,
    val centerMixLevelDb: Int,
    val persistAudioAmplification: Boolean,
    val rememberAudioDelayPerDevice: Boolean,
    val preferredAudioLanguage: String,
    val secondaryPreferredAudioLanguage: String?,
    val preferredSubtitleLanguage: String,
    val secondaryPreferredSubtitleLanguage: String?,
    val useForcedSubtitles: Boolean,
    val showOnlyPreferredSubtitleLanguages: Boolean,
    val useLibass: Boolean,
    val activePlayerUsesLibass: Boolean,
    val libassRenderType: String,
    val addonSubtitleStartupMode: String = "SIDECAR",
    val externalPlayerForwardSubtitles: Boolean,
    val subtitleOrganizationMode: String,
    val loadingOverlayEnabled: Boolean,
    val showPlayerLoadingStatus: Boolean,
    val playbackIssueReportsEnabled: Boolean,
    val dv5ToDv81Enabled: Boolean,
    val dv7ToDv81PreserveMappingEnabled: Boolean,
    val dv7HandlingMode: String,
    val dv7LibdoviModeOverride: Int,
    val stripHdr10PlusSei: Boolean,
    val mpvHardwareDecodeMode: String,
    val frameRateMatchingMode: String,
    val resolutionMatchingEnabled: Boolean,
    val resizeMode: Int,
    val aspectMode: String,
    val bufferEngineEnabled: Boolean,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val targetBufferSizeMb: Int,
    val backBufferDurationMs: Int,
    val effectiveBackBufferDurationMs: Int,
    val retainBackBufferFromKeyframe: Boolean,
    val parallelNetworkEnabled: Boolean,
    val bufferBudgetManaged: Boolean,
    val allowLargeTargetBuffer: Boolean,
    val vodCacheEnabled: Boolean,
    val vodCacheSizeMode: String,
    val vodCacheSizeMb: Int,
    val useParallelConnections: Boolean,
    val parallelConnectionCount: Int,
    val parallelChunkSizeKb: Int,
    val enableHttp2: Boolean,
    val nuvioPerformanceModeEnabled: Boolean,
    val streamAutoPlayMode: String,
    val streamAutoPlaySource: String,
    val streamAutoPlayNextEpisodeEnabled: Boolean,
    val streamAutoPlayPreferBingeGroupForNextEpisode: Boolean,
    val streamAutoPlayReuseBingeGroup: Boolean,
    val streamAutoPlayTimeoutSeconds: Int,
    val stillWatchingEnabled: Boolean,
    val stillWatchingEpisodeThreshold: Int,
    val nextEpisodeThresholdMode: String,
    val nextEpisodeThresholdPercent: Float,
    val nextEpisodeThresholdMinutesBeforeEnd: Float,
    val streamReuseLastLinkEnabled: Boolean,
    val streamReuseLastLinkCacheHours: Int
)

@Singleton
class PlaybackIssueReportRepository @Inject constructor(
    private val reportStore: DiagnosticReportStore
) {
    suspend fun submit(input: PlaybackIssueReportInput): Result<String> = runCatching {
        val error = input.error
        val summary = listOfNotNull(
            error.exceptionClass?.takeIf { it.isNotBlank() },
            error.errorCodeName?.takeIf { it.isNotBlank() },
            input.loading.reportReason.takeIf { it.isNotBlank() }
        ).joinToString(" / ").ifBlank { "Playback or loading issue" }
        val safeState = buildList {
            // This walks the existing DTO schema rather than a second, drifting report model.
            // It exports every numeric/boolean field and only explicitly named enum/format
            // strings; identifiers, messages, URLs, headers, raw events, and maps are omitted.
            addAll(input.toDto().allowlistedDiagnosticLines())
            add("player.engine=${input.playerEngine}")
            add("player.positionMs=${input.positionMs ?: -1} durationMs=${input.durationMs ?: -1} bufferedPositionMs=${input.bufferedPositionMs ?: -1}")
            add("loading.phase=${input.loading.phase} elapsedMs=${input.loading.elapsedMs} phaseElapsedMs=${input.loading.phaseElapsedMs} firstFrame=${input.loading.hasRenderedFirstFrame} exoCreated=${input.loading.exoPlayerCreated} exoState=${input.loading.exoPlaybackState} exoLoading=${input.loading.exoIsLoading} mpvAttached=${input.loading.mpvAttached}")
            add("loading.retries startup=${input.loading.startupRetryCount} error=${input.loading.errorRetryCount} timeout=${input.loading.timeoutRecoveryAttempts}")
            add("error.class=${error.exceptionClass.orEmpty()} causeClass=${error.causeClass.orEmpty()} code=${error.errorCodeName.orEmpty()} httpStatus=${error.httpStatus ?: -1}")
            addAll(input.diagnostics.toPlaybackSnapshotProvenanceLines())
            add("diagnostics.video=${input.diagnostics.videoResolution.orEmpty()} codec=${input.diagnostics.videoCodec.orEmpty()} hdr=${input.diagnostics.videoHdrType.orEmpty()} rebufferCount=${input.diagnostics.rebufferCount} rebufferTotalMs=${input.diagnostics.rebufferTotalMs} firstFrameMs=${input.diagnostics.firstFrameMs}")
            input.playbackSettings?.let { settings ->
                add("settings.engine=${settings.resolvedInternalPlayerEngine} preference=${settings.playerPreference} decoder=${settings.effectiveDecoderPriorityName} tunneling=${settings.tunnelingEffective} downmix=${settings.downmixEnabled} libass=${settings.activePlayerUsesLibass} frameRate=${settings.frameRateMatchingMode} resolutionMatching=${settings.resolutionMatchingEnabled}")
                add("settings.buffer min=${settings.minBufferMs} max=${settings.maxBufferMs} playback=${settings.bufferForPlaybackMs} rebuffer=${settings.bufferForPlaybackAfterRebufferMs} targetMb=${settings.targetBufferSizeMb} backBuffer=${settings.effectiveBackBufferDurationMs} parallel=${settings.parallelNetworkEnabled}/${settings.parallelConnectionCount} http2=${settings.enableHttp2}")
                add("settings.dv dv5to81=${settings.dv5ToDv81Enabled} dv7mode=${settings.dv7HandlingMode} stripHdr10Plus=${settings.stripHdr10PlusSei} hwdecode=${settings.mpvHardwareDecodeMode} aspect=${settings.aspectMode}")
            }
            input.playbackAnalytics?.let { analytics ->
                add("analytics.state=${analytics.playbackStateName.orEmpty()} loading=${analytics.isLoading} playing=${analytics.isPlaying} positionMs=${analytics.positionMs} durationMs=${analytics.durationMs} bufferedMs=${analytics.bufferedPositionMs} droppedFrames=${analytics.droppedFrames} loadErrors=${analytics.loadErrorCount} bytes=${analytics.totalBytesLoaded} audioUnderruns=${analytics.audioUnderrunCount}")
                analytics.videoFormat?.let { format ->
                    add("video_format type=${format.trackType} mime=${format.sampleMimeType} container=${format.containerMimeType} codecs=${format.codecs} width=${format.width} height=${format.height} fps=${format.frameRate} bitrate=${format.bitrate}")
                }
                analytics.audioFormat?.let { format ->
                    add("audio_format type=${format.trackType} mime=${format.sampleMimeType} container=${format.containerMimeType} codecs=${format.codecs} channels=${format.channelCount} sampleRate=${format.sampleRate}")
                }
                analytics.events.takeLast(80).forEach { event ->
                    add("playback_event name=${event.name} elapsedMs=${event.elapsedMs} state=${event.playbackState} positionMs=${event.positionMs} bufferedMs=${event.bufferedPositionMs}")
                }
                analytics.healthSnapshots.takeLast(40).forEach { snapshot ->
                    add("health elapsedMs=${snapshot.elapsedMs} state=${snapshot.playbackState} positionMs=${snapshot.positionMs} bufferedMs=${snapshot.bufferedPositionMs} dropped=${snapshot.droppedFrames} rebuffer=${snapshot.rebufferCount} loadErrors=${snapshot.loadErrorCount}")
                }
            }
            // Do not export event messages/details/raw event lines: those are arbitrary external
            // strings. This export is a deliberately allowlisted structured diagnostic DTO.
            addAll(input.loading.events.takeLast(20).map {
                "loading_event phase=${it.phase} elapsedMs=${it.elapsedMs} progress=${it.progress}"
            })
        }
        val report = reportStore.enqueue(
            type = "playback_issue",
            summary = summary,
            body = reportStore.buildReport("playback_issue", summary, safeState)
        )
        DiagnosticLog.record("playback_report", "Saved report id=${report.id.take(12)} type=playback_issue")
        report.id
    }

    private fun PlaybackIssueReportInput.toDto(): PlaybackIssueReportRequestDto {
        val streamUri = runCatching { Uri.parse(streamUrl) }.getOrNull()
        val urlWithoutQuery = streamUri?.withoutQueryAndFragment()
        return PlaybackIssueReportRequestDto(
            schemaVersion = 1,
            createdAtMs = System.currentTimeMillis(),
            app = PlaybackIssueAppDto(
                applicationId = BuildConfig.APPLICATION_ID,
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                debugBuild = BuildConfig.IS_DEBUG_BUILD
            ),
            device = PlaybackIssueDeviceDto(
                manufacturer = Build.MANUFACTURER.orEmpty().limit(80),
                brand = Build.BRAND.orEmpty().limit(80),
                model = Build.MODEL.orEmpty().limit(120),
                product = Build.PRODUCT.orEmpty().limit(120),
                androidRelease = Build.VERSION.RELEASE.orEmpty().limit(40),
                sdkInt = Build.VERSION.SDK_INT,
                supportedAbis = Build.SUPPORTED_ABIS.orEmpty().map { it.limit(40) }
            ),
            content = PlaybackIssueContentDto(
                title = title.cleanText(160),
                contentName = contentName.cleanText(160),
                contentId = contentId.cleanText(160),
                contentType = contentType.cleanText(60),
                videoId = videoId.cleanText(160),
                season = season,
                episode = episode,
                episodeTitle = episodeTitle.cleanText(160),
                releaseYear = releaseYear.cleanText(20)
            ),
            stream = PlaybackIssueStreamDto(
                host = streamUri?.host.cleanText(160) ?: diagnostics.host.cleanText(160),
                scheme = streamUri?.scheme.cleanText(24),
                port = streamUri?.port?.takeIf { it >= 0 },
                urlHash = streamUrl.sha256OrNull(),
                urlWithoutQueryHash = urlWithoutQuery.sha256OrNull(),
                fileExtension = streamUri.fileExtension(),
                mimeType = streamMimeType.cleanText(120),
                streamName = streamName.cleanText(160),
                addonName = addonName.cleanText(120),
                videoHash = videoHash.cleanText(160),
                videoSize = videoSize,
                requestHeaderNames = requestHeaders.safeHeaderNames(),
                responseHeaderNames = responseHeaders.safeHeaderNames()
            ),
            player = PlaybackIssuePlayerDto(
                engine = playerEngine.limit(80),
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedPositionMs = bufferedPositionMs,
                audioTrack = selectedAudioTrack.cleanText(160),
                subtitleTrack = selectedSubtitleTrack.cleanText(160),
                isTorrentStream = isTorrentStream
            ),
            loading = loading.toDto(),
            error = PlaybackIssueErrorDto(
                displayMessage = error.displayMessage.cleanText(1000),
                errorCode = error.errorCode,
                errorCodeName = error.errorCodeName.cleanText(120),
                exceptionClass = error.exceptionClass.cleanText(160),
                causeClass = error.causeClass.cleanText(160),
                causeMessage = error.causeMessage.cleanText(1000),
                httpStatus = error.httpStatus
            ),
            diagnostics = diagnostics.toDto(),
            playbackSettings = playbackSettings?.toDto(),
            playbackAnalytics = playbackAnalytics?.toDto()
        )
    }

    private fun PlaybackIssueLoadingInput.toDto(): PlaybackIssueLoadingDto =
        PlaybackIssueLoadingDto(
            phase = phase.limit(80),
            message = message.cleanText(240),
            progress = progress?.coerceIn(0f, 1f),
            elapsedMs = elapsedMs.coerceAtLeast(0L),
            phaseElapsedMs = phaseElapsedMs.coerceAtLeast(0L),
            reportReason = reportReason.limit(80),
            loadingOverlayVisible = loadingOverlayVisible,
            loadingStatusVisible = loadingStatusVisible,
            hasRenderedFirstFrame = hasRenderedFirstFrame,
            exoPlayerCreated = exoPlayerCreated,
            exoPlaybackState = exoPlaybackState,
            exoPlaybackStateName = exoPlaybackStateName.cleanText(80),
            exoIsLoading = exoIsLoading,
            exoPlayWhenReady = exoPlayWhenReady,
            mpvAttached = mpvAttached,
            startupRetryCount = startupRetryCount.coerceAtLeast(0),
            errorRetryCount = errorRetryCount.coerceAtLeast(0),
            timeoutRecoveryAttempts = timeoutRecoveryAttempts.coerceAtLeast(0),
            isLoadingAddonSubtitles = isLoadingAddonSubtitles,
            addonSubtitlesCount = addonSubtitlesCount.coerceAtLeast(0),
            isLoadingSourceStreams = isLoadingSourceStreams,
            isLoadingEpisodeStreams = isLoadingEpisodeStreams,
            torrentDownloadSpeed = torrentDownloadSpeed.coerceAtLeast(0L),
            torrentPeers = torrentPeers.coerceAtLeast(0),
            torrentSeeds = torrentSeeds.coerceAtLeast(0),
            rawEventLines = rawEventLines.takeLast(120).mapNotNull { it.rawLogLine(2000) },
            events = events.takeLast(80).map { event ->
                PlaybackIssueLoadingEventDto(
                    timeMs = event.timeMs,
                    elapsedMs = event.elapsedMs.coerceAtLeast(0L),
                    phase = event.phase.limit(80),
                    message = event.message.cleanText(240),
                    progress = event.progress?.coerceIn(0f, 1f),
                    detail = event.detail.cleanText(240)
                )
            }
        )

    private fun LastPlaybackDiagnostics.toDto(): PlaybackIssueDiagnosticsDto =
        PlaybackIssueDiagnosticsDto(
            timestampMs = timestampMs,
            host = host.limit(160),
            hdrCapsKnown = hdrCapsKnown,
            displayDv = displayDv,
            displayHdr10 = displayHdr10,
            displayHdr10Plus = displayHdr10Plus,
            codecDv7Supported = codecDv7Supported,
            dv81DecoderName = dv81DecoderName.cleanText(160),
            bridgeReady = bridgeReady,
            bridgeVersion = bridgeVersion.cleanText(120),
            bridgeReason = bridgeReason.cleanText(180),
            dv7ModeRequested = dv7ModeRequested.limit(80),
            dv7ModeEffective = dv7ModeEffective.limit(80),
            dv7AutoDecision = dv7AutoDecision.cleanText(80),
            bufferEngineEnabled = bufferEngineEnabled,
            parallelNetworkEnabled = parallelNetworkEnabled,
            firstFrameMs = firstFrameMs,
            dv7DoviCalls = dv7DoviCalls,
            dv7DoviSuccess = dv7DoviSuccess,
            dv7DoviSignalRewrites = dv7DoviSignalRewrites,
            dvSourceProfile = dvSourceProfile.cleanText(80),
            videoResolution = videoResolution.cleanText(80),
            videoCodec = videoCodec.cleanText(120),
            videoHdrType = videoHdrType.cleanText(120),
            rebufferCount = rebufferCount,
            rebufferTotalMs = rebufferTotalMs,
            result = result.limit(1000)
        )

    private fun PlaybackIssuePlaybackSettingsInput.toDto(): PlaybackIssuePlaybackSettingsDto =
        PlaybackIssuePlaybackSettingsDto(
            playerPreference = playerPreference.limit(60),
            internalPlayerEngine = internalPlayerEngine.limit(60),
            resolvedInternalPlayerEngine = resolvedInternalPlayerEngine.limit(60),
            autoSwitchInternalPlayerOnError = autoSwitchInternalPlayerOnError,
            decoderPriority = decoderPriority.coerceIn(0, 2),
            decoderPriorityName = decoderPriorityName.limit(80),
            effectiveDecoderPriority = effectiveDecoderPriority.coerceIn(0, 2),
            effectiveDecoderPriorityName = effectiveDecoderPriorityName.limit(80),
            downmixEnabled = downmixEnabled,
            audioOutputChannels = audioOutputChannels.limit(40),
            maintainOriginalAudioOnDownmix = maintainOriginalAudioOnDownmix,
            tunnelingEnabled = tunnelingEnabled,
            tunnelingEffective = tunnelingEffective,
            forceOpticalPassthrough = forceOpticalPassthrough,
            skipSilence = skipSilence,
            audioAmplificationDb = audioAmplificationDb.coerceIn(0, 10),
            centerMixLevelDb = centerMixLevelDb.coerceIn(-10, 30),
            persistAudioAmplification = persistAudioAmplification,
            rememberAudioDelayPerDevice = rememberAudioDelayPerDevice,
            preferredAudioLanguage = preferredAudioLanguage.limit(40),
            secondaryPreferredAudioLanguage = secondaryPreferredAudioLanguage.cleanText(40),
            preferredSubtitleLanguage = preferredSubtitleLanguage.limit(40),
            secondaryPreferredSubtitleLanguage = secondaryPreferredSubtitleLanguage.cleanText(40),
            useForcedSubtitles = useForcedSubtitles,
            showOnlyPreferredSubtitleLanguages = showOnlyPreferredSubtitleLanguages,
            useLibass = useLibass,
            activePlayerUsesLibass = activePlayerUsesLibass,
            libassRenderType = libassRenderType.limit(80),
            addonSubtitleStartupMode = addonSubtitleStartupMode.limit(80),
            externalPlayerForwardSubtitles = externalPlayerForwardSubtitles,
            subtitleOrganizationMode = subtitleOrganizationMode.limit(80),
            loadingOverlayEnabled = loadingOverlayEnabled,
            showPlayerLoadingStatus = showPlayerLoadingStatus,
            playbackIssueReportsEnabled = playbackIssueReportsEnabled,
            dv5ToDv81Enabled = dv5ToDv81Enabled,
            dv7ToDv81PreserveMappingEnabled = dv7ToDv81PreserveMappingEnabled,
            dv7HandlingMode = dv7HandlingMode.limit(80),
            dv7LibdoviModeOverride = dv7LibdoviModeOverride.coerceIn(-1, 4),
            stripHdr10PlusSei = stripHdr10PlusSei,
            mpvHardwareDecodeMode = mpvHardwareDecodeMode.limit(80),
            frameRateMatchingMode = frameRateMatchingMode.limit(80),
            resolutionMatchingEnabled = resolutionMatchingEnabled,
            resizeMode = resizeMode.coerceIn(0, 4),
            aspectMode = aspectMode.limit(80),
            bufferEngineEnabled = bufferEngineEnabled,
            minBufferMs = minBufferMs.coerceAtLeast(0),
            maxBufferMs = maxBufferMs.coerceAtLeast(0),
            bufferForPlaybackMs = bufferForPlaybackMs.coerceAtLeast(0),
            bufferForPlaybackAfterRebufferMs = bufferForPlaybackAfterRebufferMs.coerceAtLeast(0),
            targetBufferSizeMb = targetBufferSizeMb.coerceAtLeast(0),
            backBufferDurationMs = backBufferDurationMs.coerceAtLeast(0),
            effectiveBackBufferDurationMs = effectiveBackBufferDurationMs.coerceAtLeast(0),
            retainBackBufferFromKeyframe = retainBackBufferFromKeyframe,
            parallelNetworkEnabled = parallelNetworkEnabled,
            bufferBudgetManaged = bufferBudgetManaged,
            allowLargeTargetBuffer = allowLargeTargetBuffer,
            vodCacheEnabled = vodCacheEnabled,
            vodCacheSizeMode = vodCacheSizeMode.limit(60),
            vodCacheSizeMb = vodCacheSizeMb.coerceAtLeast(0),
            useParallelConnections = useParallelConnections,
            parallelConnectionCount = parallelConnectionCount.coerceAtLeast(0),
            parallelChunkSizeKb = parallelChunkSizeKb.coerceAtLeast(0),
            enableHttp2 = enableHttp2,
            nuvioPerformanceModeEnabled = nuvioPerformanceModeEnabled,
            streamAutoPlayMode = streamAutoPlayMode.limit(80),
            streamAutoPlaySource = streamAutoPlaySource.limit(80),
            streamAutoPlayNextEpisodeEnabled = streamAutoPlayNextEpisodeEnabled,
            streamAutoPlayPreferBingeGroupForNextEpisode = streamAutoPlayPreferBingeGroupForNextEpisode,
            streamAutoPlayReuseBingeGroup = streamAutoPlayReuseBingeGroup,
            streamAutoPlayTimeoutSeconds = streamAutoPlayTimeoutSeconds.coerceAtLeast(0),
            stillWatchingEnabled = stillWatchingEnabled,
            stillWatchingEpisodeThreshold = stillWatchingEpisodeThreshold.coerceAtLeast(0),
            nextEpisodeThresholdMode = nextEpisodeThresholdMode.limit(80),
            nextEpisodeThresholdPercent = nextEpisodeThresholdPercent.coerceIn(0f, 100f),
            nextEpisodeThresholdMinutesBeforeEnd = nextEpisodeThresholdMinutesBeforeEnd.coerceAtLeast(0f),
            streamReuseLastLinkEnabled = streamReuseLastLinkEnabled,
            streamReuseLastLinkCacheHours = streamReuseLastLinkCacheHours.coerceAtLeast(0)
        )

    private fun PlaybackIssuePlaybackAnalyticsInput.toDto(): PlaybackIssuePlaybackAnalyticsDto =
        PlaybackIssuePlaybackAnalyticsDto(
            schemaVersion = schemaVersion,
            sessionStartedAtMs = sessionStartedAtMs,
            capturedAtMs = capturedAtMs,
            elapsedMs = elapsedMs.coerceAtLeast(0L),
            clickToFirstFrameMs = clickToFirstFrameMs?.coerceAtLeast(0L),
            initToFirstFrameMs = initToFirstFrameMs?.coerceAtLeast(0L),
            startPositionMs = startPositionMs?.coerceAtLeast(0L),
            eventCount = eventCount.coerceAtLeast(0),
            lastEventElapsedMs = lastEventElapsedMs?.coerceAtLeast(0L),
            playbackState = playbackState,
            playbackStateName = playbackStateName.cleanText(80),
            playWhenReady = playWhenReady,
            isPlaying = isPlaying,
            isLoading = isLoading,
            playbackSpeed = playbackSpeed?.takeIf { it.isFinite() && it > 0f },
            playbackPitch = playbackPitch?.takeIf { it.isFinite() && it > 0f },
            positionMs = positionMs?.coerceAtLeast(0L),
            bufferedPositionMs = bufferedPositionMs?.coerceAtLeast(0L),
            durationMs = durationMs?.coerceAtLeast(0L),
            bufferedPercentage = bufferedPercentage?.coerceIn(0, 100),
            firstFrameElapsedMs = firstFrameElapsedMs?.coerceAtLeast(0L),
            renderedFirstFrameCount = renderedFirstFrameCount.coerceAtLeast(0),
            rebufferCount = rebufferCount.coerceAtLeast(0),
            rebufferTotalMs = rebufferTotalMs.coerceAtLeast(0L),
            currentRebufferMs = currentRebufferMs.coerceAtLeast(0L),
            positionStallCount = positionStallCount.coerceAtLeast(0),
            longestPositionStallMs = longestPositionStallMs.coerceAtLeast(0L),
            droppedFrames = droppedFrames.coerceAtLeast(0),
            maxDroppedFramesInEvent = maxDroppedFramesInEvent.coerceAtLeast(0),
            videoDecoderName = videoDecoderName.cleanText(160),
            videoDecoderInitMs = videoDecoderInitMs?.coerceAtLeast(0L),
            videoDecoderReleaseCount = videoDecoderReleaseCount.coerceAtLeast(0),
            videoRenderedOutputBuffers = videoRenderedOutputBuffers?.coerceAtLeast(0),
            videoDroppedBuffers = videoDroppedBuffers?.coerceAtLeast(0),
            videoMaxConsecutiveDroppedBuffers = videoMaxConsecutiveDroppedBuffers?.coerceAtLeast(0),
            videoFrameProcessingOffsetAverageUs = videoFrameProcessingOffsetAverageUs,
            videoFormat = videoFormat?.toDto(),
            audioDecoderName = audioDecoderName.cleanText(160),
            audioDecoderInitMs = audioDecoderInitMs?.coerceAtLeast(0L),
            audioDecoderReleaseCount = audioDecoderReleaseCount.coerceAtLeast(0),
            audioUnderrunCount = audioUnderrunCount.coerceAtLeast(0),
            audioUnderrunBufferSize = audioUnderrunBufferSize?.coerceAtLeast(0),
            audioUnderrunBufferSizeMs = audioUnderrunBufferSizeMs?.coerceAtLeast(0L),
            audioUnderrunElapsedSinceLastFeedMs = audioUnderrunElapsedSinceLastFeedMs?.coerceAtLeast(0L),
            audioFormat = audioFormat?.toDto(),
            bandwidthEstimateBps = bandwidthEstimateBps?.coerceAtLeast(0L),
            bandwidthTransferDurationMs = bandwidthTransferDurationMs?.coerceAtLeast(0),
            bandwidthBytesTransferred = bandwidthBytesTransferred?.coerceAtLeast(0L),
            loadStartedCount = loadStartedCount.coerceAtLeast(0),
            loadCompletedCount = loadCompletedCount.coerceAtLeast(0),
            loadCanceledCount = loadCanceledCount.coerceAtLeast(0),
            loadErrorCount = loadErrorCount.coerceAtLeast(0),
            totalBytesLoaded = totalBytesLoaded.coerceAtLeast(0L),
            lastLoad = lastLoad?.toDto(),
            lastLoadError = lastLoadError?.toDto(),
            rawEventLines = rawEventLines.takeLast(220).mapNotNull { it.rawLogLine(2000) },
            events = events.takeLast(140).map { it.toDto() },
            rawEvents = rawEvents.takeLast(220).mapNotNull { it.rawLogLine(2000) },
            deepExoEvents = deepExoEvents.takeLast(220).map { it.toDto() },
            exoEvents = deepExoEvents.takeLast(220).map { it.toDto() },
            stutterSignals = stutterSignals.takeLast(120).map { it.toDto() },
            healthSnapshots = healthSnapshots.takeLast(80).map { it.toDto() },
            startupStages = startupStages.takeLast(80).map {
                PlaybackIssueLoadingEventDto(
                    timeMs = it.timeMs,
                    elapsedMs = it.elapsedMs.coerceAtLeast(0L),
                    phase = it.phase.limit(80),
                    message = it.message.cleanText(240),
                    progress = it.progress?.coerceIn(0f, 1f),
                    detail = it.detail.cleanText(240)
                )
            }
        )

    private fun PlaybackIssuePlaybackHealthSnapshotInput.toDto(): PlaybackIssuePlaybackHealthSnapshotDto =
        PlaybackIssuePlaybackHealthSnapshotDto(
            timeMs = timeMs,
            elapsedMs = elapsedMs.coerceAtLeast(0L),
            playbackState = playbackState.cleanText(80),
            playWhenReady = playWhenReady,
            isPlaying = isPlaying,
            isLoading = isLoading,
            playbackSpeed = playbackSpeed?.takeIf { it.isFinite() && it > 0f },
            playbackPitch = playbackPitch?.takeIf { it.isFinite() && it > 0f },
            positionMs = positionMs?.coerceAtLeast(0L),
            bufferedPositionMs = bufferedPositionMs?.coerceAtLeast(0L),
            durationMs = durationMs?.coerceAtLeast(0L),
            bufferedPercentage = bufferedPercentage?.coerceIn(0, 100),
            droppedFrames = droppedFrames.coerceAtLeast(0),
            audioUnderrunCount = audioUnderrunCount.coerceAtLeast(0),
            rebufferCount = rebufferCount.coerceAtLeast(0),
            rebufferTotalMs = rebufferTotalMs.coerceAtLeast(0L),
            bandwidthEstimateBps = bandwidthEstimateBps?.coerceAtLeast(0L),
            totalBytesLoaded = totalBytesLoaded.coerceAtLeast(0L),
            loadStartedCount = loadStartedCount.coerceAtLeast(0),
            loadCompletedCount = loadCompletedCount.coerceAtLeast(0),
            loadCanceledCount = loadCanceledCount.coerceAtLeast(0),
            loadErrorCount = loadErrorCount.coerceAtLeast(0)
        )

    private fun PlaybackIssuePlaybackFormatInput.toDto(): PlaybackIssuePlaybackFormatDto =
        PlaybackIssuePlaybackFormatDto(
            trackType = trackType.cleanText(40),
            sampleMimeType = sampleMimeType.cleanText(120),
            containerMimeType = containerMimeType.cleanText(120),
            codecs = codecs.cleanText(160),
            id = id.cleanText(160),
            label = label.cleanText(160),
            language = language.cleanText(40),
            width = width?.coerceAtLeast(0),
            height = height?.coerceAtLeast(0),
            frameRate = frameRate?.takeIf { it > 0f },
            bitrate = bitrate?.coerceAtLeast(0),
            channelCount = channelCount?.coerceAtLeast(0),
            sampleRate = sampleRate?.coerceAtLeast(0),
            colorTransfer = colorTransfer,
            selectionFlags = selectionFlags,
            roleFlags = roleFlags,
            support = support.cleanText(80),
            decoderReuseResult = decoderReuseResult.cleanText(80),
            decoderDiscardReasons = decoderDiscardReasons
        )

    private fun PlaybackIssuePlaybackLoadInput.toDto(): PlaybackIssuePlaybackLoadDto =
        PlaybackIssuePlaybackLoadDto(
            host = host.cleanText(160),
            scheme = scheme.cleanText(24),
            dataType = dataType.cleanText(80),
            trackType = trackType.cleanText(40),
            httpMethod = httpMethod.cleanText(12),
            position = position?.coerceAtLeast(0L),
            length = length?.coerceAtLeast(0L),
            durationMs = durationMs?.coerceAtLeast(0L),
            bytesLoaded = bytesLoaded?.coerceAtLeast(0L),
            responseHeaderNames = responseHeaderNames.mapNotNull { it.cleanText(80)?.lowercase() }
                .distinct()
                .sorted()
                .take(40)
        )

    private fun PlaybackIssuePlaybackLoadErrorInput.toDto(): PlaybackIssuePlaybackLoadErrorDto =
        PlaybackIssuePlaybackLoadErrorDto(
            host = host.cleanText(160),
            dataType = dataType.cleanText(80),
            trackType = trackType.cleanText(40),
            exceptionClass = exceptionClass.cleanText(160),
            message = message.cleanText(500),
            httpStatus = httpStatus,
            wasCanceled = wasCanceled,
            bytesLoaded = bytesLoaded?.coerceAtLeast(0L),
            durationMs = durationMs?.coerceAtLeast(0L)
        )

    private fun PlaybackIssuePlaybackEventInput.toDto(): PlaybackIssuePlaybackEventDto =
        PlaybackIssuePlaybackEventDto(
            timeMs = timeMs,
            elapsedMs = elapsedMs.coerceAtLeast(0L),
            name = name.limit(80),
            playbackState = playbackState.cleanText(80),
            positionMs = positionMs?.coerceAtLeast(0L),
            bufferedPositionMs = bufferedPositionMs?.coerceAtLeast(0L),
            details = details.entries
                .mapNotNull { (key, value) ->
                    key.cleanText(50)?.let { safeKey ->
                        value.cleanText(240)?.let { safeValue -> safeKey to safeValue }
                    }
                }
                .take(16)
                .toMap()
        )

    private fun Uri.withoutQueryAndFragment(): String? {
        val safeScheme = scheme?.takeIf { it.isNotBlank() } ?: return null
        val safeHost = host?.takeIf { it.isNotBlank() } ?: return null
        val authority = if (port >= 0) "$safeHost:$port" else safeHost
        return buildUpon()
            .scheme(safeScheme)
            .encodedAuthority(authority)
            .encodedQuery(null)
            .fragment(null)
            .build()
            .toString()
    }

    private fun Uri?.fileExtension(): String? {
        val segment = this?.lastPathSegment?.substringBefore('?')?.substringBefore('#')
            ?: return null
        val extension = segment.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .takeIf { it.length in 1..10 }
            ?.takeIf { it.all { c -> c.isLetterOrDigit() } }
        return extension
    }

    private fun Map<String, String>.safeHeaderNames(): List<String> =
        keys.mapNotNull { it.cleanText(80)?.lowercase() }
            .distinct()
            .sorted()
            .take(40)

    private fun String?.sha256OrNull(): String? {
        val value = this?.takeIf { it.isNotBlank() } ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun String?.cleanText(maxLength: Int): String? =
        this?.trim()
            ?.redactSensitiveText()
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotBlank() }
            ?.limit(maxLength)

    private fun String.rawLogLine(maxLength: Int): String? =
        replace('\n', ' ')
            .replace('\r', ' ')
            .trim()
            .takeIf { it.isNotBlank() }
            ?.limit(maxLength)

    private fun String.redactSensitiveText(): String =
        replace(Regex("""https?://\S+""", RegexOption.IGNORE_CASE), "[redacted-url]")
            .replace(
                Regex("""(?i)\b(authorization|proxy-authorization|cookie|set-cookie)\b\s*:\s*[^\r\n]+"""),
                "$1: [redacted]"
            )
            .replace(Regex("""(?i)\b(bearer|token|apikey|api_key)\b\s*[:=]\s*\S+"""), "$1=[redacted]")
            .replace(Regex("""(?i)\b(authorization|proxy-authorization|cookie|set-cookie)\b\s*=\s*\S+"""), "$1=[redacted]")

    private fun String.limit(maxLength: Int): String =
        if (length <= maxLength) this else take(maxLength)

    private fun PlaybackIssueReportRequestDto.allowlistedDiagnosticLines(): List<String> {
        val safeStringFields = setOf(
            "engine", "playerPreference", "internalPlayerEngine", "resolvedInternalPlayerEngine",
            "decoderPriorityName", "effectiveDecoderPriorityName", "audioOutputChannels",
            "libassRenderType", "addonSubtitleStartupMode", "subtitleOrganizationMode",
            "dv7HandlingMode", "mpvHardwareDecodeMode", "frameRateMatchingMode", "aspectMode",
            "vodCacheSizeMode", "streamAutoPlayMode", "streamAutoPlaySource",
            "nextEpisodeThresholdMode", "phase", "reportReason", "exoPlaybackStateName",
            "errorCodeName", "exceptionClass", "causeClass", "dv7ModeRequested",
            "dv7ModeEffective", "videoResolution", "videoCodec", "videoHdrType",
            "playbackStateName", "trackType", "sampleMimeType", "containerMimeType",
            "codecs", "support", "decoderReuseResult", "dataType", "httpMethod", "name",
            "playbackState"
        )
        val excludedRoots = setOf("content", "stream")
        val lines = ArrayList<String>()

        fun visit(value: Any?, path: String, fieldName: String) {
            when (value) {
                null -> Unit
                is Number, is Boolean -> lines += "$path=$value"
                is String -> if (fieldName in safeStringFields) lines += "$path=$value"
                is Iterable<*> -> value.take(120).forEachIndexed { index, item ->
                    visit(item, "$path[$index]", fieldName)
                }
                is Map<*, *> -> Unit // Event details and header maps can carry arbitrary data.
                else -> if (value.javaClass.name.startsWith("com.nuvio.tv.data.remote.dto.")) {
                    value.javaClass.declaredFields
                        .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }
                        .forEach { field ->
                            if (path.isEmpty() && field.name in excludedRoots) return@forEach
                            runCatching {
                                field.isAccessible = true
                                visit(field.get(value), if (path.isEmpty()) field.name else "$path.${field.name}", field.name)
                            }
                        }
                }
            }
        }

        visit(this, "", "")
        return lines.takeLast(900)
    }
}
