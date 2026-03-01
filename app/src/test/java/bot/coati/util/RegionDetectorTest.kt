package bot.coati.util

import android.content.Context
import android.telephony.TelephonyManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

class RegionDetectorTest {

    private val context = mockk<Context>(relaxed = true)
    private val telephonyManager = mockk<TelephonyManager>(relaxed = true)

    @Before
    fun setup() {
        every { context.getSystemService(Context.TELEPHONY_SERVICE) } returns telephonyManager
    }

    @After
    fun tearDown() {
        // Restore default locale
    }

    @Test
    fun `detects CN from SIM card`() {
        every { telephonyManager.simCountryIso } returns "cn"
        assertEquals("CN", RegionDetector.detectRegion(context))
    }

    @Test
    fun `detects JP from SIM card`() {
        every { telephonyManager.simCountryIso } returns "jp"
        assertEquals("JP", RegionDetector.detectRegion(context))
    }

    @Test
    fun `detects global from US SIM card`() {
        every { telephonyManager.simCountryIso } returns "us"
        assertEquals("global", RegionDetector.detectRegion(context))
    }

    @Test
    fun `detects global from GB SIM card`() {
        every { telephonyManager.simCountryIso } returns "gb"
        assertEquals("global", RegionDetector.detectRegion(context))
    }

    @Test
    fun `falls back to global when no SIM and non-CJK language`() {
        every { telephonyManager.simCountryIso } returns ""
        mockkStatic(Locale::class)
        every { Locale.getDefault() } returns Locale.ENGLISH
        assertEquals("global", RegionDetector.detectRegion(context))
        unmockkStatic(Locale::class)
    }

    @Test
    fun `regionDisplayName returns correct names`() {
        assertEquals("China", RegionDetector.regionDisplayName("CN"))
        assertEquals("Japan", RegionDetector.regionDisplayName("JP"))
        assertEquals("Global", RegionDetector.regionDisplayName("global"))
        assertEquals("Global", RegionDetector.regionDisplayName("unknown"))
    }
}
