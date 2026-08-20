import android from 'android'

const TAG = '[FreeTubePip]'

/**
 * Syncs playback + aspect ratio with the native Picture-in-Picture controller.
 * @param {boolean} canAutoEnter whether Home / gesture leave should enter system PiP
 * @param {boolean} isPlaying
 * @param {number} aspectWidth
 * @param {number} aspectHeight
 */
export function setPictureInPictureState(canAutoEnter, isPlaying, aspectWidth, aspectHeight) {
  console.info(TAG, 'setState', { canAutoEnter, isPlaying, aspectWidth, aspectHeight })
  android.setPictureInPictureState(canAutoEnter, isPlaying, aspectWidth, aspectHeight)
}

/**
 * Reports the on-screen video box so Android can animate PiP from the player.
 * @param {number} left
 * @param {number} top
 * @param {number} width
 * @param {number} height
 */
export function setPictureInPictureSourceRect(left, top, width, height) {
  if (typeof android.setPictureInPictureSourceRect === 'function') {
    android.setPictureInPictureSourceRect(left, top, width, height)
  }
}

/**
 * Registers a URL the native TextureView / ExoPlayer can play inside system PiP.
 * @param {object|null} session
 * @param {string} [session.url]
 * @param {string} [session.mimeType]
 * @param {string} [session.audioUrl]
 * @param {string} [session.audioMimeType]
 * @param {number} [session.positionMs]
 * @param {number} [session.playbackRate]
 * @param {string} [session.thumbnailUrl]
 * @param {number} [session.preferredHeight]
 * @param {boolean} [session.useNative]
 */
export function setNativePipSession(session) {
  if (typeof android.setNativePipSession !== 'function') {
    return
  }
  if (!session) {
    console.info(TAG, 'clear native session')
    android.setNativePipSession('')
    return
  }
  console.info(TAG, 'native session', {
    url: session.url?.slice(0, 96),
    mimeType: session.mimeType,
    positionMs: session.positionMs,
    preferredHeight: session.preferredHeight,
    useNative: session.useNative
  })
  android.setNativePipSession(JSON.stringify(session))
}

/**
 * Sends a JPEG still of the current video frame for the PiP window.
 * @param {string|null} dataUrl
 */
export function setNativePipPoster(dataUrl) {
  if (dataUrl && typeof android.setNativePipPoster === 'function') {
    android.setNativePipPoster(dataUrl)
  }
}

/**
 * Asks Android to enter system Picture-in-Picture immediately.
 */
export function enterPictureInPicture() {
  console.info(TAG, 'enter')
  android.enterPictureInPicture()
}

/**
 * @returns {boolean}
 */
export function isInPictureInPicture() {
  return android.isInPictureInPicture()
}

/**
 * @returns {number}
 */
export function getNativePipPositionMs() {
  if (typeof android.getNativePipPositionMs === 'function') {
    return android.getNativePipPositionMs()
  }
  return 0
}
