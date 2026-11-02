package com.veg.automation

/**
 * User-controlled tuning for [ReelHeuristics].
 *
 * Both dwell values are guesswork proxies, not measurements of what actually
 * happened on screen — see the class doc on [ReelHeuristics] for why.
 */
data class AutomationSettings(
    val skipAdsEnabled: Boolean,
    val autoScrollEnabled: Boolean,
    val adDwellMillis: Long,
    val videoDwellMillis: Long
)
