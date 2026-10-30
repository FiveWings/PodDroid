package com.example.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.example.MainActivity
import com.example.R
import com.example.model.MediaCommand
import com.example.model.WebMediaMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PodcastMediaService : Service() {

    companion object {
        const val CHANNEL_ID = "podbridge_media_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.example.media.ACTION_PLAY"
        const val ACTION_PAUSE = "com.example.media.ACTION_PAUSE"
        const val ACTION_REWIND_15 = "com.example.media.ACTION_REWIND_15"
        const val ACTION_FORWARD_30 = "com.example.media.ACTION_FORWARD_30"
        const val ACTION_STOP = "com.example.media.ACTION_STOP"

        fun ensureStarted(context: Context) {
            val intent = Intent(context, PodcastMediaService::class.java)
            try {
                context.startService(intent)
            } catch (_: Exception) {
                // Ignored if background start restricted prior to playback
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var mediaSession: MediaSession
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var isForegroundStarted = false

    private var cachedArtworkUrl: String = ""
    private var cachedArtworkBitmap: Bitmap? = null
    private val defaultArtworkBitmap: Bitmap by lazy { createDefaultPodcastArtwork() }

    private val noisyAudioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                MediaBridgeController.dispatchCommand(MediaCommand.Pause)
            }
        }
    }
    private var isNoisyReceiverRegistered = false

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasAudioFocus = false
                MediaBridgeController.dispatchCommand(MediaCommand.Pause)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasAudioFocus = true
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()

        mediaSession = MediaSession(this, "PodBridgeMediaSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() {
                    requestAudioFocusIfNeeded()
                    MediaBridgeController.dispatchCommand(MediaCommand.Play)
                }

                override fun onPause() {
                    MediaBridgeController.dispatchCommand(MediaCommand.Pause)
                }

                override fun onSkipToPrevious() {
                    MediaBridgeController.dispatchCommand(MediaCommand.SkipBackward(15))
                }

                override fun onSkipToNext() {
                    MediaBridgeController.dispatchCommand(MediaCommand.SkipForward(30))
                }

                override fun onRewind() {
                    MediaBridgeController.dispatchCommand(MediaCommand.SkipBackward(15))
                }

                override fun onFastForward() {
                    MediaBridgeController.dispatchCommand(MediaCommand.SkipForward(30))
                }

                override fun onSeekTo(pos: Long) {
                    MediaBridgeController.dispatchCommand(MediaCommand.SeekTo(pos))
                }

                override fun onSetPlaybackSpeed(speed: Float) {
                    MediaBridgeController.dispatchCommand(MediaCommand.SetPlaybackSpeed(speed))
                }

                override fun onStop() {
                    MediaBridgeController.dispatchCommand(MediaCommand.Pause)
                    stopForegroundSafely()
                }
            })

            val sessionIntent = Intent(this@PodcastMediaService, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val sessionPendingIntent = PendingIntent.getActivity(
                this@PodcastMediaService,
                0,
                sessionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            setSessionActivity(sessionPendingIntent)
            isActive = true
        }

        serviceScope.launch {
            MediaBridgeController.mediaState.collectLatest { state ->
                onMediaStateUpdated(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                requestAudioFocusIfNeeded()
                MediaBridgeController.dispatchCommand(MediaCommand.Play)
            }
            ACTION_PAUSE -> {
                MediaBridgeController.dispatchCommand(MediaCommand.Pause)
            }
            ACTION_REWIND_15 -> {
                MediaBridgeController.dispatchCommand(MediaCommand.SkipBackward(15))
            }
            ACTION_FORWARD_30 -> {
                MediaBridgeController.dispatchCommand(MediaCommand.SkipForward(30))
            }
            ACTION_STOP -> {
                MediaBridgeController.dispatchCommand(MediaCommand.Pause)
                stopForegroundSafely()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private suspend fun onMediaStateUpdated(state: WebMediaMetadata) {
        if (!state.hasActiveMedia && !state.isPlaying) {
            return
        }

        if (state.isPlaying) {
            requestAudioFocusIfNeeded()
            registerNoisyReceiver()
        } else {
            unregisterNoisyReceiver()
        }

        val artwork = resolveArtwork(state.artworkUrl)
        updateMediaSession(state, artwork)
        val notification = buildMediaNotification(state, artwork)

        if (state.isPlaying) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                isForegroundStarted = true
            } catch (_: Exception) {
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        } else {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun updateMediaSession(state: WebMediaMetadata, artwork: Bitmap) {
        val metadataBuilder = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, state.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, state.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, state.album)
            .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, artwork)
            .putBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON, artwork)

        if (state.durationMs > 0L) {
            metadataBuilder.putLong(MediaMetadata.METADATA_KEY_DURATION, state.durationMs)
        }
        mediaSession.setMetadata(metadataBuilder.build())

        val actions = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SEEK_TO or
            PlaybackState.ACTION_REWIND or
            PlaybackState.ACTION_FAST_FORWARD or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SET_PLAYBACK_SPEED

        val playbackState = PlaybackState.Builder()
            .setActions(actions)
            .setState(
                if (state.isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                state.positionMs,
                if (state.isPlaying) state.playbackRate else 0f
            )
            .build()

        mediaSession.setPlaybackState(playbackState)
    }

    private fun buildMediaNotification(state: WebMediaMetadata, artwork: Bitmap): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            10,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rewindAction = Notification.Action.Builder(
            R.drawable.ic_notif_replay_15,
            getString(R.string.action_skip_back),
            createServicePendingIntent(ACTION_REWIND_15, 11)
        ).build()

        val playPauseAction = if (state.isPlaying) {
            Notification.Action.Builder(
                R.drawable.ic_notif_pause,
                getString(R.string.action_play_pause),
                createServicePendingIntent(ACTION_PAUSE, 12)
            ).build()
        } else {
            Notification.Action.Builder(
                R.drawable.ic_notif_play,
                getString(R.string.action_play_pause),
                createServicePendingIntent(ACTION_PLAY, 13)
            ).build()
        }

        val forwardAction = Notification.Action.Builder(
            R.drawable.ic_notif_forward_30,
            getString(R.string.action_skip_forward),
            createServicePendingIntent(ACTION_FORWARD_30, 14)
        ).build()

        val mediaStyle = Notification.MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setSmallIcon(R.drawable.ic_notif_podcast)
            .setLargeIcon(artwork)
            .setContentTitle(state.title)
            .setContentText(state.artist)
            .setSubText(state.album)
            .setContentIntent(contentPendingIntent)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(state.isPlaying)
            .setOnlyAlertOnce(true)
            .addAction(rewindAction)
            .addAction(playPauseAction)
            .addAction(forwardAction)
            .setStyle(mediaStyle)
            .build()
    }

    private fun createServicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, PodcastMediaService::class.java).apply {
            this.action = action
        }
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private suspend fun resolveArtwork(url: String): Bitmap {
        if (url.isBlank()) return defaultArtworkBitmap
        if (url == cachedArtworkUrl && cachedArtworkBitmap != null) {
            return cachedArtworkBitmap!!
        }
        return try {
            val loader = ImageLoader(this)
            val request = ImageRequest.Builder(this)
                .data(url)
                .allowHardware(false)
                .size(512, 512)
                .build()
            val result = loader.execute(request)
            if (result is SuccessResult) {
                val bmp = (result.drawable as? BitmapDrawable)?.bitmap
                if (bmp != null) {
                    cachedArtworkUrl = url
                    cachedArtworkBitmap = bmp
                    return bmp
                }
            }
            defaultArtworkBitmap
        } catch (_: Exception) {
            defaultArtworkBitmap
        }
    }

    private fun createDefaultPodcastArtwork(): Bitmap {
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, size.toFloat(), size.toFloat(),
                intArrayOf(0xFF581C87.toInt(), 0xFF9333EA.toInt(), 0xFFDB2777.toInt()),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), bgPaint)

        val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x44FFFFFF
            style = Paint.Style.STROKE
            strokeWidth = 14f
        }
        canvas.drawCircle(size / 2f, size / 2f, 150f, ringPaint)
        canvas.drawCircle(size / 2f, size / 2f, 105f, ringPaint)

        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, 44f, dotPaint)
        return bitmap
    }

    private fun requestAudioFocusIfNeeded() {
        if (hasAudioFocus) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioFocusRequest = request
            val res = audioManager.requestAudioFocus(request)
            hasAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        } else {
            @Suppress("DEPRECATION")
            val res = audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            hasAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        }
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
        hasAudioFocus = false
    }

    private fun registerNoisyReceiver() {
        if (!isNoisyReceiverRegistered) {
            registerReceiver(
                noisyAudioReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            )
            isNoisyReceiverRegistered = true
        }
    }

    private fun unregisterNoisyReceiver() {
        if (isNoisyReceiverRegistered) {
            try {
                unregisterReceiver(noisyAudioReceiver)
            } catch (_: Exception) {
            }
            isNoisyReceiverRegistered = false
        }
    }

    private fun stopForegroundSafely() {
        if (isForegroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundStarted = false
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        unregisterNoisyReceiver()
        abandonAudioFocus()
        mediaSession.isActive = false
        mediaSession.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
