package io.freetubeapp.freetube

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import io.freetubeapp.freetube.activities.FreeTubeActivity
import io.freetubeapp.freetube.databinding.ActivityMainBinding
import io.freetubeapp.freetube.helpers.NativePipPlayer
import io.freetubeapp.freetube.helpers.PictureInPictureHelper
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
      nativePipPlayer = NativePipPlayer(this@MainActivity)
      nativePipPlayer.attachTo(root as ViewGroup)
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
   * Crop the player as soon as the user leaves. On Android 12+ the system also
   * auto-enters PiP from [applyPictureInPictureParams]; this path is the
   * fallback for older APIs and for devices that skip auto-enter.
   */
  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      cropPlayerForPictureInPicture()
      startNativePipPlayback()
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
      startNativePipPlayback()
      webView.dispatchEvent("pip-enter")
    } else {
      stopNativePipPlayback(resumeWebView = !state.paused)
      webView.setAndroidPipMode(false)
      webView.setBackgroundColor(Color.TRANSPARENT)
      webView.dispatchEvent("pip-exit")
      if (state.paused) {
        webView.dispatchEvent("app-pause")
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
    // Android 12+ Home already auto-enters. Crop only — do not wait on JS
    // or enterPictureInPictureMode() will run after onPause and fail.
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
    if ((state.usingNativePip || isInPictureInPictureMode) && url != nativePipPlayer.lastUrl) {
      startNativePipPlayback()
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

  private fun startNativePipPlayback() {
    nativePipPlayer.showOverlay()
    val url = state.nativePipUrl
    if (url.isNullOrBlank()) {
      nativePipPlayer.showPosterOnly(state.nativePipThumbnailUrl)
      return
    }
    if (nativePipPlayer.lastUrl == url) {
      return
    }
    nativePipPlayer.start(
      url = url,
      mimeType = state.nativePipMimeType,
      positionMs = state.nativePipPositionMs,
      thumbnailUrl = state.nativePipThumbnailUrl,
      userAgent = webView.settings.userAgentString,
      playWhenReady = state.pictureInPicturePlaying || !state.paused,
      onFirstFrame = {
        state.usingNativePip = true
        webView.evaluateJavascript(
          "window.__androidNativePip = true; var v = document.querySelector('video.player'); if (v) { v.pause(); }",
          null
        )
      },
      onError = {
        state.usingNativePip = false
        nativePipPlayer.clearLastUrl()
        webView.evaluateJavascript("window.__androidNativePip = false", null)
      }
    )
  }

  private fun stopNativePipPlayback(resumeWebView: Boolean) {
    if (!state.usingNativePip && nativePipPlayer.lastUrl == null) {
      return
    }
    val positionMs = nativePipPlayer.positionMs
    if (positionMs > 0) {
      state.nativePipPositionMs = positionMs
    }
    nativePipPlayer.stop()
    state.usingNativePip = false
    val seconds = state.nativePipPositionMs / 1000.0
    val play = if (resumeWebView) ".play()" else ""
    webView.evaluateJavascript(
      "window.__androidNativePip = false; var v = document.querySelector('video.player'); if (v) { v.currentTime = $seconds; v$play; }",
      null
    )
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
    // stop the keep alive service
    stopService(keepGoingService)
    // cancel media notification (if there is one)
    webView.jsInterface.cancelMediaNotification()
    if (::nativePipPlayer.isInitialized) {
      nativePipPlayer.release()
    }
    // clean up the web view
    webView.destroy()
    // call `super`
    super.onDestroy()
  }
}
