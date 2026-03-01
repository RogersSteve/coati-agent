package bot.coati.util

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

object RegionDetector {

    fun detectRegion(context: Context): String {
        // Priority 1: SIM card country
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val sim = tm?.simCountryIso?.uppercase()
        if (!sim.isNullOrBlank()) {
            if (sim == "CN") return "CN"
            if (sim == "JP") return "JP"
            if (sim in listOf("US", "CA", "GB", "AU")) return "global"
        }

        // Priority 2: System language
        val lang = Locale.getDefault().language
        if (lang == "zh") return "CN"
        if (lang == "ja") return "JP"

        return "global"
    }

    fun regionDisplayName(region: String): String {
        return when (region) {
            "CN" -> "China"
            "JP" -> "Japan"
            "global" -> "Global"
            else -> "Global"
        }
    }
}
