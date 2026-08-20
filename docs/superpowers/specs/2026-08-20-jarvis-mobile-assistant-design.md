# JARVIS Mobile — Design Spec

**Date:** 2026-08-20
**Status:** Approved for phase 1 implementation
**Target device:** OnePlus 13R (Snapdragon 8 Gen 3, 12 GB RAM)
**minSdk 30 (Android 11), targetSdk 35 (Android 15).** minSdk 30 is set by `PowerManager.getThermalHeadroom`, which the runtime adaptation in section 6 depends on. Devices below API 30 are not supported.

## 1. Purpose

A fully offline personal assistant for Android, running a large language model on the device itself. No accounts, no API keys, no servers, no network access except a one-time model download. The interface is a JARVIS-style HUD whose visual state reflects real system telemetry rather than decoration.

The app must be free to build and free to run, permanently. No component may depend on a third-party free tier, a hosted backend, or a subscription.

## 2. Constraints

These were settled during brainstorming and are not open questions.

- **Pure offline.** No cloud inference, no API keys, no sign-in. The only network use is downloading model weights on first run.
- **No coding assistance required.** The user does not write code on mobile. This removes the primary weakness of a mid-size local model and makes an 8B-class model sufficient.
- **Local storage only.** No Google Drive, no Firebase, no sync. Backup is a manual encrypted export file.
- **Android first.** iOS is out of scope. A future iOS port would need a separate UI layer; the core modules are written to make that possible but no iOS work is planned.
- **Text and voice both first-class**, delivered in stages. Phase 1 is text plus the full HUD. Voice follows once the core is stable.

## 3. Non-Goals

Explicitly excluded from phase 1, to keep it shippable:

- Cloud fallback of any kind
- Account systems, sync, telemetry, analytics
- iOS build
- Voice input/output (deferred to phase 1b)
- Personal memory, document Q&A, device actions (phases 2-4)
- A standalone model-management screen. The first-run recommendation screen does list alternatives and allows overriding the recommendation; what is excluded is any *separate* picker, download manager, or model-switching UI elsewhere in the app. Changing models after first run means clearing app data and re-running setup.

## 4. Phasing

Four subsystems, sequenced by dependency. Each phase gets its own implementation plan and produces a usable app.

| Phase | Scope | Rationale for order |
|---|---|---|
| 1 | Model runtime, chat, HUD, device profiling | Nothing works without inference; the HUD is the differentiator |
| 1b | Voice: offline STT + TTS | Needs a stable chat loop to attach to |
| 2 | Personal memory | Highest impact per unit of effort; no permissions needed |
| 3 | Document Q&A (local RAG) | Reuses phase 2 storage and retrieval |
| 4 | Device actions | Most platform-divergent and permission-heavy; benefits from a mature tool-calling layer |

Phases 2-4 depend on the model reliably following structured instructions and calling tools. An 8B model is competent at this but not flawless; budget real iteration time for prompt design and output validation in those phases. This is the largest known risk to the roadmap and is deliberately pushed late.

## 5. Architecture

Native Android: **Kotlin + Jetpack Compose**.

Chosen over React Native for two reasons. The HUD needs direct GPU rendering without a JavaScript bridge in the animation path, and the device-profiling module reads Android system APIs directly. The cost is that a future iOS port needs its own UI layer; given the Android-only target that trade is correct.

Five Gradle modules, each independently testable:

| Module | Responsibility | Depends on |
|---|---|---|
| `core-device` | Read RAM, thermal, storage, SoC; produce a ranked model recommendation | nothing |
| `core-model` | Download, resume, verify, and store GGUF files | `core-device` |
| `core-inference` | llama.cpp via JNI; expose streaming tokens | `core-model` |
| `core-data` | Room database; encrypted export and import | nothing |
| `ui-hud` | The JARVIS interface | all above |

Key boundaries:

- `core-device` is pure logic over *injected* system readings. It never calls Android APIs directly; a thin adapter supplies readings. This makes it fully testable against synthetic device profiles with no hardware.
- `core-inference` exposes a stream of tokens and knows nothing about the UI. A fake implementation of the same interface allows all UI and streaming work to proceed without loading a 5 GB model.
- `core-data` has no network dependency of any kind.

Phases 2-4 attach as new modules against these interfaces rather than requiring changes to existing ones.

**Inference engine:** llama.cpp compiled for `arm64-v8a`, invoked through JNI. This is the same engine Ollama wraps; Ollama itself is a desktop daemon and cannot run inside an Android app. Model files are standard GGUF.

## 6. Device Profiling and Model Selection

The app derives its model recommendation from the device rather than hardcoding a choice.

### Readable signals (no permissions required)

- Total and available RAM (`ActivityManager.MemoryInfo`) — availability matters more than total, since it determines whether a load succeeds
- Per-app memory limit (`getMemoryClass`, `getLargeMemoryClass`) — often far below total RAM and the true ceiling
- Free storage (`StatFs`)
- CPU core count and SoC identifier
- Battery level and charging state (`BatteryManager`)
- Thermal headroom (`PowerManager.getThermalHeadroom`, Android 11+)

### Deliberately not used

Precise GPU/NPU capability and battery wear are not exposed consistently across Android devices. "Device health" as a general concept is therefore not measurable; thermal headroom and battery state are used instead, as they are the signals that actually affect inference.

### Two separate decisions

**At first launch — which model to download.** Score the device on available RAM, per-app memory limit, free storage, and SoC tier. Rank candidate models and present the top one as a recommendation with visible reasoning. Alternatives remain selectable with honest tradeoffs stated. The recommendation never silently locks the user in.

**During use — runtime adaptation.** Thermal headroom and battery state tune thread count, context window, and batch size. They never trigger a model swap mid-session, because those signals are transient. When working correctly this is invisible to the user.

### Calibration

The scoring is a heuristic, not a guarantee; real throughput varies with ROM and background load. Thresholds are set conservatively — preferring a model that runs well over one that technically fits and then thermally chokes. The formula must be validated against the actual OnePlus 13R before being trusted.

### Expected outcome on the target device

The 13R (12 GB, Snapdragon 8 Gen 3) should be recommended **Qwen3 8B Q4_K_M**, roughly 4.9 GB resident, at an expected 8-12 tokens/second. This is readable-as-generated with a 1-2 second delay before the first token, and the device will warm and throttle somewhat under sustained generation. A 4B alternative (15-25 tok/s, ~3 GB) remains available for users who prefer speed over quality.

## 7. First-Run Flow

The model cannot be bundled: app store upload limits are around 200 MB and an 8B Q4 model is roughly 4.9 GB. The model always downloads separately.

First launch presents the device scan as a JARVIS boot sequence, with the diagnostics as genuine readouts:

```
SYSTEM SCAN
  Device      OnePlus 13R
  Memory      12 GB  ·  7.2 GB available
  Chipset     Snapdragon 8 Gen 3  ·  8 cores
  Storage     84 GB free
  Thermal     nominal

RECOMMENDED  Qwen3 8B (Q4_K_M)  ·  4.9 GB
             best quality  ·  ~10 tok/s expected
```

Download requirements:

- Resumable across interruption and process death
- SHA-256 verification before the file is accepted
- The boot sequence doubles as the progress display
- Failure states are explicit and retryable, never a silent stall

## 8. The HUD

This is the primary differentiator. Existing offline-LLM Android apps are functional and visually generic; the gap is a deliberate interface.

**Core principle: every animated element maps to a real system state.** The aesthetic carries telemetry. It is not a costume over a chat list.

### The reactive core

A central GPU-rendered orb bound to actual runtime state:

| State | Behavior |
|---|---|
| Idle | Slow breathing pulse, cool cyan |
| Thinking | Tightens and accelerates during prompt processing |
| Generating | Pulses in rhythm with real token arrival, making model speed perceptible |
| Thermal stress | Shifts amber as headroom drops |
| Low battery | Dims |

### Surrounding interface

- Dark translucent glass panels with thin cyan hairline borders
- Monospaced accents for numeric readouts
- Messages materialize rather than pop in
- A persistent stat strip: tokens/sec, RAM in use, thermal state — on a local-LLM app this data is genuinely interesting rather than clutter

### Restraint

No invented sci-fi text, no rings that rotate without meaning, no generic purple-blue AI gradients. Anything that animates must be driven by real state. This restraint is what separates the result from a templated "AI app" look.

## 9. Data

**Room database.** Conversations, messages, settings. Local only, with no network dependency in the module.

**Export.** A single file, AES-256 encrypted with a user-chosen passphrase. Import restores to an identical state.

**Backup reminders.** Since manual export is the only backup path, losing or wiping the device without exporting loses all history. Once meaningful history accumulates, the app surfaces a periodic, dismissible reminder. This is a deliberate mitigation of the chosen local-only design, not an afterthought.

## 10. Testing

Test-driven throughout. The module boundaries exist to make this practical.

| Module | Approach |
|---|---|
| `core-device` | Synthetic device profiles from 4 GB budget phones to 24 GB flagships; assert ranking and thresholds. No hardware required. |
| `core-data` | In-memory Room; export/import round-trips to identical state |
| `core-inference` | Fake engine emitting scripted tokens; validates streaming and UI without loading a model |
| `ui-hud` | Compose UI tests against injected fake state, including thermal and battery states |
| `core-model` | Download resume, corruption, and verification-failure paths against a local fixture server |

**On-device validation** on the OnePlus 13R covers what no emulator can report honestly: real model load time, measured tokens/second, memory behavior under Android's process management, and thermal behavior under sustained generation. The device-profiling heuristic is calibrated against these measurements.

## 11. Known Risks

1. **JNI/llama.cpp integration.** The Android build has real sharp edges (NDK toolchain, ABI configuration, memory-mapped model loading). This is the least predictable part of phase 1 and should be proven early with a minimal spike before UI work depends on it.
2. **Android process death under memory pressure.** A ~5 GB resident model is a large target. Model state must survive or reload gracefully; the app must never appear to hang after being backgrounded.
3. **Thermal throttling under sustained use.** Mitigated by runtime adaptation, but sustained long generations will slow down. This is physics, not a bug, and the HUD surfaces it honestly rather than hiding it.
4. **Profiling heuristic accuracy.** Mitigated by conservative thresholds and on-device calibration.
5. **Structured output reliability for phases 2-4.** An 8B model follows tool-calling formats imperfectly. Deferred deliberately; will need validation and retry logic when those phases begin.
