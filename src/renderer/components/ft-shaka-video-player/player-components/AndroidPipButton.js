import shaka from 'shaka-player'

import i18n from '../../../i18n/index'
import { KeyboardShortcuts, PlayerIcons } from '../../../../constants'
import { addKeyboardShortcutToActionTitle } from '../../../helpers/utils'

export class AndroidPipButton extends shaka.ui.Element {
  /**
   * @param {EventTarget} events
   * @param {HTMLElement} parent
   * @param {shaka.ui.Controls} controls
   */
  constructor(events, parent, controls) {
    super(parent, controls)

    /** @private */
    this.button_ = document.createElement('button')
    this.button_.classList.add('android-pip-button', 'shaka-tooltip')

    /** @private */
    this.icon_ = new shaka.ui.Icon(this.button_, PlayerIcons.PICTURE_IN_PICTURE_FILLED)

    const label = document.createElement('label')
    label.classList.add(
      'shaka-overflow-button-label',
      'shaka-overflow-menu-only',
      'shaka-simple-overflow-button-label-inline'
    )

    /** @private */
    this.nameSpan_ = document.createElement('span')
    label.appendChild(this.nameSpan_)

    this.button_.appendChild(label)

    this.parent.appendChild(this.button_)

    this.eventManager.listen(this.button_, 'click', () => {
      events.dispatchEvent(new Event('enterAndroidPip'))
    })

    this.eventManager.listen(events, 'localeChanged', () => {
      this.updateLocalisedStrings_()
    })

    this.updateLocalisedStrings_()
  }

  /** @private */
  updateLocalisedStrings_() {
    const baseAriaLabel = i18n.global.t('Video.Player.Picture in Picture')
    const newLabel = addKeyboardShortcutToActionTitle(
      baseAriaLabel,
      KeyboardShortcuts.VIDEO_PLAYER.GENERAL.PICTURE_IN_PICTURE
    )
    this.nameSpan_.textContent = this.button_.ariaLabel = newLabel
  }
}
