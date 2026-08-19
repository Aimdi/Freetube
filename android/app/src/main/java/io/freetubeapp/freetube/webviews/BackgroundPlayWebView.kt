package io.freetubeapp.freetube.webviews

import android.content.Context
import android.util.AttributeSet
import android.webkit.WebView

open class BackgroundPlayWebView @JvmOverloads constructor(
  context: Context, attrs: AttributeSet? = null
) : WebView(context, attrs) {
  private var once: Boolean = false
  private var hideForPip: Boolean = false

  override fun onWindowVisibilityChanged(visibility: Int) {
    if (hideForPip) {
      super.onWindowVisibilityChanged(visibility)
      return
    }
    if (once) return
    if (visibility != GONE) super.onWindowVisibilityChanged(VISIBLE)
    once = true
  }

  /**
   * Background play ignores later visibility changes so media keeps going.
   * That also keeps Chromium's hardware video overlay attached, which sits
   * *above* every sibling view and turns system PiP into a black hole.
   * Call this before leaving the WebView activity for a dedicated PiP activity.
   */
  fun forceHideForPip() {
    hideForPip = true
    visibility = GONE
    super.onWindowVisibilityChanged(GONE)
  }

  fun restoreFromPip() {
    hideForPip = false
    visibility = VISIBLE
    super.onWindowVisibilityChanged(VISIBLE)
  }
}
