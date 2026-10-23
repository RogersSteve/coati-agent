package bot.coati.service

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.WindowInsets
import android.view.WindowManager
import android.content.Context
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import bot.coati.action.AgentAction
import bot.coati.action.ScrollAxis
import bot.coati.action.ScrollCandidate
import bot.coati.action.ScrollEvidence
import bot.coati.action.ScrollSession
import bot.coati.action.SemanticScroll
import bot.coati.coordinates.PixelBounds

/** Android adapter for scroll evidence and advertised actions. Never uses shell input. */
internal class ScrollAccessibility(private val service: CoatiAccessibilityService) {
    private fun topApp(): AccessibilityWindowInfo? {
        val windows = service.windows
        if (windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }) return null
        val window = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.maxByOrNull { it.layer } ?: return null
        // A focused system dialog belongs to neither the underlying App nor Coati's overlay.
        if (windows.any {
            if (!it.isFocused || it.id == window.id) false else {
                val focusedRoot = it.root
                try { focusedRoot?.packageName?.toString() != service.packageName }
                finally { focusedRoot?.recycle() }
            }
        }) return null
        val root = window.root ?: return null
        return try { window.takeIf { root.packageName != null && root.packageName.toString() != service.packageName } }
        finally { root.recycle() }
    }

    private fun bounds(node: AccessibilityNodeInfo): PixelBounds {
        val r = Rect(); node.getBoundsInScreen(r)
        return PixelBounds(r.left, r.top, r.right, r.bottom)
    }

    private fun axis(node: AccessibilityNodeInfo, actions: Set<Int>): ScrollAxis? {
        SemanticScroll.directionalAxis(actions)?.let { return it }
        node.collectionInfo?.let {
            if (it.rowCount == 1 && it.columnCount > 1) return ScrollAxis.HORIZONTAL
            if (it.columnCount == 1 && it.rowCount > 1) return ScrollAxis.VERTICAL
        }
        // Flutter and native lists often wrap their row/column in single-child nodes.
        // Stop at the first branching layout; never infer from viewport aspect ratio.
        var current = node
        val owned = mutableListOf<AccessibilityNodeInfo>()
        try {
            repeat(8) {
                if (current.childCount > 1) {
                    val boxes = (0 until minOf(current.childCount, 32)).mapNotNull { i ->
                        current.getChild(i)?.let { child ->
                            try { bounds(child).takeIf { b -> b.right > b.left && b.bottom > b.top } }
                            finally { child.recycle() }
                        }
                    }
                    return SemanticScroll.geometryAxis(boxes)
                }
                if (current.childCount != 1) return null
                current = current.getChild(0)?.also(owned::add) ?: return null
                if (current.isScrollable) return null // nested list is not its parent's axis
            }
            return null
        } finally { owned.forEach { it.recycle() } }
    }

    private fun candidates(root: AccessibilityNodeInfo): List<ScrollCandidate> {
        val result = mutableListOf<ScrollCandidate>()
        val stack = ArrayDeque<Pair<AccessibilityNodeInfo, List<Int>>>()
        stack.add(root to emptyList())
        var budget = 1500
        try {
            while (stack.isNotEmpty() && budget-- > 0) {
                val (node, path) = stack.removeLast()
                try {
                    if (!node.isVisibleToUser) continue
                    if (node.isScrollable && node.isEnabled && node.rangeInfo == null && !node.isEditable) {
                        val actions = node.actionList.map { it.id }.toSet()
                        result.add(ScrollCandidate(path, bounds(node), actions, axis(node, actions)))
                    }
                    for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.add(it to (path + i)) }
                } finally { if (node !== root) node.recycle() }
            }
        } finally { stack.forEach { if (it.first !== root) it.first.recycle() } }
        return result
    }

    fun containerBounds(vertical: Boolean): PixelBounds? {
        val root = topApp()?.root ?: return null
        return try {
            candidates(root).filter { it.axis == if (vertical) ScrollAxis.VERTICAL else ScrollAxis.HORIZONTAL }
                .maxByOrNull { (it.bounds.right - it.bounds.left).toLong() * (it.bounds.bottom - it.bounds.top) }?.bounds
        } finally { root.recycle() }
    }

    fun session(swipe: AgentAction.Swipe): ScrollSession? {
        if (SemanticScroll.axis(swipe) == null) return null
        val window = topApp() ?: return null
        val root = window.root ?: return null
        return try {
            val viewport = bounds(root)
            if (!SemanticScroll.contains(viewport, swipe.x1, swipe.y1) || !SemanticScroll.contains(viewport, swipe.x2, swipe.y2)) return null
            // Never reinterpret a text-selection or slider drag as list scrolling.
            val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.add(root)
            var budget = 1500
            var blocked = false
            try {
                while (stack.isNotEmpty() && budget-- > 0) {
                    val node = stack.removeLast()
                    try {
                        if (!node.isVisibleToUser) continue
                        if ((node.isEditable || node.rangeInfo != null) && SemanticScroll.contains(bounds(node), swipe.x1, swipe.y1)) blocked = true
                        for (i in 0 until node.childCount) node.getChild(i)?.let(stack::add)
                    } finally { if (node !== root) node.recycle() }
                }
            } finally { stack.forEach { if (it !== root) it.recycle() } }
            if (blocked) return null
            val target = SemanticScroll.select(candidates(root), swipe)
            ScrollSession(root.packageName.toString(), window.id, target?.bounds ?: viewport,
                target, target?.let { SemanticScroll.action(it, swipe) })
        } finally { root.recycle() }
    }

    private fun matchingWindow(session: ScrollSession): AccessibilityWindowInfo? {
        val window = topApp()?.takeIf { it.id == session.windowId } ?: return null
        val root = window.root ?: return null
        return try { window.takeIf { root.packageName?.toString() == session.packageName } }
        finally { root.recycle() }
    }

    /** Null means window/target changed, false means action rejected/boundary. Exactly one call. */
    fun perform(session: ScrollSession): Boolean? {
        val path = session.candidate?.path ?: return false
        val action = session.actionId ?: return false
        val window = matchingWindow(session) ?: return null
        var node = window.root ?: return null
        try {
            for (index in path) {
                val next = node.getChild(index) ?: return null
                node.recycle(); node = next
            }
            if (node.windowId != session.windowId || node.packageName?.toString() != session.packageName ||
                !node.isVisibleToUser || !node.isEnabled || !node.isScrollable || node.isEditable || node.rangeInfo != null ||
                bounds(node) != session.candidate.bounds || node.actionList.none { it.id == action }) return null
            return node.performAction(action)
        } finally { node.recycle() }
    }

    suspend fun evidence(session: ScrollSession): ScrollEvidence? {
        val root = matchingWindow(session)?.root ?: return null
        val signature = StringBuilder()
        val stack = ArrayDeque<AccessibilityNodeInfo>(); stack.add(root)
        var budget = 1500
        try {
            while (stack.isNotEmpty() && budget-- > 0) {
                val node = stack.removeLast()
                try {
                    if (!node.isVisibleToUser) continue
                    val b = bounds(node)
                    if (b.right > session.viewport.left && b.left < session.viewport.right && b.bottom > session.viewport.top && b.top < session.viewport.bottom)
                        signature.append(node.className).append('|').append(node.text).append('|').append(node.contentDescription)
                            .append('|').append(b).append('|').append(node.isChecked).append('|').append(node.isSelected).append('\n')
                    for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let(stack::add)
                } finally { if (node !== root) node.recycle() }
            }
        } finally { stack.forEach { if (it !== root) it.recycle() }; root.recycle() }
        val hardware = service.takeScreenshot() ?: return null
        val bitmap = try { hardware.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware.recycle() }
            ?: return null
        try {
            if (matchingWindow(session) == null) return null
            val b = session.viewport
            if (b.left < 0 || b.top < 0 || b.right > bitmap.width || b.bottom > bitmap.height || b.right <= b.left || b.bottom <= b.top) return null
            val masks = service.windows.mapNotNull { window ->
                val r = window.root ?: return@mapNotNull null
                try {
                    if (r.packageName?.toString() == service.packageName) Rect().also(window::getBoundsInScreen) else null
                } finally { r.recycle() }
            }
            val metrics = (service.getSystemService(Context.WINDOW_SERVICE) as WindowManager).maximumWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            val pixels = IntArray(64 * 64)
            for (y in 0 until 64) for (x in 0 until 64) {
                val px = b.left + (b.right - b.left - 1) * x / 63
                val py = b.top + (b.bottom - b.top - 1) * y / 63
                if (py < insets.top || py >= bitmap.height - insets.bottom || masks.any { it.contains(px, py) }) continue
                pixels[y * 64 + x] = bitmap.getPixel(px, py)
            }
            return ScrollEvidence(signature.toString(), pixels, bitmap.width, bitmap.height)
        } finally { bitmap.recycle() }
    }
}
