package bot.coati.perception

import bot.coati.coordinates.PixelBounds
import bot.coati.model.CloudModelGateway
import bot.coati.model.GatewayUtils
import bot.coati.model.StructuredTextFinishReason
import bot.coati.model.StructuredTextRequest
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Screenshot -> [ParsedScreen] with one compact vision-model call. Used when the accessibility
 * tree cannot name the controls (Flutter, games, WebView, custom canvases).
 *
 * The positional-array schema keeps output around 1-2k tokens, which is what makes a per-step
 * parse competitive with the classic reasoning + verify pair.
 */
class VisionScreenParser(private val gateway: CloudModelGateway) {

    /**
     * @param toPixels maps a normalized 0..1000 point of the full captured frame to display pixels.
     */
    suspend fun parse(
        screenshotJpeg: ByteArray,
        imageWidth: Int,
        imageHeight: Int,
        packageName: String,
        toPixels: (Int, Int) -> Pair<Int, Int>,
    ): Result<ParsedScreen> {
        var lastError: Exception = IllegalStateException("vision_parse_not_attempted")
        repeat(MAX_ATTEMPTS) {
            try {
                val result = gateway.generateStructuredText(
                    StructuredTextRequest(
                        systemPrompt = SYSTEM_PROMPT,
                        userPrompt = userPrompt(imageWidth, imageHeight),
                        temperature = 0.0,
                        maxOutputTokens = MAX_OUTPUT_TOKENS,
                        imageJpeg = screenshotJpeg,
                        lowLatency = true,
                    ),
                ).getOrThrow()
                if (result.finishReason == StructuredTextFinishReason.FILTERED) {
                    return Result.failure(IllegalStateException("vision_parse_filtered"))
                }
                return Result.success(parseResponse(result.text, packageName, toPixels))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Malformed JSON is occasional and non-deterministic; one retry recovers most cases.
                lastError = e
            }
        }
        return Result.failure(lastError)
    }

    companion object {
        private const val MAX_ATTEMPTS = 2
        private const val MAX_OUTPUT_TOKENS = 8192

        private val SYSTEM_PROMPT = """
            You are a mobile GUI parser. You convert one Android screenshot into compact JSON for an
            executor that can only act on the elements you list. Output JSON only, never markdown.
        """.trimIndent()

        fun userPrompt(width: Int, height: Int): String = """
            Screenshot is ${width}x$height px. Output ONLY this JSON:
            {"app":"name or ?","page":"page type","summary":"<=20 words current state","kb":false,"overlay":null,
            "regions":[["R1","role","V|H|VH|-",x1,y1,x2,y2]],
            "els":[["E1","R1","type","visible text verbatim","what it does (<=8 words, for icons name the glyph)","OPS","state",x1,y1,x2,y2]]}
            - coords: integers 0-1000 normalized to width/height. role: status_bar|top_bar|search|tabs|content|input_bar|bottom_nav|overlay
            - type: icon|button|text_btn|tab|input|item|card|image|avatar|switch|checkbox|text ; OPS: '|'-joined subset of TAP,LONG,INPUT, or '-' for static text
            - state: selected|unselected|on|off|focused|disabled|- ; kb=true when a soft keyboard is visible; overlay = short description of a dialog/popup covering the page, else null
            - V/H = region scrolls vertically/horizontally (items cut at an edge are evidence).
            - List EVERY tappable element (icons, tabs, list items, buttons, links, switches, inputs). Keep static text only if it carries task-relevant information.
            - Text verbatim in the original language. Do not invent elements.
        """.trimIndent()

        fun parseResponse(
            text: String,
            packageName: String,
            toPixels: (Int, Int) -> Pair<Int, Int>,
        ): ParsedScreen {
            val json = JSONObject(GatewayUtils.extractJsonFromText(text))

            fun box(arr: JSONArray, from: Int): PixelBounds? {
                val coords = (from until from + 4).map { arr.optInt(it, -1).coerceIn(-1, 1000) }
                if (coords.any { it < 0 } || coords[2] <= coords[0] || coords[3] <= coords[1]) return null
                val (l, t) = toPixels(coords[0], coords[1])
                val (r, b) = toPixels(coords[2], coords[3])
                return if (r > l && b > t) PixelBounds(l, t, r, b) else null
            }

            val regions = buildList {
                val arr = json.optJSONArray("regions") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val r = arr.optJSONArray(i) ?: continue
                    if (r.length() < 7) continue
                    val bounds = box(r, 3) ?: continue
                    val scroll = r.optString(2)
                    val axes = buildSet {
                        if ('V' in scroll) add(ScrollAxis.VERTICAL)
                        if ('H' in scroll) add(ScrollAxis.HORIZONTAL)
                    }
                    add(ScreenRegion(r.optString(0), r.optString(1), axes, bounds))
                }
            }
            val elements = buildList {
                val arr = json.optJSONArray("els") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONArray(i) ?: continue
                    if (e.length() < 11) continue
                    val bounds = box(e, 7) ?: continue
                    val ops = e.optString(5).split('|').mapNotNull { op ->
                        when (op.trim().uppercase()) {
                            "TAP" -> ElementOp.TAP
                            "LONG" -> ElementOp.LONG
                            "INPUT" -> ElementOp.INPUT
                            else -> null
                        }
                    }.distinct()
                    add(
                        ScreenElement(
                            id = e.optString(0).ifBlank { "E${i + 1}" },
                            type = e.optString(2),
                            text = e.optString(3).take(MAX_TEXT),
                            purpose = e.optString(4).take(MAX_TEXT),
                            ops = ops,
                            state = e.optString(6).takeUnless { it.isBlank() || it == "-" },
                            bounds = bounds,
                        ),
                    )
                }
            }
            return ParsedScreen(
                source = ScreenSource.VISION,
                packageName = packageName,
                summary = json.optString("summary").take(MAX_SUMMARY),
                keyboardVisible = json.optBoolean("kb", false),
                overlay = json.optString("overlay").takeUnless { it.isBlank() || it == "null" },
                elements = elements.distinctBy { it.id },
                regions = regions.distinctBy { it.id },
            )
        }

        private const val MAX_TEXT = 80
        private const val MAX_SUMMARY = 300
    }
}
