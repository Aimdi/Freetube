package io.freetubeapp.freetube.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import io.freetubeapp.freetube.R
import java.io.File
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/**
 * Plays the current watch-page stream on a TextureView so Android system
 * Picture-in-Picture has a real composited surface. WebView HTML5/Shaka video
 * is not reliably captured by the system PiP window.
 */
@UnstableApi
class NativePipPlayer(private val context: Context) {
  private val overlay = FrameLayout(context).apply {
    layoutParams = CoordinatorLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
    setBackgroundColor(Color.BLACK)
    visibility = View.GONE
    elevation = 64f
    isClickable = true
  }
  private val posterView = ImageView(context).apply {
    layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT, Gravity.CENTER)
    scaleType = ImageView.ScaleType.FIT_CENTER
    setBackgroundColor(Color.BLACK)
  }
  val playerView: PlayerView = View.inflate(context, R.layout.pip_player, null) as PlayerView
  private val mainHandler = Handler(Looper.getMainLooper())
  private var player: ExoPlayer? = null
  var lastSessionKey: String? = null
    private set

  init {
    playerView.layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
    playerView.setBackgroundColor(Color.BLACK)
    playerView.setShutterBackgroundColor(Color.BLACK)
    playerView.useController = false
    playerView.visibility = View.VISIBLE
    overlay.addView(posterView)
    overlay.addView(playerView)
  }

  val isPlaying: Boolean
    get() = player?.isPlaying == true

  val positionMs: Long
    get() = player?.currentPosition ?: 0L

  val playbackRate: Float
    get() = player?.playbackParameters?.speed ?: 1.0f

  fun attachTo(parent: ViewGroup) {
    if (overlay.parent !== parent) {
      (overlay.parent as? ViewGroup)?.removeView(overlay)
      val params = if (parent is CoordinatorLayout) {
        CoordinatorLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
      } else {
        ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
      }
      overlay.layoutParams = params
      parent.addView(overlay)
    }
  }

  fun showOverlay() {
    overlay.visibility = View.VISIBLE
    overlay.bringToFront()
    overlay.requestLayout()
    Log.d(TAG, "overlay shown")
  }

  fun start(
    session: NativePipSession,
    userAgent: String,
    playWhenReady: Boolean,
    onFirstFrame: () -> Unit,
    onError: (String) -> Unit
  ) {
    lastSessionKey = session.key()
    loadThumbnail(session.thumbnailUrl)
    showOverlay()
    posterView.visibility = View.VISIBLE

    val exo = player ?: createPlayer(userAgent, session.preferredHeight, onFirstFrame, onError).also {
      player = it
    }
    playerView.player = exo
    playerView.visibility = View.VISIBLE

    try {
      val mediaSourceFactory = DefaultMediaSourceFactory(
        DefaultDataSource.Factory(context, GoogleVideoDataSource.Factory(userAgent))
      )
      val videoItem = MediaItem.Builder()
        .setUri(resolvePlayableUri(session.url))
        .apply { guessMimeType(session.url, session.mimeType)?.let { setMimeType(it) } }
        .build()

      val mediaSource = if (!session.audioUrl.isNullOrBlank()) {
        val audioItem = MediaItem.Builder()
          .setUri(resolvePlayableUri(session.audioUrl))
          .apply { guessMimeType(session.audioUrl, session.audioMimeType)?.let { setMimeType(it) } }
          .build()
        MergingMediaSource(
          mediaSourceFactory.createMediaSource(videoItem),
          mediaSourceFactory.createMediaSource(audioItem)
        )
      } else {
        mediaSourceFactory.createMediaSource(videoItem)
      }

      exo.setMediaSource(mediaSource, session.positionMs.coerceAtLeast(0L))
      exo.setPlaybackSpeed(session.playbackRate.coerceIn(0.25f, 3.0f))
      exo.prepare()
      exo.playWhenReady = playWhenReady
      Log.d(
        TAG,
        "start url=${session.url.take(96)} mime=${session.mimeType} pos=${session.positionMs} " +
          "height=${session.preferredHeight} rate=${session.playbackRate} play=$playWhenReady"
      )
    } catch (error: Exception) {
      Log.e(TAG, "failed to start native player", error)
      onError(error.message ?: "start-failed")
    }
  }

  fun showPosterOnly(thumbnailUrl: String?) {
    loadThumbnail(thumbnailUrl)
    showOverlay()
  }

  fun setPosterBitmap(bitmap: Bitmap?) {
    if (bitmap == null) {
      return
    }
    mainHandler.post {
      posterView.setImageBitmap(bitmap)
      playerView.defaultArtwork = BitmapDrawable(context.resources, bitmap)
    }
  }

  fun play() {
    Log.d(TAG, "play")
    player?.play()
  }

  fun pause() {
    Log.d(TAG, "pause")
    player?.pause()
  }

  fun stop() {
    Log.d(TAG, "stop pos=${player?.currentPosition}")
    player?.stop()
    playerView.player = null
    overlay.visibility = View.GONE
    lastSessionKey = null
  }

  fun release() {
    Log.d(TAG, "release")
    playerView.player = null
    player?.release()
    player = null
    overlay.visibility = View.GONE
    lastSessionKey = null
  }

  fun clearLastSession() {
    lastSessionKey = null
  }

  private fun createPlayer(
    userAgent: String,
    preferredHeight: Int,
    onFirstFrame: () -> Unit,
    onError: (String) -> Unit
  ): ExoPlayer {
    val trackSelector = DefaultTrackSelector(context).apply {
      val maxHeight = preferredHeight.coerceAtLeast(720)
      setParameters(
        buildUponParameters()
          .setMaxVideoSize(4096, maxHeight)
          .setForceHighestSupportedBitrate(true)
      )
    }
    val dataSourceFactory = DefaultDataSource.Factory(context, GoogleVideoDataSource.Factory(userAgent))
    val exo = ExoPlayer.Builder(context)
      .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
      .setTrackSelector(trackSelector)
      .build()
    exo.addListener(object : Player.Listener {
      override fun onRenderedFirstFrame() {
        Log.d(TAG, "first frame rendered")
        posterView.visibility = View.GONE
        onFirstFrame()
      }

      override fun onPlayerError(error: PlaybackException) {
        Log.e(TAG, "player error code=${error.errorCodeName} ${error.message}", error)
        posterView.visibility = View.VISIBLE
        onError(error.errorCodeName)
      }

      override fun onPlaybackStateChanged(playbackState: Int) {
        Log.d(TAG, "playback state=$playbackState playing=${exo.isPlaying}")
      }
    })
    return exo
  }

  private fun resolvePlayableUri(raw: String): Uri {
    if (raw.startsWith("data:application/dash")) {
      val comma = raw.indexOf(',')
      if (comma > 0) {
        val xml = URLDecoder.decode(raw.substring(comma + 1), StandardCharsets.UTF_8.name())
        val file = File(context.cacheDir, "pip-manifest.mpd")
        file.writeText(xml)
        Log.d(TAG, "wrote DASH manifest ${file.length()} bytes")
        return Uri.fromFile(file)
      }
    }
    return Uri.parse(raw)
  }

  private fun guessMimeType(url: String, mimeType: String?): String? {
    val given = mimeType?.substringBefore(';')?.trim()?.lowercase()
    return when {
      given == "application/dash+xml" || url.startsWith("data:application/dash") || url.contains(".mpd") ->
        MimeTypes.APPLICATION_MPD
      given == "application/x-mpegurl" || given == "application/vnd.apple.mpegurl" || url.contains(".m3u8") ->
        MimeTypes.APPLICATION_M3U8
      given?.startsWith("video/") == true -> given
      given?.startsWith("audio/") == true -> given
      else -> null
    }
  }

  private fun loadThumbnail(thumbnailUrl: String?) {
    if (thumbnailUrl.isNullOrBlank()) {
      return
    }
    thread(name = "pip-thumb") {
      try {
        val bytes = URL(thumbnailUrl).readBytes()
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@thread
        setPosterBitmap(bitmap)
      } catch (error: Exception) {
        Log.d(TAG, "thumbnail load failed: ${error.message}")
      }
    }
  }

  companion object {
    private const val TAG = "FreeTubePip"

    fun decodeDataUrl(dataUrl: String): Bitmap? {
      val comma = dataUrl.indexOf(',')
      if (comma < 0) {
        return null
      }
      return try {
        val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
      } catch (_: Exception) {
        null
      }
    }
  }
}

data class NativePipSession(
  val url: String,
  val mimeType: String?,
  val audioUrl: String?,
  val audioMimeType: String?,
  val positionMs: Long,
  val playbackRate: Float,
  val thumbnailUrl: String?,
  val preferredHeight: Int
) {
  fun key(): String = "$url|$audioUrl|$preferredHeight"
}
