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
        val summarizationPrompt = "Summarize the following agent conversation history into a concise summary. " +
            "Focus on: what actions were taken, what was accomplished, what remains to do, and any errors encountered.\n\n$historyText"

        val result = reasoningGateway.analyzeScreen(
            screenshot = DUMMY_SCREENSHOT,
            uiTreeHint = historyText,
            taskDescription = summarizationPrompt,
            conversationHistory = emptyList(),
        )

        val summary = result.getOrNull()?.reasoning ?: return

        val lastEntries = conversationHistory.takeLast(3)
        conversationHistory.clear()
        conversationHistory.add(Message("assistant", "[History Summary] $summary"))
        conversationHistory.addAll(lastEntries)

        stateManager.writeMarkdown("history_$taskId", summary)
    }

    fun pruneScreenshotCache(cache: MutableList<ByteArray>, keep: Int = 3) {
        while (cache.size > keep) {
            cache.removeAt(0)
        }
    }

    companion object {
        private val DUMMY_SCREENSHOT = ByteArray(4) { 0xFF.toByte() }
    }
}
