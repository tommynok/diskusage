# Coder handoff — DiskUsage modernization roadmap

Audience: implementation agent. Read this once, then execute one work item at a time.
Repo state: `master` @ `6a81ec4`. `compileSdk 34 / targetSdk 33 / minSdk 21` (`app/build.gradle:5-12`).
No Compose, no Material dependency, no DI, no instrumentation tests. UI is Activity + legacy
`Theme.DeviceDefault.Light` (`app/src/main/res/values/theme.xml:4`) + custom GL `SurfaceView`.

## Reading list (in this order, nothing else)

| File | Why |
|---|---|
| `app/src/main/java/com/google/android/diskusage/ui/DiskUsage.java` | host Activity, `view()` intent chain, `showDetailBottomSheet()`, toolbar wiring |
| `.../filesystem/mnt/MountPoint.java` + `RootMountPoint.java` | volume discovery |
| `.../datasource/fast/NativeScannerStream.java` | `su` + `libscan.so` process plumbing |
| `.../core/Scanner.java`, `.../core/NativeScanner.java` | two parallel tree builders (Java vs binary stream) |
| `.../opengl/AbstractRenderingThread.java`, `FileSystemViewGPU.java`, `RenderingThread.java` | EGL10/GLES1 loop |
| `.../ui/FileSystemState.java` | gestures, animation, hit-testing |
| `app/src/main/jni/scan.c` | native scanner (binary protocol `D`/`F`/`Z` + NUL-terminated numerics) |
| `app/src/main/AndroidManifest.xml`, `app/build.gradle`, `app/src/main/res/xml/provider_paths.xml` | permissions, deps, FileProvider roots |

`FileSystemEntry` (`filesystem/entity/`) is the shared tree model both scanners build. **Never change
its encoded-size bit layout** (`blockOffset`, `encodedSize`, `COMPARE`) — `NativeScanner` depends on it.

---

## P0 — Breaks today, fix first (no new dependencies)

### P0.1 `FileProvider` crash on every non-primary volume

`DiskUsage.view()` builds a content URI *outside* any `try`:

```java
// DiskUsage.java:294-298
Uri uri = Uri.fromFile(file);
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
  uri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider", file);
}
```

`provider_paths.xml` declares only `<external-path name="root" path="/"/>`, which maps to
`Environment.getExternalStorageDirectory()` = `/storage/emulated/0`. Any path under `/data`,
`/system`, `/storage/<UUID>`, or a root mount throws
`IllegalArgumentException: Failed to find configured root that contains ...` → uncaught crash on
"Open". Fix:

```java
private Uri contentUriFor(File f) {
  try {
    return FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider", f);
  } catch (IllegalArgumentException outsideRoots) {
    return null;                    // caller must fall back, must NOT pass file:// to another app
  }
}
```
and add `<root-path name="ext_volumes" path="."/>` + `<files-path …/>` only if you actually intend
to share outside the shared-storage tree (a `root-path` entry is a real exposure surface — do not
add it reflexively; on Android 7+ `FileProvider` with `root-path` grants other apps read access to
anything you can read, including app-private files).

**Gate:** tap "Open" on a node inside `/data` and inside a USB-OTG volume. Expect a toast, not a crash.

### P0.2 The whole `view()` fallback chain is dead code

`DiskUsage.java:313` (`org.openintents.action.VIEW_DIRECTORY`), `:323`
(`PICK_DIRECTORY`), `:341` (`vnd.android.cursor.item/com.metago.filemanager.dir` — Astro, 2011)
resolve to nothing on any shipping device. Two independent reasons, and the second is the one that
will confuse you:

1. No modern file manager registers those intents.
2. Even reachable intents return an **empty** `queryIntentActivities()` result on Android 11+,
   because package visibility (`targetSdk 30+`) filters them. `QUERY_ALL_PACKAGES`
   (`AndroidManifest.xml:14`) currently masks this, but it is a Play-policy liability and is not
   granted by the user.

Fix both with an explicit `<queries>` block and delete `QUERY_ALL_PACKAGES` if you can:

```xml
<queries>
  <intent><action android:name="android.intent.action.VIEW"/>
          <data android:mimeType="inode/directory"/></intent>
  <intent><action android:name="android.intent.action.VIEW"/>
          <data android:mimeType="resource/folder"/></intent>
  <package android:name="com.totalcommander"/>       <!-- also com.ghisler.android.TotalCommander -->
  <package android:name="com.mixplorer"/>
  <package android:name="com.mixplorer.alpha"/>
  <package android:name="com.android.virtualizer.fileexplorer"/>  <!-- verify per build -->
  <package android:name="com.google.android.apps.docs"/>          <!-- Google Files -->
  <package android:name="com.solofody.ndx.app.galaxy.solidexplorer2"/>
  <package android:name="com.mobilesystems.mifiles"/>             <!-- MIUI Files -->
</queries>
```

### P0.3 The only reliable folder handoff on Android 10–15

There is **no** universal "open this directory in the user's file manager" intent. Any agent answer
claiming otherwise is wrong. Verified options, in this order of robustness:

```java
void openFolder(File dir) {
  // 1. Never works for arbitrary paths: a DocumentsContract URI from another provider
  //    (com.android.externalstorage.documents) throws SecurityException unless that provider
  //    granted us the URI. Do not attempt it.
  // 2. Probe before launching, so "No viewer found" is truthful and never a silent no-op.
  Intent probe = new Intent(Intent.ACTION_VIEW)
      .setDataAndType(contentUriFor(dir), "resource/folder")   // TC/MiX convention
      .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
  List<ResolveInfo> rs = pm.queryIntentActivities(probe, MATCH_DEFAULT_ONLY);
  if (!rs.isEmpty()) { startActivity(rs.size() == 1 ? buildFrom(rs.get(0)) : chooser(rs)); return; }
  // 3. Explicit component + the app's own reveal action, per known manager:
  //    Total Commander: ACTION_VIEW, "resource/folder", file/content URI
  //    Solid Explorer / MiX: ACTION_VIEW + content:// URI, some builds want EXTRA_PATH
  //    Google Files: no public deep-link to an arbitrary directory. Don't fake it.
  // 4. Guaranteed-success fallback (make this the primary visible action in the bottom sheet):
  copyToClipboard(dir.getAbsolutePath());           // already implemented for the toolbar
  Toast: "Path copied. Open your file manager and paste it."
}
```

Design consequences to implement, not just discuss:

- Move the real value **in-app**: "Reveal in treemap" (zoom the GL view to the node) and
  "Show path" must be first-class buttons. Then handoff failure stops being a dead end.
- Keep `ACTION_VIEW` + `FileProvider` URI + `FLAG_GRANT_READ_URI_PERMISSION` for **files** — that
  path (`DiskUsage.java:363-373`) already works and should not be touched.
- Never pass `Uri.fromFile()` to another app. `FileUriExposedException` on N+ is a `RuntimeException`
  you happen to catch at `:320/:334/:346`, but catching it is not a strategy: `uri` is `file://`
  whenever `contentUriFor` returns null. Remove the `Uri.fromFile` line entirely.

### P0.4 Android 11+: `requestLegacyExternalStorage` is inert

`AndroidManifest.xml:32` only has an effect on Android 10. At `targetSdk 33`, everything on
Android 11+ runs scoped-storage-enforced with `MANAGE_EXTERNAL_STORAGE` as the only lever. Keep the
attribute for API 29, but do not reason from it elsewhere.

Also correct a premise in the brief: whether AFA covers `/Android/data` is **version- and OEM-dependent**
(Android 11 often still allows it; 12+ FUSE blocks it on stock and on most OEMs). Do not hardcode
either answer. Probe once at startup and cache the verdict:

```java
static boolean canReadAndroidData(File primary) {
  File d = new File(primary, "Android/data");
  String[] kids = d.list();
  return kids != null && kids.length > 0;
}
```
Feed the result into the UI: if false, render `Android/data` as a distinct **"blocked — needs
root/Shizuku"** node (icon + tooltip + one-tap to the capability sheet) instead of a 0-byte tile.
Right now `Scanner.java:160` (`if (listNames == null) return;`) and `:190`
(`catch (ErrnoException|IOException e) { continue; }`) silently collapse denied directories to
zero bytes, which reads to users as *the app's* bug. Make the unknown explicitly unknown: add a
"restricted" flag to `FileSystemEntry` for dirs that failed with `EACCES` specifically (not other
`ErrnoException`s).

---

## P1 — Fill the size hole without root (before touching Shizuku)

`Apps2SDLoader` already calls `StorageStatsManager`
(`filesystem/Apps2SDLoader.java:61,99`, hardcoded `StorageManager.UUID_DEFAULT`). Generalize it —
this yields correct **sizes** for `Android/data`/`Android/obb` with no elevated privileges, at the
cost of no file-level breakdown.

```java
StorageManager sm = ctx.getSystemService(StorageManager.class);
for (StorageVolume v : sm.getStorageVolumes()) {
  if (!v.isPrimary() && !v.isRemovable()) continue;
  UUID uuid = v.getUuid() != null ? UUID.fromString(v.getUuid()) : StorageManager.UUID_DEFAULT;
  for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
    StorageStats ssm.queryStatsForPackage(uuid, ai.packageName, user);
    // getAppBytes + getDataBytes + getCacheBytes  -> synthetic child node under Android/data
  }
}
```

- Requires `PACKAGE_USAGE_STATS` (declared) **and** the user to grant "Usage access"; if not granted,
  `queryStatsForPackage` throws → route to `Settings.ACTION_USAGE_ACCESS_SETTINGS`
  (`PermissionRequestActivity` is the existing home for this pattern).
- Per-app totals will not sum to the treemap's block-allocated total. Attribute the delta to a
  `<unmeasurable>` node rather than silently mis-summing; the existing
  `FileSystemEntrySmall` "<N files>" placeholder is the model for that.
- Do **not** run this on the UI thread: it is ~1500 binder calls. `StorageStatsManager` calls are
  individually slow and serialized; budget 5–20 s and make it cancellable via the existing
  `ProgressGenerator`.

---

## P2 — Shizuku bridge (rootless power users)

### Verdict on the question asked

Yes, the native `scan` binary can be driven through Shizuku, and it is the right design — the
binary already speaks a stream protocol (`scan.c` → stdout, parsed by `NativeScanner.getByte()`/
`getString()`/`getLong()`), so Shizuku is only a different way to obtain an `InputStream`
connected to the same process. Add a transport; do not touch the parser.

Constraints you must respect:

1. Shizuku gives you **shell (uid 2000, `u:r:shell:s0`)**, not root. Shell *can* traverse
   `/storage/emulated/0/Android/data` on Android 11–15 stock, so it closes the gap; it cannot
   write there. Delete operations must stay disabled on the Shizuku transport.
2. Shell cannot reliably execute a binary straight from `applicationInfo.nativeLibraryDir`
   (`/data/app/~~…/lib/<abi>`), and it cannot read anything in `/data/data/<pkg>`. Copy it to
   `/data/local/tmp` first — the same trick `runWithSu()` already uses (`NativeScannerStream.java:100-115`).
3. All Shizuku binder calls must be off the main thread, and `Shizuku.pingBinder()` can flip to
   false at any time (user kills the Shizuku app). Every call site needs a
   `TransactionFailedException` / `DeadObjectException` path that degrades to the Java `Scanner`,
   not a crash.

### Wiring

```gradle
// app/build.gradle
implementation 'dev.rikka.shizuku:api:13.1.5'
implementation 'dev.rikka.shizuku:provider:13.1.5'
```
```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.INTERACT_ACROSS_USERS_FULL"
                 tools:ignore="ProtectedPermissions"/>  <!-- provider guard, not requested by us -->
<provider android:name="moe.shizuku.server.ShizukuProvider"
          android:authorities="moe.shizuku.provider"
          android:enabled="true" android:exported="true"
          android:permission="android.permission.INTERACT_ACROSS_USERS_FULL"/>
<queries><package android:name="moe.shizuku.privileged.api"/></queries>
```

Refactor the existing hardcoded process spawn into a strategy. The seam is
`NativeScannerStream.Factory.create(path, rootRequired)` (`NativeScannerStream.java:65-98`),
already the single entry point — keep that signature and add a third backend:

```java
interface ScannerBackend {                    // one method, no more abstraction
  InputStream open(String root) throws IOException, InterruptedException;
  Kind kind();                                // LEGACY_JAVA | NATIVE_SHELL | NATIVE_ROOT
  boolean canDelete();
}

// ShizukuBackend
Process p = Shizuku.newProcess(
    new String[]{"/system/bin/sh", "-c", cmd}, null, "/");
// cmd = "cp <nativeLibDir>/libscan.so /data/local/tmp/du_scan_<uid> 2>/dev/null;"
//     + " chmod 755 /data/local/tmp/du_scan_<uid> 2>/dev/null;"
//     + " /data/local/tmp/du_scan_<uid> '<root>'"
return p.getInputStream();       // NativeScanner buffers 64 KiB — matches binder txn size, fine
```

Security notes — apply to the Shizuku path **and** to the existing `su` path, which has the same flaws:

- `/data/local/tmp/diskusage_scan` is a **fixed, world-writable-directory, predictable name**.
  Another app or a previous install can plant a binary there and `su -c` will happily `chmod 755`
  and execute it. Use a unique, per-install name (`du_scan_<sha1(nativeLibraryDir)>`) and prefer
  `rm -f` + fresh copy; check the exit code, not just that bytes arrived.
- `runWithSu` interpolates `root` into a `su -c` string (`NativeScannerStream.java:101-103`).
  `"` quoting does **not** stop `$(…)` / backtick expansion. Mount roots today come from
  `/proc/mounts`, but a malicious USB volume label can carry shell metacharacters.
  Escape with `SecurityUtils.quoteArgs`-style single-quote escaping (`'` → `'\''`), or pass the
  path via stdin, never through the command line.

**Ship order:** behind a Settings toggle "Elevated scanning (Shizuku)". Three UI states —
Shizuku not installed / installed but not running / permission granted — and each needs its own
copy plus a deep link (`moe.shizuku.privileged.api` launcher intent). F-Droid users will want this;
Play users will not find it.

---

## P3 — Volume detection: replace path-string hacks

`MountPoint.initMountPoints()` (`MountPoint.java:92-105`) derives a volume root by regex-stripping
the package's own app-files path:

```java
String path = dir.getAbsolutePath()
    .replaceFirst("/Android/data/" + context.getPackageName() + "/files", "");
```

This is fragile: it breaks for cloned apps/parallel space, work profiles (`/storage/emulated/10`),
OEM suffixes (`/files` vs `/`), and any OEM that relocates app-files. It also cannot see a volume
that exists but has no app-files dir yet (a freshly formatted SD card, a just-plugged OTG drive),
and `forceHasApps = internal` mislabels every secondary volume as app-less.

Refactor to `StorageManager`, keeping the old path only as an API 21–23 fallback:

```java
StorageManager sm = ctx.getSystemService(StorageManager.class);   // API 24+
for (StorageVolume v : sm.getStorageVolumes()) {
  File dir = v.getDirectory();                 // NULL on several OEM builds for the primary volume
  String root = dir != null ? dir.getAbsolutePath() : legacyRootFor(v, ctx);
  mountPoints.add(new MountPoint(
      v.getDescription(ctx), root, /*hasApps*/ true,
      v.isRemovable(), v.getState(), v.getUuid()));
}
```

Gotchas, all of them real:

- `getDirectory()` returns null on some Samsung/Qualcomm builds for primary → **keep**
  `getExternalFilesDirs(null)` as the path resolver for the `UUID_DEFAULT` volume, but stop using
  it as the discovery mechanism. Discovery from `getStorageVolumes()`, path resolution may still use
  the legacy call.
- `getUuid()` is null for private/emulated storage; `UUID_DEFAULT` means "internal".
- `v.getState()` is not public at all SDK levels; use
  `Environment.getStorageState(new File(root))` and compare against `MEDIA_MOUNTED`.
- `StorageVolume.createOpenIntent()` is `@hide` — this is *another* reason the folder handoff in
  P0.3 must be explicit-component based.

### Hotplug

Add a lifecycle-aware receiver in `SelectActivity` (the only screen that lists volumes) and drop
the `static boolean init` cache in `MountPoint` (`MountPoint.java:38,70-71`) — process-wide mutable
statics are the reason `reset()` exists and must be called manually. Hold the list in a
`ViewModel`/repository keyed by volume UUID so rotation and `onResume` re-scan cheaply.

```java
IntentFilter f = new IntentFilter();
for (String a : new String[]{Intent.ACTION_MEDIA_MOUNTED, Intent.ACTION_MEDIA_UNMOUNTED,
    Intent.ACTION_MEDIA_REMOVED, Intent.ACTION_MEDIA_BAD_REMOVAL, Intent.ACTION_MEDIA_EJECT,
    Intent.ACTION_MEDIA_SHARED}) f.addAction(a);
f.addDataScheme("file");
registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);   // REQUIRED at targetSdk 33+
```

- Omitting the export flag throws on Android 13+. `RECEIVER_NOT_EXPORTED` is correct: these are
  system broadcasts.
- `ACTION_MEDIA_BAD_REMOVAL` (pulled without unmount) and `ACTION_MEDIA_EJECT` must both drop the
  volume; `BAD_REMOVAL` additionally must **discard the in-progress scan**, not just the list entry.
- Debounce: OTG drives emit MOUNTED→UNMOUNTED→MOUNTED within ~1 s. Coalesce with 500 ms and ignore
  a volume that flaps 3×.
- If a scan is running on a volume that disappears, the native child gets `SIGBUS`/EIO and
  `NativeScanner.read()` currently throws `RuntimeException("Error: no more data")`
  (`NativeScanner.java:108`). Catch it at the scan boundary, keep the partial tree, and show
  "Volume removed — results incomplete" instead of a crash dialog.
- `RootMountPoint` deliberately skips everything under `/storage/`
  (`RootMountPoint.java:107`), so `/storage/` children must come from the `StorageManager` path —
  after this refactor that is true; before it, it was an accident.

---

## P4 — Renderer: 90/120 Hz and pinch inertia without a rewrite

### What actually caps the frame rate

- The pipeline is **fixed-function GLES 1.x over a hand-rolled `EGL10` context** inside a plain
  `SurfaceView`: `AbstractRenderingThread extends Thread` with `gl = ((EGL10) EGLContext.getEGL())`
  (`:154-178`) and `glVertexPointer`/`glEnableClientState`/`glFrontFace` draws
  (`RenderingThread.java:693-726`).
- The loop is `while(true) { runEvents(); renderFrame(gl); eglSwapBuffers(); }`
  (`AbstractRenderingThread.java:60-64`). `eglSwapBuffers` throttles to the compositor, so this is
  not a CPU spin — but a raw `EGL10` context created without `EGL_PLATFORM`/`EGL_RECORDABLE_ANDROID`
  hints and an untimed swap on a `SurfaceView` frequently lands on the 60 Hz display-mode path
  on 90/120 Hz panels, because the panel refresh selection follows the SurfaceFlinger
  `presentOrValidate` cadence and the app never declares frame-rate intent.
- `android:hardwareAccelerated="false"` on the `.ui.DiskUsage` Activity
  (`AndroidManifest.xml:41`) also degrades the overlaying Android UI (toolbar, dialogs).

### Recommended sequence (each step independently shippable)

1. **Measure before changing anything.** Add `FrameMetrics`/`StatsLog`-free instrumentation: log
   ` Choreographer.getInstance().postFrameCallback` deltas and `Display.getRefreshRate()` vs
   `getSupportedRefreshRates()` in `sizeChanged()`. Confirm the 60 Hz cap on a 120 Hz device.
   Without this, step 2 is guesswork.
2. **Declare frame-rate intent, keep the renderer.** On the `Surface` held by
   `FileSystemViewGPU` (`FileSystemViewGPU.java:17`), call
   `surface.setFrameRate(refreshRate, FRAME_RATE_COMPATIBILITY_DEFAULT)` guarded by
   `Build.VERSION.SDK_INT >= 31`, plus
   `surfaceHolder.setSurfaceFrameRate`-equivalent via
   `View.setRequestedFrameRate(REQUESTED_FRAME_RATE_HIGH)` on the host View at API 31+. This is a
   ~10-line change and the only one that can fix 120 Hz without touching GL.
3. **Make animations clock-based, not frame-count-based.** This is the concrete 120 Hz bug: the
   fling impulse is `speedX * 15` / `speedY * 15` (`FileSystemState.java:512-513`) — 15 *frames*,
   so the same flick travels half the distance at 120 Hz as at 60 Hz. Replace with
   `OverScroller` (`DurationSheetConfig` defaults) fed by a real `VelocityTracker`, and drive
   `flingDistance` from px/s, never from frame counts. The zoom/pan animation already uses
   `animationStartTime`/`animationDuration` (`FileSystemState.java:538-540`) and is time-correct —
   leave it.
4. **Do not rewrite the parser or `FileSystemEntry`.** The GL-side cost is per-frame vertex fill
   from `FloatBuffer`s (`RenderingThread.java:143-157`, `MAX_VERTEX` fixed caps). If profiling
   shows fill-rate as the limit, only then move to GLES 2.0 with one shader program.
5. **Only if step 2 fails:** swap `FileSystemViewGPU` for `GLSurfaceView` with
   `setEGLContextClientVersion(1)` (stays GLES 1.x, so `RenderingThread`'s `GL10` API is reused
   nearly verbatim), `setRenderMode(RENDERMODE_WHEN_DIRTY)`, `queueEvent()` replacing the private
   `events` queue (`AbstractRenderingThread.java:23-106`), and `setEGLConfigChooser(8,8,8,8,6,0)`
   to match the current `EGL_DEPTH_SIZE, 6` choice (`:166-170`). GLSurfaceView gets frame pacing
   and lifecycle handling for free and deletes ~200 lines of hand-rolled EGL. This is a
   contained change, not a rewrite — but it does touch `onPause`/`onResume` and the
   `SurfaceAvailableEvent`/`ExitEvent` control flow, so budget a real device test around
   backgrounding and screen-off.
6. Later: for `TextureView`-based Material transitions (shared-element zoom into the bottom sheet)
   you must leave `SurfaceView`, since `SurfaceView` does not animate with the view hierarchy. Do
   not do this preemptively.

### Pinch-to-zoom quality

`multitouchHandler` + `MyMotionEvent` copies (created on the UI thread, consumed on the render
thread — `FileSystemViewGPU.java:41-44`) mean gestures and rendering are already decoupled, which
is good. Improvements, in order of value:

- Zoom anchored on the **gesture midpoint**, not the screen center. `FileSystemState` already has
  pointer arrays (`getX(i)/getY(i)`, `:436-437`) — the anchor math exists, verify it is used.
- Add `ScaleGestureDetector` alongside (not replacing) the manual path, and let it own
  `onScaleEnd` → snap-to-resting-scale with `OverScroller`.
- The touch-noise filters `filterX`/`filterY` (`:472-473`, `doFilter`/`noFilter`) smooth jitter but
  add latency; at 120 Hz a 2-tap filter is unnecessary. Re-tune or bypass above 90 Hz.

---

## P5 — Feature parity (high value, low architectural risk)

1. **Quick filters.** `FileSystemEntry` children are already sorted by size (`COMPARE`), so a
   "files > N MB" pass is a cheap DFS that sets a highlight flag; no re-scan. Ship three presets
   first — Videos > 500 MB, Downloads, "Unused for 1 year" (needs `st_mtime`, already read in
   `scan.c`; `Scanner.java` discards it — surface it instead of throwing it away). Filter by
   extension with a MIME→extension set; `MimeTypeMap` is already imported in `DiskUsage.java`.
   Render filtered tiles at full color and dim the rest (1 alpha value in the atlas) rather than
   hiding them — hiding destroys the spatial memory that makes a treemap useful.
2. **Duplicate detection.** Hashing is the expensive part and must not block the treemap. Do it as
   a separate, cancellable job: group by `st_size` → within a size class compare first/last 4 KiB →
   only then SHA-256 (streamed, from the same `Scanner` walk so no second traversal). Persist
   hashes keyed by `(path, size, mtime)` so re-scans are incremental. Never hash a file you cannot
   read; `Android/data` blocks are expected here.
3. **Material You.** The tile palette is **not** theme-derived — it is a bitmap atlas built on the
   Java side and uploaded as a GL texture (`RenderingThread.createResources`, `BitmapMap`, and the
   hardcoded `Color.GRAY` at `:647`). Dynamic color therefore means regenerating that atlas from
   `android.R.color.system_accent1_XXX`, which needs no new dependency:
   ```java
   if (Build.VERSION.SDK_INT >= 31) {
     int accent = ctx.getColor(android.R.color.system_accent1_500);   // + 100/200/300/600/700
   }
   ```
   Regenerate on `onConfigurationChanged` (wallpaper change emits it) and when the bottom sheet /
   toolbar colors change. Text color is hardcoded white with a black shadow (`:83-86`) — contrast
   will break with light accents; compute it from luminance, not from a constant.
4. **Bottom sheet.** `showDetailBottomSheet()` is a raw `Dialog` with `Gravity.BOTTOM`
   (`DiskUsage.java`, and `bg_bottom_sheet.xml`). That gives no swipe-to-dismiss, no scrim, no peek
   height, no edge-to-edge inset handling, and no predictive-back on Android 15. Move to
   `BottomSheetDialogFragment` (`com.google.android.material:material:1.11.0`) — note this adds the
   Material dependency, so do P5.3 and P5.4 in one batch and set
   `Theme.Material3.DayNight` + `DynamicColors.applyToActivitiesIfAvailable()` in
   `DiskUsageApplication`. Apply `WindowCompat.setDecorFitsSystemWindows(window, false)` and
   consume `navigationBars` insets in the sheet; the 48 dp Inspector Bar will need top
   `displayCutout` + status-bar insets on the same pass.
5. **Accessibility, cheap wins:** the GL surface exposes no `AccessibilityNodeInfo` at all, so
   TalkBack is blind. Add `ExploreByTouchHelper` publishing the visible top-N tiles
   (name + size + depth). Also: minimum touch target for the cursor, and a
   non-color-only channel for the selected node (the cursor frame is fine — confirm it survives
   deuteranopia-style palettes).

---

## Explicitly out of scope

- Rewriting the renderer in Compose/Canvas. The treemap interaction model *requires* the
  off-thread render loop.
- Changing the `NativeScanner` ↔ `scan.c` wire protocol. Two parsers depend on the byte format.
- Adding Kotlin-only modules, DI, or a test harness migration.
- Bumping `targetSdk` to 35/36 in the same batch as any storage change. Edge-to-edge enforcement
  (API 35) will visually break the toolbar and this bottom sheet; treat it as its own task.

## Definition of done per work item

1. `./gradlew assembleDebug` clean; install on one Android 11-class and one Android 14/15 device.
2. `adb shell dumpsys package com.diskusage.ng | grep -A6 "Runtime permissions"` shows the
   permission actually used by the feature, and the feature degrades gracefully when revoked
   (`adb shell pm revoke com.diskusage.ng android.permission.MANAGE_EXTERNAL_STORAGE`).
3. For scanning changes: `adb shell ls -la /storage/emulated/0/Android/data | head` compared
   against the app's node size for the same path; report both numbers, and state the delta.
4. For renderer changes: `adb shell dumpsys gfxinfo com.diskusage.ng framestats` before/after, on a
   90/120 Hz device, while performing one fling and one pinch. No regression in `Janky frames`.
5. `CHANGELOG` entry + one paragraph in `docs/` explaining *why* (the original design discussion is
   not recoverable; the docs are the only record of rationale).
