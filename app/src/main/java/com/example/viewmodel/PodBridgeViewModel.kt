package com.example.viewmodel

import android.app.Application
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.auth.PasswordManagerHelper
import com.example.media.MediaBridgeController
import com.example.model.AutofillEventStatus
import com.example.model.PodBridgeTab
import com.example.model.PodcastBookmark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val Application.dataStore by preferencesDataStore(name = "podbridge_prefs")

data class PodBridgeUiState(
    val currentUrl: String = "https://podcasts.apple.com",
    val pendingNavigationUrl: String? = null,
    val pageTitle: String = "Apple Podcasts",
    val isLoading: Boolean = true,
    val loadProgress: Int = 0,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val activeTab: PodBridgeTab = PodBridgeTab.LISTEN_NOW,
    val desktopMode: Boolean = false,
    val keepBackgroundAudio: Boolean = true,
    val bookmarks: List<PodcastBookmark> = defaultBookmarks(),
    val autofillStatus: AutofillEventStatus = AutofillEventStatus(),
    val showSaveCredentialSheet: Pair<String, String>? = null
)

private fun defaultBookmarks(): List<PodcastBookmark> = listOf(
    PodcastBookmark(
        url = "https://podcasts.apple.com/us/browse",
        title = "Featured & New Releases",
        category = "Apple Podcasts Directory"
    ),
    PodcastBookmark(
        url = "https://podcasts.apple.com/us/charts",
        title = "Top Shows & Episodes",
        category = "Charts"
    ),
    PodcastBookmark(
        url = "https://podcasts.apple.com/us/genre/podcasts-technology/id1318",
        title = "Technology Podcasts",
        category = "Category"
    ),
    PodcastBookmark(
        url = "https://podcasts.apple.com/us/genre/podcasts-news/id1489",
        title = "Daily News & Politics",
        category = "Category"
    ),
    PodcastBookmark(
        url = "https://podcasts.apple.com/us/genre/podcasts-comedy/id1303",
        title = "Comedy Spotlight",
        category = "Category"
    )
)

class PodBridgeViewModel(application: Application) : AndroidViewModel(application) {

    private val KEY_DESKTOP_MODE = booleanPreferencesKey("desktop_mode")
    private val KEY_BG_AUDIO = booleanPreferencesKey("keep_bg_audio")
    private val KEY_BOOKMARKS = stringSetPreferencesKey("saved_bookmarks")

    val passwordManagerHelper = PasswordManagerHelper(application)

    private val _uiState = MutableStateFlow(
        PodBridgeUiState(
            autofillStatus = AutofillEventStatus(
                systemAutofillEnabled = passwordManagerHelper.isSystemAutofillEnabled()
            )
        )
    )
    val uiState: StateFlow<PodBridgeUiState> = _uiState.asStateFlow()

    init {
        loadPreferences()

        viewModelScope.launch {
            MediaBridgeController.autofillLoginDetected.collectLatest { (detected, hint) ->
                _uiState.update { state ->
                    state.copy(
                        autofillStatus = state.autofillStatus.copy(
                            isLoginFormDetected = detected,
                            detectedDomain = hint.ifBlank { "podcasts.apple.com" },
                            systemAutofillEnabled = passwordManagerHelper.isSystemAutofillEnabled(),
                            statusMessage = if (detected) {
                                "Login field detected on $hint — tap to fill with Android Password Manager"
                            } else {
                                state.autofillStatus.statusMessage
                            }
                        )
                    )
                }
            }
        }

        viewModelScope.launch {
            MediaBridgeController.capturedCredentialsToSave.collectLatest { credentials ->
                _uiState.update { it.copy(showSaveCredentialSheet = credentials) }
            }
        }
    }

    private fun loadPreferences() {
        viewModelScope.launch {
            try {
                val prefs = getApplication<Application>().dataStore.data.first()
                val desktop = prefs[KEY_DESKTOP_MODE] ?: false
                val bgAudio = prefs[KEY_BG_AUDIO] ?: true
                val rawBookmarks = prefs[KEY_BOOKMARKS]
                val parsedBookmarks = if (!rawBookmarks.isNullOrEmpty()) {
                    rawBookmarks.mapNotNull { entry ->
                        val parts = entry.split("|||")
                        if (parts.size >= 3) {
                            PodcastBookmark(
                                url = parts[0],
                                title = parts[1],
                                category = parts[2],
                                addedAtMs = parts.getOrNull(3)?.toLongOrNull() ?: 0L
                            )
                        } else null
                    }.sortedByDescending { it.addedAtMs }
                } else {
                    defaultBookmarks()
                }
                _uiState.update {
                    it.copy(
                        desktopMode = desktop,
                        keepBackgroundAudio = bgAudio,
                        bookmarks = parsedBookmarks
                    )
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun persistBookmarks(list: List<PodcastBookmark>) {
        viewModelScope.launch {
            try {
                val serialized = list.map { "${it.url}|||${it.title}|||${it.category}|||${it.addedAtMs}" }.toSet()
                getApplication<Application>().dataStore.edit { prefs ->
                    prefs[KEY_BOOKMARKS] = serialized
                }
            } catch (_: Exception) {
            }
        }
    }

    fun selectTab(tab: PodBridgeTab) {
        _uiState.update { current ->
            val targetUrl = tab.defaultUrl
            current.copy(
                activeTab = tab,
                pendingNavigationUrl = if (targetUrl != null && targetUrl != current.currentUrl) targetUrl else null
            )
        }
    }

    fun navigateToUrl(input: String) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return
        val resolvedUrl = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains("podcasts.apple.com") -> "https://$trimmed"
            else -> "https://podcasts.apple.com/us/search?term=${android.net.Uri.encode(trimmed)}"
        }
        _uiState.update {
            it.copy(
                activeTab = if (it.activeTab == PodBridgeTab.SAVED || it.activeTab == PodBridgeTab.AUTOFILL_HUB) {
                    PodBridgeTab.LISTEN_NOW
                } else {
                    it.activeTab
                },
                pendingNavigationUrl = resolvedUrl
            )
        }
    }

    fun consumePendingNavigation() {
        _uiState.update { it.copy(pendingNavigationUrl = null) }
    }

    fun updateWebPageState(
        url: String?,
        title: String?,
        isLoading: Boolean,
        progress: Int,
        canGoBack: Boolean,
        canGoForward: Boolean
    ) {
        _uiState.update { current ->
            val cleanUrl = url?.takeIf { it.isNotBlank() } ?: current.currentUrl
            val cleanTitle = title?.takeIf { it.isNotBlank() && !it.startsWith("http") }
                ?.replace(" on Apple Podcasts", "")
                ?: current.pageTitle
            val isLoginUrl = cleanUrl.contains("idmsa.apple.com", ignoreCase = true) ||
                cleanUrl.contains("auth", ignoreCase = true) ||
                cleanUrl.contains("signin", ignoreCase = true)

            current.copy(
                currentUrl = cleanUrl,
                pageTitle = cleanTitle,
                isLoading = isLoading,
                loadProgress = progress,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                autofillStatus = current.autofillStatus.copy(
                    isLoginFormDetected = current.autofillStatus.isLoginFormDetected || isLoginUrl,
                    systemAutofillEnabled = passwordManagerHelper.isSystemAutofillEnabled()
                )
            )
        }
    }

    fun toggleCurrentBookmark() {
        val current = _uiState.value
        val existing = current.bookmarks.any { it.url == current.currentUrl }
        val updated = if (existing) {
            current.bookmarks.filterNot { it.url == current.currentUrl }
        } else {
            listOf(
                PodcastBookmark(
                    url = current.currentUrl,
                    title = current.pageTitle.ifBlank { "Apple Podcasts Page" },
                    category = "Saved Episode / Show"
                )
            ) + current.bookmarks
        }
        _uiState.update { it.copy(bookmarks = updated) }
        persistBookmarks(updated)
    }

    fun removeBookmark(url: String) {
        val updated = _uiState.value.bookmarks.filterNot { it.url == url }
        _uiState.update { it.copy(bookmarks = updated) }
        persistBookmarks(updated)
    }

    fun setDesktopMode(enabled: Boolean) {
        _uiState.update { it.copy(desktopMode = enabled, pendingNavigationUrl = it.currentUrl) }
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[KEY_DESKTOP_MODE] = enabled }
        }
    }

    fun setKeepBackgroundAudio(enabled: Boolean) {
        _uiState.update { it.copy(keepBackgroundAudio = enabled) }
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit { it[KEY_BG_AUDIO] = enabled }
        }
    }

    fun updateAutofillStatusMessage(message: String, filledUser: String? = null) {
        _uiState.update { state ->
            state.copy(
                autofillStatus = state.autofillStatus.copy(
                    lastFilledUsername = filledUser ?: state.autofillStatus.lastFilledUsername,
                    systemAutofillEnabled = passwordManagerHelper.isSystemAutofillEnabled(),
                    statusMessage = message
                )
            )
        }
    }

    fun dismissSaveCredentialBanner() {
        _uiState.update { it.copy(showSaveCredentialSheet = null) }
    }

    fun dismissLoginFormBanner() {
        MediaBridgeController.notifyLoginFormDetected(false, "")
    }
}
