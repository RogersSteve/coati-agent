package bot.coati.fixture.manual

import android.content.SharedPreferences
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Custom-drawn pages for agent benchmarks. Their controls are painted on a Canvas and expose no
 * accessibility semantics, like Flutter/game UIs without semantics: an agent can only perceive them
 * from pixels. State is persisted in "manual_lab_state" so a harness can verify outcomes with
 * `run-as bot.coati.fixture.manual cat shared_prefs/manual_lab_state.xml`.
 *
 * Text entry is covered twice: the workbench title field raises the soft keyboard but has no
 * accessibility node (input must go through the IME connection), while the order page's native
 * fields sit low on the page, so opening the keyboard scrolls and reflows the whole page.
 */

private enum class WorkbenchTab(val label: String) { LAYERS("Layers"), BRUSH("Brush"), EXPORT("Export") }

private val brushColors = listOf("red" to "Red", "green" to "Green", "blue" to "Blue")
private val swatchColors = mapOf("red" to Color(0xFFD32F2F), "green" to Color(0xFF388E3C), "blue" to Color(0xFF1976D2))
/** English names for the catalog entries offered on the mixed order page. */
private val orderItemNames = mapOf(
    "travel" to "Offline travel checklist",
    "meeting" to "Meeting prep checklist",
    "emergency" to "Home emergency checklist",
)

private val layers = listOf("background" to "Background", "sketch" to "Sketch", "paint" to "Paint")

/** Hit targets shared by drawing and tap handling, in canvas pixels. */
private class WorkbenchLayout(width: Float, u: Float) {
    val back = Rect(Offset(16 * u, 20 * u), Size(88 * u, 44 * u))
    val tabs = WorkbenchTab.entries.mapIndexed { i, tab ->
        tab to Rect(Offset(i * width / 3, 90 * u), Size(width / 3, 52 * u))
    }
    val layerRows = layers.mapIndexed { i, layer ->
        layer.first to Rect(Offset(24 * u, (170 + i * 76) * u), Size(width - 48 * u, 64 * u))
    }
    val swatches = brushColors.mapIndexed { i, color ->
        color.first to Rect(Offset((24 + i * 108) * u, 200 * u), Size(88 * u, 88 * u))
    }
    val gridToggle = Rect(Offset(width - 110 * u, 330 * u), Size(86 * u, 44 * u))
    val save = Rect(Offset(24 * u, 410 * u), Size(width - 48 * u, 56 * u))
    val titleField = Rect(Offset(24 * u, TITLE_FIELD_TOP_DP * u), Size(width - 48 * u, 52 * u))
    val exportPng = Rect(Offset(24 * u, 280 * u), Size(width - 48 * u, 56 * u))
    val exportSvg = Rect(Offset(24 * u, 356 * u), Size(width - 48 * u, 56 * u))
}

/** Top of the Export tab's title field in dp, shared by the painted frame and the real input. */
private const val TITLE_FIELD_TOP_DP = 196

@Composable
internal fun CanvasWorkbenchPage(preferences: SharedPreferences, onBack: () -> Unit) {
    val measurer = rememberTextMeasurer()
    var tab by remember { mutableStateOf(WorkbenchTab.LAYERS) }
    var color by remember { mutableStateOf(preferences.getString("canvas_color", "red") ?: "red") }
    var grid by remember { mutableStateOf(preferences.getBoolean("canvas_grid", false)) }
    var saved by remember { mutableStateOf(preferences.getString("canvas_saved", null)) }
    var exported by remember { mutableStateOf(preferences.getString("canvas_export", null)) }
    var title by remember { mutableStateOf("") }
    var hiddenLayers by remember {
        mutableStateOf(preferences.getStringSet("canvas_hidden_layers", emptySet()).orEmpty().toSet())
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .clearAndSetSemantics { }
            .pointerInput(Unit) {
                detectTapGestures { p ->
                    val layout = WorkbenchLayout(size.width.toFloat(), density)
                    when {
                        layout.back.contains(p) -> onBack()
                        else -> layout.tabs.firstOrNull { it.second.contains(p) }?.let { tab = it.first }
                            ?: when (tab) {
                                WorkbenchTab.LAYERS -> layout.layerRows.firstOrNull { it.second.contains(p) }?.let { (id, _) ->
                                    hiddenLayers = if (id in hiddenLayers) hiddenLayers - id else hiddenLayers + id
                                    preferences.edit().putStringSet("canvas_hidden_layers", hiddenLayers).apply()
                                }
                                WorkbenchTab.BRUSH -> when {
                                    layout.gridToggle.contains(p) -> {
                                        grid = !grid
                                        preferences.edit().putBoolean("canvas_grid", grid).apply()
                                    }
                                    layout.save.contains(p) -> {
                                        saved = "$color|${if (grid) "grid_on" else "grid_off"}"
                                        preferences.edit().putString("canvas_saved", saved).apply()
                                    }
                                    else -> layout.swatches.firstOrNull { it.second.contains(p) }?.let { (id, _) ->
                                        color = id
                                        preferences.edit().putString("canvas_color", id).apply()
                                    }
                                }
                                WorkbenchTab.EXPORT -> when {
                                    layout.exportPng.contains(p) -> "png"
                                    layout.exportSvg.contains(p) -> "svg"
                                    else -> null
                                }?.let { format ->
                                    exported = "$format|${title.trim()}"
                                    preferences.edit().putString("canvas_export", exported).apply()
                                }
                            }
                    }
                }
            },
    ) {
        val u = density
        val layout = WorkbenchLayout(size.width, u)
        drawRect(Color(0xFFF7F3EC))
        drawButton(measurer, layout.back, "‹ Back", Color(0xFFE0DAD0), u)
        label(measurer, "Canvas Workbench", Offset(size.width / 2 - 100 * u, 28 * u), 24, u, bold = true)
        layout.tabs.forEach { (t, r) ->
            drawRect(if (t == tab) Color(0xFF6D4C41) else Color(0xFFD7CCC8), r.topLeft, r.size)
            label(measurer, t.label, Offset(r.center.x - 30 * u, r.top + 12 * u), 18, u,
                color = if (t == tab) Color.White else Color.Black)
        }
        when (tab) {
            WorkbenchTab.LAYERS -> layout.layerRows.forEach { (id, r) ->
                val hidden = id in hiddenLayers
                drawRoundRect(Color.White, r.topLeft, r.size, CornerRadius(12 * u))
                label(measurer, layers.first { it.first == id }.second, Offset(r.left + 20 * u, r.top + 18 * u), 18, u)
                label(measurer, if (hidden) "Hidden" else "Visible", Offset(r.right - 80 * u, r.top + 18 * u), 16, u,
                    color = if (hidden) Color.Gray else Color(0xFF2E7D32))
            }
            WorkbenchTab.BRUSH -> {
                label(measurer, "Brush color", Offset(24 * u, 164 * u), 18, u, bold = true)
                layout.swatches.forEach { (id, r) ->
                    drawCircle(swatchColors.getValue(id), r.width / 2, r.center)
                    if (id == color) drawCircle(Color.Black, r.width / 2 + 4 * u, r.center, style = Stroke(4 * u))
                    label(measurer, brushColors.first { it.first == id }.second, Offset(r.left + 20 * u, r.bottom + 4 * u), 14, u)
                }
                label(measurer, "Show grid", Offset(24 * u, 338 * u), 18, u)
                val t = layout.gridToggle
                drawRoundRect(if (grid) Color(0xFF6D4C41) else Color(0xFFBDBDBD), t.topLeft, t.size, CornerRadius(22 * u))
                drawCircle(Color.White, 18 * u, Offset(if (grid) t.right - 22 * u else t.left + 22 * u, t.center.y))
                drawButton(measurer, layout.save, "Save canvas", Color(0xFF6D4C41), u, textColor = Color.White)
                saved?.let { label(measurer, "Saved: $it", Offset(24 * u, 480 * u), 16, u, color = Color.DarkGray) }
            }
            WorkbenchTab.EXPORT -> {
                label(measurer, "Artwork title", Offset(24 * u, 164 * u), 18, u, bold = true)
                val f = layout.titleField
                drawRoundRect(Color.White, f.topLeft, f.size, CornerRadius(8 * u))
                if (title.isEmpty()) label(measurer, "Untitled", Offset(f.left + 14 * u, f.top + 14 * u), 18, u, color = Color.Gray)
                drawButton(measurer, layout.exportPng, "Export as PNG", Color(0xFF6D4C41), u, textColor = Color.White)
                drawButton(measurer, layout.exportSvg, "Export as SVG", Color(0xFF8D6E63), u, textColor = Color.White)
                exported?.let {
                    val parts = it.split("|")
                    val name = parts.getOrElse(1) { "" }
                    val suffix = if (name.isEmpty()) "" else " \"$name\""
                    label(measurer, "Exported: ${parts[0].uppercase()}$suffix", Offset(24 * u, 430 * u), 16, u, color = Color.DarkGray)
                }
            }
        }
    }
    if (tab == WorkbenchTab.EXPORT) {
        // A real text input (it raises the IME) that stays invisible to accessibility, like a
        // custom-rendered field; the Canvas paints its background and placeholder.
        BasicTextField(
            value = title,
            onValueChange = { title = it.take(40) },
            singleLine = true,
            textStyle = TextStyle(fontSize = 18.sp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier
                .offset(x = 24.dp, y = TITLE_FIELD_TOP_DP.dp)
                .padding(end = 48.dp)
                .fillMaxWidth()
                .height(52.dp)
                .border(1.dp, Color(0xFF8D6E63))
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .clearAndSetSemantics { },
        )
    }
    }
}

/** Native list and submit button around painted quantity and priority controls. */
@Composable
internal fun MixedOrderPage(preferences: SharedPreferences) {
    val measurer = rememberTextMeasurer()
    var item by remember { mutableStateOf("travel") }
    var quantity by remember { mutableStateOf(1) }
    var priority by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(preferences.getString("order_submitted", null)) }
    var recipient by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    PageIntro("Mixed Order", "Choose an item and submit an offline print order.")
    Text("Choose an item", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    ManualLabCatalog.entries.forEach { entry ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = item == entry.id, role = Role.RadioButton, onClick = { item = entry.id })
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = item == entry.id, onClick = null)
            Text(orderItemNames.getValue(entry.id), modifier = Modifier.padding(start = 8.dp))
        }
    }
    Text("Print options", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clearAndSetSemantics { }
            .pointerInput(Unit) {
                detectTapGestures { p ->
                    val u = density
                    val minus = Rect(Offset(size.width - 200 * u, 12 * u), Size(52 * u, 52 * u))
                    val plus = Rect(Offset(size.width - 70 * u, 12 * u), Size(52 * u, 52 * u))
                    val toggle = Rect(Offset(size.width - 104 * u, 90 * u), Size(86 * u, 44 * u))
                    when {
                        minus.contains(p) -> quantity = (quantity - 1).coerceAtLeast(1)
                        plus.contains(p) -> quantity = (quantity + 1).coerceAtMost(9)
                        toggle.contains(p) -> priority = !priority
                    }
                }
            },
    ) {
        val u = density
        drawRoundRect(Color(0xFFFFFBF5), Offset.Zero, size, CornerRadius(16 * u))
        label(measurer, "Copies", Offset(18 * u, 26 * u), 18, u)
        val minus = Rect(Offset(size.width - 200 * u, 12 * u), Size(52 * u, 52 * u))
        val plus = Rect(Offset(size.width - 70 * u, 12 * u), Size(52 * u, 52 * u))
        drawButton(measurer, minus, "−", Color(0xFFD7CCC8), u)
        drawButton(measurer, plus, "+", Color(0xFFD7CCC8), u)
        label(measurer, quantity.toString(), Offset(size.width - 120 * u, 24 * u), 22, u, bold = true)
        label(measurer, "Priority delivery", Offset(18 * u, 100 * u), 18, u)
        val toggle = Rect(Offset(size.width - 104 * u, 90 * u), Size(86 * u, 44 * u))
        drawRoundRect(if (priority) Color(0xFF6D4C41) else Color(0xFFBDBDBD), toggle.topLeft, toggle.size, CornerRadius(22 * u))
        drawCircle(Color.White, 18 * u, Offset(if (priority) toggle.right - 22 * u else toggle.left + 22 * u, toggle.center.y))
    }
    // Keep the delivery fields low on any screen height: opening the keyboard resizes the window and
    // scrolls the page, so every control moves between the tap on a field and the next action.
    Spacer(modifier = Modifier.height((LocalConfiguration.current.screenHeightDp * 0.3f).dp))
    Text("Delivery details", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    OutlinedTextField(
        value = recipient,
        onValueChange = { recipient = it.take(40) },
        label = { Text("Recipient") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = note,
        onValueChange = { note = it.take(80) },
        label = { Text("Delivery note") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = {
            submitted = listOf(item, quantity, priority, recipient.trim(), note.trim()).joinToString("|")
            preferences.edit().putString("order_submitted", submitted).apply()
        },
        enabled = recipient.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("Submit order")
    }
    submitted?.let { value ->
        val parts = value.split("|")
        val priorityText = if (parts[2] == "true") " (priority delivery)" else ""
        Text("Submitted: ${orderItemNames.getValue(parts[0])} × ${parts[1]}$priorityText for ${parts.getOrElse(3) { "" }}")
    }
}

private fun DrawScope.label(
    measurer: TextMeasurer,
    text: String,
    at: Offset,
    sp: Int,
    u: Float,
    color: Color = Color.Black,
    bold: Boolean = false,
) {
    drawText(
        measurer,
        text,
        topLeft = at,
        style = TextStyle(fontSize = sp.sp, color = color, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
    )
}

private fun DrawScope.drawButton(
    measurer: TextMeasurer,
    r: Rect,
    text: String,
    fill: Color,
    u: Float,
    textColor: Color = Color.Black,
) {
    drawRoundRect(fill, r.topLeft, r.size, CornerRadius(12 * u))
    val layout = measurer.measure(text, TextStyle(fontSize = 18.sp, color = textColor))
    drawText(layout, topLeft = Offset(r.center.x - layout.size.width / 2, r.center.y - layout.size.height / 2))
}
