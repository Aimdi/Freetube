import android from 'android'

/**
 * Syncs playback + aspect ratio with the native Picture-in-Picture controller.
 * @param {boolean} canAutoEnter whether Home / gesture leave should enter system PiP
 * @param {boolean} isPlaying
 * @param {number} aspectWidth
 * @param {number} aspectHeight
 */
export function setPictureInPictureState(canAutoEnter, isPlaying, aspectWidth, aspectHeight) {
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
 * Registers a URL the native TextureView player can play inside system PiP.
 * @param {string|null} url
 * @param {string|null} mimeType
 * @param {number} positionMs
 * @param {string|null} thumbnailUrl
 */
export function setNativePipMedia(url, mimeType, positionMs, thumbnailUrl) {
  if (typeof android.setNativePipMedia === 'function') {
    android.setNativePipMedia(url, mimeType, positionMs, thumbnailUrl)
  }
}

/**
 * Asks Android to enter system Picture-in-Picture immediately.
 */
export function enterPictureInPicture() {
  android.enterPictureInPicture()
}

/**
 * @returns {boolean}
 */
export function isInPictureInPicture() {
  return android.isInPictureInPicture()
}
