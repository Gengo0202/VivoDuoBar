# Duo Status Bar 1.4.2 — stable

The 1.4.2 beta line, plus fixes from user reports. Safe to keep switched on.

- **Every bar now draws the same element.** The lock screen and the pulled-down shade used to draw the
  ring at a different size, and could even fall back to the simple drawing while the home bar used the
  animated one. All three bars now share one size and one renderer.
- **The colour follows the bar again.** On some ROMs the element flickered between black and white
  because the bar's brightness was mistaken for a colour; it now reads the real icon tint.
- **Measured ROM profiles actually load** (they were never read from the module's own files before).
- **New "Show charging bolt" switch.** You can hide the bolt while charging, independent of the charging
  animation. The ring still turns green for a charging battery.
- The settings previews show 40% so the ring reads clearly.

# Duo Status Bar 1.4.2-beta.2 — pre-release

Fixes from testing beta.1.

- **"Allow root access" now works and explains itself.** KernelSU, KernelSU Next and SukiSU never show a
  permission popup — you must enable Duo Status Bar in the root app's Superuser list. The row now shows a
  short animated guide (root manager → Superuser → enable Duo, with a GIF) and reports the result, instead
  of appearing to do nothing.
- **Revoking root is noticed.** If you turn Superuser off later, the app re-checks when you open it and the
  row returns to "Root not granted" (in red) with the guide again.
- **A report needs a description and evidence before it can be sent.** Send and Export stay off until you
  type what went wrong and there is at least one source of evidence: root, Shizuku, or the module's own log
  (the case where logs already share fine without root).
- **A cleaner screen.** The internal status line and the long explanatory paragraphs are gone; every row
  now has a one-line description.

# Duo Status Bar 1.4.2-beta.1 — pre-release

Fixes the "most reports arrive empty" problem and finishes the tap actions.

- **Bug reports no longer depend on root.** The old capture ran a single `su` command and, when the phone
  would not run it (no `su` on the app's PATH, or SELinux denying the app — the common locked-bootloader
  case), the report came back as a few hundred bytes. The capture now tries, in order: **root** (only
  after you tap **Allow root access**), **Shizuku / Sui**, the module's own log, and the device details
  that always work. A report is never empty, and it names how the phone is rooted
  (`rootKind=magisk` / `kernelsu` / `kernelsu_lkm` / `apatch` / `sui` / `shizuku` / `unknown` / `none`).
- **Root is asked for, not taken.** A new **Allow root access** button in About runs one root check and
  remembers the answer, so no root prompt appears uninvited. With neither root nor Shizuku, reports still
  carry the module log and the device facts.
- **Shizuku support for the log.** When Shizuku (or Sui) is running, reports include the system log through
  it — useful on a phone that is rooted but where the app cannot run `su` directly.
- **Privacy kept.** Only this module's own log lines are captured (`-s DuoSB` / `duostatusbar`); a full
  logcat is never read, so no other app's data can end up in a report.
- **Tap actions finished.** More actions on the battery icon — **Bluetooth** and **Mobile data** work
  right away. **Do Not Disturb**, **Airplane mode** and **Auto rotate** also work once the matching Auto
  Expand update is installed: Duo always offered these keys, but Auto Expand's receiver ignored them, so
  they did nothing. Tap actions still need the Auto Expand app, and Duo now says so plainly when it is
  missing.

# Duo Status Bar 1.4.1-beta.3 — pre-release

Diagnostics only.

- **Reports no longer need root.** The module keeps its own recent log and sends it with the diagnostic
  dump, so a device where the app cannot run `su` (locked-bootloader root) still sends its log. This is
  what "root log collection failed: Cannot run program su" was losing.
- **The shade header's layout is snapshotted while the shade is open**, so the report still carries it
  after the shade closes — the evidence the shade-position report needs.
- The shade/keyguard window and shade-header trees are included, and "Send the bug" refreshes the dump
  at that moment instead of using the boot-time one.

# Duo Status Bar 1.4.1-beta.2 — pre-release

Diagnostics only — no change to how the bar draws. It records the two things the current reports could
not, so the next report can be fixed instead of guessed:

- **The shade's own view tree** is now included (the shade is a separate window the report never
  reached), which is what the "element jumps to the left when Quick Settings expands" report needs.
- **A line when the ROM re-shows the stock battery** (e.g. during an OEM charging animation), which is
  what the "stock battery appears over Duo while charging" report needs.
- **"Send the bug" refreshes the dump at that moment**, not the one captured at boot.

# Duo Status Bar 1.4.1-beta.1 — pre-release

Straight from user logs. A beta: it goes through the pre-release channel only.

- **The moon shows only for Do Not Disturb.** A phone put on **silent** (but not DND) used to draw the
  DND crescent inside the ring, because silent was being treated as Do Not Disturb. Silent and DND are
  now separate, so the moon means DND.
- **The simple drawing's ring now matches the smooth one.** With smooth graphics off, the ring's track
  was drawn as a full circle and passed under the number; it now leaves the same top gap the animated
  element does.
- **The percentage is centred.** The number sat a few pixels left of the ring's centre in the smooth
  drawing; it is centred now, matching the simple drawing.
- **Edge spacing works where the element overlays the battery** (HyperOS 3 and similar). On those ROMs
  the setting was ignored and the element stayed hard against the right edge; it now moves.
- Corrected the earlier wording: a new ROM is a **data-file** change, but the file still ships inside the
  APK, so an app update is needed — there is no remote configuration channel.

# Duo Status Bar 1.4.0 — stable

This release makes Duo work on more phones, adds in-app updating, and makes problems easier to fix.

- **More custom ROMs work.** The status-bar icon area is found by what it contains, not only by names
  we already knew, so phones we have never measured are more likely to work.
- **Samsung One UI 8 and similar ROMs are fixed.** On those, System UI could not see the app's settings,
  so Duo loaded but stayed off. There is now a second channel between the app and the module.
- **Updates are easy.** A notification pops up for a new version, and you can download and install it
  straight from the notification or the app.
- **A red card when something is wrong**, with Restart System UI, Reboot, and a one-tap bug report.
- **The simple drawing shows the Wi-Fi icon** (it was missing before).
- From the community contribution: separate portrait and landscape settings, a raised battery
  percentage for punch-hole cameras, and separate Airplane / Do Not Disturb controls.
- A simpler README and small fixes.

# Duo Status Bar 1.4.0-beta.1 — pre-release

This beta is mostly about supporting more phones and making problems easier to fix.

- **More custom ROMs work.** The status-bar icon area is now found by what it contains, not only by
  names we already knew, so phones we have never measured are more likely to work.
- **A new ROM profile is a data file** (`rom-profiles.json`), so adding or correcting one needs no code
  change. The file still ships inside the APK, so users install an update to receive it — there is no
  remote configuration channel.
- **Better reports.** Phones with locked-bootloader root can now send their device details and logs,
  and every report shows the device's SELinux state and ABI.
- **From the community contribution:** separate portrait and landscape settings, a raised battery
  percentage for punch-hole cameras, and separate Airplane / Do Not Disturb controls.
- **Fixed:** the simple drawing (stage 1) did not show the Wi-Fi icon. The app now also explains and
  clears a leftover adb override that pins the simple drawing.
- A simpler README and small fixes.

# Duo Status Bar 1.3.2-beta.2 — pre-release

Adds a fix from the **Samsung Galaxy S25 Ultra (SM-S948N)** report and a new way to reach the developer.

- **Samsung One UI 8 (Android 16, flagship "IndicatorGarden") now draws.** The report showed the module
  attached but the element measured **0×0** on the main bar: One UI 8 leaves the AOSP `system_icons` in the
  tree as a dead stub (its icons measure 0×0) while the real cluster is a `CombinedStatusView` drawn
  beside it. Duo now detects that shape, anchors the element over the real `CombinedStatusView`, and hides
  it — so the ring appears instead of an empty gap. Gated to that exact layout, so other ROMs are
  untouched. *(Unverified on-device — a fresh dump from the S25U is welcome.)*
- **Contact the developer.** A new button under **Send the bug to the developer** opens Telegram
  (`@kvmy1`) directly, for questions that are not bug reports.

# Duo Status Bar 1.3.2-beta.1 — pre-release

Fixes straight from the 30 Sep logs: a **realme/ColorOS (RMX5200)** report where the module loaded but
resolved to `stage 0`, posted **no status** and never drew — and a **Samsung One UI** report that was
healthy, which proved the element itself was fine and pinned the failure to the app↔module channel.

- **Restart System UI now actually works on every ROM.** It only ever worked when the module was already
  enabled and listening. The broadcast receiver was registered *after* the enable gate, so on a ROM where
  the module read itself as "off" (ColorOS/realme) nobody received the tap and the button was dead — and
  the app's root/Shizuku fallback was skipped whenever the module had loaded at least once. The receiver
  is now installed *before* the gate, and the app waits for a fresh module-load stamp before falling back
  to root, then Shizuku.
- **The module no longer mistakes an unreadable provider for "off".** On ColorOS/realme the first settings
  read can land before the app process is ready; the module used to take the default (`enabled=false`) and
  give up as `stage 0`. It now recognises "provider unreachable" separately from "the user switched it
  off", retries the read a few times, and re-reads on every app change — so enabling it live works.
- **The next report is conclusive.** The diagnostic dump now records where the stage came from
  (`adb-override` / `app-settings` / `default (provider unreachable)`) and whether the settings provider
  answered, so a `stage=0` report can be told apart from a broken channel without another round-trip.
- **Android 13 (API 33) is now the floor** (was 14). Best-effort: all newer APIs are guarded and the
  AOSP probes exist on 13, but the OEM adapters were measured on 14+, so a 13 report is welcome.

# Duo Status Bar 1.3.1 — stable

A code-health release: **no behavior change**. The internals were split into focused files and the
ROM-facing surface is now documented and defended:

- `DuoMapping` → `RingGeometry` / `SignalMapping` / `Colors`.
- `SystemReaders` → `WifiReader` / `CellReader` / `DeviceStateReader`.
- `DuoHook` → `AppContextResolver` / `HookReporter` / `ProcessState`.
- `DuoIconHost` → `ContainerFinder`; the settings screen → per-section composables + `LogReporter`.
- Duplicated resource-id lookups route through one `RomResources.id` helper.
- New `docs/rom-contract.md` records exactly what must not change (probes, reflection targets, hide
  semantics, log strings) and the diagnostic-diff procedure used to prove a refactor is behavior-freezing.

Every step was verified against the on-device diagnostic dump (identical) plus the unit tests, lint and CI
guards. Nothing about how the element attaches, hides icons or draws changed.

# Duo Status Bar 1.3.0 — stable

The 1.3 line graduates from beta. Everything below shipped through the betas and is now the stable
release: cross-ROM injection fixes (HyperOS, ColorOS, Samsung, Android 17 Compose), the **Vector /
late-injection** load fix (no more false "module not loaded"), accurate **Wi-Fi bars** (stock 4-bar scale
mapped to Duo's three), **choose your SIM** on dual-SIM phones, a tidied **settings screen with a
Customize section**, one-tap **bug reporting** (with a description field, plus **Export log to a file**),
a fixed **launcher icon**, and slower README GIFs.

## What's new in v1.3.0-beta.4 — pre-release

### Dual SIM and signal accuracy (from issue #10 and user reports)

- **Dual SIM: choose the line.** On a dual-SIM phone a new **Cellular line** setting picks which line the
  four spheres follow — Automatic (the data line), SIM 1 or SIM 2. Levels are read per subscription, and a
  single-SIM phone is unaffected.
- **A nicer screen.** The settings got a header with the app icon, rounded cards, and a cleaner layout.
- **Bug reporting, simplified.** "Refresh status", "Share status" and "Save status to a file" are gone.
  The user types **what went wrong**, then **Send the bug to the developer**: it packs the description, the
  module status and the root logs into one file and uploads it. There is also **Export log to a file**,
  which saves the same report anywhere with the system picker — no Telegram, no network required. The user
  does not need Telegram installed; the app uploads over HTTPS.
- **No bot token in the APK.** Uploads go through a tiny Cloudflare Worker relay (`relay/`) that holds the
  token as a server secret and enforces a real per-IP cooldown with a Durable Object. The app only knows
  the relay URL, so a published APK contains no secret. A hidden 60-second cooldown in the app plus the
  relay's own limit stop spamming. If the relay is unreachable, it falls back to the share sheet.
- **PayPal support button** with the PayPal mark.
- **Fixed launcher icon.** The adaptive-icon foreground was an old wireframe render; it is now rebuilt from
  the real Rive scene, solid and inside the launcher's safe zone (see `tools/make-launcher-icon.py`).
- **Restart System UI** now tries the module first, then root (several `pkill`/`killall` spellings), then
  Shizuku, so it works on ROMs without `su`.
- **Slower README GIFs** and regenerated from the scene.
- **Accurate Wi-Fi bars.** The Wi-Fi level now comes from the active network's own `WifiInfo` and the
  platform's RSSI-to-bars mapping instead of fixed thresholds, which showed a strong link as 2 of 3 bars.
- **Wi-Fi only when it has internet.** A connected-but-unvalidated Wi-Fi network (captive portal,
  internet-less AP) no longer shows the Wi-Fi glyph; the middle slot follows the connection that is really
  carrying data.
- **Your other icons stay.** The default now hides only the icons Duo replaces (battery, Wi-Fi,
  cellular). Alarm, Bluetooth, network speed and the rest are kept. Hiding everything is still available
  as an opt-in switch, and the detection now also matches OEM view ids/classes (e.g. ColorOS).

### Module loading (issue #9 and stale reports)

- **Late injection no longer reads as "never".** On frameworks that inject the module after System UI has
  started (e.g. Vector), `Application.onCreate` never fires for the module and the already-added status-bar
  window was never found. Duo now resolves the process context directly and adopts an existing status-bar
  window, and it records its heartbeat before the enable gate — so the About screen reflects reality.
- **Bootstrap no longer uses the system context.** The extra context resolution must not fall back to
  `ActivityThread.mSystemContext`: its package is `android` while the process uid is System UI, so every
  `ContentResolver` call (the `Settings.Global` override and the settings provider) was rejected with
  "*calling package android does not match caller's uid*" and the module resolved to `stage 0` — the icon
  did not draw. It now only accepts a context whose package is the app, or System UI's own window context.

### ROM fixes

- **HyperOS 3:** the icon-arrival hook now also covers `MiuiStatusIconContainer`, and the clock font is
  applied to the visible clock rather than a 0×0 duplicate in HyperOS's nested clock tree.

## What's new in v1.3.0-beta.3 — pre-release

These builds are **pre-release / beta**: the fixes below come straight from users' diagnostic logs on
several ROMs and are being tested in the open.

### beta.3 — Android 17 AOSP (Pixel) and safety

- **Android 17 AOSP support (Pixel).** Android 17 draws the status-bar Wi-Fi/cellular/battery cluster with
  Compose, and the old View strip (`system_icons`) is left `GONE`. The module used to inject into that dead
  container and draw nothing. It now detects the Compose icon view and draws the element in the bar,
  anchored over it. (Experimental — this is the one path with no on-device confirmation yet.)
- **Never inject into a `GONE` strip.** A GONE legacy container is skipped, so a ROM that has both the old
  and the new layouts can never end up with an invisible element again.
- **Clearer colour diagnostics.** The log now states which colour the element is drawn in and where it came
  from, so "the colour does not match the bar" can be pinned down from one report.

### beta.2 — HyperOS fix (icons hid but the ring never appeared)

- **The element no longer disappears on HyperOS 3 (Xiaomi).** The user's log showed the module attaching
  correctly and hiding the stock icons — but `DuoRiveView` measured **0×0**. HyperOS re-hosts
  `system_icons` as a `MiuiStatusBatteryContainer` that lays out only its own children, so the injected
  view got no size and drew nothing. The element now goes into the bar's plain `FrameLayout` layer and is
  anchored over the battery (which is kept laid-out, `INVISIBLE`, so the position survives a rotation).

### ROM fixes (from user logs)

- **HyperOS (Xiaomi) now actually attaches.** On HyperOS 16/17 the System UI process existed before
  LSPosed injected the module, so `Application.onCreate` never ran afterwards — the log said
  "MainHook loaded" and then nothing, and the app reported "module load: never". The module now installs
  its window hook the instant it loads and bootstraps from the already-created Application, so the
  element appears without a special build. The HyperOS adapter is now **measured** (HyperOS 3, Android
  17): `system_icons → MiuiStatusBatteryContainer`, with `system_icon_area`/`status_bar_icons` fallbacks.
- **Samsung One UI now attaches.** Same root cause: the window hook used to be installed too late, after
  One UI had already added the status-bar window, so nothing was ever found. Installing it first fixes
  it, and a failed attach now sends a full diagnostic dump instead of staying silent, so any remaining
  One UI id can be corrected from one report.
- **The Rive breaker no longer latches forever.** Two unrelated System UI restarts (the app's Restart
  button, the watchdog) could push the crash counter to 2 and the module then used the simple Canvas
  drawing *forever* — the OxygenOS 16 report (`renderer=Canvas, riveAttempts=2`). The counter now only
  stays armed while the last attempt is recent, so a genuine crash loop still stops, but a healthy device
  re-arms the animated renderer on its next start.
- **Failed ROMs leave evidence.** If the module cannot find the icon strip, it now pushes the same
  diagnostic dump (id probes + view tree) the About screen exports, so support does not need a round-trip.

### New

- **Show only Wi-Fi and 5G/4G.** A new Appearance switch keeps the middle of the ring to the Wi-Fi icon
  and the cellular 5G/4G label. When it is on, **Do Not Disturb and Airplane never replace them** — the
  slot follows the connection only.

**Tip:** changing the size still needs a restart — the **Restart System UI** button is in the app.
