package com.xremail.app.voice

import com.xremail.app.util.XrLog

/**
 * Pure parser that turns a raw speech transcript into an
 * [EmailCommandTool.Command], without making any network calls.
 *
 * This is the heart of the "local-first" voice path: the recognizer
 * (on-device Android `SpeechRecognizer`) gives us partial / final text
 * results in ~200-500 ms, and we try to map them to a Command here. If
 * we match, the dispatcher fires immediately — no Gemini round-trip,
 * no audio bidi stream, no SDK warmup. The whole user-visible latency
 * is just (speech-end-silence-window) + (one ViewModel coroutine hop).
 *
 * If [parse] returns null, the caller should escalate to Gemini Live
 * (the model can handle anything we can't, e.g. "what's important
 * today", "draft a reply saying I'll be late", "remind me about Bob's
 * email tomorrow").
 *
 * ### Wake-word stripping
 *
 * The on-device recognizer captures the WHOLE utterance — wake phrase
 * included — so the typical input is "hey gemini archive this email"
 * rather than "archive this email". We strip the wake prefix here so
 * the caller doesn't have to. If the utterance doesn't START with a
 * wake phrase, [parse] returns null without trying to match — that's
 * how we keep coworkers' overheard "next" / "archive that" from
 * accidentally controlling the inbox.
 *
 * ### Pattern style
 *
 * Patterns are kept as Regex (rather than fuzzy ML matching) on
 * purpose: deterministic, debuggable, and most importantly *fast* —
 * matching all ~25 patterns against a transcript takes <1 ms even on
 * the headset's slow path. The trade-off is that paraphrases the user
 * makes up on the spot might miss; for those, the Gemini fallback
 * picks up the slack.
 */
object CommandGrammar {

    private const val TAG = "CmdGrammar"

    /**
     * Wake phrases the user can prefix any local command with. Matching
     * is case-insensitive substring on the lowercased + cleaned-up
     * transcript. We accept several mishears the same way [WakeWordDetector]
     * does, because the on-device ASR loves to render "gemini" as
     * "jiminy" / "gem" / "gemma" / "gemmini".
     */
    private val WAKE_PHRASES = listOf(
        "hey gemini",
        "hi gemini",
        "ok gemini",
        "okay gemini",
        "hey jiminy",
        "hey gemma",
        "hey gem",
        "gemini",
        // Mail-specific wake words feel more natural for an email app
        // and avoid the user having to say "gemini" at all when they're
        // explicitly addressing the inbox.
        "hey mail",
        "hey email",
        "mail",
    )

    /**
     * Result of a parse attempt — distinguishes between
     * `not-addressed-to-us`, `addressed-but-unparseable`, and
     * `parsed-ok`. The caller uses this trichotomy to decide whether
     * to ignore the utterance, escalate to Gemini, or dispatch the
     * command.
     */
    sealed class Result {
        /** Utterance didn't start with a wake phrase — ignore. */
        data object NotAddressed : Result()

        /**
         * Utterance was addressed to us (had a wake prefix) but didn't
         * match any known local command. The remaining text after the
         * wake prefix is captured in [remainder] so the caller can
         * forward it to Gemini Live as a text turn.
         */
        data class Escalate(val remainder: String) : Result()

        /** Local command matched — dispatch directly. */
        data class Dispatch(val command: EmailCommandTool.Command) : Result()
    }

    fun parse(rawText: String): Result {
        val normalized = normalize(rawText)
        if (normalized.isBlank()) return Result.NotAddressed

        val stripped = stripWakePrefix(normalized) ?: return Result.NotAddressed
        if (stripped.isBlank()) {
            // User just said "hey gemini" with nothing after — treat as
            // a manual summon (escalate with empty remainder so caller
            // can decide whether to open Gemini's mic for follow-up).
            XrLog.d(TAG, "wake-only utterance — escalating with empty remainder")
            return Result.Escalate(remainder = "")
        }

        for ((label, pattern, build) in PATTERNS) {
            if (pattern.containsMatchIn(stripped)) {
                val cmd = build(pattern.find(stripped)!!) ?: continue
                XrLog.i(TAG, "local command matched [$label] from \"$stripped\" -> $cmd")
                return Result.Dispatch(cmd)
            }
        }

        XrLog.d(TAG, "no local pattern matched \"$stripped\" — escalating to Gemini")
        return Result.Escalate(remainder = stripped)
    }

    // ---------------------------------------------------------------------------
    // Normalization
    // ---------------------------------------------------------------------------

    private val FILLER_PUNCTUATION = Regex("""[,.!?;:"'\-]""")

    private fun normalize(raw: String): String {
        return raw
            .lowercase()
            .replace(FILLER_PUNCTUATION, " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun stripWakePrefix(text: String): String? {
        for (phrase in WAKE_PHRASES) {
            if (text.startsWith(phrase)) {
                return text.removePrefix(phrase).trim()
            }
        }
        // Some recognizers prepend filler ("uh hey gemini …"). Be lenient:
        // if any wake phrase appears in the FIRST 25 chars, treat
        // everything after it as the command text.
        val window = text.take(25)
        for (phrase in WAKE_PHRASES) {
            val idx = window.indexOf(phrase)
            if (idx >= 0) {
                return text.substring(idx + phrase.length).trim()
            }
        }
        return null
    }

    // ---------------------------------------------------------------------------
    // Patterns
    // ---------------------------------------------------------------------------

    /**
     * One row per command. The match function receives the full Regex
     * MatchResult so it can pull out captured groups (e.g. snooze time,
     * search query). Returning null from the build lambda means
     * "matched the regex but couldn't build a valid command" — the
     * outer loop continues to the next pattern.
     *
     * Pattern ordering matters: more specific patterns come first.
     * E.g. "send draft" must beat "send" alone, "snooze until tomorrow"
     * must beat bare "snooze". The matcher is greedy-first-match.
     */
    private data class Pattern(
        val label: String,
        val regex: Regex,
        val build: (MatchResult) -> EmailCommandTool.Command?,
    )

    private val PATTERNS: List<Pattern> = listOf(
        // ----- Tier escalation (specific phrasings) -----
        Pattern(
            label = "expand_inbox",
            regex = Regex("""^(show\s+(me\s+)?(my\s+)?(the\s+)?inbox|open\s+(the\s+)?inbox|inbox)$"""),
            build = { EmailCommandTool.Command.ExpandTier("triage") },
        ),
        Pattern(
            label = "expand_focus",
            regex = Regex("""^(focus(\s+mode)?|enter\s+focus|read\s+mode)$"""),
            build = { EmailCommandTool.Command.ExpandTier("focus") },
        ),
        Pattern(
            label = "expand_notifications",
            regex = Regex("""^(show\s+(me\s+)?(my\s+)?notifications?|open\s+notifications?|notifications?)$"""),
            build = { EmailCommandTool.Command.ExpandTier("notifications") },
        ),

        // ----- Tier collapse -----
        Pattern(
            label = "collapse",
            regex = Regex("""^(close|minimize|collapse|go\s+back|back\s+out|i'?m\s+done|done|dismiss|exit)$"""),
            build = { EmailCommandTool.Command.CollapseOneTier },
        ),

        // ----- Refresh -----
        Pattern(
            label = "refresh",
            regex = Regex("""^(refresh|reset|reload|start\s+over|i'?m\s+stuck|the\s+ui\s+is\s+frozen)$"""),
            build = { EmailCommandTool.Command.Refresh },
        ),

        // ----- Mailbox actions on the SELECTED email -----
        Pattern(
            label = "archive",
            regex = Regex("""^(archive|delete|trash|throw\s+(it|this|that)\s+away)(\s+(this|it|that|email|message))*$"""),
            build = { EmailCommandTool.Command.ArchiveEmail(null) },
        ),
        Pattern(
            label = "snooze_until",
            regex = Regex("""^snooze(\s+(this|it|that|email))?\s+(until|for|till)\s+(.+)$"""),
            build = { match ->
                val until = match.groupValues[4].trim().ifBlank { "tomorrow morning" }
                EmailCommandTool.Command.SnoozeEmail(emailId = null, until = until)
            },
        ),
        Pattern(
            label = "snooze_bare",
            regex = Regex("""^snooze(\s+(this|it|that|email))?$"""),
            build = {
                EmailCommandTool.Command.SnoozeEmail(emailId = null, until = "tomorrow morning")
            },
        ),
        Pattern(
            label = "read_aloud",
            regex = Regex("""^(read|read\s+(it|this|that|aloud|to\s+me)|read\s+(this|it|that)\s+(aloud|to\s+me))$"""),
            build = { EmailCommandTool.Command.ReadAloud(emailId = null) },
        ),
        Pattern(
            label = "summarize",
            regex = Regex("""^(summarize(\s+(this|it|that))?|summary|tl\s*dr|what\s+does\s+(it|this|that)\s+say|give\s+me\s+(the\s+)?summary)$"""),
            build = { EmailCommandTool.Command.Summarize(emailId = null) },
        ),
        Pattern(
            label = "next_unread",
            regex = Regex("""^(next(\s+(email|one|unread|message))?|skip|move\s+on)$"""),
            build = { EmailCommandTool.Command.NextUnread },
        ),
        Pattern(
            label = "send_draft",
            regex = Regex("""^(send|send\s+it|send\s+the?\s+(draft|reply|message)|fire\s+it\s+off)$"""),
            build = { EmailCommandTool.Command.SendDraft(emailId = null) },
        ),
        Pattern(
            label = "compose_reply",
            regex = Regex("""^(reply|respond|compose(\s+(a\s+)?reply)?|write\s+(a\s+)?reply|write\s+back)$"""),
            build = { EmailCommandTool.Command.DraftReply(emailId = null, tone = null) },
        ),
        Pattern(
            label = "filter_category",
            regex = Regex("""^(show\s+(me\s+)?|filter\s+(by\s+)?)?(work|personal|promotions|social|updates|finance)(\s+emails?)?$"""),
            build = { match ->
                val category = match.groupValues[4].trim()
                EmailCommandTool.Command.FilterCategory(category)
            },
        ),
        Pattern(
            label = "search",
            regex = Regex("""^(search\s+(for\s+)?|find\s+(emails?\s+)?(from\s+|about\s+)?)(.+)$"""),
            build = { match ->
                val query = match.groupValues[5].trim().ifBlank { return@Pattern null }
                EmailCommandTool.Command.Search(query)
            },
        ),
    )
}
