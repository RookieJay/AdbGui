# Port Forwarding 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a generic `adb forward` management page to ADB GUI: list existing forwards for the selected device, add a forward (local ↔ remote, supporting `tcp:`/`localabstract:`/`localreserved:`/`localfilesystem:`), remove one or all, with inline error display and refresh. This is the infrastructure prerequisite for a future WebView/CDP debug page (the `tcp:9222 ↔ localabstract:webview_devtools_remote_*` forward is just one preset of this generic page).

**Architecture:** Continues v1 layering. `:core` gets a `ForwardListParser` (pure function, TDD with a real-recorded fixture) + `CommandRunner` methods (`forward`/`listForwards`/`removeForward`/`removeAllForwards`) + `DeviceRepository` delegates. `:desktop` gets a `PortForwardingViewModel` (state-machine unit-tested) + `PortForwardingScreen` (add form + list + remove). UI never touches adb — only reads VM `StateFlow`s, only calls VM methods that forward to `DeviceRepository`.

**Tech Stack:** Kotlin 2.1.20, Compose Multiplatform 1.7.3, kotlinx-coroutines 1.9.0, JDK 21, kotlin.test + kotlinx-coroutines-test + app.cash.turbine. Gradle 8.11 wrapper.

**Spec:** `docs/superpowers/specs/2026-08-14-adb-gui-design.md` §11 lists "端口转发" under 后续阶段. This plan implements that line item. (No spec edit required — port forwarding is already named in §11; this plan fills in the design.)

## Global Constraints

- **JDK 21**; Gradle wrapper 8.11 (Tencent mirror `distributionUrl` + Aliyun Maven mirrors already configured — keep them).
- **`:core` must not depend on Compose/`java.awt`/`javax.swing`.** UI strings go through `Strings.t(...)` (i18n); `:core` keeps adb raw text untranslated.
- **UI red line #2:** `PortForwardingViewModel` touches `DeviceRepository` (core→core), never `CommandRunner`/adb directly.
- **Package roots:** `com.adbgui.core.*` / `com.adbgui.desktop.*`.
- **TDD on `:core`**: failing test → verify fails → minimal impl → verify passes → commit. adb output fixtures under `core/src/test/resources/fixtures/`, **real-recorded** (tech-debt rule #4 — see Task 1 Step 1 for the required header).
- **Commits:** Conventional Commits (`feat(core):` / `feat(desktop):` / `fix(...)`). One commit per task (or per logical step within a task).
- **Dispatcher injection (tech-debt rule #1):** no new `withContext(Dispatchers.IO)` in `:core` from this plan. `CommandRunner.forward*` are one-shot `runner.run` calls on the caller's scope — no I/O dispatcher needed. `DeviceHistoryStore` already injects `io`; this plan adds no new store.
- **No dead code (tech-debt rule #2):** don't add a `ForwardType`/param "we might use later". Only the 4 types `adb forward` accepts that the UI exposes.
- **Existing signatures (don't change):**
  - `CommandRunner(adb: suspend () -> AdbBinary, runner: AdbProcessRunner, logger: Logger, scope: CoroutineScope, server: CommandRunner.AdbServerStarter)`; tests use `CommandRunner.AdbServerStarter{}`.
  - `AdbProcessRunner.run(adb, args, timeoutMs?): AdbProcessResult` (host command — no `-s serial` prefix).
  - `CommandRunner.runCmd(serial, args)` prepends `-s serial` and throws `AdbCommandException` on non-zero. `forward --list` and `forward --remove-all` are host/serial commands; see Task 2 rulings.
  - `DeviceRepository(tracker, history, commands, logger, scope, clock)`.
  - `FakeAdbProcessRunner.whenArgsContains(keywords, result)` + `setDefault(result)`.
- **Plan-level rulings:**
  - **R1:** `adb forward --list` is a host command (lists forwards for *all* devices) → implemented as `runner.run(adb(), listOf("forward","--list"))` with `server.ensureStarted()`, NOT via `runCmd` (which would prepend `-s serial` and is wrong for `--list`). `adb -s <serial> forward <local> <remote>` / `--remove` / `--remove-all` ARE serial commands → go through `runCmd(serial, ...)`.
  - **R2:** `adb forward` exits 0 on success and prints *nothing* to stdout. Failure (e.g. bad spec, port in use) writes to stderr and exits non-zero → `runCmd` throws `AdbCommandException`, which the VM surfaces inline. No separate "forward result parser" — success = no exception.
  - **R3:** `forward --list` on a device with no forwards prints empty stdout, exit 0 → parser returns `emptyList()`. Don't treat empty as an error.
  - **R4:** `DeviceRepository.listForwards` filters the host-wide `--list` output to the selected serial on the `:core` side (the UI should only ever see this device's forwards). `CommandRunner.listForwardsRaw()` returns all; `DeviceRepository.listForwards(serial)` filters.

---

## File Structure

```
:core
├─ domain/ForwardModels.kt            ForwardEndpointType enum + ForwardSpec + ForwardEntry
├─ adb/ForwardListParser.kt           pure parse(stdout): List<ForwardEntry>
├─ adb/CommandRunner.kt               [modify] + forward/listForwardsRaw/removeForward/removeAllForwards
├─ test/resources/fixtures/forward_list_output.txt   [new] real-recorded
├─ test/.../ForwardListParserTest.kt  [new]
├─ test/.../CommandRunnerTest.kt      [modify] + forward tests

:desktop
├─ ui/PortForwardingViewModel.kt     [new] state machine over repo
├─ ui/PortForwardingScreen.kt         [new] add form + list + remove + inline error
├─ ui/i18n/Strings.kt                 [modify] + pf_* keys (zh/en)
├─ ui/AppShell.kt                     [modify] + NavPage.PORT_FORWARDING + when branch + param
├─ main/Main.kt                       [modify] construct PortForwardingViewModel, wire AppShell
├─ test/.../PortForwardingViewModelTest.kt  [new]
```

---

## Task 1: Forward models + `adb forward --list` parser + fixture

**Files:**
- Create: `core/src/main/kotlin/com/adbgui/core/domain/ForwardModels.kt`
- Create: `core/src/main/kotlin/com/adbgui/core/adb/ForwardListParser.kt`
- Create: `core/src/test/resources/fixtures/forward_list_output.txt`
- Create: `core/src/test/kotlin/com/adbgui/core/adb/ForwardListParserTest.kt`

**Interfaces:**
- Produces:
  - `enum class ForwardEndpointType { TCP, LOCALABSTRACT, LOCALRESERVED, LOCALFILESYSTEM }` with `fun prefix(): String` (`"tcp:"`, `"localabstract:"`, `"localreserved:"`, `"localfilesystem:"`).
  - `data class ForwardSpec(val type: ForwardEndpointType, val value: String)` with `fun adbForm(): String = type.prefix() + value`.
  - `data class ForwardEntry(val serial: String, val local: ForwardSpec, val remote: ForwardSpec)`.
  - `object ForwardListParser { fun parse(stdout: String): List<ForwardEntry> }` — parses `adb forward --list` output; one entry per non-blank line `<serial> <local> <remote>`; lines that don't match the 3-token shape are skipped (resilience — see Step 1).

- [ ] **Step 1: Record the real fixture**

Per tech-debt rule #4, fixtures must be real-recorded with a provenance header. Run on a device that has at least one forward set up (if none, set one first: `adb forward tcp:9222 localabstract:webview_devtools_remote_1` — or any `localabstract:` name present on the device).

Record command: `adb forward --list > forward_list_output.txt` (then prepend the header comment below).

`core/src/test/resources/fixtures/forward_list_output.txt` (header lines are `#` comments — the parser must skip lines starting with `#`):
```
# Source: real device recording. Device: <MODEL> (<MANUFACTURER>), Android <VERSION> (SDK <SDK>),
# build id <BUILDID>. Recorded: 2026-08-31. Command: `adb forward --list` (host command, no -s).
# One forward was set up beforehand: `adb -s <serial> forward tcp:9222 localabstract:webview_devtools_remote_1`
# so the output is non-empty. A second device line (different serial) is included to verify serial filtering.
<real serial 1> tcp:9222 localabstract:webview_devtools_remote_1
<real serial 2> tcp:8080 localabstract:webview_devtools_remote_2
```

Replace `<real serial N>` etc. with the actual recorded content. If you cannot record from a real device right now, STOP — do not hand-write the data lines. Tag the task blocked and ask the user to record. (The header + structure above is a template; the data lines must be real.)

- [ ] **Step 2: Write the failing test**

`core/src/test/kotlin/com/adbgui/core/adb/ForwardListParserTest.kt`:
```kotlin
package com.adbgui.core.adb

import com.adbgui.core.domain.ForwardEndpointType
import com.adbgui.core.domain.ForwardSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForwardListParserTest {

    @Test
    fun parses_two_entries_with_correct_specs() {
        // Mirrors the shape of fixtures/forward_list_output.txt (kept inline here so the test
        // is self-contained; the fixture itself is exercised in Step 4's regression test).
        val out = "192.168.1.50:5555 tcp:9222 localabstract:webview_devtools_remote_1\n" +
            "emulator-5554 tcp:8080 localabstract:webview_devtools_remote_2\n"
        val entries = ForwardListParser.parse(out)
        assertEquals(2, entries.size)
        assertEquals("192.168.1.50:5555", entries[0].serial)
        assertEquals(ForwardSpec(ForwardEndpointType.TCP, "9222"), entries[0].local)
        assertEquals(ForwardSpec(ForwardEndpointType.LOCALABSTRACT, "webview_devtools_remote_1"), entries[0].remote)
        assertEquals("emulator-5554", entries[1].serial)
        assertEquals(ForwardSpec(ForwardEndpointType.TCP, "8080"), entries[1].local)
    }

    @Test
    fun empty_stdout_returns_empty_list() {
        // adb forward --list prints nothing when no forwards exist (R3) — not an error.
        assertTrue(ForwardListParser.parse("").isEmpty())
        assertTrue(ForwardListParser.parse("\n  \n").isEmpty())
    }

    @Test
    fun skips_comment_and_malformed_lines() {
        // Fixture header is `#` comments; a stray malformed line (only 2 tokens) is skipped, not crashed on.
        val out = "# this is a comment\n" +
            "badline only-two-tokens\n" +
            "192.168.1.50:5555 tcp:9222 localabstract:foo\n"
        val entries = ForwardListParser.parse(out)
        assertEquals(1, entries.size)
        assertEquals("192.168.1.50:5555", entries[0].serial)
    }

    @Test
    fun parses_all_four_endpoint_types() {
        val out = "s1 tcp:1 localabstract:a\n" +
            "s2 localreserved:lr localfilesystem:/tmp/x\n"
        val entries = ForwardListParser.parse(out)
        assertEquals(ForwardEndpointType.TCP, entries[0].local.type)
        assertEquals(ForwardEndpointType.LOCALABSTRACT, entries[0].remote.type)
        assertEquals(ForwardEndpointType.LOCALRESERVED, entries[1].local.type)
        assertEquals(ForwardEndpointType.LOCALFILESYSTEM, entries[1].remote.type)
    }

    @Test
    fun fixture_regression_parses_without_error() {
        // Reads the real-recorded fixture; just asserts it parses to a non-empty list whose
        // first entry's adbForm() round-trips (serial, local, remote all populated).
        val out = object {}.javaClass.getResourceAsStream("/fixtures/forward_list_output.txt")!!
            .bufferedReader().readText()
        val entries = ForwardListParser.parse(out)
        assertTrue(entries.isNotEmpty(), "fixture must contain at least one forward — re-record if empty")
        val first = entries.first()
        assertTrue(first.serial.isNotBlank())
        assertTrue(first.local.adbForm().startsWith("tcp:") || first.local.adbForm().startsWith("local"))
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :core:test --tests "com.adbgui.core.adb.ForwardListParserTest"`
Expected: FAIL with `unresolved reference: ForwardListParser` (and the domain types don't exist yet).

- [ ] **Step 4: Write minimal implementation**

`core/src/main/kotlin/com/adbgui/core/domain/ForwardModels.kt`:
```kotlin
package com.adbgui.core.domain

/** One endpoint of an `adb forward` mapping. Mirrors the adb spec syntax: `<type>:<value>`. */
enum class ForwardEndpointType(val prefix: String) {
    TCP("tcp:"),
    LOCALABSTRACT("localabstract:"),
    LOCALRESERVED("localreserved:"),
    LOCALFILESYSTEM("localfilesystem:");
    companion object {
        /** Parse a single adb endpoint token like `tcp:9222` or `localabstract:foo`.
         *  Returns null for an unrecognized prefix (caller skips the line). */
        fun parse(token: String): ForwardSpec? {
            for (t in entries) {
                if (token.startsWith(t.prefix)) {
                    return ForwardSpec(t, token.removePrefix(t.prefix))
                }
            }
            return null
        }
    }
}

data class ForwardSpec(val type: ForwardEndpointType, val value: String) {
    /** The form adb expects on the command line, e.g. `tcp:9222`. */
    fun adbForm(): String = type.prefix + value
}

/** One row of `adb forward --list`: `<serial> <local> <remote>`. */
data class ForwardEntry(val serial: String, val local: ForwardSpec, val remote: ForwardSpec)
```

`core/src/main/kotlin/com/adbgui/core/adb/ForwardListParser.kt`:
```kotlin
package com.adbgui.core.adb

import com.adbgui.core.domain.ForwardEndpointType
import com.adbgui.core.domain.ForwardEntry

/** Parses `adb forward --list` stdout into [ForwardEntry]s. Pure function — no adb, no I/O.
 *  - skips blank lines, `#` comment lines (fixture provenance header), and malformed lines
 *    (lines that don't tokenize into 3 parts or whose endpoints don't use a known prefix);
 *  - never throws: a real `--list` is host-wide and may include rows for devices the caller
 *    doesn't care about; filtering by serial is the caller's job (R4). */
object ForwardListParser {
    fun parse(stdout: String): List<ForwardEntry> {
        val out = mutableListOf<ForwardEntry>()
        for (raw in stdout.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 3) continue
            val serial = parts[0]
            val local = ForwardEndpointType.parse(parts[1]) ?: continue
            val remote = ForwardEndpointType.parse(parts[2]) ?: continue
            out.add(ForwardEntry(serial, local, remote))
        }
        return out
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :core:test --tests "com.adbgui.core.adb.ForwardListParserTest"`
Expected: PASS — all 5 tests green.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/adbgui/core/domain/ForwardModels.kt \
  core/src/main/kotlin/com/adbgui/core/adb/ForwardListParser.kt \
  core/src/test/resources/fixtures/forward_list_output.txt \
  core/src/test/kotlin/com/adbgui/core/adb/ForwardListParserTest.kt
git commit -m "feat(core): add ForwardListParser + forward domain models"
```

---

## Task 2: `CommandRunner` forward methods

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/adb/CommandRunner.kt` (add 4 methods after `screenshot` ~line 151, before `deviceDetailReport` or grouped with other device commands — placement near `pull`/`push` at the end is fine too; keep file under ~300 lines is not a concern here, this adds ~30 lines)
- Modify: `core/src/test/kotlin/com/adbgui/core/adb/CommandRunnerTest.kt` (append tests)

**Interfaces:**
- Consumes: `ForwardSpec.adbForm()` / `ForwardEntry` from Task 1; `runCmd(serial, args)` (private, throws `AdbCommandException` on non-zero); `server.ensureStarted()`; `runner.run(adb(), args)`.
- Produces (used by Task 3 `DeviceRepository`):
  - `suspend fun forward(serial, local: ForwardSpec, remote: ForwardSpec): Unit` — `adb -s <serial> forward <local.adbForm()> <remote.adbForm()>`; throws `AdbCommandException` on non-zero (R2).
  - `suspend fun listForwardsRaw(): List<ForwardEntry>` — host `adb forward --list` → `ForwardListParser.parse`; **does not throw** on empty (R3) but does throw `AdbCommandException` on non-zero exit (real failure).
  - `suspend fun removeForward(serial, local: ForwardSpec): Unit` — `adb -s <serial> forward --remove <local.adbForm()>`.
  - `suspend fun removeAllForwards(serial): Unit` — `adb -s <serial> forward --remove-all`.

- [ ] **Step 1: Write the failing tests**

Append to `core/src/test/kotlin/com/adbgui/core/adb/CommandRunnerTest.kt` (inside the class):
```kotlin
    @Test
    fun forward_sends_minus_s_serial_forward_specs() = runTest {
        // R1/R2: `adb -s <serial> forward <local> <remote>` — serial command, exits 0 with empty stdout.
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("forward", "tcp:9222", "localabstract:foo"),
            AdbProcessResult(0, "", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        cr.forward("192.168.1.50:5555",
            com.adbgui.core.domain.ForwardSpec(com.adbgui.core.domain.ForwardEndpointType.TCP, "9222"),
            com.adbgui.core.domain.ForwardSpec(com.adbgui.core.domain.ForwardEndpointType.LOCALABSTRACT, "foo"))
        // No assertion on result — success = no exception. The FakeAdbProcessRunner default is
        // exit 1 "no script matched", so if forward() didn't send the right args it would throw.
    }

    @Test
    fun forward_nonzero_throws_adb_command_exception() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("forward", "tcp:9222"), AdbProcessResult(1, "", "cannot bind socket"))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        assertFailsWith<AdbCommandException> {
            cr.forward("s1",
                com.adbgui.core.domain.ForwardSpec(com.adbgui.core.domain.ForwardEndpointType.TCP, "9222"),
                com.adbgui.core.domain.ForwardSpec(com.adbgui.core.domain.ForwardEndpointType.LOCALABSTRACT, "foo"))
        }
    }

    @Test
    fun listForwardsRaw_parses_host_wide_output() = runTest {
        // R1: `adb forward --list` is a host command — no -s serial. R4: returns ALL devices' rows.
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("forward", "--list"),
            AdbProcessResult(0, "s1 tcp:9222 localabstract:foo\ns2 tcp:8080 localabstract:bar\n", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val all = cr.listForwardsRaw()
        assertEquals(2, all.size)
        assertEquals("s1", all[0].serial)
        assertEquals("s2", all[1].serial)
    }

    @Test
    fun listForwardsRaw_empty_is_not_an_error() = runTest {
        // R3: empty stdout, exit 0 → emptyList, no throw.
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("forward", "--list"), AdbProcessResult(0, "", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        assertTrue(cr.listForwardsRaw().isEmpty())
    }

    @Test
    fun removeForward_sends_minus_s_remove_local() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("forward", "--remove", "tcp:9222"), AdbProcessResult(0, "", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        cr.removeForward("s1",
            com.adbgui.core.domain.ForwardSpec(com.adbgui.core.domain.ForwardEndpointType.TCP, "9222"))
    }

    @Test
    fun removeAllForwards_sends_remove_all() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("forward", "--remove-all"), AdbProcessResult(0, "", ""))
        val cr = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        cr.removeAllForwards("s1")
    }
```

(Imports `assertEquals` etc. are already at the top of `CommandRunnerTest.kt`; confirm and add any missing ones — `assertFailsWith` and `assertTrue` are already imported per the file head.)

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:test --tests "com.adbgui.core.adb.CommandRunnerTest"`
Expected: FAIL — `unresolved reference: forward` / `listForwardsRaw` etc.

- [ ] **Step 3: Write minimal implementation**

Add to `core/src/main/kotlin/com/adbgui/core/adb/CommandRunner.kt` (place after `pull` near line 277, before `private fun extractPng`):
```kotlin
    /** `adb -s <serial> forward <local> <remote>`. Exits 0 with empty stdout on success (R2);
     *  on failure adb writes stderr + non-zero → runCmd throws AdbCommandException (surfaced inline by UI). */
    suspend fun forward(serial: String, local: com.adbgui.core.domain.ForwardSpec, remote: com.adbgui.core.domain.ForwardSpec) {
        runCmd(serial, listOf("forward", local.adbForm(), remote.adbForm()))
    }

    /** `adb forward --list` — host command (no -s serial), lists ALL devices' forwards (R1).
     *  Returns the parsed rows unfiltered; DeviceRepository.listForwards filters by serial (R4).
     *  Empty result is not an error (R3); non-zero exit is. */
    suspend fun listForwardsRaw(): List<com.adbgui.core.domain.ForwardEntry> {
        server.ensureStarted()
        val cmd = listOf("forward", "--list")
        val r = runner.run(adb(), cmd)
        logger.debug("adb ${cmd.joinToString(" ")} -> exit=${r.exitCode} out=${r.stdout.take(200)}")
        if (r.exitCode != 0) throw AdbCommandException(command = "adb forward --list", exitCode = r.exitCode, stderr = r.stderr)
        return ForwardListParser.parse(r.stdout)
    }

    /** `adb -s <serial> forward --remove <local>`. */
    suspend fun removeForward(serial: String, local: com.adbgui.core.domain.ForwardSpec) {
        runCmd(serial, listOf("forward", "--remove", local.adbForm()))
    }

    /** `adb -s <serial> forward --remove-all`. */
    suspend fun removeAllForwards(serial: String) {
        runCmd(serial, listOf("forward", "--remove-all"))
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:test --tests "com.adbgui.core.adb.CommandRunnerTest"`
Expected: PASS — all 6 new tests green, existing tests still green.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/adbgui/core/adb/CommandRunner.kt \
  core/src/test/kotlin/com/adbgui/core/adb/CommandRunnerTest.kt
git commit -m "feat(core): add adb forward/list/remove to CommandRunner"
```

---

## Task 3: `DeviceRepository` delegates

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/device/DeviceRepository.kt` (add 4 delegates after `pull` ~line 133)

**Interfaces:**
- Consumes: `CommandRunner.forward/listForwardsRaw/removeForward/removeAllForwards` (Task 2).
- Produces (used by Task 4 VM):
  - `suspend fun forward(serial, local, remote): Unit`
  - `suspend fun listForwards(serial): List<ForwardEntry>` — filters `listForwardsRaw()` to `serial` (R4).
  - `suspend fun removeForward(serial, local): Unit`
  - `suspend fun removeAllForwards(serial): Unit`

- [ ] **Step 1: Write the failing test**

The `:core` already has no `DeviceRepositoryTest` that exercises forward; the VM test in Task 4 covers the filtered path end-to-end. To keep `:core` TDD-honest, add a tiny focused test in a new file `core/src/test/kotlin/com/adbgui/core/device/DeviceRepositoryForwardTest.kt`:
```kotlin
package com.adbgui.core.device

import com.adbgui.core.adb.AdbProcessResult
import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.adb.FakeAdbProcessRunner
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.domain.ForwardEndpointType
import com.adbgui.core.domain.ForwardSpec
import com.adbgui.core.log.NoopLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class DeviceRepositoryForwardTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    @Test
    fun listForwards_filters_to_selected_serial() = runTest {
        // R4: --list is host-wide; repo filters to the serial the UI asked about.
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("forward", "--list"),
            AdbProcessResult(0, "s1 tcp:9222 localabstract:foo\ns2 tcp:8080 localabstract:bar\n", ""))
        val tracker = object : IDeviceTracker { override val devices = MutableStateFlow(emptyList<com.adbgui.core.domain.DeviceSnapshot>()) }
        val history = DeviceHistoryStore(Files.createTempDirectory("fwd"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val cmd = CommandRunner({ adb }, runner, NoopLogger, this, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, this, clock = { 0L })
        val mine = repo.listForwards("s1")
        assertEquals(1, mine.size)
        assertEquals("s1", mine[0].serial)
        assertEquals(ForwardSpec(ForwardEndpointType.LOCALABSTRACT, "foo"), mine[0].remote)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :core:test --tests "com.adbgui.core.device.DeviceRepositoryForwardTest"`
Expected: FAIL — `unresolved reference: listForwards`.

- [ ] **Step 3: Write minimal implementation**

Add to `core/src/main/kotlin/com/adbgui/core/device/DeviceRepository.kt` after `pull` (line 133, before the closing brace):
```kotlin
    suspend fun forward(serial: String, local: com.adbgui.core.domain.ForwardSpec, remote: com.adbgui.core.domain.ForwardSpec) =
        commands.forward(serial, local, remote)
    /** This device's forwards only — `adb forward --list` is host-wide, filtered here (R4). */
    suspend fun listForwards(serial: String): List<com.adbgui.core.domain.ForwardEntry> =
        commands.listForwardsRaw().filter { it.serial == serial }
    suspend fun removeForward(serial: String, local: com.adbgui.core.domain.ForwardSpec) =
        commands.removeForward(serial, local)
    suspend fun removeAllForwards(serial: String) = commands.removeAllForwards(serial)
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :core:test --tests "com.adbgui.core.device.DeviceRepositoryForwardTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/adbgui/core/device/DeviceRepository.kt \
  core/src/test/kotlin/com/adbgui/core/device/DeviceRepositoryForwardTest.kt
git commit -m "feat(core): expose forward/listForwards/remove through DeviceRepository"
```

---

## Task 4: `PortForwardingViewModel` + state-machine tests

**Files:**
- Create: `desktop/src/main/kotlin/com/adbgui/desktop/ui/PortForwardingViewModel.kt`
- Create: `desktop/src/test/kotlin/com/adbgui/desktop/ui/PortForwardingViewModelTest.kt`

**Interfaces:**
- Consumes: `DeviceRepository.forward/listForwards/removeForward/removeAllForwards` (Task 3); `selectedSerial: StateFlow<String?>`; `CoroutineScope`; `Strings.t(...)`; `AdbCommandException`.
- Produces (used by Task 5 Screen):
  - `val forwards: StateFlow<List<ForwardEntry>>`
  - `val localType: StateFlow<ForwardEndpointType>`, `val localValue: StateFlow<String>`, `val remoteType: StateFlow<ForwardEndpointType>`, `val remoteValue: StateFlow<String>` (form state)
  - `val busy: StateFlow<Boolean>`, `val error: StateFlow<String?>`
  - `fun setLocalType(t)`, `fun setLocalValue(v)`, `fun setRemoteType(t)`, `fun setRemoteValue(v)`
  - `fun refresh(): Job`, `fun add(): Job`, `fun remove(local: ForwardSpec): Job`, `fun removeAll(): Job`
  - `fun clearError()`
  - Auto-loads when the selected serial changes (collect on `selectedSerial` → refresh).

- [ ] **Step 1: Write the failing state-machine test**

`desktop/src/test/kotlin/com/adbgui/desktop/ui/PortForwardingViewModelTest.kt`:
```kotlin
package com.adbgui.desktop.ui

import app.cash.turbine.test
import com.adbgui.core.adb.AdbProcessResult
import com.adbgui.core.adb.CommandRunner
import com.adbgui.core.adb.FakeAdbProcessRunner
import com.adbgui.core.device.DeviceHistoryStore
import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.device.IDeviceTracker
import com.adbgui.core.domain.AdbBinary
import com.adbgui.core.domain.AdbCommandException
import com.adbgui.core.domain.AdbSource
import com.adbgui.core.domain.DeviceSnapshot
import com.adbgui.core.domain.ForwardEndpointType
import com.adbgui.core.domain.ForwardSpec
import com.adbgui.core.log.NoopLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PortForwardingViewModelTest {
    private val adb = AdbBinary("adb", AdbSource.PATH)

    private fun makeVm(
        runner: FakeAdbProcessRunner,
        selected: MutableStateFlow<String?>,
        scope: kotlinx.coroutines.CoroutineScope,
    ): PortForwardingViewModel {
        val tracker = object : IDeviceTracker { override val devices = MutableStateFlow(emptyList<DeviceSnapshot>()) }
        val history = DeviceHistoryStore(Files.createTempDirectory("pf"), clock = { 0L }, io = kotlinx.coroutines.Dispatchers.Unconfined)
        val cmd = CommandRunner({ adb }, runner, NoopLogger, scope, CommandRunner.AdbServerStarter{})
        val repo = DeviceRepository(tracker, history, cmd, NoopLogger, scope, clock = { 0L })
        return PortForwardingViewModel(repo, selected, scope)
    }

    @Test
    fun refresh_loads_and_filters_forwards_for_selected_serial() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("forward", "--list"),
            AdbProcessResult(0, "s1 tcp:9222 localabstract:foo\ns2 tcp:8080 localabstract:bar\n", ""))
        val vm = makeVm(runner, MutableStateFlow("s1"), this)
        advanceUntilIdle()
        assertEquals(1, vm.forwards.value.size)
        assertEquals("s1", vm.forwards.value[0].serial)
    }

    @Test
    fun add_calls_forward_then_refreshes_list() = runTest {
        val runner = FakeAdbProcessRunner()
        // forward command (serial, exits 0); then --list shows the new row
        runner.whenArgsContains(listOf("forward", "tcp:9222", "localabstract:foo"), AdbProcessResult(0, "", ""))
        runner.whenArgsContains(listOf("forward", "--list"), AdbProcessResult(0, "s1 tcp:9222 localabstract:foo\n", ""))
        val vm = makeVm(runner, MutableStateFlow("s1"), this)
        vm.setLocalValue("9222"); vm.setRemoteType(ForwardEndpointType.LOCALABSTRACT); vm.setRemoteValue("foo")
        vm.add(); advanceUntilIdle()
        assertEquals(1, vm.forwards.value.size)
        assertEquals("tcp:9222", vm.forwards.value[0].local.adbForm())
        assertNull(vm.error.value)
    }

    @Test
    fun add_failure_sets_inline_error_keeps_list() = runTest {
        val runner = FakeAdbProcessRunner()
        // forward fails; --list returns a pre-existing row so the list isn't wiped on error
        runner.whenArgsContains(listOf("forward", "tcp:9222", "localabstract:foo"), AdbProcessResult(1, "", "cannot bind socket"))
        runner.whenArgsContains(listOf("forward", "--list"), AdbProcessResult(0, "s1 tcp:8080 localabstract:bar\n", ""))
        val vm = makeVm(runner, MutableStateFlow("s1"), this)
        vm.setLocalValue("9222"); vm.setRemoteType(ForwardEndpointType.LOCALABSTRACT); vm.setRemoteValue("foo")
        vm.add(); advanceUntilIdle()
        assertTrue(vm.error.value!!.contains("adb"))
        assertEquals(1, vm.forwards.value.size, "list must still reflect the refresh after add failure")
    }

    @Test
    fun add_with_blank_local_value_sets_error_without_calling_adb() = runTest {
        val runner = FakeAdbProcessRunner()
        // No script for forward tcp:9222 — if the VM wrongly calls adb, the default exit-1 would set a different error.
        val vm = makeVm(runner, MutableStateFlow("s1"), this)
        vm.add(); advanceUntilIdle()
        assertTrue(vm.error.value!!.isNotBlank())
        assertTrue(vm.forwards.value.isEmpty())
    }

    @Test
    fun remove_calls_removeForward_then_refreshes() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("forward", "--remove", "tcp:9222"), AdbProcessResult(0, "", ""))
        runner.whenArgsContains(listOf("forward", "--list"), AdbProcessResult(0, "", ""))
        val vm = makeVm(runner, MutableStateFlow("s1"), this)
        vm.remove(ForwardSpec(ForwardEndpointType.TCP, "9222")); advanceUntilIdle()
        assertTrue(vm.forwards.value.isEmpty())
    }

    @Test
    fun auto_refreshes_when_selected_serial_changes() = runTest {
        val runner = FakeAdbProcessRunner()
        runner.whenArgsContains(listOf("forward", "--list"), AdbProcessResult(0, "sX tcp:1 localabstract:a\n", ""))
        val selected = MutableStateFlow<String?>(null)
        val vm = makeVm(runner, selected, this)
        advanceUntilIdle()
        assertTrue(vm.forwards.value.isEmpty())
        selected.value = "sX"; advanceUntilIdle()
        assertEquals(1, vm.forwards.value.size)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :desktop:test --tests "com.adbgui.desktop.ui.PortForwardingViewModelTest"`
Expected: FAIL — `unresolved reference: PortForwardingViewModel`.

- [ ] **Step 3: Write minimal implementation**

`desktop/src/main/kotlin/com/adbgui/desktop/ui/PortForwardingViewModel.kt`:
```kotlin
package com.adbgui.desktop.ui

import com.adbgui.core.device.DeviceRepository
import com.adbgui.core.domain.AdbCommandException
import com.adbgui.core.domain.ForwardEndpointType
import com.adbgui.core.domain.ForwardEntry
import com.adbgui.core.domain.ForwardSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Thin VM over [DeviceRepository] for the Port Forwarding page. Reads selectedSerial, loads
 *  this device's forwards, and forwards add/remove actions. Errors are surfaced inline (no modal),
 *  and the list is always refreshed after a mutating action so the UI reflects adb's real state. */
class PortForwardingViewModel(
    private val repo: DeviceRepository,
    private val selectedSerial: StateFlow<String?>,
    private val scope: CoroutineScope,
) {
    private val _forwards = MutableStateFlow<List<ForwardEntry>>(emptyList())
    val forwards: StateFlow<List<ForwardEntry>> = _forwards.asStateFlow()

    private val _localType = MutableStateFlow(ForwardEndpointType.TCP)
    val localType: StateFlow<ForwardEndpointType> = _localType.asStateFlow()
    private val _localValue = MutableStateFlow("")
    val localValue: StateFlow<String> = _localValue.asStateFlow()
    private val _remoteType = MutableStateFlow(ForwardEndpointType.LOCALABSTRACT)
    val remoteType: StateFlow<ForwardEndpointType> = _remoteType.asStateFlow()
    private val _remoteValue = MutableStateFlow("")
    val remoteValue: StateFlow<String> = _remoteValue.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // One collector for the lifetime of the VM: re-load whenever the selected device changes.
    private val collector: Job = scope.launch {
        selectedSerial.collectLatest { serial ->
            if (serial != null) refresh().join()
            else _forwards.value = emptyList()
        }
    }

    fun stop() = collector.cancel()

    fun setLocalType(t: ForwardEndpointType) { _localType.value = t }
    fun setLocalValue(v: String) { _localValue.value = v }
    fun setRemoteType(t: ForwardEndpointType) { _remoteType.value = t }
    fun setRemoteValue(v: String) { _remoteValue.value = v }

    fun clearError() { _error.value = null }

    fun refresh(): Job = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true
        try {
            _forwards.value = repo.listForwards(serial)
        } catch (e: AdbCommandException) {
            _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}"
        } finally { _busy.value = false }
    }

    fun add(): Job = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        val local = _localValue.value.trim()
        val remote = _remoteValue.value.trim()
        if (local.isEmpty() || remote.isEmpty()) {
            _error.value = com.adbgui.desktop.ui.i18n.Strings.t("pf_need_both_specs")
            return@launch
        }
        _busy.value = true; _error.value = null
        try {
            repo.forward(serial, ForwardSpec(_localType.value, local), ForwardSpec(_remoteType.value, remote))
            _forwards.value = repo.listForwards(serial)
        } catch (e: AdbCommandException) {
            _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}"
            // Still refresh so the list reflects adb's real state (the failed add may have left nothing).
            runCatching { _forwards.value = repo.listForwards(serial) }
        } finally { _busy.value = false }
    }

    fun remove(local: ForwardSpec): Job = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null
        try {
            repo.removeForward(serial, local)
            _forwards.value = repo.listForwards(serial)
        } catch (e: AdbCommandException) {
            _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}"
            runCatching { _forwards.value = repo.listForwards(serial) }
        } finally { _busy.value = false }
    }

    fun removeAll(): Job = scope.launch {
        val serial = selectedSerial.value ?: return@launch
        _busy.value = true; _error.value = null
        try {
            repo.removeAllForwards(serial)
            _forwards.value = repo.listForwards(serial)
        } catch (e: AdbCommandException) {
            _error.value = "${e.message}\n--- adb stderr ---\n${e.stderr}"
            runCatching { _forwards.value = repo.listForwards(serial) }
        } finally { _busy.value = false }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :desktop:test --tests "com.adbgui.desktop.ui.PortForwardingViewModelTest"`
Expected: PASS — all 6 tests green.

- [ ] **Step 5: Commit**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/PortForwardingViewModel.kt \
  desktop/src/test/kotlin/com/adbgui/desktop/ui/PortForwardingViewModelTest.kt
git commit -m "feat(desktop): add PortForwardingViewModel with state-machine tests"
```

---

## Task 5: i18n strings (zh/en)

**Files:**
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt` (add keys to BOTH `zh` map after line ~60 and `en` map before its closing — see file; add a "Port Forwarding" section comment in each)

**Interfaces:**
- Produces: the `pf_*` keys referenced by Task 6 Screen + Task 4 VM (`pf_need_both_specs`).

- [ ] **Step 1: Add keys to the `zh` map**

Inside the `zh` map (after the "System Info (G2)" block or at a sensible spot before the closing `)`), add:
```kotlin
        // Port Forwarding
        "nav_port_forwarding" to "端口转发",
        "pf_add_title" to "新建转发",
        "pf_local" to "本地",
        "pf_remote" to "远端",
        "pf_add" to "添加",
        "pf_remove" to "移除",
        "pf_remove_all" to "全部移除",
        "pf_refresh" to "刷新",
        "pf_empty" to "该设备暂无转发",
        "pf_need_both_specs" to "请填写本地与远端地址",
        "pf_value_placeholder" to "端口 / 抽象名",
        "pf_table_serial" to "设备",
        "pf_table_local" to "本地",
        "pf_table_remote" to "远端",
```

- [ ] **Step 2: Add the same keys to the `en` map**

Inside the `en` map, add the matching English block:
```kotlin
        // Port Forwarding
        "nav_port_forwarding" to "Port Forwarding",
        "pf_add_title" to "New Forward",
        "pf_local" to "Local",
        "pf_remote" to "Remote",
        "pf_add" to "Add",
        "pf_remove" to "Remove",
        "pf_remove_all" to "Remove All",
        "pf_refresh" to "Refresh",
        "pf_empty" to "No forwards on this device",
        "pf_need_both_specs" to "Fill in both local and remote specs",
        "pf_value_placeholder" to "port / abstract name",
        "pf_table_serial" to "Device",
        "pf_table_local" to "Local",
        "pf_table_remote" to "Remote",
```

- [ ] **Step 3: Verify it compiles + keys resolve**

Run: `./gradlew :desktop:compileKotlin`
Expected: BUILD SUCCESSFUL. (`Strings.t("pf_*")` now resolves via the map fallback — no `key`-passthrough at runtime.)

- [ ] **Step 4: Commit**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/i18n/Strings.kt
git commit -m "feat(desktop): add port-forwarding i18n keys (zh/en)"
```

---

## Task 6: `PortForwardingScreen` Compose UI

**Files:**
- Create: `desktop/src/main/kotlin/com/adbgui/desktop/ui/PortForwardingScreen.kt`
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/ui/AppShell.kt` (add `NavPage.PORT_FORWARDING` + `navItems` entry + `when` branch + `portForwardingVm` param)
- Modify: `desktop/src/main/kotlin/com/adbgui/desktop/main/Main.kt` (construct VM, forward to AppShell)

**Interfaces:**
- Consumes: `PortForwardingViewModel` (Task 4), `Strings.t` (Task 5), `ForwardEndpointType`/`ForwardSpec`/`ForwardEntry` (Task 1), existing `AppColors`/`MaterialTheme` theming, the inline-error pattern used elsewhere (error text + adb stderr in a collapsible/monospace block — mirror `SystemOpsViewModel`'s error rendering).
- Produces: a wired-in nav page reachable from the sidebar.

- [ ] **Step 1: Write the Screen**

`desktop/src/main/kotlin/com/adbgui/desktop/ui/PortForwardingScreen.kt`:
```kotlin
package com.adbgui.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.adbgui.core.domain.ForwardEndpointType
import com.adbgui.desktop.ui.i18n.Strings
import com.adbgui.desktop.ui.theme.AppColors

@Composable
fun PortForwardingScreen(
    vm: PortForwardingViewModel,
    selectedSerial: String?,
) {
    val forwards by vm.forwards.collectAsState()
    val localType by vm.localType.collectAsState()
    val localValue by vm.localValue.collectAsState()
    val remoteType by vm.remoteType.collectAsState()
    val remoteValue by vm.remoteValue.collectAsState()
    val busy by vm.busy.collectAsState()
    val error by vm.error.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // ---- Add form ----
        Text(Strings.t("pf_add_title"), style = androidx.compose.material.MaterialTheme.typography.h6)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EndpointEditor(
                label = Strings.t("pf_local"),
                type = localType, onTypeChange = vm::setLocalType,
                value = localValue, onValueChange = vm::setLocalValue,
                placeholder = Strings.t("pf_value_placeholder"),
            )
            Text("→")
            EndpointEditor(
                label = Strings.t("pf_remote"),
                type = remoteType, onTypeChange = vm::setRemoteType,
                value = remoteValue, onValueChange = vm::setRemoteValue,
                placeholder = Strings.t("pf_value_placeholder"),
            )
            Button(onClick = { vm.add() }, enabled = !busy) { Text(Strings.t("pf_add")) }
        }

        // ---- Inline error (no modal) ----
        error?.let { msg ->
            Column(Modifier.fillMaxWidth().padding(8.dp)
                .padding(8.dp)) {
                Text(Strings.t("adb_error"), color = AppColors.current.error)
                Spacer(Modifier.width(0.dp))
                Text(msg, fontFamily = FontFamily.Monospace, color = AppColors.current.error,
                    modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { vm.clearError() }) { Text(Strings.t("clear")) }
            }
        }

        // ---- List toolbar ----
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${forwards.size}", style = androidx.compose.material.MaterialTheme.typography.subtitle2)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { vm.refresh() }, enabled = !busy) { Text(Strings.t("pf_refresh")) }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.removeAll() }, enabled = !busy && forwards.isNotEmpty()) {
                Text(Strings.t("pf_remove_all"))
            }
        }
        Divider(color = AppColors.current.divider)

        // ---- List ----
        if (forwards.isEmpty()) {
            Text(Strings.t("pf_empty"), color = AppColors.current.onSurfaceMuted,
                modifier = Modifier.padding(16.dp))
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(forwards, key = { it.local.adbForm() + ">" + it.remote.adbForm() }) { entry ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${entry.local.adbForm()}  →  ${entry.remote.adbForm()}",
                            fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                        TextButton(onClick = { vm.remove(entry.local) }, enabled = !busy) {
                            Text(Strings.t("pf_remove"))
                        }
                    }
                    Divider(color = AppColors.current.divider)
                }
            }
        }
    }
}

@Composable
private fun EndpointEditor(
    label: String,
    type: ForwardEndpointType,
    onTypeChange: (ForwardEndpointType) -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.Start) {
        Text(label, style = androidx.compose.material.MaterialTheme.typography.caption,
            color = AppColors.current.onSurfaceMuted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { menuOpen = true }) { Text(type.prefix()) }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                ForwardEndpointType.entries.forEach { t ->
                    DropdownMenuItem(onClick = { onTypeChange(t); menuOpen = false }) { Text(t.prefix()) }
                }
            }
            OutlinedTextField(value = value, onValueChange = onValueChange,
                singleLine = true, placeholder = { Text(placeholder) },
                modifier = Modifier.width(180.dp))
        }
    }
}
```

> **Note on `AppColors`:** verify `error` and `onSurfaceMuted` exist on `AppColors` by reading `desktop/src/main/kotlin/com/adbgui/desktop/ui/theme/AppColors.kt` before implementing. If `onSurfaceMuted` is named differently (e.g. `onSurfaceMuted` vs `onSurface.copy(alpha=…)`), use the existing name — do not invent a new field. If `error` doesn't exist, use `MaterialTheme.colors.error`. Match whatever `SystemOpsViewModel`'s error rendering uses in its screen (read `DeviceOverviewScreen.kt`'s SystemOps section for the canonical inline-error pattern and mirror it exactly).

- [ ] **Step 2: Wire `NavPage` + nav entry + `when` branch into AppShell**

In `desktop/src/main/kotlin/com/adbgui/desktop/ui/AppShell.kt`:

a) Add a parameter to the `AppShell` composable signature (near the other VM params ~line 71):
```kotlin
    portForwardingVm: PortForwardingViewModel? = null,
```

b) Add to the `NavPage` enum (line 203):
```kotlin
private enum class NavPage { DEVICE_OVERVIEW, APP_CONSOLE, LOGCAT, SYSTEM_INFO, FILE_EXPLORER, PORT_FORWARDING, SETTINGS }
```

c) Add a nav item in the `navItems` buildList (after the FILE_EXPLORER entry ~line 115):
```kotlin
                if (portForwardingVm != null) add(NavItemSpec(NavPage.PORT_FORWARDING, "nav_port_forwarding", Icons.Filled.SettingsAlt)) // see note
```
> **Icon note:** `Icons.Filled.SettingsAlt` may not exist in the material-icons set this project depends on. Pick an existing icon already imported in AppShell (e.g. reuse `Icons.Filled.Memory` or import `androidx.compose.material.icons.filled.CompareArrows` if available). Verify by checking which `Icons.Filled.*` are used elsewhere; use one that compiles. The icon is decorative.

d) Add a `when` branch (after the FILE_EXPLORER branch ~line 176):
```kotlin
                    selected != null && page == NavPage.PORT_FORWARDING && portForwardingVm != null -> {
                        PortForwardingScreen(vm = portForwardingVm, selectedSerial = selected)
                    }
```

e) Add to `pageTitle` (line 194):
```kotlin
    NavPage.PORT_FORWARDING -> Strings.t("nav_port_forwarding")
```

- [ ] **Step 3: Construct the VM in `Main.kt` and pass to AppShell**

In `desktop/src/main/kotlin/com/adbgui/desktop/main/Main.kt`:

a) After the `fileExplorerVm` line (~line 67), add:
```kotlin
    val portForwardingVm = remember { PortForwardingViewModel(root.repository, selectedSerial, root.scope) }
```

b) In the `AppShell(...)` call (~line 85), add:
```kotlin
                portForwardingVm = portForwardingVm,
```

- [ ] **Step 4: Build the desktop app**

Run: `./gradlew :desktop:compileKotlin`
Expected: BUILD SUCCESSFUL. If `AppColors`/icon names are wrong, fix per the notes in Step 1/2 (read the canonical pattern from `DeviceOverviewScreen.kt`'s SystemOps section).

- [ ] **Step 5: Run all tests**

Run: `./gradlew :core:test :desktop:test`
Expected: all green (core parser/runner/repo + desktop VM).

- [ ] **Step 6: Commit**

```bash
git add desktop/src/main/kotlin/com/adbgui/desktop/ui/PortForwardingScreen.kt \
  desktop/src/main/kotlin/com/adbgui/desktop/ui/AppShell.kt \
  desktop/src/main/kotlin/com/adbgui/desktop/main/Main.kt
git commit -m "feat(desktop): add Port Forwarding page wired into AppShell"
```

---

## Task 7: Manual verification on a real device

**Files:** none (verification only)

**Interfaces:** consumes the whole feature.

- [ ] **Step 1: Run the app**

Run: `./gradlew :desktop:run`

- [ ] **Step 2: Verify on a real device**

With a device connected (USB or wireless), select it, open the Port Forwarding page:
1. Add `tcp:9222 → localabstract:webview_devtools_remote_1` (or any abstract name) → list shows the new row.
2. Refresh → list persists (it's read back from `adb forward --list`).
3. Open a second adb client / run `adb forward --list` in a terminal → confirm the row is real (proves we're driving the real adb server, not a fake).
4. Remove one → list updates; Remove All → list empties.
5. Add a bad forward (e.g. `tcp:abc` → `localabstract:foo`) → inline error shows adb stderr; list is not corrupted.
6. Switch device in the sidebar → list auto-reloads for the new serial (R4 filtering visible if the second device has its own forwards).

- [ ] **Step 3: If a probe is needed, read the app log**

Logs at `%APPDATA%/AdbGui/logs/` — the `CommandRunner` logs each `adb forward ...` call at DEBUG (`cmd -> exit=… out=…`). Toggle DEBUG in Settings if a command misbehaves. (Per `debug-via-logs-not-guessing` memory: read the probe before guessing.)

- [ ] **Step 4: Final commit (if any fixes surfaced from manual testing)**

```bash
# only if Step 2 surfaced a bug — fix under TDD (failing test first), then:
git commit -m "fix(desktop): <what the manual test caught>"
```

---

## Self-Review (run before declaring done)

1. **Spec coverage:** spec §11 line "端口转发" → Tasks 1-6 implement list/add/remove; Task 7 verifies. ✅ The "调试/系统操作" umbrella also mentions `adb pair`/reboot/root/remount/monkey — those are NOT in this plan (pair/reboot/root/remount already implemented per CHANGELOG; monkey is out of scope). ✅
2. **Placeholder scan:** search the plan for "TBD"/"TODO"/"implement later"/"add appropriate". None. The fixture data lines in Task 1 Step 1 are explicitly marked "must be real-recorded, else block the task" — that's a guardrail, not a placeholder. ✅
3. **Type consistency:** `ForwardSpec(ForwardEndpointType.X, value)` signature is identical in Task 1 (definition), Task 2 (CommandRunner tests + impl), Task 3 (repo), Task 4 (VM), Task 6 (Screen). `adbForm()` used consistently. `ForwardEntry.serial/local/remote` used consistently. VM method names (`forward`/`listForwards`/`removeForward`/`removeAllForwards` on repo; `add`/`remove`/`removeAll`/`refresh` on VM) match across Task 4 test, Task 4 impl, Task 6 Screen. ✅
