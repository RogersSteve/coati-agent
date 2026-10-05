package bot.coati.service

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeScreensTest {
    @Test
    fun `the boot-time fallback home of Settings is not a home screen`() {
        val homes = HomeScreens.realHomes(
            listOf(
                "com.google.android.apps.nexuslauncher" to 0,
                "com.android.settings" to -1000,
                "com.example.launcher" to 1,
            ),
        )
        assertEquals(setOf("com.google.android.apps.nexuslauncher", "com.example.launcher"), homes)
    }
}
