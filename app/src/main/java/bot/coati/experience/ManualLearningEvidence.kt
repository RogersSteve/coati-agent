package bot.coati.experience

import android.graphics.Rect
import bot.coati.action.AgentAction
import bot.coati.model.ManualPageObservation
import bot.coati.service.UiTreeNode

internal enum class ManualObservationIgnoreReason {
    BLOCKED_HOST_APP,
    BLOCKED_PENDING_PRODUCT_POLICY,
    WECHAT_MINI_PROGRAM,
    NO_OBSERVATION,
    INVALID_STATUS,
    UNKNOWN_OBSERVATION,
    MISSING_PAGE_NAME,
    MISSING_STRUCTURE,
    NO_REUSABLE_SUPPORT,
    OBSERVATION_LIMIT,
}

internal sealed interface ManualObservationRecordResult {
    data class Accepted(
        val status: ManualObservationStatus,
        val duplicate: Boolean,
    ) : ManualObservationRecordResult

    data class Ignored(val reason: ManualObservationIgnoreReason) : ManualObservationRecordResult
}

internal sealed interface ManualObservationAdmission {
    data class Accepted(val observation: VerifiedPageObservation) : ManualObservationAdmission
    data class Ignored(val reason: ManualObservationIgnoreReason) : ManualObservationAdmission
}

/** Collects only bounded, structural observations from actions that normal Verify accepted. */
internal class ManualTraceCollector {
    private data class MutableTrace(
        val identity: ManualAppIdentity,
        val observations: MutableList<VerifiedPageObservation> = mutableListOf(),
    )

    private val sanitizer = ManualObservationSanitizer()
    private val traces = linkedMapOf<String, MutableTrace>()

    fun recordVerifiedSuccess(
        identity: ManualAppIdentity,
        raw: ManualPageObservation?,
        action: AgentAction,
        beforeTree: UiTreeNode?,
        afterTree: UiTreeNode?,
        knownHeadingPaths: Set<String>,
    ): ManualObservationRecordResult {
        secretFrom(action)?.let(sanitizer::addSensitiveLiteral)
        return record(
            identity,
            raw,
            actionType(action),
            targetFor(action, beforeTree),
            beforeTree,
            afterTree,
            knownHeadingPaths,
            false,
        )
    }

    /** Records the destination page seen by the independent AFTER-state verifier. */
    fun recordVerifiedDestination(
        identity: ManualAppIdentity,
        raw: ManualPageObservation?,
        action: AgentAction,
        beforeTree: UiTreeNode?,
        afterTree: UiTreeNode?,
        knownHeadingPaths: Set<String>,
    ): ManualObservationRecordResult {
        secretFrom(action)?.let(sanitizer::addSensitiveLiteral)
        ManualLearningPolicy.blockReason(identity.packageName, afterTree)?.let { reason ->
            val typedReason = runCatching { ManualObservationIgnoreReason.valueOf(reason) }
                .getOrDefault(ManualObservationIgnoreReason.BLOCKED_HOST_APP)
            return ManualObservationRecordResult.Ignored(typedReason)
        }
        return record(
            identity,
            raw,
            actionType(action),
            targetFor(action, beforeTree),
            beforeTree,
            afterTree,
            knownHeadingPaths,
            false,
            checkPolicy = false,
        )
    }

    fun recordCompletedPage(
        identity: ManualAppIdentity,
        raw: ManualPageObservation?,
        tree: UiTreeNode?,
        knownHeadingPaths: Set<String>,
    ): ManualObservationRecordResult = record(identity, raw, null, null, tree, tree, knownHeadingPaths, true)

    /** Current screen was already established by the preceding successful AFTER-state Verify. */
    fun recordVerifiedCurrentPage(
        identity: ManualAppIdentity,
        raw: ManualPageObservation?,
        tree: UiTreeNode?,
        knownHeadingPaths: Set<String>,
    ): ManualObservationRecordResult = record(identity, raw, null, null, tree, tree, knownHeadingPaths, false)

    /**
     * Records stable facts from the current screen seen by Reasoning. This is observation
     * evidence, not a claim that the preceding action or the whole task succeeded.
     */
    fun recordObservedCurrentPage(
        identity: ManualAppIdentity,
        raw: ManualPageObservation?,
        tree: UiTreeNode?,
        knownHeadingPaths: Set<String>,
        precedingAction: AgentAction? = null,
        beforeTree: UiTreeNode? = null,
    ): ManualObservationRecordResult {
        precedingAction?.let { secretFrom(it)?.let(sanitizer::addSensitiveLiteral) }
        ManualLearningPolicy.blockReason(identity.packageName, tree)?.let { reason ->
            val typedReason = runCatching { ManualObservationIgnoreReason.valueOf(reason) }
                .getOrDefault(ManualObservationIgnoreReason.BLOCKED_HOST_APP)
            return ManualObservationRecordResult.Ignored(typedReason)
        }
        return record(
            identity = identity,
            raw = raw,
            actionType = precedingAction?.let(::actionType),
            targetHint = precedingAction?.let { targetFor(it, beforeTree) },
            beforeTree = beforeTree ?: tree,
            afterTree = tree,
            knownHeadingPaths = knownHeadingPaths,
            terminal = false,
            checkPolicy = false,
        )
    }

    fun buildRequest(): ManualLearningRequest? {
        val selected = traces.values
            .asSequence()
            // Input can happen after an earlier observation. Recheck retained evidence with
            // all actual values before it leaves this task; a UI label never overrides input.
            .map { ManualAppLearningTrace(it.identity, it.observations.mapNotNull(sanitizer::redactRetained)) }
            .filter { trace -> trace.observations.any { it.status == ManualObservationStatus.NEW_OR_CHANGED } }
            .sortedByDescending { it.observations.count { observation -> observation.status == ManualObservationStatus.NEW_OR_CHANGED } }
            .take(AppManualPolicy.MAX_APPS_PER_TASK)
            .map { ManualAppLearningTrace(it.identity, it.observations.toList()) }
            .toList()
        return ManualLearningRequest(selected).takeIf { it.traces.isNotEmpty() }
    }

    private fun record(
        identity: ManualAppIdentity,
        raw: ManualPageObservation?,
        actionType: String?,
        targetHint: String?,
        beforeTree: UiTreeNode?,
        afterTree: UiTreeNode?,
        knownHeadingPaths: Set<String>,
        terminal: Boolean,
        checkPolicy: Boolean = true,
    ): ManualObservationRecordResult {
        if (checkPolicy) {
            ManualLearningPolicy.blockReason(identity.packageName, beforeTree)?.let { reason ->
                val typedReason = runCatching { ManualObservationIgnoreReason.valueOf(reason) }
                    .getOrDefault(ManualObservationIgnoreReason.BLOCKED_HOST_APP)
                return ManualObservationRecordResult.Ignored(typedReason)
            }
        }
        if (raw == null) return ManualObservationRecordResult.Ignored(ManualObservationIgnoreReason.NO_OBSERVATION)
        val admission = sanitizer.sanitize(
            raw = raw,
            actionType = actionType,
            targetHint = targetHint,
            beforeControls = summarizeControls(beforeTree),
            afterControls = summarizeControls(afterTree),
            knownHeadingPaths = knownHeadingPaths,
            terminal = terminal,
        )
        val observation = when (admission) {
            is ManualObservationAdmission.Accepted -> admission.observation
            is ManualObservationAdmission.Ignored -> return ManualObservationRecordResult.Ignored(admission.reason)
        }
        val trace = traces.getOrPut(identity.appKey) { MutableTrace(identity) }
        val duplicate = trace.observations.any {
            it.status == observation.status && it.pageName == observation.pageName &&
                it.visualSignature == observation.visualSignature && it.terminal == observation.terminal
        }
        if (!duplicate) {
            // The route that finally worked comes last in a task; a task that explored first used to
            // fill the budget with pages it only passed through and drop that route (board: a manual
            // without Settings -> Advanced -> Archive). Keep the latest pages instead.
            if (trace.observations.size >= AppManualPolicy.MAX_OBSERVATIONS_PER_APP) trace.observations.removeAt(0)
            trace.observations += observation
        }
        return ManualObservationRecordResult.Accepted(observation.status, duplicate)
    }

    private fun secretFrom(action: AgentAction): String? = when (action) {
        is AgentAction.InputText -> action.text
        is AgentAction.FocusAndInputText -> action.text
        else -> null
    }

    private fun actionType(action: AgentAction): String? = when (action) {
        is AgentAction.Tap -> "tap"
        is AgentAction.LongPress -> "long_press"
        is AgentAction.Swipe -> "swipe"
        is AgentAction.InputText -> "input_text"
        is AgentAction.FocusAndInputText -> "focus_and_input_text"
        is AgentAction.PressKey -> "press_key_${action.keyCode.lowercase()}"
        is AgentAction.Scroll -> "scroll_${action.direction.lowercase()}"
        is AgentAction.Wait, AgentAction.Screenshot, is AgentAction.OpenApp -> null
    }

    private fun targetFor(action: AgentAction, tree: UiTreeNode?): String? {
        val point = when (action) {
            is AgentAction.Tap -> action.x to action.y
            is AgentAction.LongPress -> action.x to action.y
            is AgentAction.FocusAndInputText -> action.x to action.y
            else -> null
        } ?: return null
        return flatten(tree)
            .filter { node ->
                point.first >= node.bounds.left && point.first < node.bounds.right &&
                    point.second >= node.bounds.top && point.second < node.bounds.bottom
            }
            .sortedBy { area(it.bounds) }
            .firstNotNullOfOrNull(::stableNodeLabel)
    }

    private fun summarizeControls(tree: UiTreeNode?): List<String> = flatten(tree)
        .asSequence()
        .filter { it.isClickable || it.isScrollable || it.isEditable }
        .mapNotNull(::stableNodeLabel)
        .distinct()
        .toList()

    private fun stableNodeLabel(node: UiTreeNode): String? {
        node.viewIdResourceName?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { return "id:$it" }
        node.hintText?.takeIf { it.isNotBlank() }?.let { return "hint:$it" }
        node.contentDescription?.takeIf { it.isNotBlank() }?.let { return "desc:$it" }
        if (!node.isEditable) node.text?.takeIf { it.isNotBlank() }?.let { return "text:$it" }
        return null
    }

    private fun flatten(root: UiTreeNode?): List<UiTreeNode> {
        if (root == null) return emptyList()
        return buildList {
            fun visit(node: UiTreeNode) {
                add(node)
                node.children.forEach(::visit)
            }
            visit(root)
        }
    }

    private fun area(rect: Rect): Long =
        (rect.right - rect.left).coerceAtLeast(0).toLong() * (rect.bottom - rect.top).coerceAtLeast(0).toLong()
}

internal class ManualObservationSanitizer {
    private val sensitiveLiterals = linkedSetOf<String>()

    fun addSensitiveLiteral(value: String) {
        value.trim().takeIf { it.length >= 2 }?.let(sensitiveLiterals::add)
    }

    fun sanitize(
        raw: ManualPageObservation,
        actionType: String?,
        targetHint: String?,
        beforeControls: List<String>,
        afterControls: List<String>,
        knownHeadingPaths: Set<String>,
        terminal: Boolean,
    ): ManualObservationAdmission {
        val requestedStatus = normalizeStatus(raw.status)
            ?: return ManualObservationAdmission.Ignored(ManualObservationIgnoreReason.INVALID_STATUS)
        if (requestedStatus == ManualObservationStatus.UNKNOWN) {
            return ManualObservationAdmission.Ignored(ManualObservationIgnoreReason.UNKNOWN_OBSERVATION)
        }
        // Heading paths are capability-like identifiers copied from the bounded prompt.
        // Validate them against that exact allow-list before any task-literal redaction;
        // otherwise a task that names the page could accidentally corrupt a valid path.
        val claimedMatched = raw.matchedHeadingPath
            ?.trim()
            ?.takeIf { it.length <= 180 && !it.contains(Regex("[\\p{Cc}<>]")) }
            ?.takeIf { it.startsWith("页面详情/") && it in knownHeadingPaths }
        val explicitPageName = clean(raw.pageName, 48, sensitiveLiterals)
        val matchedPageName = claimedMatched?.substringAfterLast('/')
        // A MATCHED claim is valid only when both identifiers name the same supplied page.
        // Otherwise the model has confused task progress with manual coverage; retain the evidence
        // as NEW_OR_CHANGED and let the normal support/privacy gates decide whether to admit it.
        val matched = claimedMatched?.takeIf {
            explicitPageName == null || explicitPageName == matchedPageName
        }
        // MATCHED is a capability claim: it means the page matched an exact manual heading
        // supplied in this request. A model can confuse it with a successful task target match.
        // Preserve its structured page evidence, but remove the invalid
        // capability claim and make it pass every NEW_OR_CHANGED support/privacy gate below.
        // This is deterministic and does not trust or synthesize a heading path.
        val status = if (requestedStatus == ManualObservationStatus.MATCHED && matched == null) {
            ManualObservationStatus.NEW_OR_CHANGED
        } else {
            requestedStatus
        }
        // A quoted task target is not evidence of sensitive content. In particular, page
        // names must not depend on OEM tree shape, traversal position or native TabWidgets.
        val literals = sensitiveLiterals
        val cleanedBefore = beforeControls.asSequence().mapNotNull { sanitizeControl(it, literals) }.distinct().take(8).toList()
        val cleanedAfter = afterControls.asSequence().mapNotNull { sanitizeControl(it, literals) }.distinct().take(8).toList()
        val pageName = explicitPageName
            ?: clean(matchedPageName, 48, sensitiveLiterals)
            ?: return ManualObservationAdmission.Ignored(ManualObservationIgnoreReason.MISSING_PAGE_NAME)
        val stableControls = (cleanedBefore + cleanedAfter)
            .asSequence()
            .distinct()
            .filterNot(::isOnlyDynamicControl)
            .take(6)
            .toList()
        val reusable = raw.reusableFacts.mapNotNull { clean(it, 180, literals) }.distinct().take(4)
        val signature = clean(raw.visualSignature, 180, literals)
            ?: controlSignature(stableControls)
            ?: reusable.firstOrNull()
            ?: matched?.let { "已匹配手册页面：$it" }
            ?: return ManualObservationAdmission.Ignored(ManualObservationIgnoreReason.MISSING_STRUCTURE)
        val navigation = raw.navigationFacts.mapNotNull { clean(it, 180, literals) }.distinct().take(3)
        val popup = clean(raw.popupOrRecoveryFact, 180, literals)
        if (status == ManualObservationStatus.NEW_OR_CHANGED && reusable.isEmpty() &&
            navigation.isEmpty() && popup == null && stableControls.size < 2
        ) {
            return ManualObservationAdmission.Ignored(ManualObservationIgnoreReason.NO_REUSABLE_SUPPORT)
        }
        return ManualObservationAdmission.Accepted(
            VerifiedPageObservation(
                status = status,
                pageName = pageName,
                matchedHeadingPath = matched,
                visualSignature = signature,
                reusableFacts = reusable,
                navigationFacts = navigation,
                popupOrRecoveryFact = popup,
                actionType = actionType,
                targetHint = sanitizeControl(targetHint, literals),
                beforeControls = cleanedBefore,
                afterControls = cleanedAfter,
                terminal = terminal,
            ),
        )
    }

    fun redactRetained(observation: VerifiedPageObservation): VerifiedPageObservation? {
        val pageName = clean(observation.pageName, 48, sensitiveLiterals) ?: return null
        val signature = clean(observation.visualSignature, 180, sensitiveLiterals)
            ?: controlSignature((observation.beforeControls + observation.afterControls)
                .mapNotNull { sanitizeControl(it, sensitiveLiterals) }.distinct())
            ?: observation.reusableFacts.firstNotNullOfOrNull { clean(it, 180, sensitiveLiterals) }
            ?: return null
        // A heading is an identifier; never rewrite it into a different capability.
        val matched = observation.matchedHeadingPath
        if (matched != null && clean(matched, 180, sensitiveLiterals) != matched) return null
        return observation.copy(
            pageName = pageName,
            visualSignature = signature,
            reusableFacts = observation.reusableFacts.mapNotNull { clean(it, 180, sensitiveLiterals) },
            navigationFacts = observation.navigationFacts.mapNotNull { clean(it, 180, sensitiveLiterals) },
            popupOrRecoveryFact = clean(observation.popupOrRecoveryFact, 180, sensitiveLiterals),
            targetHint = sanitizeControl(observation.targetHint, sensitiveLiterals),
            beforeControls = observation.beforeControls.mapNotNull { sanitizeControl(it, sensitiveLiterals) },
            afterControls = observation.afterControls.mapNotNull { sanitizeControl(it, sensitiveLiterals) },
        ).takeIf {
            it.status != ManualObservationStatus.NEW_OR_CHANGED || it.reusableFacts.isNotEmpty() ||
                it.navigationFacts.isNotEmpty() || it.popupOrRecoveryFact != null ||
                (it.beforeControls + it.afterControls).distinct().size >= 2
        }
    }

    private fun sanitizeControl(value: String?, literals: Collection<String>): String? = clean(value, 96, literals)?.takeIf { label ->
        !isOnlyDynamicControl(label) && label.count(Char::isDigit) < 5 &&
            label.split(Regex("\\s+")).size <= 8
    }

    private fun normalizeStatus(value: String): ManualObservationStatus? = when (
        value.trim().uppercase().replace('-', '_').replace(' ', '_')
    ) {
        "MATCH", "MATCHED" -> ManualObservationStatus.MATCHED
        "NEW", "CHANGED", "NEW_OR_CHANGED" -> ManualObservationStatus.NEW_OR_CHANGED
        "UNKNOWN", "UNCERTAIN" -> ManualObservationStatus.UNKNOWN
        else -> null
    }

    private fun controlSignature(controls: List<String>): String? = controls
        .takeIf { it.size >= 2 }
        ?.joinToString(prefix = "稳定控件：", separator = "；")
        ?.take(180)

    private fun isOnlyDynamicControl(value: String): Boolean {
        val payload = value.substringAfter(':', value).trim()
        return payload in setOf(DYNAMIC_VALUE, PRIVATE_VALUE, DYNAMIC_LINK)
    }

    private fun clean(value: String?, maxChars: Int, literals: Collection<String>): String? {
        var cleaned = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        cleaned = cleaned.replace(Regex("[\\p{Cc}<>]"), " ").replace(Regex("\\s+"), " ").trim()
        // Omit the entire contaminated field, not just its value: replacing a substring
        // can manufacture a different page/control and overwrite correct existing facts.
        // Model-authored placeholders are also ambiguous evidence, not fixed UI names.
        // This applies to new observations only, not existing human-authored manuals.
        if (ManualSemanticGuard.hasPlaceholder(cleaned) || literals.any { cleaned.contains(it, ignoreCase = true) } ||
            SENSITIVE_PATTERNS.any { it.containsMatchIn(cleaned) }) return null
        if (cleaned in setOf(DYNAMIC_VALUE, PRIVATE_VALUE, DYNAMIC_LINK)) return null
        return cleaned.take(maxChars).trim().takeIf { it.isNotEmpty() }
    }

    companion object {
        private const val DYNAMIC_VALUE = "动态值"
        private const val PRIVATE_VALUE = "隐私值"
        private const val DYNAMIC_LINK = "动态链接"
        private val SENSITIVE_PATTERNS = listOf(
            Regex("https?://\\S+|www\\.\\S+", RegexOption.IGNORE_CASE),
            Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"),
            Regex("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)"),
            Regex("(?<!\\d)\\d{5,}(?!\\d)"),
        )
    }
}

object ManualLearningPolicy {
    private val exactBlocked = setOf(
        "bot.coati",
        "com.android.systemui",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
    )
    private val technicalHostFragments = listOf("launcher", "inputmethod", "keyboard")
    private val pendingProductDecisionFragments = listOf("bank", "wallet", "payment", "authenticator")

    fun blockReason(packageName: String, tree: UiTreeNode?): String? {
        if (packageName == "unknown" || packageName in exactBlocked) return "BLOCKED_HOST_APP"
        if (technicalHostFragments.any { packageName.contains(it, ignoreCase = true) }) return "BLOCKED_HOST_APP"
        if (pendingProductDecisionFragments.any { packageName.contains(it, ignoreCase = true) }) {
            return "BLOCKED_PENDING_PRODUCT_POLICY"
        }
        if (isWeChatMiniProgram(packageName, tree)) return "WECHAT_MINI_PROGRAM"
        return null
    }

    internal fun isWeChatMiniProgram(packageName: String, tree: UiTreeNode?): Boolean {
        if (packageName != "com.tencent.mm" || tree == null) return false
        val nodes = buildList {
            fun visit(node: UiTreeNode) {
                add(node)
                node.children.forEach(::visit)
            }
            visit(tree)
        }
        val structural = nodes.any { node ->
            node.className.orEmpty().contains(Regex("appbrand|app_brand|wxa", RegexOption.IGNORE_CASE)) ||
                node.viewIdResourceName.orEmpty().contains(Regex("appbrand|app_brand|mini_program|wxa", RegexOption.IGNORE_CASE))
        }
        val semantic = nodes.any { node ->
            listOf(node.text, node.contentDescription, node.hintText).any {
                it.orEmpty().contains(Regex("小程序|mini program", RegexOption.IGNORE_CASE))
            }
        }
        val rootBounds = tree.bounds
        val rootHeight = (rootBounds.bottom - rootBounds.top).coerceAtLeast(0)
        val rootWidth = (rootBounds.right - rootBounds.left).coerceAtLeast(0)
        val topLimit = rootBounds.top + (rootHeight * 0.25).toInt()
        val rightLimit = rootBounds.left + (rootWidth * 0.55).toInt()
        val capsule = nodes.any { node ->
            node.isClickable && node.bounds.top <= topLimit && node.bounds.left >= rightLimit &&
                listOf(node.text, node.contentDescription).any {
                    it.orEmpty().contains(Regex("更多|关闭|more|close", RegexOption.IGNORE_CASE))
                }
        }
        return (structural && (semantic || capsule)) || (semantic && capsule)
    }
}
