package io.freetubeapp.freetube

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.freetubeapp.freetube.activities.FreeTubeActivity
import io.freetubeapp.freetube.databinding.ActivityMainBinding
import io.freetubeapp.freetube.helpers.NativePipPlayer
import io.freetubeapp.freetube.helpers.NativePipSession
import io.freetubeapp.freetube.helpers.PictureInPictureHelper
import io.freetubeapp.freetube.helpers.PipStreamBuffer
import io.freetubeapp.freetube.helpers.PipStreamDataSource
import io.freetubeapp.freetube.helpers.isDarkMode
import io.freetubeapp.freetube.helpers.toYtUrl
import io.freetubeapp.freetube.helpers.urlEncode
import io.freetubeapp.freetube.javascript.dispatchEvent
import io.freetubeapp.freetube.webviews.FreeTubeWebView

class MainActivity: FreeTubeActivity() {
  private val keepGoingService: Intent
    get() {
      return Intent(this, KeepAliveService::class.java)
    }
  private lateinit var webView: FreeTubeWebView
  private lateinit var nativePipPlayer: NativePipPlayer
  private var pipEnterInProgress = false

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    webView = FreeTubeWebView(this)

    val url = intent?.toYtUrl()
    val postfix = if (url != null) {
      "?intent=${url.urlEncode()}"
    } else {
      ""
    }
    webView.loadUrl("file:///android_asset/index.html$postfix")

    ActivityMainBinding.inflate(layoutInflater).apply {
      setContentView(root)
      root.viewTreeObserver.addOnPreDrawListener {
        if (!state.showSplashScreen) {
          true
        } else {
          false
        }
      }
      root.addView(webView)
      nativePipPlayer = NativePipPlayer(this@MainActivity)
      nativePipPlayer.attachTo(root as ViewGroup)
    }

    startService(keepGoingService)

    state.darkMode = resources.configuration.isDarkMode()

    window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

    applyPictureInPictureParams()
    Log.d(TAG, "onCreate pipSupported=${PictureInPictureHelper.supportsPictureInPicture(this)}")
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    state.darkMode = newConfig.isDarkMode()
    val colorString = if (state.darkMode) { "dark" } else { "light" }
    webView.dispatchEvent("enabled-$colorString-mode")
  }

  override fun onNewIntent(intent: Intent?) {
    super.onNewIntent(intent)
    val url = intent?.toYtUrl()
    if (url != null) {
      webView.dispatchEvent("youtube-link", "link", url)
    }
  }

  override fun onPause() {
    super.onPause()
    if (isInPictureInPictureMode) {
      Log.d(TAG, "onPause ignored while in PiP")
      return
    }
    state.paused = true
    webView.dispatchEvent("app-pause")
  }

  override fun onResume() {
    super.onResume()
    state.paused = false
    webView.dispatchEvent("app-resume")
  }

  /**
   * Android 12+ auto-enters PiP from the params set in [applyPictureInPictureParams].
   * Older versions enter here. Either way the crop CSS goes in as early as possible.
   */
  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    Log.d(TAG, "onUserLeaveHint sdk=${Build.VERSION.SDK_INT} canAuto=${state.canEnterPictureInPicture}")
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      cropPlayerForPictureInPicture()
      startNativePipPlayback()
      return
    }
    enterPictureInPictureModeIfPossible(requireAutoEnter = true)
  }

  override fun onPictureInPictureRequested(): Boolean {
    Log.d(TAG, "onPictureInPictureRequested canAuto=${state.canEnterPictureInPicture}")
    if (state.canEnterPictureInPicture && !isInPictureInPictureMode) {
      enterPictureInPictureModeIfPossible(requireAutoEnter = true)
      return true
    }
    return super.onPictureInPictureRequested()
  }

  override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    state.isInPictureInPicture = isInPictureInPictureMode
    pipEnterInProgress = false
    Log.d(TAG, "onPictureInPictureModeChanged inPip=$isInPictureInPictureMode native=${state.usingNativePip}")
    if (isInPictureInPictureMode) {
      webView.setBackgroundColor(Color.BLACK)
      cropPlayerForPictureInPicture()
      startNativePipPlayback()
      webView.dispatchEvent("pip-enter")
    } else {
      val positionMs = if (state.usingNativePip) nativePipPlayer.positionMs else state.nativePipPositionMs
      stopNativePipPlayback(resumeWebView = !state.paused)
      webView.setAndroidPipMode(false)
      webView.setBackgroundColor(Color.TRANSPARENT)
      webView.evaluateJavascript(
        "window.dispatchEvent(new CustomEvent('pip-exit',{detail:{positionMs:$positionMs}}));",
        null
      )
      if (state.paused) {
        webView.dispatchEvent("app-pause")
      }
    }
  }

  /**
   * The page's MediaRecorder started streaming; play it natively.
   * Real video pipeline: hardware decode, proper frame pacing, and
   * A/V sync from the container timestamps.
   */
  fun onPipStreamStart(@Suppress("UNUSED_PARAMETER") mimeType: String) {
    stopPipStreamPlayer()
    PipStreamBuffer.reset()
    val dataSourceFactory = DataSource.Factory { PipStreamDataSource() }
    val player = ExoPlayer.Builder(this)
      .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
      .setLoadControl(
        DefaultLoadControl.Builder()
          .setBufferDurationsMs(500, 10_000, 250, 500)
          .build()
      )
      .build()
    player.setVideoTextureView(pipStreamTexture)
    player.addListener(object : Player.Listener {
      override fun onRenderedFirstFrame() {
        lastPipFrameAt = SystemClock.elapsedRealtime()
        pipStreamTexture.visibility = View.VISIBLE
        // reveal the stream; stop the JPEG frame relay
        pipTexture.visibility = View.INVISIBLE
        pipStatus.visibility = View.GONE
        webView.evaluateJavascript("window.__ftPipStreamRendering && window.__ftPipStreamRendering()", null)
      }

      override fun onPlayerError(error: PlaybackException) {
        stopPipStreamPlayer()
        webView.evaluateJavascript("window.__ftPipStreamFailed && window.__ftPipStreamFailed()", null)
      }
    })
    player.setMediaItem(MediaItem.fromUri("pipstream://live"))
    player.prepare()
    player.playWhenReady = true
    pipStreamPlayer = player
  }

  /** chunk heartbeat for the diagnostics poller */
  fun onPipStreamData() {
    lastPipFrameAt = SystemClock.elapsedRealtime()
  }

  private fun stopPipStreamPlayer() {
    PipStreamBuffer.close()
    pipStreamPlayer?.release()
    pipStreamPlayer = null
    pipStreamTexture.visibility = View.INVISIBLE
    pipTexture.visibility = View.VISIBLE
  }

  /**
   * Called from the frame-decoder thread. Draws straight onto the
   * TextureView surface so no per-frame work queues on the UI thread,
   * then acks the frame so the page can measure end-to-end latency.
   */
  fun onPipFrame(bitmap: Bitmap, captureId: Double) {
    lastPipFrameAt = SystemClock.elapsedRealtime()
    if (!isInPictureInPictureMode || !pipTexture.isAvailable) {
      return
    }
    val canvas = try {
      pipTexture.lockCanvas() ?: return
    } catch (_: IllegalStateException) {
      return
    }
    try {
      canvas.drawColor(Color.BLACK)
      val canvasWidth = canvas.width.toFloat()
      val canvasHeight = canvas.height.toFloat()
      val bitmapWidth = bitmap.width.toFloat()
      val bitmapHeight = bitmap.height.toFloat()
      if (bitmapWidth > 0 && bitmapHeight > 0) {
        val scale = minOf(canvasWidth / bitmapWidth, canvasHeight / bitmapHeight)
        val drawWidth = bitmapWidth * scale
        val drawHeight = bitmapHeight * scale
        val left = (canvasWidth - drawWidth) / 2f
        val top = (canvasHeight - drawHeight) / 2f
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + drawWidth, top + drawHeight), pipFramePaint)
      }
    } finally {
      try {
        pipTexture.unlockCanvasAndPost(canvas)
      } catch (_: IllegalStateException) {
        // surface went away mid-draw (PiP dismissed)
      }
    }
    mainHandler.post {
      webView.evaluateJavascript(
        "window.__ftPipFrameShown && window.__ftPipFrameShown($captureId)",
        null
      )
      if (pipStatus.visibility == View.VISIBLE &&
        SystemClock.elapsedRealtime() - lastPipFrameAt < 2000
      ) {
        pipStatus.visibility = View.GONE
      }
    }
  }

  fun applyPictureInPictureParams() {
    if (!PictureInPictureHelper.supportsPictureInPicture(this)) {
      return
    }
    try {
      setPictureInPictureParams(currentPictureInPictureParams())
    } catch (error: IllegalStateException) {
      Log.d(TAG, "setPictureInPictureParams ignored: ${error.message}")
    }
  }

  /**
   * @param requireAutoEnter when true, only enter if the player reported that
   * auto-enter-on-leave is enabled and a video is playing
   */
  fun enterPictureInPictureModeIfPossible(requireAutoEnter: Boolean = false): Boolean {
    if (!PictureInPictureHelper.supportsPictureInPicture(this) || isInPictureInPictureMode || pipEnterInProgress) {
      Log.d(TAG, "enter skipped alreadyIn=$isInPictureInPictureMode inProgress=$pipEnterInProgress")
      return isInPictureInPictureMode
    }
    if (requireAutoEnter && !state.canEnterPictureInPicture) {
      Log.d(TAG, "enter skipped autoEnter disabled")
      return false
    }
    if (requireAutoEnter && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      cropPlayerForPictureInPicture()
      startNativePipPlayback()
      return true
    }
    pipEnterInProgress = true
    startNativePipPlayback()
    cropPlayerForPictureInPicture {
      webView.post {
        enterPictureInPictureNow()
      }
    }
    return true
  }

  /**
   * @return true when play/pause was handled by the native TextureView player
   */
  fun handleNativePipMediaEvent(event: String): Boolean {
    if (!state.usingNativePip) {
      return false
    }
    return when (event) {
      "media-play" -> {
        nativePipPlayer.play()
        state.pictureInPicturePlaying = true
        applyPictureInPictureParams()
        true
      }
      "media-pause" -> {
        nativePipPlayer.pause()
        state.pictureInPicturePlaying = false
        applyPictureInPictureParams()
        true
      }
      else -> false
    }
  }

  fun onNativePipMediaUpdated() {
    val url = state.nativePipUrl ?: return
    val session = currentNativeSession() ?: return
    if ((state.usingNativePip || isInPictureInPictureMode) && session.key() != nativePipPlayer.lastSessionKey) {
      Log.d(TAG, "native media updated, restarting player")
      nativePipPlayer.clearLastSession()
      startNativePipPlayback()
    } else {
      Log.d(TAG, "native media registered url=${url.take(96)}")
    }
  }

  fun setNativePipPoster(bitmap: Bitmap) {
    if (!::nativePipPlayer.isInitialized) {
      return
    }
    nativePipPlayer.setPosterBitmap(bitmap)
    if (isInPictureInPictureMode || pipEnterInProgress) {
      nativePipPlayer.showOverlay()
    }
  }

  fun nativePipPositionMs(): Long {
    return if (::nativePipPlayer.isInitialized && state.usingNativePip) {
      nativePipPlayer.positionMs
    } else {
      state.nativePipPositionMs
    }
  }

  private fun startNativePipPlayback() {
    if (!state.useNativePipPlayer) {
      Log.d(TAG, "native player disabled by setting, using WebView surface")
      return
    }
    if (!::nativePipPlayer.isInitialized) {
      return
    }
    nativePipPlayer.showOverlay()
    val session = currentNativeSession()
    if (session == null) {
      Log.d(TAG, "no native stream yet, showing poster")
      nativePipPlayer.showPosterOnly(state.nativePipThumbnailUrl)
      return
    }
    if (nativePipPlayer.lastSessionKey == session.key()) {
      if (state.pictureInPicturePlaying || !state.paused) {
        nativePipPlayer.play()
      }
      return
    }
    nativePipPlayer.start(
      session = session,
      userAgent = webView.settings.userAgentString ?: DEFAULT_USER_AGENT,
      playWhenReady = state.pictureInPicturePlaying || !state.paused,
      onFirstFrame = {
        state.usingNativePip = true
        webView.visibility = View.INVISIBLE
        webView.evaluateJavascript(
          "window.__androidNativePip=true;var v=document.querySelector('video.player');if(v){v.pause();v.muted=true;}",
          null
        )
        Log.d(TAG, "native first frame, WebView hidden")
      },
      onError = { error ->
        Log.w(TAG, "native player failed ($error), falling back to WebView crop")
        state.usingNativePip = false
        nativePipPlayer.stop()
        webView.visibility = View.VISIBLE
        cropPlayerForPictureInPicture()
      }
    )
  }

  private fun stopNativePipPlayback(resumeWebView: Boolean) {
    val positionMs = if (::nativePipPlayer.isInitialized) nativePipPlayer.positionMs else 0L
    if (positionMs > 0) {
      state.nativePipPositionMs = positionMs
    }
    if (::nativePipPlayer.isInitialized) {
      nativePipPlayer.stop()
    }
    state.usingNativePip = false
    webView.visibility = View.VISIBLE
    webView.evaluateJavascript(
      "window.__androidNativePip=false;var v=document.querySelector('video.player');if(v){v.muted=false;}",
      null
    )
    Log.d(TAG, "stopped native player pos=$positionMs resumeWebView=$resumeWebView")
  }

  private fun currentNativeSession(): NativePipSession? {
    val url = state.nativePipUrl ?: return null
    if (url.isBlank()) {
      return null
    }
    return NativePipSession(
      url = url,
      mimeType = state.nativePipMimeType,
      audioUrl = state.nativePipAudioUrl,
      audioMimeType = state.nativePipAudioMimeType,
      positionMs = state.nativePipPositionMs,
      playbackRate = state.nativePipPlaybackRate,
      thumbnailUrl = state.nativePipThumbnailUrl,
      preferredHeight = state.nativePipPreferredHeight
    )
  }

  private fun currentPictureInPictureParams() = PictureInPictureHelper.buildParams(
    this,
    state.pictureInPictureAspectWidth,
    state.pictureInPictureAspectHeight,
    state.canEnterPictureInPicture,
    state.pictureInPicturePlaying,
    state.pictureInPictureSourceRect
  )

  private fun cropPlayerForPictureInPicture(after: (() -> Unit)? = null) {
    webView.setAndroidPipMode(true, after)
  }

  private fun enterPictureInPictureNow() {
    if (isDestroyed || isFinishing || isInPictureInPictureMode) {
      pipEnterInProgress = false
      return
    }
    try {
      val entered = enterPictureInPictureMode(currentPictureInPictureParams())
      Log.d(TAG, "enterPictureInPictureMode => $entered")
      if (!entered) {
        pipEnterInProgress = false
      }
    } catch (error: IllegalStateException) {
      Log.w(TAG, "enterPictureInPictureMode failed: ${error.message}")
      pipEnterInProgress = false
    }
  }

  private fun currentPictureInPictureParams(autoEnter: Boolean = state.canEnterPictureInPicture && !state.isInPictureInPicture) =
    PictureInPictureHelper.buildParams(
      this,
      state.pictureInPictureAspectWidth,
      state.pictureInPictureAspectHeight,
      autoEnter,
      state.pictureInPicturePlaying,
      state.pictureInPictureSourceRect
    )

  override fun onBack() {
    if (state.isInAPrompt) {
      webView.dispatchEvent("exit-prompt")
      webView.jsInterface.exitPromptMode()
    } else {
      if (webView.canGoBack()) {
        webView.goBack()
      } else if (!enterPictureInPictureModeIfPossible(requireAutoEnter = true)) {
        moveTaskToBack(true)
      }
    }
  }

  override fun onDestroy() {
    if (::nativePipPlayer.isInitialized) {
      nativePipPlayer.release()
    }
    stopService(keepGoingService)
    webView.jsInterface.cancelMediaNotification()
    webView.destroy()
    super.onDestroy()
  }

  companion object {
    private const val TAG = "FreeTubePip"
    private const val DEFAULT_USER_AGENT =
      "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
  }
}
