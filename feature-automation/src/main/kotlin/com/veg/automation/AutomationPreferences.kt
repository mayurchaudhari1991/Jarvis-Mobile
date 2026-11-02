package com.veg.automation

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the user's automation choices.
 *
 * Everything defaults to OFF. An accessibility service that starts
 * automating gestures the moment Android grants it the permission would be
 * hostile — the user must deliberately opt in to each behavior here, on top
 * of deliberately granting the accessibility permission itself.
 */
class AutomationPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var skipAdsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SKIP_ADS_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SKIP_ADS_ENABLED, value).apply()

    var autoScrollEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SCROLL_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SCROLL_ENABLED, value).apply()

    var adDwellMillis: Long
        get() = prefs.getLong(KEY_AD_DWELL_MILLIS, ReelHeuristics.AD_DWELL_MILLIS)
        set(value) = prefs.edit().putLong(KEY_AD_DWELL_MILLIS, value).apply()

    var videoDwellMillis: Long
        get() = prefs.getLong(KEY_VIDEO_DWELL_MILLIS, ReelHeuristics.VIDEO_DWELL_MILLIS)
        set(value) = prefs.edit().putLong(KEY_VIDEO_DWELL_MILLIS, value).apply()

    fun snapshot(): AutomationSettings = AutomationSettings(
        skipAdsEnabled = skipAdsEnabled,
        autoScrollEnabled = autoScrollEnabled,
        adDwellMillis = adDwellMillis,
        videoDwellMillis = videoDwellMillis
    )

    companion object {
        private const val PREFS_NAME = "veg_automation_prefs"
        private const val KEY_SKIP_ADS_ENABLED = "skip_ads_enabled"
        private const val KEY_AUTO_SCROLL_ENABLED = "auto_scroll_enabled"
        private const val KEY_AD_DWELL_MILLIS = "ad_dwell_millis"
        private const val KEY_VIDEO_DWELL_MILLIS = "video_dwell_millis"
    }
}
