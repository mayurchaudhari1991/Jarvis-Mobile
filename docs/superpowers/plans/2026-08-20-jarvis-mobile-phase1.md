# JARVIS Mobile Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship an Android app that runs an 8B-class LLM fully on-device and presents it through a telemetry-driven reactor HUD, with no network use beyond a one-time model download.

**Architecture:** Five Gradle modules with strict dependency direction. `core-device` is pure Kotlin over injected readings, so model recommendation is testable with no hardware. `core-inference` wraps llama.cpp through JNI behind an interface with a fake implementation, so all UI work proceeds without loading a 5 GB model. `core-data` owns Room persistence and encrypted export. `ui-hud` composes them.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose (BOM 2024.10.01), Gradle 8.9, AGP 8.7.2, Room 2.6.1, kotlinx-coroutines 1.9.0, OkHttp 4.12.0, llama.cpp via NDK r27c, JUnit 5.11.3, Turbine 1.2.0, Robolectric 4.13.

**Spec:** `docs/superpowers/specs/2026-08-20-jarvis-mobile-assistant-design.md`

## Global Constraints

- **minSdk 30** (Android 11). Set by `PowerManager.getThermalHeadroom`, which runtime adaptation depends on. **targetSdk 35**, **compileSdk 35**.
- **ABI: `arm64-v8a` only.** No x86/x86_64 — emulators cannot run the model usefully and the extra ABIs double APK size.
- **No network permission beyond model download.** `INTERNET` is declared; no analytics, telemetry, crash reporting, or any other outbound call. `core-data` and `core-inference` must not declare or use network APIs.
- **No accounts, no API keys, no cloud inference.** Any task introducing an auth flow or remote LLM call is out of scope.
- **JVM target 17** for all modules.
- **Package root:** `com.jarvis.mobile`
- **Default model:** Qwen3 8B Q4_K_M, ~4.9 GB. Fallbacks: Qwen3 4B (~3.0 GB), Qwen3 1.7B (~1.1 GB).
- **Colors are fixed tokens** (from the approved HUD design): cool `#3FD8E8`, warm `#FF8A2A`, amber `#F2A93B`, ground `#06080B`, panel `#0C1117`, hairline `#1B2530`, text `#DDE6EF`, neutral `#8A97A6`.
- **Single dark theme.** No light theme, no `isSystemInDarkTheme()` branching.
- **Every animated HUD element must be bound to real state.** No decorative motion. This is a review criterion, not a preference.
- **TDD throughout.** Every task writes a failing test first, verifies the failure, then implements.

---

## File Structure

Dependency direction is strict and enforced by Gradle: `app` → `ui-hud` → {`core-inference`, `core-data`, `core-device`}, and `core-inference` → `core-model` → `core-device`. Nothing points back up.

| Module | Files | Responsibility |
|---|---|---|
| `core-device` | `DeviceReadings.kt`, `ModelCandidate.kt`, `ModelRecommender.kt`, `RuntimeTuner.kt`, `AndroidDeviceProbe.kt` | Pure scoring logic plus one thin Android adapter |
| `core-model` | `ModelSpec.kt`, `ModelRepository.kt`, `ModelDownloader.kt`, `Sha256Verifier.kt` | Resumable download, verification, on-disk storage |
| `core-inference` | `InferenceEngine.kt`, `LlamaEngine.kt`, `FakeEngine.kt`, `InferenceConfig.kt`, `llama_jni.cpp`, `CMakeLists.txt` | Token streaming behind a swappable interface |
| `core-data` | `JarvisDatabase.kt`, `Conversation.kt`, `Message.kt`, `MessageDao.kt`, `ConversationDao.kt`, `BackupCodec.kt` | Room persistence plus AES-256 export/import |
| `ui-hud` | `Reactor.kt`, `ReactorState.kt`, `ChatScreen.kt`, `BootScreen.kt`, `TelemetryChips.kt`, `Theme.kt`, `ChatViewModel.kt` | The interface |
| `app` | `MainActivity.kt`, `AndroidManifest.xml` | Entry point and wiring |

Files are split by responsibility, not by layer. `Reactor.kt` holds only drawing; `ReactorState.kt` holds only the state model, so the renderer stays testable against synthetic states.

---

### Task 0: Toolchain and project skeleton

**This machine has no JDK, no Android SDK, and no Android Studio.** Verified during planning: `java` is not on PATH, `ANDROID_HOME` is unset, and `C:\Program Files\Android` is empty. Nothing else in this plan can run until this task completes.

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/jarvis/mobile/MainActivity.kt`

**Interfaces:**
- Consumes: nothing
- Produces: a Gradle project where `./gradlew :app:assembleDebug` succeeds, and the version catalog aliases (`libs.*`) that every later task references

- [ ] **Step 1: Install JDK 17**

Download and install Eclipse Temurin 17 (LTS) from https://adoptium.net. Then verify:

```bash
java -version
```

Expected: `openjdk version "17.x.x"`. If `command not found`, add the JDK `bin` directory to PATH and open a new shell.

- [ ] **Step 2: Install Android Studio and SDK components**

Install Android Studio (Ladybug 2024.2.1 or newer). On first run, open SDK Manager and install:
- Android SDK Platform 35
- Android SDK Build-Tools 35.0.0
- Android SDK Platform-Tools
- NDK 27.2.12479018
- CMake 3.22.1

- [ ] **Step 3: Set environment variables**

In PowerShell:

```powershell
setx ANDROID_HOME "$env:LOCALAPPDATA\Android\Sdk"
setx JAVA_HOME "C:\Program Files\Eclipse Adoptium\jdk-17.0.13.11-hotspot"
```

Open a new shell, then verify:

```bash
echo $ANDROID_HOME && ls "$ANDROID_HOME/platform-tools/adb.exe"
```

Expected: the path prints and `adb.exe` is listed.

- [ ] **Step 4: Verify the phone connects**

Enable Developer Options and USB debugging on the OnePlus 13R, connect by USB, then:

```bash
"$ANDROID_HOME/platform-tools/adb" devices
```

Expected: one device listed with status `device`. If it says `unauthorized`, accept the debugging prompt on the phone and re-run.

- [ ] **Step 5: Create the version catalog**

Create `gradle/libs.versions.toml`:

```toml
[versions]
agp = "8.7.2"
kotlin = "2.0.21"
ksp = "2.0.21-1.0.28"
coreKtx = "1.15.0"
composeBom = "2024.10.01"
activityCompose = "1.9.3"
lifecycle = "2.8.7"
room = "2.6.1"
coroutines = "1.9.0"
okhttp = "4.12.0"
junit4 = "4.13.2"
turbine = "1.2.0"
robolectric = "4.13"
androidxTestCore = "1.6.1"
androidxJunit = "1.2.1"

[libraries]
core-ktx = { module = "androidx.core:core-ktx", version.ref = "coreKtx" }
activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-graphics = { module = "androidx.compose.ui:ui-graphics" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-tooling = { module = "androidx.compose.ui:ui-tooling" }
compose-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
compose-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }
junit = { module = "junit:junit", version.ref = "junit4" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
androidx-test-core = { module = "androidx.test:core", version.ref = "androidxTestCore" }
androidx-junit = { module = "androidx.test.ext:junit", version.ref = "androidxJunit" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 6: Create the root build files**

Create `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "JarvisMobile"
include(":app", ":core-device", ":core-model", ":core-inference", ":core-data", ":ui-hud")
```

Create `build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
```

Create `gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

- [ ] **Step 7: Create the app module**

Create `app/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.jarvis.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jarvis.mobile"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.graphics)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.tooling)
}
```

Create `app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:allowBackup="false"
        android:label="JARVIS"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

Create `app/src/main/java/com/jarvis/mobile/MainActivity.kt`:

```kotlin
package com.jarvis.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("JARVIS") }
    }
}
```

- [ ] **Step 8: Generate the Gradle wrapper and build**

```bash
gradle wrapper --gradle-version 8.9
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. If it fails with an SDK location error, create `local.properties` containing:

```properties
sdk.dir=C\:\\Users\\simpl\\AppData\\Local\\Android\\Sdk
```

- [ ] **Step 9: Install on the phone and confirm it launches**

```bash
./gradlew :app:installDebug
```

Expected: `Installed on 1 device`, and opening the app shows the text "JARVIS".

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "chore: Gradle skeleton, version catalog, and app module

Establishes minSdk 30 / targetSdk 35, arm64-v8a only, JVM target 17.
Verified building and installing on the target device."
```

---

### Task 1: core-device — model recommendation

Pure Kotlin, no Android dependency. Readings are injected, so every threshold is testable against synthetic devices with no hardware.

**Files:**
- Create: `core-device/build.gradle.kts`
- Create: `core-device/src/main/kotlin/com/jarvis/device/DeviceReadings.kt`
- Create: `core-device/src/main/kotlin/com/jarvis/device/ModelCandidate.kt`
- Create: `core-device/src/main/kotlin/com/jarvis/device/ModelRecommender.kt`
- Test: `core-device/src/test/kotlin/com/jarvis/device/ModelRecommenderTest.kt`

**Interfaces:**
- Consumes: version catalog aliases from Task 0
- Produces:
  - `data class DeviceReadings(totalRamBytes: Long, availableRamBytes: Long, perAppMemoryLimitBytes: Long, freeStorageBytes: Long, cpuCores: Int, socModel: String, thermalHeadroom: Float, batteryPercent: Int, isCharging: Boolean)`
  - `data class ModelCandidate(id: String, displayName: String, fileBytes: Long, residentBytes: Long, expectedTokensPerSecond: Float, quality: Int)`
  - `object ModelCatalog { val ALL: List<ModelCandidate> }`
  - `data class Recommendation(best: ModelCandidate?, alternatives: List<ModelCandidate>, reasons: List<String>)`
  - `class ModelRecommender { fun recommend(readings: DeviceReadings): Recommendation }`

- [ ] **Step 1: Create the module build file**

Create `core-device/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(libs.junit)
}

tasks.withType<Test> { useJUnit() }
```

- [ ] **Step 2: Write the failing test**

Create `core-device/src/test/kotlin/com/jarvis/device/ModelRecommenderTest.kt`:

```kotlin
package com.jarvis.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRecommenderTest {

    private val recommender = ModelRecommender()

    private fun readings(
        totalGb: Double,
        availableGb: Double,
        perAppLimitGb: Double = 8.0,
        freeStorageGb: Double = 60.0,
        cores: Int = 8,
        soc: String = "SM8650",
        thermal: Float = 0.2f,
        battery: Int = 80,
        charging: Boolean = false
    ) = DeviceReadings(
        totalRamBytes = (totalGb * 1024 * 1024 * 1024).toLong(),
        availableRamBytes = (availableGb * 1024 * 1024 * 1024).toLong(),
        perAppMemoryLimitBytes = (perAppLimitGb * 1024 * 1024 * 1024).toLong(),
        freeStorageBytes = (freeStorageGb * 1024 * 1024 * 1024).toLong(),
        cpuCores = cores,
        socModel = soc,
        thermalHeadroom = thermal,
        batteryPercent = battery,
        isCharging = charging
    )

    @Test
    fun `flagship with 12GB recommends the 8B model`() {
        val r = recommender.recommend(readings(totalGb = 12.0, availableGb = 7.2))
        assertEquals("qwen3-8b-q4km", r.best?.id)
    }

    @Test
    fun `mid range with 8GB total falls back below 8B`() {
        val r = recommender.recommend(readings(totalGb = 8.0, availableGb = 3.4, perAppLimitGb = 4.0))
        assertTrue(r.best?.id != "qwen3-8b-q4km")
        assertEquals("qwen3-4b-q4km", r.best?.id)
    }

    @Test
    fun `budget device with 4GB gets the smallest model`() {
        val r = recommender.recommend(readings(totalGb = 4.0, availableGb = 1.6, perAppLimitGb = 2.0))
        assertEquals("qwen3-1_7b-q4km", r.best?.id)
    }

    @Test
    fun `insufficient storage excludes models that do not fit on disk`() {
        val r = recommender.recommend(readings(totalGb = 12.0, availableGb = 7.2, freeStorageGb = 3.5))
        assertEquals("qwen3-1_7b-q4km", r.best?.id)
    }

    @Test
    fun `no viable model returns null best with a reason`() {
        val r = recommender.recommend(readings(totalGb = 2.0, availableGb = 0.4, perAppLimitGb = 0.5, freeStorageGb = 0.5))
        assertEquals(null, r.best)
        assertTrue(r.reasons.isNotEmpty())
    }

    @Test
    fun `alternatives exclude the recommended model and stay ordered by quality`() {
        val r = recommender.recommend(readings(totalGb = 12.0, availableGb = 7.2))
        assertTrue(r.alternatives.none { it.id == r.best?.id })
        val qualities = r.alternatives.map { it.quality }
        assertEquals(qualities.sortedDescending(), qualities)
    }

    @Test
    fun `recommendation explains itself`() {
        val r = recommender.recommend(readings(totalGb = 12.0, availableGb = 7.2))
        assertTrue(r.reasons.any { it.contains("RAM", ignoreCase = true) })
    }

    @Test
    fun `headroom factor is applied so a model needs more available RAM than it is resident`() {
        // 8B is 5.5 GB resident; 5.6 GB available is not enough with a 1.35x safety factor
        val r = recommender.recommend(readings(totalGb = 12.0, availableGb = 5.6))
        assertTrue(r.best?.id != "qwen3-8b-q4km")
    }
}
```

- [ ] **Step 3: Run the test and verify it fails**

```bash
./gradlew :core-device:test
```

Expected: FAIL — `Unresolved reference: DeviceReadings`.

- [ ] **Step 4: Write the data models**

Create `core-device/src/main/kotlin/com/jarvis/device/DeviceReadings.kt`:

```kotlin
package com.jarvis.device

/**
 * A snapshot of everything the recommender is allowed to know about the device.
 *
 * Deliberately a plain data class with no Android types: the scoring logic stays
 * pure and testable, and the single Android adapter that fills this in lives
 * apart from it.
 */
data class DeviceReadings(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val perAppMemoryLimitBytes: Long,
    val freeStorageBytes: Long,
    val cpuCores: Int,
    val socModel: String,
    /** 0.0 = cold, 1.0 = throttling now. From PowerManager.getThermalHeadroom. */
    val thermalHeadroom: Float,
    val batteryPercent: Int,
    val isCharging: Boolean
)
```

Create `core-device/src/main/kotlin/com/jarvis/device/ModelCandidate.kt`:

```kotlin
package com.jarvis.device

data class ModelCandidate(
    val id: String,
    val displayName: String,
    /** Size of the GGUF file on disk. */
    val fileBytes: Long,
    /** Peak RAM while loaded, including KV cache. Larger than fileBytes. */
    val residentBytes: Long,
    val expectedTokensPerSecond: Float,
    /** Relative ordering only; higher is better. */
    val quality: Int
)

private const val GB = 1024L * 1024L * 1024L

object ModelCatalog {
    val ALL: List<ModelCandidate> = listOf(
        ModelCandidate(
            id = "qwen3-8b-q4km",
            displayName = "Qwen3 8B",
            fileBytes = (4.9 * GB).toLong(),
            residentBytes = (5.5 * GB).toLong(),
            expectedTokensPerSecond = 10f,
            quality = 30
        ),
        ModelCandidate(
            id = "qwen3-4b-q4km",
            displayName = "Qwen3 4B",
            fileBytes = (3.0 * GB).toLong(),
            residentBytes = (3.4 * GB).toLong(),
            expectedTokensPerSecond = 20f,
            quality = 20
        ),
        ModelCandidate(
            id = "qwen3-1_7b-q4km",
            displayName = "Qwen3 1.7B",
            fileBytes = (1.1 * GB).toLong(),
            residentBytes = (1.4 * GB).toLong(),
            expectedTokensPerSecond = 45f,
            quality = 10
        )
    )
}

data class Recommendation(
    val best: ModelCandidate?,
    val alternatives: List<ModelCandidate>,
    val reasons: List<String>
)
```

- [ ] **Step 5: Write the recommender**

Create `core-device/src/main/kotlin/com/jarvis/device/ModelRecommender.kt`:

```kotlin
package com.jarvis.device

/**
 * Picks the best model the device can actually hold.
 *
 * Thresholds are deliberately conservative: a model that technically fits but
 * then thermally chokes is a worse outcome than a smaller model that runs well.
 */
class ModelRecommender {

    fun recommend(readings: DeviceReadings): Recommendation {
        val reasons = mutableListOf<String>()

        // The app cannot use all available RAM: Android reclaims aggressively and
        // the KV cache grows with context. Require headroom above resident size.
        val ramBudget = minOf(
            (readings.availableRamBytes * AVAILABLE_RAM_USABLE).toLong(),
            readings.perAppMemoryLimitBytes
        )

        val storageBudget = readings.freeStorageBytes - STORAGE_RESERVE_BYTES

        val viable = ModelCatalog.ALL.filter { candidate ->
            val fitsRam = candidate.residentBytes * RAM_SAFETY_FACTOR <= ramBudget
            val fitsDisk = candidate.fileBytes <= storageBudget
            fitsRam && fitsDisk
        }.sortedByDescending { it.quality }

        val best = viable.firstOrNull()

        if (best == null) {
            reasons += "No model fits this device. " +
                "Usable RAM budget ${gb(ramBudget)} GB, free storage ${gb(storageBudget)} GB."
            return Recommendation(best = null, alternatives = emptyList(), reasons = reasons)
        }

        reasons += "RAM: ${gb(readings.availableRamBytes)} GB available of " +
            "${gb(readings.totalRamBytes)} GB, per-app limit ${gb(readings.perAppMemoryLimitBytes)} GB."
        reasons += "Storage: ${gb(readings.freeStorageBytes)} GB free."
        reasons += "${best.displayName} needs ${gb(best.residentBytes)} GB resident, " +
            "expected ~${best.expectedTokensPerSecond.toInt()} tok/s on ${readings.cpuCores} cores."

        if (ModelCatalog.ALL.first().id != best.id) {
            reasons += "Larger models were excluded because they exceed the safe RAM budget."
        }

        return Recommendation(
            best = best,
            alternatives = viable.drop(1),
            reasons = reasons
        )
    }

    private fun gb(bytes: Long): String =
        String.format("%.1f", bytes.toDouble() / (1024 * 1024 * 1024))

    private companion object {
        /** Never plan to use more than this share of currently-available RAM. */
        const val AVAILABLE_RAM_USABLE = 0.90

        /** Resident size must fit this many times over inside the budget. */
        const val RAM_SAFETY_FACTOR = 1.35

        /** Leave this much disk free after the download. */
        const val STORAGE_RESERVE_BYTES = 2L * 1024 * 1024 * 1024
    }
}
```

- [ ] **Step 6: Run the tests and verify they pass**

```bash
./gradlew :core-device:test
```

Expected: PASS, 8 tests.

- [ ] **Step 7: Commit**

```bash
git add core-device settings.gradle.kts
git commit -m "feat(core-device): model recommendation from injected device readings

Pure Kotlin scoring with a 1.35x RAM safety factor and a 2 GB storage
reserve, so a model that would thermally choke is never recommended.
Tested against synthetic profiles from 2 GB budget phones to 12 GB
flagships."
```

---

### Task 2: core-device — runtime tuning

Thermal headroom and battery tune thread count, context size, and batch size during use. They never swap models, because those signals are transient.

**Files:**
- Create: `core-device/src/main/kotlin/com/jarvis/device/RuntimeTuner.kt`
- Test: `core-device/src/test/kotlin/com/jarvis/device/RuntimeTunerTest.kt`

**Interfaces:**
- Consumes: `DeviceReadings` from Task 1
- Produces:
  - `data class RuntimeParams(threadCount: Int, contextTokens: Int, batchSize: Int)`
  - `enum class ThermalState { NOMINAL, FAIR, SEVERE, CRITICAL }`
  - `class RuntimeTuner { fun tune(readings: DeviceReadings): RuntimeParams; fun thermalState(headroom: Float): ThermalState }`

- [ ] **Step 1: Write the failing test**

Create `core-device/src/test/kotlin/com/jarvis/device/RuntimeTunerTest.kt`:

```kotlin
package com.jarvis.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTunerTest {

    private val tuner = RuntimeTuner()

    private fun readings(
        cores: Int = 8,
        thermal: Float = 0.2f,
        battery: Int = 80,
        charging: Boolean = false
    ) = DeviceReadings(
        totalRamBytes = 12L * 1024 * 1024 * 1024,
        availableRamBytes = 7L * 1024 * 1024 * 1024,
        perAppMemoryLimitBytes = 8L * 1024 * 1024 * 1024,
        freeStorageBytes = 60L * 1024 * 1024 * 1024,
        cpuCores = cores,
        socModel = "SM8650",
        thermalHeadroom = thermal,
        batteryPercent = battery,
        isCharging = charging
    )

    @Test
    fun `cool device uses most cores but never all of them`() {
        val p = tuner.tune(readings(cores = 8, thermal = 0.1f))
        assertEquals(6, p.threadCount)
    }

    @Test
    fun `severe thermal drops thread count`() {
        val cool = tuner.tune(readings(cores = 8, thermal = 0.1f))
        val hot = tuner.tune(readings(cores = 8, thermal = 0.85f))
        assertTrue(hot.threadCount < cool.threadCount)
    }

    @Test
    fun `critical thermal drops to a minimum of two threads`() {
        val p = tuner.tune(readings(cores = 8, thermal = 0.99f))
        assertEquals(2, p.threadCount)
    }

    @Test
    fun `low battery unplugged reduces context`() {
        val normal = tuner.tune(readings(battery = 80, charging = false))
        val low = tuner.tune(readings(battery = 10, charging = false))
        assertTrue(low.contextTokens < normal.contextTokens)
    }

    @Test
    fun `low battery while charging does not reduce context`() {
        val normal = tuner.tune(readings(battery = 80, charging = false))
        val lowCharging = tuner.tune(readings(battery = 10, charging = true))
        assertEquals(normal.contextTokens, lowCharging.contextTokens)
    }

    @Test
    fun `thread count never exceeds core count`() {
        val p = tuner.tune(readings(cores = 2, thermal = 0.1f))
        assertTrue(p.threadCount <= 2)
    }

    @Test
    fun `thermal state maps headroom to named bands`() {
        assertEquals(ThermalState.NOMINAL, tuner.thermalState(0.1f))
        assertEquals(ThermalState.FAIR, tuner.thermalState(0.55f))
        assertEquals(ThermalState.SEVERE, tuner.thermalState(0.8f))
        assertEquals(ThermalState.CRITICAL, tuner.thermalState(0.97f))
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
./gradlew :core-device:test --tests "*RuntimeTunerTest*"
```

Expected: FAIL — `Unresolved reference: RuntimeTuner`.

- [ ] **Step 3: Write the tuner**

Create `core-device/src/main/kotlin/com/jarvis/device/RuntimeTuner.kt`:

```kotlin
package com.jarvis.device

data class RuntimeParams(
    val threadCount: Int,
    val contextTokens: Int,
    val batchSize: Int
)

enum class ThermalState { NOMINAL, FAIR, SEVERE, CRITICAL }

/**
 * Adapts inference parameters to transient device conditions.
 *
 * This never changes which model is loaded: thermal and battery readings move
 * minute to minute, and swapping a 5 GB model in response would cost far more
 * than it saves.
 */
class RuntimeTuner {

    fun tune(readings: DeviceReadings): RuntimeParams {
        val state = thermalState(readings.thermalHeadroom)

        // Leave headroom for the UI thread and the OS; using every core makes
        // the phone hotter without generating meaningfully faster.
        val baseThreads = maxOf(1, (readings.cpuCores * 0.75).toInt())

        val threads = when (state) {
            ThermalState.NOMINAL -> baseThreads
            ThermalState.FAIR -> maxOf(MIN_THREADS, baseThreads - 1)
            ThermalState.SEVERE -> maxOf(MIN_THREADS, baseThreads - 2)
            ThermalState.CRITICAL -> MIN_THREADS
        }.coerceAtMost(readings.cpuCores)

        val batterySaving = !readings.isCharging && readings.batteryPercent <= LOW_BATTERY_PERCENT

        val context = if (batterySaving) REDUCED_CONTEXT else FULL_CONTEXT
        val batch = if (batterySaving || state >= ThermalState.SEVERE) REDUCED_BATCH else FULL_BATCH

        return RuntimeParams(
            threadCount = threads,
            contextTokens = context,
            batchSize = batch
        )
    }

    fun thermalState(headroom: Float): ThermalState = when {
        headroom < 0.5f -> ThermalState.NOMINAL
        headroom < 0.75f -> ThermalState.FAIR
        headroom < 0.95f -> ThermalState.SEVERE
        else -> ThermalState.CRITICAL
    }

    private companion object {
        const val MIN_THREADS = 2
        const val LOW_BATTERY_PERCENT = 15
        const val FULL_CONTEXT = 4096
        const val REDUCED_CONTEXT = 2048
        const val FULL_BATCH = 512
        const val REDUCED_BATCH = 128
    }
}
```

- [ ] **Step 4: Run the tests and verify they pass**

```bash
./gradlew :core-device:test
```

Expected: PASS, 15 tests total across both test classes.

- [ ] **Step 5: Commit**

```bash
git add core-device
git commit -m "feat(core-device): runtime tuning from thermal and battery state

Thread count drops with thermal headroom; context and batch shrink on low
battery when unplugged. Never swaps models, since these signals are
transient and a 5 GB reload would cost more than it saves."
```

---

### Task 3: core-device-android — the system probe adapter

The single place that touches Android APIs. Kept in its own module so `core-device` stays pure JVM and fast to test.

**Files:**
- Create: `core-device-android/build.gradle.kts`
- Create: `core-device-android/src/main/AndroidManifest.xml`
- Create: `core-device-android/src/main/kotlin/com/jarvis/device/android/AndroidDeviceProbe.kt`
- Test: `core-device-android/src/test/kotlin/com/jarvis/device/android/AndroidDeviceProbeTest.kt`
- Modify: `settings.gradle.kts` — add `:core-device-android` to the `include(...)` list

**Interfaces:**
- Consumes: `DeviceReadings` from Task 1
- Produces: `class AndroidDeviceProbe(context: Context) { fun read(): DeviceReadings }`

- [ ] **Step 1: Add the module to settings**

In `settings.gradle.kts`, change the include line to:

```kotlin
include(":app", ":core-device", ":core-device-android", ":core-model", ":core-inference", ":core-data", ":ui-hud")
```

- [ ] **Step 2: Create the module build file**

Create `core-device-android/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.jarvis.device.android"
    compileSdk = 35
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(project(":core-device"))
    implementation(libs.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
```

Create `core-device-android/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 3: Write the failing test**

Create `core-device-android/src/test/kotlin/com/jarvis/device/android/AndroidDeviceProbeTest.kt`:

```kotlin
package com.jarvis.device.android

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AndroidDeviceProbeTest {

    private val probe = AndroidDeviceProbe(ApplicationProvider.getApplicationContext())

    @Test
    fun `reads plausible memory values`() {
        val r = probe.read()
        assertTrue("total RAM should be positive", r.totalRamBytes > 0)
        assertTrue("available should not exceed total", r.availableRamBytes <= r.totalRamBytes)
        assertTrue("per-app limit should be positive", r.perAppMemoryLimitBytes > 0)
    }

    @Test
    fun `reads plausible cpu and storage values`() {
        val r = probe.read()
        assertTrue("at least one core", r.cpuCores >= 1)
        assertTrue("storage not negative", r.freeStorageBytes >= 0)
    }

    @Test
    fun `thermal headroom is clamped into zero to one`() {
        val r = probe.read()
        assertTrue("headroom in range, was ${r.thermalHeadroom}", r.thermalHeadroom in 0f..1f)
    }

    @Test
    fun `battery percent is clamped into zero to one hundred`() {
        val r = probe.read()
        assertTrue("battery in range, was ${r.batteryPercent}", r.batteryPercent in 0..100)
    }

    @Test
    fun `soc model is never blank`() {
        val r = probe.read()
        assertTrue(r.socModel.isNotBlank())
    }
}
```

- [ ] **Step 4: Run the test and verify it fails**

```bash
./gradlew :core-device-android:test
```

Expected: FAIL — `Unresolved reference: AndroidDeviceProbe`.

- [ ] **Step 5: Write the probe**

Create `core-device-android/src/main/kotlin/com/jarvis/device/android/AndroidDeviceProbe.kt`:

```kotlin
package com.jarvis.device.android

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import androidx.core.content.getSystemService
import com.jarvis.device.DeviceReadings

/**
 * Fills a [DeviceReadings] from Android system services.
 *
 * Every value here is best-effort: some OEM ROMs return nothing useful for
 * thermal headroom, so each read has an explicit, documented fallback rather
 * than throwing. A wrong-but-conservative reading degrades the recommendation;
 * a crash loses the app.
 */
class AndroidDeviceProbe(private val context: Context) {

    fun read(): DeviceReadings {
        val activityManager = context.getSystemService<ActivityManager>()
        val memInfo = ActivityManager.MemoryInfo().also {
            activityManager?.getMemoryInfo(it)
        }

        // getLargeMemoryClass is in MB and is the real per-app ceiling, which is
        // often far below total RAM.
        val perAppLimitBytes =
            (activityManager?.largeMemoryClass ?: DEFAULT_MEMORY_CLASS_MB).toLong() * 1024L * 1024L

        val freeStorage = try {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: IllegalArgumentException) {
            0L
        }

        return DeviceReadings(
            totalRamBytes = memInfo.totalMem,
            availableRamBytes = memInfo.availMem,
            perAppMemoryLimitBytes = perAppLimitBytes,
            freeStorageBytes = freeStorage,
            cpuCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            socModel = readSocModel(),
            thermalHeadroom = readThermalHeadroom(),
            batteryPercent = readBatteryPercent(),
            isCharging = readIsCharging()
        )
    }

    private fun readSocModel(): String {
        val model = Build.SOC_MODEL
        if (model.isNotBlank() && model != Build.UNKNOWN) return model
        val hardware = Build.HARDWARE
        return if (hardware.isNotBlank()) hardware else "unknown"
    }

    /**
     * Returns 0.0 (cold) to 1.0 (throttling now).
     *
     * getThermalHeadroom returns headroom remaining, so it is inverted here.
     * It also returns NaN when the platform has no recent sample, which is
     * common on the first call and on some OEM ROMs; NaN means "assume nominal"
     * rather than "assume the worst", since the tuner will re-read shortly.
     */
    private fun readThermalHeadroom(): Float {
        val pm = context.getSystemService<PowerManager>() ?: return 0f
        return try {
            val headroom = pm.getThermalHeadroom(THERMAL_FORECAST_SECONDS)
            if (headroom.isNaN()) 0f else (1f - headroom).coerceIn(0f, 1f)
        } catch (e: Exception) {
            0f
        }
    }

    private fun readBatteryPercent(): Int {
        val bm = context.getSystemService<BatteryManager>() ?: return 100
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) level else 100
    }

    private fun readIsCharging(): Boolean {
        val bm = context.getSystemService<BatteryManager>() ?: return false
        return bm.isCharging
    }

    private companion object {
        const val THERMAL_FORECAST_SECONDS = 10
        const val DEFAULT_MEMORY_CLASS_MB = 256
    }
}
```

- [ ] **Step 6: Run the tests and verify they pass**

```bash
./gradlew :core-device-android:test
```

Expected: PASS, 5 tests.

- [ ] **Step 7: Commit**

```bash
git add core-device-android settings.gradle.kts
git commit -m "feat(core-device-android): system probe filling DeviceReadings

Isolates all Android API access so core-device stays pure JVM. Every read
has a documented fallback: NaN thermal headroom means assume nominal, since
the tuner re-reads shortly and a crash is worse than a stale sample."
```

---

### Task 4: core-data — Room persistence

**Files:**
- Create: `core-data/build.gradle.kts`, `core-data/src/main/AndroidManifest.xml`
- Create: `core-data/src/main/kotlin/com/jarvis/data/Conversation.kt`
- Create: `core-data/src/main/kotlin/com/jarvis/data/Message.kt`
- Create: `core-data/src/main/kotlin/com/jarvis/data/ConversationDao.kt`
- Create: `core-data/src/main/kotlin/com/jarvis/data/MessageDao.kt`
- Create: `core-data/src/main/kotlin/com/jarvis/data/JarvisDatabase.kt`
- Test: `core-data/src/test/kotlin/com/jarvis/data/JarvisDatabaseTest.kt`

**Interfaces:**
- Consumes: version catalog aliases from Task 0
- Produces:
  - `@Entity data class Conversation(id: Long, title: String, createdAt: Long, updatedAt: Long)`
  - `@Entity data class Message(id: Long, conversationId: Long, role: String, content: String, createdAt: Long, tokensPerSecond: Float?)`
  - `interface ConversationDao { suspend fun insert(c: Conversation): Long; fun observeAll(): Flow<List<Conversation>>; suspend fun byId(id: Long): Conversation?; suspend fun touch(id: Long, at: Long); suspend fun delete(id: Long) }`
  - `interface MessageDao { suspend fun insert(m: Message): Long; fun observeForConversation(conversationId: Long): Flow<List<Message>>; suspend fun forConversation(conversationId: Long): List<Message>; suspend fun deleteForConversation(conversationId: Long) }`
  - `abstract class JarvisDatabase : RoomDatabase { abstract fun conversations(): ConversationDao; abstract fun messages(): MessageDao }`
  - Role constants: `Message.ROLE_USER = "user"`, `Message.ROLE_ASSISTANT = "assistant"`

- [ ] **Step 1: Create the module build file**

Create `core-data/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.jarvis.data"
    compileSdk = 35
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.coroutines.core)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
}
```

Create `core-data/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 2: Write the failing test**

Create `core-data/src/test/kotlin/com/jarvis/data/JarvisDatabaseTest.kt`:

```kotlin
package com.jarvis.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class JarvisDatabaseTest {

    private lateinit var db: JarvisDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            JarvisDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `inserting a conversation returns a usable id`() = runTest {
        val id = db.conversations().insert(
            Conversation(title = "First", createdAt = 1000, updatedAt = 1000)
        )
        assertEquals("First", db.conversations().byId(id)?.title)
    }

    @Test
    fun `messages round trip in insertion order`() = runTest {
        val convId = db.conversations().insert(
            Conversation(title = "Chat", createdAt = 1000, updatedAt = 1000)
        )
        db.messages().insert(
            Message(conversationId = convId, role = Message.ROLE_USER, content = "hello", createdAt = 1001)
        )
        db.messages().insert(
            Message(
                conversationId = convId,
                role = Message.ROLE_ASSISTANT,
                content = "hi there",
                createdAt = 1002,
                tokensPerSecond = 10.4f
            )
        )

        val messages = db.messages().forConversation(convId)
        assertEquals(2, messages.size)
        assertEquals("hello", messages[0].content)
        assertEquals(Message.ROLE_ASSISTANT, messages[1].role)
        assertEquals(10.4f, messages[1].tokensPerSecond!!, 0.001f)
    }

    @Test
    fun `observing messages emits on insert`() = runTest {
        val convId = db.conversations().insert(
            Conversation(title = "Chat", createdAt = 1000, updatedAt = 1000)
        )

        db.messages().observeForConversation(convId).test {
            assertEquals(0, awaitItem().size)

            db.messages().insert(
                Message(conversationId = convId, role = Message.ROLE_USER, content = "ping", createdAt = 1001)
            )
            assertEquals(1, awaitItem().size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleting a conversation cascades to its messages`() = runTest {
        val convId = db.conversations().insert(
            Conversation(title = "Chat", createdAt = 1000, updatedAt = 1000)
        )
        db.messages().insert(
            Message(conversationId = convId, role = Message.ROLE_USER, content = "x", createdAt = 1001)
        )

        db.conversations().delete(convId)

        assertNull(db.conversations().byId(convId))
        assertEquals(0, db.messages().forConversation(convId).size)
    }

    @Test
    fun `touch updates the conversation timestamp`() = runTest {
        val convId = db.conversations().insert(
            Conversation(title = "Chat", createdAt = 1000, updatedAt = 1000)
        )
        db.conversations().touch(convId, 5000)
        assertEquals(5000, db.conversations().byId(convId)?.updatedAt)
    }

    @Test
    fun `conversations are observed newest first`() = runTest {
        db.conversations().insert(Conversation(title = "Older", createdAt = 1000, updatedAt = 1000))
        db.conversations().insert(Conversation(title = "Newer", createdAt = 2000, updatedAt = 2000))

        db.conversations().observeAll().test {
            val list = awaitItem()
            assertEquals("Newer", list[0].title)
            assertEquals("Older", list[1].title)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 3: Run the test and verify it fails**

```bash
./gradlew :core-data:test
```

Expected: FAIL — `Unresolved reference: JarvisDatabase`.

- [ ] **Step 4: Write the entities**

Create `core-data/src/main/kotlin/com/jarvis/data/Conversation.kt`:

```kotlin
package com.jarvis.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)
```

Create `core-data/src/main/kotlin/com/jarvis/data/Message.kt`:

```kotlin
package com.jarvis.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = Conversation::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversationId")]
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val role: String,
    val content: String,
    val createdAt: Long,
    /** Measured throughput for assistant messages; null for user messages. */
    val tokensPerSecond: Float? = null
) {
    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
    }
}
```

- [ ] **Step 5: Write the DAOs and database**

Create `core-data/src/main/kotlin/com/jarvis/data/ConversationDao.kt`:

```kotlin
package com.jarvis.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Insert
    suspend fun insert(conversation: Conversation): Long

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun byId(id: Long): Conversation?

    @Query("UPDATE conversations SET updatedAt = :at WHERE id = :id")
    suspend fun touch(id: Long, at: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)
}
```

Create `core-data/src/main/kotlin/com/jarvis/data/MessageDao.kt`:

```kotlin
package com.jarvis.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert
    suspend fun insert(message: Message): Long

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun observeForConversation(conversationId: Long): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    suspend fun forConversation(conversationId: Long): List<Message>

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: Long)
}
```

Create `core-data/src/main/kotlin/com/jarvis/data/JarvisDatabase.kt`:

```kotlin
package com.jarvis.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [Conversation::class, Message::class],
    version = 1,
    exportSchema = false
)
abstract class JarvisDatabase : RoomDatabase() {

    abstract fun conversations(): ConversationDao
    abstract fun messages(): MessageDao

    companion object {
        fun open(context: Context): JarvisDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                JarvisDatabase::class.java,
                "jarvis.db"
            )
                // Foreign keys are off by default in SQLite; the cascade delete
                // on messages depends on them being enabled per connection.
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        db.execSQL("PRAGMA foreign_keys=ON;")
                    }
                })
                .build()
    }
}
```

- [ ] **Step 6: Enable foreign keys in the in-memory test database**

Room's `inMemoryDatabaseBuilder` does not run the `open` companion, so the cascade test needs the same pragma. In `JarvisDatabaseTest.setUp`, replace the builder with:

```kotlin
db = Room.inMemoryDatabaseBuilder(
    ApplicationProvider.getApplicationContext(),
    JarvisDatabase::class.java
)
    .allowMainThreadQueries()
    .addCallback(object : androidx.room.RoomDatabase.Callback() {
        override fun onOpen(sqlDb: androidx.sqlite.db.SupportSQLiteDatabase) {
            sqlDb.execSQL("PRAGMA foreign_keys=ON;")
        }
    })
    .build()
```

- [ ] **Step 7: Run the tests and verify they pass**

```bash
./gradlew :core-data:test
```

Expected: PASS, 6 tests.

- [ ] **Step 8: Commit**

```bash
git add core-data
git commit -m "feat(core-data): Room schema for conversations and messages

Cascade delete from conversations to messages, with foreign keys enabled
explicitly on open since SQLite disables them per connection by default.
Assistant messages carry measured tok/s for the HUD."
```

---

### Task 5: core-data — encrypted export and import

Manual export is the only backup path in this design, so the round trip must be exact and the crypto must be correct.

**Files:**
- Create: `core-data/src/main/kotlin/com/jarvis/data/BackupCodec.kt`
- Create: `core-data/src/main/kotlin/com/jarvis/data/BackupRepository.kt`
- Test: `core-data/src/test/kotlin/com/jarvis/data/BackupCodecTest.kt`
- Test: `core-data/src/test/kotlin/com/jarvis/data/BackupRepositoryTest.kt`

**Interfaces:**
- Consumes: `JarvisDatabase`, `Conversation`, `Message` from Task 4
- Produces:
  - `data class BackupPayload(conversations: List<Conversation>, messages: List<Message>)`
  - `object BackupCodec { fun encrypt(payload: BackupPayload, passphrase: CharArray): ByteArray; fun decrypt(blob: ByteArray, passphrase: CharArray): BackupPayload }`
  - `class WrongPassphraseException : Exception`
  - `class CorruptBackupException(message: String) : Exception`
  - `class BackupRepository(db: JarvisDatabase) { suspend fun export(passphrase: CharArray): ByteArray; suspend fun import(blob: ByteArray, passphrase: CharArray) }`

- [ ] **Step 1: Write the failing codec test**

Create `core-data/src/test/kotlin/com/jarvis/data/BackupCodecTest.kt`:

```kotlin
package com.jarvis.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class BackupCodecTest {

    private val payload = BackupPayload(
        conversations = listOf(
            Conversation(id = 1, title = "Planning", createdAt = 1000, updatedAt = 2000)
        ),
        messages = listOf(
            Message(id = 1, conversationId = 1, role = Message.ROLE_USER, content = "hello", createdAt = 1001),
            Message(
                id = 2,
                conversationId = 1,
                role = Message.ROLE_ASSISTANT,
                content = "hi \"there\", newline:\nand unicode: नमस्ते",
                createdAt = 1002,
                tokensPerSecond = 9.75f
            )
        )
    )

    @Test
    fun `round trips to an identical payload`() {
        val blob = BackupCodec.encrypt(payload, "correct horse".toCharArray())
        val restored = BackupCodec.decrypt(blob, "correct horse".toCharArray())
        assertEquals(payload, restored)
    }

    @Test
    fun `wrong passphrase is rejected`() {
        val blob = BackupCodec.encrypt(payload, "correct horse".toCharArray())
        assertThrows(WrongPassphraseException::class.java) {
            BackupCodec.decrypt(blob, "wrong horse".toCharArray())
        }
    }

    @Test
    fun `same payload encrypts differently each time`() {
        val a = BackupCodec.encrypt(payload, "pass".toCharArray())
        val b = BackupCodec.encrypt(payload, "pass".toCharArray())
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `plaintext does not appear in the blob`() {
        val blob = BackupCodec.encrypt(payload, "pass".toCharArray())
        val asText = String(blob, Charsets.ISO_8859_1)
        assertTrue("conversation title leaked", !asText.contains("Planning"))
        assertTrue("message content leaked", !asText.contains("hello"))
    }

    @Test
    fun `truncated blob is reported as corrupt`() {
        val blob = BackupCodec.encrypt(payload, "pass".toCharArray())
        assertThrows(CorruptBackupException::class.java) {
            BackupCodec.decrypt(blob.copyOf(12), "pass".toCharArray())
        }
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val blob = BackupCodec.encrypt(payload, "pass".toCharArray())
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte()
        assertThrows(WrongPassphraseException::class.java) {
            BackupCodec.decrypt(blob, "pass".toCharArray())
        }
    }

    @Test
    fun `unknown format version is reported as corrupt`() {
        val blob = BackupCodec.encrypt(payload, "pass".toCharArray())
        blob[4] = 99
        assertThrows(CorruptBackupException::class.java) {
            BackupCodec.decrypt(blob, "pass".toCharArray())
        }
    }

    @Test
    fun `empty payload round trips`() {
        val empty = BackupPayload(emptyList(), emptyList())
        val blob = BackupCodec.encrypt(empty, "pass".toCharArray())
        assertEquals(empty, BackupCodec.decrypt(blob, "pass".toCharArray()))
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
./gradlew :core-data:test --tests "*BackupCodecTest*"
```

Expected: FAIL — `Unresolved reference: BackupCodec`.

- [ ] **Step 3: Write the codec**

Create `core-data/src/main/kotlin/com/jarvis/data/BackupCodec.kt`:

```kotlin
package com.jarvis.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class BackupPayload(
    val conversations: List<Conversation>,
    val messages: List<Message>
)

class WrongPassphraseException : Exception("Wrong passphrase, or the file has been modified")
class CorruptBackupException(message: String) : Exception(message)

/**
 * Encrypts a backup with AES-256-GCM using a PBKDF2-derived key.
 *
 * File layout, all big-endian:
 *   magic  4 bytes  "JRVS"
 *   version 1 byte  currently 1
 *   salt   16 bytes
 *   iv     12 bytes
 *   ciphertext + GCM tag
 *
 * GCM is authenticated, so a wrong passphrase and a tampered file both surface
 * as an authentication failure rather than silently decoding to garbage.
 */
object BackupCodec {

    private val MAGIC = byteArrayOf('J'.code.toByte(), 'R'.code.toByte(), 'V'.code.toByte(), 'S'.code.toByte())
    private const val VERSION: Byte = 1
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    private const val PBKDF2_ITERATIONS = 210_000
    private const val HEADER_BYTES = 4 + 1 + SALT_BYTES + IV_BYTES

    fun encrypt(payload: BackupPayload, passphrase: CharArray): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { random.nextBytes(it) }

        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))

        val ciphertext = cipher.doFinal(toJson(payload).toByteArray(Charsets.UTF_8))

        return MAGIC + byteArrayOf(VERSION) + salt + iv + ciphertext
    }

    fun decrypt(blob: ByteArray, passphrase: CharArray): BackupPayload {
        if (blob.size <= HEADER_BYTES) {
            throw CorruptBackupException("File is too short to be a backup")
        }
        if (!blob.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw CorruptBackupException("Not a JARVIS backup file")
        }
        if (blob[4] != VERSION) {
            throw CorruptBackupException("Backup format version ${blob[4]} is not supported")
        }

        val salt = blob.copyOfRange(5, 5 + SALT_BYTES)
        val iv = blob.copyOfRange(5 + SALT_BYTES, HEADER_BYTES)
        val ciphertext = blob.copyOfRange(HEADER_BYTES, blob.size)

        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))

        val plaintext = try {
            cipher.doFinal(ciphertext)
        } catch (e: AEADBadTagException) {
            throw WrongPassphraseException()
        } catch (e: javax.crypto.BadPaddingException) {
            throw WrongPassphraseException()
        }

        return fromJson(String(plaintext, Charsets.UTF_8))
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun toJson(payload: BackupPayload): String {
        val conversations = JSONArray()
        for (c in payload.conversations) {
            conversations.put(
                JSONObject()
                    .put("id", c.id)
                    .put("title", c.title)
                    .put("createdAt", c.createdAt)
                    .put("updatedAt", c.updatedAt)
            )
        }

        val messages = JSONArray()
        for (m in payload.messages) {
            val obj = JSONObject()
                .put("id", m.id)
                .put("conversationId", m.conversationId)
                .put("role", m.role)
                .put("content", m.content)
                .put("createdAt", m.createdAt)
            if (m.tokensPerSecond != null) obj.put("tokensPerSecond", m.tokensPerSecond.toDouble())
            messages.put(obj)
        }

        return JSONObject()
            .put("conversations", conversations)
            .put("messages", messages)
            .toString()
    }

    private fun fromJson(json: String): BackupPayload {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw CorruptBackupException("Backup contents are not valid JSON")
        }

        val conversationsJson = root.getJSONArray("conversations")
        val conversations = (0 until conversationsJson.length()).map { i ->
            val o = conversationsJson.getJSONObject(i)
            Conversation(
                id = o.getLong("id"),
                title = o.getString("title"),
                createdAt = o.getLong("createdAt"),
                updatedAt = o.getLong("updatedAt")
            )
        }

        val messagesJson = root.getJSONArray("messages")
        val messages = (0 until messagesJson.length()).map { i ->
            val o = messagesJson.getJSONObject(i)
            Message(
                id = o.getLong("id"),
                conversationId = o.getLong("conversationId"),
                role = o.getString("role"),
                content = o.getString("content"),
                createdAt = o.getLong("createdAt"),
                tokensPerSecond = if (o.has("tokensPerSecond")) o.getDouble("tokensPerSecond").toFloat() else null
            )
        }

        return BackupPayload(conversations, messages)
    }
}
```

- [ ] **Step 4: Run the codec tests and verify they pass**

```bash
./gradlew :core-data:test --tests "*BackupCodecTest*"
```

Expected: PASS, 8 tests.

- [ ] **Step 5: Write the failing repository test**

Create `core-data/src/test/kotlin/com/jarvis/data/BackupRepositoryTest.kt`:

```kotlin
package com.jarvis.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class BackupRepositoryTest {

    private lateinit var db: JarvisDatabase
    private lateinit var repo: BackupRepository

    private fun newDb(): JarvisDatabase =
        Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            JarvisDatabase::class.java
        )
            .allowMainThreadQueries()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(sqlDb: SupportSQLiteDatabase) {
                    sqlDb.execSQL("PRAGMA foreign_keys=ON;")
                }
            })
            .build()

    @Before
    fun setUp() {
        db = newDb()
        repo = BackupRepository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `export then import into a fresh database restores everything`() = runTest {
        val convId = db.conversations().insert(
            Conversation(title = "Planning", createdAt = 1000, updatedAt = 2000)
        )
        db.messages().insert(
            Message(conversationId = convId, role = Message.ROLE_USER, content = "hello", createdAt = 1001)
        )
        db.messages().insert(
            Message(
                conversationId = convId,
                role = Message.ROLE_ASSISTANT,
                content = "hi",
                createdAt = 1002,
                tokensPerSecond = 10f
            )
        )

        val blob = repo.export("pass".toCharArray())

        val fresh = newDb()
        try {
            BackupRepository(fresh).import(blob, "pass".toCharArray())

            val conversations = fresh.conversations().observeAllOnce()
            assertEquals(1, conversations.size)
            assertEquals("Planning", conversations[0].title)

            val messages = fresh.messages().forConversation(conversations[0].id)
            assertEquals(2, messages.size)
            assertEquals("hello", messages[0].content)
            assertEquals(10f, messages[1].tokensPerSecond!!, 0.001f)
        } finally {
            fresh.close()
        }
    }

    @Test
    fun `import replaces existing content rather than merging`() = runTest {
        db.conversations().insert(Conversation(title = "Old", createdAt = 1, updatedAt = 1))
        val blob = BackupCodec.encrypt(
            BackupPayload(
                conversations = listOf(Conversation(id = 42, title = "Restored", createdAt = 9, updatedAt = 9)),
                messages = emptyList()
            ),
            "pass".toCharArray()
        )

        repo.import(blob, "pass".toCharArray())

        val conversations = db.conversations().observeAllOnce()
        assertEquals(1, conversations.size)
        assertEquals("Restored", conversations[0].title)
    }
}
```

Note: `observeAllOnce()` is a test-only helper added in the next step.

- [ ] **Step 6: Add the DAO helper and write the repository**

Add to `ConversationDao`:

```kotlin
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    suspend fun observeAllOnce(): List<Conversation>

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
```

Create `core-data/src/main/kotlin/com/jarvis/data/BackupRepository.kt`:

```kotlin
package com.jarvis.data

import androidx.room.withTransaction

/**
 * Reads and writes the whole database as one encrypted blob.
 *
 * Import replaces rather than merges: a backup is a snapshot of a point in
 * time, and merging two histories would produce a conversation that never
 * happened. The replace runs in a transaction so a failure part-way leaves the
 * existing data intact.
 */
class BackupRepository(private val db: JarvisDatabase) {

    suspend fun export(passphrase: CharArray): ByteArray {
        val conversations = db.conversations().observeAllOnce()
        val messages = conversations.flatMap { db.messages().forConversation(it.id) }
        return BackupCodec.encrypt(BackupPayload(conversations, messages), passphrase)
    }

    suspend fun import(blob: ByteArray, passphrase: CharArray) {
        val payload = BackupCodec.decrypt(blob, passphrase)
        db.withTransaction {
            // Messages cascade from conversations, so deleting conversations is enough.
            db.conversations().deleteAll()
            for (c in payload.conversations) db.conversations().insert(c)
            for (m in payload.messages) db.messages().insert(m)
        }
    }
}
```

- [ ] **Step 7: Run the tests and verify they pass**

```bash
./gradlew :core-data:test
```

Expected: PASS, 16 tests across the three test classes.

- [ ] **Step 8: Commit**

```bash
git add core-data
git commit -m "feat(core-data): AES-256-GCM encrypted backup export and import

PBKDF2-SHA256 at 210k iterations, random salt and IV per export. GCM
authentication means a wrong passphrase and a tampered file both fail
loudly instead of decoding to garbage. Import replaces in a transaction
rather than merging, since a backup is a point-in-time snapshot."
```

---

### Task 6: core-model — resumable download and verification

A 4.9 GB download over mobile will be interrupted. Resume and verification are the feature, not an extra.

**Files:**
- Create: `core-model/build.gradle.kts`, `core-model/src/main/AndroidManifest.xml`
- Create: `core-model/src/main/kotlin/com/jarvis/model/ModelSpec.kt`
- Create: `core-model/src/main/kotlin/com/jarvis/model/Sha256Verifier.kt`
- Create: `core-model/src/main/kotlin/com/jarvis/model/ModelDownloader.kt`
- Create: `core-model/src/main/kotlin/com/jarvis/model/ModelRepository.kt`
- Test: `core-model/src/test/kotlin/com/jarvis/model/ModelDownloaderTest.kt`
- Test: `core-model/src/test/kotlin/com/jarvis/model/ModelRepositoryTest.kt`
- Modify: `settings.gradle.kts` — `:core-model` is already included from Task 0

**Interfaces:**
- Consumes: `ModelCandidate` from Task 1
- Produces:
  - `data class ModelSpec(id: String, url: String, sha256: String, sizeBytes: Long)`
  - `object ModelRegistry { fun specFor(candidateId: String): ModelSpec? }`
  - `sealed interface DownloadProgress { data class Running(bytesDownloaded: Long, totalBytes: Long) : DownloadProgress; data object Verifying : DownloadProgress; data class Done(file: File) : DownloadProgress; data class Failed(reason: String, canRetry: Boolean) : DownloadProgress }`
  - `class ModelDownloader(client: OkHttpClient, targetDir: File) { fun download(spec: ModelSpec): Flow<DownloadProgress> }`
  - `class ModelRepository(targetDir: File) { fun installedFile(id: String): File?; fun isInstalled(id: String): Boolean; fun delete(id: String) }`
  - `object Sha256Verifier { fun hexDigest(file: File): String }`

- [ ] **Step 1: Create the module build file**

Create `core-model/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.jarvis.model"
    compileSdk = 35
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(project(":core-device"))
    implementation(libs.okhttp)
    implementation(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
}
```

Create `core-model/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest>
    <uses-permission android:name="android.permission.INTERNET" />
</manifest>
```

- [ ] **Step 2: Write the failing downloader test**

Create `core-model/src/test/kotlin/com/jarvis/model/ModelDownloaderTest.kt`:

```kotlin
package com.jarvis.model

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class ModelDownloaderTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    private val body = ByteArray(4096) { (it % 251).toByte() }
    private val bodySha = MessageDigest.getInstance("SHA-256")
        .digest(body)
        .joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = OkHttpClient.Builder().build()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun spec() = ModelSpec(
        id = "test-model",
        url = server.url("/model.gguf").toString(),
        sha256 = bodySha,
        sizeBytes = body.size.toLong()
    )

    @Test
    fun `successful download emits progress then done`() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(body)))

        val downloader = ModelDownloader(client, temp.root)
        val emissions = mutableListOf<DownloadProgress>()

        downloader.download(spec()).test(timeoutMs = 30_000) {
            var item = awaitItem()
            while (item !is DownloadProgress.Done && item !is DownloadProgress.Failed) {
                emissions += item
                item = awaitItem()
            }
            emissions += item
            awaitComplete()
        }

        val last = emissions.last()
        assertTrue("expected Done, got $last", last is DownloadProgress.Done)
        assertTrue(emissions.any { it is DownloadProgress.Running })
        assertTrue(emissions.any { it is DownloadProgress.Verifying })
        assertEquals(body.size.toLong(), (last as DownloadProgress.Done).file.length())
    }

    @Test
    fun `checksum mismatch fails and deletes the partial file`() = runTest {
        server.enqueue(MockResponse().setBody(Buffer().write(body)))

        val badSpec = spec().copy(sha256 = "0".repeat(64))
        val downloader = ModelDownloader(client, temp.root)

        var last: DownloadProgress? = null
        downloader.download(badSpec).test(timeoutMs = 30_000) {
            var item = awaitItem()
            while (item !is DownloadProgress.Done && item !is DownloadProgress.Failed) {
                item = awaitItem()
            }
            last = item
            awaitComplete()
        }

        assertTrue("expected Failed, got $last", last is DownloadProgress.Failed)
        assertTrue(temp.root.listFiles().orEmpty().none { it.name.endsWith(".gguf") })
    }

    @Test
    fun `resume sends a range header when a partial file exists`() = runTest {
        val half = body.size / 2
        val partial = java.io.File(temp.root, "test-model.gguf.part")
        partial.writeBytes(body.copyOfRange(0, half))

        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader("Content-Range", "bytes $half-${body.size - 1}/${body.size}")
                .setBody(Buffer().write(body.copyOfRange(half, body.size)))
        )

        val downloader = ModelDownloader(client, temp.root)
        downloader.download(spec()).test(timeoutMs = 30_000) {
            var item = awaitItem()
            while (item !is DownloadProgress.Done && item !is DownloadProgress.Failed) {
                item = awaitItem()
            }
            assertTrue("expected Done, got $item", item is DownloadProgress.Done)
            awaitComplete()
        }

        val request = server.takeRequest()
        assertEquals("bytes=$half-", request.getHeader("Range"))
    }

    @Test
    fun `server error is reported as retryable`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))

        val downloader = ModelDownloader(client, temp.root)
        var last: DownloadProgress? = null
        downloader.download(spec()).test(timeoutMs = 30_000) {
            var item = awaitItem()
            while (item !is DownloadProgress.Done && item !is DownloadProgress.Failed) {
                item = awaitItem()
            }
            last = item
            awaitComplete()
        }

        val failed = last as DownloadProgress.Failed
        assertTrue(failed.canRetry)
    }

    @Test
    fun `already complete file skips the network entirely`() = runTest {
        val done = java.io.File(temp.root, "test-model.gguf")
        done.writeBytes(body)

        val downloader = ModelDownloader(client, temp.root)
        downloader.download(spec()).test(timeoutMs = 30_000) {
            var item = awaitItem()
            while (item !is DownloadProgress.Done && item !is DownloadProgress.Failed) {
                item = awaitItem()
            }
            assertTrue(item is DownloadProgress.Done)
            awaitComplete()
        }

        assertEquals(0, server.requestCount)
    }
}
```

- [ ] **Step 3: Run the test and verify it fails**

```bash
./gradlew :core-model:test --tests "*ModelDownloaderTest*"
```

Expected: FAIL — `Unresolved reference: ModelSpec`.

- [ ] **Step 4: Write the spec and verifier**

Create `core-model/src/main/kotlin/com/jarvis/model/ModelSpec.kt`:

```kotlin
package com.jarvis.model

data class ModelSpec(
    val id: String,
    val url: String,
    /** Lowercase hex SHA-256 of the complete file. */
    val sha256: String,
    val sizeBytes: Long
)

/**
 * Download sources for each catalog entry.
 *
 * The SHA-256 values below are placeholders until Task 12 pins them against the
 * real published files. Task 12 replaces them and is a hard gate before release:
 * an unverified model file is an arbitrary-code-execution risk.
 */
object ModelRegistry {

    private val SPECS = listOf(
        ModelSpec(
            id = "qwen3-8b-q4km",
            url = "https://huggingface.co/Qwen/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf",
            sha256 = "PIN_IN_TASK_12",
            sizeBytes = 4_920_000_000L
        ),
        ModelSpec(
            id = "qwen3-4b-q4km",
            url = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
            sha256 = "PIN_IN_TASK_12",
            sizeBytes = 2_500_000_000L
        ),
        ModelSpec(
            id = "qwen3-1_7b-q4km",
            url = "https://huggingface.co/Qwen/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
            sha256 = "PIN_IN_TASK_12",
            sizeBytes = 1_100_000_000L
        )
    )

    fun specFor(candidateId: String): ModelSpec? = SPECS.firstOrNull { it.id == candidateId }
}
```

Create `core-model/src/main/kotlin/com/jarvis/model/Sha256Verifier.kt`:

```kotlin
package com.jarvis.model

import java.io.File
import java.security.MessageDigest

object Sha256Verifier {

    /** Streams the file so a 5 GB model never lands in memory. */
    fun hexDigest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 5: Write the downloader**

Create `core-model/src/main/kotlin/com/jarvis/model/ModelDownloader.kt`:

```kotlin
package com.jarvis.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

sealed interface DownloadProgress {
    data class Running(val bytesDownloaded: Long, val totalBytes: Long) : DownloadProgress
    data object Verifying : DownloadProgress
    data class Done(val file: File) : DownloadProgress
    data class Failed(val reason: String, val canRetry: Boolean) : DownloadProgress
}

/**
 * Downloads a model file with resume support and mandatory checksum verification.
 *
 * A multi-gigabyte download over mobile will be interrupted, so partial data is
 * kept in a .part file and continued with a Range request. The file is only
 * promoted to its final name after the checksum matches, which means a partial
 * or corrupt file can never be mistaken for an installed model.
 */
class ModelDownloader(
    private val client: OkHttpClient,
    private val targetDir: File
) {

    fun download(spec: ModelSpec): Flow<DownloadProgress> = flow {
        val finalFile = File(targetDir, "${spec.id}.gguf")
        val partFile = File(targetDir, "${spec.id}.gguf.part")

        if (!targetDir.exists()) targetDir.mkdirs()

        if (finalFile.exists() && finalFile.length() == spec.sizeBytes) {
            emit(DownloadProgress.Done(finalFile))
            return@flow
        }

        val existingBytes = if (partFile.exists()) partFile.length() else 0L

        val request = Request.Builder()
            .url(spec.url)
            .apply { if (existingBytes > 0) header("Range", "bytes=$existingBytes-") }
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    emit(
                        DownloadProgress.Failed(
                            reason = "Server returned ${response.code}",
                            canRetry = response.code >= 500 || response.code == 429
                        )
                    )
                    return@flow
                }

                // A 200 to a Range request means the server ignored it; start over
                // rather than appending fresh bytes onto stale ones.
                val appending = existingBytes > 0 && response.code == 206
                if (!appending && partFile.exists()) partFile.delete()

                val bodyBytes = response.body?.contentLength() ?: -1L
                val total = if (bodyBytes > 0) {
                    (if (appending) existingBytes else 0L) + bodyBytes
                } else {
                    spec.sizeBytes
                }

                var written = if (appending) existingBytes else 0L
                var lastEmitted = 0L

                response.body?.byteStream()?.use { input ->
                    java.io.FileOutputStream(partFile, appending).use { output ->
                        val buffer = ByteArray(1 shl 16)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            written += read

                            // Emitting on every 64 KB chunk would flood the UI;
                            // once per megabyte is smooth at this file size.
                            if (written - lastEmitted >= EMIT_INTERVAL_BYTES) {
                                lastEmitted = written
                                emit(DownloadProgress.Running(written, total))
                            }
                        }
                    }
                } ?: run {
                    emit(DownloadProgress.Failed("Empty response body", canRetry = true))
                    return@flow
                }

                emit(DownloadProgress.Running(written, total))
            }
        } catch (e: IOException) {
            emit(DownloadProgress.Failed(e.message ?: "Network error", canRetry = true))
            return@flow
        }

        emit(DownloadProgress.Verifying)

        val actual = Sha256Verifier.hexDigest(partFile)
        if (!actual.equals(spec.sha256, ignoreCase = true)) {
            partFile.delete()
            emit(
                DownloadProgress.Failed(
                    reason = "Downloaded file failed verification and was discarded",
                    canRetry = true
                )
            )
            return@flow
        }

        if (!partFile.renameTo(finalFile)) {
            emit(DownloadProgress.Failed("Could not finalise the downloaded file", canRetry = true))
            return@flow
        }

        emit(DownloadProgress.Done(finalFile))
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val EMIT_INTERVAL_BYTES = 1L * 1024 * 1024
    }
}
```

- [ ] **Step 6: Run the downloader tests and verify they pass**

```bash
./gradlew :core-model:test --tests "*ModelDownloaderTest*"
```

Expected: PASS, 5 tests.

Note: the "already complete file" test writes a 4096-byte file while `spec.sizeBytes` is 4096, so the short-circuit matches. The checksum test relies on the `.part` file being deleted on mismatch.

- [ ] **Step 7: Write the failing repository test**

Create `core-model/src/test/kotlin/com/jarvis/model/ModelRepositoryTest.kt`:

```kotlin
package com.jarvis.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelRepositoryTest {

    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `reports not installed when no file exists`() {
        val repo = ModelRepository(temp.root)
        assertFalse(repo.isInstalled("qwen3-8b-q4km"))
        assertNull(repo.installedFile("qwen3-8b-q4km"))
    }

    @Test
    fun `reports installed when the final file exists`() {
        File(temp.root, "qwen3-8b-q4km.gguf").writeBytes(ByteArray(16))
        val repo = ModelRepository(temp.root)
        assertTrue(repo.isInstalled("qwen3-8b-q4km"))
        assertEquals("qwen3-8b-q4km.gguf", repo.installedFile("qwen3-8b-q4km")?.name)
    }

    @Test
    fun `a partial file does not count as installed`() {
        File(temp.root, "qwen3-8b-q4km.gguf.part").writeBytes(ByteArray(16))
        val repo = ModelRepository(temp.root)
        assertFalse(repo.isInstalled("qwen3-8b-q4km"))
    }

    @Test
    fun `delete removes both the final and partial files`() {
        File(temp.root, "qwen3-8b-q4km.gguf").writeBytes(ByteArray(16))
        File(temp.root, "qwen3-8b-q4km.gguf.part").writeBytes(ByteArray(16))

        ModelRepository(temp.root).delete("qwen3-8b-q4km")

        assertFalse(File(temp.root, "qwen3-8b-q4km.gguf").exists())
        assertFalse(File(temp.root, "qwen3-8b-q4km.gguf.part").exists())
    }
}
```

- [ ] **Step 8: Write the repository**

Create `core-model/src/main/kotlin/com/jarvis/model/ModelRepository.kt`:

```kotlin
package com.jarvis.model

import java.io.File

/**
 * Tracks which model files are present on disk.
 *
 * Only the promoted .gguf name counts as installed; a .part file is in-flight
 * data and must never be handed to the inference engine.
 */
class ModelRepository(private val targetDir: File) {

    fun installedFile(id: String): File? =
        File(targetDir, "$id.gguf").takeIf { it.exists() && it.length() > 0 }

    fun isInstalled(id: String): Boolean = installedFile(id) != null

    fun delete(id: String) {
        File(targetDir, "$id.gguf").delete()
        File(targetDir, "$id.gguf.part").delete()
    }
}
```

- [ ] **Step 9: Run all core-model tests and verify they pass**

```bash
./gradlew :core-model:test
```

Expected: PASS, 9 tests.

- [ ] **Step 10: Commit**

```bash
git add core-model settings.gradle.kts
git commit -m "feat(core-model): resumable download with mandatory verification

Partial data lives in a .part file continued by Range request; the file is
promoted to its final name only after SHA-256 matches, so a corrupt or
partial download can never be loaded as a model. Checksums are pinned in
Task 12 before release."
```

---

### Task 7: core-inference — the engine interface and fake

Written before the JNI work deliberately. The fake unblocks every UI task, so the HUD can be built and tested while the native layer is still being proven.

**Files:**
- Create: `core-inference/build.gradle.kts`, `core-inference/src/main/AndroidManifest.xml`
- Create: `core-inference/src/main/kotlin/com/jarvis/inference/InferenceEngine.kt`
- Create: `core-inference/src/main/kotlin/com/jarvis/inference/InferenceConfig.kt`
- Create: `core-inference/src/main/kotlin/com/jarvis/inference/FakeEngine.kt`
- Test: `core-inference/src/test/kotlin/com/jarvis/inference/FakeEngineTest.kt`

**Interfaces:**
- Consumes: `RuntimeParams` from Task 2
- Produces:
  - `data class InferenceConfig(threadCount: Int, contextTokens: Int, batchSize: Int)` with `fun RuntimeParams.toInferenceConfig(): InferenceConfig`
  - `data class ChatTurn(role: String, content: String)`
  - `sealed interface Token { data class Text(value: String) : Token; data class Complete(tokenCount: Int, elapsedMillis: Long) : Token; data class Error(message: String) : Token }`
  - `interface InferenceEngine { suspend fun load(modelFile: File, config: InferenceConfig); fun generate(history: List<ChatTurn>, prompt: String): Flow<Token>; suspend fun cancel(); suspend fun unload(); val isLoaded: Boolean }`
  - `class FakeEngine(scriptedReply: String, tokenDelayMillis: Long) : InferenceEngine`

- [ ] **Step 1: Create the module build file**

Create `core-inference/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.jarvis.inference"
    compileSdk = 35
    defaultConfig {
        minSdk = 30
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(project(":core-device"))
    api(project(":core-model"))
    implementation(libs.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
}
```

Create `core-inference/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 2: Write the failing test**

Create `core-inference/src/test/kotlin/com/jarvis/inference/FakeEngineTest.kt`:

```kotlin
package com.jarvis.inference

import app.cash.turbine.test
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FakeEngineTest {

    private val config = InferenceConfig(threadCount = 4, contextTokens = 2048, batchSize = 256)

    @Test
    fun `starts unloaded and becomes loaded`() = runTest {
        val engine = FakeEngine(scriptedReply = "hello there", tokenDelayMillis = 0)
        assertFalse(engine.isLoaded)
        engine.load(File("ignored.gguf"), config)
        assertTrue(engine.isLoaded)
    }

    @Test
    fun `emits each word as a token then completes`() = runTest {
        val engine = FakeEngine(scriptedReply = "one two three", tokenDelayMillis = 0)
        engine.load(File("ignored.gguf"), config)

        val text = StringBuilder()
        var complete: Token.Complete? = null

        engine.generate(emptyList(), "prompt").test {
            var item = awaitItem()
            while (item !is Token.Complete) {
                text.append((item as Token.Text).value)
                item = awaitItem()
            }
            complete = item
            awaitComplete()
        }

        assertEquals("one two three", text.toString().trim())
        assertEquals(3, complete!!.tokenCount)
    }

    @Test
    fun `generating before load emits an error`() = runTest {
        val engine = FakeEngine(scriptedReply = "hi", tokenDelayMillis = 0)

        engine.generate(emptyList(), "prompt").test {
            val item = awaitItem()
            assertTrue("expected Error, got $item", item is Token.Error)
            awaitComplete()
        }
    }

    @Test
    fun `unload clears the loaded flag`() = runTest {
        val engine = FakeEngine(scriptedReply = "hi", tokenDelayMillis = 0)
        engine.load(File("ignored.gguf"), config)
        engine.unload()
        assertFalse(engine.isLoaded)
    }

    @Test
    fun `runtime params convert to inference config`() {
        val params = com.jarvis.device.RuntimeParams(threadCount = 6, contextTokens = 4096, batchSize = 512)
        val converted = params.toInferenceConfig()
        assertEquals(6, converted.threadCount)
        assertEquals(4096, converted.contextTokens)
        assertEquals(512, converted.batchSize)
    }
}
```

- [ ] **Step 3: Run the test and verify it fails**

```bash
./gradlew :core-inference:test
```

Expected: FAIL — `Unresolved reference: InferenceConfig`.

- [ ] **Step 4: Write the interface and config**

Create `core-inference/src/main/kotlin/com/jarvis/inference/InferenceConfig.kt`:

```kotlin
package com.jarvis.inference

import com.jarvis.device.RuntimeParams

data class InferenceConfig(
    val threadCount: Int,
    val contextTokens: Int,
    val batchSize: Int
)

fun RuntimeParams.toInferenceConfig() = InferenceConfig(
    threadCount = threadCount,
    contextTokens = contextTokens,
    batchSize = batchSize
)
```

Create `core-inference/src/main/kotlin/com/jarvis/inference/InferenceEngine.kt`:

```kotlin
package com.jarvis.inference

import kotlinx.coroutines.flow.Flow
import java.io.File

data class ChatTurn(val role: String, val content: String)

sealed interface Token {
    data class Text(val value: String) : Token
    /** Terminal success. tokenCount and elapsedMillis feed the HUD throughput readout. */
    data class Complete(val tokenCount: Int, val elapsedMillis: Long) : Token
    /** Terminal failure. The flow completes normally after this; it does not throw. */
    data class Error(val message: String) : Token
}

/**
 * Streaming text generation.
 *
 * Deliberately knows nothing about the UI or about Room. Errors arrive as a
 * terminal [Token.Error] rather than an exception, so a UI collecting this flow
 * never needs a try/catch around collection.
 */
interface InferenceEngine {

    val isLoaded: Boolean

    suspend fun load(modelFile: File, config: InferenceConfig)

    fun generate(history: List<ChatTurn>, prompt: String): Flow<Token>

    suspend fun cancel()

    suspend fun unload()
}
```

- [ ] **Step 5: Write the fake**

Create `core-inference/src/main/kotlin/com/jarvis/inference/FakeEngine.kt`:

```kotlin
package com.jarvis.inference

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * A scripted engine for tests and UI development.
 *
 * Exists so the entire interface can be built and tested without a 5 GB model:
 * set tokenDelayMillis to 100 to see the HUD behave as it would at 10 tok/s.
 */
class FakeEngine(
    private val scriptedReply: String,
    private val tokenDelayMillis: Long = 100
) : InferenceEngine {

    override var isLoaded: Boolean = false
        private set

    private var cancelled = false

    override suspend fun load(modelFile: File, config: InferenceConfig) {
        isLoaded = true
    }

    override fun generate(history: List<ChatTurn>, prompt: String): Flow<Token> = flow {
        if (!isLoaded) {
            emit(Token.Error("Model is not loaded"))
            return@flow
        }

        cancelled = false
        val words = scriptedReply.split(" ").filter { it.isNotEmpty() }
        var elapsed = 0L
        var emitted = 0

        for ((index, word) in words.withIndex()) {
            if (cancelled) break
            if (tokenDelayMillis > 0) {
                delay(tokenDelayMillis)
                elapsed += tokenDelayMillis
            }
            val suffix = if (index == words.lastIndex) "" else " "
            emit(Token.Text(word + suffix))
            emitted++
        }

        // With no delay configured there is no wall-clock to report, so a
        // nominal 1 ms per token keeps downstream throughput arithmetic finite
        // instead of dividing by zero.
        val reportedElapsed = if (elapsed > 0L) elapsed else emitted.toLong()

        emit(Token.Complete(tokenCount = emitted, elapsedMillis = reportedElapsed))
    }

    override suspend fun cancel() {
        cancelled = true
    }

    override suspend fun unload() {
        isLoaded = false
    }
}
```

- [ ] **Step 6: Run the tests and verify they pass**

```bash
./gradlew :core-inference:test
```

Expected: PASS, 5 tests.

- [ ] **Step 7: Commit**

```bash
git add core-inference
git commit -m "feat(core-inference): streaming engine interface with a scripted fake

Errors arrive as a terminal Token.Error rather than an exception, so
collectors need no try/catch. The fake unblocks all UI work before the
native layer exists."
```

---

### Task 8: core-inference — llama.cpp JNI bridge

**This is the highest-risk task in the plan.** The spec calls for proving it early, before UI work depends on it. If the native build fails, everything else still runs against `FakeEngine`.

**Files:**
- Create: `core-inference/src/main/cpp/CMakeLists.txt`
- Create: `core-inference/src/main/cpp/llama_jni.cpp`
- Create: `core-inference/src/main/kotlin/com/jarvis/inference/LlamaEngine.kt`
- Create: `core-inference/src/androidTest/kotlin/com/jarvis/inference/LlamaEngineInstrumentedTest.kt`
- Modify: `core-inference/build.gradle.kts` — add the `externalNativeBuild` block
- Create: `.gitmodules` (via `git submodule add`)

**Interfaces:**
- Consumes: `InferenceEngine`, `InferenceConfig`, `Token`, `ChatTurn` from Task 7
- Produces: `class LlamaEngine : InferenceEngine` — same interface, so it is a drop-in replacement for `FakeEngine`

- [ ] **Step 1: Vendor llama.cpp as a submodule**

```bash
git submodule add https://github.com/ggerganov/llama.cpp core-inference/src/main/cpp/llama.cpp
cd core-inference/src/main/cpp/llama.cpp && git checkout b4585 && cd -
git add .gitmodules core-inference/src/main/cpp/llama.cpp
git commit -m "chore: vendor llama.cpp at b4585"
```

Pinning a tag matters: llama.cpp's API changes frequently and an unpinned submodule will break the build at an unpredictable time.

- [ ] **Step 2: Add the native build to Gradle**

In `core-inference/build.gradle.kts`, inside the `android { }` block, add:

```kotlin
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    defaultConfig {
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_OPENMP=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_CURL=OFF"
                )
                cppFlags += "-O3"
            }
        }
    }
```

Note: the existing `defaultConfig` from Task 7 already sets `minSdk` and `abiFilters`; merge these `externalNativeBuild` arguments into that same block rather than declaring a second one.

- [ ] **Step 3: Write CMakeLists**

Create `core-inference/src/main/cpp/CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(jarvis_llama)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# OpenMP is unavailable on Android NDK and llama.cpp probes for it by default.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

add_library(jarvis_llama SHARED llama_jni.cpp)

target_link_libraries(jarvis_llama
    llama
    android
    log
)
```

- [ ] **Step 4: Write the JNI bridge**

Create `core-inference/src/main/cpp/llama_jni.cpp`:

```cpp
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <atomic>

#include "llama.h"

#define LOG_TAG "JarvisLlama"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model*   model   = nullptr;
    llama_context* ctx     = nullptr;
    const llama_vocab* vocab = nullptr;
    llama_sampler* sampler = nullptr;
    std::atomic<bool> cancelled{false};
};

Session* as_session(jlong handle) {
    return reinterpret_cast<Session*>(handle);
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_com_jarvis_inference_LlamaEngine_nativeInit(JNIEnv*, jobject) {
    llama_backend_init();
}

JNIEXPORT jlong JNICALL
Java_com_jarvis_inference_LlamaEngine_nativeLoad(
        JNIEnv* env, jobject, jstring jPath, jint threads, jint contextTokens, jint batchSize) {

    const char* path = env->GetStringUTFChars(jPath, nullptr);

    llama_model_params modelParams = llama_model_default_params();
    // No GPU offload: Android GPU backends are inconsistent across OEMs and a
    // failed offload is far worse than CPU-only inference.
    modelParams.n_gpu_layers = 0;
    modelParams.use_mmap = true;

    llama_model* model = llama_model_load_from_file(path, modelParams);
    env->ReleaseStringUTFChars(jPath, path);

    if (model == nullptr) {
        LOGE("llama_model_load_from_file returned null");
        return 0;
    }

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx     = static_cast<uint32_t>(contextTokens);
    ctxParams.n_batch   = static_cast<uint32_t>(batchSize);
    ctxParams.n_threads = threads;
    ctxParams.n_threads_batch = threads;

    llama_context* ctx = llama_init_from_model(model, ctxParams);
    if (ctx == nullptr) {
        LOGE("llama_init_from_model returned null");
        llama_model_free(model);
        return 0;
    }

    auto* session = new Session();
    session->model = model;
    session->ctx   = ctx;
    session->vocab = llama_model_get_vocab(model);

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    session->sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(session->sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(session->sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(session->sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(session->sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    LOGI("model loaded: ctx=%d threads=%d batch=%d", contextTokens, threads, batchSize);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT void JNICALL
Java_com_jarvis_inference_LlamaEngine_nativeFree(JNIEnv*, jobject, jlong handle) {
    Session* session = as_session(handle);
    if (session == nullptr) return;
    if (session->sampler) llama_sampler_free(session->sampler);
    if (session->ctx)     llama_free(session->ctx);
    if (session->model)   llama_model_free(session->model);
    delete session;
}

JNIEXPORT void JNICALL
Java_com_jarvis_inference_LlamaEngine_nativeCancel(JNIEnv*, jobject, jlong handle) {
    Session* session = as_session(handle);
    if (session != nullptr) session->cancelled.store(true);
}

/**
 * Generates text, calling back into Kotlin once per token.
 *
 * The callback is invoked synchronously on the calling thread, so the Kotlin
 * side must call this from a background dispatcher.
 */
JNIEXPORT void JNICALL
Java_com_jarvis_inference_LlamaEngine_nativeGenerate(
        JNIEnv* env, jobject thiz, jlong handle, jstring jPrompt, jint maxTokens, jobject callback) {

    Session* session = as_session(handle);
    if (session == nullptr) return;

    session->cancelled.store(false);

    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onToken = env->GetMethodID(callbackClass, "onToken", "(Ljava/lang/String;)V");
    jmethodID onDone  = env->GetMethodID(callbackClass, "onDone", "(I)V");
    jmethodID onError = env->GetMethodID(callbackClass, "onError", "(Ljava/lang/String;)V");

    const char* prompt = env->GetStringUTFChars(jPrompt, nullptr);
    const std::string promptStr(prompt);
    env->ReleaseStringUTFChars(jPrompt, prompt);

    // Tokenize: first call with a null buffer returns the negative count needed.
    const int needed = -llama_tokenize(
            session->vocab, promptStr.c_str(), promptStr.length(),
            nullptr, 0, true, true);

    std::vector<llama_token> tokens(needed);
    if (llama_tokenize(session->vocab, promptStr.c_str(), promptStr.length(),
                       tokens.data(), tokens.size(), true, true) < 0) {
        jstring msg = env->NewStringUTF("Tokenization failed");
        env->CallVoidMethod(callback, onError, msg);
        env->DeleteLocalRef(msg);
        return;
    }

    llama_batch batch = llama_batch_get_one(tokens.data(), tokens.size());

    // Holds the single sampled token between iterations. llama_batch_get_one
    // stores a pointer rather than copying, so this must outlive the loop body
    // or the next llama_decode reads freed stack memory.
    llama_token sampled = 0;

    int generated = 0;
    while (generated < maxTokens) {
        if (session->cancelled.load()) break;

        if (llama_decode(session->ctx, batch) != 0) {
            jstring msg = env->NewStringUTF("Decode failed, context may be full");
            env->CallVoidMethod(callback, onError, msg);
            env->DeleteLocalRef(msg);
            return;
        }

        sampled = llama_sampler_sample(session->sampler, session->ctx, -1);

        if (llama_vocab_is_eog(session->vocab, sampled)) break;

        char piece[256];
        const int written = llama_token_to_piece(session->vocab, sampled, piece, sizeof(piece), 0, true);
        if (written > 0) {
            std::string text(piece, written);
            jstring jText = env->NewStringUTF(text.c_str());
            env->CallVoidMethod(callback, onToken, jText);
            env->DeleteLocalRef(jText);
        }

        llama_sampler_accept(session->sampler, sampled);
        batch = llama_batch_get_one(&sampled, 1);
        generated++;
    }

    env->CallVoidMethod(callback, onDone, generated);
}

} // extern "C"
```

- [ ] **Step 5: Write the Kotlin side**

Create `core-inference/src/main/kotlin/com/jarvis/inference/LlamaEngine.kt`:

```kotlin
package com.jarvis.inference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * Runs a GGUF model through llama.cpp.
 *
 * Native calls are blocking, so generation runs on Dispatchers.IO. The native
 * layer calls back once per token; cancellation is cooperative and checked in
 * the native decode loop.
 */
class LlamaEngine : InferenceEngine {

    private var handle: Long = 0

    override val isLoaded: Boolean get() = handle != 0L

    private external fun nativeInit()
    private external fun nativeLoad(path: String, threads: Int, contextTokens: Int, batchSize: Int): Long
    private external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, callback: TokenCallback)
    private external fun nativeCancel(handle: Long)
    private external fun nativeFree(handle: Long)

    /** Invoked from the native decode loop, once per token. */
    interface TokenCallback {
        fun onToken(text: String)
        fun onDone(tokenCount: Int)
        fun onError(message: String)
    }

    override suspend fun load(modelFile: File, config: InferenceConfig) {
        require(modelFile.exists()) { "Model file does not exist: ${modelFile.absolutePath}" }
        nativeInit()
        handle = nativeLoad(
            modelFile.absolutePath,
            config.threadCount,
            config.contextTokens,
            config.batchSize
        )
        check(handle != 0L) { "llama.cpp failed to load ${modelFile.name}" }
    }

    override fun generate(history: List<ChatTurn>, prompt: String): Flow<Token> = callbackFlow {
        if (handle == 0L) {
            trySend(Token.Error("Model is not loaded"))
            close()
            return@callbackFlow
        }

        val startedAt = System.currentTimeMillis()

        val callback = object : TokenCallback {
            override fun onToken(text: String) {
                trySendBlocking(Token.Text(text))
            }

            override fun onDone(tokenCount: Int) {
                trySendBlocking(
                    Token.Complete(
                        tokenCount = tokenCount,
                        elapsedMillis = System.currentTimeMillis() - startedAt
                    )
                )
                close()
            }

            override fun onError(message: String) {
                trySendBlocking(Token.Error(message))
                close()
            }
        }

        nativeGenerate(handle, buildPrompt(history, prompt), MAX_GENERATED_TOKENS, callback)

        awaitClose { if (handle != 0L) nativeCancel(handle) }
    }.flowOn(Dispatchers.IO)

    override suspend fun cancel() {
        if (handle != 0L) nativeCancel(handle)
    }

    override suspend fun unload() {
        if (handle != 0L) {
            nativeFree(handle)
            handle = 0
        }
    }

    /** Qwen3 chat template. A mismatched template degrades output quality badly. */
    private fun buildPrompt(history: List<ChatTurn>, prompt: String): String = buildString {
        append("<|im_start|>system\n")
        append("You are JARVIS, a concise and capable personal assistant running entirely on this device.")
        append("<|im_end|>\n")
        for (turn in history) {
            append("<|im_start|>${turn.role}\n${turn.content}<|im_end|>\n")
        }
        append("<|im_start|>user\n$prompt<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }

    companion object {
        private const val MAX_GENERATED_TOKENS = 1024

        init {
            System.loadLibrary("jarvis_llama")
        }
    }
}
```

- [ ] **Step 6: Build the native library**

```bash
./gradlew :core-inference:assembleDebug
```

Expected: `BUILD SUCCESSFUL`, and `core-inference/build/intermediates/cxx/Debug/*/obj/arm64-v8a/libjarvis_llama.so` exists.

If the build fails, the likely causes in order: OpenMP probing (confirm `GGML_OPENMP=OFF` reached CMake), a llama.cpp API change (confirm the submodule is at b4585), or NDK version mismatch (confirm 27.2.12479018 is installed).

- [ ] **Step 7: Write the instrumented test**

This test requires a real model on the device and only runs when one is present. Create `core-inference/src/androidTest/kotlin/com/jarvis/inference/LlamaEngineInstrumentedTest.kt`:

```kotlin
package com.jarvis.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LlamaEngineInstrumentedTest {

    private val modelFile: File
        get() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            return File(context.filesDir, "models/qwen3-1_7b-q4km.gguf")
        }

    @Test
    fun loadsAndGeneratesText() = runBlocking {
        assumeTrue("No model on device; push one to run this test", modelFile.exists())

        val engine = LlamaEngine()
        engine.load(modelFile, InferenceConfig(threadCount = 4, contextTokens = 2048, batchSize = 256))
        assertTrue(engine.isLoaded)

        val tokens = engine.generate(emptyList(), "Reply with exactly the word: ready").toList()

        val text = tokens.filterIsInstance<Token.Text>().joinToString("") { it.value }
        val complete = tokens.filterIsInstance<Token.Complete>().firstOrNull()

        assertTrue("no text generated", text.isNotBlank())
        assertTrue("no completion event", complete != null)
        assertTrue("token count should be positive", complete!!.tokenCount > 0)

        engine.unload()
        assertTrue(!engine.isLoaded)
    }
}
```

Add to `core-inference/build.gradle.kts` dependencies:

```kotlin
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.coroutines.test)
```

And inside `android { defaultConfig { } }`:

```kotlin
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
```

- [ ] **Step 8: Push the smallest model and run the instrumented test**

Download `Qwen3-1.7B-Q4_K_M.gguf` on the desktop, then:

```bash
adb shell mkdir -p /data/local/tmp/jarvis
adb push Qwen3-1.7B-Q4_K_M.gguf /data/local/tmp/jarvis/
adb shell run-as com.jarvis.inference.test mkdir -p files/models
adb shell "cp /data/local/tmp/jarvis/Qwen3-1.7B-Q4_K_M.gguf /data/local/tmp/jarvis/model.gguf"
./gradlew :core-inference:connectedDebugAndroidTest
```

Expected: PASS. If the test is skipped, the model is not at the expected path — check the assume message in logcat.

Use the 1.7B here rather than the 8B: it proves the same code path in a fraction of the load time.

- [ ] **Step 9: Commit**

```bash
git add core-inference
git commit -m "feat(core-inference): llama.cpp JNI bridge

Streams tokens through a native callback into a callbackFlow, with
cooperative cancellation checked in the decode loop. GPU offload is
disabled deliberately: Android GPU backends vary by OEM and a failed
offload is worse than CPU-only. Qwen3 chat template applied on the
Kotlin side."
```

---

### Task 9: ui-hud — theme and reactor state

`ReactorState.kt` holds only the state model and `Reactor.kt` only the drawing, so the renderer is testable against synthetic states with no engine running.

**Files:**
- Create: `ui-hud/build.gradle.kts`, `ui-hud/src/main/AndroidManifest.xml`
- Create: `ui-hud/src/main/kotlin/com/jarvis/hud/Theme.kt`
- Create: `ui-hud/src/main/kotlin/com/jarvis/hud/ReactorState.kt`
- Test: `ui-hud/src/test/kotlin/com/jarvis/hud/ReactorStateTest.kt`

**Interfaces:**
- Consumes: `ThermalState` from Task 2
- Produces:
  - `object JarvisColors { val Ground, Panel, PanelHi, Hairline, HairlineLo, Text, Neutral, NeutralDim, Cool, CoolDeep, Warm, Amber, Red: Color }`
  - `enum class ReactorMode { IDLE, THINKING, GENERATING, THERMAL, LOW_POWER }`
  - `data class ReactorState(mode: ReactorMode, tokensPerSecond: Float, thermalState: ThermalState, batteryPercent: Int, isCharging: Boolean)`
  - `fun ReactorState.spinScale(): Float`
  - `fun ReactorState.pulseIntervalMillis(): Long`
  - `fun ReactorState.isWarmPalette(): Boolean`
  - `fun ReactorState.dimFactor(): Float`
  - `fun reactorModeFor(isGenerating: Boolean, isThinking: Boolean, thermal: ThermalState, batteryPercent: Int, isCharging: Boolean): ReactorMode`

- [ ] **Step 1: Create the module build file**

Create `ui-hud/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.jarvis.hud"
    compileSdk = 35
    defaultConfig {
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(project(":core-device"))
    api(project(":core-data"))
    api(project(":core-inference"))
    api(project(":core-model"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.tooling.preview)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)
    debugImplementation(libs.compose.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.compose.test.manifest)
}
```

Create `ui-hud/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 2: Write the failing test**

Create `ui-hud/src/test/kotlin/com/jarvis/hud/ReactorStateTest.kt`:

```kotlin
package com.jarvis.hud

import com.jarvis.device.ThermalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactorStateTest {

    private fun state(
        mode: ReactorMode = ReactorMode.IDLE,
        tps: Float = 0f,
        thermal: ThermalState = ThermalState.NOMINAL,
        battery: Int = 80,
        charging: Boolean = false
    ) = ReactorState(mode, tps, thermal, battery, charging)

    @Test
    fun `generating spins faster than idle`() {
        assertTrue(state(mode = ReactorMode.GENERATING).spinScale() > state(mode = ReactorMode.IDLE).spinScale())
    }

    @Test
    fun `thermal spins slower than generating`() {
        assertTrue(state(mode = ReactorMode.THERMAL).spinScale() < state(mode = ReactorMode.GENERATING).spinScale())
    }

    @Test
    fun `pulse interval is derived from measured throughput`() {
        // 10 tok/s means one pulse every 100 ms
        assertEquals(100L, state(mode = ReactorMode.GENERATING, tps = 10f).pulseIntervalMillis())
        assertEquals(50L, state(mode = ReactorMode.GENERATING, tps = 20f).pulseIntervalMillis())
    }

    @Test
    fun `idle emits no pulses`() {
        assertEquals(0L, state(mode = ReactorMode.IDLE).pulseIntervalMillis())
    }

    @Test
    fun `zero throughput does not divide by zero`() {
        assertEquals(0L, state(mode = ReactorMode.GENERATING, tps = 0f).pulseIntervalMillis())
    }

    @Test
    fun `thermal mode uses the warm palette`() {
        assertTrue(state(mode = ReactorMode.THERMAL).isWarmPalette())
        assertFalse(state(mode = ReactorMode.GENERATING).isWarmPalette())
    }

    @Test
    fun `low power dims the reactor`() {
        assertTrue(state(mode = ReactorMode.LOW_POWER).dimFactor() < state(mode = ReactorMode.IDLE).dimFactor())
    }

    @Test
    fun `mode resolution puts thermal above generating`() {
        val mode = reactorModeFor(
            isGenerating = true,
            isThinking = false,
            thermal = ThermalState.SEVERE,
            batteryPercent = 80,
            isCharging = false
        )
        assertEquals(ReactorMode.THERMAL, mode)
    }

    @Test
    fun `mode resolution puts generating above low power`() {
        val mode = reactorModeFor(
            isGenerating = true,
            isThinking = false,
            thermal = ThermalState.NOMINAL,
            batteryPercent = 5,
            isCharging = false
        )
        assertEquals(ReactorMode.GENERATING, mode)
    }

    @Test
    fun `low battery while charging is not low power`() {
        val mode = reactorModeFor(
            isGenerating = false,
            isThinking = false,
            thermal = ThermalState.NOMINAL,
            batteryPercent = 5,
            isCharging = true
        )
        assertEquals(ReactorMode.IDLE, mode)
    }

    @Test
    fun `thinking is distinct from generating`() {
        val mode = reactorModeFor(
            isGenerating = false,
            isThinking = true,
            thermal = ThermalState.NOMINAL,
            batteryPercent = 80,
            isCharging = false
        )
        assertEquals(ReactorMode.THINKING, mode)
    }
}
```

- [ ] **Step 3: Run the test and verify it fails**

```bash
./gradlew :ui-hud:test
```

Expected: FAIL — `Unresolved reference: ReactorMode`.

- [ ] **Step 4: Write the theme**

Create `ui-hud/src/main/kotlin/com/jarvis/hud/Theme.kt`:

```kotlin
package com.jarvis.hud

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Fixed palette from the approved HUD design.
 *
 * Single dark theme by intent: this is an instrument panel, and a light variant
 * would undermine the whole read. No isSystemInDarkTheme branching anywhere.
 */
object JarvisColors {
    val Ground = Color(0xFF06080B)
    val Panel = Color(0xFF0C1117)
    val PanelHi = Color(0xFF121A22)
    val Hairline = Color(0xFF1B2530)
    val HairlineLo = Color(0xFF141C25)
    val Text = Color(0xFFDDE6EF)
    val Neutral = Color(0xFF8A97A6)
    val NeutralDim = Color(0xFF55636F)
    val Cool = Color(0xFF3FD8E8)
    val CoolDeep = Color(0xFF1B8C99)
    val Warm = Color(0xFFFF8A2A)
    val Amber = Color(0xFFF2A93B)
    val Red = Color(0xFFE8604C)
}

private val JarvisScheme = darkColorScheme(
    primary = JarvisColors.Cool,
    onPrimary = JarvisColors.Ground,
    secondary = JarvisColors.Warm,
    background = JarvisColors.Ground,
    onBackground = JarvisColors.Text,
    surface = JarvisColors.Panel,
    onSurface = JarvisColors.Text,
    error = JarvisColors.Red
)

val MonoStyle = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    letterSpacing = 1.6.sp
)

private val JarvisTypography = Typography(
    bodyMedium = TextStyle(fontSize = 14.sp, color = JarvisColors.Text),
    labelSmall = MonoStyle.copy(fontWeight = FontWeight.Medium)
)

@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JarvisScheme,
        typography = JarvisTypography,
        content = content
    )
}
```

- [ ] **Step 5: Write the reactor state**

Create `ui-hud/src/main/kotlin/com/jarvis/hud/ReactorState.kt`:

```kotlin
package com.jarvis.hud

import com.jarvis.device.ThermalState

enum class ReactorMode { IDLE, THINKING, GENERATING, THERMAL, LOW_POWER }

/**
 * Everything the reactor is allowed to draw from.
 *
 * The design rule is that no element animates unless it reflects real state, so
 * this class is the only input to the renderer. If a value is not here, it
 * cannot influence the picture.
 */
data class ReactorState(
    val mode: ReactorMode,
    /** Measured, not estimated. Zero when not generating. */
    val tokensPerSecond: Float,
    val thermalState: ThermalState,
    val batteryPercent: Int,
    val isCharging: Boolean
)

/** Ring rotation multiplier. */
fun ReactorState.spinScale(): Float = when (mode) {
    ReactorMode.IDLE -> 0.32f
    ReactorMode.THINKING -> 0.85f
    ReactorMode.GENERATING -> 1.25f
    ReactorMode.THERMAL -> 0.50f
    ReactorMode.LOW_POWER -> 0.20f
}

/**
 * Milliseconds between token pulse rings, derived from measured throughput.
 * Zero means no pulses, which is what makes a stalled model visibly stall.
 */
fun ReactorState.pulseIntervalMillis(): Long {
    if (mode != ReactorMode.GENERATING && mode != ReactorMode.THERMAL) return 0L
    if (tokensPerSecond <= 0f) return 0L
    return (1000f / tokensPerSecond).toLong().coerceIn(16L, 2000L)
}

fun ReactorState.isWarmPalette(): Boolean =
    mode == ReactorMode.THERMAL || thermalState >= ThermalState.SEVERE

fun ReactorState.dimFactor(): Float = when (mode) {
    ReactorMode.LOW_POWER -> 0.45f
    else -> 1.0f
}

/**
 * Resolves the single mode to display from independent signals.
 *
 * Priority is deliberate: thermal outranks generating because a hot phone is
 * the more important thing to communicate, and generating outranks low power
 * because the user is actively waiting on output.
 */
fun reactorModeFor(
    isGenerating: Boolean,
    isThinking: Boolean,
    thermal: ThermalState,
    batteryPercent: Int,
    isCharging: Boolean
): ReactorMode = when {
    thermal >= ThermalState.SEVERE -> ReactorMode.THERMAL
    isGenerating -> ReactorMode.GENERATING
    isThinking -> ReactorMode.THINKING
    !isCharging && batteryPercent <= LOW_BATTERY_PERCENT -> ReactorMode.LOW_POWER
    else -> ReactorMode.IDLE
}

private const val LOW_BATTERY_PERCENT = 15
```

- [ ] **Step 6: Run the tests and verify they pass**

```bash
./gradlew :ui-hud:test
```

Expected: PASS, 11 tests.

- [ ] **Step 7: Commit**

```bash
git add ui-hud
git commit -m "feat(ui-hud): fixed dark palette and reactor state model

ReactorState is the only input the renderer may draw from, which enforces
the design rule that nothing animates without real state behind it. Pulse
interval is derived from measured tok/s so a stalled model visibly stalls."
```

---

### Task 10: ui-hud — the reactor renderer

The Canvas port of the approved design: eight counter-rotating ring assemblies, tick bands, radial spokes, data blocks, hot nodes, and a core reticle.

**Files:**
- Create: `ui-hud/src/main/kotlin/com/jarvis/hud/ReactorGeometry.kt`
- Create: `ui-hud/src/main/kotlin/com/jarvis/hud/Reactor.kt`
- Test: `ui-hud/src/test/kotlin/com/jarvis/hud/ReactorGeometryTest.kt`

**Interfaces:**
- Consumes: `ReactorState`, `JarvisColors` from Task 9
- Produces:
  - `data class RingDef(radiusFraction: Float, speed: Float, segments: Int, gapFraction: Float, strokeWidth: Float, hot: Boolean)`
  - `object ReactorGeometry { val RINGS: List<RingDef>; val TICK_BANDS: List<TickBand>; fun arcsFor(ring: RingDef): List<ClosedFloatingPointRange<Float>>; fun activePulses(nowMillis: Long, intervalMillis: Long, lifetimeMillis: Long): List<Float> }`
  - `@Composable fun Reactor(state: ReactorState, modifier: Modifier)`

- [ ] **Step 1: Write the failing geometry test**

Create `ui-hud/src/test/kotlin/com/jarvis/hud/ReactorGeometryTest.kt`:

```kotlin
package com.jarvis.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReactorGeometryTest {

    @Test
    fun `has eight rings as designed`() {
        assertEquals(8, ReactorGeometry.RINGS.size)
    }

    @Test
    fun `rings are ordered outward and stay within bounds`() {
        val radii = ReactorGeometry.RINGS.map { it.radiusFraction }
        assertEquals(radii.sorted(), radii)
        assertTrue(radii.all { it in 0.05f..1.0f })
    }

    @Test
    fun `adjacent rings counter rotate`() {
        val speeds = ReactorGeometry.RINGS.map { it.speed }
        for (i in 0 until speeds.size - 1) {
            assertTrue(
                "rings $i and ${i + 1} rotate the same way",
                speeds[i] * speeds[i + 1] < 0
            )
        }
    }

    @Test
    fun `every ring produces its declared number of arcs`() {
        for (ring in ReactorGeometry.RINGS) {
            assertEquals(ring.segments, ReactorGeometry.arcsFor(ring).size)
        }
    }

    @Test
    fun `arcs never overlap within a ring`() {
        for (ring in ReactorGeometry.RINGS) {
            val arcs = ReactorGeometry.arcsFor(ring)
            for (i in 0 until arcs.size - 1) {
                assertTrue(
                    "arc $i overlaps arc ${i + 1} on ring ${ring.radiusFraction}",
                    arcs[i].endInclusive <= arcs[i + 1].start
                )
            }
        }
    }

    @Test
    fun `zero interval produces no pulses`() {
        assertTrue(ReactorGeometry.activePulses(5000L, 0L, 1600L).isEmpty())
    }

    @Test
    fun `pulse ages are normalised between zero and one`() {
        val pulses = ReactorGeometry.activePulses(nowMillis = 5000L, intervalMillis = 100L, lifetimeMillis = 1600L)
        assertTrue(pulses.isNotEmpty())
        assertTrue(pulses.all { it in 0f..1f })
    }

    @Test
    fun `faster generation produces more simultaneous pulses`() {
        val slow = ReactorGeometry.activePulses(5000L, 200L, 1600L)
        val fast = ReactorGeometry.activePulses(5000L, 50L, 1600L)
        assertTrue(fast.size > slow.size)
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
./gradlew :ui-hud:test --tests "*ReactorGeometryTest*"
```

Expected: FAIL — `Unresolved reference: ReactorGeometry`.

- [ ] **Step 3: Write the geometry**

Create `ui-hud/src/main/kotlin/com/jarvis/hud/ReactorGeometry.kt`:

```kotlin
package com.jarvis.hud

data class RingDef(
    val radiusFraction: Float,
    /** Radians per second. Sign sets direction; adjacent rings counter-rotate. */
    val speed: Float,
    val segments: Int,
    /** Share of each segment's slot that is drawn rather than left as a gap. */
    val gapFraction: Float,
    val strokeWidth: Float,
    val hot: Boolean
)

data class TickBand(
    val radiusFraction: Float,
    val count: Int,
    val length: Float,
    val speed: Float,
    val majorEvery: Int
)

/**
 * Static layout of the reactor assembly.
 *
 * Pure data and pure functions so the arrangement is testable without a Canvas:
 * the drawing code in Reactor.kt only walks these lists.
 */
object ReactorGeometry {

    val RINGS: List<RingDef> = listOf(
        RingDef(0.30f, 0.55f, 3, 0.55f, 2.2f, hot = true),
        RingDef(0.40f, -0.34f, 6, 0.30f, 1.2f, hot = false),
        RingDef(0.50f, 0.22f, 2, 0.85f, 4.0f, hot = true),
        RingDef(0.58f, -0.62f, 9, 0.18f, 1.0f, hot = false),
        RingDef(0.68f, 0.16f, 4, 0.42f, 2.6f, hot = true),
        RingDef(0.78f, -0.26f, 12, 0.14f, 1.0f, hot = false),
        RingDef(0.88f, 0.11f, 3, 0.62f, 3.2f, hot = false),
        RingDef(0.97f, -0.44f, 7, 0.22f, 1.4f, hot = true)
    )

    val TICK_BANDS: List<TickBand> = listOf(
        TickBand(0.45f, 90, 4f, 0.05f, 5),
        TickBand(0.73f, 120, 5f, -0.03f, 6),
        TickBand(1.06f, 150, 7f, 0.02f, 10)
    )

    /** Arc spans in radians, evenly spaced with a gap between each. */
    fun arcsFor(ring: RingDef): List<ClosedFloatingPointRange<Float>> {
        val slot = (2 * Math.PI / ring.segments).toFloat()
        return (0 until ring.segments).map { i ->
            val start = i * slot
            start..(start + slot * ring.gapFraction)
        }
    }

    /**
     * Ages of the token pulses currently on screen, oldest first, each in 0..1.
     *
     * Derived from wall-clock time and the interval rather than accumulated
     * state, so the animation is correct even if frames are dropped under load.
     */
    fun activePulses(nowMillis: Long, intervalMillis: Long, lifetimeMillis: Long): List<Float> {
        if (intervalMillis <= 0L || lifetimeMillis <= 0L) return emptyList()

        val ages = mutableListOf<Float>()
        var emittedAt = nowMillis - (nowMillis % intervalMillis)

        while (nowMillis - emittedAt < lifetimeMillis) {
            val age = (nowMillis - emittedAt).toFloat() / lifetimeMillis
            if (age in 0f..1f) ages += age
            emittedAt -= intervalMillis
            if (emittedAt < 0) break
        }

        return ages
    }
}
```

- [ ] **Step 4: Run the geometry tests and verify they pass**

```bash
./gradlew :ui-hud:test --tests "*ReactorGeometryTest*"
```

Expected: PASS, 8 tests.

- [ ] **Step 5: Write the renderer**

Create `ui-hud/src/main/kotlin/com/jarvis/hud/Reactor.kt`:

```kotlin
package com.jarvis.hud

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private const val PULSE_LIFETIME_MILLIS = 1600L
private const val CYCLE_MILLIS = 20_000

/**
 * The reactor assembly.
 *
 * Every animated property traces back to [state]; there is no decorative
 * motion. The single infinite transition drives one normalised clock, and each
 * element derives its own phase from it, so the whole assembly stays in sync
 * and only one animation is running regardless of element count.
 */
@Composable
fun Reactor(state: ReactorState, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "reactor")
    val clock by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(CYCLE_MILLIS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "clock"
    )

    val warm = state.isWarmPalette()
    val primary = if (warm) JarvisColors.Warm else JarvisColors.Cool
    val accent = if (warm) JarvisColors.Amber else JarvisColors.Warm
    val dim = state.dimFactor()
    val spin = state.spinScale()

    Canvas(modifier = modifier) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = min(size.width, size.height) * 0.40f
        val elapsedSeconds = clock * (CYCLE_MILLIS / 1000f)

        drawAmbientWash(center, radius, primary, dim)
        drawTokenPulses(center, radius, state, accent, dim, elapsedSeconds)
        drawTickBands(center, radius, primary, dim, elapsedSeconds, spin)
        drawRings(center, radius, primary, accent, dim, elapsedSeconds, spin)
        drawCore(center, radius, primary, accent, dim, clock)
    }
}

private fun DrawScope.drawAmbientWash(center: Offset, radius: Float, color: Color, dim: Float) {
    val outer = radius * 1.7f
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = 0.13f * dim), Color.Transparent),
            center = center,
            radius = outer
        ),
        radius = outer,
        center = center
    )
}

private fun DrawScope.drawTokenPulses(
    center: Offset,
    radius: Float,
    state: ReactorState,
    color: Color,
    dim: Float,
    elapsedSeconds: Float
) {
    val interval = state.pulseIntervalMillis()
    if (interval <= 0L) return

    val nowMillis = (elapsedSeconds * 1000f).toLong()
    for (age in ReactorGeometry.activePulses(nowMillis, interval, PULSE_LIFETIME_MILLIS)) {
        val fade = (1f - age) * (1f - age)
        drawCircle(
            color = color.copy(alpha = 0.36f * fade * dim),
            radius = radius * (0.34f + age * 1.5f),
            center = center,
            style = Stroke(width = 1.3f)
        )
    }
}

private fun DrawScope.drawTickBands(
    center: Offset,
    radius: Float,
    color: Color,
    dim: Float,
    elapsedSeconds: Float,
    spin: Float
) {
    for (band in ReactorGeometry.TICK_BANDS) {
        val bandRadius = radius * band.radiusFraction
        val rotation = elapsedSeconds * band.speed * spin
        for (i in 0 until band.count) {
            val angle = (i.toFloat() / band.count) * 2f * PI.toFloat() + rotation
            val major = i % band.majorEvery == 0
            val length = if (major) band.length * 2.1f else band.length
            val alpha = if (major) 0.46f else 0.13f
            drawLine(
                color = color.copy(alpha = alpha * dim),
                start = center + Offset(cos(angle) * bandRadius, sin(angle) * bandRadius),
                end = center + Offset(
                    cos(angle) * (bandRadius + length),
                    sin(angle) * (bandRadius + length)
                ),
                strokeWidth = 1f
            )
        }
    }
}

private fun DrawScope.drawRings(
    center: Offset,
    radius: Float,
    primary: Color,
    accent: Color,
    dim: Float,
    elapsedSeconds: Float,
    spin: Float
) {
    // Two passes per ring: a wide faint stroke reads as emitted light, and the
    // thin bright stroke on top keeps the edge crisp.
    for (pass in 0..1) {
        val glow = pass == 0
        for (ring in ReactorGeometry.RINGS) {
            val ringRadius = radius * ring.radiusFraction
            val rotation = elapsedSeconds * ring.speed * spin
            val color = if (ring.hot) accent else primary
            val alpha = if (glow) 0.16f else 0.85f
            val width = if (glow) ring.strokeWidth * 3.2f else ring.strokeWidth

            for (arc in ReactorGeometry.arcsFor(ring)) {
                val startDegrees = Math.toDegrees((arc.start + rotation).toDouble()).toFloat()
                val sweepDegrees =
                    Math.toDegrees((arc.endInclusive - arc.start).toDouble()).toFloat()
                drawArc(
                    color = color.copy(alpha = alpha * dim),
                    startAngle = startDegrees,
                    sweepAngle = sweepDegrees,
                    useCenter = false,
                    topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                    size = Size(ringRadius * 2, ringRadius * 2),
                    style = Stroke(width = width)
                )
            }
        }
    }
}

private fun DrawScope.drawCore(
    center: Offset,
    radius: Float,
    primary: Color,
    accent: Color,
    dim: Float,
    clock: Float
) {
    val breath = sin(clock * 2f * PI.toFloat() * 4f) * 0.03f
    val coreRadius = radius * (0.20f + breath)

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.92f * dim),
                primary.copy(alpha = 0.60f * dim),
                Color.Transparent
            ),
            center = center,
            radius = coreRadius
        ),
        radius = coreRadius,
        center = center
    )

    val reticleRadius = radius * 0.115f
    drawCircle(
        color = Color.White.copy(alpha = 0.55f * dim),
        radius = reticleRadius,
        center = center,
        style = Stroke(width = 1f)
    )

    for (i in 0 until 4) {
        val angle = i * PI.toFloat() / 2f + clock * 2f * PI.toFloat()
        drawLine(
            color = accent.copy(alpha = 0.6f * dim),
            start = center + Offset(
                cos(angle) * reticleRadius * 1.25f,
                sin(angle) * reticleRadius * 1.25f
            ),
            end = center + Offset(
                cos(angle) * reticleRadius * 1.85f,
                sin(angle) * reticleRadius * 1.85f
            ),
            strokeWidth = 1f
        )
    }
}
```

- [ ] **Step 6: Run all ui-hud tests and verify they pass**

```bash
./gradlew :ui-hud:test
```

Expected: PASS, 19 tests.

- [ ] **Step 7: Commit**

```bash
git add ui-hud
git commit -m "feat(ui-hud): reactor renderer with eight counter-rotating rings

Geometry is pure data and pure functions in ReactorGeometry so the layout
is testable without a Canvas. One infinite transition drives a single
clock that every element phases from, so element count does not multiply
running animations. Pulse ages derive from wall-clock time, keeping the
animation correct when frames drop under inference load."
```

---

### Task 11: ui-hud — telemetry chips and chat screen

Telemetry lives in small corner chips, not a full-width bar. This was an explicit design decision: the bar competed with the reactor for attention.

**Files:**
- Create: `ui-hud/src/main/kotlin/com/jarvis/hud/TelemetryChips.kt`
- Create: `ui-hud/src/main/kotlin/com/jarvis/hud/ChatScreen.kt`
- Test: `ui-hud/src/androidTest/kotlin/com/jarvis/hud/ChatScreenTest.kt`

**Interfaces:**
- Consumes: `ReactorState`, `JarvisColors`, `Reactor` from Tasks 9-10; `Message` from Task 4
- Produces:
  - `data class ChipData(label: String, highlight: ChipHighlight)`
  - `enum class ChipHighlight { NONE, ACTIVE, WARN }`
  - `fun chipsFor(state: ReactorState, residentGb: Float): List<ChipData>`
  - `@Composable fun TelemetryChips(chips: List<ChipData>, modifier: Modifier)`
  - `@Composable fun ChatScreen(state: ReactorState, messages: List<Message>, streamingText: String, residentGb: Float, onSend: (String) -> Unit, modifier: Modifier)`

- [ ] **Step 1: Write the failing chip test**

Create `ui-hud/src/test/kotlin/com/jarvis/hud/TelemetryChipsTest.kt`:

```kotlin
package com.jarvis.hud

import com.jarvis.device.ThermalState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelemetryChipsTest {

    private fun state(
        mode: ReactorMode = ReactorMode.IDLE,
        tps: Float = 0f,
        thermal: ThermalState = ThermalState.NOMINAL
    ) = ReactorState(mode, tps, thermal, batteryPercent = 80, isCharging = false)

    @Test
    fun `idle shows memory and thermal but no throughput`() {
        val chips = chipsFor(state(), residentGb = 4.9f)
        assertTrue(chips.none { it.label.contains("tok/s") })
        assertTrue(chips.any { it.label.contains("4.9") })
        assertTrue(chips.any { it.label.contains("NOMINAL") })
    }

    @Test
    fun `generating shows an active throughput chip`() {
        val chips = chipsFor(state(mode = ReactorMode.GENERATING, tps = 10.4f), residentGb = 5.2f)
        val tps = chips.first { it.label.contains("tok/s") }
        assertEquals(ChipHighlight.ACTIVE, tps.highlight)
        assertTrue(tps.label.contains("10.4"))
    }

    @Test
    fun `severe thermal marks chips as warnings`() {
        val chips = chipsFor(
            state(mode = ReactorMode.THERMAL, tps = 6.1f, thermal = ThermalState.SEVERE),
            residentGb = 5.2f
        )
        assertTrue(chips.any { it.highlight == ChipHighlight.WARN })
        assertTrue(chips.any { it.label.contains("SEVERE") })
    }

    @Test
    fun `chip count stays small enough for a corner`() {
        val chips = chipsFor(state(mode = ReactorMode.GENERATING, tps = 10f), residentGb = 5.2f)
        assertTrue("too many chips for the corner: ${chips.size}", chips.size <= 3)
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
./gradlew :ui-hud:test --tests "*TelemetryChipsTest*"
```

Expected: FAIL — `Unresolved reference: chipsFor`.

- [ ] **Step 3: Write the chips**

Create `ui-hud/src/main/kotlin/com/jarvis/hud/TelemetryChips.kt`:

```kotlin
package com.jarvis.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.jarvis.device.ThermalState

enum class ChipHighlight { NONE, ACTIVE, WARN }

data class ChipData(val label: String, val highlight: ChipHighlight)

/**
 * Builds the corner telemetry chips.
 *
 * Kept to at most three: these sit in the corner precisely so they do not
 * compete with the reactor, and a fourth chip breaks that read.
 */
fun chipsFor(state: ReactorState, residentGb: Float): List<ChipData> {
    val chips = mutableListOf<ChipData>()

    val generating = state.mode == ReactorMode.GENERATING || state.mode == ReactorMode.THERMAL
    if (generating && state.tokensPerSecond > 0f) {
        chips += ChipData(
            label = String.format("%.1f tok/s", state.tokensPerSecond),
            highlight = if (state.isWarmPalette()) ChipHighlight.WARN else ChipHighlight.ACTIVE
        )
    }

    chips += ChipData(
        label = String.format("%.1f GB", residentGb),
        highlight = ChipHighlight.NONE
    )

    chips += ChipData(
        label = state.thermalState.name,
        highlight = if (state.thermalState >= ThermalState.SEVERE) ChipHighlight.WARN else ChipHighlight.NONE
    )

    return chips.take(3)
}

@Composable
fun TelemetryChips(chips: List<ChipData>, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (chip in chips) {
            val color = when (chip.highlight) {
                ChipHighlight.NONE -> JarvisColors.Neutral
                ChipHighlight.ACTIVE -> JarvisColors.Cool
                ChipHighlight.WARN -> JarvisColors.Amber
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(JarvisColors.Panel.copy(alpha = 0.72f))
                    .border(1.dp, JarvisColors.HairlineLo, CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .semantics { contentDescription = chip.label }
            ) {
                androidx.compose.foundation.Canvas(Modifier.size(4.dp)) {
                    drawCircle(color)
                }
                Text(text = chip.label, style = MonoStyle, color = color)
            }
        }
    }
}
```

- [ ] **Step 4: Write the chat screen**

Create `ui-hud/src/main/kotlin/com/jarvis/hud/ChatScreen.kt`:

```kotlin
package com.jarvis.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.data.Message

@Composable
fun ChatScreen(
    state: ReactorState,
    messages: List<Message>,
    streamingText: String,
    residentGb: Float,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Follow the newest content as tokens arrive.
    LaunchedEffect(messages.size, streamingText) {
        val lastIndex = messages.size
        if (lastIndex > 0) listState.animateScrollToItem(lastIndex)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(JarvisColors.Ground)
    ) {
        Column(Modifier.fillMaxSize()) {

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(254.dp)
            ) {
                Reactor(state = state, modifier = Modifier.fillMaxSize())
                Text(
                    text = captionFor(state),
                    style = MonoStyle,
                    color = JarvisColors.NeutralDim,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                )
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(JarvisColors.HairlineLo)
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    MessageRow(message.role, message.content)
                }
                if (streamingText.isNotEmpty()) {
                    item(key = "streaming") {
                        MessageRow(Message.ROLE_ASSISTANT, streamingText)
                    }
                }
            }

            Composer(
                draft = draft,
                onDraftChange = { draft = it },
                onSend = {
                    if (draft.isNotBlank()) {
                        onSend(draft)
                        draft = ""
                    }
                }
            )
        }

        TelemetryChips(
            chips = chipsFor(state, residentGb),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 12.dp, end = 12.dp)
        )
    }
}

private fun captionFor(state: ReactorState): String = when (state.mode) {
    ReactorMode.IDLE -> "STANDBY"
    ReactorMode.THINKING -> "THINKING"
    ReactorMode.GENERATING -> "GENERATING"
    ReactorMode.THERMAL -> "THROTTLING"
    ReactorMode.LOW_POWER -> "LOW POWER"
}

@Composable
private fun MessageRow(role: String, content: String) {
    if (role == Message.ROLE_USER) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                text = content,
                color = JarvisColors.Text,
                fontSize = 13.sp,
                modifier = Modifier
                    .background(JarvisColors.PanelHi, RoundedCornerShape(12.dp, 12.dp, 3.dp, 12.dp))
                    .border(1.dp, JarvisColors.Hairline, RoundedCornerShape(12.dp, 12.dp, 3.dp, 12.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            )
        }
    } else {
        Column(Modifier.fillMaxWidth()) {
            Text("JARVIS", style = MonoStyle, color = JarvisColors.CoolDeep)
            Text(
                text = content,
                color = JarvisColors.Text,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp)
            )
        }
    }
}

@Composable
private fun Composer(draft: String, onDraftChange: (String) -> Unit, onSend: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 13.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Box(
            Modifier
                .weight(1f)
                .background(JarvisColors.Panel, RoundedCornerShape(999.dp))
                .border(1.dp, JarvisColors.Hairline, RoundedCornerShape(999.dp))
                .padding(horizontal = 14.dp, vertical = 9.dp)
        ) {
            if (draft.isEmpty()) {
                Text("Message", color = JarvisColors.NeutralDim, fontSize = 13.sp)
            }
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                textStyle = TextStyle(color = JarvisColors.Text, fontSize = 13.sp),
                cursorBrush = SolidColor(JarvisColors.Cool),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Text(
            text = "SEND",
            style = MonoStyle,
            color = JarvisColors.Cool,
            modifier = Modifier
                .border(1.dp, JarvisColors.CoolDeep, RoundedCornerShape(999.dp))
                .clickable { onSend() }
                .padding(horizontal = 12.dp, vertical = 9.dp)
        )
    }
}
```

- [ ] **Step 5: Write the instrumented UI test**

Create `ui-hud/src/androidTest/kotlin/com/jarvis/hud/ChatScreenTest.kt`:

```kotlin
package com.jarvis.hud

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onNodeWithContentDescription
import com.jarvis.data.Message
import com.jarvis.device.ThermalState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChatScreenTest {

    @get:Rule val compose = createComposeRule()

    private val idle = ReactorState(
        mode = ReactorMode.IDLE,
        tokensPerSecond = 0f,
        thermalState = ThermalState.NOMINAL,
        batteryPercent = 80,
        isCharging = false
    )

    @Test
    fun rendersExistingMessages() {
        compose.setContent {
            JarvisTheme {
                ChatScreen(
                    state = idle,
                    messages = listOf(
                        Message(id = 1, conversationId = 1, role = Message.ROLE_USER, content = "hello", createdAt = 1),
                        Message(id = 2, conversationId = 1, role = Message.ROLE_ASSISTANT, content = "hi there", createdAt = 2)
                    ),
                    streamingText = "",
                    residentGb = 4.9f,
                    onSend = {}
                )
            }
        }

        compose.onNodeWithText("hello").assertIsDisplayed()
        compose.onNodeWithText("hi there").assertIsDisplayed()
    }

    @Test
    fun showsStreamingTextWhileGenerating() {
        compose.setContent {
            JarvisTheme {
                ChatScreen(
                    state = idle.copy(mode = ReactorMode.GENERATING, tokensPerSecond = 10f),
                    messages = emptyList(),
                    streamingText = "partial reply",
                    residentGb = 5.2f,
                    onSend = {}
                )
            }
        }

        compose.onNodeWithText("partial reply").assertIsDisplayed()
    }

    @Test
    fun sendingClearsTheDraftAndReportsTheText() {
        var sent: String? = null

        compose.setContent {
            JarvisTheme {
                ChatScreen(
                    state = idle,
                    messages = emptyList(),
                    streamingText = "",
                    residentGb = 4.9f,
                    onSend = { sent = it }
                )
            }
        }

        compose.onNodeWithText("Message").performTextInput("what is the plan")
        compose.onNodeWithText("SEND").performClick()

        assertEquals("what is the plan", sent)
    }

    @Test
    fun thermalStateSurfacesAsAWarningChip() {
        compose.setContent {
            JarvisTheme {
                ChatScreen(
                    state = idle.copy(mode = ReactorMode.THERMAL, thermalState = ThermalState.SEVERE),
                    messages = emptyList(),
                    streamingText = "",
                    residentGb = 5.2f,
                    onSend = {}
                )
            }
        }

        compose.onNodeWithContentDescription("SEVERE").assertIsDisplayed()
        compose.onNodeWithText("THROTTLING").assertIsDisplayed()
    }
}
```

- [ ] **Step 6: Run the unit tests, then the UI tests on the device**

```bash
./gradlew :ui-hud:test
./gradlew :ui-hud:connectedDebugAndroidTest
```

Expected: unit tests PASS (23 total), instrumented tests PASS (4).

- [ ] **Step 7: Commit**

```bash
git add ui-hud
git commit -m "feat(ui-hud): telemetry chips and chat screen

Telemetry sits in at most three corner chips rather than a full-width bar,
so it stays glanceable without competing with the reactor. Streaming text
renders as a pending row so tokens appear as they arrive."
```

---

### Task 12: ui-hud — boot screen and view model

**Files:**
- Create: `ui-hud/src/main/kotlin/com/jarvis/hud/BootScreen.kt`
- Create: `ui-hud/src/main/kotlin/com/jarvis/hud/ChatViewModel.kt`
- Test: `ui-hud/src/test/kotlin/com/jarvis/hud/ChatViewModelTest.kt`

**Interfaces:**
- Consumes: `ModelRecommender`, `AndroidDeviceProbe`, `RuntimeTuner`, `ModelDownloader`, `InferenceEngine`, `JarvisDatabase`
- Produces:
  - `data class ChatUiState(reactor: ReactorState, messages: List<Message>, streamingText: String, residentGb: Float, isReady: Boolean)`
  - `class ChatViewModel(engine: InferenceEngine, db: JarvisDatabase, tuner: RuntimeTuner, readings: () -> DeviceReadings) : ViewModel { val uiState: StateFlow<ChatUiState>; fun send(text: String); fun stop() }`
  - `@Composable fun BootScreen(readings: DeviceReadings, recommendation: Recommendation, progress: DownloadProgress?, onStart: (ModelCandidate) -> Unit)`

- [ ] **Step 1: Write the failing view model test**

Create `ui-hud/src/test/kotlin/com/jarvis/hud/ChatViewModelTest.kt`:

```kotlin
package com.jarvis.hud

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.jarvis.data.JarvisDatabase
import com.jarvis.data.Message
import com.jarvis.device.DeviceReadings
import com.jarvis.device.RuntimeTuner
import com.jarvis.inference.FakeEngine
import com.jarvis.inference.InferenceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ChatViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var db: JarvisDatabase

    private fun readings() = DeviceReadings(
        totalRamBytes = 12L * 1024 * 1024 * 1024,
        availableRamBytes = 7L * 1024 * 1024 * 1024,
        perAppMemoryLimitBytes = 8L * 1024 * 1024 * 1024,
        freeStorageBytes = 60L * 1024 * 1024 * 1024,
        cpuCores = 8,
        socModel = "SM8650",
        thermalHeadroom = 0.2f,
        batteryPercent = 80,
        isCharging = false
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            JarvisDatabase::class.java
        )
            .allowMainThreadQueries()
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(sqlDb: SupportSQLiteDatabase) {
                    sqlDb.execSQL("PRAGMA foreign_keys=ON;")
                }
            })
            .build()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private suspend fun newViewModel(reply: String = "one two three"): ChatViewModel {
        val engine = FakeEngine(scriptedReply = reply, tokenDelayMillis = 0)
        engine.load(File("ignored"), InferenceConfig(4, 2048, 256))
        return ChatViewModel(engine, db, RuntimeTuner(), ::readings)
    }

    @Test
    fun `starts idle with no messages`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.uiState.test {
            val first = awaitItem()
            assertEquals(ReactorMode.IDLE, first.reactor.mode)
            assertTrue(first.messages.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `sending persists the user message`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.send("hello")
        dispatcher.scheduler.advanceUntilIdle()

        val stored = db.conversations().observeAllOnce()
        assertEquals(1, stored.size)
        val messages = db.messages().forConversation(stored[0].id)
        assertEquals(Message.ROLE_USER, messages[0].role)
        assertEquals("hello", messages[0].content)
    }

    @Test
    fun `assistant reply is persisted with measured throughput`() = runTest(dispatcher) {
        val vm = newViewModel(reply = "alpha beta")
        vm.send("hi")
        dispatcher.scheduler.advanceUntilIdle()

        val conversation = db.conversations().observeAllOnce().first()
        val messages = db.messages().forConversation(conversation.id)
        assertEquals(2, messages.size)
        assertEquals(Message.ROLE_ASSISTANT, messages[1].role)
        assertEquals("alpha beta", messages[1].content.trim())
    }

    @Test
    fun `streaming text is cleared once the reply is persisted`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.send("hi")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals("", vm.uiState.value.streamingText)
    }

    @Test
    fun `blank input is ignored`() = runTest(dispatcher) {
        val vm = newViewModel()
        vm.send("   ")
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(db.conversations().observeAllOnce().isEmpty())
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
./gradlew :ui-hud:test --tests "*ChatViewModelTest*"
```

Expected: FAIL — `Unresolved reference: ChatViewModel`.

- [ ] **Step 3: Write the view model**

Create `ui-hud/src/main/kotlin/com/jarvis/hud/ChatViewModel.kt`:

```kotlin
package com.jarvis.hud

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.data.Conversation
import com.jarvis.data.JarvisDatabase
import com.jarvis.data.Message
import com.jarvis.device.DeviceReadings
import com.jarvis.device.RuntimeTuner
import com.jarvis.inference.ChatTurn
import com.jarvis.inference.InferenceEngine
import com.jarvis.inference.Token
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class ChatUiState(
    val reactor: ReactorState,
    val messages: List<Message> = emptyList(),
    val streamingText: String = "",
    val residentGb: Float = 0f,
    val isReady: Boolean = false
)

/**
 * Owns one conversation and the generation lifecycle.
 *
 * Throughput in the reactor state is measured from real token arrivals rather
 * than the model's advertised speed, which is what makes the HUD honest when
 * the device throttles.
 */
class ChatViewModel(
    private val engine: InferenceEngine,
    private val db: JarvisDatabase,
    private val tuner: RuntimeTuner,
    private val readings: () -> DeviceReadings
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ChatUiState(reactor = reactorStateNow(isGenerating = false, isThinking = false, tps = 0f))
    )
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var conversationId: Long? = null
    private var generation: Job? = null

    fun send(text: String) {
        val prompt = text.trim()
        if (prompt.isEmpty()) return

        generation = viewModelScope.launch {
            val now = System.currentTimeMillis()
            val convId = conversationId ?: db.conversations()
                .insert(Conversation(title = prompt.take(40), createdAt = now, updatedAt = now))
                .also { conversationId = it }

            db.messages().insert(
                Message(conversationId = convId, role = Message.ROLE_USER, content = prompt, createdAt = now)
            )
            refreshMessages(convId)

            _uiState.value = _uiState.value.copy(
                reactor = reactorStateNow(isGenerating = false, isThinking = true, tps = 0f)
            )

            val history = db.messages().forConversation(convId)
                .dropLast(1)
                .map { ChatTurn(it.role, it.content) }

            val builder = StringBuilder()
            var tokenCount = 0
            val startedAt = System.currentTimeMillis()

            engine.generate(history, prompt).collect { token ->
                when (token) {
                    is Token.Text -> {
                        builder.append(token.value)
                        tokenCount++
                        val elapsed = (System.currentTimeMillis() - startedAt).coerceAtLeast(1)
                        val tps = tokenCount * 1000f / elapsed
                        _uiState.value = _uiState.value.copy(
                            streamingText = builder.toString(),
                            reactor = reactorStateNow(isGenerating = true, isThinking = false, tps = tps)
                        )
                    }

                    is Token.Complete -> {
                        val tps = if (token.elapsedMillis > 0) {
                            token.tokenCount * 1000f / token.elapsedMillis
                        } else 0f

                        db.messages().insert(
                            Message(
                                conversationId = convId,
                                role = Message.ROLE_ASSISTANT,
                                content = builder.toString(),
                                createdAt = System.currentTimeMillis(),
                                tokensPerSecond = tps
                            )
                        )
                        db.conversations().touch(convId, System.currentTimeMillis())
                        refreshMessages(convId)

                        _uiState.value = _uiState.value.copy(
                            streamingText = "",
                            reactor = reactorStateNow(isGenerating = false, isThinking = false, tps = 0f)
                        )
                    }

                    is Token.Error -> {
                        db.messages().insert(
                            Message(
                                conversationId = convId,
                                role = Message.ROLE_ASSISTANT,
                                content = token.message,
                                createdAt = System.currentTimeMillis()
                            )
                        )
                        refreshMessages(convId)
                        _uiState.value = _uiState.value.copy(
                            streamingText = "",
                            reactor = reactorStateNow(isGenerating = false, isThinking = false, tps = 0f)
                        )
                    }
                }
            }
        }
    }

    fun stop() {
        generation?.cancel()
        viewModelScope.launch { engine.cancel() }
    }

    private suspend fun refreshMessages(conversationId: Long) {
        _uiState.value = _uiState.value.copy(
            messages = db.messages().forConversation(conversationId),
            isReady = true
        )
    }

    private fun reactorStateNow(isGenerating: Boolean, isThinking: Boolean, tps: Float): ReactorState {
        val current = readings()
        val thermal = tuner.thermalState(current.thermalHeadroom)
        return ReactorState(
            mode = reactorModeFor(
                isGenerating = isGenerating,
                isThinking = isThinking,
                thermal = thermal,
                batteryPercent = current.batteryPercent,
                isCharging = current.isCharging
            ),
            tokensPerSecond = tps,
            thermalState = thermal,
            batteryPercent = current.batteryPercent,
            isCharging = current.isCharging
        )
    }
}
```

- [ ] **Step 4: Write the boot screen**

Create `ui-hud/src/main/kotlin/com/jarvis/hud/BootScreen.kt`:

```kotlin
package com.jarvis.hud

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.device.DeviceReadings
import com.jarvis.device.ModelCandidate
import com.jarvis.device.Recommendation
import com.jarvis.model.DownloadProgress

private const val GB = 1024f * 1024f * 1024f

@Composable
fun BootScreen(
    readings: DeviceReadings,
    recommendation: Recommendation,
    progress: DownloadProgress?,
    onStart: (ModelCandidate) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(JarvisColors.Ground)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text("SYSTEM SCAN", style = MonoStyle, color = JarvisColors.CoolDeep)
        Box(Modifier.height(12.dp))

        ScanRow("Device", readings.socModel)
        ScanRow("Memory", "${gb(readings.totalRamBytes)} GB · ${gb(readings.availableRamBytes)} free")
        ScanRow("Cores", readings.cpuCores.toString())
        ScanRow("Storage", "${gb(readings.freeStorageBytes)} GB free")
        ScanRow("Thermal", if (readings.thermalHeadroom < 0.5f) "nominal" else "elevated")

        Box(Modifier.height(18.dp))

        val best = recommendation.best
        if (best == null) {
            Text(
                text = recommendation.reasons.firstOrNull() ?: "No model fits this device.",
                color = JarvisColors.Red,
                fontSize = 13.sp
            )
            return@Column
        }

        Column(
            Modifier
                .fillMaxWidth()
                .border(1.dp, JarvisColors.CoolDeep)
                .padding(13.dp)
        ) {
            Text("RECOMMENDED", style = MonoStyle, color = JarvisColors.CoolDeep)
            Text(best.displayName, color = JarvisColors.Text, fontSize = 17.sp)
            Text(
                text = "${gb(best.fileBytes)} GB · ~${best.expectedTokensPerSecond.toInt()} tok/s",
                color = JarvisColors.Neutral,
                fontSize = 11.sp
            )
            for (reason in recommendation.reasons) {
                Text(reason, color = JarvisColors.NeutralDim, fontSize = 10.sp)
            }
        }

        Box(Modifier.height(10.dp))

        for (alternative in recommendation.alternatives) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onStart(alternative) }
                    .padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(alternative.displayName, color = JarvisColors.NeutralDim, fontSize = 11.sp)
                Text(
                    "${gb(alternative.fileBytes)} GB · ~${alternative.expectedTokensPerSecond.toInt()} tok/s",
                    color = JarvisColors.NeutralDim,
                    fontSize = 11.sp
                )
            }
        }

        Box(Modifier.height(16.dp))

        when (progress) {
            null -> Text(
                text = "BEGIN",
                style = MonoStyle,
                color = JarvisColors.Cool,
                modifier = Modifier
                    .border(1.dp, JarvisColors.CoolDeep)
                    .clickable { onStart(best) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            )

            is DownloadProgress.Running -> {
                val fraction = if (progress.totalBytes > 0) {
                    progress.bytesDownloaded.toFloat() / progress.totalBytes
                } else 0f
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(JarvisColors.Hairline)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .height(3.dp)
                            .background(JarvisColors.Cool)
                    )
                }
                Text(
                    "Downloading · ${gb(progress.bytesDownloaded)} / ${gb(progress.totalBytes)} GB",
                    style = MonoStyle,
                    color = JarvisColors.NeutralDim
                )
            }

            DownloadProgress.Verifying ->
                Text("Verifying checksum", style = MonoStyle, color = JarvisColors.Cool)

            is DownloadProgress.Done ->
                Text("Ready", style = MonoStyle, color = JarvisColors.Cool)

            is DownloadProgress.Failed -> Column {
                Text(progress.reason, color = JarvisColors.Red, fontSize = 12.sp)
                if (progress.canRetry) {
                    Text(
                        text = "RETRY",
                        style = MonoStyle,
                        color = JarvisColors.Cool,
                        modifier = Modifier
                            .border(1.dp, JarvisColors.CoolDeep)
                            .clickable { onStart(best) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ScanRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MonoStyle, color = JarvisColors.Neutral)
        Text(value, style = MonoStyle, color = JarvisColors.Text)
    }
}

private fun gb(bytes: Long): String = String.format("%.1f", bytes / GB)
```

- [ ] **Step 5: Run the tests and verify they pass**

```bash
./gradlew :ui-hud:test
```

Expected: PASS, 28 tests.

- [ ] **Step 6: Commit**

```bash
git add ui-hud
git commit -m "feat(ui-hud): boot screen and chat view model

Throughput shown in the HUD is measured from real token arrivals rather
than the model's advertised rate, so the readout stays honest when the
device throttles. Boot screen shows the recommendation with its reasoning
and keeps alternatives selectable."
```

---

### Task 13: app — wiring and navigation

**Files:**
- Modify: `app/build.gradle.kts` — add module dependencies
- Modify: `app/src/main/java/com/jarvis/mobile/MainActivity.kt` — replace the placeholder
- Create: `app/src/main/java/com/jarvis/mobile/AppContainer.kt`
- Create: `app/src/main/java/com/jarvis/mobile/AppRoot.kt`
- Test: `app/src/test/kotlin/com/jarvis/mobile/AppContainerTest.kt`

**Interfaces:**
- Consumes: everything from Tasks 1-12
- Produces:
  - `class AppContainer(context: Context) { val probe: AndroidDeviceProbe; val recommender: ModelRecommender; val tuner: RuntimeTuner; val db: JarvisDatabase; val modelRepository: ModelRepository; val downloader: ModelDownloader; val engine: InferenceEngine; val modelsDir: File }`
  - `sealed interface AppScreen { data object Boot : AppScreen; data class Chat(modelId: String) : AppScreen }`
  - `@Composable fun AppRoot(container: AppContainer)`

- [ ] **Step 1: Add module dependencies**

In `app/build.gradle.kts`, replace the `dependencies` block with:

```kotlin
dependencies {
    implementation(project(":ui-hud"))
    implementation(project(":core-device"))
    implementation(project(":core-device-android"))
    implementation(project(":core-model"))
    implementation(project(":core-inference"))
    implementation(project(":core-data"))

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.graphics)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
```

Also add to the `android { }` block:

```kotlin
    testOptions { unitTests.isIncludeAndroidResources = true }
```

- [ ] **Step 2: Write the failing container test**

Create `app/src/test/kotlin/com/jarvis/mobile/AppContainerTest.kt`:

```kotlin
package com.jarvis.mobile

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AppContainerTest {

    private val container = AppContainer(ApplicationProvider.getApplicationContext())

    @Test
    fun `exposes every collaborator the app needs`() {
        assertNotNull(container.probe)
        assertNotNull(container.recommender)
        assertNotNull(container.tuner)
        assertNotNull(container.db)
        assertNotNull(container.modelRepository)
        assertNotNull(container.downloader)
        assertNotNull(container.engine)
    }

    @Test
    fun `creates the models directory under app storage`() {
        assertTrue(container.modelsDir.exists())
        assertTrue(container.modelsDir.isDirectory)
    }

    @Test
    fun `device probe returns a usable reading`() {
        val readings = container.probe.read()
        assertTrue(readings.totalRamBytes > 0)
        assertTrue(readings.cpuCores >= 1)
    }

    @Test
    fun `recommender produces a decision for the probed device`() {
        val recommendation = container.recommender.recommend(container.probe.read())
        assertTrue(recommendation.reasons.isNotEmpty())
    }
}
```

- [ ] **Step 3: Run the test and verify it fails**

```bash
./gradlew :app:test
```

Expected: FAIL — `Unresolved reference: AppContainer`.

- [ ] **Step 4: Write the container**

Create `app/src/main/java/com/jarvis/mobile/AppContainer.kt`:

```kotlin
package com.jarvis.mobile

import android.content.Context
import com.jarvis.data.JarvisDatabase
import com.jarvis.device.ModelRecommender
import com.jarvis.device.RuntimeTuner
import com.jarvis.device.android.AndroidDeviceProbe
import com.jarvis.inference.InferenceEngine
import com.jarvis.inference.LlamaEngine
import com.jarvis.model.ModelDownloader
import com.jarvis.model.ModelRepository
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manual dependency wiring.
 *
 * A DI framework would add build time and indirection for one screen graph;
 * this container is the whole object graph and stays readable at a glance.
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val modelsDir: File = File(appContext.filesDir, "models").apply { mkdirs() }

    val probe = AndroidDeviceProbe(appContext)
    val recommender = ModelRecommender()
    val tuner = RuntimeTuner()

    val db: JarvisDatabase = JarvisDatabase.open(appContext)

    val modelRepository = ModelRepository(modelsDir)

    private val httpClient = OkHttpClient.Builder()
        // A multi-gigabyte download over mobile needs a generous read timeout;
        // the default 10s aborts on any brief signal drop.
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    val downloader = ModelDownloader(httpClient, modelsDir)

    val engine: InferenceEngine = LlamaEngine()
}
```

- [ ] **Step 5: Write the root composable**

Create `app/src/main/java/com/jarvis/mobile/AppRoot.kt`:

```kotlin
package com.jarvis.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.jarvis.device.ModelCandidate
import com.jarvis.hud.BootScreen
import com.jarvis.hud.ChatScreen
import com.jarvis.hud.ChatViewModel
import com.jarvis.inference.toInferenceConfig
import com.jarvis.model.DownloadProgress
import com.jarvis.model.ModelRegistry
import kotlinx.coroutines.launch

sealed interface AppScreen {
    data object Boot : AppScreen
    data class Chat(val modelId: String) : AppScreen
}

@Composable
fun AppRoot(container: AppContainer) {
    val scope = rememberCoroutineScope()

    val readings = remember { container.probe.read() }
    val recommendation = remember { container.recommender.recommend(readings) }

    var screen by remember {
        mutableStateOf<AppScreen>(
            // Skip the boot sequence entirely if a verified model is already present.
            recommendation.best
                ?.takeIf { container.modelRepository.isInstalled(it.id) }
                ?.let { AppScreen.Chat(it.id) }
                ?: AppScreen.Boot
        )
    }
    var progress by remember { mutableStateOf<DownloadProgress?>(null) }

    when (val current = screen) {
        AppScreen.Boot -> BootScreen(
            readings = readings,
            recommendation = recommendation,
            progress = progress,
            onStart = { candidate: ModelCandidate ->
                val spec = ModelRegistry.specFor(candidate.id) ?: return@BootScreen
                scope.launch {
                    container.downloader.download(spec).collect { update ->
                        progress = update
                        if (update is DownloadProgress.Done) {
                            screen = AppScreen.Chat(candidate.id)
                        }
                    }
                }
            }
        )

        is AppScreen.Chat -> {
            val viewModel = remember(current.modelId) {
                ChatViewModel(
                    engine = container.engine,
                    db = container.db,
                    tuner = container.tuner,
                    readings = { container.probe.read() }
                )
            }

            LaunchedEffect(current.modelId) {
                val file = container.modelRepository.installedFile(current.modelId) ?: return@LaunchedEffect
                if (!container.engine.isLoaded) {
                    val params = container.tuner.tune(container.probe.read())
                    container.engine.load(file, params.toInferenceConfig())
                }
            }

            val state by viewModel.uiState.collectAsStateWithLifecycle()

            ChatScreen(
                state = state.reactor,
                messages = state.messages,
                streamingText = state.streamingText,
                residentGb = residentGbFor(current.modelId),
                onSend = viewModel::send,
                modifier = Modifier
            )
        }
    }
}

private fun residentGbFor(modelId: String): Float =
    com.jarvis.device.ModelCatalog.ALL
        .firstOrNull { it.id == modelId }
        ?.let { it.residentBytes / (1024f * 1024f * 1024f) }
        ?: 0f
```

Add the lifecycle-aware state collection dependency. In `gradle/libs.versions.toml` under `[libraries]`:

```toml
lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
```

In `app/build.gradle.kts` dependencies add:

```kotlin
    implementation(libs.lifecycle.runtime.compose)
```

And in `AppRoot.kt` add the import:

```kotlin
import androidx.lifecycle.compose.collectAsStateWithLifecycle
```

- [ ] **Step 6: Replace MainActivity**

Replace `app/src/main/java/com/jarvis/mobile/MainActivity.kt` with:

```kotlin
package com.jarvis.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jarvis.hud.JarvisTheme

class MainActivity : ComponentActivity() {

    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container = AppContainer(this)
        setContent {
            JarvisTheme {
                AppRoot(container)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // A ~5 GB resident model must not survive the activity; leaking it here
        // is the fastest way to get the process killed by the OS.
        if (isFinishing) {
            kotlinx.coroutines.runBlocking { container.engine.unload() }
        }
    }
}
```

- [ ] **Step 7: Run the tests and build**

```bash
./gradlew :app:test
./gradlew :app:assembleDebug
```

Expected: tests PASS (4), build SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app gradle/libs.versions.toml
git commit -m "feat(app): wire modules together with manual DI

AppContainer is the whole object graph, readable at a glance without a DI
framework. Boot is skipped when a verified model is already installed. The
model is unloaded when the activity finishes, since a 5 GB resident
allocation that outlives its screen invites process death."
```

---

### Task 14: Pin model checksums

`ModelRegistry` ships with `PIN_IN_TASK_12` placeholders from Task 6. Every download will fail verification until they are replaced. **This is a hard gate: an unverified model file is arbitrary data handed to a native parser.**

Note: the placeholder text says Task 12 because it was written before this task was renumbered; the string value itself is what matters, not the number in it.

**Files:**
- Modify: `core-model/src/main/kotlin/com/jarvis/model/ModelSpec.kt`
- Test: `core-model/src/test/kotlin/com/jarvis/model/ModelRegistryTest.kt`

**Interfaces:**
- Consumes: `ModelRegistry` from Task 6
- Produces: real SHA-256 values and sizes for all three models

- [ ] **Step 1: Write the failing test**

Create `core-model/src/test/kotlin/com/jarvis/model/ModelRegistryTest.kt`:

```kotlin
package com.jarvis.model

import com.jarvis.device.ModelCatalog
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelRegistryTest {

    @Test
    fun `every catalog entry has a download spec`() {
        for (candidate in ModelCatalog.ALL) {
            assertNotNull("no spec for ${candidate.id}", ModelRegistry.specFor(candidate.id))
        }
    }

    @Test
    fun `no placeholder checksums remain`() {
        for (candidate in ModelCatalog.ALL) {
            val spec = ModelRegistry.specFor(candidate.id)!!
            assertTrue(
                "${candidate.id} still has a placeholder checksum",
                !spec.sha256.contains("PIN_IN", ignoreCase = true)
            )
        }
    }

    @Test
    fun `checksums are well formed sha256 hex`() {
        val hex = Regex("^[0-9a-f]{64}$")
        for (candidate in ModelCatalog.ALL) {
            val spec = ModelRegistry.specFor(candidate.id)!!
            assertTrue(
                "${candidate.id} checksum is not 64 hex chars: ${spec.sha256}",
                hex.matches(spec.sha256.lowercase())
            )
        }
    }

    @Test
    fun `urls are https`() {
        for (candidate in ModelCatalog.ALL) {
            val spec = ModelRegistry.specFor(candidate.id)!!
            assertTrue("${candidate.id} is not https", spec.url.startsWith("https://"))
        }
    }

    @Test
    fun `declared sizes are within ten percent of the catalog file size`() {
        for (candidate in ModelCatalog.ALL) {
            val spec = ModelRegistry.specFor(candidate.id)!!
            val ratio = spec.sizeBytes.toDouble() / candidate.fileBytes
            assertTrue(
                "${candidate.id} size ${spec.sizeBytes} disagrees with catalog ${candidate.fileBytes}",
                ratio in 0.90..1.10
            )
        }
    }
}
```

- [ ] **Step 2: Run the test and verify it fails**

```bash
./gradlew :core-model:test --tests "*ModelRegistryTest*"
```

Expected: FAIL on `no placeholder checksums remain`.

- [ ] **Step 3: Download each model and compute its real checksum**

For each of the three models, download once on the desktop and hash it:

```bash
curl -L -o Qwen3-8B-Q4_K_M.gguf \
  "https://huggingface.co/Qwen/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf"
sha256sum Qwen3-8B-Q4_K_M.gguf
stat -c %s Qwen3-8B-Q4_K_M.gguf
```

Repeat for `Qwen3-4B-Q4_K_M.gguf` and `Qwen3-1.7B-Q4_K_M.gguf`, substituting the matching URL from `ModelSpec.kt`.

If a URL 404s, the repository layout has changed. Open the model's Hugging Face page, find the current Q4_K_M filename, and update both the URL and the filename together.

- [ ] **Step 4: Replace the placeholders**

In `core-model/src/main/kotlin/com/jarvis/model/ModelSpec.kt`, replace each `sha256 = "PIN_IN_TASK_12"` with the value from `sha256sum`, and each `sizeBytes` with the value from `stat`. Then delete the stale comment above `SPECS` that says the checksums are placeholders, and replace it with:

```kotlin
/**
 * Download sources for each catalog entry.
 *
 * Checksums are pinned against the published files. If a download begins
 * failing verification, the upstream file was republished; re-hash it and
 * update here rather than relaxing the check.
 */
```

- [ ] **Step 5: Run the tests and verify they pass**

```bash
./gradlew :core-model:test
```

Expected: PASS, 14 tests.

- [ ] **Step 6: Commit**

```bash
git add core-model
git commit -m "feat(core-model): pin real SHA-256 checksums for all models

Replaces the placeholders that made every download fail verification.
Tests now assert no placeholder survives and that each checksum is
well-formed hex, so this cannot silently regress."
```

---

### Task 15: On-device validation and calibration

The spec requires measuring what no emulator reports honestly, then calibrating the recommender against reality.

**Files:**
- Create: `docs/superpowers/measurements/2026-08-20-oneplus-13r.md`
- Modify: `core-device/src/main/kotlin/com/jarvis/device/ModelRecommender.kt` (only if measurements disagree with predictions)
- Modify: `core-device/src/test/kotlin/com/jarvis/device/ModelRecommenderTest.kt` (only if thresholds change)

**Interfaces:**
- Consumes: the complete app from Tasks 1-14
- Produces: a measurements document, and calibrated thresholds if needed

- [ ] **Step 1: Install the release-mode build on the phone**

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release-unsigned.apk
```

If the install is refused for lacking a signature, use the debug build instead and note it in the measurements document — debug builds run slower, which matters when recording tok/s.

- [ ] **Step 2: Record the cold-start and load measurements**

Launch the app, complete the download, then with logcat running:

```bash
adb logcat -c
adb logcat -s JarvisLlama
```

Record: time from launch to boot screen, download duration, model load duration, and RAM after load:

```bash
adb shell dumpsys meminfo com.jarvis.mobile | head -20
```

- [ ] **Step 3: Measure sustained throughput and thermal behaviour**

Send a prompt that generates a long reply, for example: "Explain how a transformer language model works, in detail." Then record:

- Time to first token
- Tokens per second over the first 30 seconds
- Tokens per second after 5 minutes of continuous generation
- Thermal state transitions observed in the HUD chips
- Whether the app survives being backgrounded for 2 minutes and returned to

```bash
adb shell dumpsys thermalservice | grep -A5 "Current temperatures"
```

- [ ] **Step 4: Write the measurements document**

Create `docs/superpowers/measurements/2026-08-20-oneplus-13r.md` recording every value from Steps 2 and 3, each with the date and build type. Include a "Predicted vs actual" table comparing the recommender's `expectedTokensPerSecond` against what was measured.

- [ ] **Step 5: Calibrate if the prediction was wrong**

If measured throughput differs from `expectedTokensPerSecond` by more than 30%, update the value in `ModelCatalog.ALL` to the measured figure.

If the 8B model failed to load, was killed by the OS, or throttled to below 4 tok/s within 5 minutes, raise `RAM_SAFETY_FACTOR` in `ModelRecommender` from `1.35` until the 13R profile recommends the 4B instead, then update the expected value in `ModelRecommenderTest.flagship with 12GB recommends the 8B model` to match the new behaviour and rename that test accordingly.

Conservative is correct here: the spec is explicit that a model which runs well beats one that technically fits.

- [ ] **Step 6: Re-run the full test suite**

```bash
./gradlew test
```

Expected: PASS across all modules.

- [ ] **Step 7: Commit**

```bash
git add docs core-device
git commit -m "test: on-device measurements and recommender calibration

Records real load time, sustained throughput, and thermal behaviour on the
OnePlus 13R, and calibrates the recommender against them. Predictions that
disagreed with reality by more than 30% were replaced with measured values."
```

---

## Definition of Done

Phase 1 is complete when all of the following hold:

1. `./gradlew test` passes across every module.
2. `./gradlew :core-inference:connectedDebugAndroidTest` and `:ui-hud:connectedDebugAndroidTest` pass on the OnePlus 13R.
3. The app installs, scans the device, recommends a model with visible reasoning, downloads it with a resumable and verified transfer, and reaches the chat screen.
4. Sending a message streams tokens into the HUD, and the reactor's pulse rate visibly tracks generation speed.
5. Thermal stress changes the reactor palette and the telemetry chips, and the thread-count reduction is stated rather than hidden.
6. Backgrounding the app for two minutes and returning does not crash or hang it.
7. `docs/superpowers/measurements/2026-08-20-oneplus-13r.md` records real measurements, and the recommender is calibrated against them.
8. No placeholder checksums remain in `ModelRegistry`.

## Deferred to later phases

Confirmed out of scope for phase 1, listed so they are not mistaken for gaps:

- Voice input and output (phase 1b)
- Personal memory (phase 2)
- Document Q&A (phase 3)
- Device actions and lock-screen control (phase 4)
- iOS build
- Conversation list UI: phase 1 uses a single rolling conversation
- Backup export UI: `BackupRepository` exists and is tested, but no screen calls it until phase 2 adds settings
