package bot.coati.fixture.marketplace

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Run state that must survive activity recreation but not `pm clear`: whether the promotion is
 * enabled, how many detail pages were opened and whether the promotion was already shown, and in
 * repeat mode the favorites, compare list, hidden listings and refresh count that tasks R1-R4 are
 * checked against (`run-as bot.coati.fixture.marketplace cat shared_prefs/market_session.xml`).
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
    private const val EXTRA_REPEAT = "repeat"
    private const val KEY_REPEAT = "repeat_mode"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_COMPARE = "compare"
    private const val KEY_HIDDEN = "hidden"
    private const val KEY_REFRESHES = "refreshes"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Applies the launch extras: `reset` clears all state first, then `promo` and `repeat` turn those modes on or off. */
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
        if (intent.hasExtra(EXTRA_REPEAT)) {
            val on = intent.getBooleanExtra(EXTRA_REPEAT, false)
            prefs(context).edit().putBoolean(KEY_REPEAT, on).commit()
            Log.i(TAG, "repeat mode=$on")
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

    fun isRepeatMode(context: Context) = prefs(context).getBoolean(KEY_REPEAT, false)

    fun repeatState(context: Context): RepeatState {
        val p = prefs(context)
        return RepeatState(
            favorites = p.getStringSet(KEY_FAVORITES, null).orEmpty().toSet(),
            compare = p.getStringSet(KEY_COMPARE, null).orEmpty().toSet(),
            hidden = p.getStringSet(KEY_HIDDEN, null).orEmpty().toSet(),
            refreshes = p.getInt(KEY_REFRESHES, 0),
        )
    }

    fun toggleFavorite(context: Context, id: String) = updateSet(context, KEY_FAVORITES, id) { it.toggled(id) }

    fun toggleCompare(context: Context, id: String) = updateSet(context, KEY_COMPARE, id) { it.toggled(id) }

    fun hide(context: Context, id: String) = updateSet(context, KEY_HIDDEN, id) { it + id }

    /** Records one refresh of the list and returns the new count. */
    fun refresh(context: Context): Int {
        val p = prefs(context)
        val count = p.getInt(KEY_REFRESHES, 0) + 1
        p.edit().putInt(KEY_REFRESHES, count).commit()
        Log.i(TAG, "refresh count=$count")
        return count
    }

    private fun updateSet(context: Context, key: String, id: String, change: (Set<String>) -> Set<String>): Set<String> {
        val p = prefs(context)
        val next = change(p.getStringSet(key, null).orEmpty().toSet())
        // A new set instance: SharedPreferences must not be handed back the set it returned.
        p.edit().putStringSet(key, HashSet(next)).commit()
        Log.i(TAG, "$key id=$id now=${next.size}")
        return next
    }
}
