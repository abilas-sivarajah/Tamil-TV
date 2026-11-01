package com.tvclient.app.ui.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.tvclient.app.R
import com.tvclient.app.data.model.Movie
import com.tvclient.app.data.model.StreamResponse
import com.tvclient.app.data.model.SubtitleTrack
import com.tvclient.app.data.remote.ApiClient
import com.tvclient.app.data.remote.MediaRepository
import com.tvclient.app.databinding.ActivityPlayerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Vollbild-Wiedergabe mit Media3/ExoPlayer.
 *
 * - Hardware-Decoding mit Software-Fallback (wichtig auf dem Stick: manche
 *   Streams haben Profile, die der HW-Decoder ablehnt).
 * - Alle Header aus [StreamResponse.headers] gehen an jede HTTP-Anfrage,
 *   auch an die Segment-Requests einer HLS-Playlist.
 * - Komplette Steuerung per Fernbedienung, inkl. der Medientasten.
 */
// OptIn statt @UnstableApi: sonst muesste jeder Aufrufer von PlayerActivity.intent() mit opt-in
@OptIn(UnstableApi::class)
class PlayerActivity : FragmentActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private var streamInfo: StreamResponse? = null
    private var movie: Movie? = null
    private var resolveJob: Job? = null

    private var playbackPosition: Long = C.TIME_UNSET
    private var playWhenReady: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enterFullscreen()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        movie = IntentCompat.getSerializableExtra(intent, EXTRA_MOVIE, Movie::class.java)
        streamInfo = savedInstanceState?.let {
            BundleCompat.getSerializable(it, STATE_STREAM, StreamResponse::class.java)
        }
        playbackPosition = savedInstanceState?.getLong(STATE_POSITION, C.TIME_UNSET) ?: C.TIME_UNSET
        playWhenReady = savedInstanceState?.getBoolean(STATE_PLAY_WHEN_READY, true) ?: true

        binding.title.text = movie?.title ?: streamInfo?.title.orEmpty()
        binding.retryButton.setOnClickListener { retry() }

        if (movie == null && streamInfo == null) {
            showError(getString(R.string.player_no_source))
            return
        }
        resolveAndPlay()
    }

    /**
     * singleTask: ein zweiter Start (z. B. schneller Doppelklick oder eine andere Karte)
     * landet hier statt in onCreate - ohne diesen Override wuerde er ignoriert.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val newMovie = IntentCompat.getSerializableExtra(intent, EXTRA_MOVIE, Movie::class.java)
            ?: return
        if (newMovie.id == movie?.id) return
        setIntent(intent)

        resolveJob?.cancel()
        releasePlayer(keepPosition = false)
        movie = newMovie
        streamInfo = null
        playbackPosition = C.TIME_UNSET
        playWhenReady = true
        binding.title.text = newMovie.title
        resolveAndPlay()
    }

    // ------------------------------------------------------------------ Stream

    private fun retry() {
        // Nach einem Wiedergabefehler ist die aufgeloeste URL oft abgelaufen (Token, 403) -
        // dieselbe URL nochmal zu spielen hilft dann nicht, also neu aufloesen.
        val target = movie
        if (target != null && target.stream == null && streamInfo != null) {
            MediaRepository.invalidateStream(target)
            streamInfo = null
        }
        resolveAndPlay()
    }

    private fun resolveAndPlay() {
        val cached = streamInfo
        if (cached != null) {
            startPlayback(cached)
            return
        }
        val target = movie ?: return
        showLoading()

        resolveJob?.cancel()
        resolveJob = lifecycleScope.launch {
            MediaRepository.stream(target)
                .onSuccess { info ->
                    streamInfo = info
                    if (info.startPositionMs != null && playbackPosition == C.TIME_UNSET) {
                        playbackPosition = info.startPositionMs
                    }
                    // App inzwischen verlassen? Dann nicht im Hintergrund losspielen -
                    // onStart() startet mit dem gemerkten streamInfo, sobald sie zurueck ist.
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        startPlayback(info)
                    }
                }
                .onFailure {
                    showError(getString(R.string.player_resolve_failed, it.shortMessage()))
                }
        }
    }

    private fun startPlayback(info: StreamResponse) {
        releasePlayer(keepPosition = true)
        binding.errorGroup.visibility = View.GONE

        val trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setPreferredTextLanguages("de", "en")
                .build()
        }

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        val exo = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory(info)))
            .setSeekBackIncrementMs(SEEK_STEP_MS)
            .setSeekForwardIncrementMs(SEEK_STEP_MS)
            .build()
            .apply {
                setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ true)
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_NETWORK)
                addListener(playerListener)
            }

        exo.setMediaItem(buildMediaItem(info))
        if (playbackPosition != C.TIME_UNSET) exo.seekTo(playbackPosition)
        exo.playWhenReady = playWhenReady
        exo.prepare()

        binding.playerView.player = exo
        binding.playerView.setControllerShowTimeoutMs(CONTROLLER_TIMEOUT_MS)
        binding.playerView.requestFocus()
        player = exo
    }

    /**
     * OkHttp als Transport, damit Verbindungspool und Timeouts mit dem Rest der
     * App geteilt werden. setDefaultRequestProperties greift fuer die Playlist
     * UND die Segmente - genau das brauchen Quellen mit Referer-Pruefung.
     */
    private fun dataSourceFactory(info: StreamResponse): DataSource.Factory {
        val httpFactory = OkHttpDataSource.Factory(ApiClient.okHttp)
            .setUserAgent(info.userAgent ?: DEFAULT_USER_AGENT)

        info.extraHeaders.takeIf { it.isNotEmpty() }?.let {
            httpFactory.setDefaultRequestProperties(it)
        }
        // DefaultDataSource erlaubt zusaetzlich file:// und content:// Quellen
        return DefaultDataSource.Factory(this, httpFactory)
    }

    private fun buildMediaItem(info: StreamResponse): MediaItem {
        val builder = MediaItem.Builder().setUri(info.url)

        mimeTypeFor(info.type)?.let { builder.setMimeType(it) }

        info.subtitles
            ?.map { it.toSubtitleConfiguration() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { builder.setSubtitleConfigurations(it) }

        return builder.build()
    }

    /** null = Media3 erkennt den Typ selbst anhand der Endung. */
    private fun mimeTypeFor(type: String?): String? = when (type?.lowercase()) {
        "hls", "m3u8" -> MimeTypes.APPLICATION_M3U8
        "dash", "mpd" -> MimeTypes.APPLICATION_MPD
        "mp4", "progressive" -> MimeTypes.VIDEO_MP4
        else -> null
    }

    private fun SubtitleTrack.toSubtitleConfiguration(): MediaItem.SubtitleConfiguration =
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
            .setMimeType(mimeType ?: MimeTypes.TEXT_VTT)
            .setLanguage(language)
            .setLabel(label)
            .build()

    // ---------------------------------------------------------------- Listener

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            binding.progress.visibility =
                if (playbackState == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (playbackState == Player.STATE_READY) {
                binding.errorGroup.visibility = View.GONE
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // Live-TV: nach Pufferpausen aus dem Live-Fenster gefallen -> zurueck an die Live-Kante
            val exo = player
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && exo != null) {
                exo.seekToDefaultPosition()
                exo.prepare()
                return
            }
            showError(getString(R.string.player_playback_failed, error.errorCodeName))
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Titel-Overlay nur im Pausenzustand einblenden
            binding.title.visibility = if (isPlaying) View.GONE else View.VISIBLE
        }
    }

    // ----------------------------------------------------------- Fernbedienung

    // Lint-Fehlalarm: super.dispatchKeyEvent wird faelschlich der @RestrictTo-Variante
    // aus androidx.core.app.ComponentActivity zugeordnet
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val exo = player
        // Bei sichtbarer Fehlermeldung gehoert die Fernbedienung dem "Erneut versuchen"-Button,
        // sonst schluckt OK den Klick als Play/Pause
        if (exo == null || event.action != KeyEvent.ACTION_DOWN || binding.errorGroup.isVisible) {
            return super.dispatchKeyEvent(event)
        }

        // Medientasten immer direkt bedienen
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                exo.play(); return true
            }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                exo.pause(); return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> {
                togglePlayPause(exo); return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                seekBy(exo, SEEK_STEP_MS); return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                seekBy(exo, -SEEK_STEP_MS); return true
            }
            KeyEvent.KEYCODE_MEDIA_STOP -> {
                finish(); return true
            }
        }

        // Steuerkreuz: solange die Bedienleiste aus ist, selbst reagieren.
        // Ist sie sichtbar, bekommt sie die Tasten (Fokus auf Buttons/Seekbar).
        if (!binding.playerView.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BUTTON_A -> {
                    togglePlayPause(exo)
                    binding.playerView.showController()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    seekBy(exo, if (event.repeatCount > 0) SEEK_STEP_LONG_MS else SEEK_STEP_MS)
                    binding.playerView.showController()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    seekBy(exo, if (event.repeatCount > 0) -SEEK_STEP_LONG_MS else -SEEK_STEP_MS)
                    binding.playerView.showController()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP,
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    binding.playerView.showController()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun togglePlayPause(exo: ExoPlayer) {
        if (exo.isPlaying) exo.pause() else exo.play()
    }

    private fun seekBy(exo: ExoPlayer, deltaMs: Long) {
        val duration = exo.duration
        val target = (exo.currentPosition + deltaMs).coerceAtLeast(0L)
        exo.seekTo(if (duration != C.TIME_UNSET) target.coerceAtMost(duration) else target)
    }

    // ------------------------------------------------------------- UI-Zustand

    private fun showLoading() {
        binding.progress.visibility = View.VISIBLE
        binding.errorGroup.visibility = View.GONE
    }

    private fun showError(message: String) {
        binding.progress.visibility = View.GONE
        binding.errorGroup.visibility = View.VISIBLE
        binding.errorText.text = message
        binding.retryButton.requestFocus()
    }

    private fun enterFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // -------------------------------------------------------------- Lifecycle

    override fun onStart() {
        super.onStart()
        if (player == null) streamInfo?.let { startPlayback(it) }
    }

    override fun onResume() {
        super.onResume()
        enterFullscreen()
        // Nur onPause() ohne onStop() (z. B. Alexa-Overlay): wieder weiterspielen, wenn es vorher lief
        if (playWhenReady) player?.play()
    }

    override fun onPause() {
        super.onPause()
        player?.let {
            playWhenReady = it.playWhenReady
            it.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        // Der Video-Decoder ist auf dem Stick eine knappe Ressource -> freigeben
        releasePlayer(keepPosition = true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let { playbackPosition = resumePosition(it) }
        outState.putLong(STATE_POSITION, playbackPosition)
        outState.putBoolean(STATE_PLAY_WHEN_READY, playWhenReady)
        outState.putSerializable(STATE_STREAM, streamInfo)
    }

    private fun releasePlayer(keepPosition: Boolean) {
        player?.let { exo ->
            if (keepPosition) {
                playbackPosition = resumePosition(exo)
                // Nach onPause() steht der Player nur lifecycle-bedingt auf Pause - der dort
                // gesicherte Zustand darf hier nicht mit "pausiert" ueberschrieben werden
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    playWhenReady = exo.playWhenReady
                }
            }
            exo.removeListener(playerListener)
            exo.release()
        }
        binding.playerView.player = null
        player = null
    }

    /** Live-Streams nie an einer alten Position fortsetzen, sondern immer an der Live-Kante. */
    private fun resumePosition(exo: ExoPlayer): Long =
        if (exo.isCurrentMediaItemLive || movie?.isLiveTv == true) C.TIME_UNSET else exo.currentPosition

    companion object {
        private const val EXTRA_MOVIE = "extra_movie"
        private const val STATE_POSITION = "state_position"
        private const val STATE_PLAY_WHEN_READY = "state_play_when_ready"
        private const val STATE_STREAM = "state_stream"

        private const val SEEK_STEP_MS = 10_000L
        private const val SEEK_STEP_LONG_MS = 60_000L
        private const val CONTROLLER_TIMEOUT_MS = 4_000
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 9; AFTKA) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        fun intent(context: Context, movie: Movie): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_MOVIE, movie)
    }
}

/** Erste Zeile einer Exception-Meldung, gekuerzt fuer die Anzeige auf dem TV. */
private fun Throwable.shortMessage(): String =
    (message ?: this::class.java.simpleName).take(160)
