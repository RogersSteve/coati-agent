package bot.coati.experience

object AppManualPolicy {
    const val SCHEMA_VERSION = 2
    const val MAX_STORED_CHARS = 80_000
    const val MAX_STORED_ESTIMATED_TOKENS = 20_000
    const val MAX_RENDERED_CHARS = 8_000
    const val MAX_RENDERED_ESTIMATED_TOKENS = 2_000
    const val MAX_HEADINGS = 160
    const val MAX_HEADING_CHARS = 80
    const val MAX_REVISIONS = 3
    const val MAX_OBSERVATIONS_PER_APP = 10
    const val MAX_APPS_PER_TASK = 2
    const val MAX_PARTIAL_EDITS = 4
    const val MAX_EDIT_OLD_CHARS = 2_000
    const val MAX_EDIT_NEW_CHARS = 4_000
    const val MAX_CURATION_INPUT_ESTIMATED_TOKENS = 6_000
    const val MAX_CURATION_OUTPUT_TOKENS = 2_000
    const val MAX_SUMMARY_OUTPUT_TOKENS = 12_000
    const val SUMMARIZE_TRIGGER_ESTIMATED_TOKENS = 16_000
    const val UPDATE_TOTAL_TIMEOUT_MS = 120_000L
    const val CURATOR_PRIMARY_TIMEOUT_MS = 90_000L
    const val MAX_UPDATE_ATTEMPTS = 2

    val REQUIRED_H2 = linkedSetOf(
        "App 信息",
        "App 用途",
        "页面索引",
        "页面详情",
        "页面导航",
        "弹窗与恢复",
    )

    private val forbiddenMarkdown = listOf(
        Regex("```"),
        Regex("<[^>]+>"),
        Regex("(?i)ignore (all |any )?(previous|prior|system|developer)"),
        Regex("(?i)(system|developer|assistant|user)\\s*prompt"),
        Regex("忽略.{0,12}(指令|提示词|规则)"),
        Regex("(系统|开发者|用户|助手)\\s*(消息|提示词|指令)"),
    )

    fun normalizeMarkdown(markdown: String): String {
        return markdown.replace("\r\n", "\n").replace('\r', '\n').trim() + "\n"
    }

    fun validateManual(manual: AppManual): String? {
        val metadata = manual.metadata
        if (metadata.schemaVersion != SCHEMA_VERSION) return "UNSUPPORTED_SCHEMA"
        validateIdentity(metadata.app)?.let { return it }
        if (metadata.revision < 1L) return "INVALID_REVISION"
        if (!metadata.manualSha256.matches(Regex("^[a-f0-9]{64}$"))) return "INVALID_MANUAL_HASH"
        if (metadata.manualSha256 != AppManualCodec.sha256(manual.markdown)) return "MANUAL_HASH_MISMATCH"
        val estimatedTokens = ManualTokenEstimator.estimate(manual.markdown)
        if (metadata.estimatedTokens != estimatedTokens) return "TOKEN_ESTIMATE_MISMATCH"
        if (metadata.observedVersionCodes.isEmpty() || metadata.observedVersionCodes.size > 8 ||
            metadata.observedVersionCodes.any { it < 0L } || metadata.app.versionCode !in metadata.observedVersionCodes
        ) return "INVALID_VERSION_HISTORY"
        if (metadata.localeTags.isEmpty() || metadata.localeTags.size > 8 ||
            metadata.localeTags.any { !it.matches(Regex("^[A-Za-z0-9-]{1,35}$")) } ||
            metadata.app.localeTag !in metadata.localeTags
        ) return "INVALID_LOCALE_HISTORY"
        if (manual.markdown.length > MAX_STORED_CHARS || estimatedTokens > MAX_STORED_ESTIMATED_TOKENS) {
            return "MANUAL_TOO_LARGE"
        }
        if (manual.markdown.any { it == '\u0000' || (it.code < 0x20 && it !in listOf('\n', '\t')) }) {
            return "CONTROL_CHARACTER"
        }
        if (forbiddenMarkdown.any { it.containsMatchIn(manual.markdown) }) return "UNSAFE_MARKDOWN"
        val parsed = runCatching { AppManualMarkdownParser.parse(manual.markdown) }
            .getOrElse { return "INVALID_MARKDOWN_STRUCTURE" }
        if ("App 信息" !in metadata.protectedHeadingPaths) return "APP_INFO_NOT_PROTECTED"
        if (!parsed.headingPaths.containsAll(metadata.protectedHeadingPaths)) return "MISSING_PROTECTED_HEADING"
        if (metadata.protectedHeadingPaths.any { it.length > 180 }) return "INVALID_PROTECTED_HEADING"
        return null
    }

    fun validateIdentity(identity: ManualAppIdentity): String? {
        if (!identity.appKey.matches(Regex("^[a-f0-9]{64}$"))) return "INVALID_APP_KEY"
        if (!identity.packageName.matches(Regex("^[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+$"))) {
            return "INVALID_PACKAGE"
        }
        if (!identity.signerSha256.matches(Regex("^(?:unsigned|[a-f0-9]{64}(?::[a-f0-9]{64})*)$"))) {
            return "INVALID_SIGNER"
        }
        if (identity.displayName.isBlank() || identity.displayName.length > 80 ||
            identity.displayName.contains(Regex("[\\p{Cc}<>{}]"))
        ) return "INVALID_DISPLAY_NAME"
        if (identity.versionCode < 0L) return "INVALID_VERSION"
        if (!identity.localeTag.matches(Regex("^[A-Za-z0-9-]{1,35}$"))) return "INVALID_LOCALE"
        return null
    }
}

object ManualTokenEstimator {
    fun estimate(text: String): Int {
        var cjk = 0
        var other = 0
        text.forEach { ch ->
            if (ch.code in 0x3400..0x9FFF || ch.code in 0xF900..0xFAFF || ch.code in 0x3040..0x30FF) {
                cjk++
            } else if (!ch.isWhitespace()) {
                other++
            }
        }
        return cjk + (other + 3) / 4
    }
}
