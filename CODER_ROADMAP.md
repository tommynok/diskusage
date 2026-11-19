# Coder handoff — DiskUsage modernization roadmap

Audience: implementation agent.
Repo state: `master` @ `96852b2` · verified 2026-11-19 · `compileSdk 34 / targetSdk 33 / minSdk 21`.
Stack: Activity + legacy `Theme.DeviceDefault.Light` (`res/values/theme.xml:4`) + custom EGL10/GLES1
`SurfaceView`. Shizuku SDK 13.1.5 (`app/build.gradle:100-101`). Porter via hand-rolled AIDL
(`app/build.gradle:17` `aidl true`). No Compose, no Material dependency, no instrumentation tests.

## MAINTENANCE RULE — mandatory, not optional

**This file is a living document. The agent MUST update it in the SAME commit as any change that
alters the status of an item below.** A fix shipped without a roadmap update is an incomplete fix.

On every batch:
1. Re-verify each cited `file:line` before trusting it. Line numbers drift; this file has already
   gone stale once (it was written against `6a81ec4` and survived 21 commits unchanged).
2. Move finished items to **DONE** with the commit hash and the current evidence line.
3. Add newly discovered defects as new items — do not silently fix them outside the list.
4. Delete items that are no longer reachable. Do not keep dead proposals "for history"; git is the history.
5. Update the `Repo state` line above.

## Authority order

`docs/GUIDELINES.md` **overrides this roadmap.** Read it first. Two constraints that change proposals here:
- **§1** — every UI element name must be grepped from `res/values-ru/strings.xml`, never invented or
  back-translated. There is no «Расширенный доступ» string; the canonical item is
  `R.string.menu_shizuku` = **«Доступ к Shizuku / Porter»**.
- **§3** — the classic treemap is **canonical and immutable**: colour differentiation, rectangular
  grid, navigation logic. Graphics work is **under the hood only** (90/120 Hz, V-Sync, power).
  This **cancels** any proposal to recolour tiles with Material You. Dynamic colour may touch app
  chrome only, never the treemap palette.

Canonical strings relevant to open items: `button_show_in_fm` = «В проводнике»,
`no_viewer_found` = «Не найдено приложение для открытия», `storage_card` = «Память устройства»,
`elevated_access_providers` = «Root, Shizuku или Porter»,
`elevation_not_available` = «Требуются Root-права или запущенный Shizuku / Porter».

---

## Status board

| Item | Status | Evidence |
|---|---|---|
| P0.1 FileProvider crash outside primary volume | **DONE** `602dfc5` | `DiskUsage.contentUriFor()` :325-333 catches `IllegalArgumentException`/`SecurityException`; `provider_paths.xml` expanded |
| P0.2 legacy OI/Astro intent chain | **DONE** `602dfc5`,`ec90f1f` | chain removed; `<queries>` at `AndroidManifest.xml:31-53` |
| P0.3 folder handoff to modern FMs | **DONE** `7903f72`,`30ca676` | `revealInFileManager()` :388; per-app targets + MIME probe :492-497; chooser :614 |
| P2 Shizuku bridge | **DONE** `7903f72`,`ed42990` | deps :100-101; `ShizukuProvider` manifest :92-99; `runWithShizuku()` `NativeScannerStream.java:135-150` |
| Porter bridge (new, not in original roadmap) | **IN PROGRESS — uncommitted** `96852b2` + working tree | see **P0-PORTER**; audit verdict was FAIL, fixes for F1/F2/F5/F8/F9/F10 are present in the working tree but **not committed and not device-verified** |
| Storage selector / cancelable progress / navbar FM button | **DONE** `925929c`,`969dfa9`,`d693acf` | — |
| P1 per-volume `StorageStatsManager` | **OPEN** | `Apps2SDLoader.java:99` still hardcoded `StorageManager.UUID_DEFAULT` |
| P3 volume detection + OTG hotplug | **OPEN — untouched** | `MountPoint.java:97` still `replaceFirst(...)`; **zero** occurrences of `getStorageVolumes`/`StorageVolume`/`ACTION_MEDIA_MOUNTED` in the whole source tree |
| P4 renderer frame pacing + inertia | **OPEN — untouched** | `AbstractRenderingThread.java:155,162` still `EGL10`; fling still frame-count based at `FileSystemState.java:569-570` |
| P5 filters / duplicates / a11y / bottom sheet | **OPEN** | no `MessageDigest` anywhere; `showDetailBottomSheet()` :917 still raw `Dialog` + `Gravity.BOTTOM` :927 |

---

## P0-PORTER — ship-blockers from the `96852b2` audit

> **Working-tree state (2026-11-19):** fixes referencing F1/F2/F5/F8/F9/F10 already exist,
> uncommitted, in `PorterClient.java`, `PorterProcess.java`, `NativeScannerStream.java`,
> `DeviceHelper.kt`, `SelectActivity.java`. Line numbers below refer to committed `96852b2` and
> **must be re-verified** against the working tree before acting. Do not re-implement what is there.
>
> Present: background worker for attach (F5), `transactExitCode()` throwing instead of returning 0
> (F1), `escapeShellArg` + unique temp name + `trap 'rm -f' EXIT` (F8), stderr drain thread (F9),
> `su -c id` verification (F10), and a stdin-EOF self-kill monitor for cancellation (F2).
>
> **Verify this specific concern in the F2 fix** (`PorterClient.java:319-324`): the wrapper does
> `(setsid "$TMP" …) & PID=$!` then `kill -TERM -$PID`. `setsid` puts the child in a **new session
> whose PGID is the grandchild's PID, not `$!`** (the subshell's). So `-$PID` likely targets a
> non-existent group and the fallback `kill -TERM $PID` kills only the subshell — leaving `scan`
> alive. Confirm on device with `tools/verify_porter.py` (F2 must be PASS); if the process survives,
> capture the real PGID (`setsid sh -c 'echo $$ > …'`) or drop `setsid` and kill the subshell's group.

Audit verdict on the committed code: **FAIL**. The wire protocol is **correct** — do not "fix" it.
Verified against upstream `d4rken-org/porter-api@main` source, not the prose docs:

- all three AIDL files are byte-identical to upstream, **including the deliberately unallocated id 7**;
- `createHost = 200` / `getExitCode = 202` = `TRANSACTION_PORSH_BASE(200)` + `PorshConfig` offsets `0/1/2`;
- `writeByte` for tty is **right** (server does `readByte()`, not a boolean);
- `writeFileDescriptor` (raw FD, not Parcelable) is **right**; arg order
  tty→stdin→stdout→stderr→args→env→dir matches `PorshService.onTransact`;
- `attach` keys, `protocol-version = 4`, provider authority `.porter.api`, `sendBinder`/`getBinder`,
  `eu.darken.porter.server.extra.BINDER`, `INTERACT_ACROSS_USERS_FULL` + exported + `multiprocess=false`
  all match `PorterProtocol.kt` and satisfy every `check()` in `DeliveryEndpoint.attached()`.

Hand-rolling AIDL instead of the SDK is legitimate: the SDK is coroutines/`Flow` and
*"use from Java is not supported."* The price is owning protocol drift and the process-kill path.

### F2 — CRITICAL · cancel does not kill the child (orphan elevated process)
`PorterProcess.java:90-100`, `NativeScannerStream.java:57-71`, `DiskUsage.abortScan()` :787

The raw porsh path exposes **only 200/201/202 — there is no destroy transaction** (`PorshConfig` has
exactly three codes). Upstream's SIGKILL-to-process-group lives on `IPorterRemoteProcess.destroy() = 6`,
reachable **only through the shell user service** (`addUserService`, `com.example:porter_shell`),
which this integration never uses. `destroy()` here only closes the three local stream ends; the child
gets EPIPE/SIGPIPE **only on its next write**, so a `scan` blocked in `readdir`/`stat` keeps running at
root/shell identity after cancel or Back.

Fix: move execution to the documented `startProcess`/user-service path so `destroy()` really kills the
group. If staying on raw porsh, make the child self-terminating on stdin EOF (wrapper shell that kills
its own process group). Closing streams alone is not cancellation.

### F5 — HIGH · main-thread blocking IPC (ANR)
`PorterClient.java:115` (`static synchronized`), `:132` (`attach`), `:96` (`ContentResolver.call`), `:46`,`:63`

`onBinderReceived` holds the class lock across a blocking `attach()`, and is reached on the **main
thread** from `SelectActivity.onResume()`, `requestElevatedAccess()` step 0, and `PorterClient.init()`
in both activities' `onCreate`. Upstream explicitly offloads this (*"the attach behind it blocks, for
seconds on the Shizuku wire"*, `Porter.deliveryExecutor`). Also `dispatchRequestPermissionResult` runs
on a binder thread and calls `checkSelfPermission()` — re-entrant synchronous IPC inside a `oneway`
callback. And `SelectActivity.porterBinderReceivedListener` calls `DeviceHelper.isElevated()` on the
main thread, which can spawn up to 4 `su`/`which` probes with `waitFor()` (`DeviceHelper.kt:58-75`).

Fix: one background executor for `init`/`fetch`/`attach`; never call `sService.*` inside a Stub
callback; keep only listener dispatch on main (already correct at `:50`, `:142`).

### F10 — HIGH · a broken `su` cuts off Porter and Shizuku entirely
`DeviceHelper.kt:50-55`, `NativeScannerStream.java:89-99`

`isDeviceRooted()` returns true on **mere existence of an `su` file**. `runWithSu` only catches
`IOException` from `exec`, so a leftover/broken `su` still yields a non-null `Process` →
`process != null` → **Porter and Shizuku are never attempted**, and the scan dies with
"Error: no more data". Verify `su` actually works (exit code of `id`) before preferring it, or fall
through on non-zero exit / empty stdout. Fallback order su → Porter → Shizuku (`:89-115`) is otherwise
correct and non-breaking for root-only / Shizuku-only users.

### F8 — MEDIUM · confirmed on device: leftover predictable elevated binaries
`PorterClient.java:203-206`, `NativeScannerStream.java:136-139`, `:153-155`

Observed in `/data/local/tmp` on the maintainer's device (2026-11-19):
```
-rwxr-xr-x root  root   8152  2026-11-17  diskusage_scan
-rwxr-xr-x root  root   8152  2026-11-18  du_scan_0
-rwxr-xr-x shell shell  8152  2026-11-18  du_scan_2000
```
Three deterministic names, mode 755, owned by root and shell, **never cleaned up**. `cp -f` overwrites
whatever is at that path. `"`-quoting does not stop `$(…)`/backtick expansion, and `root` comes from
mount points / volume labels. Scope honestly: `/data/local/tmp` is writable by shell-identity
processes, not by ordinary apps — so this is a shell-level hazard, not an any-app exploit.

Fix: single-quote-escape (`'` → `'\''`) or avoid the shell; unique names; delete the temp binary after
the scan; drop `2>/dev/null` so failures are diagnosable. Unify all three paths on one helper.

### Backlog (academic — do not spend a batch on these)
- **F1** `PorterProcess.waitFor()` :54-74 returns `0` on dead binder and caches the server's `-1`
  no-host sentinel; `getExitCode` is PID-keyed with one slot, so a second scan in the same PID
  overwrites the first. Real, but the exit code is currently ignored by `NativeScannerStream.close()`,
  so user impact is limited to rescan collisions. Fix alongside F2.
- **F3** `createPipe()` at `PorterClient.java:209-211` sits outside the try → FD leak only on EMFILE.
- **F4** `PorterClient.java:45-46` `… || checkSelfPermission()` could mask a denial. **Downgraded to
  LOW**: the server enforces `enforceCallingPermission("createHost")` on every transaction
  (`PorshService.onTransact`), and upstream `ServiceAuthorizationTest.kt:276,283,322` asserts
  `SecurityException` for unattached/revoked callers. **There is no privilege bypass.** Worst case is
  a wrong UI state. Still worth trusting the pushed bundle when the key is present.
- **F6** `porter:reply-unsupported` ignored (`:133-135`) → silent no-op only once Porter raises its
  version floor (currently `MIN_VERSION = 4`, same as the client).
- **F7** stale `DeathRecipient` (`:69-74`,`:121`) can wipe a freshly installed connection on Porter
  restart; never unlinked, notifies no listeners.
- **F9** `getErrorOutput()` drains via `available()` (usually empty) and `close()` destroys stderr
  before anything reads it.

### Device verification
One interactive script, no command lists: **`python tools/verify_porter.py`**.
It snapshots processes, captures logcat, and prints PASS/FAIL for F2/F4/F5/F8; full log to
`tools/porter_verify.log`. All adb calls are pipe-free (filtering in Python) so Windows quoting is
irrelevant. Acceptance for F2: the scan process must be **gone** after cancel.

---

## P1 — per-volume sizes without root (OPEN)

`Apps2SDLoader.java:99` still queries only `StorageManager.UUID_DEFAULT`, so secondary volumes and
`Android/data` aggregates are wrong or missing. Generalize over `getStorageVolumes()` and
`queryStatsForPackage(uuid, pkg, user)`. Requires `PACKAGE_USAGE_STATS` (declared) plus the user's
"Usage access" grant; route refusal to `Settings.ACTION_USAGE_ACCESS_SETTINGS` via
`PermissionRequestActivity`. ~1500 binder calls — never on the UI thread, make it cancellable through
the existing `ProgressGenerator`. Per-app totals will not sum to block-allocated totals: attribute the
delta to a `<unmeasurable>` node (model: `FileSystemEntrySmall`) rather than mis-summing.

Also still open from the original audit: denied directories collapse silently to 0 bytes
(`Scanner.java:160` `if (listNames == null) return;`, `:190` `catch … continue`). Whether AFA covers
`/Android/data` is **version- and OEM-dependent** — probe at runtime, do not hardcode. Surface a
"restricted" state instead of a 0-byte tile.

## P3 — volume detection + OTG hotplug (OPEN, untouched)

`MountPoint.java:92-105` still derives roots by regex-stripping the app's own files path (`:97`).
Breaks for cloned apps, work profiles (`/storage/emulated/10`), OEM suffixes, and volumes with no
app-files dir yet (fresh SD, just-plugged OTG). `forceHasApps = internal` mislabels secondary volumes.

Refactor discovery to `StorageManager.getStorageVolumes()` (API 24+), keeping `getExternalFilesDirs`
**only as a path resolver** for `UUID_DEFAULT` — `getDirectory()` returns null on some OEM builds.
`getUuid()` is null for emulated storage. Use `Environment.getStorageState(File)` rather than
`StorageVolume.getState()`. Keep an API 21-23 fallback. `StorageVolume.createOpenIntent()` is `@hide`.

Hotplug: receiver in `SelectActivity` for `ACTION_MEDIA_MOUNTED/UNMOUNTED/REMOVED/BAD_REMOVAL/EJECT/SHARED`,
`addDataScheme("file")`, and **`Context.RECEIVER_NOT_EXPORTED` is mandatory at targetSdk 33+**.
`BAD_REMOVAL` must discard the in-progress scan, not just the list entry. Debounce OTG flap (~500 ms).
Drop the process-wide `static boolean init` cache (`MountPoint.java:38,70-71`). A vanished volume makes
`NativeScanner.read()` throw `RuntimeException("Error: no more data")` (`NativeScanner.java:108`) —
catch at the scan boundary, keep the partial tree, report "volume removed".

## P4 — renderer: 90/120 Hz and inertia (OPEN, untouched)

GUIDELINES §3 restricts this to **under-the-hood** work. No visual redesign.

1. **Measure first.** Log `Choreographer` frame deltas and `Display.getRefreshRate()` vs
   `getSupportedRefreshRates()` in `sizeChanged()`. Confirm the cap on a 120 Hz panel before changing code.
2. **Declare frame-rate intent, keep the renderer.** `Surface.setFrameRate(...)` (API 30+) /
   `View.setRequestedFrameRate(...)` (API 31+) on the surface held by `FileSystemViewGPU`.
   *Verify the exact constant names against the SDK — the original audit used approximate names.*
3. **Fix the real 120 Hz bug:** the fling impulse is `speedX * 15` / `speedY * 15`
   (`FileSystemState.java:569-570`) — 15 *frames*, so the same flick travels half as far at 120 Hz.
   Replace with `OverScroller` + a real `VelocityTracker`, distance from px/s. The zoom/pan animation
   already uses `animationStartTime`/`animationDuration` and is time-correct — leave it.
4. Only if profiling shows fill-rate limits: GLES 2.0 with one shader program.
5. Only if step 2 fails: `GLSurfaceView` with `setEGLContextClientVersion(1)` (reuses the `GL10` API),
   `RENDERMODE_WHEN_DIRTY`, `queueEvent()` replacing the private event queue
   (`AbstractRenderingThread.java:23-106`), config chooser matching the current `EGL_DEPTH_SIZE, 6`.
   Deletes ~200 lines of hand-rolled EGL but touches pause/resume — budget real device testing.

`android:hardwareAccelerated="false"` on `.ui.DiskUsage` (`AndroidManifest.xml:75`) also degrades the
toolbar and dialogs. Investigate why before removing.

## P5 — features (OPEN)

- **Quick filters.** Children are already size-sorted (`COMPARE`), so a "files > N MB" pass is a cheap
  DFS setting a highlight flag — no re-scan. Presets: Videos > 500 MB, Downloads, unused-for-1-year
  (`st_mtime` is read in `scan.c` but discarded by `Scanner.java` — surface it).
  **GUIDELINES §3 constraint:** do not recolour or hide tiles. Dimming via alpha is a visual change to
  the canonical treemap — get owner approval before implementing, or render the filter as a separate
  list overlay instead.
- **Duplicate detection.** Separate cancellable job: group by `st_size` → compare first/last 4 KiB →
  only then streamed SHA-256, reusing the same walk. Persist hashes keyed by `(path, size, mtime)`.
- **Bottom sheet.** `showDetailBottomSheet()` :917 is a raw `Dialog` + `Gravity.BOTTOM` :927 — no
  swipe-to-dismiss, no scrim, no peek height, no inset handling, no predictive-back on Android 15.
  Moving to `BottomSheetDialogFragment` adds the Material dependency; batch it with chrome-only
  dynamic colour (`android.R.color.system_accent1_*`, API 31+, no dependency needed for the colour
  itself). **Never apply dynamic colour to the treemap palette** (GUIDELINES §3).
- **Accessibility.** The GL surface publishes no `AccessibilityNodeInfo` — TalkBack is blind.
  `ExploreByTouchHelper` over the visible top-N tiles (name + size + depth).

---

## Explicitly out of scope

- Recolouring or restructuring the treemap (GUIDELINES §3).
- Deleting apps, or Delete/Show on protected partitions and special blocks (GUIDELINES §2).
- Rewriting the renderer in Compose/Canvas; changing the `NativeScanner` ↔ `scan.c` wire protocol;
  changing `FileSystemEntry`'s encoded-size bit layout (`blockOffset`, `encodedSize`, `COMPARE`).
- Bumping `targetSdk` to 35/36 in the same batch as any storage or UI change — edge-to-edge
  enforcement will break the toolbar and the bottom sheet. Separate task.
- Adding the Porter Kotlin SDK (Java unsupported). If protocol drift becomes a maintenance burden,
  raise it as a decision for the owner, not a unilateral rewrite.

## Definition of done per work item

1. `./gradlew assembleDebug` clean; install on one Android 11-class and one Android 14/15 device.
2. Feature degrades gracefully when its permission is revoked
   (`adb shell pm revoke com.diskusage.ng android.permission.MANAGE_EXTERNAL_STORAGE`).
3. Scanning changes: compare `adb shell ls -la /storage/emulated/0/Android/data` against the app's
   node size for the same path; report both numbers and the delta.
4. Renderer changes: `adb shell dumpsys gfxinfo com.diskusage.ng framestats` before/after on a
   90/120 Hz device during one fling and one pinch; no regression in janky frames.
5. Porter changes: `python tools/verify_porter.py` → F2 must be PASS.
6. Changelog entry + one paragraph of *why* in `docs/` (the original design discussion is not
   recoverable; docs are the only rationale).
7. **Update this file** per the Maintenance Rule above. Same commit.
