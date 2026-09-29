package quizarena.client

import org.w3c.dom.HTMLElement

/**
 * Placeholders for the screens still being ported. Each is replaced by its own
 * file as the port reaches it; this exists so the module keeps compiling in
 * between, rather than accumulating errors across several screens at once.
 */

fun renderSetupScreenStub(root: HTMLElement, ctx: AppContext, params: ScreenParams): (() -> Unit)? {
    root.appendChild(
        h("section", "screen screen-setup") {
            +t("h1", text = "Setup")
            +t("p", "lede", "Not ported yet.")
        },
    )
    return null
}

