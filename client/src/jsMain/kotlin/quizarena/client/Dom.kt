package quizarena.client

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.Node
import org.w3c.dom.events.Event
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/**
 * Minimal DOM toolkit. All text goes through text nodes (never innerHTML), so
 * question content can safely contain markup-looking strings like "<div>".
 *
 * Where the JavaScript version took a props object, this is a typed builder: a
 * misspelled helper is a compile error rather than an attribute silently named
 * "clss".
 */
class El(val node: HTMLElement) {
    /** Appends text. */
    operator fun String.unaryPlus() {
        node.appendChild(document.createTextNode(this))
    }

    /** Appends a built element. */
    operator fun Node.unaryPlus() {
        node.appendChild(this)
    }

    operator fun Iterable<Node>.unaryPlus() {
        forEach { node.appendChild(it) }
    }

    fun attr(name: String, value: String?) {
        if (value != null) node.setAttribute(name, value)
    }

    fun data(name: String, value: String) = node.setAttribute("data-$name", value)

    fun on(event: String, handler: (Event) -> Unit) {
        node.addEventListener(event, { handler(it) })
    }

    /** Text with `backticked` spans turned into <code> elements. */
    fun rich(text: String?) {
        richText(text).forEach { node.appendChild(it) }
    }

    var className: String
        get() = node.className
        set(value) {
            node.className = value
        }
}

fun h(tag: String, cls: String? = null, block: El.() -> Unit = {}): HTMLElement {
    val node = document.createElement(tag) as HTMLElement
    if (cls != null) node.className = cls
    El(node).block()
    return node
}

/** Shorthand for an element whose only content is text. */
fun t(tag: String, cls: String? = null, text: String): HTMLElement = h(tag, cls) { +text }

/**
 * Splits text on paired backticks into text nodes and <code> elements. Question
 * banks write inline code that way, and rendering it as markup beats showing
 * the marks. Still text nodes only, so content like "<div>" stays literal.
 * An unpaired backtick is left as an ordinary character.
 */
fun richText(text: String?): List<Node> {
    val source = text ?: return emptyList()
    val parts = source.split("`")
    return parts.mapIndexedNotNull { i, part ->
        when {
            i % 2 == 0 -> if (part.isEmpty()) null else document.createTextNode(part)
            i < parts.size - 1 -> h("code") { +part }
            // A trailing odd piece never closed, so the mark is ordinary text.
            else -> document.createTextNode("`$part")
        }
    }
}

/** Formats milliseconds as m:ss (or h:mm:ss). */
fun formatClock(ms: Double): String {
    val totalSeconds = ceil(maxOf(0.0, ms) / 1000).toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = (totalSeconds % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$seconds" else "$minutes:$seconds"
}

fun formatClock(ms: Long): String = formatClock(ms.toDouble())

/** Human-friendly duration: "45s", "3m 12s", "1h 4m". */
fun formatDuration(ms: Long): String {
    // floor(x + 0.5), not round(): Kotlin rounds a tie to even, so 500 ms would
    // become "0s" and 2500 ms "2s". Everything else here rounds ties up.
    val totalSeconds = floor(maxOf(0L, ms).toDouble() / 1000 + 0.5).toInt()
    if (totalSeconds < 60) return "${totalSeconds}s"
    val minutes = totalSeconds / 60
    if (minutes < 60) return "${minutes}m ${totalSeconds % 60}s"
    return "${minutes / 60}h ${minutes % 60}m"
}

private val dateFormatter: dynamic = js(
    "new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' })",
)

fun formatDate(timestamp: Long): String =
    dateFormatter.format(kotlin.js.Date(timestamp.toDouble())) as String

private val FOCUSABLE = setOf("A", "BUTTON", "INPUT", "SELECT", "TEXTAREA")

/** Moves keyboard/screen-reader focus to an element without scrolling jank. */
fun focusElement(el: HTMLElement?) {
    if (el == null) return
    if (!el.hasAttribute("tabindex") && el.tagName !in FOCUSABLE) {
        el.setAttribute("tabindex", "-1")
    }
    el.asDynamic().focus(js("({ preventScroll: true })"))
}

fun prefersReducedMotion(): Boolean =
    window.matchMedia("(prefers-reduced-motion: reduce)").matches

// ------------------------------------------------------------- queries

fun Element.query(selector: String): HTMLElement? = querySelector(selector) as? HTMLElement

fun Element.queryAll(selector: String): List<HTMLElement> {
    val found = querySelectorAll(selector)
    return (0 until found.length).mapNotNull { found.item(it) as? HTMLElement }
}

fun Document.query(selector: String): HTMLElement? = querySelector(selector) as? HTMLElement

fun Document.queryAll(selector: String): List<HTMLElement> {
    val found = querySelectorAll(selector)
    return (0 until found.length).mapNotNull { found.item(it) as? HTMLElement }
}

/** Replaces an element's children in one step. */
fun HTMLElement.replaceChildren(vararg nodes: Node) {
    while (firstChild != null) removeChild(firstChild!!)
    nodes.forEach { appendChild(it) }
}

fun HTMLElement.replaceChildren(nodes: List<Node>) {
    while (firstChild != null) removeChild(firstChild!!)
    nodes.forEach { appendChild(it) }
}

/** Percentage helper shared by the bars and rings. */
fun percentOf(part: Int, whole: Int): Int =
    if (whole > 0) floor(part * 100.0 / whole + 0.5).toInt() else 0

internal fun absInt(value: Int) = abs(value)
