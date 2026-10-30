package com.example.webview

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.example.media.MediaBridgeController
import com.example.media.PodcastMediaService
import com.example.model.MediaCommand

/**
 * Chromium W3C MediaSession (`navigator.mediaSession`) & HTMLMediaElement bridge
 * that connects Apple Podcasts' built-in web player directly to Android's System MediaSession
 * and bridges Apple ID login forms with Android's Password Manager.
 */
class ApplePodcastsJsBridge(
    private val appContext: Context
) {

    @JavascriptInterface
    fun onMediaUpdate(
        title: String?,
        artist: String?,
        album: String?,
        artworkUrl: String?,
        isPlaying: Boolean,
        positionSec: Double,
        durationSec: Double,
        playbackRate: Double,
        pageUrl: String?
    ) {
        val posMs = if (positionSec.isFinite() && positionSec >= 0.0) (positionSec * 1000.0).toLong() else 0L
        val durMs = if (durationSec.isFinite() && durationSec > 0.0) (durationSec * 1000.0).toLong() else 0L
        val rate = if (playbackRate.isFinite() && playbackRate > 0.0) playbackRate.toFloat() else 1.0f

        PodcastMediaService.ensureStarted(appContext)
        MediaBridgeController.updateFromWeb(
            title = title?.trim().orEmpty(),
            artist = artist?.trim().orEmpty(),
            album = album?.trim().orEmpty(),
            artworkUrl = artworkUrl?.trim().orEmpty(),
            isPlaying = isPlaying,
            positionMs = posMs,
            durationMs = durMs,
            playbackRate = rate,
            pageUrl = pageUrl?.trim().orEmpty()
        )
    }

    @JavascriptInterface
    fun onLoginFieldFocused(domain: String?, fieldType: String?) {
        MediaBridgeController.notifyLoginFormDetected(
            detected = true,
            fieldHint = "${domain.orEmpty()} (${fieldType.orEmpty()})"
        )
    }

    @JavascriptInterface
    fun onCredentialSubmitted(username: String?, password: String?) {
        if (!username.isNullOrBlank() && !password.isNullOrBlank()) {
            MediaBridgeController.offerSaveCredential(username.trim(), password)
        }
    }

    companion object {
        const val JS_INTERFACE_NAME = "PodBridgeNative"

        fun executeMediaCommand(webView: WebView, command: MediaCommand) {
            val jsCall = when (command) {
                MediaCommand.Play -> "window.__podBridgeControl && window.__podBridgeControl('play', 0);"
                MediaCommand.Pause -> "window.__podBridgeControl && window.__podBridgeControl('pause', 0);"
                MediaCommand.TogglePlayPause -> "window.__podBridgeControl && window.__podBridgeControl('toggle', 0);"
                is MediaCommand.SeekTo -> {
                    val sec = command.positionMs / 1000.0
                    "window.__podBridgeControl && window.__podBridgeControl('seekTo', $sec);"
                }
                is MediaCommand.SkipForward -> {
                    "window.__podBridgeControl && window.__podBridgeControl('skipForward', ${command.seconds});"
                }
                is MediaCommand.SkipBackward -> {
                    "window.__podBridgeControl && window.__podBridgeControl('skipBackward', ${command.seconds});"
                }
                is MediaCommand.SetPlaybackSpeed -> {
                    "window.__podBridgeControl && window.__podBridgeControl('setRate', ${command.speed});"
                }
            }
            webView.evaluateJavascript(jsCall, null)
        }

        fun injectCredentialsIntoDom(webView: WebView, username: String, password: String) {
            val escapedUser = username.replace("\\", "\\\\").replace("'", "\\'")
            val escapedPass = password.replace("\\", "\\\\").replace("'", "\\'")
            val script = """
                (function() {
                    if (window.__podBridgeFillCredentials) {
                        window.__podBridgeFillCredentials('$escapedUser', '$escapedPass');
                    }
                })();
            """.trimIndent()
            webView.evaluateJavascript(script, null)
        }

        /**
         * Hooks directly into Chromium's `navigator.mediaSession` (action handlers, metadata,
         * position state) and HTMLMediaElement so Apple Podcasts' own web player seamlessly
         * drives Android's System Media Player notification & lock screen controls.
         */
        val INJECTED_BRIDGE_SCRIPT = """
            (function() {
                if (window.__podBridgeInitialized) return;
                window.__podBridgeInitialized = true;

                // 1. Keep Chromium WebView audio playback active when app is backgrounded or screen is locked
                try {
                    Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                    Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                    document.addEventListener('visibilitychange', function(e) {
                        e.stopImmediatePropagation();
                    }, true);
                } catch (e) {}

                var activeMediaElement = null;
                var mediaSessionHandlers = {};
                var lastPositionState = { duration: 0, playbackRate: 1.0, position: 0 };
                var lastMetadata = {
                    title: '',
                    artist: '',
                    album: 'Apple Podcasts',
                    artworkUrl: ''
                };

                // 2. Intercept Chromium navigator.mediaSession API used by Apple Podcasts web player
                try {
                    if ('mediaSession' in navigator && navigator.mediaSession) {
                        var msProto = Object.getPrototypeOf(navigator.mediaSession);

                        if (msProto && msProto.setActionHandler) {
                            var origSetActionHandler = msProto.setActionHandler;
                            msProto.setActionHandler = function(action, handler) {
                                if (typeof handler === 'function') {
                                    mediaSessionHandlers[action] = handler;
                                } else {
                                    delete mediaSessionHandlers[action];
                                }
                                return origSetActionHandler.apply(this, arguments);
                            };
                        }

                        if (msProto && msProto.setPositionState) {
                            var origSetPositionState = msProto.setPositionState;
                            msProto.setPositionState = function(state) {
                                if (state) {
                                    if (Number.isFinite(state.duration)) lastPositionState.duration = state.duration;
                                    if (Number.isFinite(state.playbackRate)) lastPositionState.playbackRate = state.playbackRate;
                                    if (Number.isFinite(state.position)) lastPositionState.position = state.position;
                                    reportToAndroid(activeMediaElement);
                                }
                                return origSetPositionState.apply(this, arguments);
                            };
                        }
                    }
                } catch (e) {}

                function syncFromNavigatorMediaSession() {
                    try {
                        if (navigator.mediaSession && navigator.mediaSession.metadata) {
                            var meta = navigator.mediaSession.metadata;
                            if (meta.title) lastMetadata.title = meta.title;
                            if (meta.artist) lastMetadata.artist = meta.artist;
                            if (meta.album) lastMetadata.album = meta.album;
                            if (meta.artwork && meta.artwork.length > 0) {
                                var best = meta.artwork[meta.artwork.length - 1];
                                if (best && best.src) {
                                    lastMetadata.artworkUrl = best.src;
                                }
                            }
                        }
                    } catch (e) {}
                }

                function extractMetadataFromWebPlayerDom() {
                    syncFromNavigatorMediaSession();
                    try {
                        var titleSelectors = [
                            '.lcd__track-info__title',
                            '[class*="track-title"]',
                            '[class*="episode-title"]',
                            '[data-testid="lcd-track-title"]'
                        ];
                        for (var i = 0; i < titleSelectors.length; i++) {
                            var el = document.querySelector(titleSelectors[i]);
                            if (el && el.textContent && el.textContent.trim().length > 1) {
                                lastMetadata.title = el.textContent.trim();
                                break;
                            }
                        }

                        var artistSelectors = [
                            '.lcd__track-info__sub-title',
                            '[class*="show-title"]',
                            '[class*="podcast-title"]',
                            '[data-testid="lcd-subtitle"]'
                        ];
                        for (var j = 0; j < artistSelectors.length; j++) {
                            var aEl = document.querySelector(artistSelectors[j]);
                            if (aEl && aEl.textContent && aEl.textContent.trim().length > 1) {
                                lastMetadata.artist = aEl.textContent.trim();
                                break;
                            }
                        }

                        if (!lastMetadata.artworkUrl) {
                            var artEl = document.querySelector('.lcd__artwork img, [class*="player"] img, meta[property="og:image"]');
                            if (artEl) {
                                var src = artEl.getAttribute('src') || artEl.getAttribute('content') || '';
                                if (src.indexOf('http') === 0) {
                                    lastMetadata.artworkUrl = src;
                                }
                            }
                        }

                        if (!lastMetadata.title) {
                            lastMetadata.title = (document.title || 'Apple Podcasts').replace(' on Apple Podcasts', '').trim();
                        }
                        if (!lastMetadata.artist) {
                            lastMetadata.artist = 'Apple Podcasts';
                        }
                    } catch (e) {}
                }

                function findAllMediaElements(root) {
                    var list = [];
                    try {
                        var direct = root.querySelectorAll('audio, video');
                        for (var i = 0; i < direct.length; i++) list.push(direct[i]);
                        var allNodes = root.querySelectorAll('*');
                        for (var j = 0; j < allNodes.length; j++) {
                            if (allNodes[j].shadowRoot) {
                                var nested = findAllMediaElements(allNodes[j].shadowRoot);
                                for (var k = 0; k < nested.length; k++) list.push(nested[k]);
                            }
                        }
                    } catch (e) {}
                    return list;
                }

                function pickActiveMedia() {
                    if (activeMediaElement && !activeMediaElement.paused) return activeMediaElement;
                    var all = findAllMediaElements(document);
                    for (var i = 0; i < all.length; i++) {
                        if (!all[i].paused) {
                            activeMediaElement = all[i];
                            return activeMediaElement;
                        }
                    }
                    if (!activeMediaElement && all.length > 0) {
                        activeMediaElement = all[0];
                    }
                    return activeMediaElement;
                }

                function reportToAndroid(mediaEl) {
                    if (!window.PodBridgeNative) return;
                    var el = mediaEl || pickActiveMedia();
                    extractMetadataFromWebPlayerDom();

                    var msState = (navigator.mediaSession && navigator.mediaSession.playbackState) || 'none';
                    var isPlaying = el ? (!el.paused && !el.ended) : (msState === 'playing');
                    if (!el && msState === 'none') return;

                    var pos = (el && Number.isFinite(el.currentTime)) ? el.currentTime : (lastPositionState.position || 0);
                    var dur = (el && Number.isFinite(el.duration) && el.duration > 0) ? el.duration : (lastPositionState.duration || 0);
                    var rate = (el && Number.isFinite(el.playbackRate) && el.playbackRate > 0) ? el.playbackRate : (lastPositionState.playbackRate || 1.0);

                    try {
                        window.PodBridgeNative.onMediaUpdate(
                            lastMetadata.title || document.title || 'Apple Podcasts',
                            lastMetadata.artist || 'Apple Podcasts',
                            lastMetadata.album || 'Apple Podcasts',
                            lastMetadata.artworkUrl || '',
                            isPlaying,
                            pos,
                            dur,
                            rate,
                            window.location.href
                        );
                    } catch (e) {}
                }

                function bindMediaElement(el) {
                    if (!el || el.__podBridgeBound) return;
                    el.__podBridgeBound = true;
                    var events = ['play', 'playing', 'pause', 'timeupdate', 'durationchange', 'ratechange', 'loadedmetadata', 'seeked', 'ended'];
                    events.forEach(function(ev) {
                        el.addEventListener(ev, function() {
                            activeMediaElement = el;
                            reportToAndroid(el);
                        }, { passive: true });
                    });
                }

                try {
                    var origPlay = HTMLMediaElement.prototype.play;
                    HTMLMediaElement.prototype.play = function() {
                        activeMediaElement = this;
                        bindMediaElement(this);
                        setTimeout(function() { reportToAndroid(activeMediaElement); }, 120);
                        return origPlay.apply(this, arguments);
                    };
                } catch (e) {}

                // Route Android System MediaSession actions directly to Apple Podcasts' W3C MediaSession handlers or <audio> element
                window.__podBridgeControl = function(action, value) {
                    var el = pickActiveMedia();
                    try {
                        if (action === 'play') {
                            if (mediaSessionHandlers['play']) {
                                mediaSessionHandlers['play']({ action: 'play' });
                            } else if (el) {
                                el.play();
                            }
                        } else if (action === 'pause') {
                            if (mediaSessionHandlers['pause']) {
                                mediaSessionHandlers['pause']({ action: 'pause' });
                            } else if (el) {
                                el.pause();
                            }
                        } else if (action === 'toggle') {
                            var currentlyPlaying = el ? !el.paused : (navigator.mediaSession && navigator.mediaSession.playbackState === 'playing');
                            if (currentlyPlaying) {
                                window.__podBridgeControl('pause', 0);
                            } else {
                                window.__podBridgeControl('play', 0);
                            }
                        } else if (action === 'seekTo') {
                            var targetSec = Math.max(0, Number(value) || 0);
                            if (mediaSessionHandlers['seekto']) {
                                mediaSessionHandlers['seekto']({ action: 'seekto', seekTime: targetSec, fastSeek: false });
                            } else if (el) {
                                el.currentTime = targetSec;
                            }
                        } else if (action === 'skipForward') {
                            var offsetFwd = Number(value) || 30;
                            if (mediaSessionHandlers['seekforward']) {
                                mediaSessionHandlers['seekforward']({ action: 'seekforward', seekOffset: offsetFwd });
                            } else if (mediaSessionHandlers['nexttrack']) {
                                mediaSessionHandlers['nexttrack']({ action: 'nexttrack' });
                            } else if (el) {
                                el.currentTime = Math.min(el.duration || Infinity, (el.currentTime || 0) + offsetFwd);
                            }
                        } else if (action === 'skipBackward') {
                            var offsetBack = Number(value) || 15;
                            if (mediaSessionHandlers['seekbackward']) {
                                mediaSessionHandlers['seekbackward']({ action: 'seekbackward', seekOffset: offsetBack });
                            } else if (mediaSessionHandlers['previoustrack']) {
                                mediaSessionHandlers['previoustrack']({ action: 'previoustrack' });
                            } else if (el) {
                                el.currentTime = Math.max(0, (el.currentTime || 0) - offsetBack);
                            }
                        } else if (action === 'setRate' && el) {
                            el.playbackRate = Number(value) || 1.0;
                        }
                        setTimeout(function() { reportToAndroid(el); }, 100);
                        return true;
                    } catch (e) {}
                    return false;
                };

                // 3. Android Password Manager & Autofill enhancement for Apple ID & Web sign-in forms
                function enhanceAutofillInputs(root) {
                    try {
                        var inputs = root.querySelectorAll('input');
                        for (var i = 0; i < inputs.length; i++) {
                            var inp = inputs[i];
                            if (inp.__podBridgeAutofillBound) continue;
                            var type = (inp.getAttribute('type') || 'text').toLowerCase();
                            var name = (inp.getAttribute('name') || '').toLowerCase();
                            var id = (inp.getAttribute('id') || '').toLowerCase();
                            var placeholder = (inp.getAttribute('placeholder') || '').toLowerCase();

                            var isUserField = (
                                type === 'email' ||
                                name.indexOf('account') !== -1 ||
                                name.indexOf('appleid') !== -1 ||
                                name.indexOf('email') !== -1 ||
                                id.indexOf('account_name') !== -1 ||
                                placeholder.indexOf('apple') !== -1 ||
                                placeholder.indexOf('email') !== -1
                            );
                            var isPassField = (
                                type === 'password' ||
                                id.indexOf('password') !== -1 ||
                                name.indexOf('password') !== -1
                            );

                            if (isUserField) {
                                if (!inp.getAttribute('autocomplete')) {
                                    inp.setAttribute('autocomplete', 'username webauthn');
                                }
                                inp.__podBridgeAutofillBound = true;
                                inp.addEventListener('focus', function() {
                                    if (window.PodBridgeNative) {
                                        window.PodBridgeNative.onLoginFieldFocused(window.location.hostname, 'username');
                                    }
                                });
                            } else if (isPassField) {
                                if (!inp.getAttribute('autocomplete')) {
                                    inp.setAttribute('autocomplete', 'current-password');
                                }
                                inp.__podBridgeAutofillBound = true;
                                inp.addEventListener('focus', function() {
                                    if (window.PodBridgeNative) {
                                        window.PodBridgeNative.onLoginFieldFocused(window.location.hostname, 'password');
                                    }
                                });
                                inp.addEventListener('change', function() {
                                    var uEl = root.querySelector('input[autocomplete*="username"], input[type="email"], #account_name_text_field');
                                    if (uEl && uEl.value && this.value && window.PodBridgeNative) {
                                        window.PodBridgeNative.onCredentialSubmitted(uEl.value, this.value);
                                    }
                                });
                            }
                        }
                    } catch (e) {}
                }

                window.__podBridgeFillCredentials = function(username, password) {
                    function setNativeValue(element, value) {
                        var valueSetter = Object.getOwnPropertyDescriptor(element, 'value')?.set;
                        var prototype = Object.getPrototypeOf(element);
                        var prototypeValueSetter = Object.getPrototypeOf(prototype, 'value')?.set;
                        if (valueSetter && valueSetter !== prototypeValueSetter) {
                            prototypeValueSetter.call(element, value);
                        } else if (valueSetter) {
                            valueSetter.call(element, value);
                        } else {
                            element.value = value;
                        }
                        element.dispatchEvent(new Event('input', { bubbles: true }));
                        element.dispatchEvent(new Event('change', { bubbles: true }));
                    }

                    var userFields = document.querySelectorAll(
                        '#account_name_text_field, input[autocomplete*="username"], input[type="email"], input[name*="account"], input[type="text"]'
                    );
                    for (var i = 0; i < userFields.length; i++) {
                        if (userFields[i].offsetParent !== null) {
                            setNativeValue(userFields[i], username);
                            break;
                        }
                    }

                    var passFields = document.querySelectorAll(
                        '#password_text_field, input[type="password"], input[autocomplete*="password"]'
                    );
                    for (var j = 0; j < passFields.length; j++) {
                        if (passFields[j].offsetParent !== null) {
                            setNativeValue(passFields[j], password);
                            break;
                        }
                    }
                };

                setInterval(function() {
                    var all = findAllMediaElements(document);
                    for (var i = 0; i < all.length; i++) bindMediaElement(all[i]);
                    if ((activeMediaElement && !activeMediaElement.paused) ||
                        (navigator.mediaSession && navigator.mediaSession.playbackState === 'playing')) {
                        reportToAndroid(activeMediaElement);
                    }
                    enhanceAutofillInputs(document);
                }, 1000);
            })();
        """.trimIndent()
    }
}
