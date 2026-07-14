package bot.coati.agent

import bot.coati.model.CloudModelGateway
import bot.coati.model.Message
import bot.coati.state.StateManager

class Autocompact(private val compactInterval: Int = 15) {

    fun shouldCompact(stepCount: Int): Boolean {
        return stepCount > 0 && stepCount % compactInterval == 0
    }

    suspend fun compact(
        reasoningGateway: CloudModelGateway,
        conversationHistory: MutableList<Message>,
        stateManager: StateManager,
        taskId: String,
    ) {
        if (conversationHistory.size < 6) return

        val historyText = conversationHistory.joinToString("\n") { "[${it.role}] ${it.content}" }
        val summarizationPrompt = """
            Summarize the following Android agent conversation history into a compact handoff note.

            Focus on:
            - user goal and current sub-goal
            - actions already taken
            - visible progress accomplished
            - errors, failed attempts, drifted states, or unknown verification results
            - what remains to do next

            Do not invent screen details that are not present in the text.
            Return concise plain text only.

            Conversation history:
            $historyText
        """.trimIndent()

        val summary = reasoningGateway.summarizeText(summarizationPrompt).getOrNull() ?: return

        val lastEntries = conversationHistory.takeLast(3)
        conversationHistory.clear()
        conversationHistory.add(Message("user", "[History Summary]\n$summary"))
        conversationHistory.addAll(lastEntries)

        stateManager.writeMarkdown("history_$taskId", summary)
    }

    fun pruneScreenshotCache(cache: MutableList<ByteArray>, keep: Int = 3) {
        while (cache.size > keep) {
            cache.removeAt(0)
        }
    }

}
