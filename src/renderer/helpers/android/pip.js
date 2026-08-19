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
