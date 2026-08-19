package io.freetubeapp.freetube

import android.app.ActivityOptions
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import io.freetubeapp.freetube.activities.FreeTubeActivity
import io.freetubeapp.freetube.databinding.ActivityMainBinding
import io.freetubeapp.freetube.helpers.PictureInPictureHelper
import io.freetubeapp.freetube.helpers.PipPlaybackSession
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
  private var launchedPip = false

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
    }

    // this keeps android from shutting off the app to conserve battery
    startService(keepGoingService)

    state.darkMode = resources.configuration.isDarkMode()

    // allow fullscreen shaka player to use whole window width
    window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

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
    if (launchedPip) {
      launchedPip = false
      webView.restoreFromPip()
      val seconds = PipPlaybackSession.positionMs / 1000.0
      webView.evaluateJavascript(
        "window.__androidNativePip = false; var v = document.querySelector('video.player'); if (v) { v.currentTime = $seconds; v.play(); }",
        null
      )
      webView.dispatchEvent("pip-exit")
    }
    webView.dispatchEvent("app-resume")
  }

  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    launchPipPlayer(requirePlaying = true)
  }

  override fun onPictureInPictureRequested(): Boolean {
    if (launchPipPlayer(requirePlaying = true)) {
      return true
    }
    return super.onPictureInPictureRequested()
  }

  fun applyPictureInPictureParams() {
    // MainActivity must not auto-enter PiP. The WebView hardware overlay
    // would be the surface the system scales, which is always black.
  }

  fun enterPictureInPictureModeIfPossible(requireAutoEnter: Boolean = false): Boolean {
    return launchPipPlayer(requirePlaying = requireAutoEnter)
  }

  fun handleNativePipMediaEvent(event: String): Boolean {
    return when (event) {
      "media-play" -> {
        PipPlaybackSession.onPlay?.invoke()
        PipPlaybackSession.onPlay != null
      }
      "media-pause" -> {
        PipPlaybackSession.onPause?.invoke()
        PipPlaybackSession.onPause != null
      }
      else -> false
    }
  }

  fun onNativePipMediaUpdated() {
    PipPlaybackSession.updateFromState(state, webView)
  }

  fun setNativePipPoster(bitmap: Bitmap) {
    PipPlaybackSession.poster = bitmap
  }

  /**
   * Starts a WebView-free player activity and puts *that* window in PiP.
   */
  fun launchPipPlayer(requirePlaying: Boolean = false): Boolean {
    if (!PictureInPictureHelper.supportsPictureInPicture(this)) {
      return false
    }
    if (requirePlaying && !state.canEnterPictureInPicture && state.nativePipUrl.isNullOrBlank()) {
      return false
    }
    PipPlaybackSession.updateFromState(state, webView)
    if (!PipPlaybackSession.canLaunch) {
      return false
    }
    launchedPip = true
    state.usingNativePip = true
    webView.exitHtmlFullscreen()
    webView.forceHideForPip()
    webView.evaluateJavascript(
      "window.__androidNativePip = true; var v = document.querySelector('video.player'); if (v) { v.pause(); }",
      null
    )
    webView.dispatchEvent("pip-enter")
    val intent = Intent(this, PipPlayerActivity::class.java)
      .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val options = ActivityOptions.makeLaunchIntoPip(
          PictureInPictureHelper.buildParams(
            this,
            state.pictureInPictureAspectWidth,
            state.pictureInPictureAspectHeight,
            false,
            state.pictureInPicturePlaying,
            null
          )
        )
        startActivity(intent, options.toBundle())
      } else {
        startActivity(intent)
      }
      return true
    } catch (_: Exception) {
      launchedPip = false
      state.usingNativePip = false
      webView.restoreFromPip()
      return false
    }
  }

  override fun onBack() {
    // bind the back button to the web-view history
    if (state.isInAPrompt) {
      webView.dispatchEvent("exit-prompt")
      webView.jsInterface.exitPromptMode()
    } else {
      if (webView.canGoBack()) {
        webView.goBack()
      } else if (!launchPipPlayer(requirePlaying = true)) {
        moveTaskToBack(true)
      }
    }
  }

  override fun onDestroy() {
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
