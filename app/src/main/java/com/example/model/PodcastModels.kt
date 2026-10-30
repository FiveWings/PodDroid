package com.example.model

data class WebMediaMetadata(
    val title: String = "Ready to Stream",
    val artist: String = "Select any episode on Apple Podcasts",
    val album: String = "Apple Podcasts",
    val artworkUrl: String = "",
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val playbackRate: Float = 1.0f,
    val hasActiveMedia: Boolean = false,
    val pageUrl: String = "https://podcasts.apple.com"
)

data class PodcastBookmark(
    val url: String,
    val title: String,
    val category: String,
    val addedAtMs: Long = System.currentTimeMillis()
)

data class AutofillEventStatus(
    val isLoginFormDetected: Boolean = false,
    val detectedDomain: String = "podcasts.apple.com",
    val fieldType: String = "",
    val systemAutofillEnabled: Boolean = true,
    val lastFilledUsername: String? = null,
    val statusMessage: String = "Android Autofill & Credential Manager active for Apple ID"
)

enum class PodBridgeTab(val title: String, val defaultUrl: String?) {
    LISTEN_NOW("Listen Now", "https://podcasts.apple.com"),
    BROWSE("Browse", "https://podcasts.apple.com/us/browse"),
    CHARTS("Top Charts", "https://podcasts.apple.com/us/charts"),
    SAVED("Saved", null),
    AUTOFILL_HUB("Passwords", null)
}

sealed interface MediaCommand {
    data object Play : MediaCommand
    data object Pause : MediaCommand
    data object TogglePlayPause : MediaCommand
    data class SeekTo(val positionMs: Long) : MediaCommand
    data class SkipForward(val seconds: Int = 30) : MediaCommand
    data class SkipBackward(val seconds: Int = 15) : MediaCommand
    data class SetPlaybackSpeed(val speed: Float) : MediaCommand
}
