package bot.coati.fixture.marketplace

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Run state that must survive activity recreation but not `pm clear`: whether the promotion is
 * enabled, how many detail pages were opened and whether the promotion was already shown.
 */
internal object MarketSession {
    const val TAG = "CoatiMarket"
    /** The promotion appears on this detail page view (counting every opening, repeats included). */
    const val PROMO_AT_VIEW = 5

    private const val PREFS = "market_session"
    private const val KEY_PROMO = "promo_enabled"
    private const val KEY_VIEWS = "detail_views"
    private const val KEY_PROMO_SHOWN = "promo_shown"
    private const val EXTRA_RESET = "reset"
    private const val EXTRA_PROMO = "promo"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Applies the launch extras: `reset` clears all state first, then `promo` turns the promotion on or off. */
    fun applyLaunchExtras(context: Context, intent: Intent): Boolean {
        val reset = intent.getBooleanExtra(EXTRA_RESET, false)
        if (reset) {
            prefs(context).edit().clear().commit()
            Log.i(TAG, "reset")
        }
        if (intent.hasExtra(EXTRA_PROMO)) {
            val on = intent.getBooleanExtra(EXTRA_PROMO, false)
            prefs(context).edit().putBoolean(KEY_PROMO, on).commit()
            Log.i(TAG, "promo enabled=$on")
        }
        return reset
    }

    /** Records one detail page view and returns whether the promotion should appear on it. */
    fun recordDetailView(context: Context, id: String): Boolean {
        val p = prefs(context)
        val views = p.getInt(KEY_VIEWS, 0) + 1
        p.edit().putInt(KEY_VIEWS, views).commit()
        Log.i(TAG, "detail opened id=$id view=$views")
        val promo = p.getBoolean(KEY_PROMO, false) && views >= PROMO_AT_VIEW && !p.getBoolean(KEY_PROMO_SHOWN, false)
        if (promo) p.edit().putBoolean(KEY_PROMO_SHOWN, true).commit()
        return promo
    }
}
