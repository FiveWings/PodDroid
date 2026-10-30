package com.example.media

import com.example.model.MediaCommand
import com.example.model.WebMediaMetadata
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Shared singleton bridge connecting the WebView JS audio engine, Jetpack Compose UI,
 * and the Android Foreground MediaSession Service.
 */
object MediaBridgeController {

    private val _mediaState = MutableStateFlow(WebMediaMetadata())
    val mediaState: StateFlow<WebMediaMetadata> = _mediaState.asStateFlow()

    private val _commands = MutableSharedFlow<MediaCommand>(extraBufferCapacity = 16)
    val commands: SharedFlow<MediaCommand> = _commands.asSharedFlow()

    private val _autofillLoginDetected = MutableStateFlow<Pair<Boolean, String>>(false to "")
    val autofillLoginDetected: StateFlow<Pair<Boolean, String>> = _autofillLoginDetected.asStateFlow()

    private val _capturedCredentialsToSave = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 4)
    val capturedCredentialsToSave: SharedFlow<Pair<String, String>> = _capturedCredentialsToSave.asSharedFlow()

    fun updateFromWeb(
        title: String,
        artist: String,
        album: String,
        artworkUrl: String,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
        playbackRate: Float,
        pageUrl: String
    ) {
        _mediaState.update { current ->
            val resolvedTitle = title.takeIf { it.isNotBlank() } ?: current.title
            val resolvedArtist = artist.takeIf { it.isNotBlank() } ?: current.artist
            val resolvedArtwork = artworkUrl.takeIf { it.isNotBlank() } ?: current.artworkUrl
            current.copy(
                title = resolvedTitle,
                artist = resolvedArtist,
                album = album.takeIf { it.isNotBlank() } ?: "Apple Podcasts",
                artworkUrl = resolvedArtwork,
                isPlaying = isPlaying,
                positionMs = positionMs.coerceAtLeast(0L),
                durationMs = durationMs.coerceAtLeast(0L),
                playbackRate = if (playbackRate > 0f) playbackRate else 1.0f,
                hasActiveMedia = true,
                pageUrl = pageUrl.takeIf { it.isNotBlank() } ?: current.pageUrl
            )
        }
    }

    fun dispatchCommand(command: MediaCommand) {
        _commands.tryEmit(command)
        // Optimistically update local state for snappy UI & System Media Notification response
        _mediaState.update { current ->
            when (command) {
                MediaCommand.Play -> current.copy(isPlaying = true, hasActiveMedia = true)
                MediaCommand.Pause -> current.copy(isPlaying = false)
                MediaCommand.TogglePlayPause -> current.copy(isPlaying = !current.isPlaying, hasActiveMedia = true)
                is MediaCommand.SeekTo -> current.copy(
                    positionMs = command.positionMs.coerceIn(0L, current.durationMs.coerceAtLeast(command.positionMs))
                )
                is MediaCommand.SkipForward -> current.copy(
                    positionMs = (current.positionMs + command.seconds * 1000L)
                        .coerceAtMost(if (current.durationMs > 0) current.durationMs else Long.MAX_VALUE)
                )
                is MediaCommand.SkipBackward -> current.copy(
                    positionMs = (current.positionMs - command.seconds * 1000L).coerceAtLeast(0L)
                )
                is MediaCommand.SetPlaybackSpeed -> current.copy(playbackRate = command.speed)
            }
        }
    }

    fun notifyLoginFormDetected(detected: Boolean, fieldHint: String) {
        _autofillLoginDetected.value = detected to fieldHint
    }

    fun offerSaveCredential(username: String, password: String) {
        if (username.isNotBlank() && password.isNotBlank()) {
            _capturedCredentialsToSave.tryEmit(username to password)
        }
    }
}
