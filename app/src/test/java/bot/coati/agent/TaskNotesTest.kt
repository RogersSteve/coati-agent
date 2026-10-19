package bot.coati.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskNotesTest {

    @Test
    fun `entries are kept verbatim and an entry with the same label replaces the earlier one`() {
        val notes = TaskNotes()
        assertEquals(2, notes.merge(listOf("Title: Pasta Carbonara", "Tasks due Monday: 2")))
        notes.merge(listOf("tasks due monday: 3", "Servings: 4"))
        assertEquals(listOf("Title: Pasta Carbonara", "tasks due monday: 3", "Servings: 4"), notes.entries())
        val rendered = notes.render()!!
        assertTrue(rendered.startsWith("[NOTES]"))
        assertTrue(rendered.contains("\n- Title: Pasta Carbonara"))
    }

    @Test
    fun `blank updates change nothing and empty notes render nothing`() {
        val notes = TaskNotes()
        assertEquals(0, notes.merge(listOf("", "  ")))
        assertNull(notes.render())
        assertTrue(notes.isEmpty)
    }

    @Test
    fun `the oldest entries go first when the bounds are exceeded`() {
        val notes = TaskNotes(maxEntries = 3, maxChars = 1_000)
        notes.merge((1..5).map { "Item $it: value $it" })
        assertEquals(listOf("Item 3: value 3", "Item 4: value 4", "Item 5: value 5"), notes.entries())

        val small = TaskNotes(maxEntries = 10, maxChars = 30)
        small.merge(listOf("A: 1234567890", "B: 1234567890", "C: 1234567890"))
        assertEquals(listOf("B: 1234567890", "C: 1234567890"), small.entries())
    }

    @Test
    fun `an entry without a label is deduplicated by its whole text`() {
        val notes = TaskNotes()
        notes.merge(listOf("Morning run 5 km", "Morning run 5 km"))
        assertEquals(listOf("Morning run 5 km"), notes.entries())
        assertEquals("title", TaskNotes.labelOf(" Title : x"))
    }
}
