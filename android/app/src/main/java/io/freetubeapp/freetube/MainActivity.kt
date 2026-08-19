package io.freetubeapp.freetube

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import io.freetubeapp.freetube.activities.FreeTubeActivity
import io.freetubeapp.freetube.databinding.ActivityMainBinding
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
   * Android 11 and below do not support [android.app.PictureInPictureParams.Builder.setAutoEnterEnabled].
   */
  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
      enterPictureInPictureModeIfPossible(requireAutoEnter = true)
    }
  }

  override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    state.isInPictureInPicture = isInPictureInPictureMode
    if (isInPictureInPictureMode) {
      webView.evaluateJavascript(
        "document.body.classList.add('androidPip');window.dispatchEvent(new Event('pip-enter'));",
        null
      )
    } else {
      webView.evaluateJavascript(
        "document.body.classList.remove('androidPip');window.dispatchEvent(new Event('pip-exit'));",
        null
      )
      if (state.paused) {
        webView.dispatchEvent("app-pause")
      }
    }
  }

  fun applyPictureInPictureParams() {
    if (!PictureInPictureHelper.supportsPictureInPicture(this)) {
      return
    }
    setPictureInPictureParams(
      PictureInPictureHelper.buildParams(
        this,
        state.pictureInPictureAspectWidth,
        state.pictureInPictureAspectHeight,
        state.canEnterPictureInPicture,
        state.pictureInPicturePlaying
      )
    )
  }

  fun enterPictureInPictureModeIfPossible(requireAutoEnter: Boolean = false): Boolean {
    if (!PictureInPictureHelper.supportsPictureInPicture(this) || isInPictureInPictureMode) {
      return isInPictureInPictureMode
    }
    if (requireAutoEnter && !state.canEnterPictureInPicture) {
      return false
    }
    return enterPictureInPictureMode(
      PictureInPictureHelper.buildParams(
        this,
        state.pictureInPictureAspectWidth,
        state.pictureInPictureAspectHeight,
        state.canEnterPictureInPicture,
        state.pictureInPicturePlaying
      )
    )
  }

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
    // clean up the web view
    webView.destroy()
    // call `super`
    super.onDestroy()
  }
}
