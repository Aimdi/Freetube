package io.freetubeapp.freetube

import android.app.PictureInPictureParams
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import io.freetubeapp.freetube.helpers.NativePipPlayer
import io.freetubeapp.freetube.helpers.PictureInPictureHelper
import io.freetubeapp.freetube.helpers.PipPlaybackSession

/**
 * Isolated player window for system Picture-in-Picture.
 * No WebView — Chromium's video overlay cannot exist in this activity.
 */
class PipPlayerActivity : AppCompatActivity() {
  private lateinit var nativePipPlayer: NativePipPlayer
  private var finishingPip = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val root = FrameLayout(this).apply {
      setBackgroundColor(Color.BLACK)
      layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
      )
    }
    setContentView(root)
    nativePipPlayer = NativePipPlayer(this)
    nativePipPlayer.attachTo(root)
    PipPlaybackSession.poster?.let { nativePipPlayer.setPosterBitmap(it) }
    nativePipPlayer.showOverlay()
    PipPlaybackSession.onPlay = { nativePipPlayer.play() }
    PipPlaybackSession.onPause = { nativePipPlayer.pause() }
    startPlayback()
    enterPip()
  }

  override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    if (!isInPictureInPictureMode && !isChangingConfigurations) {
      returnToWatchPage()
    }
  }

  override fun onStop() {
    if (!isInPictureInPictureMode) {
      persistPosition()
    }
    super.onStop()
  }

  override fun onDestroy() {
    persistPosition()
    PipPlaybackSession.onPlay = null
    PipPlaybackSession.onPause = null
    if (::nativePipPlayer.isInitialized) {
      nativePipPlayer.release()
    }
    super.onDestroy()
  }

  private fun startPlayback() {
    val url = PipPlaybackSession.url
    if (url.isNullOrBlank()) {
      nativePipPlayer.showPosterOnly(PipPlaybackSession.thumbnailUrl)
      return
    }
    nativePipPlayer.start(
      url = url,
      mimeType = PipPlaybackSession.mimeType,
      positionMs = PipPlaybackSession.positionMs,
      thumbnailUrl = PipPlaybackSession.thumbnailUrl,
      userAgent = PipPlaybackSession.userAgent,
      cookies = PipPlaybackSession.cookies,
      playWhenReady = PipPlaybackSession.playWhenReady,
      onFirstFrame = {},
      onError = {
        nativePipPlayer.showPosterOnly(PipPlaybackSession.thumbnailUrl)
      }
    )
  }

  private fun enterPip() {
    if (!PictureInPictureHelper.supportsPictureInPicture(this) || isInPictureInPictureMode) {
      return
    }
    try {
      val entered = enterPictureInPictureMode(currentParams())
      if (!entered) {
        returnToWatchPage()
      }
    } catch (_: IllegalStateException) {
      returnToWatchPage()
    }
  }

  private fun currentParams(): PictureInPictureParams {
    return PictureInPictureHelper.buildParams(
      this,
      PipPlaybackSession.aspectWidth,
      PipPlaybackSession.aspectHeight,
      false,
      nativePipPlayer.isPlaying || PipPlaybackSession.playWhenReady,
      null
    )
  }

  private fun persistPosition() {
    val position = nativePipPlayer.positionMs
    if (position > 0) {
      PipPlaybackSession.positionMs = position
    }
  }

  private fun returnToWatchPage() {
    if (finishingPip) {
      return
    }
    finishingPip = true
    persistPosition()
    nativePipPlayer.stop()
    finish()
  }
}
