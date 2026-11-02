package com.veg.automation

/** What the service observed about the currently visible reel. */
data class ReelSnapshot(
    val packageName: String,
    /** Text found in the visible node tree, lowercased. */
    val visibleText: List<String>,
    /** View ids present, e.g. "com.instagram.android:id/..." */
    val viewIds: List<String>,
    val atMillis: Long
)

enum class ReelAction { NONE, SCROLL_NEXT }

/**
 * Decides whether to scroll past the current reel.
 *
 * Neither "the video ended" nor "this is an ad" is exposed by an API, so both
 * are inferred from what is on screen. That makes this guesswork, and it will
 * occasionally be wrong — so the logic lives here, pure and testable, rather
 * than tangled into the service where it cannot be inspected.
 *
 * Concretely: there is no accessibility signal for "playback finished", so a
 * fixed dwell time is used as a stand-in — if a reel has been visible longer
 * than [AutomationSettings.videoDwellMillis] (or [AutomationSettings.adDwellMillis]
 * for a detected ad) it is assumed to be done and is scrolled past. This will
 * cut off long videos early and let short ones sit on screen a little after
 * they end. There is no way to do better without a real "playback state" API,
 * so this is a deliberate, honest tradeoff rather than a bug to chase.
 */
object ReelHeuristics {

    private val AD_MARKERS = listOf("sponsored", "paid partnership", "promoted")

    /** An ad is skipped sooner than a real video is allowed to finish. */
    const val AD_DWELL_MILLIS = 1_500L
    const val VIDEO_DWELL_MILLIS = 30_000L

    fun decide(
        snapshot: ReelSnapshot,
        shownSinceMillis: Long,
        settings: AutomationSettings
    ): ReelAction {
        if (!settings.autoScrollEnabled) return ReelAction.NONE

        val elapsed = snapshot.atMillis - shownSinceMillis
        val ad = isAd(snapshot)

        val dwellLimit = if (ad && settings.skipAdsEnabled) {
            settings.adDwellMillis
        } else {
            settings.videoDwellMillis
        }

        return if (elapsed >= dwellLimit) ReelAction.SCROLL_NEXT else ReelAction.NONE
    }

    fun isAd(snapshot: ReelSnapshot): Boolean =
        snapshot.visibleText.any { text -> AD_MARKERS.any { text.lowercase().contains(it) } }
}
