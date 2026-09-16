package bot.coati.model

class ModelRouter(
    val reasoning: CloudModelGateway,
    val fast: CloudModelGateway,
) {
    /** Persistent cross-task knowledge requires schema fidelity, not step latency. */
    val knowledgeUpdater: CloudModelGateway
        get() = reasoning
}
