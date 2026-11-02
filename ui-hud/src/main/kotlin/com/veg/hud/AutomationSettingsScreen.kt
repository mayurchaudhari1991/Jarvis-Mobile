package com.veg.hud

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.veg.automation.AutomationPreferences
import com.veg.automation.VegAutomationService

/**
 * Lets the user turn Vega's device-automation feature on and off, and see
 * whether the underlying accessibility permission has actually been granted.
 *
 * The grant itself can only happen in system settings — Android does not let
 * an app enable an accessibility service on the user's behalf, and this
 * screen does not try to. It only ever links out to
 * [Settings.ACTION_ACCESSIBILITY_SETTINGS] and reflects back whatever the
 * user decided there.
 */
@Composable
fun AutomationSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val preferences = remember { AutomationPreferences(context) }

    var autoScrollEnabled by remember { mutableStateOf(preferences.autoScrollEnabled) }
    var skipAdsEnabled by remember { mutableStateOf(preferences.skipAdsEnabled) }
    var serviceGranted by remember { mutableStateOf(isAccessibilityServiceEnabled(context)) }

    // The grant happens in a different app (system Settings) and this screen
    // has no callback for it, so re-check whenever the user comes back to
    // Vega rather than only once on first composition.
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                serviceGranted = isAccessibilityServiceEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VegColors.Ground)
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "BACK",
                style = MonoStyle,
                color = VegColors.Cool,
                modifier = Modifier
                    .border(1.dp, VegColors.CoolDeep, RoundedCornerShape(999.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
            Text(
                text = "AUTOMATION",
                style = MonoStyle,
                color = VegColors.Text
            )
        }

        androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 18.dp))

        GrantStatusCard(
            granted = serviceGranted,
            onOpenSettings = {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        )

        androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 18.dp))

        SettingToggleRow(
            title = "Auto-scroll past finished reels",
            subtitle = "Scrolls to the next reel after a video has likely ended.",
            checked = autoScrollEnabled,
            enabled = serviceGranted,
            onCheckedChange = {
                autoScrollEnabled = it
                preferences.autoScrollEnabled = it
            }
        )

        androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 10.dp))

        SettingToggleRow(
            title = "Skip ads",
            subtitle = "Scrolls past posts marked Sponsored or Paid partnership sooner than an ordinary reel.",
            checked = skipAdsEnabled,
            enabled = serviceGranted,
            onCheckedChange = {
                skipAdsEnabled = it
                preferences.skipAdsEnabled = it
            }
        )

        androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 18.dp))

        Text(
            text = "Neither \"video ended\" nor \"this is an ad\" is something the OS reports directly. " +
                "Both are guesses based on how long a reel has been on screen and what text is visible, " +
                "so this will occasionally scroll too early, too late, or skip something that wasn't an ad.",
            color = VegColors.NeutralDim,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun GrantStatusCard(granted: Boolean, onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(VegColors.Panel, RoundedCornerShape(12.dp))
            .border(1.dp, VegColors.Hairline, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text(
            text = if (granted) "PERMISSION GRANTED" else "PERMISSION NOT GRANTED",
            style = MonoStyle,
            color = if (granted) VegColors.Cool else VegColors.Amber
        )
        androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 6.dp))
        Text(
            text = if (granted) {
                "Vega's accessibility service is enabled for Facebook and Instagram."
            } else {
                "Vega needs the accessibility permission to see when a reel ends. " +
                    "This only applies to Facebook and Instagram — Vega does not read any other app."
            },
            color = VegColors.Text,
            fontSize = 13.sp
        )
        if (!granted) {
            androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 10.dp))
            Text(
                text = "OPEN ACCESSIBILITY SETTINGS",
                style = MonoStyle,
                color = VegColors.Ground,
                modifier = Modifier
                    .background(VegColors.Cool, RoundedCornerShape(999.dp))
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(VegColors.Panel, RoundedCornerShape(12.dp))
            .border(1.dp, VegColors.Hairline, RoundedCornerShape(12.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, color = VegColors.Text, fontSize = 14.sp)
            androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 3.dp))
            Text(text = subtitle, color = VegColors.NeutralDim, fontSize = 12.sp)
            if (!enabled) {
                androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 3.dp))
                Text(text = "Grant the accessibility permission above first.", color = VegColors.Amber, fontSize = 11.sp)
            }
        }
        androidx.compose.material3.Switch(
            checked = checked && enabled,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}

/**
 * Whether Android's system settings list Vega's automation service among the
 * user's *enabled* accessibility services — not merely installed. Reads the
 * same secure setting the OS itself uses to drive the accessibility settings
 * UI, since there is no direct API for "is my own service enabled."
 */
private fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedComponent = "${context.packageName}/${VegAutomationService::class.java.name}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val splitter = TextUtils.SimpleStringSplitter(':')
    splitter.setString(enabledServices)
    for (component in splitter) {
        if (component.equals(expectedComponent, ignoreCase = true)) return true
    }
    return false
}
