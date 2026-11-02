package com.veg.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log

/**
 * Watches Facebook and Instagram reel surfaces and scrolls past a reel once
 * [ReelHeuristics] decides it has been on screen long enough.
 *
 * Scoped to exactly two packages by veg_automation_config.xml
 * (android:packageNames) — see that file for why. This class never sees
 * events from any other app.
 */
class VegAutomationService : AccessibilityService() {

    private lateinit var preferences: AutomationPreferences

    // Wall-clock time the current reel first appeared, and the last time a
    // gesture was actually dispatched. Both reset on package/content changes
    // that look like a genuinely new reel, and the latter also guards against
    // dispatching the same scroll twice in a row.
    private var shownSinceMillis: Long = 0L
    private var lastScrollDispatchMillis: Long = 0L
    private var lastSnapshotKey: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        preferences = AutomationPreferences(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (!::preferences.isInitialized) {
            preferences = AutomationPreferences(applicationContext)
        }

        val root = rootInActiveWindow ?: return
        val snapshot = try {
            buildSnapshot(root)
        } finally {
            root.recycle()
        }

        val key = snapshot.viewIds.joinToString("|") + "::" + snapshot.visibleText.joinToString("|")
        if (key != lastSnapshotKey) {
            // The visible content changed enough to look like a new reel;
            // restart the dwell clock. This is itself a heuristic — see
            // ReelHeuristics for why there is no better signal available.
            lastSnapshotKey = key
            shownSinceMillis = snapshot.atMillis
        }

        val action = ReelHeuristics.decide(
            snapshot = snapshot,
            shownSinceMillis = shownSinceMillis,
            settings = preferences.snapshot()
        )

        if (action == ReelAction.SCROLL_NEXT) {
            val now = snapshot.atMillis
            if (now - lastScrollDispatchMillis >= MIN_MILLIS_BETWEEN_SCROLLS) {
                dispatchScrollGesture()
                lastScrollDispatchMillis = now
                shownSinceMillis = now
            }
        }
    }

    override fun onInterrupt() {
        // Nothing to clean up: no persistent state beyond preferences, which
        // are just SharedPreferences reads.
    }

    private fun buildSnapshot(root: AccessibilityNodeInfo): ReelSnapshot {
        val texts = mutableListOf<String>()
        val ids = mutableListOf<String>()
        collectNodeInfo(root, texts, ids)
        return ReelSnapshot(
            packageName = root.packageName?.toString() ?: "",
            visibleText = texts,
            viewIds = ids,
            atMillis = System.currentTimeMillis()
        )
    }

    private fun collectNodeInfo(
        node: AccessibilityNodeInfo,
        texts: MutableList<String>,
        ids: MutableList<String>,
        depth: Int = 0
    ) {
        if (depth > MAX_TREE_DEPTH) return

        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it.lowercase()) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { texts.add(it.lowercase()) }
        node.viewIdResourceName?.let { ids.add(it) }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectNodeInfo(child, texts, ids, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    private fun dispatchScrollGesture() {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels

        val startY = height * 0.70f
        val endY = height * 0.25f
        val x = width / 2f

        val path = Path().apply {
            moveTo(x, startY)
            lineTo(x, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, GESTURE_DURATION_MILLIS))
            .build()

        val dispatched = dispatchGesture(gesture, null, null)
        if (!dispatched) {
            Log.w(TAG, "Scroll gesture was not dispatched")
        }
    }

    companion object {
        private const val TAG = "VegAutomationService"
        private const val GESTURE_DURATION_MILLIS = 250L

        // Never dispatch two scroll gestures within this window, regardless
        // of what the heuristics decide — guards against a burst of
        // accessibility events (e.g. content changing mid-scroll) causing a
        // double-swipe.
        private const val MIN_MILLIS_BETWEEN_SCROLLS = 800L

        private const val MAX_TREE_DEPTH = 40
    }
}
