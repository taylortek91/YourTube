/*
 * SPDX-FileCopyrightText: 2026 YourTube contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cleanfeed

import java.util.Locale

/** What Clean Feed does with a candidate video. */
enum class CleanFeedDecision {
    SHOW,
    REVIEW,
    HIDE
}

/** How readily heuristic signals turn into REVIEW or HIDE decisions. */
enum class CleanFeedStrength(
    val preferenceValue: String,
    val reviewThreshold: Int,
    val hideThreshold: Int
) {
    LIGHT("light", reviewThreshold = 50, hideThreshold = 80),
    BALANCED("balanced", reviewThreshold = 35, hideThreshold = 60),
    STRICT("strict", reviewThreshold = 25, hideThreshold = 45);

    companion object {
        @JvmStatic
        fun fromPreferenceValue(value: String?): CleanFeedStrength = entries.firstOrNull { it.preferenceValue == value } ?: BALANCED
    }
}

/**
 * A human-explainable reason contributing to a verdict.
 *
 * Rule signals ([isRule]) come from explicit lists and decide on their own. Heuristic signals only
 * add risk; a heuristic HIDE always needs at least two different heuristic signals.
 */
enum class CleanFeedSignal(val weight: Int, val isRule: Boolean = false) {
    USER_BLOCKED_VIDEO(100, isRule = true),
    USER_BLOCKED_CHANNEL(100, isRule = true),
    USER_BLOCKED_KEYWORD(100, isRule = true),
    COMMUNITY_BLOCKLIST(100, isRule = true),
    ALWAYS_ALLOWED_CHANNEL(0, isRule = true),
    COMMUNITY_WARNLIST(35),
    REPETITIVE_TITLES(35),
    BAIT_PHRASE(25),
    SHOUTING_TITLE(20),
    EXCESSIVE_PUNCTUATION(15),
    EMOJI_HEAVY_TITLE(10)
}

data class CleanFeedVerdict(
    val decision: CleanFeedDecision,
    val score: Int,
    val reasons: List<CleanFeedSignal>
) {
    companion object {
        @JvmField
        val SHOW = CleanFeedVerdict(CleanFeedDecision.SHOW, 0, emptyList())
    }
}

/** The metadata Clean Feed inspects for one video. */
data class CleanFeedCandidate(
    val title: String?,
    val channelKey: String?,
    /** The result of explicit rules, or null when no rule matched. */
    val ruleSignal: CleanFeedSignal? = null,
    val communityWarnlisted: Boolean = false
)

/**
 * Explainable, on-device quality screening for a batch of videos.
 *
 * Mentioning or disclosing AI, or uploading often, is never a signal by itself. Repetition is
 * judged from near-identical title templates within one channel, and no single heuristic can hide
 * a video.
 */
object CleanFeedClassifier {
    private const val MAX_SCORE = 100
    private const val MIN_REPETITIVE_CLUSTER = 3
    private const val MIN_TEMPLATE_TOKENS = 4
    private const val NEAR_DUPLICATE_SIMILARITY = 0.75
    private const val MAX_PAIRWISE_TITLES_PER_CHANNEL = 200
    private const val MIN_SHOUTING_LETTERS = 12
    private const val SHOUTING_UPPERCASE_RATIO = 0.6
    private const val MIN_EXCLAMATIONS = 3
    private const val MIN_EMOJI = 3

    private val BAIT_PHRASES = listOf(
        "you wont believe",
        "you will not believe",
        "gone wrong",
        "not clickbait",
        "watch till the end",
        "watch until the end",
        "wait for it",
        "you need to see this",
        "nobody is talking about",
        "doctors hate",
        "this one trick",
        "100% real",
        "shocking truth"
    )
    private val REPEATED_PUNCTUATION = Regex("[!?]{2,}")
    private val NON_TEMPLATE_CHARACTERS = Regex("[^\\p{L}\\p{N}#]+")
    private val DIGITS = Regex("\\p{N}+")
    private val APOSTROPHES = Regex("['’`]")

    @JvmStatic
    fun classify(
        candidates: List<CleanFeedCandidate>,
        strength: CleanFeedStrength
    ): List<CleanFeedVerdict> {
        val repetitive = findRepetitiveTitles(candidates)
        return candidates.mapIndexed { index, candidate ->
            classify(candidate, index in repetitive, strength)
        }
    }

    private fun classify(
        candidate: CleanFeedCandidate,
        repetitive: Boolean,
        strength: CleanFeedStrength
    ): CleanFeedVerdict {
        candidate.ruleSignal?.let { rule ->
            return if (rule == CleanFeedSignal.ALWAYS_ALLOWED_CHANNEL) {
                CleanFeedVerdict(CleanFeedDecision.SHOW, 0, listOf(rule))
            } else {
                CleanFeedVerdict(CleanFeedDecision.HIDE, rule.weight, listOf(rule))
            }
        }

        val reasons = buildList {
            if (candidate.communityWarnlisted) {
                add(CleanFeedSignal.COMMUNITY_WARNLIST)
            }
            if (repetitive) {
                add(CleanFeedSignal.REPETITIVE_TITLES)
            }
            addAll(titleSignals(candidate.title.orEmpty()))
        }
        if (reasons.isEmpty()) {
            return CleanFeedVerdict.SHOW
        }

        val score = reasons.sumOf { it.weight }.coerceAtMost(MAX_SCORE)
        val decision = when {
            score >= strength.hideThreshold && reasons.size >= 2 -> CleanFeedDecision.HIDE
            score >= strength.reviewThreshold -> CleanFeedDecision.REVIEW
            else -> CleanFeedDecision.SHOW
        }
        return CleanFeedVerdict(decision, score, reasons)
    }

    internal fun titleSignals(title: String): List<CleanFeedSignal> = buildList {
        val folded = APOSTROPHES.replace(title.lowercase(Locale.ROOT), "")
        if (BAIT_PHRASES.any { folded.contains(it) }) {
            add(CleanFeedSignal.BAIT_PHRASE)
        }
        if (isShouting(title)) {
            add(CleanFeedSignal.SHOUTING_TITLE)
        }
        if (REPEATED_PUNCTUATION.containsMatchIn(title) ||
            title.count { it == '!' } >= MIN_EXCLAMATIONS
        ) {
            add(CleanFeedSignal.EXCESSIVE_PUNCTUATION)
        }
        if (countEmoji(title) >= MIN_EMOJI) {
            add(CleanFeedSignal.EMOJI_HEAVY_TITLE)
        }
    }

    private fun isShouting(title: String): Boolean {
        var letters = 0
        var uppercase = 0
        for (character in title) {
            if (character.isLetter()) {
                letters++
                if (character.isUpperCase()) {
                    uppercase++
                }
            }
        }
        return letters >= MIN_SHOUTING_LETTERS &&
            uppercase >= letters * SHOUTING_UPPERCASE_RATIO
    }

    private fun countEmoji(title: String): Int {
        var count = 0
        var offset = 0
        while (offset < title.length) {
            val codePoint = title.codePointAt(offset)
            if (codePoint >= 0x1F000 || codePoint in 0x2600..0x27BF) {
                count++
            }
            offset += Character.charCount(codePoint)
        }
        return count
    }

    /** Indices of videos whose channel used a near-identical title template at least 3 times. */
    internal fun findRepetitiveTitles(candidates: List<CleanFeedCandidate>): Set<Int> {
        val byChannel = candidates.indices
            .filter { !candidates[it].channelKey.isNullOrBlank() }
            .groupBy { candidates[it].channelKey!!.trim().lowercase(Locale.ROOT) }

        val repetitive = HashSet<Int>()
        for (indices in byChannel.values) {
            if (indices.size < MIN_REPETITIVE_CLUSTER) {
                continue
            }
            val tokens = indices.map { templateTokens(candidates[it].title.orEmpty()) }
            val clusters = UnionFind(indices.size)
            val firstByTemplate = HashMap<List<String>, Int>()
            tokens.forEachIndexed { position, template ->
                if (template.size >= MIN_TEMPLATE_TOKENS) {
                    firstByTemplate.putIfAbsent(template, position)
                        ?.let { clusters.union(it, position) }
                }
            }
            if (indices.size <= MAX_PAIRWISE_TITLES_PER_CHANNEL) {
                val sets = tokens.map { it.toSet() }
                for (first in sets.indices) {
                    if (sets[first].size < MIN_TEMPLATE_TOKENS) continue
                    for (second in first + 1 until sets.size) {
                        if (sets[second].size >= MIN_TEMPLATE_TOKENS &&
                            jaccard(sets[first], sets[second]) >= NEAR_DUPLICATE_SIMILARITY
                        ) {
                            clusters.union(first, second)
                        }
                    }
                }
            }
            indices.indices.groupBy(clusters::find).values
                .filter { it.size >= MIN_REPETITIVE_CLUSTER }
                .forEach { cluster -> cluster.forEach { repetitive.add(indices[it]) } }
        }
        return repetitive
    }

    internal fun templateTokens(title: String): List<String> = DIGITS.replace(APOSTROPHES.replace(title.lowercase(Locale.ROOT), ""), "#")
        .split(NON_TEMPLATE_CHARACTERS)
        .filter { it.isNotEmpty() }

    private fun jaccard(first: Set<String>, second: Set<String>): Double {
        val intersection = first.count { it in second }
        return intersection.toDouble() / (first.size + second.size - intersection)
    }

    private class UnionFind(size: Int) {
        private val parent = IntArray(size) { it }

        fun find(value: Int): Int {
            var root = value
            while (parent[root] != root) {
                root = parent[root]
            }
            var current = value
            while (parent[current] != root) {
                val next = parent[current]
                parent[current] = root
                current = next
            }
            return root
        }

        fun union(first: Int, second: Int) {
            parent[find(first)] = find(second)
        }
    }
}
