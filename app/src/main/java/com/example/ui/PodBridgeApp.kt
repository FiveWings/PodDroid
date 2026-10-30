package com.example.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Leaderboard
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.auth.CredentialFetchResult
import com.example.auth.CredentialSaveResult
import com.example.media.MediaBridgeController
import com.example.model.PodBridgeTab
import com.example.ui.components.PasswordManagerHubScreen
import com.example.ui.components.SavedBookmarksScreen
import com.example.viewmodel.PodBridgeViewModel
import com.example.webview.ApplePodcastsJsBridge
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Custom Chromium WebView subclass that keeps Apple Podcasts' web player audio and
 * W3C MediaSession active in the background when the user switches apps or locks the screen,
 * while preserving full Android Autofill support.
 */
class BackgroundAudioWebView(context: Context) : WebView(context) {
    var keepAudioInBackground: Boolean = true

    override fun onWindowVisibilityChanged(visibility: Int) {
        if (keepAudioInBackground && visibility != View.VISIBLE) {
            super.onWindowVisibilityChanged(View.VISIBLE)
        } else {
            super.onWindowVisibilityChanged(visibility)
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PodBridgeApp(
    viewModel: PodBridgeViewModel
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current

    var searchQuery by remember { mutableStateOf("") }
    var isEditingOmnibox by remember { mutableStateOf(false) }

    // Create and retain a single Chromium WebView instance across tab switches so web player playback never stops
    val webView = remember {
        BackgroundAudioWebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            viewModel.passwordManagerHelper.configureWebViewForAutofill(this)

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(this, true)

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                loadsImagesAutomatically = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = true
                }
            }

            addJavascriptInterface(
                ApplePodcastsJsBridge(context.applicationContext),
                ApplePodcastsJsBridge.JS_INTERFACE_NAME
            )

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    view?.evaluateJavascript(ApplePodcastsJsBridge.INJECTED_BRIDGE_SCRIPT, null)
                    viewModel.updateWebPageState(
                        url = url,
                        title = view?.title,
                        isLoading = true,
                        progress = 15,
                        canGoBack = view?.canGoBack() == true,
                        canGoForward = view?.canGoForward() == true
                    )
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(ApplePodcastsJsBridge.INJECTED_BRIDGE_SCRIPT, null)
                    CookieManager.getInstance().flush()
                    viewModel.updateWebPageState(
                        url = url,
                        title = view?.title,
                        isLoading = false,
                        progress = 100,
                        canGoBack = view?.canGoBack() == true,
                        canGoForward = view?.canGoForward() == true
                    )
                }

                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, url, isReload)
                    view?.evaluateJavascript(ApplePodcastsJsBridge.INJECTED_BRIDGE_SCRIPT, null)
                    viewModel.updateWebPageState(
                        url = url,
                        title = view?.title,
                        isLoading = false,
                        progress = 100,
                        canGoBack = view?.canGoBack() == true,
                        canGoForward = view?.canGoForward() == true
                    )
                }

                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val target = request?.url?.toString() ?: return false
                    if (target.startsWith("https://") || target.startsWith("http://")) {
                        return false
                    }
                    if (target.startsWith("itms-podcast://") || target.startsWith("itms-podcasts://")) {
                        val httpsUrl = target
                            .replaceFirst("itms-podcasts://", "https://")
                            .replaceFirst("itms-podcast://", "https://")
                        view?.loadUrl(httpsUrl)
                        return true
                    }
                    return true
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    if (newProgress > 25) {
                        view?.evaluateJavascript(ApplePodcastsJsBridge.INJECTED_BRIDGE_SCRIPT, null)
                    }
                    viewModel.updateWebPageState(
                        url = view?.url,
                        title = view?.title,
                        isLoading = newProgress < 100,
                        progress = newProgress,
                        canGoBack = view?.canGoBack() == true,
                        canGoForward = view?.canGoForward() == true
                    )
                }

                override fun onPermissionRequest(request: PermissionRequest?) {
                    // Grant protected media / EME permissions so Apple Podcasts web player streams without restriction
                    val resources = request?.resources ?: emptyArray()
                    val granted = resources.filter {
                        it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
                    }.toTypedArray()
                    if (granted.isNotEmpty()) {
                        request?.grant(granted)
                    } else {
                        request?.deny()
                    }
                }
            }

            loadUrl(uiState.currentUrl)
        }
    }

    // Keep background audio setting synced with WebView
    LaunchedEffect(uiState.keepBackgroundAudio) {
        webView.keepAudioInBackground = uiState.keepBackgroundAudio
    }

    // Keep Desktop / Mobile User-Agent synced with WebView
    LaunchedEffect(uiState.desktopMode) {
        val defaultUa = WebSettings.getDefaultUserAgent(context)
        webView.settings.userAgentString = if (uiState.desktopMode) {
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"
        } else {
            defaultUa
        }
        webView.settings.useWideViewPort = uiState.desktopMode
        webView.settings.loadWithOverviewMode = uiState.desktopMode
    }

    // Execute pending URL navigations
    LaunchedEffect(uiState.pendingNavigationUrl) {
        val target = uiState.pendingNavigationUrl
        if (!target.isNullOrBlank()) {
            webView.loadUrl(target)
            viewModel.consumePendingNavigation()
        }
    }

    // Forward MediaCommands from Android System MediaSession / Lock Screen / Notification into Chromium's web player
    LaunchedEffect(webView) {
        MediaBridgeController.commands.collectLatest { command ->
            ApplePodcastsJsBridge.executeMediaCommand(webView, command)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.passwordManagerHelper.commitAutofillSession()
        }
    }

    // Handle system Back gesture / button
    BackHandler(
        enabled = uiState.activeTab == PodBridgeTab.SAVED ||
            uiState.activeTab == PodBridgeTab.AUTOFILL_HUB ||
            uiState.canGoBack
    ) {
        when {
            uiState.activeTab == PodBridgeTab.SAVED || uiState.activeTab == PodBridgeTab.AUTOFILL_HUB -> {
                viewModel.selectTab(PodBridgeTab.LISTEN_NOW)
            }
            webView.canGoBack() -> webView.goBack()
        }
    }

    val invokeAndroidPasswordManager: () -> Unit = {
        viewModel.passwordManagerHelper.triggerNativeWebViewAutofill(webView)
        if (activity != null) {
            scope.launch {
                when (val res = viewModel.passwordManagerHelper.requestPasswordFromManager(activity)) {
                    is CredentialFetchResult.Success -> {
                        ApplePodcastsJsBridge.injectCredentialsIntoDom(
                            webView = webView,
                            username = res.username,
                            password = res.password
                        )
                        viewModel.selectTab(PodBridgeTab.LISTEN_NOW)
                        viewModel.updateAutofillStatusMessage(
                            message = "Filled credentials for ${res.username} via Android Password Manager",
                            filledUser = res.username
                        )
                        snackbarHostState.showSnackbar("Filled Apple ID credentials for ${res.username}")
                    }
                    CredentialFetchResult.NoCredentialsFound -> {
                        viewModel.updateAutofillStatusMessage(
                            "No saved passwords found yet — sign in on the page or save your Apple ID in the Passwords tab."
                        )
                        snackbarHostState.showSnackbar(
                            "No saved Apple Podcasts credentials found in Android Password Manager yet."
                        )
                    }
                    CredentialFetchResult.Cancelled -> {}
                    is CredentialFetchResult.Error -> {
                        viewModel.updateAutofillStatusMessage(
                            "Inline Android Autofill triggered on WebView."
                        )
                        snackbarHostState.showSnackbar("Triggered Android Autofill for active WebView field.")
                    }
                }
            }
        }
    }

    val saveCredentialToAndroidManager: (String, String) -> Unit = { user, pass ->
        if (activity != null) {
            scope.launch {
                when (val res = viewModel.passwordManagerHelper.savePasswordToManager(activity, user, pass)) {
                    CredentialSaveResult.Saved -> {
                        viewModel.dismissSaveCredentialBanner()
                        viewModel.updateAutofillStatusMessage(
                            message = "Saved $user to Android Password Manager",
                            filledUser = user
                        )
                        snackbarHostState.showSnackbar("Saved $user to Android Password Manager")
                    }
                    CredentialSaveResult.Cancelled -> {
                        viewModel.dismissSaveCredentialBanner()
                    }
                    is CredentialSaveResult.Error -> {
                        snackbarHostState.showSnackbar(res.message)
                    }
                }
            }
        }
    }

    val isBookmarked = remember(uiState.bookmarks, uiState.currentUrl) {
        uiState.bookmarks.any { it.url == uiState.currentUrl }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                PodBridgeTopBar(
                    currentUrl = uiState.currentUrl,
                    pageTitle = uiState.pageTitle,
                    isLoading = uiState.isLoading,
                    loadProgress = uiState.loadProgress,
                    canGoBack = uiState.canGoBack,
                    canGoForward = uiState.canGoForward,
                    isBookmarked = isBookmarked,
                    isEditingOmnibox = isEditingOmnibox,
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    onStartEditOmnibox = {
                        searchQuery = ""
                        isEditingOmnibox = true
                    },
                    onCancelEditOmnibox = {
                        isEditingOmnibox = false
                        focusManager.clearFocus()
                    },
                    onSubmitOmnibox = {
                        isEditingOmnibox = false
                        focusManager.clearFocus()
                        viewModel.navigateToUrl(searchQuery)
                    },
                    onBack = { if (webView.canGoBack()) webView.goBack() },
                    onForward = { if (webView.canGoForward()) webView.goForward() },
                    onReload = { webView.reload() },
                    onToggleBookmark = { viewModel.toggleCurrentBookmark() },
                    onTriggerPasswordManager = invokeAndroidPasswordManager
                )
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                ) {
                    // Prompt banner when new credentials are submitted in the WebView
                    AnimatedVisibility(
                        visible = uiState.showSaveCredentialSheet != null,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut()
                    ) {
                        uiState.showSaveCredentialSheet?.let { (user, pass) ->
                            SaveCredentialPromptCard(
                                username = user,
                                onSave = { saveCredentialToAndroidManager(user, pass) },
                                onDismiss = { viewModel.dismissSaveCredentialBanner() }
                            )
                        }
                    }

                    if (!isWideScreen) {
                        PodBridgeBottomNav(
                            activeTab = uiState.activeTab,
                            onSelectTab = { viewModel.selectTab(it) }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isWideScreen) {
                    PodBridgeNavRail(
                        activeTab = uiState.activeTab,
                        onSelectTab = { viewModel.selectTab(it) }
                    )
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    // Full-bleed Chromium WebView letting Apple Podcasts' built-in web player shine
                    AndroidView(
                        factory = { webView },
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("apple_podcasts_webview")
                    )

                    // Floating Android Password Manager chip when an Apple ID login field is detected
                    androidx.compose.animation.AnimatedVisibility(
                        visible = uiState.autofillStatus.isLoginFormDetected &&
                            uiState.activeTab != PodBridgeTab.SAVED &&
                            uiState.activeTab != PodBridgeTab.AUTOFILL_HUB,
                        enter = fadeIn() + slideInVertically(),
                        exit = fadeOut() + slideOutVertically(),
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(12.dp)
                    ) {
                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                            modifier = Modifier.testTag("autofill_detected_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Apple ID Sign-In Detected",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Button(
                                    onClick = invokeAndroidPasswordManager,
                                    modifier = Modifier.testTag("banner_autofill_button")
                                ) {
                                    Text("Autofill Password")
                                }
                                IconButton(
                                    onClick = { viewModel.dismissLoginFormBanner() },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Dismiss autofill banner",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                            }
                        }
                    }

                    // Overlay Saved Bookmarks or Password Manager Hub when those tabs are selected
                    when (uiState.activeTab) {
                        PodBridgeTab.SAVED -> {
                            SavedBookmarksScreen(
                                bookmarks = uiState.bookmarks,
                                currentUrl = uiState.currentUrl,
                                onSelectUrl = { url -> viewModel.navigateToUrl(url) },
                                onBookmarkCurrentPage = { viewModel.toggleCurrentBookmark() },
                                onRemoveBookmark = { url -> viewModel.removeBookmark(url) }
                            )
                        }
                        PodBridgeTab.AUTOFILL_HUB -> {
                            PasswordManagerHubScreen(
                                autofillStatus = uiState.autofillStatus,
                                keepBackgroundAudio = uiState.keepBackgroundAudio,
                                desktopMode = uiState.desktopMode,
                                onToggleBackgroundAudio = { viewModel.setKeepBackgroundAudio(it) },
                                onToggleDesktopMode = { viewModel.setDesktopMode(it) },
                                onRequestCredentialFromManager = invokeAndroidPasswordManager,
                                onTriggerWebViewInlineAutofill = {
                                    viewModel.passwordManagerHelper.triggerNativeWebViewAutofill(webView)
                                    viewModel.selectTab(PodBridgeTab.LISTEN_NOW)
                                },
                                onSaveCredentialToManager = saveCredentialToAndroidManager,
                                onOpenSystemAutofillSettings = {
                                    viewModel.passwordManagerHelper.openSystemAutofillSettings()
                                }
                            )
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun PodBridgeTopBar(
    currentUrl: String,
    pageTitle: String,
    isLoading: Boolean,
    loadProgress: Int,
    canGoBack: Boolean,
    canGoForward: Boolean,
    isBookmarked: Boolean,
    isEditingOmnibox: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onStartEditOmnibox: () -> Unit,
    onCancelEditOmnibox: () -> Unit,
    onSubmitOmnibox: () -> Unit,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onReload: () -> Unit,
    onToggleBookmark: () -> Unit,
    onTriggerPasswordManager: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    enabled = canGoBack,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("topbar_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Navigate Back"
                    )
                }

                IconButton(
                    onClick = onForward,
                    enabled = canGoForward,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("topbar_forward_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Navigate Forward"
                    )
                }

                if (isEditingOmnibox) {
                    TextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        placeholder = { Text("Search Apple Podcasts or enter URL...") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSubmitOmnibox() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        shape = RoundedCornerShape(16.dp),
                        trailingIcon = {
                            IconButton(onClick = onCancelEditOmnibox) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Cancel search"
                                )
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("omnibox_search_input")
                    )
                } else {
                    Surface(
                        onClick = onStartEditOmnibox,
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("omnibox_bar")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = pageTitle,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = currentUrl.removePrefix("https://"),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }

                IconButton(
                    onClick = onTriggerPasswordManager,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("topbar_autofill_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = "Trigger Android Password Manager",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(
                    onClick = onToggleBookmark,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("topbar_bookmark_button")
                ) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = "Bookmark current page",
                        tint = if (isBookmarked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(
                    onClick = onReload,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("topbar_reload_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reload page"
                    )
                }
            }

            if (isLoading) {
                LinearProgressIndicator(
                    progress = { (loadProgress / 100f).coerceIn(0.05f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent
                )
            }
        }
    }
}

@Composable
private fun SaveCredentialPromptCard(
    username: String,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .testTag("save_credential_prompt_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Key,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "Save to Android Password Manager?",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = username,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) {
                    Text("Not now")
                }
                FilledTonalButton(
                    onClick = onSave,
                    modifier = Modifier.testTag("confirm_save_credential_btn")
                ) {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun PodBridgeBottomNav(
    activeTab: PodBridgeTab,
    onSelectTab: (PodBridgeTab) -> Unit
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        modifier = Modifier.testTag("bottom_navigation_bar")
    ) {
        PodBridgeTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = activeTab == tab,
                onClick = { onSelectTab(tab) },
                icon = {
                    Icon(
                        imageVector = tab.icon(),
                        contentDescription = tab.title
                    )
                },
                label = {
                    Text(
                        text = tab.title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}")
            )
        }
    }
}

@Composable
private fun PodBridgeNavRail(
    activeTab: PodBridgeTab,
    onSelectTab: (PodBridgeTab) -> Unit
) {
    NavigationRail(
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxHeight()
            .testTag("navigation_rail")
    ) {
        Spacer(modifier = Modifier.height(12.dp))
        PodBridgeTab.entries.forEach { tab ->
            NavigationRailItem(
                selected = activeTab == tab,
                onClick = { onSelectTab(tab) },
                icon = {
                    Icon(
                        imageVector = tab.icon(),
                        contentDescription = tab.title
                    )
                },
                label = { Text(tab.title) },
                modifier = Modifier.testTag("rail_tab_${tab.name.lowercase()}")
            )
        }
    }
}

private fun PodBridgeTab.icon(): ImageVector = when (this) {
    PodBridgeTab.LISTEN_NOW -> Icons.Default.Home
    PodBridgeTab.BROWSE -> Icons.Default.Explore
    PodBridgeTab.CHARTS -> Icons.Default.Leaderboard
    PodBridgeTab.SAVED -> Icons.Default.Bookmark
    PodBridgeTab.AUTOFILL_HUB -> Icons.Default.Key
}
