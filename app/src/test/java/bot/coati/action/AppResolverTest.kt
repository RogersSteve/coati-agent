package bot.coati.action

import bot.coati.action.AppResolver.LaunchableApp
import bot.coati.action.AppResolver.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppResolverTest {

    private val apps = listOf(
        LaunchableApp("Settings", "com.android.settings", "SettingsActivity"),
        LaunchableApp("Calendar", "com.coloros.calendar", "CalendarActivity"),
        LaunchableApp("Hema", "com.wudaokou.hippo", "HomeActivity"),
        LaunchableApp("Gaode Map", "com.autonavi.minimap", "MapActivity"),
        LaunchableApp("System Notes", "com.coloros.note", "NotesActivity"),
    )

    @Test
    fun `resolve matches exact app label`() {
        val result = AppResolver.resolve("Calendar", apps)

        assertTrue(result is Result.Resolved)
        assertEquals("com.coloros.calendar", (result as Result.Resolved).app.packageName)
    }

    @Test
    fun `resolve matches exact package name`() {
        val result = AppResolver.resolve("com.wudaokou.hippo", apps)

        assertTrue(result is Result.Resolved)
        assertEquals("Hema", (result as Result.Resolved).app.label)
    }

    @Test
    fun `resolve ignores case and whitespace`() {
        val result = AppResolver.resolve(" gaode map ", apps)

        assertTrue(result is Result.Resolved)
        assertEquals("com.autonavi.minimap", (result as Result.Resolved).app.packageName)
    }

    @Test
    fun `resolve matches app name contained in label`() {
        val result = AppResolver.resolve("Notes", apps)

        assertTrue(result is Result.Resolved)
        assertEquals("com.coloros.note", (result as Result.Resolved).app.packageName)
    }

    @Test
    fun `resolve returns ambiguous candidates for duplicate labels`() {
        val result = AppResolver.resolve(
            "Calendar",
            apps + LaunchableApp("Calendar", "com.google.android.calendar", "CalendarActivity"),
        )

        assertTrue(result is Result.Ambiguous)
        assertEquals(2, (result as Result.Ambiguous).candidates.size)
    }

    @Test
    fun `resolve returns not found for missing app`() {
        val result = AppResolver.resolve("Missing App", apps)

        assertTrue(result is Result.NotFound)
    }

    @Test
    fun `looksLikePackageName accepts package-style values`() {
        assertTrue(AppResolver.looksLikePackageName("com.android.settings"))
        assertTrue(AppResolver.looksLikePackageName("com.wudaokou.hippo"))
    }

    @Test
    fun `looksLikePackageName rejects visible app names`() {
        assertFalse(AppResolver.looksLikePackageName("Calendar"))
        assertFalse(AppResolver.looksLikePackageName("Hema App"))
    }

    @Test
    fun `a tie between same-label apps goes to the one covering the leftover query words`() {
        val calendars = listOf(
            LaunchableApp("Calendar", "com.google.android.calendar", "LaunchActivity"),
            LaunchableApp("Calendar", "com.simplemobiletools.calendar.pro", "SplashActivity"),
        )
        val result = AppResolver.resolve("Simple Calendar Pro", calendars)
        assertTrue(result is Result.Resolved)
        assertEquals("com.simplemobiletools.calendar.pro", (result as Result.Resolved).app.packageName)

        // Nothing beyond the shared label: still ambiguous, never a guess.
        assertTrue(AppResolver.resolve("Calendar", calendars) is Result.Ambiguous)
        // Both cover the leftover word equally: still ambiguous.
        val twins = listOf(
            LaunchableApp("Notes", "com.alpha.pro.notes", "A"),
            LaunchableApp("Notes", "com.beta.pro.notes", "B"),
        )
        assertTrue(AppResolver.resolve("Pro Notes", twins) is Result.Ambiguous)
    }
}
