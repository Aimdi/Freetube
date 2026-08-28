package io.freetubeapp.freetube.helpers

import android.graphics.Rect
import org.json.JSONObject

data class ApplicationState(
  val consoleMessages: MutableList<JSONObject> = mutableListOf(),
  var showSplashScreen: Boolean = true,
  var darkMode: Boolean = false,
  var paused: Boolean = false,
  var isInAPrompt: Boolean = false,
  var keepScreenOn: Boolean = false,
  var currentPage: String? = null,
  var canEnterPictureInPicture: Boolean = false,
  var pictureInPictureAspectWidth: Int = 16,
  var pictureInPictureAspectHeight: Int = 9,
  var pictureInPicturePlaying: Boolean = false,
  var pictureInPictureSourceRect: Rect? = null,
  var isInPictureInPicture: Boolean = false,
  var nativePipUrl: String? = null,
  var nativePipMimeType: String? = null,
  var nativePipAudioUrl: String? = null,
  var nativePipAudioMimeType: String? = null,
  var nativePipPositionMs: Long = 0,
  var nativePipPlaybackRate: Float = 1.0f,
  var nativePipThumbnailUrl: String? = null,
  var nativePipPreferredHeight: Int = 720,
  var useNativePipPlayer: Boolean = true,
  var usingNativePip: Boolean = false
)
