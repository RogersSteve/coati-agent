package bot.coati.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Broadcast control avoids Activity relaunch/pause and cannot dispatch a new gesture. */
class CoordinateFixtureControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val accepted = intent.getStringExtra("op") == "validate-stale" &&
            CoordinateFixtureActivity.validate(intent.getStringExtra("run_id"), intent.getStringExtra("instance_id"))
        if (isOrderedBroadcast) setResult(if(accepted) 0 else 1, if(accepted) "accepted" else "rejected", null)
    }
}
