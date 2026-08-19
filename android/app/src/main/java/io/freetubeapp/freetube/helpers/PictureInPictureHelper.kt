package io.freetubeapp.freetube.helpers

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import io.freetubeapp.freetube.MediaControlsReceiver

/**
 * Builds Android system Picture-in-Picture params for the WebView player.
 *
 * Android 12+ uses [PictureInPictureParams.Builder.setAutoEnterEnabled] so Home / gesture
 * leave can enter PiP before the activity is paused. Older versions use
 * [Activity.onUserLeaveHint] instead.
 *
 * PiP shows the live activity, not a screenshot. Chrome (nav, comments, sidebar) must be
 * hidden and the video forced to fill the WebView — otherwise the floating window shows
 * the whole watch page.
 */
object PictureInPictureHelper {
  private const val MAX_ASPECT_RATIO = 2.39
  private const val MIN_ASPECT_RATIO = 1.0 / 2.39

  fun supportsPictureInPicture(activity: Activity): Boolean {
    return activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
  }

  fun clampAspectRatio(width: Int, height: Int): Rational {
    var w = width.coerceAtLeast(1)
    var h = height.coerceAtLeast(1)
    val ratio = w.toDouble() / h.toDouble()
    if (ratio > MAX_ASPECT_RATIO) {
      w = (h * MAX_ASPECT_RATIO).toInt().coerceAtLeast(1)
    } else if (ratio < MIN_ASPECT_RATIO) {
      h = (w / MIN_ASPECT_RATIO).toInt().coerceAtLeast(1)
    }
    return Rational(w, h)
  }

  fun buildParams(
    activity: Activity,
    aspectWidth: Int,
    aspectHeight: Int,
    autoEnter: Boolean,
    isPlaying: Boolean,
    sourceHint: Rect? = null
  ): PictureInPictureParams {
    val builder = PictureInPictureParams.Builder()
      .setAspectRatio(clampAspectRatio(aspectWidth, aspectHeight))
      .setActions(buildActions(activity, isPlaying))

    if (sourceHint != null && !sourceHint.isEmpty) {
      builder.setSourceRectHint(sourceHint)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      builder.setAutoEnterEnabled(autoEnter)
      builder.setSeamlessResizeEnabled(true)
    }

    return builder.build()
  }

  private fun buildActions(activity: Activity, isPlaying: Boolean): List<RemoteAction> {
    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    fun action(name: String, icon: Int, title: String): RemoteAction {
      val intent = Intent(activity, MediaControlsReceiver::class.java).setAction(name)
      val pending = PendingIntent.getBroadcast(activity, name.hashCode(), intent, flags)
      return RemoteAction(
        Icon.createWithResource(activity, icon),
        title,
        title,
        pending
      )
    }

    return listOf(
      action("previous", android.R.drawable.ic_media_previous, "Previous"),
      if (isPlaying) {
        action("pause", android.R.drawable.ic_media_pause, "Pause")
      } else {
        action("play", android.R.drawable.ic_media_play, "Play")
      },
      action("next", android.R.drawable.ic_media_next, "Next")
    )
  }

  /**
   * Installed into the WebView on every page load and immediately before PiP.
   * Toggling `html.androidPip` crops the page to the video using % sizes
   * (vw/vh stay at the full-screen size inside a PiP window).
   */
  const val INSTALL_SCRIPT = """
    (function () {
      if (!document.getElementById('android-pip-style')) {
        var style = document.createElement('style');
        style.id = 'android-pip-style';
        style.textContent = [
          'html.androidPip, html.androidPip body {',
          '  margin: 0 !important;',
          '  padding: 0 !important;',
          '  overflow: hidden !important;',
          '  width: 100% !important;',
          '  height: 100% !important;',
          '  min-width: 0 !important;',
          '  min-height: 0 !important;',
          '  background: #000 !important;',
          '}',
          'html.androidPip body * { visibility: hidden !important; }',
          'html.androidPip .ftVideoPlayer,',
          'html.androidPip .ftVideoPlayer *,',
          'html.androidPip video.player,',
          'html.androidPip video { visibility: visible !important; }',
          'html.androidPip .topNav,',
          'html.androidPip .sideNav,',
          'html.androidPip .sideNavMoreOptions,',
          'html.androidPip .banner-wrapper,',
          'html.androidPip .ftPrompt,',
          'html.androidPip .videoLayout > :not(.videoArea),',
          'html.androidPip .shaka-controls-container,',
          'html.androidPip .shaka-scrim-container,',
          'html.androidPip .shaka-spinner-container,',
          'html.androidPip .shaka-overflow-menu,',
          'html.androidPip .playerFullscreenTitleOverlay,',
          'html.androidPip .stats,',
          'html.androidPip .valueChangePopup,',
          'html.androidPip .offlineWrapper,',
          'html.androidPip .offlineMessage,',
          'html.androidPip .skippedSegmentsWrapper {',
          '  display: none !important;',
          '  visibility: hidden !important;',
          '}',
          'html.androidPip .app,',
          'html.androidPip .flexBox,',
          'html.androidPip .routerView,',
          'html.androidPip .videoLayout,',
          'html.androidPip .videoArea,',
          'html.androidPip .videoAreaMargin,',
          'html.androidPip .videoPlayer {',
          '  position: static !important;',
          '  margin: 0 !important;',
          '  padding: 0 !important;',
          '  width: 100% !important;',
          '  height: 100% !important;',
          '  max-width: none !important;',
          '  max-inline-size: none !important;',
          '  max-height: none !important;',
          '  overflow: hidden !important;',
          '  background: #000 !important;',
          '  transform: none !important;',
          '}',
          'html.androidPip .ftVideoPlayer,',
          'html.androidPip .shaka-video-container {',
          '  position: fixed !important;',
          '  inset: 0 !important;',
          '  margin: 0 !important;',
          '  padding: 0 !important;',
          '  width: 100% !important;',
          '  height: 100% !important;',
          '  max-width: none !important;',
          '  max-inline-size: none !important;',
          '  max-height: none !important;',
          '  aspect-ratio: auto !important;',
          '  overflow: hidden !important;',
          '  background: #000 !important;',
          '  transform: none !important;',
          '  z-index: 2147483646 !important;',
          '}',
          'html.androidPip video.player,',
          'html.androidPip .ftVideoPlayer > video {',
          '  position: absolute !important;',
          '  inset: 0 !important;',
          '  width: 100% !important;',
          '  height: 100% !important;',
          '  max-width: none !important;',
          '  object-fit: contain !important;',
          '  background: #000 !important;',
          '}'
        ].join('\n');
        (document.head || document.documentElement).appendChild(style);
      }
      window.__setAndroidPip = function (on) {
        document.documentElement.classList.toggle('androidPip', !!on);
        if (document.body) {
          document.body.classList.toggle('androidPip', !!on);
          document.body.classList.toggle('playerFullWindow', !!on);
        }
        try { if (on) { document.exitFullscreen(); } } catch (e) {}
        var player = document.querySelector('.ftVideoPlayer');
        if (player) {
          player.classList.toggle('fullWindow', !!on);
        }
        return true;
      };
    })();
  """.trimIndent()
}
