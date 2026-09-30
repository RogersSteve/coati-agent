package bot.coati.fixture.interrupt

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * An interrupting dialog for robustness runs: a promotion that covers the app in front, the kind
 * of pop-up a real device shows mid-task. Close it with "Not now" (or Back).
 *
 * adb shell am start -n bot.coati.fixture.interrupt/.PopupActivity [--ei dismissals N]
 *
 * `dismissals` (default 1) is how many closing attempts it takes: each one before the last keeps
 * the dialog and changes its text, so a large value gives a dialog that cannot be dismissed.
 * Every event is logged under the tag CoatiInterrupt for the benchmark log.
 */
class PopupActivity : Activity() {
    private var remaining = 1
    private lateinit var body: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        remaining = (savedInstanceState?.getInt(KEY_REMAINING) ?: intent.getIntExtra(EXTRA_DISMISSALS, 1)).coerceAtLeast(1)
        setFinishOnTouchOutside(false)
        val pad = (24 * resources.displayMetrics.density).toInt()
        body = TextView(this).apply {
            text = "Get 3 months of Premium free. Offer ends today."
            textSize = 16f
        }
        val later = Button(this).apply {
            text = "Not now"
            setOnClickListener { dismissAttempt("not_now") }
        }
        val accept = Button(this).apply {
            text = "Claim offer"
            setOnClickListener { Log.i(TAG, "claim pressed") }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
            addView(TextView(context).apply {
                text = "Special offer"
                textSize = 20f
                setPadding(0, 0, 0, pad / 2)
            })
            addView(body)
            addView(LinearLayout(context).apply {
                gravity = Gravity.END
                setPadding(0, pad / 2, 0, 0)
                addView(later)
                addView(accept)
            })
        })
        Log.i(TAG, "shown dismissals=$remaining")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_REMAINING, remaining)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = dismissAttempt("back")

    private fun dismissAttempt(source: String) {
        remaining--
        if (remaining <= 0) {
            Log.i(TAG, "dismissed via $source")
            finish()
        } else {
            Log.i(TAG, "dismiss via $source refused, $remaining left")
            body.text = "Are you sure? Premium is free for 3 months only today."
        }
    }

    companion object {
        private const val TAG = "CoatiInterrupt"
        private const val EXTRA_DISMISSALS = "dismissals"
        private const val KEY_REMAINING = "remaining"
    }
}
