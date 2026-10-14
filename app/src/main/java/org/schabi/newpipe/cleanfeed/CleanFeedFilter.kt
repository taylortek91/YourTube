/*
 * SPDX-FileCopyrightText: 2026 YourTube contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package org.schabi.newpipe.cleanfeed

import android.content.Context
import androidx.preference.PreferenceManager
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.util.AiSListContentHelper
import org.schabi.newpipe.util.AiSListRepository
import org.schabi.newpipe.util.ContentBlockingHelper
import org.schabi.newpipe.util.ContentBlockingHelper.BlockReason

/**
 * Applies the user's blocking rules and, when Clean Feed is enabled, its quality screening to a
 * batch of remote items. With Clean Feed disabled, verdicts match the plain blocking rules.
 */
class CleanFeedFilter internal constructor(
    private val rules: ContentBlockingHelper.Rules,
    private val strength: CleanFeedStrength?,
    private val isWarnListed: (StreamInfoItem) -> Boolean
) {
    fun evaluate(items: List<InfoItem>): List<CleanFeedVerdict> {
        if (strength == null) {
            return items.map { ruleVerdict(rules.blockReason(it)) }
        }

        val streamPositions = ArrayList<Int>()
        val candidates = ArrayList<CleanFeedCandidate>()
        items.forEachIndexed { position, item ->
            if (item is StreamInfoItem) {
                streamPositions.add(position)
                candidates.add(candidate(item))
            }
        }
        val streamVerdicts = CleanFeedClassifier.classify(candidates, strength)

        val verdicts = items.map { ruleVerdict(rules.blockReason(it)) }.toMutableList()
        streamPositions.forEachIndexed { index, position ->
            verdicts[position] = streamVerdicts[index]
        }
        return verdicts
    }

    private fun candidate(stream: StreamInfoItem): CleanFeedCandidate {
        val ruleSignal = rules.blockReason(stream)?.toSignal()
            ?: CleanFeedSignal.ALWAYS_ALLOWED_CHANNEL.takeIf {
                rules.isAllowedChannel(stream.uploaderUrl, stream.uploaderName)
            }
        return CleanFeedCandidate(
            title = stream.name,
            channelKey = stream.uploaderUrl?.takeIf { it.isNotBlank() } ?: stream.uploaderName,
            ruleSignal = ruleSignal,
            communityWarnlisted = ruleSignal == null && isWarnListed(stream)
        )
    }

    companion object {
        @JvmStatic
        fun create(context: Context, target: ContentBlockingHelper.Target): CleanFeedFilter {
            val rules = ContentBlockingHelper.getRules(context, target)
            val preferences = PreferenceManager.getDefaultSharedPreferences(context)
            val strength = CleanFeedStrength.fromPreferenceValue(
                preferences.getString(context.getString(R.string.clean_feed_strength_key), null)
            ).takeIf {
                rules.isEnabled &&
                    preferences.getBoolean(context.getString(R.string.clean_feed_enabled_key), false)
            }
            val appContext = context.applicationContext
            val warnlistActive = strength != null && AiSListContentHelper.isActive(appContext)
            return CleanFeedFilter(rules, strength) { stream ->
                warnlistActive && AiSListRepository.isWarnListed(
                    appContext,
                    stream.serviceId,
                    stream.uploaderUrl,
                    stream.uploaderName
                )
            }
        }

        private fun ruleVerdict(reason: BlockReason?): CleanFeedVerdict {
            val signal = reason?.toSignal() ?: return CleanFeedVerdict.SHOW
            return CleanFeedVerdict(CleanFeedDecision.HIDE, signal.weight, listOf(signal))
        }

        private fun BlockReason.toSignal(): CleanFeedSignal = when (this) {
            BlockReason.VIDEO -> CleanFeedSignal.USER_BLOCKED_VIDEO
            BlockReason.CHANNEL -> CleanFeedSignal.USER_BLOCKED_CHANNEL
            BlockReason.KEYWORD -> CleanFeedSignal.USER_BLOCKED_KEYWORD
            BlockReason.COMMUNITY_LIST -> CleanFeedSignal.COMMUNITY_BLOCKLIST
        }
    }
}
