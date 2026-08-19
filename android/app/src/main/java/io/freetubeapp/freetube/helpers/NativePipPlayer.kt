package io.freetubeapp.freetube.helpers

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import io.freetubeapp.freetube.R
import java.io.File
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/**
 * Plays the current watch-page stream on a TextureView so Android system
 * Picture-in-Picture has a real composited surface.
 *
 * WebView HTML5/Shaka video is decoded onto a hardware overlay / SurfaceView
 * that PiP cannot capture, which is why the floating window was black.
 */
class NativePipPlayer(private val context: Context) {
  val playerView: PlayerView = View.inflate(context, R.layout.pip_player, null) as PlayerView
  private val mainHandler = Handler(Looper.getMainLooper())
  private var player: ExoPlayer? = null
  var lastUrl: String? = null
    private set

  init {
    playerView.layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
    playerView.setBackgroundColor(Color.BLACK)
    playerView.setShutterBackgroundColor(Color.BLACK)
    playerView.useController = false
  }

  val isPlaying: Boolean
    get() = player?.isPlaying == true

  val positionMs: Long
    get() = player?.currentPosition ?: 0L

  fun attachTo(parent: ViewGroup) {
    if (playerView.parent !== parent) {
      (playerView.parent as? ViewGroup)?.removeView(playerView)
      parent.addView(playerView)
    }
  }

  fun start(
    url: String,
    mimeType: String?,
    positionMs: Long,
    thumbnailUrl: String?,
    userAgent: String,
    playWhenReady: Boolean,
    onError: () -> Unit
  ) {
    lastUrl = url
    loadThumbnail(thumbnailUrl)
    playerView.visibility = View.VISIBLE

    val exo = player ?: createPlayer(userAgent, onError).also { player = it }
    playerView.player = exo

    val mediaItem = MediaItem.Builder()
      .setUri(resolvePlayableUri(url))
      .apply {
        guessMimeType(url, mimeType)?.let { setMimeType(it) }
      }
      .build()

    exo.setMediaItem(mediaItem, positionMs.coerceAtLeast(0L))
    exo.prepare()
    exo.playWhenReady = playWhenReady
  }

  fun showPosterOnly(thumbnailUrl: String?) {
    loadThumbnail(thumbnailUrl)
    playerView.visibility = View.VISIBLE
  }

  fun play() {
    player?.play()
  }

  fun pause() {
    player?.pause()
  }

  fun stop() {
    player?.stop()
    playerView.player = null
    playerView.visibility = View.GONE
    lastUrl = null
  }

  fun release() {
    playerView.player = null
    player?.release()
    player = null
    playerView.visibility = View.GONE
    lastUrl = null
  }

  private fun createPlayer(userAgent: String, onError: () -> Unit): ExoPlayer {
    val httpFactory = DefaultHttpDataSource.Factory()
      .setUserAgent(userAgent)
      .setAllowCrossProtocolRedirects(true)
    val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
    val exo = ExoPlayer.Builder(context)
      .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
      .build()
    exo.addListener(object : Player.Listener {
      override fun onPlayerError(error: PlaybackException) {
        onError()
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
        mainHandler.post {
          playerView.defaultArtwork = BitmapDrawable(context.resources, bitmap)
        }
      } catch (_: Exception) {
        // Poster is optional; the TextureView still shows decoded frames.
      }
    }
  }
}
