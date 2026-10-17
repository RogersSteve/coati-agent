package bot.coati.fixture.marketplace

import android.content.Context
import org.json.JSONObject
import java.util.Locale

/** A value in the unit the seller entered ("mi" or "km"), shown as entered: the app never converts. */
internal data class Measure(val value: Int, val unit: String) {
    val label: String get() = String.format(Locale.US, "%,d %s", value, unit)
}

internal data class Listing(
    val id: String,
    val make: String,
    /** Model family shown in the list, e.g. "E-Class Sedan"; the exact model is on the detail page only. */
    val family: String,
    val year: Int,
    val model: String,
    val color: String,
    val priceUsd: Int,
    val mileage: Measure,
    val distance: Measure,
    val location: String,
    val accidents: String,
    val status: String,
    val seller: String,
    val note: String,
) {
    /** List row title: make and family only, so year, model, mileage and distance need the detail page. */
    val listTitle: String get() = "$make $family"
    val detailTitle: String get() = "$year $make $model"
    val priceLabel: String get() = String.format(Locale.US, "$%,d", priceUsd)

    /** Detail page fields, one readable "Label: value" line each, in display order. */
    fun detailLines(referenceCity: String): List<String> = listOf(
        "Listing ID: $id",
        "Status: $status",
        "Year: $year",
        "Model: $model",
        "Mileage: ${mileage.label}",
        "Location: $location",
        "Distance from $referenceCity: ${distance.label}",
        "Accident history: $accidents",
        "Exterior color: $color",
        "Seller: $seller",
    )
}

internal class MarketCatalog(val referenceCity: String, val listings: List<Listing>) {
    fun byId(id: String?): Listing? = listings.firstOrNull { it.id == id }

    companion object {
        /** The ground-truth file; scripts/device_bench/marketplace.py scores answers against the same file. */
        const val ASSET = "listings.json"

        @Volatile
        private var loaded: MarketCatalog? = null

        fun get(context: Context): MarketCatalog = loaded ?: synchronized(this) {
            loaded ?: parse(context.assets.open(ASSET).bufferedReader().use { it.readText() }).also { loaded = it }
        }

        fun parse(json: String): MarketCatalog {
            val root = JSONObject(json)
            val items = root.getJSONArray("listings")
            return MarketCatalog(
                referenceCity = root.getString("reference_city"),
                listings = List(items.length()) { i -> items.getJSONObject(i).toListing() },
            )
        }

        private fun JSONObject.toMeasure() = Measure(getInt("value"), getString("unit"))

        private fun JSONObject.toListing() = Listing(
            id = getString("id"),
            make = getString("make"),
            family = getString("family"),
            year = getInt("year"),
            model = getString("model"),
            color = getString("color"),
            priceUsd = getInt("price_usd"),
            mileage = getJSONObject("mileage").toMeasure(),
            distance = getJSONObject("distance").toMeasure(),
            location = getString("location"),
            accidents = getString("accidents"),
            status = getString("status"),
            seller = getString("seller"),
            note = getString("note"),
        )
    }
}
