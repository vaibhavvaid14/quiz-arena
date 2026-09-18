/**
 * Accessible confirmation dialog built on the native <dialog> element
 * (focus trapping, Esc-to-cancel and inert background come for free).
 */

import { h } from './dom.js';

/**
 * @param {{ title: string, message: string, confirmText?: string, cancelText?: string, danger?: boolean }} options
 * @returns {Promise<boolean>} true when confirmed
 */
export function confirmDialog({ title, message, confirmText = 'Confirm', cancelText = 'Cancel', danger = false }) {
  return new Promise((resolve) => {
    const cancelButton = h('button', { type: 'button', class: 'btn btn-ghost', dataset: { action: 'cancel' } }, cancelText);
    const confirmButton = h(
      'button',
      { type: 'button', class: ['btn', danger ? 'btn-danger' : 'btn-primary'], dataset: { action: 'confirm' } },
      confirmText,
    );
    const titleId = `dialog-title-${Date.now()}`;
    const dialog = h(
      'dialog',
      { class: 'dialog', attrs: { 'aria-labelledby': titleId } },
      h('h2', { class: 'dialog-title', id: titleId }, title),
      h('p', { class: 'dialog-message' }, message),
      h('div', { class: 'dialog-actions' }, cancelButton, confirmButton),
    );

    let settled = false;
    const close = (result) => {
      if (settled) return;
      settled = true;
      if (dialog.open) dialog.close();
      dialog.remove();
      resolve(result);
    };

    cancelButton.addEventListener('click', () => close(false));
    confirmButton.addEventListener('click', () => close(true));
    dialog.addEventListener('cancel', (event) => {
      event.preventDefault();
      close(false);
    });
    // Clicking the backdrop (outside the dialog box) cancels.
    dialog.addEventListener('click', (event) => {
      if (event.target === dialog) close(false);
    });

    document.body.append(dialog);
    if (typeof dialog.showModal === 'function') dialog.showModal();
    else dialog.setAttribute('open', '');
    (danger ? cancelButton : confirmButton).focus();
  });
}
