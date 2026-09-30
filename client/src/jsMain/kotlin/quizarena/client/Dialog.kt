package quizarena.client

import kotlinx.browser.document
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.HTMLElement
import kotlin.coroutines.resume
import kotlin.js.Date

/**
 * Accessible confirmation dialog built on the native <dialog> element
 * (focus trapping, Esc-to-cancel and an inert background come for free).
 *
 * Suspends until the person answers, so callers read as straight-line code
 * rather than a promise chain.
 */
suspend fun confirmDialog(
    title: String,
    message: String,
    confirmText: String = "Confirm",
    cancelText: String = "Cancel",
    danger: Boolean = false,
): Boolean = suspendCancellableCoroutine { continuation ->
    val cancelButton = h("button", "btn btn-ghost") {
        attr("type", "button")
        data("action", "cancel")
        +cancelText
    }
    val confirmButton = h("button", "btn ${if (danger) "btn-danger" else "btn-primary"}") {
        attr("type", "button")
        data("action", "confirm")
        +confirmText
    }
    val titleId = "dialog-title-${Date.now().toLong()}"
    val dialog = h("dialog", "dialog") {
        attr("aria-labelledby", titleId)
        +h("h2", "dialog-title") {
            attr("id", titleId)
            +title
        }
        +t("p", "dialog-message", message)
        +h("div", "dialog-actions") {
            +cancelButton
            +confirmButton
        }
    }

    var settled = false
    fun close(result: Boolean) {
        if (settled) return
        settled = true
        val d = dialog.asDynamic()
        // `open` is undefined where <dialog> is unsupported; casting it to
        // Boolean would throw and strand this coroutine.
        if (d.open == true) d.close()
        dialog.remove()
        if (continuation.isActive) continuation.resume(result)
    }

    cancelButton.addEventListener("click", { close(false) })
    confirmButton.addEventListener("click", { close(true) })
    dialog.addEventListener("cancel", { event ->
        event.preventDefault()
        close(false)
    })
    // Clicking the backdrop (outside the dialog box) cancels.
    dialog.addEventListener("click", { event ->
        if (event.target === dialog) close(false)
    })

    document.body?.appendChild(dialog)
    val d = dialog.asDynamic()
    if (d.showModal != undefined) d.showModal() else dialog.setAttribute("open", "")
    (if (danger) cancelButton else confirmButton).focus()

    continuation.invokeOnCancellation { close(false) }
}
