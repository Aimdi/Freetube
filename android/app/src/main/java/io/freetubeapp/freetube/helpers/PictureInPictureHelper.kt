package io.freetubeapp.freetube.helpers

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import io.freetubeapp.freetube.MediaControlsReceiver

/**
 * Builds Android system Picture-in-Picture params for the WebView player.
 *
 * Auto-enter (Home / gesture leave) is only available on Android 12+.
 * Older versions use [Activity.onUserLeaveHint] instead.
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
    isPlaying: Boolean
  ): PictureInPictureParams {
    val builder = PictureInPictureParams.Builder()
      .setAspectRatio(clampAspectRatio(aspectWidth, aspectHeight))
      .setActions(buildActions(activity, isPlaying))

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
}
