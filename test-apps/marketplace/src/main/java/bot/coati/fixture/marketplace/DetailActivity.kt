package bot.coati.fixture.marketplace

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * One listing: title, price and every decisive field as a "Label: value" line. The fields fit on
 * one phone screen above the seller notes. "Back" in the top bar and system Back both return to
 * the list at its scroll position.
 */
class DetailActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val catalog = MarketCatalog.get(this)
        val listing = catalog.byId(intent.getStringExtra(EXTRA_ID)) ?: return finish()
        val pad = dp(16)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, pad)
            addView(TextView(context).apply {
                text = listing.detailTitle
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(INK)
            })
            addView(TextView(context).apply {
                text = listing.priceLabel
                textSize = 20f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ACCENT)
                setPadding(0, dp(4), 0, dp(12))
            })
            addView(photoPlaceholder(context).apply {
                (background as GradientDrawable).setColor(swatch(listing.color))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(120)))
            addView(section("Vehicle details"))
            listing.detailLines(catalog.referenceCity).forEach { line ->
                addView(TextView(context).apply {
                    text = line
                    textSize = 16f
                    setTextColor(INK)
                    setPadding(0, dp(5), 0, dp(5))
                })
            }
            addView(section("Seller notes"))
            addView(TextView(context).apply {
                text = listing.note
                textSize = 15f
                setTextColor(MUTED)
            })
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(Button(context).apply {
                text = "Back"
                isAllCaps = false
                setOnClickListener {
                    Log.i(MarketSession.TAG, "back button id=${listing.id}")
                    finish()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dp(8), dp(4), 0, 0)
            })
            addView(ScrollView(context).apply { addView(page) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            padForSystemBars()
        })
        // Count only real openings, not recreation after a configuration change.
        if (savedInstanceState == null && MarketSession.recordDetailView(this, listing.id)) showPromotion()
    }

    private fun section(title: String) = TextView(this).apply {
        text = title
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(MUTED)
        setPadding(0, dp(16), 0, dp(4))
    }

    /** The one-time financing promotion (launch with `--ez promo true`); either button or Back closes it. */
    private fun showPromotion() {
        Log.i(MarketSession.TAG, "promo shown")
        AlertDialog.Builder(this)
            .setTitle("Special financing")
            .setMessage("Get pre-approved for 2.9% APR on this car in two minutes.")
            .setNegativeButton("No thanks") { _, _ -> Log.i(MarketSession.TAG, "promo dismissed via no_thanks") }
            .setPositiveButton("Check my rate") { _, _ -> Log.i(MarketSession.TAG, "promo dismissed via check_rate") }
            .setOnCancelListener { Log.i(MarketSession.TAG, "promo dismissed via back") }
            .show()
    }

    companion object {
        private const val EXTRA_ID = "listing_id"

        fun intent(context: Context, id: String): Intent =
            Intent(context, DetailActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
