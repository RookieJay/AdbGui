# LsParser 多设备日期格式 fixture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `LsParser` parse `ls -la` output from devices that use the classic `Mon DD HH:MM` / `Mon DD  YYYY` date format (old Android / busybox / some Toybox), not just the ISO `YYYY-MM-DD HH:MM` format the current regex handles. Ship with real recorded fixtures from ≥2 device formats.

**Architecture:** A new real-recorded fixture file (`ls_la_output_mon.txt`) captures the `Mon DD` format. A test reads that fixture and asserts entries parse (fails today — those lines are silently dropped). Then `LsParser`'s regex is broadened to accept either date token shape; the captured date is stored verbatim in `FileEntry.date` (display only). No model change.

**Tech Stack:** Kotlin, JUnit/kotlin.test, runTest-free (pure parser). Fixtures are real `adb shell ls -la` recordings per CLAUDE.md (no hand-written output).

**Spec:** CLAUDE.md "技术债防范规范 §4: Parser fixture 必须真实录制" + "已知未修: LsParser 只覆盖了 VIDAA TV 的 YYYY-MM-DD 格式；旧 Android 可能用 Mon DD HH:MM。需补充多设备 fixture。"

## Global Constraints

- **Fixtures 必须真实录制**（CLAUDE.md §4）：不许手写 `ls -la` 输出。必须 `adb shell ls -la <dir> > fixtures/xxx.txt` 录制。文件首行注释标明 **设备型号 + Android 版本 + 录制日期 + 目录**。
- **`:core` no UI deps.** `LsParser` stays a pure `object` in `:core/adb`.
- **TDD:** failing test (from the real fixture) → fix regex → green → commit.
- **Conventional Commits:** `fix(core):`.
- **No dead code:** don't add unused regex groups.

## Date format background (verified)

`ls -la` date field shapes seen in the wild:
- ISO (modern Toybox, e.g. VIDAA Android): `2026-08-18 09:48` — 2 tokens.
- Classic (old Android toolbox / busybox): `Aug 18 09:48` (recent files) or `Aug 18  2024` (older than ~6 months) — 3 tokens, month = 3 letters, day 1–2 digits, last token time `HH:MM` or 4-digit year. Multiple spaces between fields (right-aligned).

The current regex only matches `(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2})`, so any `Mon DD` line fails `matchEntire` and is dropped by `mapNotNull` → entries silently missing in File Explorer on those devices.

---

## File Structure

```
:core
├─ adb/LsParser.kt                      broadened regex (date = 1 group, ISO | Mon)
└─ test/resources/fixtures/
   ├─ ls_la_output.txt                  existing (VIDAA, ISO) — unchanged
   └─ ls_la_output_mon.txt              NEW — real Mon DD recording
:core/test
└─ adb/LsParserTest.kt                 + reads ls_la_output_mon.txt, asserts parse
```

---

## Task 1: Acquire a real `Mon DD` fixture (USER-DRIVEN — executor cannot run adb)

**This task requires a human with a device.** The executing subagent must **ask the user** to run the commands and paste the output; the subagent then saves the fixture + writes the header.

**Files:**
- Create: `core/src/test/resources/fixtures/ls_la_output_mon.txt`

- [ ] **Step 1: Ask the user to record `ls -la` from a device that shows the `Mon DD` format**

Tell the user (exact message to send):

> 我需要一台**显示 `Mon DD HH:MM` 日期格式**的设备（老 Android / busybox / 某些 Toybox）。请连上这样一台设备，运行：
> ```
> adb shell ls -la /
> ```
> 把开头 ~20 行（含 `total` 行 + 若干 `drwx...` / `lrw...` 行）原样贴给我。如果日期列显示成 `Aug 18 09:48` 或 `Aug 18  2024` 这种，就是对的。若你手上设备都显示 `2026-08-18 09:48`（ISO），告诉我——我们改用别的途径（模拟器旧镜像 / busybox ls）。

Also ask for: **device model + Android version** (for the header).

- [ ] **Step 2: Save the fixture with a header**

Save the user's pasted output verbatim to `core/src/test/resources/fixtures/ls_la_output_mon.txt`, with a FIRST line comment (CLAUDE.md §4 requires device + version + date + dir):

```
# device: <model> | android: <version> | recorded: 2026-08-20 | dir: / | format: Mon DD HH:MM / Mon DD YYYY
```

(The `#` first line is a comment — `LsParser.parse` skips lines that don't match the regex, so the header is harmless; but the test trims it — see Task 2.)

- [ ] **Step 3: Commit the fixture**

```bash
git add core/src/test/resources/fixtures/ls_la_output_mon.txt
git commit -m "test(core): real ls -la fixture (Mon DD date format) from <device>"
```

---

## Task 2: Failing test reading the Mon DD fixture (TDD)

**Files:**
- Modify: `core/src/test/kotlin/com/adbgui/core/adb/LsParserTest.kt`

**Interfaces:**
- Consumes: `core/src/test/resources/fixtures/ls_la_output_mon.txt` (Task 1).

- [ ] **Step 1: Add the failing test**

Append to `LsParserTest`:

```kotlin
@Test
fun parses_mon_dd_date_format_from_fixture() {
    val raw = LsParserTest::class.java.getResourceAsStream("/fixtures/ls_la_output_mon.txt")!!
        .bufferedReader().readText()
    // Drop the leading '# device ...' header comment so only ls lines are parsed.
    val out = raw.lineSequence().dropWhile { it.startsWith("#") }.joinToString("\n")
    val list = LsParser.parse(out)
    assertTrue("expected ≥1 entry from the Mon DD fixture, got 0 — regex doesn't match Mon DD format" , list.isNotEmpty())
    // None of the parsed entries should carry a raw ISO date; their dates are Mon DD tokens.
    assertTrue(list.all { !it.date.contains(Regex("\\d{4}-\\d{2}-\\d{2}")) })
}
```

(If the fixture's real `ls -la` lines include a `Mon DD  YYYY` old-file entry, the parser must still produce a `FileEntry` for it — `isNotEmpty()` covers it.)

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :core:test --tests "*LsParserTest.parses_mon_dd*" 2>&1 | tail -15`
Expected: FAIL — `list.isNotEmpty()` assertion fails (0 entries) because the regex drops every Mon DD line. (If it PASSES, the fixture isn't actually Mon DD — go back to Task 1.)

- [ ] **Step 3: Commit the failing test**

```bash
git add core/src/test/kotlin/com/adbgui/core/adb/LsParserTest.kt
git commit -m "test(core): failing — LsParser drops Mon DD ls -la lines"
```

---

## Task 3: Broaden LsParser regex to accept Mon DD dates

**Files:**
- Modify: `core/src/main/kotlin/com/adbgui/core/adb/LsParser.kt`

**Interfaces:**
- Produces: `LsParser.parse` now returns entries for both ISO and Mon DD date formats; `FileEntry.date` holds the raw date tokens (e.g. `Aug 18 09:48`).

- [ ] **Step 1: Replace the regex + date capture**

In `LsParser.kt`, change the regex so the date is one group matching either shape, and update the `date` assignment:

Current:
```kotlin
    // drwxrwx--- 2 root root 4096 2020-01-01 12:00 Photos
    // lrw-r--r-- 1 root root 50 2020-01-01 12:00 etc -> /system/etc
    private val re = Regex("""^([ldrwxst-]{10})\s+\d+\s+\S+\s+\S+\s+(\d+)\s+(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2})\s+(.+)$""")
```

New:
```kotlin
    // drwxrwx--- 2 root root 4096 2020-01-01 12:00 Photos          (ISO: YYYY-MM-DD HH:MM)
    // drwxr-xr-x 2 root root 4096 Aug 18 09:48 cache               (Mon DD HH:MM)
    // -rw-r--r-- 1 root root  123 Aug 18  2024 old.log             (Mon DD  YYYY, old file)
    // lrw-r--r-- 1 root root 50 2020-01-01 12:00 etc -> /system/etc
    // Date = one group, ISO (2 tokens) or Mon DD (3 tokens); captured verbatim into FileEntry.date.
    private val re = Regex(
        """^([ldrwxst-]{10})\s+\d+\s+\S+\s+\S+\s+(\d+)\s+""" +
        """(?:(\d{4}-\d{2}-\d{2}\s+\d{2}:\d{2})|([A-Z][a-z]{2}\s+\d{1,2}\s+(?:\d{2}:\d{2}|\d{4})))\s+(.+)$"""
    )
```

And in `parse`, replace the `date` extraction (groups shifted — perms=1, size=2, ISO-date=3, Mon-date=4, name=5; only one of 3/4 matches):

```kotlin
            .mapNotNull { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("total")) return@mapNotNull null
                val m = re.matchEntire(trimmed) ?: return@mapNotNull null
                val perms = m.groupValues[1]
                val date = m.groupValues[3].ifBlank { m.groupValues[4] }  // ISO group, else Mon group
                val name = m.groupValues[5].trim()
                val linkName = name.substringBefore(" -> ")
                if (linkName == "." || linkName == "..") return@mapNotNull null
                FileEntry(
                    name = linkName,
                    isDirectory = perms.firstOrNull() == 'd',
                    isSymlink = perms.firstOrNull() == 'l',
                    size = m.groupValues[2].toLongOrNull() ?: 0,
                    date = date,
                    permissions = perms,
                    raw = line,
                )
            }
```

- [ ] **Step 2: Run the new test to verify it passes**

Run: `./gradlew :core:test --tests "*LsParserTest.parses_mon_dd*" 2>&1 | tail -15`
Expected: PASS (≥1 entry, none with ISO date).

- [ ] **Step 3: Run the full LsParser suite (no regressions on ISO)**

Run: `./gradlew :core:test --tests "*LsParserTest" 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL (the existing ISO tests still pass — the ISO alternation branch is unchanged).

- [ ] **Step 4: Commit**

```bash
git add core/src/main/kotlin/com/adbgui/core/adb/LsParser.kt
git commit -m "fix(core): LsParser accepts Mon DD ls -la date format (old Android/busybox)"
```

---

## Task 4: Smoke on the real Mon DD device (USER-DRIVEN)

- [ ] **Step 1: Build + run the app** — `./gradlew clean :desktop:run` (or `run.bat clean`).

- [ ] **Step 2: Ask the user to verify File Explorer on the Mon DD device**

> 请在刚录 fixture 的那台设备上，打开应用左侧「文件」页，进入 `/`（或录制的目录）。确认目录条目都列出来了（之前 Mon DD 设备会丢行）。点开一个子目录能正常进入。

- [ ] **Step 3: Run full test suite**

Run: `./gradlew :core:test :desktop:test 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

---

## Self-Review

**1. Spec coverage:** Mon DD date format → Task 1 (fixture) + Task 2 (failing test) + Task 3 (regex). ISO format preserved (alternation keeps ISO branch). Real fixture (not hand-written) per CLAUDE.md §4. ✅
**2. Placeholder scan:** No TBD/TODO. Task 1 is explicitly user-driven (real adb recording can't be synthesized). Regex code is concrete. ✅
**3. Type consistency:** `FileEntry.date` stays a `String` (now holds Mon tokens too). Regex groups: 1=perms, 2=size, 3=ISO-date, 4=Mon-date, 5=name — used consistently in Task 3. Fixture filename `ls_la_output_mon.txt` used identically in Task 1 (save) + Task 2 (read). ✅
**4. TDD:** Task 2 failing-from-real-fixture → Task 3 fix → green. Task 4 smoke on the real device. ✅
**5. Red lines:** `:core` only gains a regex broadening (no UI/process). No new interfaces. ✅

No gaps. Plan complete.

---

## Execution Handoff

Plan saved to `docs/superpowers/plans/2026-08-20-lsparser-multi-device-fixtures.md`. Open a fresh Claude window and run:

> 读 `docs/superpowers/plans/2026-08-20-lsparser-multi-device-fixtures.md`,按 CLAUDE.md 约定执行(TDD + 每任务一个提交)。Task 1 需要你连一台 Mon DD 格式的设备录制 fixture。

Two execution options: (1) Subagent-Driven (recommended); (2) Inline.
