package org.schabi.newpipe.cleanfeed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanFeedClassifierTest {
    @Test
    fun `ordinary titles are shown without reasons`() {
        val verdicts = classify(
            candidate("Building a Custom Mini PC for 4K Editing"),
            candidate("Restoration of a 100 Year Old Tool")
        )

        verdicts.forEach { assertEquals(CleanFeedVerdict.SHOW, it) }
    }

    @Test
    fun `mentioning or disclosing AI is never a signal`() {
        val verdicts = classify(
            candidate("How I use AI to edit my videos"),
            candidate("This video was made with AI tools (disclosed)"),
            candidate("AI generated music review")
        )

        verdicts.forEach { assertEquals(CleanFeedDecision.SHOW, it.decision) }
    }

    @Test
    fun `frequent uploads with varied titles are not repetitive`() {
        val titles = listOf(
            "Fixing a leaking kitchen tap",
            "Tiling the downstairs bathroom floor",
            "Our garden shed gets a new roof",
            "Wiring outdoor lights for winter",
            "Choosing paint for an old fence"
        )

        val verdicts = classify(*titles.map { candidate(it, channel = "diy") }.toTypedArray())

        verdicts.forEach { assertEquals(CleanFeedVerdict.SHOW, it) }
    }

    @Test
    fun `a single heuristic signal can reach review but never hide`() {
        val verdict = classify(
            candidate("You won't believe what happened next"),
            strength = CleanFeedStrength.STRICT
        ).single()

        assertEquals(CleanFeedDecision.REVIEW, verdict.decision)
        assertEquals(listOf(CleanFeedSignal.BAIT_PHRASE), verdict.reasons)
    }

    @Test
    fun `several heuristic signals together hide with every reason listed`() {
        val verdict = classify(candidate("YOU WON'T BELIEVE THIS GONE WRONG!!!")).single()

        assertEquals(CleanFeedDecision.HIDE, verdict.decision)
        assertEquals(
            listOf(
                CleanFeedSignal.BAIT_PHRASE,
                CleanFeedSignal.SHOUTING_TITLE,
                CleanFeedSignal.EXCESSIVE_PUNCTUATION
            ),
            verdict.reasons
        )
        assertEquals(60, verdict.score)
    }

    @Test
    fun `strength changes how readily combined signals hide`() {
        val title = "Wait for it!!"

        assertEquals(
            CleanFeedDecision.SHOW,
            classify(candidate(title), strength = CleanFeedStrength.LIGHT).single().decision
        )
        assertEquals(
            CleanFeedDecision.REVIEW,
            classify(candidate(title), strength = CleanFeedStrength.BALANCED).single().decision
        )
        assertEquals(
            CleanFeedDecision.REVIEW,
            classify(candidate(title), strength = CleanFeedStrength.STRICT).single().decision
        )
    }

    @Test
    fun `near-identical title templates from one channel are flagged as repetitive`() {
        val verdicts = classify(
            candidate("Top 10 Facts About Lions You Never Knew", channel = "farm"),
            candidate("Top 10 Facts About Sharks You Never Knew", channel = "farm"),
            candidate("Top 7 Facts About Eagles You Never Knew", channel = "farm"),
            candidate("A day at our animal sanctuary", channel = "farm")
        )

        verdicts.take(3).forEach {
            assertEquals(CleanFeedDecision.REVIEW, it.decision)
            assertEquals(listOf(CleanFeedSignal.REPETITIVE_TITLES), it.reasons)
        }
        assertEquals(CleanFeedVerdict.SHOW, verdicts[3])
    }

    @Test
    fun `the same template across different channels is not repetitive`() {
        val verdicts = classify(
            candidate("Top 10 Facts About Lions You Never Knew", channel = "a"),
            candidate("Top 10 Facts About Sharks You Never Knew", channel = "b"),
            candidate("Top 7 Facts About Eagles You Never Knew", channel = "c")
        )

        verdicts.forEach { assertEquals(CleanFeedVerdict.SHOW, it) }
    }

    @Test
    fun `repetition combined with a warnlisted channel hides`() {
        val verdicts = classify(
            candidate("Relaxing Rain Sounds for Sleep 10 Hours Black Screen", channel = "x", warnlisted = true),
            candidate("Relaxing Ocean Sounds for Sleep 10 Hours Black Screen", channel = "x", warnlisted = true),
            candidate("Relaxing Forest Sounds for Sleep 8 Hours Black Screen", channel = "x", warnlisted = true)
        )

        verdicts.forEach {
            assertEquals(CleanFeedDecision.HIDE, it.decision)
            assertEquals(
                listOf(CleanFeedSignal.COMMUNITY_WARNLIST, CleanFeedSignal.REPETITIVE_TITLES),
                it.reasons
            )
        }
    }

    @Test
    fun `explicit rules decide on their own`() {
        val verdicts = classify(
            candidate("Ordinary title", rule = CleanFeedSignal.USER_BLOCKED_CHANNEL),
            candidate("YOU WON'T BELIEVE THIS!!!", rule = CleanFeedSignal.ALWAYS_ALLOWED_CHANNEL)
        )

        assertEquals(CleanFeedDecision.HIDE, verdicts[0].decision)
        assertEquals(listOf(CleanFeedSignal.USER_BLOCKED_CHANNEL), verdicts[0].reasons)
        assertEquals(CleanFeedDecision.SHOW, verdicts[1].decision)
        assertEquals(listOf(CleanFeedSignal.ALWAYS_ALLOWED_CHANNEL), verdicts[1].reasons)
    }

    @Test
    fun `emoji-heavy titles are flagged`() {
        assertTrue(
            CleanFeedSignal.EMOJI_HEAVY_TITLE in
                CleanFeedClassifier.titleSignals("Best day ever 🔥🔥😱")
        )
    }

    @Test
    fun `unknown strength preference falls back to balanced`() {
        assertEquals(CleanFeedStrength.BALANCED, CleanFeedStrength.fromPreferenceValue(null))
        assertEquals(CleanFeedStrength.STRICT, CleanFeedStrength.fromPreferenceValue("strict"))
    }

    private fun classify(
        vararg candidates: CleanFeedCandidate,
        strength: CleanFeedStrength = CleanFeedStrength.BALANCED
    ) = CleanFeedClassifier.classify(candidates.toList(), strength)

    private fun candidate(
        title: String,
        channel: String? = null,
        rule: CleanFeedSignal? = null,
        warnlisted: Boolean = false
    ) = CleanFeedCandidate(title, channel, rule, warnlisted)
}
