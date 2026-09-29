package quizarena.client

import kotlinx.browser.document
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.svg.SVGElement
import kotlin.math.PI
import kotlin.math.roundToInt

/** Reusable presentational components (pure DOM factories). */

private const val SVG_NS = "http://www.w3.org/2000/svg"

/** Same idea as [h], in the SVG namespace, where attributes are the only option. */
fun svg(tag: String, attrs: Map<String, String> = emptyMap(), children: List<Element> = emptyList()): Element {
    val node = document.createElementNS(SVG_NS, tag) as Element
    attrs.forEach { (name, value) -> node.setAttribute(name, value) }
    children.forEach { node.appendChild(it) }
    return node
}

/** Status is never conveyed by colour alone: every status has an icon and a label. */
data class StatusMeta(val label: String, val icon: String, val tone: String)

val STATUS_META = mapOf(
    ItemStatus.CORRECT to StatusMeta("Correct", "✓", "good"),
    ItemStatus.WRONG to StatusMeta("Incorrect", "✗", "critical"),
    ItemStatus.TIMEOUT to StatusMeta("Timed out", "⏱", "warning"),
    ItemStatus.SKIPPED to StatusMeta("Skipped", "–", "muted"),
    ItemStatus.PENDING to StatusMeta("Unanswered", "–", "muted"),
)

fun statusMetaFor(status: String): StatusMeta =
    STATUS_META[status] ?: STATUS_META.getValue(ItemStatus.PENDING)

fun statusPill(status: String): HTMLElement {
    val meta = statusMetaFor(status)
    return h("span", "pill tone-${meta.tone}") {
        +h("span", "pill-icon") {
            attr("aria-hidden", "true")
            +meta.icon
        }
        +meta.label
    }
}

fun chip(text: String, extraClass: String = ""): HTMLElement =
    h("span", listOf("chip", extraClass).filter { it.isNotEmpty() }.joinToString(" ")) { +text }

fun difficultyChip(difficulty: String): HTMLElement =
    chip(Config.DIFFICULTY_LABELS[difficulty] ?: difficulty, "chip-$difficulty")

fun statTile(label: String, value: String, detail: String? = null): HTMLElement =
    h("div", "stat-tile") {
        +t("span", "stat-label", label)
        +t("span", "stat-value", value)
        detail?.let { +t("span", "stat-detail", it) }
    }

/**
 * Circular countdown. [TimerRing.update] takes the timer state; the colour steps
 * from normal to warning to danger as time runs out, always alongside the text,
 * never by colour alone.
 */
class TimerRing(label: String = "Time left") {
    private val radius = 26.0
    private val circumference = 2 * PI * radius
    private val progress = svg(
        "circle",
        mapOf(
            "class" to "timer-ring-progress",
            "cx" to "32", "cy" to "32", "r" to radius.toString(),
            "stroke-dasharray" to circumference.asFixed(2),
            "stroke-dashoffset" to "0",
        ),
    )
    private val text = t("span", "timer-ring-text", "0:00")

    val el: HTMLElement = h("div", "timer-ring") {
        attr("role", "timer")
        attr("aria-label", label)
        +svg(
            "svg",
            mapOf("viewBox" to "0 0 64 64", "aria-hidden" to "true"),
            listOf(
                svg("circle", mapOf("class" to "timer-ring-track", "cx" to "32", "cy" to "32", "r" to radius.toString())),
                progress,
            ),
        )
        +text
    }

    fun update(state: TimerState) {
        text.textContent = formatClock(state.remainingMs)
        progress.setAttribute("stroke-dashoffset", (circumference * (1 - state.fraction)).asFixed(2))
        el.setAttribute(
            "data-level",
            when {
                state.fraction <= Config.TIMER_DANGER_FRACTION -> "danger"
                state.fraction <= Config.TIMER_WARNING_FRACTION -> "warning"
                else -> "normal"
            },
        )
    }
}

/** Big percentage ring used as the results "hero number". */
fun scoreRing(percentage: Int): HTMLElement {
    val radius = 54.0
    val circumference = 2 * PI * radius
    val clamped = percentage.coerceIn(0, 100)
    return h("div", "score-ring") {
        attr("role", "img")
        attr("aria-label", "Score $clamped percent")
        +svg(
            "svg",
            mapOf("viewBox" to "0 0 128 128", "aria-hidden" to "true"),
            listOf(
                svg("circle", mapOf("class" to "score-ring-track", "cx" to "64", "cy" to "64", "r" to radius.toString())),
                svg(
                    "circle",
                    mapOf(
                        "class" to "score-ring-progress",
                        "cx" to "64", "cy" to "64", "r" to radius.toString(),
                        "stroke-dasharray" to circumference.asFixed(2),
                        "stroke-dashoffset" to (circumference * (1 - clamped / 100.0)).asFixed(2),
                    ),
                ),
            ),
        )
        +t("span", "score-ring-value", "$clamped%")
    }
}

/** One row of an accuracy bar chart. */
data class BarRow(
    val label: String,
    val correct: Int,
    val total: Int,
    val percentage: Int,
    val prefix: String? = null,
)

/**
 * Horizontal accuracy bars — one series, one hue, the value at the bar tip, and
 * a hover/focus tooltip with the exact counts.
 */
fun accuracyBars(rows: List<BarRow>, caption: String): HTMLElement =
    h("figure", "bars") {
        +t("figcaption", "bars-caption", caption)
        +h("ul", "bars-list") {
            rows.forEach { row ->
                +h("li", "bar-row") {
                    attr("tabindex", "0")
                    attr(
                        "aria-label",
                        "${row.label}: ${row.percentage}% (${row.correct} of ${row.total} correct)",
                    )
                    +t("span", "bar-label", row.prefix?.let { "$it ${row.label}" } ?: row.label)
                    +h("span", "bar-track") {
                        // The plot has a fixed extent so bar length stays proportional
                        // to the value; the label sits in its own column after it.
                        +h("span", "bar-plot") {
                            +h("span", "bar-fill") {
                                node.setAttribute("style", "width: ${row.percentage}%")
                            }
                        }
                        +t("span", "bar-value", "${row.percentage}%")
                    }
                    +h("span", "bar-tooltip") {
                        attr("role", "tooltip")
                        +"${row.correct} of ${row.total} correct"
                    }
                }
            }
        }
    }

/** Placeholder shown while a screen's data loads. */
fun loadingState(message: String = "Loading…"): HTMLElement =
    h("p", "loading") {
        attr("role", "status")
        +h("span", "spinner") { attr("aria-hidden", "true") }
        +message
    }

fun emptyState(icon: String, title: String, message: String, action: HTMLElement? = null): HTMLElement =
    h("div", "empty-state") {
        +h("span", "empty-icon") {
            attr("aria-hidden", "true")
            +icon
        }
        +t("h2", text = title)
        +t("p", text = message)
        action?.let { +it }
    }

/** A primary/secondary button with a click handler. */
fun button(label: String, cls: String = "btn btn-primary", onClick: () -> Unit): HTMLElement =
    h("button", cls) {
        attr("type", "button")
        +label
        on("click") { onClick() }
    }

private fun Double.asFixed(digits: Int): String {
    val factor = generateSequence(1.0) { it * 10 }.elementAt(digits)
    return ((this * factor).roundToInt() / factor).toString()
}
