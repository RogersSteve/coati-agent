package bot.coati.jev

import bot.coati.coordinates.PixelBounds
import bot.coati.perception.ElementOp
import bot.coati.perception.ParsedScreen
import bot.coati.perception.ScreenElement
import bot.coati.perception.ScreenRegion
import bot.coati.perception.ScreenSource
import bot.coati.perception.ScrollAxis

internal fun element(
    id: String,
    text: String,
    vararg ops: ElementOp = arrayOf(ElementOp.TAP),
    type: String = "item",
    state: String? = null,
    bounds: PixelBounds = PixelBounds(0, 0, 100, 100),
) = ScreenElement(id, type, text, purpose = "", ops = ops.toList(), state = state, bounds = bounds)

internal fun screen(
    vararg elements: ScreenElement,
    regions: List<ScreenRegion> = emptyList(),
    keyboard: Boolean = false,
    summary: String = "",
) = ParsedScreen(
    source = ScreenSource.VISION,
    packageName = "com.example",
    summary = summary,
    keyboardVisible = keyboard,
    overlay = null,
    elements = elements.toList(),
    regions = regions,
)

internal fun verticalRegion(id: String = "R1", role: String = "content") =
    ScreenRegion(id, role, setOf(ScrollAxis.VERTICAL), PixelBounds(0, 200, 1080, 2000))
