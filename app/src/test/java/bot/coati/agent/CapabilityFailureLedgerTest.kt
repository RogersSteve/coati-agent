package bot.coati.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityFailureLedgerTest {

    @Test
    fun `same capability failure survives unrelated successful steps`() {
        val ledger = CapabilityFailureLedger()

        assertEquals(
            CapabilityFailureDecision.RETRY,
            ledger.recordFailure("direct-input", "Do not retry direct input"),
        )
        // No recordSuccess API exists by design: an unrelated tap/clear cannot erase this evidence.
        assertEquals(
            CapabilityFailureDecision.REPLAN,
            ledger.recordFailure("direct-input", "Do not retry direct input"),
        )
        assertEquals(2, ledger.count("direct-input"))
        assertTrue(ledger.persistentConstraints().contains("Do not retry direct input"))
    }

    @Test
    fun `third matching capability failure aborts`() {
        val ledger = CapabilityFailureLedger()

        ledger.recordFailure("document-precondition", "Document mode is blocked")
        ledger.recordFailure("document-precondition", "Document mode is blocked")

        assertEquals(
            CapabilityFailureDecision.ABORT,
            ledger.recordFailure("document-precondition", "Document mode is blocked"),
        )
    }

    @Test
    fun `different failure keys do not share counts`() {
        val ledger = CapabilityFailureLedger()

        ledger.recordFailure("document", "Document blocked")
        assertEquals(
            CapabilityFailureDecision.RETRY,
            ledger.recordFailure("append", "Append blocked"),
        )
    }

    @Test
    fun `changing editor scope clears counts and constraints`() {
        val ledger = CapabilityFailureLedger()
        ledger.activateScope("notes:editor:session=1")
        ledger.recordFailure("focus", "Refocus")
        ledger.recordFailure("focus", "Refocus")

        ledger.activateScope("notes:editor:session=2")

        assertEquals(0, ledger.count("focus"))
        assertTrue(ledger.persistentConstraints().isEmpty())
        assertEquals(
            CapabilityFailureDecision.RETRY,
            ledger.recordFailure("focus", "Refocus"),
        )
    }

    @Test
    fun `same editor scope preserves repeated failure evidence`() {
        val ledger = CapabilityFailureLedger()
        ledger.activateScope("notes:editor:session=1")
        ledger.recordFailure("focus", "Refocus")

        ledger.activateScope("notes:editor:session=1")

        assertEquals(
            CapabilityFailureDecision.REPLAN,
            ledger.recordFailure("focus", "Refocus"),
        )
    }
}
