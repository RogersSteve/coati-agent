package bot.coati.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskHooksTest {
    @Test
    fun `without installed hooks tasks verify, see the tree and record nothing`() {
        val defaults = object : TaskHooks {}
        assertTrue(defaults.verifyEnabled())
        assertFalse(defaults.treeWithheld())
    }
}
