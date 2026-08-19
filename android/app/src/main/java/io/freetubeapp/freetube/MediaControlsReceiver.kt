package io.freetubeapp.freetube

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

open class MediaControlsReceiver : BroadcastReceiver() {

  companion object Static {
    var notifyMediaSessionListeners: ((String) -> Unit)? = null
  }

  override fun onReceive(context: Context?, intent: Intent?) {
    val action = intent?.action
    if (action != null) {
      notifyMediaSessionListeners?.invoke(action)
    }
  }
}
