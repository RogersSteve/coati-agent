package bot.coati.fixture.marketplace

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.Locale

/**
 * The listing page: every car as a row with its make and family, color and price, plus a photo
 * placeholder. Year, model, mileage and distance are on the detail page only, so a search must
 * open each candidate. The list is a ListView, so only rows on screen are in the accessibility tree.
 *
 * adb shell am start -S -n bot.coati.fixture.marketplace/.MainActivity [--ez reset true] [--ez promo true]
 *     [--ez repeat true]
 *
 * With `repeat` the page switches to repeat mode (see [RepeatConfig]); the default page is unchanged.
 */
class MainActivity : Activity() {
    private lateinit var list: ListView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MarketSession.applyLaunchExtras(this, intent)
        val catalog = MarketCatalog.get(this)
        if (MarketSession.isRepeatMode(this)) {
            showRepeatMode(catalog)
            return
        }
        list = ListView(this).apply {
            adapter = ListingAdapter(catalog.listings)
            setOnItemClickListener { _, _, position, _ ->
                startActivity(DetailActivity.intent(this@MainActivity, catalog.listings[position].id))
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(TextView(context).apply {
                text = getString(R.string.app_name)
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(INK)
                setPadding(dp(16), dp(16), dp(16), 0)
            })
            addView(TextView(context).apply {
                text = "${catalog.listings.size} used cars for sale"
                textSize = 14f
                setTextColor(MUTED)
                setPadding(dp(16), dp(2), dp(16), dp(8))
            })
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            padForSystemBars()
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (MarketSession.applyLaunchExtras(this, intent)) list.setSelection(0)
    }

    override fun onResume() {
        super.onResume()
        // The detail page can change favorites and the compare list.
        repeatAdapter?.reload()
    }

    private var repeatAdapter: RepeatAdapter? = null

    /** Repeat mode: listing IDs on the rows, a Sold badge, per-row actions and a Refresh button. */
    private fun showRepeatMode(catalog: MarketCatalog) {
        val config = RepeatConfig.get(this)
        val status = TextView(this).apply {
            textSize = 14f
            setTextColor(MUTED)
            setPadding(dp(16), dp(2), dp(16), dp(8))
        }
        val adapter = RepeatAdapter(this, catalog.listings, config) { state ->
            status.text = "Favorites: ${state.favorites.size} · Compare: ${state.compare.size} · " +
                "Refreshed ${state.refreshes} times"
        }
        repeatAdapter = adapter
        list = ListView(this).apply {
            this.adapter = adapter
            setOnItemClickListener { _, _, position, _ ->
                startActivity(DetailActivity.intent(this@MainActivity, adapter.getItem(position).id))
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(16), dp(8), 0)
                addView(TextView(context).apply {
                    text = getString(R.string.app_name)
                    textSize = 22f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(INK)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(context).apply {
                    text = "Refresh"
                    isAllCaps = false
                    setOnClickListener {
                        MarketSession.refresh(this@MainActivity)
                        adapter.reload()
                    }
                })
            })
            addView(status)
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            padForSystemBars()
        })
        adapter.reload()
    }

    private class RepeatAdapter(
        private val activity: Activity,
        private val all: List<Listing>,
        private val config: RepeatConfig,
        private val onState: (RepeatState) -> Unit,
    ) : BaseAdapter() {
        private var state = RepeatState()
        private var items = all

        fun reload() {
            state = MarketSession.repeatState(activity)
            items = state.visible(all)
            onState(state)
            notifyDataSetChanged()
        }

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = items[position]
            val context = parent.context
            val sold = config.isSold(item.id)
            val price = String.format(Locale.US, "$%,d", config.priceUsd(item, state.refreshes))
            return LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(16), context.dp(10), context.dp(16), context.dp(6))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(photoPlaceholder(context).apply {
                        (background as GradientDrawable).setColor(swatch(item.color))
                    }, LinearLayout.LayoutParams(context.dp(72), context.dp(52)))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(context.dp(14), 0, 0, 0)
                        addView(TextView(context).apply {
                            text = item.listTitle
                            textSize = 16f
                            setTextColor(INK)
                        })
                        addView(TextView(context).apply {
                            text = "${item.id} · ${item.color}"
                            textSize = 13f
                            setTextColor(MUTED)
                        })
                        addView(TextView(context).apply {
                            text = if (sold) "$price · SOLD" else price
                            textSize = 16f
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(if (sold) SOLD else ACCENT)
                        })
                    })
                })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(action(context, if (item.id in state.favorites) "Favorited" else "Favorite",
                        "Favorite ${item.id}") { MarketSession.toggleFavorite(activity, item.id); reload() })
                    addView(action(context, if (item.id in state.compare) "In compare" else "Compare",
                        "Compare ${item.id}") { MarketSession.toggleCompare(activity, item.id); reload() })
                    addView(action(context, "Hide", "Hide ${item.id}") { MarketSession.hide(activity, item.id); reload() })
                })
            }
        }

        /** A row button; not focusable, so tapping the rest of the row still opens the listing. */
        private fun action(context: Context, label: String, description: String, onClick: () -> Unit) =
            Button(context).apply {
                text = label
                isAllCaps = false
                isFocusable = false
                contentDescription = "$description ($label)"
                setOnClickListener { onClick() }
            }
    }

    private class ListingAdapter(private val items: List<Listing>) : BaseAdapter() {
        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = (convertView as? LinearLayout) ?: newRow(parent.context)
            val item = items[position]
            (row.getChildAt(0).background as GradientDrawable).setColor(swatch(item.color))
            val texts = row.getChildAt(1) as LinearLayout
            (texts.getChildAt(0) as TextView).text = item.listTitle
            (texts.getChildAt(1) as TextView).text = "${item.color} · Automatic"
            (texts.getChildAt(2) as TextView).text = item.priceLabel
            return row
        }

        private fun newRow(context: Context) = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(16), context.dp(12), context.dp(16), context.dp(12))
            addView(photoPlaceholder(context), LinearLayout.LayoutParams(context.dp(88), context.dp(64)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(14), 0, 0, 0)
                addView(TextView(context).apply {
                    textSize = 16f
                    setTextColor(INK)
                })
                addView(TextView(context).apply {
                    textSize = 13f
                    setTextColor(MUTED)
                })
                addView(TextView(context).apply {
                    textSize = 16f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(ACCENT)
                })
            })
        }
    }
}

internal val INK = Color.rgb(0x1B, 0x1F, 0x24)
internal val MUTED = Color.rgb(0x5F, 0x66, 0x70)
internal val ACCENT = Color.rgb(0x1F, 0x6F, 0x5C)
internal val SOLD = Color.rgb(0xB0, 0x4A, 0x45)

internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()

/** A tinted box standing in for the car photo; it has no text and is hidden from accessibility. */
internal fun photoPlaceholder(context: Context) = View(context).apply {
    background = GradientDrawable().apply { cornerRadius = context.dp(6).toFloat() }
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

/** Placeholder tint for a paint color; unknown names get a neutral grey. */
internal fun swatch(color: String): Int = when {
    "Black" in color || "Carbon" in color || "Caviar" in color -> Color.rgb(0x3A, 0x3D, 0x42)
    "White" in color -> Color.rgb(0xE4, 0xE6, 0xE9)
    "Blue" in color -> Color.rgb(0x5B, 0x76, 0x9C)
    "Red" in color -> Color.rgb(0xB0, 0x4A, 0x45)
    else -> Color.rgb(0xA4, 0xA8, 0xAE)
}

/** targetSdk 35 draws edge to edge on Android 15; keep content clear of the status and navigation bars. */
internal fun View.padForSystemBars() {
    val base = intArrayOf(paddingLeft, paddingTop, paddingRight, paddingBottom)
    setOnApplyWindowInsetsListener { v, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars())
        v.setPadding(base[0] + bars.left, base[1] + bars.top, base[2] + bars.right, base[3] + bars.bottom)
        insets
    }
}
