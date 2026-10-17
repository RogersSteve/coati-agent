package bot.coati.fixture.marketplace

import android.content.Context
import org.json.JSONObject

/**
 * Repeat mode (tasks R1-R4): the same 40 listings with the listing ID on every row, a Sold badge,
 * per-row Favorite / Compare / Hide buttons and a Refresh button. Each step is one tap or one glance,
 * repeated many times; the outcome is persisted (see [MarketSession]) and checked from that state.
 */
internal class RepeatConfig(
    val favoriteCount: Int,
    val compare: List<String>,
    val sold: Set<String>,
    val watchId: String,
    val dropAtRefresh: Int,
    val newPriceUsd: Int,
) {
    fun isSold(id: String) = id in sold

    /** The watched listing's price drops on the [dropAtRefresh]-th refresh and stays dropped. */
    fun priceUsd(listing: Listing, refreshes: Int): Int =
        if (listing.id == watchId && refreshes >= dropAtRefresh) newPriceUsd else listing.priceUsd

    companion object {
        const val ASSET = "repeat.json"

        @Volatile
        private var loaded: RepeatConfig? = null

        fun get(context: Context): RepeatConfig = loaded ?: synchronized(this) {
            loaded ?: parse(context.assets.open(ASSET).bufferedReader().use { it.readText() }).also { loaded = it }
        }

        fun parse(json: String): RepeatConfig {
            val root = JSONObject(json)
            val watch = root.getJSONObject("watch")
            fun strings(key: String) = root.getJSONArray(key).let { a -> List(a.length()) { a.getString(it) } }
            return RepeatConfig(
                favoriteCount = root.getInt("favorite_count"),
                compare = strings("compare"),
                sold = strings("sold").toSet(),
                watchId = watch.getString("id"),
                dropAtRefresh = watch.getInt("drop_at_refresh"),
                newPriceUsd = watch.getInt("new_price_usd"),
            )
        }
    }
}

/** The user's actions in repeat mode, as plain values so the list logic is testable without Android. */
internal data class RepeatState(
    val favorites: Set<String> = emptySet(),
    val compare: Set<String> = emptySet(),
    val hidden: Set<String> = emptySet(),
    val refreshes: Int = 0,
) {
    fun visible(listings: List<Listing>): List<Listing> = listings.filter { it.id !in hidden }
}

/** Toggling: tapping again removes the ID (an "undo" a real app offers too). */
internal fun Set<String>.toggled(id: String): Set<String> = if (id in this) this - id else this + id
