package io.freetubeapp.freetube.helpers

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView

/**
 * Hands the current watch-page stream to [io.freetubeapp.freetube.PipPlayerActivity].
 * System PiP must not use MainActivity — its WebView owns a hardware overlay
 * that punches a black hole through the floating window.
 */
object PipPlaybackSession {
  var url: String? = null
  var mimeType: String? = null
  var positionMs: Long = 0
  var thumbnailUrl: String? = null
  var poster: Bitmap? = null
  var userAgent: String = ""
  var cookies: String? = null
  var aspectWidth: Int = 16
  var aspectHeight: Int = 9
  var playWhenReady: Boolean = true
  var onPlay: (() -> Unit)? = null
  var onPause: (() -> Unit)? = null

  val canLaunch: Boolean
    get() = !url.isNullOrBlank() || poster != null || !thumbnailUrl.isNullOrBlank()

  fun updateFromState(state: ApplicationState, webView: WebView) {
    url = state.nativePipUrl
    mimeType = state.nativePipMimeType
    positionMs = state.nativePipPositionMs
    thumbnailUrl = state.nativePipThumbnailUrl
    aspectWidth = state.pictureInPictureAspectWidth
    aspectHeight = state.pictureInPictureAspectHeight
    playWhenReady = state.pictureInPicturePlaying || !state.paused
    userAgent = webView.settings.userAgentString ?: userAgent
    cookies = collectCookies()
  }

  fun collectCookies(): String? {
    val manager = CookieManager.getInstance()
    val parts = listOfNotNull(
      manager.getCookie("https://www.youtube.com"),
      manager.getCookie("https://youtube.com"),
      manager.getCookie("https://www.googlevideo.com"),
      manager.getCookie("https://googlevideo.com")
    ).filter { it.isNotBlank() }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("; ")
  }

  fun clear() {
    url = null
    mimeType = null
    positionMs = 0
    thumbnailUrl = null
    poster = null
    playWhenReady = true
  }
}
