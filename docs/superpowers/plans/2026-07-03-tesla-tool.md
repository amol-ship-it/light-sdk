# Tesla Tool for LightOS — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A LightOS tool (Light Phone III) that shows Tesla vehicle status and issues signed commands (lock/unlock, charging, climate, windows) directly against Tesla's Fleet API, fully on-device.

**Architecture:** Four layers inside the `:tool` module — `auth` (credentials/tokens), `fleet` (REST), `vcp` (pure-Kotlin Vehicle Command Protocol port, verified byte-exactly against fixtures generated from Tesla's open-source Go reference), `vehicle` (repository facade the Compose UI consumes). UI is `LightScreen`/`LightViewModel` + `sdk:ui` components. A `FakeVehicleRepository` makes the whole UI walkable with no car and no credentials.

**Tech Stack:** Kotlin, Compose (via light-sdk), kotlinx-coroutines, kotlinx-serialization, ktor (transitively provided by `:sdk:client`; test deps added explicitly), DataStore, JDK crypto (P-256 ECDH, AES-GCM). All within the light-sdk dependency allow-list.

**Spec:** `docs/superpowers/specs/2026-07-02-tesla-tool-design.md`

**Repo context for the worker:**
- Everything happens in the light-sdk repo (branch `tesla-tool`). The tool lives in `tool/`; you edit only `tool/**`, `docs/**`, and `scripts/tesla/**`.
- The build plugin bans many APIs (`Context`, `Intent`, reflection, `getSystemService`, `LocalContext.current`, …) and non-allow-listed dependencies. If the build fails with "Light SDK: build configuration violations", you used a banned API — fix the code, don't fight the plugin.
- Reference idioms: `examples/weather` (ktor + serialization + DataStore usage), `tool/src/main/kotlin/com/thelightphone/sample/HomeScreen.kt` (screen/VM idiom — will be deleted in Task 1 but read it first).
- Run unit tests: `./gradlew :tool:testDebugUnitTest`. Full check: `./gradlew check`. Install on emulator: `./gradlew :tool:installDebug`.
- Every commit message ends with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

**A note on Chunk 3 (VCP):** the codec/signer must match Tesla's wire format byte-for-byte. Field numbers and crypto details are **extracted from the vendored reference at execution time**, not hardcoded in this plan — the fixture-generation harness (Task 19) is built FIRST, and every subsequent VCP task is written test-first against those fixtures. Where this plan shows VCP message structures, treat the shapes as authoritative and the field numbers as "verify against the vendored `.proto` before implementing."

---

## Chunk 1: M1 — Skeleton, domain model, fake repository, all screens

Outcome: tool installs on the emulator, dashboard + Charge + Climate screens fully walkable against `FakeVehicleRepository`. No network.

### Task 1: Tool metadata and module reset

**Files:**
- Modify: `tool/lighttool.toml`
- Delete: `tool/src/main/kotlin/com/thelightphone/sample/HomeScreen.kt`, `tool/src/main/kotlin/com/thelightphone/sample/DetailScreen.kt`, `tool/src/main/kotlin/com/thelightphone/sample/ToolEntryPoint.kt`
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/ui/HomeScreen.kt` (placeholder)
- Modify: `tool/build.gradle.kts` (test deps only)

- [ ] **Step 1: Read the sample first.** Read `tool/src/main/kotlin/com/thelightphone/sample/HomeScreen.kt` to absorb the screen/VM idiom (`@InitialScreen`, `viewModelClass`, `createViewModel`, `Content()` with `LightTheme`), then delete all three sample files.

- [ ] **Step 2: Replace `tool/lighttool.toml` contents:**

```toml
[tool]
id = "com.amolpurohit.tesla"
label = "Tesla"
versionCode = 1
versionName = "0.1.0"
permissions = ["android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.CAMERA"]
# change if you run this on an LP3!
# serverPackage = "com.lightos"
serverPackage = "com.thelightphone.sdk.emulator"
```

- [ ] **Step 3: Minimal placeholder initial screen** at `tool/src/main/kotlin/com/amolpurohit/tesla/ui/HomeScreen.kt` (there must be exactly one `@InitialScreen` or the build fails):

```kotlin
package com.amolpurohit.tesla.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {
    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        LightTheme(colors = themeColors) {
            Box(
                modifier = Modifier.fillMaxSize().background(LightThemeTokens.colors.background),
                contentAlignment = Alignment.Center,
            ) {
                LightText(text = "Tesla", variant = LightTextVariant.Heading)
            }
        }
    }
}
```

(Verify `SimpleLightScreen` subclassing compiles exactly like this — if the SDK requires the `LightScreen` variant even for VM-less screens, mirror whatever `examples/ui-demo` does.)

- [ ] **Step 4: Add test dependencies** to `tool/build.gradle.kts` `dependencies` block (both allow-listed by prefix):

```kotlin
testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
testImplementation("io.ktor:ktor-client-mock:3.4.2")
```

- [ ] **Step 5: Build.** Run: `./gradlew :tool:assembleDebug`. Expected: BUILD SUCCESSFUL (plugin validates metadata + deps).

- [ ] **Step 6: Commit** `git add -A tool/ && git commit -m "tesla: reset tool module, metadata, placeholder home screen"`

### Task 2: Domain model

**Files:**
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/vehicle/VehicleState.kt`
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/vehicle/VehicleUiState.kt`
- Test: `tool/src/test/kotlin/com/amolpurohit/tesla/vehicle/VehicleStateTest.kt`

- [ ] **Step 1: Write the model** (serializable — it is also the cache format):

```kotlin
package com.amolpurohit.tesla.vehicle

import kotlinx.serialization.Serializable

@Serializable
enum class ChargingState { Disconnected, Stopped, Charging, Complete }

@Serializable
enum class OverheatProtectionMode { Off, NoAc, Ac }

@Serializable
data class VehicleState(
    val batteryPercent: Int,
    val rangeKm: Double,
    val chargingState: ChargingState,
    val pluggedIn: Boolean,
    val chargeLimitPercent: Int,
    val chargeAmps: Int,
    val maxChargeAmps: Int,
    val insideTempC: Double?,
    val climateOn: Boolean,
    val targetTempC: Double,
    val minTargetTempC: Double = 15.0,
    val maxTargetTempC: Double = 28.0,
    val overheatProtection: OverheatProtectionMode,
    val dogModeOn: Boolean,
    val locked: Boolean,
    val windowsOpen: Boolean,
    val asleep: Boolean,
)
```

```kotlin
package com.amolpurohit.tesla.vehicle

sealed interface VehicleUiState {
    data object NoCredentials : VehicleUiState
    data object Loading : VehicleUiState
    data class Ready(val state: VehicleState, val updatedAtMs: Long, val stale: Boolean) : VehicleUiState
    data class Asleep(val cached: VehicleState?, val updatedAtMs: Long?) : VehicleUiState
    data class Error(val kind: ErrorKind, val cached: VehicleState?, val updatedAtMs: Long?) : VehicleUiState
}

enum class ErrorKind { Offline, AuthExpired, KeyNotEnrolled, RateLimited, WakeTimeout, Unknown }
```

- [ ] **Step 2: Failing test** — JSON round-trip (this is the cache contract):

```kotlin
package com.amolpurohit.tesla.vehicle

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class VehicleStateTest {
    @Test fun `state round-trips through json`() {
        val s = VehicleState(
            batteryPercent = 72, rangeKm = 340.5, chargingState = ChargingState.Charging,
            pluggedIn = true, chargeLimitPercent = 80, chargeAmps = 16, maxChargeAmps = 32,
            insideTempC = 24.5, climateOn = false, targetTempC = 21.0,
            overheatProtection = OverheatProtectionMode.NoAc, dogModeOn = false,
            locked = true, windowsOpen = false, asleep = false,
        )
        val json = Json { ignoreUnknownKeys = true }
        assertEquals(s, json.decodeFromString<VehicleState>(json.encodeToString(VehicleState.serializer(), s)))
    }
}
```

- [ ] **Step 3: Run** `./gradlew :tool:testDebugUnitTest --tests '*VehicleStateTest*'`. Expected: PASS (model + test land together; the "failing" phase here is compilation).

- [ ] **Step 4: Commit** `git add -A tool/ && git commit -m "tesla: vehicle domain model + ui state"`

### Task 3: Repository interface + FakeVehicleRepository

**Files:**
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/vehicle/VehicleRepository.kt`
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/vehicle/FakeVehicleRepository.kt`
- Test: `tool/src/test/kotlin/com/amolpurohit/tesla/vehicle/FakeVehicleRepositoryTest.kt`

- [ ] **Step 1: The interface** — the only surface the UI ever sees. `CommandResult` carries vehicle-level rejections (spec §8):

```kotlin
package com.amolpurohit.tesla.vehicle

import kotlinx.coroutines.flow.StateFlow

sealed interface CommandResult {
    data object Success : CommandResult
    data class Rejected(val reason: String) : CommandResult
    data class Failed(val kind: ErrorKind) : CommandResult
}

// Naming note: diverges deliberately from spec §4.4's sketch (setClimateOn(Boolean) vs
// climateOn()/climateOff(); VehicleUiState gains NoCredentials) — same shape, tighter surface.
interface VehicleRepository {
    val state: StateFlow<VehicleUiState>
    suspend fun refresh()
    suspend fun wake(): CommandResult
    suspend fun lock(): CommandResult
    suspend fun unlock(): CommandResult
    suspend fun startCharging(): CommandResult
    suspend fun stopCharging(): CommandResult
    suspend fun setChargeLimit(percent: Int): CommandResult
    suspend fun setChargeAmps(amps: Int): CommandResult
    suspend fun setClimateOn(on: Boolean): CommandResult
    suspend fun setTargetTemp(celsius: Double): CommandResult
    suspend fun setOverheatProtection(mode: OverheatProtectionMode): CommandResult
    suspend fun setDogMode(on: Boolean): CommandResult
    suspend fun ventWindows(): CommandResult
    suspend fun closeWindows(): CommandResult
}
```

- [ ] **Step 2: Failing tests** (representative set — write all of these):

```kotlin
package com.amolpurohit.tesla.vehicle

import kotlinx.coroutines.test.runTest
import kotlin.test.*

class FakeVehicleRepositoryTest {
    private fun ready(repo: VehicleRepository) =
        (repo.state.value as VehicleUiState.Ready).state

    @Test fun `starts Ready with plausible state`() = runTest {
        val repo = FakeVehicleRepository()
        assertIs<VehicleUiState.Ready>(repo.state.value)
    }

    @Test fun `lock and unlock toggle locked`() = runTest {
        val repo = FakeVehicleRepository()
        repo.unlock(); assertFalse(ready(repo).locked)
        repo.lock(); assertTrue(ready(repo).locked)
    }

    @Test fun `stopCharging while charging moves to Stopped`() = runTest {
        val repo = FakeVehicleRepository()
        repo.startCharging()
        assertEquals(ChargingState.Charging, ready(repo).chargingState)
        repo.stopCharging()
        assertEquals(ChargingState.Stopped, ready(repo).chargingState)
    }

    @Test fun `startCharging while unplugged is Rejected`() = runTest {
        val repo = FakeVehicleRepository(initial = FakeVehicleRepository.DEFAULT.copy(
            pluggedIn = false, chargingState = ChargingState.Disconnected))
        val r = repo.startCharging()
        assertIs<CommandResult.Rejected>(r)
    }

    @Test fun `asleep vehicle reports Asleep and wake recovers`() = runTest {
        val repo = FakeVehicleRepository(initial = FakeVehicleRepository.DEFAULT.copy(asleep = true))
        repo.refresh()
        assertIs<VehicleUiState.Asleep>(repo.state.value)
        repo.wake()
        assertIs<VehicleUiState.Ready>(repo.state.value)
    }

    @Test fun `setChargeLimit clamps to 50-100`() = runTest {
        val repo = FakeVehicleRepository()
        repo.setChargeLimit(30); assertEquals(50, ready(repo).chargeLimitPercent)
        repo.setChargeLimit(110); assertEquals(100, ready(repo).chargeLimitPercent)
    }

    @Test fun `setChargeAmps clamps to 5-maxChargeAmps`() = runTest {
        val repo = FakeVehicleRepository()  // DEFAULT.maxChargeAmps = 32
        repo.setChargeAmps(2); assertEquals(5, ready(repo).chargeAmps)
        repo.setChargeAmps(48); assertEquals(32, ready(repo).chargeAmps)
    }
}
```

- [ ] **Step 3: Run to verify failure** `./gradlew :tool:testDebugUnitTest --tests '*FakeVehicleRepositoryTest*'`. Expected: compilation failure (`FakeVehicleRepository` undefined).

- [ ] **Step 4: Implement `FakeVehicleRepository`** — in-memory `MutableStateFlow`, mutations mirror real vehicle semantics; expose `DEFAULT` companion state (72%, plugged in, stopped, locked, climate off, 21 °C target, overheat NoAc, awake). `startCharging` when `!pluggedIn` returns `Rejected("Not plugged in")`. `refresh()` re-emits (Asleep if `asleep`). `wake()` flips `asleep=false` and emits Ready. Clamp limit to 50..100 and amps to 5..maxChargeAmps. No delays in the default constructor (tests stay fast); an optional `latencyMs` constructor param (default 0) adds `delay(latencyMs)` before each mutation for emulator realism.

- [ ] **Step 5: Run to verify pass** — same command. Expected: all PASS.

- [ ] **Step 6: Commit** `git add -A tool/ && git commit -m "tesla: VehicleRepository interface + fake implementation"`

### Task 4: Formatting helpers

**Files:**
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/ui/Formatting.kt`
- Test: `tool/src/test/kotlin/com/amolpurohit/tesla/ui/FormattingTest.kt`

- [ ] **Step 1: Failing tests:**

```kotlin
package com.amolpurohit.tesla.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class FormattingTest {
    @Test fun `range renders in miles`() = assertEquals("212 mi", formatRange(rangeKm = 341.2))
    @Test fun `temp renders one decimal C`() = assertEquals("21.5°C", formatTemp(21.5))
    @Test fun `updatedAt renders relative minutes`() =
        assertEquals("8 min ago", formatUpdatedAt(nowMs = 1_000_000, updatedAtMs = 1_000_000 - 8 * 60_000))
    @Test fun `updatedAt renders just now under a minute`() =
        assertEquals("just now", formatUpdatedAt(nowMs = 1_000_000, updatedAtMs = 990_000))
    @Test fun `updatedAt renders hours`() =
        assertEquals("3 h ago", formatUpdatedAt(nowMs = 4 * 3_600_000, updatedAtMs = 3_600_000 - 600_000))
}
```

- [ ] **Step 2: Run to verify failure**, then implement pure functions `formatRange` (km→mi, `%.0f mi`), `formatTemp`, `formatUpdatedAt` (just now / N min ago / N h ago / N d ago) in `Formatting.kt`. No Android imports — plain Kotlin only.

- [ ] **Step 3: Run to verify pass**: `./gradlew :tool:testDebugUnitTest --tests '*FormattingTest*'`.

- [ ] **Step 4: Commit** `git add -A tool/ && git commit -m "tesla: formatting helpers"`

### Task 5: Shared UI components

**Files:**
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/ui/components/CommandButton.kt`
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/ui/components/Stepper.kt`
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/ui/components/StatusRow.kt`

No unit tests (pure Compose); verified visually in Task 9. Build on `sdk:ui` primitives (`LightText`, `lightClickable`, theme tokens) — check `sdk/ui/src/main/kotlin/com/thelightphone/sdk/ui/` for exact signatures before writing.

- [ ] **Step 1: `CommandButton`** — label + per-button pending/disabled/inline-error state (spec §6: every control carries its own in-flight indicator):

```kotlin
@Composable
fun CommandButton(
    label: String,
    pending: Boolean,
    error: String?,          // inline error rendered beneath, spec §8
    enabled: Boolean = true,
    onClick: () -> Unit,
) { /* LightText inside a bordered Box via lightClickable; label becomes "label…" while pending; error as Detail-variant text underneath */ }
```

- [ ] **Step 2: `Stepper`** — `-` / value / `+` row: `Stepper(label, value: String, onDecrement, onIncrement, pending: Boolean)`. Increments are owned by the caller (charge limit steps by 5, temp by 0.5).

- [ ] **Step 3: `StatusRow`** — `StatusRow(label: String, value: String)` two-column dashboard line.

- [ ] **Step 4: Build**: `./gradlew :tool:assembleDebug`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit** `git add -A tool/ && git commit -m "tesla: shared UI components"`

### Task 6: Dependency wiring (`Graph`) + HomeScreen with dashboard

**Files:**
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/Graph.kt`
- Modify: `tool/src/main/kotlin/com/amolpurohit/tesla/ui/HomeScreen.kt` (replace placeholder)
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/ui/HomeScreenViewModel.kt`
- Test: `tool/src/test/kotlin/com/amolpurohit/tesla/ui/HomeScreenViewModelTest.kt`

- [ ] **Step 1: `Graph`** — one lazy singleton object holding the repository so all screens share it. For M1 it always returns `FakeVehicleRepository`:

```kotlin
package com.amolpurohit.tesla

import com.amolpurohit.tesla.vehicle.FakeVehicleRepository
import com.amolpurohit.tesla.vehicle.VehicleRepository

object Graph {
    @Volatile private var repo: VehicleRepository? = null
    fun repository(): VehicleRepository =
        repo ?: synchronized(this) { repo ?: FakeVehicleRepository(latencyMs = 400).also { repo = it } }
    // test seam:
    fun override(r: VehicleRepository) { repo = r }
}
```

- [ ] **Step 2: Failing VM tests** — VM delegates to repo and tracks per-command pending/error:

```kotlin
package com.amolpurohit.tesla.ui

import com.amolpurohit.tesla.vehicle.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.*
import kotlin.test.*

class HomeScreenViewModelTest {
    @Test fun `lock command sets pending then clears`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeVehicleRepository()
            val vm = HomeScreenViewModel(repo)
            vm.toggleLock()
            assertEquals(Command.Lock, vm.pending.value)
            advanceUntilIdle()
            assertNull(vm.pending.value)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun `rejected command surfaces inline error`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repo = FakeVehicleRepository(initial = FakeVehicleRepository.DEFAULT.copy(
                pluggedIn = false, chargingState = ChargingState.Disconnected))
            val vm = HomeScreenViewModel(repo)
            vm.toggleCharging()
            advanceUntilIdle()
            assertEquals("Not plugged in", vm.commandError.value?.message)
        } finally { Dispatchers.resetMain() }
    }
}
```

- [ ] **Step 3: Run to verify failure**, then implement `HomeScreenViewModel : LightViewModel<Unit>`:
  - `enum class Command { Lock, Climate, Windows, Charging, Wake, Refresh }`
  - `val ui: StateFlow<VehicleUiState>` (repo.state), `val pending: MutableStateFlow<Command?>`, `val commandError: MutableStateFlow<CommandError?>` (`data class CommandError(val command: Command, val message: String)`)
  - `toggleLock/toggleClimate/toggleWindows/toggleCharging/wake/refresh` — each: set pending **synchronously** (the test asserts it before any coroutine runs), then `viewModelScope.launch`: call repo, map `Rejected/Failed` to `commandError` with a human message via `errorMessage(kind)` helper, clear pending. `onScreenShow` calls `refresh()` once (spec §7: one status call on open).

- [ ] **Step 4: Run to verify pass**: `./gradlew :tool:testDebugUnitTest --tests '*HomeScreenViewModelTest*'`.

- [ ] **Step 5: Replace HomeScreen placeholder** with the real screen, following the deleted sample's idiom exactly (`@InitialScreen`, `LightScreen<Unit, HomeScreenViewModel>`, `createViewModel() = HomeScreenViewModel(Graph.repository())`). Layout inside `LightTheme` + `LightScrollView`:
  - Dashboard block: `LightText("Tesla", Heading)`, then `StatusRow`s — Battery `"72% · 212 mi"`, Charging (state + limit), Climate (Off/On · inside temp), Locked, Windows; then updated-at Detail line with `stale`/`Asleep` badge text.
  - When `Asleep`: cached rows lightened + `CommandButton("Wake", …)`.
  - When `NoCredentials`: single message + button navigating to Setup (button is a no-op until Chunk 2's SetupScreen exists — render `LightText("Setup required")` placeholder for now).
  - Command block: `CommandButton`s Lock/Unlock, Climate On/Off, Vent/Close Windows, Start/Stop Charging (only when `pluggedIn`), Refresh.
  - Nav: `LightText("Charging →")` / `LightText("Climate →")` via `lightClickable { navigateTo(::ChargeScreen) }` — add these in Tasks 7–8; comment them out until then.

- [ ] **Step 6: Build + commit** `./gradlew :tool:assembleDebug && git add -A tool/ && git commit -m "tesla: home screen + view model against fake repo"`

### Task 7: ChargeScreen

**Files:**
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/ui/ChargeScreen.kt` (screen + VM in one file, mirroring sample idiom)
- Test: `tool/src/test/kotlin/com/amolpurohit/tesla/ui/ChargeScreenViewModelTest.kt`

- [ ] **Step 1: Failing tests:** limit stepper steps by 5 within 50..100 (`vm.incrementLimit()` from 80 → repo state 85; from 100 → stays 100); amps stepper steps by 1 within 5..`maxChargeAmps`; `toggleCharging` delegates and surfaces rejection (reuse the pattern from `HomeScreenViewModelTest`, e.g. unplugged → `Rejected`).

- [ ] **Step 2: Run to verify failure**, then implement `ChargeScreenViewModel(repo)` + `ChargeScreen : LightScreen<Unit, ChargeScreenViewModel>`: `Stepper("Charge limit", "80%")`, `Stepper("Amps", "16 A")`, `CommandButton("Start/Stop charging")`. Steppers do not debounce or batch: the local target updates optimistically, one repo call per tap (YAGNI).

- [ ] **Step 3: Run to verify pass**, uncomment the Home→Charge nav link in `HomeScreen`.

- [ ] **Step 4: Build + commit** `git add -A tool/ && git commit -m "tesla: charge screen"`

### Task 8: ClimateScreen

**Files:**
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/ui/ClimateScreen.kt` (screen + VM)
- Test: `tool/src/test/kotlin/com/amolpurohit/tesla/ui/ClimateScreenViewModelTest.kt`

- [ ] **Step 1: Failing tests:** temp stepper steps by 0.5 clamped to `minTargetTempC..maxTargetTempC`; `setOverheatProtection` cycles Off→NoAc→Ac; dog-mode toggle delegates; climate on/off delegates.

- [ ] **Step 2: Run to verify failure**, then implement: climate on/off `CommandButton`, temp `Stepper`, overheat selector (three `lightClickable` labels with the active one rendered in Heading variant), dog-mode `CommandButton` with caption `LightText("Keeps climate on for pets while parked. Screen in car shows a message.", Detail)`.

- [ ] **Step 3: Run to verify pass**, uncomment Home→Climate nav link.

- [ ] **Step 4: Build + commit** `git add -A tool/ && git commit -m "tesla: climate screen"`

### Task 9: M1 gate — emulator walkthrough

- [ ] **Step 1:** `./gradlew :tool:installDebug` on an Android emulator (1080×1240, API 34, no Play Services — see repo root README), `adb shell am start -n com.amolpurohit.tesla/com.thelightphone.sdk.LightActivity`.

- [ ] **Step 2: Walk the checklist** (all against fake repo, 400 ms latency): dashboard renders all rows → Lock/Unlock toggles with pending state → Charge screen steppers clamp at 50/100 (the unplugged-rejection inline error is not reachable with the demo fake state — it's covered by unit tests in Tasks 3/6, don't improvise a path to it) → Climate temp clamps → back bar returns from every screen → dog-mode caption visible.

- [ ] **Step 3:** Run `./gradlew check`. Expected: BUILD SUCCESSFUL. Fix anything it flags.

- [ ] **Step 4: Commit** any fixes: `git add -A tool/ && git commit -m "tesla: M1 walkthrough fixes"`

---

## Chunk 2: M2 — Credentials, tokens, Fleet client, Setup screen, live read-only dashboard

Outcome: scan a setup QR → pick vehicle → live dashboard from `vehicle_data`. `Graph` returns the real repository when credentials exist; its command methods return `Rejected("Not yet supported in this build")` until Chunk 4.

### Task 10: KeyValueStore abstraction

**Files:**
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/store/KeyValueStore.kt`
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/store/DataStoreKeyValueStore.kt`
- Test: `tool/src/test/kotlin/com/amolpurohit/tesla/store/InMemoryKeyValueStoreTest.kt`

- [ ] **Step 1:** Interface + in-memory impl (tests run without Android):

```kotlin
package com.amolpurohit.tesla.store

interface KeyValueStore {
    suspend fun get(key: String): String?
    suspend fun put(key: String, value: String)
    suspend fun remove(key: String)
}

class InMemoryKeyValueStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()
    override suspend fun get(key: String) = map[key]
    override suspend fun put(key: String, value: String) { map[key] = value }
    override suspend fun remove(key: String) { map.remove(key) }
}
```

- [ ] **Step 2:** Failing test: put/get/remove/get-null semantics. Run, implement, pass.

- [ ] **Step 3:** `DataStoreKeyValueStore(dataStore: DataStore<Preferences>)` — thin adapter using `stringPreferencesKey`; no unit test (covered by emulator use).

- [ ] **Step 4: Commit** `git add -A tool/ && git commit -m "tesla: key-value store abstraction"`

### Task 11: Setup payload codec (QR contents)

**Files:**
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/auth/SetupPayload.kt`
- Test: `tool/src/test/kotlin/com/amolpurohit/tesla/auth/SetupPayloadTest.kt`

Spec §5: QR carries deflate-compressed, base64url-encoded JSON `{v, refresh_token, client_id, client_secret?, region, private_key}`, with a numbered multi-part fallback (`LTP/<i>/<n>/<data>` prefix per part, parts scannable in any order).

- [ ] **Step 1: Failing tests:**

```kotlin
class SetupPayloadTest {
    private fun encode(json: String): String {   // test helper mirrors the login script
        val deflated = java.util.zip.Deflater(9, /*nowrap=*/true).let { d ->
            d.setInput(json.toByteArray()); d.finish()
            val buf = ByteArray(json.length * 2 + 64)
            val n = d.deflate(buf); buf.copyOf(n)
        }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(deflated)
    }

    @Test fun `single QR decodes`() {
        val json = """{"v":1,"refresh_token":"rt","client_id":"cid","region":"na","private_key":"pk"}"""
        val p = SetupPayload.fromScans(listOf(encode(json)))
        assertEquals(SetupPayload.Complete(payload = SetupPayload("rt", "cid", null, "na", "pk")), p)
    }

    @Test fun `multi-part assembles in any order`() {
        val body = encode("""{"v":1,"refresh_token":"rt","client_id":"cid","region":"na","private_key":"pk"}""")
        val a = "LTP/1/2/" + body.substring(0, body.length / 2)
        val b = "LTP/2/2/" + body.substring(body.length / 2)
        assertIs<SetupPayload.NeedMore>(SetupPayload.fromScans(listOf(b)))
        assertIs<SetupPayload.Complete>(SetupPayload.fromScans(listOf(b, a)))
    }

    @Test fun `unsupported version rejected`() {
        val p = SetupPayload.fromScans(listOf(encode("""{"v":99,"refresh_token":"x","client_id":"y","region":"na","private_key":"z"}""")))
        assertIs<SetupPayload.Invalid>(p)
    }

    @Test fun `garbage rejected as Invalid`() {
        assertIs<SetupPayload.Invalid>(SetupPayload.fromScans(listOf("not-a-payload")))
    }
}
```

- [ ] **Step 2: Run to verify failure**, then implement `SetupPayload` (`@Serializable` data class + `fromScans(scans: List<String>): ScanResult` where `ScanResult = Complete | NeedMore(have: Set<Int>, of: Int) | Invalid(reason: String)`; Inflater with `nowrap=true`; version gate `v == 1`; missing required fields → Invalid).

- [ ] **Step 3: Run to verify pass. Commit** `git add -A tool/ && git commit -m "tesla: setup payload codec with multi-part QR support"`

### Task 12: CredentialStore

**Files:**
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/auth/CredentialStore.kt`
- Test: `tool/src/test/kotlin/com/amolpurohit/tesla/auth/CredentialStoreTest.kt`

- [ ] **Step 1: Failing tests** over `InMemoryKeyValueStore`: `save(payload)` then `load()` returns it; `updateRefreshToken(new)` persists; `saveVehicle(id, vin, name)` persists; `clear()` empties; `load()` on empty store returns null.

- [ ] **Step 2:** Implement over `KeyValueStore` (one JSON blob under key `credentials`, vehicle under `vehicle`). Run, pass.

- [ ] **Step 3: Commit** `git add -A tool/ && git commit -m "tesla: credential store"`

### Task 13: TokenManager

**Files:**
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/auth/TokenManager.kt`
- Test: `tool/src/test/kotlin/com/amolpurohit/tesla/auth/TokenManagerTest.kt`

Refresh grant: `POST https://auth.tesla.com/oauth2/v3/token` form-encoded `grant_type=refresh_token&client_id=<cid>&refresh_token=<rt>` (+ `client_secret` when present). Response JSON: `access_token`, `refresh_token`, `expires_in`. **Rotation invariant (spec §4.1): persist the new refresh token BEFORE returning the access token.**

- [ ] **Step 1: Failing tests** with ktor `MockEngine`:
  - `bearer() refreshes on first call and persists rotated refresh token` — MockEngine returns `{"access_token":"at1","refresh_token":"rt2","expires_in":28800}`; assert `bearer() == "at1"` and `credentialStore.load()!!.refreshToken == "rt2"`, and the outbound request body contained `refresh_token=rt1`.
  - `bearer() caches until expiry` — second call performs no second HTTP request (count MockEngine hits).
  - `invalidate() forces re-refresh` — after `invalidate()`, next `bearer()` hits the endpoint again (this is the 401-retry seam).
  - `refresh rejection maps to AuthExpired` — MockEngine returns 400 `{"error":"invalid_grant"}`; assert `bearer()` throws `AuthExpiredException`.

- [ ] **Step 2: Run to verify failure**, then implement `TokenManager(client: HttpClient, credentials: CredentialStore, nowMs: () -> Long = System::currentTimeMillis)`: mutex-guarded refresh, in-memory access token + expiry (refresh 60 s early), `invalidate()`.

- [ ] **Step 3: Run to verify pass. Commit** `git add -A tool/ && git commit -m "tesla: token manager with rotation-safe refresh"`

### Task 14: FleetClient

**Files:**
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/fleet/FleetClient.kt`
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/fleet/FleetModels.kt`
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/fleet/FleetException.kt`
- Test: `tool/src/test/kotlin/com/amolpurohit/tesla/fleet/FleetClientTest.kt`

Base URL by region: `na` → `https://fleet-api.prd.na.vn.cloud.tesla.com`, `eu` → `…prd.eu…`. Endpoints used: `GET /api/1/vehicles` (summary incl. `state: "online"|"asleep"|"offline"`), `GET /api/1/vehicles/{id}/vehicle_data`, `POST /api/1/vehicles/{id}/wake_up`, `POST /api/1/vehicles/{id}/signed_command` body `{"routable_message":"<base64>"}`.

- [ ] **Step 1: Failing tests** with MockEngine:
  - `listVehicles parses id, vin, display_name, state`
  - `vehicleData maps to VehicleState` — feed a realistic captured `vehicle_data` JSON fixture (commit as `tool/src/test/resources/fleet/vehicle_data.json`; build it from the Fleet API docs' example response, fields: `charge_state.battery_level/battery_range/charging_state/charge_limit_soc/charge_amps/charge_current_request_max/charge_port_latch`, `climate_state.inside_temp/is_climate_on/driver_temp_setting/min_avail_temp/max_avail_temp/cabin_overheat_protection/climate_keeper_mode`, `vehicle_state.locked/fd_window/fp_window/rd_window/rp_window`). Note: `battery_range` is in **miles** — convert to km in the mapper (`* 1.609344`); assert the conversion.
  - `sends bearer header from TokenManager`
  - `401 triggers invalidate + single retry` — first response 401, second 200; assert exactly 2 hits and success. Second 401 → `AuthExpiredException`.
  - `408 maps to VehicleAsleepException`, `429 maps to RateLimitedException`, IOException maps to `FleetOfflineException`.

- [ ] **Step 2: Run to verify failure**, then implement. Constructor: `FleetClient(engine: HttpClientEngine, tokens: TokenManager, region: String)` so tests inject MockEngine and prod injects OkHttp. Mapper `VehicleDataResponse.toVehicleState()` lives in `FleetModels.kt`. `windowsOpen` = any of the four window fields != 0. `overheatProtection` maps `"Off"/"On"(NoAc? verify)/"FanOnly"` — check actual values in Fleet API docs during implementation, encode the mapping in the fixture test, and record the verified strings in the committed `vehicle_data.json` fixture so the decision survives outside this session.

- [ ] **Step 3: Run to verify pass. Commit** `git add -A tool/ && git commit -m "tesla: fleet api client"`

### Task 15: StateCache

**Files:**
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/vehicle/StateCache.kt`
- Test: `tool/src/test/kotlin/com/amolpurohit/tesla/vehicle/StateCacheTest.kt`

- [ ] **Step 1: Failing tests** over `InMemoryKeyValueStore`: save/load round-trip of `(VehicleState, updatedAtMs)`; load on empty returns null; corrupted JSON returns null (not crash).

- [ ] **Step 2:** Implement (kotlinx-serialization over `KeyValueStore`, key `state_cache`). Run, pass, **commit** `git add -A tool/ && git commit -m "tesla: vehicle state cache"`

### Task 16: RealVehicleRepository (read-only)

**Files:**
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/vehicle/RealVehicleRepository.kt`
- Test: `tool/src/test/kotlin/com/amolpurohit/tesla/vehicle/RealVehicleRepositoryTest.kt`

To keep this testable without HTTP, define `interface FleetApi` in `FleetClient.kt` (the four calls) implemented by `FleetClient`; tests use a scripted `FakeFleetApi`.

- [ ] **Step 1: Failing tests:**
  - `starts with cached state marked stale` — seed StateCache; construct; assert `Ready(stale=true)` before any refresh.
  - `refresh fetches vehicle_data and un-stales`
  - `refresh when vehicle asleep emits Asleep with cached data, does NOT wake` (spec: never auto-wake)
  - `wake polls summary state then fetches data once` — FakeFleetApi scripts summary `asleep, asleep, online`; assert exactly one `vehicleData` call after online (spec §4.4 budget rule)
  - `wake timeout after 30s emits Error(WakeTimeout)` — use `runTest` virtual time; poll interval 3 s, budget 30 s
  - `auth expiry surfaces Error(AuthExpired)`
  - `offline surfaces Error(Offline) with cached data`
  - `command methods return Rejected("Not yet supported in this build")` (until Chunk 4)

- [ ] **Step 2: Run to verify failure**, then implement. Constructor `RealVehicleRepository(api: FleetApi, cache: StateCache, vehicleId: String, scope: CoroutineScope, nowMs: () -> Long, pollDelayMs: Long = 3_000, wakeBudgetMs: Long = 30_000)`. Design decision (satisfies spec §4.4 "progress reported"): the per-button pending indicator IS the wake progress UI — no separate `Waking` ui state; note this in a comment on `wake()`.

- [ ] **Step 3: Run to verify pass. Commit** `git add -A tool/ && git commit -m "tesla: real repository, read-only paths"`

### Task 17: SetupScreen (QR scan → vehicle pick → done)

**Files:**
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/ui/SetupScreen.kt` (screen + VM)
- Test: `tool/src/test/kotlin/com/amolpurohit/tesla/ui/SetupScreenViewModelTest.kt`

- [ ] **Step 1: Use the `sdk:client` scanner wrapper `com.thelightphone.sdk.LightQrCodeScanner`** (`sdk/client/src/main/kotlin/com/thelightphone/sdk/LightClientUiUtils.kt`) — NOT the raw `sdk:ui` composable, whose camera-permission lambdas tool code cannot legally implement under the build plugin (the wrapper supplies them internally). Read the wrapper plus `examples/ui-demo/src/main/kotlin/com/thelightphone/uidemo/UiDemoQrScannerScreen.kt` for the intended zero-permission-plumbing usage before writing any code.

- [ ] **Step 2: Failing VM tests** (VM takes `CredentialStore` + a `(SetupPayload) -> FleetApi` factory so tests avoid HTTP): scan accumulation states (`Scanning` → `NeedMore(1 of 2)` → `PickingVehicle(list)` → `Done`); a `Complete` scan persists the payload via `CredentialStore.save` **before** transitioning to `PickingVehicle` (assert `credentialStore.load() != null` at that point — Task 18 silently depends on this); `Invalid` scan shows retryable error; vehicle pick persists via `CredentialStore.saveVehicle`.

- [ ] **Step 3: Run to verify failure**, then implement VM (`sealed interface SetupStep { Scanning; NeedMore; PickingVehicle; Verifying; Done; Failed }`) and screen: scanner composable while `Scanning/NeedMore` (progress text "Scanned 1 of 2"), vehicle list as clickable `LightText` rows, `Done` navigates back. "Verify key" button appears in `Done` but is disabled with caption "Available after M4" until Chunk 4 wires it.

- [ ] **Step 4: Run to verify pass. Commit** `git add -A tool/ && git commit -m "tesla: setup screen with QR credential handoff"`

### Task 18: Graph wiring + M2 gate

**Files:**
- Modify: `tool/src/main/kotlin/com/amolpurohit/tesla/Graph.kt`
- Modify: `tool/src/main/kotlin/com/amolpurohit/tesla/ui/HomeScreen.kt`
- Modify: `tool/src/main/kotlin/com/amolpurohit/tesla/ui/ChargeScreen.kt`, `tool/src/main/kotlin/com/amolpurohit/tesla/ui/ClimateScreen.kt` (same `lightContext.dataStore` pattern for obtaining the repo)

- [ ] **Step 1: Rework `Graph`** to build the real stack when credentials exist. **Seam constraint (verified in SDK source):** `lightContext` — which owns `dataStore` — is `protected` on the screen, so `Graph` can never take a screen parameter. Instead the screen hands the DataStore over from inside its own class: `createViewModel() = HomeScreenViewModel(lightContext.dataStore)` (legal — `createViewModel` is a screen member). Signature: `suspend fun Graph.repository(dataStore: DataStore<Preferences>): VehicleRepository` — reads `CredentialStore(DataStoreKeyValueStore(dataStore))`; if null → `NoCredentialsRepository` sentinel (fixed `state`); else `RealVehicleRepository(FleetClient(OkHttp engine, TokenManager(...), region), …)`; result memoized until `Graph.reset()`. Because `createViewModel()` is synchronous, the VM resolves the repo in `onScreenShow` via `viewModelScope`, exposing `VehicleUiState.Loading` until resolved; tests bypass all of this through a constructor overload taking a `VehicleRepository` directly. Keep `FakeVehicleRepository` reachable via a `USE_FAKE = false` top-level constant for emulator demos (flip by editing one line; documented in tool README in Chunk 4).

- [ ] **Step 2: HomeScreen `NoCredentials` path** now navigates to `SetupScreen`; after setup completes, repo is rebuilt (`Graph.reset()` called by SetupVM on `Done`). `Error(AuthExpired)` renders a "Re-link account" `CommandButton` that also navigates to `SetupScreen` (spec §6/§8 re-link affordance — wired here, copy polished in Task 26).

- [ ] **Step 3: M2 gate (manual, real account):** run the owner-side login by hand once (developer registration can lag — the M5 scripts automate this later; for the gate use any working method to mint a refresh token, e.g. the community `tesla-auth` CLI, and paste its output through the Task 11 `encode()` helper in a scratch `main()` to render a QR with `qrencode -t ANSIUTF8`). Scan on emulator → pick the Model 3 → dashboard shows real battery/climate/lock rows; asleep car shows Asleep + cached rows; airplane-mode emulator shows Offline + cached rows.

- [ ] **Step 4:** `./gradlew check` green. **Commit** `git add -A tool/ && git commit -m "tesla: wire real repository, M2 live read-only dashboard"`

---

## Chunk 3: M3 — Vehicle Command Protocol port

Outcome: `vcp` package produces byte-exact signed command envelopes, proven by fixtures generated from Tesla's reference implementation. No UI changes in this chunk.

**Ground rule for every task here:** the vendored Go reference (`github.com/teslamotors/vehicle-command`) is the single source of truth. Never invent field numbers, key-derivation info strings, or metadata ordering — read them out of the vendored `.proto` files and Go sources, and encode them into fixtures before writing Kotlin.

### Task 19: Vendor the reference + fixture-generation harness

**Files:**
- Create: `scripts/tesla/vcp-fixtures/README.md`
- Create: `scripts/tesla/vcp-fixtures/pin.txt` (the pinned upstream commit hash)
- Create: `scripts/tesla/vcp-fixtures/fetch.sh`
- Create: `scripts/tesla/vcp-fixtures/main.go`
- Create: `scripts/tesla/vcp-fixtures/go.mod`, `scripts/tesla/vcp-fixtures/go.sum` — module resolution uses `replace github.com/teslamotors/vehicle-command => ./upstream` so `pin.txt` + `fetch.sh` remain the single source of truth (never resolve upstream via the module proxy)
- Create: `tool/src/test/resources/vcp/` (generated fixtures, committed)

Requires Go ≥1.21 on the dev machine (`brew install go`) — dev-machine only, never on the phone.

- [ ] **Step 1: `fetch.sh`** — clones `https://github.com/teslamotors/vehicle-command` at the commit in `pin.txt` into `scripts/tesla/vcp-fixtures/upstream/` (gitignored; add `scripts/tesla/vcp-fixtures/upstream/` to the repo's `.gitignore`). Pick the latest release commit at execution time and write it to `pin.txt`.

- [ ] **Step 2: Study the reference.** Read, in the vendored checkout: `pkg/protocol/protocol.go` (session handshake, key derivation, HMAC/AES-GCM signing), `pkg/protocol/protobuf/*.proto` (`universal_message.proto`, `signatures.proto`, `car_server.proto`, `vcsec.proto`), and `pkg/vehicle/` command constructors for: lock, unlock, charge start/stop, set charge limit, set charging amps, HVAC on/off, set temp, cabin overheat protection, climate keeper (dog mode), window vent/close. Record findings in `scripts/tesla/vcp-fixtures/README.md`: which domain each command routes to (this resolves the spec's open VCSEC-vs-Infotainment window question), the exact proto field numbers used, epoch/counter/expiry semantics, and the AES-GCM AAD metadata construction. Also record the fixed test inputs (keys, epoch, UUID, time, nonce source) so fixtures are auditable and regenerable without reading `main.go`.

- [ ] **Step 3: `main.go` fixture generator.** Using the vendored packages directly (import the pinned module), with **fixed inputs** — hardcoded client private key (a test key generated once and embedded), hardcoded fake "vehicle" key pair, fixed epoch/UUID/counter/time — emit JSON fixtures to `tool/src/test/resources/vcp/`:
  - `keys.json` — client private key (PKCS#8 b64), client public (uncompressed point b64), vehicle private/public, ECDH shared secret, derived session key (with the exact derivation labels used), **plus a `gcm_vector` block** `{key_b64, nonce_b64, aad_b64, plaintext_b64, ciphertext_b64, tag_b64}` as an isolated AES-GCM known-answer vector (Task 21 tests against this, not against whole envelopes)
  - `session_info_request.json` — `{description, routable_message_b64}` for a session-info request to each domain
  - `session_info_response.json` — a synthetic vehicle response (built with the reference's own types) + the parsed fields Kotlin must extract (epoch b64, clock_time, counter, vehicle public key)
  - `fault_response.json` — a `MESSAGE_FAULT`-family `RoutableMessage` built with the reference's types, plus the full fault-code enum transcribed from the vendored proto (drives Task 23's `isFault` classifier tests)
  - `commands.json` — array of `{name, domain, expires_at, counter, nonce_b64, aad_b64, plaintext_action_b64, routable_message_b64}` for **every** in-scope command with representative parameters (e.g. `set_charge_limit_80`, `set_temp_21_5`, `dog_mode_on`, `vent_windows`, …). The nonce actually used MUST be captured per entry — inject it if the reference allows, otherwise extract it from the produced envelope — or Kotlin cannot reproduce the bytes; omit `aad_b64` only if the scheme derives AAD entirely from other captured fields (record which, in the README)
  Determinism matters: AES-GCM nonces must come from a fixed test source, not crypto/rand — check how the reference lets you inject the nonce (if it doesn't, generate once and record the produced ciphertext as the fixture; determinism across regenerations is nice-to-have, byte-stability of committed fixtures is what tests rely on).

- [ ] **Step 4: Run** `cd scripts/tesla/vcp-fixtures && ./fetch.sh && go run main.go`. Expected: fixture files appear under `tool/src/test/resources/vcp/`. Spot-check `commands.json` has ≥12 entries and non-empty base64.

- [ ] **Step 5: Commit** fixtures + harness: `git add -A scripts/tesla tool/src/test/resources .gitignore && git commit -m "tesla: vendor VCP reference, generate signing fixtures"`

### Task 20: Protobuf wire codec

**Files:**
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/vcp/ProtoWriter.kt`
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/vcp/ProtoReader.kt`
- Test: `tool/src/test/kotlin/com/amolpurohit/tesla/vcp/ProtoCodecTest.kt`

Generic protobuf wire format only (no schema): varint, length-delimited, and 32-bit on the writer; the reader additionally skips 64-bit fields. ~120 lines total.

- [ ] **Step 1: Failing tests:** varint round-trips (0, 1, 127, 128, 300, `Int.MAX_VALUE`, negative int as 10-byte varint); tag encoding `(field shl 3) or wireType`; length-delimited bytes/string/embedded message round-trip; `ProtoReader.forEachField` visits fields in order and skips unknown wire types correctly; known-answer test: `writeVarint(300) == bytes(0xAC, 0x02)`.

- [ ] **Step 2: Run to verify failure**, then implement:

```kotlin
class ProtoWriter {
    private val buf = java.io.ByteArrayOutputStream()
    fun varint(field: Int, value: Long): ProtoWriter { tag(field, 0); writeVarintRaw(value); return this }
    fun bytes(field: Int, value: ByteArray): ProtoWriter { tag(field, 2); writeVarintRaw(value.size.toLong()); buf.write(value); return this }
    fun string(field: Int, value: String): ProtoWriter = bytes(field, value.toByteArray(Charsets.UTF_8))
    fun message(field: Int, m: ProtoWriter): ProtoWriter = bytes(field, m.toByteArray())
    fun fixed32(field: Int, value: Int): ProtoWriter { tag(field, 5); repeat(4) { buf.write((value ushr (8 * it)) and 0xFF) }; return this }
    fun toByteArray(): ByteArray = buf.toByteArray()
    private fun tag(field: Int, wire: Int) = writeVarintRaw(((field shl 3) or wire).toLong())
    private fun writeVarintRaw(v: Long) { var x = v; while (true) { if (x and 0x7F.inv().toLong() == 0L) { buf.write(x.toInt()); return }; buf.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 } }
}
```

`ProtoReader(bytes)`: `forEachField { field, wireType, value -> }` where value is `Long` (varint/fixed) or `ByteArray` (length-delimited); nested read via `ProtoReader(value)`.

- [ ] **Step 3: Run to verify pass. Commit** `git add -A tool/ && git commit -m "tesla: protobuf wire codec"`

### Task 21: VCP crypto primitives

**Files:**
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/vcp/VcpCrypto.kt`
- Test: `tool/src/test/kotlin/com/amolpurohit/tesla/vcp/VcpCryptoTest.kt`

- [ ] **Step 1: Failing tests from `keys.json` fixture:** load client private + vehicle public → ECDH shared secret matches fixture; session key derivation (exact hash/truncation per the reference — encode what Task 19 recorded) matches fixture; AES-GCM encrypt with fixture nonce/AAD reproduces fixture ciphertext+tag.

- [ ] **Step 2: Run to verify failure**, then implement with JDK/AndroidOpenSSL only: `KeyFactory.getInstance("EC")` + PKCS#8/X.509 specs, uncompressed-point encode/decode for P-256 (manual: `0x04 || X(32) || Y(32)`), `KeyAgreement.getInstance("ECDH")`, `MessageDigest` SHA family, `Cipher.getInstance("AES/GCM/NoPadding")`. No BouncyCastle, no reflection.

- [ ] **Step 3: Run to verify pass. Commit** `git add -A tool/ && git commit -m "tesla: vcp crypto primitives against reference fixtures"`

### Task 22: VCP messages (schema layer)

**Files:**
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/vcp/Messages.kt`
- Test: `tool/src/test/kotlin/com/amolpurohit/tesla/vcp/MessagesTest.kt`

Hand-written encoders/decoders on top of ProtoWriter/Reader for exactly the subset the fixtures exercise: `RoutableMessage` (routing addresses, domain enum, payload oneof, signature data), session-info request/response, VCSEC action message(s), `CarServer.Action` tree for the in-scope commands. Field numbers transcribed from the vendored `.proto` files — cite the proto file+line in a comment for each field constant.

- [ ] **Step 1: Failing tests:** for each `commands.json` entry, building the plaintext action in Kotlin encodes to `plaintext_action_b64` exactly; for each `session_info_request.json` entry (unsigned message), the full Kotlin-built `RoutableMessage` encodes to `routable_message_b64` exactly; decoding `session_info_response.json` extracts epoch/counter/clock-time/public-key matching the fixture's parsed fields.

- [ ] **Step 2: Run to verify failure**, then implement data classes + encode functions until byte-equal. Work one command at a time; keep each encoder a pure function. If `Messages.kt` passes ~400 lines, split the `CarServer.Action` tree into `CarServerActions.kt`.

- [ ] **Step 3: Run to verify pass. Commit** `git add -A tool/ && git commit -m "tesla: vcp message encoders byte-exact against fixtures"`

### Task 23: Session manager + command signer

**Files:**
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/vcp/Session.kt`
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/vcp/CommandSigner.kt`
- Test: `tool/src/test/kotlin/com/amolpurohit/tesla/vcp/CommandSignerTest.kt`

- [ ] **Step 1: Failing tests:** from fixtures — construct `Session` from the parsed session-info response + client key; for every entry in `commands.json`, `CommandSigner.sign(session, action, counter, expiresAt, nonce)` reproduces `routable_message_b64` **byte-for-byte**. Also: counter monotonicity (signing increments), expiry computed as clock-offset-adjusted (per reference semantics recorded in Task 19), and a `session.isFault(response)` classifier tested against `fault_response.json` plus the transcribed fault-code enum (positive and negative cases).

- [ ] **Step 2: Run to verify failure**, then implement. `Session` is immutable-ish state (epoch, base counter, clock offset, shared key) + `nextCounter()`; `CommandSigner` is pure given explicit nonce/time inputs (prod call sites pass `SecureRandom` nonce + real clock).

- [ ] **Step 3: Run to verify pass.** This is the **M3 gate**: `./gradlew :tool:testDebugUnitTest` fully green means the crypto port is done.

- [ ] **Step 4: Commit** `git add -A tool/ && git commit -m "tesla: vcp session + signer, byte-exact fixture suite green (M3)"`

---

## Chunk 4: M4 + M5 — Commands end-to-end, hardening, setup ceremony

Outcome: every in-scope command works on the real Model 3; setup is fully scripted; README walks a stranger through the ceremony.

### Task 24: SignedCommandService (vcp ↔ fleet glue)

**Files:**
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/vehicle/SignedCommandService.kt`
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/vcp/ClientKeys.kt`
- Test: `tool/src/test/kotlin/com/amolpurohit/tesla/vehicle/SignedCommandServiceTest.kt`

- [ ] **Step 1: Failing tests** (FakeFleetApi + fixture keys): first command to a domain performs handshake (session-info request observed) then sends signed command; second command reuses session (no second handshake); `MESSAGE_FAULT` response → exactly one re-handshake + retry, then surfaces `Failed`; vehicle rejection in response protobuf → `Rejected(reason)` with the reference's `ReasonNotMet` string mapped to plain language; `whoami`/key-permission fault → `Failed(KeyNotEnrolled)`.

- [ ] **Step 2: Run to verify failure**, then implement. `ClientKeys` (new, `vcp/ClientKeys.kt`): small wrapper that parses the stored PKCS#8 private key (the `private_key` field of the `CredentialStore` payload) via Task 21's `VcpCrypto` into a key pair + uncompressed public point; constructed once by `Graph`, fixture keys in tests. `SignedCommandService(api: FleetApi, keys: ClientKeys, nowMs, nonceSource)`: per-domain session cache, `suspend fun execute(vehicleId, command: VehicleCommand): CommandResult`, where `VehicleCommand` is a sealed class enumerating the in-scope commands with parameters (the mapping to proto actions reuses Task 22 encoders).

- [ ] **Step 3: Run to verify pass. Commit** `git add -A tool/ && git commit -m "tesla: signed command service with session lifecycle"`

### Task 25: Wire commands into RealVehicleRepository

**Files:**
- Modify: `tool/src/main/kotlin/com/amolpurohit/tesla/vehicle/RealVehicleRepository.kt`
- Modify: `tool/src/main/kotlin/com/amolpurohit/tesla/Graph.kt`
- Test: extend `tool/src/test/kotlin/com/amolpurohit/tesla/vehicle/RealVehicleRepositoryTest.kt`

- [ ] **Step 1: Failing tests:** each repository command delegates to `SignedCommandService` with the right `VehicleCommand`; command against asleep vehicle wakes first (scripted summary transitions) then sends; wake failure aborts with `Failed(WakeTimeout)` and does NOT send; successful command triggers exactly one `vehicle_data` refresh; `Rejected` propagates untouched.

- [ ] **Step 2: Run to verify failure**, implement: `RealVehicleRepository`'s constructor gains a `commands: SignedCommandService` parameter; each command method is a **thin one-liner** delegating to `commands.execute(...)` (wake orchestration stays the only logic in the repository). Update `Graph` to construct `SignedCommandService(fleetClient, ClientKeys(payload.privateKey), …)` and pass it in — this revisits Task 18's wiring; without it the on-device path doesn't compile. Run to verify pass.

- [ ] **Step 3: Remove the placeholder text check** from Task 16's test (delete that test case). Full suite green.

- [ ] **Step 4: Commit** `git add -A tool/ && git commit -m "tesla: commands end-to-end through repository"`

### Task 26: Verify-key flow + error copy

**Files:**
- Modify: `tool/src/main/kotlin/com/amolpurohit/tesla/ui/SetupScreen.kt`
- Modify: `tool/src/main/kotlin/com/amolpurohit/tesla/auth/SetupPayload.kt` (optional `domain` field)
- Create: `tool/src/main/kotlin/com/amolpurohit/tesla/ui/ErrorCopy.kt`
- Test: `tool/src/test/kotlin/com/amolpurohit/tesla/ui/ErrorCopyTest.kt`
- Test: extend `tool/src/test/kotlin/com/amolpurohit/tesla/auth/SetupPayloadTest.kt` and `tool/src/test/kotlin/com/amolpurohit/tesla/ui/SetupScreenViewModelTest.kt`

- [ ] **Step 1:** Enable Setup's "Verify key". The Setup VM gains a `(SetupPayload) -> SignedCommandService` factory parameter (prod: supplied via `Graph`, mirroring the existing `FleetApi` factory; tests: a scripted fake). **Failing VM tests first**, covering all three branches, then implement: handshake success → "Key enrolled ✓"; `KeyNotEnrolled` → shows `tesla.com/_ak/<domain>` (domain from the new **optional `domain` payload field — `v` stays 1; extend the schema and Task 11's tests**) with instructions to open it once via the official Tesla app; payload without `domain` → "Verify unavailable — re-run login.py to include your domain".

- [ ] **Step 2: `ErrorCopy.kt` + failing tests:** exhaustive `ErrorKind → String` map (spec §8 table: every failure names the next action — assert every enum value produces non-empty copy containing an action verb) plus `Rejected` reason pass-through. Wire into all three command screens' inline errors.

- [ ] **Step 3: Run to verify pass. Commit** `git add -A tool/ && git commit -m "tesla: verify-key flow + actionable error copy"`

### Task 27: Owner-side setup scripts

**Files:**
- Create: `scripts/tesla/setup/keygen.sh`
- Create: `scripts/tesla/setup/register.sh`
- Create: `scripts/tesla/setup/login.py`
- Create: `scripts/tesla/setup/README.md`

Dev-machine scripts — outside the tool module, unrestricted. Dependencies: `openssl`, `python3` (stdlib only), `qrencode` (`brew install qrencode`).

- [ ] **Step 1: `keygen.sh`:** `openssl ecparam -name prime256v1 -genkey` → `private-key.pem` (chmod 600) + `public-key.pem`, prints the exact GitHub Pages path to publish (`/.well-known/appspecific/com.tesla.3p.public-key.pem`).

- [ ] **Step 2: `register.sh`:** takes `CLIENT_ID`, `CLIENT_SECRET`, `DOMAIN` env vars; obtains a partner token (client-credentials grant against `auth.tesla.com`, scopes per Fleet API docs) and calls `POST /api/1/partner_accounts` with the domain. Idempotent; prints response.

- [ ] **Step 3: `login.py`:** stdlib-only PKCE flow (required flag `--region na|eu`, embedded in the payload) — spins `http.server` on `localhost:8085`, opens `auth.tesla.com/oauth2/v3/authorize` (scopes `openid offline_access vehicle_device_data vehicle_cmds vehicle_charging_cmds`) via `webbrowser`, catches the redirect, exchanges the code, then builds the payload JSON `{v:1, refresh_token, client_id, client_secret?, region, domain, private_key}` (private key PEM body from `private-key.pem`), deflate+base64url encodes (`zlib.compressobj(9, zlib.DEFLATED, -15)` — raw deflate to match the Kotlin `Inflater(nowrap=true)`), and renders via `qrencode -t ANSIUTF8`; if the encoded payload exceeds 1,200 chars, splits into `LTP/<i>/<n>/<part>` QRs shown one at a time (ENTER advances). **Never writes the payload to disk.**

- [ ] **Step 4: Test the codec seam:** run `login.py --selftest` (flag that encodes a canned dummy payload and prints it) and paste the output into the Kotlin test in Task 11 as an additional cross-implementation fixture test. Run: `./gradlew :tool:testDebugUnitTest --tests '*SetupPayloadTest*'`. Expected: PASS.

- [ ] **Step 5: `scripts/tesla/setup/README.md`:** prerequisites (`openssl`, `python3`, `qrencode`; Go NOT needed for setup), the three scripts in run order with their required env vars/flags, what each produces, and a troubleshooting note for the localhost redirect (port busy, browser not opening).

- [ ] **Step 6: Commit** `git add -A scripts/tesla tool/ && git commit -m "tesla: owner-side setup scripts (keygen, register, login QR)"`

### Task 28: Tool README + repo docs

**Files:**
- Create: `tool/README.md`
- Modify: `docs/superpowers/specs/2026-07-02-tesla-tool-design.md` (mark window-domain open point resolved with the Task 19 finding)

- [ ] **Step 1: `tool/README.md`:** what the tool is; the full setup ceremony start-to-finish (developer account → keygen → GitHub Pages → register → login QR → scan → pick vehicle → virtual-key enrollment via `tesla.com/_ak/<domain>` → verify key); the `USE_FAKE` demo flag; API-budget design invariant (no polling — keep it that way); security posture note (where the private key lives, how to rotate: re-run keygen + re-enroll + re-scan).

- [ ] **Step 2: Update the spec** — replace the "Open point to resolve at M3 start" sentence with the resolved domain finding, citing the vendored proto path. Also update spec §5's payload shape to include the optional `domain` field added in Task 26 (keep spec and QR contract in sync).

- [ ] **Step 3: Commit** `git add -A tool/README.md docs/ && git commit -m "tesla: setup ceremony README, resolve window-domain open point"`

### Task 29: M4/M5 gate — real-vehicle E2E

- [ ] **Step 1: Real-car checklist** (run sparingly — wakes cost API credits; batch the session): fresh install → full ceremony from README as written (no improvisation — if a step needs improvising, the README is wrong; fix it) → verify key ✓ → then: lock, unlock, climate on, set temp, climate off, vent, close, start charge (plugged), set limit 80→85, set amps, stop charge, overheat NoAc, dog mode on/off (confirm the car's center screen shows the Dog Mode message), asleep-car command (wake-then-send path), airplane-mode failure copy, revoked-token path (revoke in Tesla account settings → expect "Re-link account").

- [ ] **Step 2:** `./gradlew check` — full repo green (repo convention before any PR).

- [ ] **Step 3:** Record any deviations found on the real car as fixes with tests where feasible. **Commit** `git add -A && git commit -m "tesla: real-vehicle E2E fixes (M4/M5 complete)"`

---

## Execution notes

- Task order is strict within chunks; chunks are strictly sequential.
- Every task ends with a commit; never batch commits across tasks.
- If the light-sdk plugin rejects something this plan asked for, the plan is wrong — stop and surface it rather than working around the plugin.
- Fleet API/auth endpoints and `vehicle_data` field names should be verified against https://developer.tesla.com/docs/fleet-api when first touched (Task 13/14): Tesla occasionally renames fields; the fixture files encode whatever is verified there.

