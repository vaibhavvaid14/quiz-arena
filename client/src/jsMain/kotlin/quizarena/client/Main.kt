package quizarena.client

import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.w3c.dom.HTMLElement

/**
 * Browser entry point: wires storage and the API client, then mounts the app.
 *
 * `theme-init.js` still runs first from <head> as a classic script, applying the
 * saved theme before the bundle loads so there is no light/dark flash.
 */
fun main() {
    val resolved = resolveBackend()
    val storage = Storage(
        backend = resolved.backend,
        onError = { error -> console.warn("[quiz] storage error", error) },
    )
    val api = Api(getKey = { storage.getCurrentPlayer()?.key })

    val root = document.getElementById("app") as? HTMLElement ?: return
    val chrome = document.getElementById("site-header") as? HTMLElement

    val app = App(
        root = root,
        api = api,
        storage = storage,
        persistent = resolved.persistent,
        chrome = chrome,
    )
    MainScope().launch { app.start() }
}
