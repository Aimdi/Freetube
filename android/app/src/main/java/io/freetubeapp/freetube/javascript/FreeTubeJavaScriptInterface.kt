package io.freetubeapp.freetube.javascript

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.media.session.PlaybackState.STATE_PAUSED
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.freetubeapp.freetube.MainActivity
import io.freetubeapp.freetube.activities.FreeTubeActivity
import io.freetubeapp.freetube.helpers.MediaSessionFacade
import io.freetubeapp.freetube.helpers.NativePipPlayer
import io.freetubeapp.freetube.helpers.Promise
import io.freetubeapp.freetube.helpers.WriteMode
import io.freetubeapp.freetube.helpers.getDataDirectory
import io.freetubeapp.freetube.helpers.getFileName
import io.freetubeapp.freetube.helpers.readBytes
import io.freetubeapp.freetube.helpers.readText
import io.freetubeapp.freetube.helpers.resolveAmbiguousUri
import io.freetubeapp.freetube.helpers.writeBytes
import io.freetubeapp.freetube.webviews.FreeTubeWebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset
import kotlin.concurrent.thread
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

const val DATA_DIRECTORY = "data://"

class FreeTubeJavaScriptInterface(
  private val context: FreeTubeActivity,
  private val webView: FreeTubeWebView
) {
  private val coroutineScope = CoroutineScope(Dispatchers.Main)
  private val mediaSession: MediaSessionFacade = MediaSessionFacade(
    context,
    "media_controls",
    { event ->
      context.runOnUiThread {
        if ((context as? MainActivity)?.handleNativePipMediaEvent(event) != true) {
          webView.dispatchEvent(event)
        }
      }
    },
    { position ->
      webView.dispatchEvent("media-seek", "position", position)
    }
  )
  val jsCommunicator: AsyncJSCommunicator = AsyncJSCommunicator(webView)

  // region Media Notifications
  /**
   * creates a media notification
   * @param title the track name / video title
   * @param artist the author / channel name
   * @param duration the duration in milliseconds of the video
   * @param thumbnail a URL to the thumbnail for the video
   */
  @JavascriptInterface
  fun createMediaSession(title: String, artist: String, duration: Long = 0, thumbnail: String? = null) {
    mediaSession
      .setMetadata(title, artist, duration, thumbnail)
      .setState(STATE_PAUSED, 0)
      .push()
  }

  /**
   * updates the playback state of a media notification
   */
  @JavascriptInterface
  fun updateMediaSessionState(state: String?, position: String? = null) {
    mediaSession
      .setState(
        state?.toInt(),
        position?.toLong()
      )
  }

  /**
   * updates the track information of a media notification
   */
  @JavascriptInterface
  fun updateMediaSessionData(trackName: String, artist: String, duration: Long, art: String? = null) {
    mediaSession
      .setMetadata(
        trackName,
        artist,
        duration,
        art
      )
  }

  @JavascriptInterface
  fun cancelMediaNotification() {
    mediaSession.cancel()
  }

  // endregion

  // region File Helpers
  /**
   * @param directory a shortened directory uri
   * @return a full directory uri
   */
  @JavascriptInterface
  fun getDirectory(directory: String): String? {
    return if (directory == DATA_DIRECTORY) {
      context.getDataDirectory()
    } else {
      directory
    }
  }

  @JavascriptInterface
  fun revokePermissionForTree(treeUri: String) {
    context.revokeUriPermission(
      treeUri.toUri(),
      Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    )
  }

  @JavascriptInterface
  fun listFilesInTree(tree: String): String {
    val directory = DocumentFile.fromTreeUri(context, tree.toUri())
    val files = directory?.listFiles()?.joinToString(",") { file ->
      "{ \"uri\": \"${file.uri}\", \"fileName\": \"${file.name}\", \"isFile\": ${file.isFile}, \"isDirectory\": ${file.isDirectory} }"
    }
    return "[${files ?: ""}]"
  }

  @JavascriptInterface
  fun createFileInTree(tree: String, fileName: String): String? {
    val directory = DocumentFile.fromTreeUri(context, tree.toUri())
    return directory?.createFile("*/*", fileName)?.uri?.toString()
  }
  // endregion

  // region IO
  @JavascriptInterface
  fun listFilesInDataDir(): String {
    val directory = context.getDataDirectory()
    return if (directory == null) {
      "[]"
    } else {
      "[${
        File(directory).listFiles()?.joinToString(",") { file ->
          "{ \"uri\": \"$DATA_DIRECTORY${file.name}\", \"fileName\": \"${file.name}\", \"isFile\": ${file.isFile}, \"isDirectory\": ${file.isDirectory} }"
        } ?: ""
      }]"
    }
  }

  /**
   * reads a file from storage
   */
  @JavascriptInterface
  fun readFile(uri: String): String {
    return Promise(coroutineScope) { resolve, reject ->
      val file = context.resolveAmbiguousUri(uri)
      if (file != null) {
        try {
          resolve(context.contentResolver
            .readBytes(file.uri)
            ?.toString(Charset.forName("utf-8")))
        } catch (ex: Throwable) {
          reject(ex.stackTraceToString())
        }
      } else {
        reject("File not found from given uri")
      }
    }.addJsCommunicator(jsCommunicator)
  }

  /**
   * writes a file to storage
   */
  @OptIn(ExperimentalEncodingApi::class)
  @JavascriptInterface
  fun writeFile(uri: String, content: String): String {
    return Promise(coroutineScope) { resolve, reject ->
      val file = context.resolveAmbiguousUri(uri)
      if (file != null) {
        val bytes = if (content.startsWith("data:")) {
          Base64.decode(content.split("base64,")[1])
        } else {
          content.toByteArray()
        }
        context.contentResolver.writeBytes(
          file.uri,
          bytes
        )
        resolve("")
      } else {
        reject("File not found from given uri")
      }
    }.addJsCommunicator(jsCommunicator)
  }

  @OptIn(ExperimentalEncodingApi::class)
  @JavascriptInterface
  fun appendFile(uri: String, content: String): String {
    return Promise(coroutineScope) { resolve, reject ->
      val file = context.resolveAmbiguousUri(uri)
      if (file != null) {
        val bytes = if (content.startsWith("data:")) {
          Base64.decode(content.split("base64,")[1])
        } else {
          content.toByteArray()
        }
        context.contentResolver.writeBytes(
          file.uri,
          bytes,
          WriteMode.Append
        )
        resolve("")
      } else {
        reject("File not found from given uri")
      }
    }.addJsCommunicator(jsCommunicator)
  }
  // endregion

  // region Dialogs
  /**
   * requests a save dialog, resolves a js promise when done, resolves with `USER_CANCELED` if the user cancels
   * @return a js promise id
   */
  @JavascriptInterface
  fun requestSaveDialog(fileName: String, fileType: String): String {
    return Promise(coroutineScope) { resolve, reject ->
      context.launchIntent(
        Intent(Intent.ACTION_CREATE_DOCUMENT)
          .addCategory(Intent.CATEGORY_OPENABLE)
          .setType(fileType)
          .putExtra(Intent.EXTRA_TITLE, fileName)
      ).then {
        if (it?.resultCode == Activity.RESULT_CANCELED) {
          resolve("USER_CANCELED")
        }
        try {
          val payload = JSONObject()
          payload.put("uri", it?.data?.data)
          resolve(payload)
        } catch (ex: Exception) {
          reject(ex.toString())
        }
      }
    }.addJsCommunicator(jsCommunicator)
  }

  @JavascriptInterface
  fun requestOpenDialog(fileTypes: String): String {
    return Promise(coroutineScope) { resolve, reject ->
      context.launchIntent(
        Intent(Intent.ACTION_GET_CONTENT)
          .setType("*/*")
          .putExtra(Intent.EXTRA_MIME_TYPES, fileTypes.split(",").toTypedArray())
      ).then {
        if (it?.resultCode == Activity.RESULT_CANCELED) {
          resolve("USER_CANCELED")
        }
        try {
          val uri = it?.data?.data
          if (uri != null) {
            val mimeType = context.contentResolver.getType(uri)
            val fileName = context.contentResolver.getFileName(uri)
            val payload = JSONObject()
            payload.put("uri", uri)
            payload.put("type", mimeType)
            payload.put("fileName", fileName)
            resolve(payload)
          } else {
            reject("Uri from intent was null")
          }
        } catch (ex: Exception) {
          reject(ex.toString())
        }
      }
    }.addJsCommunicator(jsCommunicator)
  }

  @JavascriptInterface
  fun requestDirectoryAccessDialog(): String {
    return Promise(coroutineScope) { resolve, reject ->
      context.launchIntent(
        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
      ).then {
        if (it?.resultCode == Activity.RESULT_CANCELED) {
          resolve("USER_CANCELED")
        }
        try {
          val uri = it?.data?.data
          if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
              uri,
              Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            resolve(uri)
          } else {
            reject("Uri from intent was null")
          }
        } catch (ex: Exception) {
          reject(ex.toString())
        }
      }
    }.addJsCommunicator(jsCommunicator)
  }

  // endregion

  // region System

  @JavascriptInterface
  fun openExternalLink(url: String) {
    context.startActivity(
      Intent(Intent.ACTION_VIEW, url.toUri())
    )
  }

  @JavascriptInterface
  fun getLogs(): String {
    var logs = "["
    for (message in context.state.consoleMessages) {
      logs += "${message},"
    }
    // get rid of trailing comma
    if (logs.length > 1) {
      logs = logs.substring(0, logs.length - 1)
    }
    logs += "]"
    return logs
  }

  @JavascriptInterface
  fun restart() {
    context.restart()
  }

  /**
   * hides the splashscreen
   */
  @JavascriptInterface
  fun hideSplashScreen() {
    context.state.showSplashScreen = false
  }

  @JavascriptInterface
  fun enableKeepScreenOn() {
    context.setKeepScreenOn(true)
  }

  @JavascriptInterface
  fun disableKeepScreenOn() {
    context.setKeepScreenOn(false)
  }

  /**
   * used on the JS side for async js communication
   */
  @JavascriptInterface
  fun getSyncMessage(promise: String): String? {
    return jsCommunicator.getSyncMessage(promise)
  }

  /**
   *
   */
  @JavascriptInterface
  fun themeSystemUi(navigationHex: String, statusHex: String, navigationDarkMode: Boolean  = true,  statusDarkMode: Boolean = true) {
    context.themeSystemUi(navigationHex, statusHex, navigationDarkMode, statusDarkMode)
  }

  @JavascriptInterface
  fun getSystemTheme(): String {
    return if (context.state.darkMode) {
      "dark"
    } else {
      "light"
    }
  }

  @JavascriptInterface
  fun isAppPaused(): Boolean {
    return context.state.paused
  }

  @JavascriptInterface
  fun enterPromptMode() {
    webView.isVerticalScrollBarEnabled = false
    context.state.isInAPrompt = true
  }

  @JavascriptInterface
  fun exitPromptMode() {
    webView.isVerticalScrollBarEnabled = true
    context.state.isInAPrompt = false
  }

  @JavascriptInterface
  fun setScale(scale: Int) {
    webView.setScale(scale / 100.0, context)
  }

  /**
   * Updates whether Android should auto-enter system Picture-in-Picture when the user leaves.
   * Also refreshes the PiP window aspect ratio and play/pause action.
   */
  @JavascriptInterface
  fun setPictureInPictureState(
    canAutoEnter: Boolean,
    isPlaying: Boolean,
    aspectWidth: Double,
    aspectHeight: Double
  ) {
    context.runOnUiThread {
      Log.d(PIP_TAG, "setState auto=$canAutoEnter playing=$isPlaying aspect=${aspectWidth}x${aspectHeight}")
      context.state.canEnterPictureInPicture = canAutoEnter
      context.state.pictureInPicturePlaying = isPlaying
      val width = aspectWidth.toInt()
      val height = aspectHeight.toInt()
      if (width > 0 && height > 0) {
        context.state.pictureInPictureAspectWidth = width
        context.state.pictureInPictureAspectHeight = height
      }
      (context as? MainActivity)?.applyPictureInPictureParams()
    }
  }

  /**
   * Reports the on-screen video box so Android can animate PiP from the player.
   */
  @JavascriptInterface
  fun setPictureInPictureSourceRect(left: Double, top: Double, width: Double, height: Double) {
    context.runOnUiThread {
      if (width <= 0 || height <= 0) {
        context.state.pictureInPictureSourceRect = null
        return@runOnUiThread
      }
      val density = context.resources.displayMetrics.density
      val rect = Rect(
        (left * density).toInt(),
        (top * density).toInt(),
        ((left + width) * density).toInt(),
        ((top + height) * density).toInt()
      )
      context.state.pictureInPictureSourceRect = rect
      (context as? MainActivity)?.applyPictureInPictureParams()
    }
  }

  /**
   * Enters Android system Picture-in-Picture if the device supports it.
   */
  @JavascriptInterface
  fun enterPictureInPicture() {
    context.runOnUiThread {
      Log.d(PIP_TAG, "enterPictureInPicture from JS")
      (context as? MainActivity)?.enterPictureInPictureModeIfPossible()
    }
  }

  @JavascriptInterface
  fun isInPictureInPicture(): Boolean {
    return context.state.isInPictureInPicture
  }

  /**
   * Registers a stream the native TextureView / ExoPlayer can play in PiP.
   * WebView HTML5 video is not composited into the system PiP window.
   *
   * @param sessionJson keys: url, mimeType, audioUrl, audioMimeType, positionMs,
   * playbackRate, thumbnailUrl, preferredHeight, useNative
   */
  @JavascriptInterface
  fun setNativePipSession(sessionJson: String?) {
    if (sessionJson.isNullOrBlank()) {
      context.runOnUiThread {
        context.state.nativePipUrl = null
        context.state.useNativePipPlayer = false
      }
      return
    }
    try {
      val json = JSONObject(sessionJson)
      context.runOnUiThread {
        context.state.useNativePipPlayer = json.optBoolean("useNative", true)
        context.state.nativePipUrl = json.optString("url").takeIf { it.isNotBlank() }
        context.state.nativePipMimeType = json.optString("mimeType").takeIf { it.isNotBlank() }
        context.state.nativePipAudioUrl = json.optString("audioUrl").takeIf { it.isNotBlank() }
        context.state.nativePipAudioMimeType = json.optString("audioMimeType").takeIf { it.isNotBlank() }
        val positionMs = json.optLong("positionMs", -1L)
        if (positionMs >= 0) {
          context.state.nativePipPositionMs = positionMs
        }
        val rate = json.optDouble("playbackRate", 1.0)
        if (rate > 0) {
          context.state.nativePipPlaybackRate = rate.toFloat()
        }
        context.state.nativePipThumbnailUrl = json.optString("thumbnailUrl").takeIf { it.isNotBlank() }
        val height = json.optInt("preferredHeight", 0)
        if (height > 0) {
          context.state.nativePipPreferredHeight = height
        }
        Log.d(
          PIP_TAG,
          "setNativePipSession native=${context.state.useNativePipPlayer} " +
            "url=${context.state.nativePipUrl?.take(96)} mime=${context.state.nativePipMimeType} " +
            "pos=${context.state.nativePipPositionMs} height=${context.state.nativePipPreferredHeight}"
        )
        (context as? MainActivity)?.onNativePipMediaUpdated()
      }
    } catch (error: Exception) {
      Log.e(PIP_TAG, "setNativePipSession parse failed", error)
    }
  }

  @JavascriptInterface
  fun setNativePipPoster(dataUrl: String?) {
    if (dataUrl.isNullOrBlank()) {
      return
    }
    val bitmap = NativePipPlayer.decodeDataUrl(dataUrl) ?: return
    context.runOnUiThread {
      (context as? MainActivity)?.setNativePipPoster(bitmap)
    }
  }

  @JavascriptInterface
  fun getNativePipPositionMs(): Double {
    return (context as? MainActivity)?.nativePipPositionMs()?.toDouble()
      ?: context.state.nativePipPositionMs.toDouble()
  }

  // endregion

  // region Data Extraction

  private fun getBotGuardScript(
    videoId: String,
    sessionContext: String,
    initialAttestationData: String,
    ytConfig: String
  ): String {
    val script = context.assets.readText("botGuardScript.js")
    val functionName = script.split("export{")[1].split(" as default};")[0]
    val exportSection = "export{${functionName} as default};"
    val bakedScript =
      script.replace(exportSection, "; ${functionName}(\"$videoId\", $sessionContext, $initialAttestationData, $ytConfig)")
    return bakedScript
  }

  @JavascriptInterface
  fun generatePOToken(
    videoId: String,
    sessionContext: String,
    initialAttestationData: String,
    ytConfig: String
  ): String {
    return Promise(coroutineScope) { resolve, reject ->
      webView.post {
        try {
          val bgScript = getBotGuardScript(videoId, sessionContext, initialAttestationData, ytConfig)
          val bgWv = webView.generateBgWebview()
          bgWv.jsInterface.onReturn {
            run {
              webView.post {
                resolve(it)
                bgWv.destroy()
              }
            }
          }
          bgWv.jsInterface.onReject {
            run {
              webView.post {
                reject(it)
                bgWv.destroy()
              }
            }
          }
          webView.post {
            bgWv.loadDataWithBaseURL(
              "https://www.youtube.com/",
              "<!DOCTYPE html>" +
                "<html lang=\"en\">" +
                "<head>" +
                "<title></title>" +
                "</head>" +
                "<body><script>${bgScript}.then((TOKEN_RESULT) => { console.log(`Your potoken is \${TOKEN_RESULT}`); Android.returnToken(TOKEN_RESULT) }).catch((error) => { Android.rejectToken(error.toString()) })</script></body>" +
                "</html>",
              "text/html",
              "utf-8",
              null
            )
          }
        } catch (exception: Exception) {
          reject(exception.message ?: exception.javaClass.name)
        }
      }
    }.addJsCommunicator(jsCommunicator)
  }

  @JavascriptInterface
  fun runDecipherScript(id: String, code: String, timeout: String): String {
    webView.post {
      webView.generateSigWebview()
        .onLoad = {
          // pass data to other webview
          jsInterface.jsCommunicator.resolve(id, code)
          // dispatch event to read data
          dispatchEvent("message", "id", id)
          // when timeout is called, clean up webview
          postDelayed({
            destroy()
          }, timeout.toLong())
      }
    }
    return id
  }

  // endregion

  companion object {
    private const val PIP_TAG = "FreeTubePip"
  }
}
