package io.freetubeapp.freetube

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.TextureView
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
  private lateinit var pipOverlay: FrameLayout
  private lateinit var pipTexture: TextureView
  private lateinit var pipStreamTexture: TextureView
  private lateinit var pipStatus: TextView
  private val mainHandler = Handler(Looper.getMainLooper())
  private val pipFramePaint = Paint(Paint.FILTER_BITMAP_FLAG)
  private var lastPipFrameAt = 0L
  private var pipEnterInProgress = false
  private var pipStreamPlayer: ExoPlayer? = null

  /**
   * Ticks the in-page capture from the native side. Renderer timers are
   * throttled while the activity is paused in PiP; evaluateJavascript
   * injections are not.
   */
  private val pipCaptureClock = object : Runnable {
    override fun run() {
      if (!isInPictureInPictureMode) {
        return
      }
      webView.evaluateJavascript("window.__ftPipCapture && window.__ftPipCapture()", null)
      mainHandler.postDelayed(this, 33)
    }
  }

  private val pipSizeNotifier = Runnable {
    if (isInPictureInPictureMode && pipTexture.width > 0) {
      webView.evaluateJavascript("window.__ftPipTargetWidth = ${pipTexture.width}", null)
    }
  }

  private val pipStatusPoller = object : Runnable {
    override fun run() {
      if (!isInPictureInPictureMode) {
        return
      }
      // Only surface diagnostics when no frames are arriving
      if (SystemClock.elapsedRealtime() - lastPipFrameAt > 2000) {
        webView.evaluateJavascript(
          "(function(){try{var v=document.querySelector('video.player');" +
            "return 'video:'+(v?1:0)+' size:'+(v?v.videoWidth+'x'+v.videoHeight:'-')+" +
            "' time:'+(v?Math.round(v.currentTime):'-')+' mirror:'+(window.__ftPipMirrorActive?1:0)+" +
            "' pip:'+(document.documentElement.classList.contains('androidPip')?1:0)}" +
            "catch(e){return 'err: '+e.message}})()"
        ) { result ->
          pipStatus.text = "waiting for frames\n${result?.trim('"') ?: "no response"}"
          pipStatus.visibility = View.VISIBLE
        }
      }
      mainHandler.postDelayed(this, 1000)
    }
  }

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
        // Check whether the initial data is ready.
        if (!state.showSplashScreen) {
          // The content is ready. Start drawing.
          true
        } else {
          // The content isn't ready. Suspend.
          false
        }
      }
      root.addView(webView)

      // Native PiP surface: system PiP always composites native views,
      // even when the WebView's own surface goes missing or black.
      // A TextureView lets the frame-decoder thread draw directly via
      // lockCanvas, skipping the (busy) UI thread for every frame.
      pipTexture = TextureView(this@MainActivity).apply {
        layoutParams = FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT
        )
        isOpaque = true
        // Tell the page the real PiP resolution so relayed frames are
        // exactly as sharp as the window can display. Debounced so the
        // resize animation doesn't spam changing sizes.
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
          if (isInPictureInPictureMode) {
            mainHandler.removeCallbacks(pipSizeNotifier)
            mainHandler.postDelayed(pipSizeNotifier, 250)
          }
        }
      }
      pipStatus = TextView(this@MainActivity).apply {
        layoutParams = FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.WRAP_CONTENT,
          Gravity.BOTTOM
        )
        setTextColor(Color.WHITE)
        textSize = 10f
        setBackgroundColor(0x88000000.toInt())
        setPadding(12, 6, 12, 6)
        visibility = View.GONE
      }
      // ExoPlayer sink for the MediaRecorder stream. Sits under the
      // frame-relay texture; revealed once the stream renders.
      pipStreamTexture = TextureView(this@MainActivity).apply {
        layoutParams = FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT
        )
        isOpaque = true
      }
      pipOverlay = FrameLayout(this@MainActivity).apply {
        layoutParams = ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
        isClickable = false
        addView(pipStreamTexture)
        addView(pipTexture)
        addView(pipStatus)
      }
      root.addView(pipOverlay)
    }

    // this keeps android from shutting off the app to conserve battery
    startService(keepGoingService)

    state.darkMode = resources.configuration.isDarkMode()

    // allow fullscreen shaka player to use whole window width
    window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

    applyPictureInPictureParams()
  }

  override fun onConfigurationChanged(newConfig: Configuration) {
    super.onConfigurationChanged(newConfig)
    state.darkMode = newConfig.isDarkMode()
    val colorString = if (state.darkMode) { "dark" } else { "light" }
    webView.dispatchEvent("enabled-$colorString-mode")
  }

  /**
   * handles new intents which involve deep links (aka supported links)
   */
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
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      if (state.canEnterPictureInPicture) {
        cropPlayerForPictureInPicture()
      }
      return
    }
    enterPictureInPictureModeIfPossible(requireAutoEnter = true)
  }

  override fun onPictureInPictureRequested(): Boolean {
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
    if (isInPictureInPictureMode) {
      webView.setBackgroundColor(Color.BLACK)
      cropPlayerForPictureInPicture()
      webView.dispatchEvent("pip-enter")
      lastPipFrameAt = 0L
      pipStatus.text = "waiting for frames…"
      pipStatus.visibility = View.VISIBLE
      pipTexture.visibility = View.VISIBLE
      pipOverlay.visibility = View.VISIBLE
      pipOverlay.bringToFront()
      // push the settled window size once the enter animation finishes
      mainHandler.removeCallbacks(pipSizeNotifier)
      mainHandler.postDelayed(pipSizeNotifier, 400)
      mainHandler.removeCallbacks(pipCaptureClock)
      mainHandler.post(pipCaptureClock)
      mainHandler.removeCallbacks(pipStatusPoller)
      mainHandler.postDelayed(pipStatusPoller, 1000)
    } else {
      mainHandler.removeCallbacks(pipCaptureClock)
      mainHandler.removeCallbacks(pipStatusPoller)
      mainHandler.removeCallbacks(pipSizeNotifier)
      stopPipStreamPlayer()
      pipOverlay.visibility = View.GONE
      pipStatus.visibility = View.GONE
      webView.setAndroidPipMode(false)
      webView.setBackgroundColor(Color.TRANSPARENT)
      webView.dispatchEvent("pip-exit")
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
    } catch (_: IllegalStateException) {
      // Activity is finishing or not in a valid PiP state
    }
  }

  /**
   * @param requireAutoEnter when true, only enter if the player reported that
   * auto-enter-on-leave is enabled and a video is playing
   */
  fun enterPictureInPictureModeIfPossible(requireAutoEnter: Boolean = false): Boolean {
    if (!PictureInPictureHelper.supportsPictureInPicture(this) || isInPictureInPictureMode || pipEnterInProgress) {
      return isInPictureInPictureMode
    }
    if (requireAutoEnter && !state.canEnterPictureInPicture) {
      return false
    }
    pipEnterInProgress = true
    cropPlayerForPictureInPicture {
      webView.post {
        enterPictureInPictureNow()
      }
    }
    return true
  }

  private fun cropPlayerForPictureInPicture(after: (() -> Unit)? = null) {
    webView.exitHtmlFullscreen()
    webView.setBackgroundColor(Color.BLACK)
    webView.setAndroidPipMode(true, after)
  }

  private fun enterPictureInPictureNow() {
    if (isInPictureInPictureMode) {
      pipEnterInProgress = false
      return
    }
    try {
      val entered = enterPictureInPictureMode(currentPictureInPictureParams(autoEnter = false))
      if (!entered) {
        pipEnterInProgress = false
        webView.setAndroidPipMode(false)
        webView.setBackgroundColor(Color.TRANSPARENT)
      }
    } catch (_: IllegalStateException) {
      pipEnterInProgress = false
      webView.setAndroidPipMode(false)
      webView.setBackgroundColor(Color.TRANSPARENT)
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
    // bind the back button to the web-view history
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
    stopPipStreamPlayer()
    // stop the keep alive service
    stopService(keepGoingService)
    // cancel media notification (if there is one)
    webView.jsInterface.cancelMediaNotification()
    // clean up the web view
    webView.destroy()
    // call `super`
    super.onDestroy()
  }
}
