package com.veg.automation

import org.junit.Assert.assertEquals
import org.junit.Test

class ReelHeuristicsTest {

    private val defaultSettings = AutomationSettings(
        skipAdsEnabled = true,
        autoScrollEnabled = true,
        adDwellMillis = ReelHeuristics.AD_DWELL_MILLIS,
        videoDwellMillis = ReelHeuristics.VIDEO_DWELL_MILLIS
    )

    private fun snapshot(vararg text: String, pkg: String = "com.instagram.android") = ReelSnapshot(
        packageName = pkg,
        visibleText = text.toList(),
        viewIds = emptyList(),
        atMillis = 0L
    )

    @Test
    fun `a sponsored post is detected as an ad`() {
        val snap = snapshot("some caption", "sponsored")
        assertEquals(true, ReelHeuristics.isAd(snap))
    }

    @Test
    fun `a paid partnership label is detected as an ad`() {
        val snap = snapshot("caption text", "paid partnership")
        assertEquals(true, ReelHeuristics.isAd(snap))
    }

    @Test
    fun `an ordinary reel is not an ad`() {
        val snap = snapshot("just a normal caption", "42 likes")
        assertEquals(false, ReelHeuristics.isAd(snap))
    }

    @Test
    fun `an ad is scrolled after the short dwell`() {
        val snap = snapshot("sponsored")
        val shownSince = 0L
        val now = ReelHeuristics.AD_DWELL_MILLIS + 1
        val action = ReelHeuristics.decide(
            snapshot = snap.copy(atMillis = now),
            shownSinceMillis = shownSince,
            settings = defaultSettings
        )
        assertEquals(ReelAction.SCROLL_NEXT, action)
    }

    @Test
    fun `an ad is not scrolled before the short dwell`() {
        val snap = snapshot("sponsored")
        val shownSince = 0L
        val now = ReelHeuristics.AD_DWELL_MILLIS - 1
        val action = ReelHeuristics.decide(
            snapshot = snap.copy(atMillis = now),
            shownSinceMillis = shownSince,
            settings = defaultSettings
        )
        assertEquals(ReelAction.NONE, action)
    }

    @Test
    fun `an ordinary video is not scrolled before the long dwell`() {
        val snap = snapshot("just a normal caption")
        val shownSince = 0L
        val now = ReelHeuristics.VIDEO_DWELL_MILLIS - 1
        val action = ReelHeuristics.decide(
            snapshot = snap.copy(atMillis = now),
            shownSinceMillis = shownSince,
            settings = defaultSettings
        )
        assertEquals(ReelAction.NONE, action)
    }

    @Test
    fun `an ordinary video is scrolled after the long dwell`() {
        val snap = snapshot("just a normal caption")
        val shownSince = 0L
        val now = ReelHeuristics.VIDEO_DWELL_MILLIS + 1
        val action = ReelHeuristics.decide(
            snapshot = snap.copy(atMillis = now),
            shownSinceMillis = shownSince,
            settings = defaultSettings
        )
        assertEquals(ReelAction.SCROLL_NEXT, action)
    }

    @Test
    fun `nothing is scrolled when auto scroll is disabled`() {
        val snap = snapshot("just a normal caption")
        val shownSince = 0L
        val now = ReelHeuristics.VIDEO_DWELL_MILLIS + 1
        val settings = defaultSettings.copy(autoScrollEnabled = false)
        val action = ReelHeuristics.decide(
            snapshot = snap.copy(atMillis = now),
            shownSinceMillis = shownSince,
            settings = settings
        )
        assertEquals(ReelAction.NONE, action)
    }

    @Test
    fun `an ad is not skipped when ad skipping is disabled`() {
        val snap = snapshot("sponsored")
        val shownSince = 0L
        val now = ReelHeuristics.AD_DWELL_MILLIS + 1
        val settings = defaultSettings.copy(skipAdsEnabled = false)
        val action = ReelHeuristics.decide(
            snapshot = snap.copy(atMillis = now),
            shownSinceMillis = shownSince,
            settings = settings
        )
        // Ad-skipping is off, but auto-scroll is on: the ad still counts as a
        // reel and should be scrolled once the ordinary (long) dwell elapses,
        // not the short ad dwell.
        assertEquals(ReelAction.NONE, action)
    }

    @Test
    fun `detection is case insensitive`() {
        val snap = snapshot("SPONSORED")
        assertEquals(true, ReelHeuristics.isAd(snap))
    }
}
