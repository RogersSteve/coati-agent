package bot.coati.coordinates

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display
import android.view.WindowInsets
import android.view.WindowManager
import bot.coati.service.CoatiAccessibilityService

object DisplaySpaceReader {
    @Suppress("DEPRECATION")
    fun read(service: CoatiAccessibilityService): DisplaySpace? = try {
        val display = (service.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY)
        val real = DisplayMetrics().also { display.getRealMetrics(it) }
        val window = (service.getSystemService(Context.WINDOW_SERVICE) as WindowManager).maximumWindowMetrics
        val bars = window.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val visible = window.windowInsets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val root = service.rootInActiveWindow
        if (root == null) null else try {
            val appWindow = root.window
            if (appWindow == null) null else try {
                val bounds = Rect().also { appWindow.getBoundsInScreen(it) }
                fun Rect.pixels() = PixelBounds(left, top, right, bottom)
                DisplaySpace(real.widthPixels, real.heightPixels, display.rotation, appWindow.displayId,
                    window.bounds.pixels(), bounds.pixels(), PixelBounds(bars.left, bars.top, bars.right, bars.bottom),
                    real.densityDpi, root.windowId, root.packageName?.toString().orEmpty(), service.magnificationController.scale,
                    PixelBounds(visible.left, visible.top, visible.right, visible.bottom))
                    .also { it.validate() }
            } finally { appWindow.recycle() }
        } finally { root.recycle() }
    } catch (e: Exception) {
        // Fail closed, but say why: an unsupported window (dialog, split screen) looks identical to a bug otherwise.
        android.util.Log.w("CoatiDisplay", "Display space unavailable: ${e.message}")
        null
    }
}
