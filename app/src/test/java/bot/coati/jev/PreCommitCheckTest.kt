package bot.coati.jev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreCommitCheckTest {

    @Test
    fun `commit controls are recognized in English and Chinese`() {
        listOf("Submit order", "Save canvas", "Export as SVG", "Send", "Place order", "提交订单", "保存").forEach {
            assertTrue(it, PreCommitCheck.isCommit(it, "button"))
        }
        assertTrue(PreCommitCheck.isCommit("Submit order", "item"))
    }

    @Test
    fun `ordinary controls are not commits`() {
        listOf("+", "Priority delivery", "Brush", "Blue", "Recipient", "Home emergency checklist", "Saved: red").forEach {
            assertFalse(it, PreCommitCheck.isCommit(it, "button"))
        }
        // Board: the Export tab of the Canvas Workbench only switches pages.
        assertFalse(PreCommitCheck.isCommit("Export", "tab"))
        assertFalse(PreCommitCheck.isCommit("Save battery", "switch"))
    }

    @Test
    fun `verdicts parse with and without a missing value`() {
        assertEquals(
            PreCommitCheck.Verdict(false, "Copies is 2, the task asks for 3"),
            PreCommitCheck.parse("""{"ready":false,"missing":"Copies is 2, the task asks for 3"}"""),
        )
        assertEquals(PreCommitCheck.Verdict(true, null), PreCommitCheck.parse("""```json
            {"ready":true,"missing":null}
            ```"""))
        assertNull(PreCommitCheck.parse("not json"))
    }
}
