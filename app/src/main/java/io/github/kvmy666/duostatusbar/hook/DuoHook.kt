package io.github.kvmy666.duostatusbar.hook

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.kvmy666.duostatusbar.L
import io.github.kvmy666.duostatusbar.BuildConfig
import io.github.kvmy666.duostatusbar.hook.rom.RomDetection
import io.github.kvmy666.duostatusbar.hook.rom.RomResources
import io.github.kvmy666.duostatusbar.settings.DuoPrefs
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Phase 3 entry point for the SystemUI process.
 *
 * Finds the status bar the same OEM-agnostic way the Phase 0 probe did — by watching
 * `WindowManagerImpl.addView` for the window whose layout params say `TYPE_STATUS_BAR` — then hands
 * that view to [DuoIconHost] and starts [DuoStateMonitor].
 *
 * The status bar is inflated a few seconds into boot, so the attach is retried on a short schedule;
 * if it never succeeds nothing is hidden and the stock status bar is untouched (FR-21).
 */
class DuoHook(private val lp: XC_LoadPackage.LoadPackageParam) {

    private val handler = Handler(Looper.getMainLooper())
    private val attaching = AtomicBoolean(false)

    /** The status-bar touch hook is installed once per process. */
    private val touchHooked = AtomicBoolean(false)

    /**
     * A context to read the settings and register the settings receiver with. It is the [Application]
     * once one exists, and the status-bar window's own context on a ROM where the module is injected
     * after the Application was created (see [start]).
     */
    private var app: Context? = null
    private var host: DuoIconHost? = null

    /** The orientation whose settings are currently applied, so a config change can switch sets. */
    private var appliedOrientation = Configuration.ORIENTATION_UNDEFINED

    /** The next [settingsApply] came from a rotation, so the saved size for that orientation is drawn. */
    private val orientationPending = AtomicBoolean(false)
    private var monitor: DuoStateMonitor? = null
    private var statusBarRoot: View? = null

    /**
     * The keyguard/shade window (FR-03b). On the lock screen the `StatusBar` window still draws our
     * element, but the keyguard's own status bar is a *second* bar in this window and sits on top of
     * it, so its stock icons have to be hidden too or the lock screen shows both.
     */
    private var shadeRoot: View? = null

    /** The pulled-down shade's header, handed over by [hookShadeHeader]. */
    private var shadeHeader: View? = null

    /** Sends status and the one-shot diagnostic dump to the app. */
    private val reporter = HookReporter(
        { statusBarRoot },
        { host?.duo },
        // The shade/keyguard window and the shade header are separate windows the status-bar dump never
        // reaches; their tree is what shows where the shade element actually sits (Issue #1).
        { listOf("shade window" to shadeRoot, "shade header" to shadeHeader) }
    )

    /** The ROM adapter, used only to resolve resource ids against SystemUI's package. */
    private val rom = RomDetection.forThisRom(
        Build.MANUFACTURER.orEmpty(),
        Build.BRAND.orEmpty(),
        Build.PRODUCT.orEmpty(),
        Build.DISPLAY.orEmpty()
    )

    /**
     * The debounced "settings changed" apply. A drag on the app's size/position slider broadcasts on
     * every tick; running the whole re-read for each one puts a burst of binder calls on System UI's
     * main thread. Waiting for the burst to settle keeps the bar responsive and, more importantly,
     * keeps the watchdog from restarting System UI.
     */
    private val settingsApply = Runnable {
        val ctx = app ?: return@Runnable
        L.guard("DuoHook settings changed") {
            val applySavedSize = orientationPending.getAndSet(false)
            val stage = DuoGuard(ctx).stage()
            val settings = host?.refreshSettings(applySavedSize)
            when {
                stage == DuoGuard.OFF -> host?.teardown()
                host?.duo == null -> scheduleAttach(attempt = 0)
                else -> monitor?.refresh()
            }
            reporter.report(ctx, stage, settings)
        }
    }

    fun install() {
        L.guard("DuoHook install") {
            // LSPosed calls handleLoadPackage once per package in scope; on HyperOS System UI is seen
            // twice (`com.android.systemui` and `system`). Hooking everything twice would double every
            // callback, so only the first load in the process does anything.
            if (!ProcessState.installed.compareAndSet(false, true)) {
                L.i("already installed in this process - skipping duplicate load of ${lp.packageName}")
                return@guard
            }
            L.i("=== Duo Status Bar ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE}) ===")
            // The window hook goes in *now*, not from Application.onCreate. On HyperOS 16 and One UI the
            // Application is created before LSPosed injects the module, so an Application.onCreate hook
            // fires too late (or never) and the status-bar window - already added - was never seen. The
            // users' logs show exactly that: "MainHook loaded" then silence, and the element never drew.
            // Watching addView from the first instant of the process catches the window on every ROM.
            hookWindowManagerAddView()
            hookApplication()
            // If the Application already exists (injected late), start now; otherwise Application.onCreate
            // calls [start] the moment it fires, and [bootstrap] retries in case neither is ready yet.
            adoptExistingStatusBarWindow()
            startWithCurrentApplication()
            bootstrap()
        }
    }

    /** The single host for this process, created on first use. */
    private fun ensureHost(ctx: Context): DuoIconHost {
        host?.let { return it }
        return DuoIconHost(ctx, onClockFound = { clock -> hookClockTint(clock) })
            .also { host = it }
    }

    /**
     * Bootstraps from the Application that is already alive, if there is one.
     *
     * This is the other half of the early-attach fix: when the module is injected after the Application
     * was created, `Application.onCreate` never runs again, so the heartbeat, the settings receiver and
     * the extra hooks would never be installed. `AndroidAppHelper.currentApplication()` is the Xposed
     * API for exactly this case; it is null when a normal onCreate is still coming.
     */
    private fun startWithCurrentApplication() {
        L.guard("DuoHook bootstrap") {
            val ctx = AppContextResolver.resolveAppContext()
            if (ctx != null) {
                L.i("Application already created at injection - starting now")
                start(ctx)
            }
        }
    }

    /**
     * The Application may only be created a moment after the module loads, so a failed resolve is retried
     * briefly instead of giving up - the alternative is the "MainHook loaded" then silence that made the
     * app report the module as never injected.
     */
    private fun bootstrap(attempt: Int = 0) {
        if (ProcessState.started.get()) return
        val ctx = AppContextResolver.resolveAppContext() ?: statusBarRoot?.context
        if (ctx != null) {
            start(ctx)
            return
        }
        if (attempt >= MAX_BOOTSTRAP_ATTEMPTS) {
            L.w("no Application context after $MAX_BOOTSTRAP_ATTEMPTS attempts - waiting for the status-bar window")
            return
        }
        handler.postDelayed({
            L.guard("DuoHook bootstrap retry") { bootstrap(attempt + 1) }
        }, BOOTSTRAP_RETRY_MS)
    }

    /**
     * Adopts a status-bar window that was added before the module was injected.
     *
     * When injection is late, `WindowManagerImpl.addView` already fired and the addView hook will never see
     * the window, so it is read back from `WindowManagerGlobal.mViews` - the process's own list of added
     * windows - by its layout-params type.
     */
    private fun adoptExistingStatusBarWindow() {
        if (statusBarRoot != null) return
        L.guard("DuoHook existing window") {
            val found = AppContextResolver.findExistingStatusBarWindow() ?: return@guard
            statusBarRoot = found
            L.i("existing status bar window found: ${found.javaClass.name}")
            scheduleAttach(attempt = 0)
            // Now that a SystemUI-owned context exists, the bootstrap can succeed even when the
            // ActivityThread lookups did not.
            bootstrap()
        }
    }

    /**
     * Everything that needs a live context. Runs once per process, from whichever of
     * [startWithCurrentApplication] or `Application.onCreate` happens first.
     */
    private fun start(ctx: Context) {
        if (!ProcessState.started.compareAndSet(false, true)) return
        app = ctx
        L.guard("DuoHook start") {
            // The settings/restart receiver goes in *before* the gate. It is the app's only way to reach a
            // module that read itself as "off": "Restart System UI" and a live enable both arrive here.
            // Registering it after the gate (as before) left the button dead on every ROM where the stage
            // resolved to 0 - exactly the ColorOS/realme report, where the provider was briefly unreachable
            // at boot and the module then never listened again. The receiver changes nothing while the
            // module is off; it only reacts to our own custom actions.
            hookSettingsChanges(ctx)
            enable(ctx, attempt = 0)
        }
    }

    /**
     * Resolves the stage and, when the module is on, hooks and attaches. The stage is read - and, when the
     * provider could not be reached, re-read - because "unreadable" is not "the user turned it off": on
     * ColorOS/realme the first read at boot can fail while the app process is still coming up, and
     * treating that as "off" is what made the element silently never appear (stage 0, no report).
     */
    private fun enable(ctx: Context, attempt: Int) {
        L.guard("DuoHook enable #$attempt") {
            val guard = DuoGuard(ctx)
            val stage = guard.stage()
            if (attempt == 0) {
                // Heartbeat before the gate: it is how the app tells "LSPosed never injected the module"
                // apart from "the module ran but is switched off". Two signals: a Settings.Global stamp,
                // and a provider report. The provider needs no permission, so the About screen cannot show
                // a false "never" on a ROM that denies SystemUI WRITE_SECURE_SETTINGS.
                L.guard("DuoHook heartbeat") {
                    guard.noteLoaded()
                    DuoSettingsClient.report(ctx, "loaded · stage=$stage")
                    DuoSettingsClient.reportFallback(ctx, "")
                }
            }
            if (stage == DuoGuard.OFF) {
                // Retry only while the provider itself was unreachable; a real "off" answer is final.
                // An unreachable read looks like "off", so it must not be treated as the other
                // orientation being off too — that would skip the hooks and never retry.
                if (DuoSettingsClient.providerUnreachable && attempt < MAX_STAGE_RETRIES) {
                    L.w("settings provider unreachable (attempt ${attempt + 1}/$MAX_STAGE_RETRIES) - retrying")
                    handler.postDelayed({
                        L.guard("DuoHook enable retry") { enable(ctx, attempt + 1) }
                    }, STAGE_RETRY_MS)
                    return@guard
                }
                // Off in *both* orientations leaves the process untouched, the same as a single off switch.
                // Off in only this orientation still installs the hooks: rotating has to be able to attach
                // the other set without a System UI restart.
                if (!DuoSettingsClient.otherOrientationEnabled(ctx)) {
                    L.i("gated off - nothing hooked. Enable with: ${guard.enableHint}, then restart SystemUI")
                    return@guard
                }
            }
            L.i("application ready: ${ctx.packageName} (stage $stage)")
            ensureHost(ctx)
            hookShadeHeader()
            hookStatusIconContainer()
            hookBarAppearance()
            // The settings receiver is already registered in start(), before this gate, so a restart
            // request still arrives when the module reads as off. Orientation is installed here, once
            // we know a rotation might need to attach the other set.
            hookOrientation()
            // The window may already have been captured by the early addView hook; attach now.
            if (stage == DuoGuard.OFF) {
                L.i("this orientation is off - waiting to apply the other orientation")
            } else {
                statusBarRoot?.let { scheduleAttach(attempt = 0) }
            }
        }
    }

    private fun hookApplication() {
        L.guard("DuoHook hook Application") {
            val callback = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    L.guard("DuoHook Application.onCreate") {
                        start(param.thisObject as Application)
                    }
                }
            }
            // The base class covers every ROM whose Application calls super.onCreate(). The concrete
            // SystemUI Applications are hooked too, for GSI/Vector ROMs (issue #9, PR #8) where the
            // framework can bypass the base `android.app.Application.onCreate` lifecycle. Each name is
            // optional - a ROM that does not have it is simply skipped - and `start` runs once regardless
            // of how many fire (it is guarded by a process-wide flag).
            for (name in listOf(
                "android.app.Application",
                "com.android.systemui.SystemUIApplication",
                "com.android.systemui.MiuiSystemUIApplication"
            )) {
                try {
                    XposedHelpers.findAndHookMethod(name, lp.classLoader, "onCreate", callback)
                    L.i("Application.onCreate hook installed on $name")
                } catch (_: Throwable) {
                    // Not present on this ROM.
                }
            }
        }
    }

    /**
     * The app's half of the channel. When the settings screen writes something it broadcasts, and this
     * re-reads and applies it live: size and offset change without re-injecting anything, switching off puts
     * the stock icons back exactly as they were, and switching on re-attaches.
     */
    private fun hookSettingsChanges(ctx: Context) {
        L.guard("DuoHook settings receiver") {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    // The size only takes effect on a fresh start, so the app asks for one here. This
                    // module is the only side that can do it: it lives inside System UI, and killing its
                    // own process makes Android bring System UI straight back.
                    if (intent?.action == DuoPrefs.ACTION_RESTART_SYSTEMUI) {
                        L.i("restart requested by the app - restarting System UI")
                        handler.postDelayed({
                            try {
                                android.os.Process.killProcess(android.os.Process.myPid())
                            } catch (t: Throwable) {
                                L.w("restart failed: ${t.javaClass.simpleName}: ${t.message}")
                            }
                        }, RESTART_DELAY_MS)
                        return
                    }
                    if (intent?.action == DuoPrefs.ACTION_DIAGNOSTICS_REQUEST) {
                        // The app is building a bug report: capture the bar now (the shade may be open),
                        // so the dump reflects the reported state instead of the boot-time one.
                        val ctxNow = app
                        if (ctxNow != null) {
                            reporter.reportDiagnostics(
                                ctxNow,
                                DuoGuard(ctxNow).stage(),
                                DuoSettingsClient.read(ctxNow),
                                host?.duo
                            )
                            L.i("diagnostic dump refreshed on app request")
                        }
                        return
                    }
                    // Coalesce a burst of changes (a slider drag broadcasts on every tick) into one
                    // apply. Each apply does synchronous provider reads on System UI's main thread, so
                    // without this a drag could block it and get System UI restarted by the watchdog.
                    handler.removeCallbacks(settingsApply)
                    handler.postDelayed(settingsApply, SETTINGS_DEBOUNCE_MS)
                }
            }
            ctx.registerReceiver(
                receiver,
                IntentFilter().apply {
                    addAction(DuoSettingsClient.ACTION_SETTINGS_CHANGED)
                    addAction(DuoPrefs.ACTION_RESTART_SYSTEMUI)
                },
                Context.RECEIVER_EXPORTED
            )
            L.i("listening for app settings changes")
        }
    }

    /**
     * Rotation swaps which stored set is drawn.
     *
     * The status-bar window on this ROM does not get a configuration callback whose orientation
     * actually changes, so watching only that left the previous set on screen until some other
     * settings write happened to re-read. The display's rotation is what the phone really did.
     */
    private fun hookOrientation() {
        val ctx = app ?: return
        appliedOrientation = orientationFrom(ctx)
        L.guard("DuoHook orientation") {
            ctx.registerComponentCallbacks(object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    noteCurrentOrientation(ctx)
                }

                override fun onLowMemory() {}
            })
            val displays = ctx.getSystemService(DisplayManager::class.java)
            displays?.registerDisplayListener(object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}

                override fun onDisplayRemoved(displayId: Int) {}

                override fun onDisplayChanged(displayId: Int) {
                    noteCurrentOrientation(ctx)
                }
            }, handler)
            L.i("orientation listener installed")
        }
    }

    /**
     * The orientation to draw. Resources and the display can update a moment apart; a display that
     * has already left the set we are drawing wins, otherwise the configuration does, which is the
     * same value a later settings read uses.
     */
    private fun noteCurrentOrientation(ctx: Context) {
        val configured = ctx.resources.configuration.orientation
        val fromDisplay = orientationFromRotation(displayRotation(ctx))
        if (fromDisplay != Configuration.ORIENTATION_UNDEFINED && fromDisplay != appliedOrientation) {
            noteOrientation(fromDisplay)
            return
        }
        noteOrientation(configured)
    }

    /** What is on screen right now, for the initial value so the first layout does not re-apply. */
    private fun orientationFrom(ctx: Context): Int {
        val fromDisplay = orientationFromRotation(displayRotation(ctx))
        if (fromDisplay != Configuration.ORIENTATION_UNDEFINED) return fromDisplay
        return ctx.resources.configuration.orientation
    }

    private fun displayRotation(ctx: Context): Int? = try {
        ctx.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.rotation
    } catch (_: Throwable) {
        null
    }

    private fun orientationFromRotation(rotation: Int?): Int = when (rotation) {
        Surface.ROTATION_90, Surface.ROTATION_270 -> Configuration.ORIENTATION_LANDSCAPE
        Surface.ROTATION_0, Surface.ROTATION_180 -> Configuration.ORIENTATION_PORTRAIT
        else -> Configuration.ORIENTATION_UNDEFINED
    }

    /** Applies [next] when it is a real orientation and not the one already drawn. */
    private fun noteOrientation(next: Int) {
        if (next != Configuration.ORIENTATION_PORTRAIT && next != Configuration.ORIENTATION_LANDSCAPE) return
        if (next == appliedOrientation) return
        appliedOrientation = next
        DuoSettingsClient.useOrientation(next)
        orientationPending.set(true)
        L.i(
            "orientation is now " +
                "${if (next == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"}" +
                " - applying that set"
        )
        handler.removeCallbacks(settingsApply)
        handler.post(settingsApply)
    }

    private fun hookWindowManagerAddView() {
        val callback = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                L.guard("DuoHook addView") {
                    val view = param.args.getOrNull(0) as? View ?: return
                    val layoutParams = view.layoutParams ?: return
                    when (XposedHelpers.getIntField(layoutParams, "type")) {
                        AppContextResolver.TYPE_STATUS_BAR -> {
                            if (statusBarRoot != null) return
                            statusBarRoot = view
                            L.i("status bar window found: ${view.javaClass.name}")
                            scheduleAttach(attempt = 0)
                        }
                        AppContextResolver.TYPE_NOTIFICATION_SHADE -> {
                            if (shadeRoot != null) return
                            shadeRoot = view
                            L.i("keyguard/shade window found: ${view.javaClass.name} (FR-03b)")
                        }
                    }
                }
            }
        }
        L.guard("DuoHook hook addView") {
            XposedHelpers.findAndHookMethod(
                "android.view.WindowManagerImpl", lp.classLoader, "addView",
                View::class.java, ViewGroup.LayoutParams::class.java, callback
            )
        }
    }

    /** The bar inflates its children over a few seconds; retry until the icon strip exists. */
    private fun scheduleAttach(attempt: Int) {
        if (attempt > MAX_ATTEMPTS) {
            L.i("giving up after $MAX_ATTEMPTS attempts - status bar left untouched")
            // A ROM the module could not attach on must still leave evidence: send the same diagnostic
            // dump as a successful attach, so a report names the ids/probes that were tried. Without
            // this, the Samsung One UI user's report was just "loaded · stage=2" with nothing to act on.
            val ctx = app ?: statusBarRoot?.context
            if (ctx != null) {
                L.guard("DuoHook attach failed report") {
                    DuoSettingsClient.report(ctx, "attach failed after $MAX_ATTEMPTS attempts - see the diagnostic dump")
                    reporter.reportDiagnostics(ctx, DuoGuard(ctx).stage(), null, host?.duo)
                }
            }
            return
        }
        handler.postDelayed({
            L.guard("DuoHook attach #$attempt") {
                val root = statusBarRoot ?: return@guard
                // Prefer the Application, but fall back to the bar window's own context: on a ROM where
                // the module is injected late the Application may never have been captured.
                val ctx = app ?: root.context
                val attached = ensureHost(ctx).attach(root)
                if (attached) {
                    if (monitor == null) {
                        monitor = DuoStateMonitor(ctx, host!!).also { it.start() }
                        // Keep the state fresh without polling: a cheap re-read on every layout pass.
                        attachLayoutListener(root)
                    }
                    if (!attaching.compareAndSet(false, true)) return@guard
                    // The keyguard and the shade header may already be on screen (the element attaches
                    // at boot, they come later, but a re-attach after rotation can land either way).
                    shadeRoot?.let { shade -> attachExtraBars(shade) }
                    hookStatusBarTouch(root)
                    L.i("Duo attached on attempt $attempt")
                    reporter.report(ctx, DuoGuard(ctx).stage(), null)
                } else {
                    scheduleAttach(attempt + 1)
                }
            }
        }, if (attempt == 0) FIRST_DELAY_MS else RETRY_MS)
    }

    /**
     * FR-05/18: drives the element's own tap gestures from the status bar's touch stream.
     *
     * The status bar consumes touches before they reach the injected element view, so its OnTouch
     * listener never fired and the element's configured actions did nothing - only Auto Expand's edge
     * zones reacted. This hooks `dispatchTouchEvent` on the bar (the same layer Auto Expand uses) and
     * lets [DuoIconHost.handleElementTouch] claim only the touches that land on the element. Auto
     * Expand's zones yield to those touches, so one tap means one action.
     *
     * The runtime class is checked for a *declared* override: hooking an inherited
     * `ViewGroup.dispatchTouchEvent` would intercept every touch in the process.
     */
    private fun hookStatusBarTouch(root: View) {
        if (!touchHooked.compareAndSet(false, true)) return
        L.guard("DuoHook status bar touch") {
            // `dispatchTouchEvent` is inherited from ViewGroup, so this hooks the base method and the
            // identity guard below keeps it to the status-bar window only (hooking without the guard
            // would run for every touch in the process).
            val method = try {
                XposedHelpers.findMethodExact(
                    root.javaClass, "dispatchTouchEvent", android.view.MotionEvent::class.java
                )
            } catch (t: Throwable) {
                L.w("no dispatchTouchEvent on ${root.javaClass.simpleName}: ${t.message}")
                return@guard
            }
            XposedBridge.hookMethod(method, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.thisObject !== root) return
                    try {
                        val event = param.args.getOrNull(0) as? android.view.MotionEvent ?: return
                        host?.handleElementTouch(event)
                    } catch (t: Throwable) {
                        L.w("element touch: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            })
            L.i("status bar touch hook installed (${root.javaClass.simpleName}) - FR-05/18")
        }
    }

    private fun attachLayoutListener(root: View) {
        L.guard("DuoHook layout listener") {
            root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                L.guard("DuoHook onLayout") {
                    // Hide pass only. Do NOT re-read system state here: the status bar re-lays out on
                    // every clock tick, and refreshing Wi-Fi/cell/etc plus re-rendering Rive on each one
                    // kept SystemUI awake at ~1 Hz — the battery drain. State is already broadcast-driven.
                    // Orientation is an int compare and only posts work when it actually changed.
                    // The display listener covers a rotation that has not reached this configuration yet.
                    app?.let { noteOrientation(it.resources.configuration.orientation) }
                    host?.reapplyHiding()
                }
            }
            // The keyguard's bar is inflated into the shade window when the lock screen appears, and the
            // shade window is the one that changes then - so its layout pass is the trigger for the
            // second hiding pass (FR-03b).
            val shade = shadeRoot
            shade?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                L.guard("DuoHook onShadeLayout") {
                    attachExtraBars(shade)
                    // Opening the shade re-shows the main bar's icon views, so the hide pass has to run
                    // again here - the status bar's own layout pass does not fire for a shade drag. The
                    // ROM also re-shows them *after* the drag settles, so this runs again a moment later.
                    host?.reapplyHiding()
                    handler.postDelayed({
                        L.guard("DuoHook shade settle") {
                            host?.reapplyHiding()
                            shadeHeader?.let { host?.attachShadeHeader(it) }
                            // Snapshot the header's layout while the shade is open. The app-only dump
                            // happens after the shade closed, by which point these bounds are gone
                            // (Issue #1: the element jumps when Quick Settings expands).
                            shadeHeader?.let {
                                reporter.captureExtras(Diag.treeText("shade header (as last laid out)", it))
                            }
                        }
                    }, SHADE_SETTLE_MS)
                }
            }
        }
    }

    /**
     * FR-03b: the two extra bars that carry their own icon strip, both inside the shade window. The ids
     * are read out of the device's SystemUI (`reverse/SystemUI-device.apk`): the lock screen's
     * `KeyguardStatusBarView` uses `system_icons`, and the pulled-down shade's header
     * (`combined_qs_header`) uses `shade_header_system_icons`. Both draw their own stock icons, which is
     * why the lock screen and the shade looked untouched until they were handled.
     */
    private fun attachExtraBars(shade: View) {
        val host = host ?: return
        host.attachExtra("keyguard bar", shade, "system_icons")
        // The shade header is not in this window; it arrives through hookShadeHeader. It is built early,
        // often before the main bar has attached, so this is also where a failed attempt is retried.
        shadeHeader?.let { host.attachShadeHeader(it) }
    }

    /**
     * FR-03b: the pulled-down shade's header.
     *
     * Decompiled from the device's SystemUI rather than guessed:
     * `com.android.systemui.qs.dagger.OplusQSModuleEx.providesShadeHeaderView` takes the shade window,
     * finds the `qs_header_stub` ViewStub inside it, sets it to `R.layout.combined_qs_header` and
     * inflates it - returning the header view. So the header *is* in the shade window's tree, but only
     * after the stub inflates, which is why searching for it at boot found nothing. This takes the
     * returned view, which is the only moment it is handed over directly.
     */
    /**
     * FR-03b: hides icon views as the ROM adds them.
     *
     * A hiding pass on a layout change is not enough on the shade header - the ROM repopulates its
     * `StatusIconContainer` afterwards, so the icons came back after every pass. `StatusIconContainer`
     * is the one container all three bars use for their icons, so hooking its `addView` catches every
     * icon in every bar at the moment it arrives.
     */
    private fun hookStatusIconContainer() {
        L.guard("DuoHook icon container") {
            val names = listOf(
                "com.android.systemui.statusbar.phone.StatusIconContainer",
                "com.android.systemui.statusbar.views.MiuiStatusIconContainer"
            )
            val callback = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    L.guard("DuoHook icon added") {
                        val child = param.args.firstOrNull() as? View ?: return@guard
                        host?.onStatusIconAdded(child)
                    }
                }
            }
            var hooked = 0
            for (name in names) {
                try {
                    val cls = XposedHelpers.findClass(name, lp.classLoader)
                    XposedBridge.hookAllMethods(cls, "addView", callback)
                    hooked++
                } catch (_: Throwable) {
                }
            }
            L.i("status icon container hooks installed: $hooked")
        }
    }

    /**
     * Captures the colour SystemUI tints its own icons, so the Duo element can match the bar (black on a
     * light bar, white on a dark one) - the "chameleon" behaviour across apps.
     *
     * Two independent sources are installed, because no single one exists on every ROM:
     *
     *  1. **`StatusBarIconView`** tint methods, in *both* package spellings. The class lives in
     *     `com.android.systemui.statusbar.phone` on AOSP 13+ (and the forks that track it, vivo
     *     OriginOS included), while the pre-13 spelling and some OEM forks keep
     *     `com.android.systemui.statusbar`. The old code hooked only the latter, so on any ROM using
     *     the modern package the hook silently never installed - the colour never followed the bar.
     *     Every spelling × method combination is tried separately and its result logged, so a report
     *     shows exactly which entry points exist on the device.
     *  2. **The status-bar clock's own text colour** — ROM-independent: SystemUI re-tints the clock
     *     with every bar transition, the clock is a framework `TextView` (public API, no reflection
     *     into SystemUI), and the module never hides it. A base `TextView.setTextColor` hook covers
     *     non-overriding implementations; an OEM that *overrides* the method on its clock class is
     *     caught by [hookClockTint] once the clock view has been resolved.
     *
     * When neither source ever fires, the element falls back to the system day/night setting
     * (see [BarTint]).
     */
    private fun hookBarAppearance() {
        L.guard("DuoHook bar appearance") {
            // Source 1: the icon view's tint pipeline, in every spelling the ROMs keep.
            for (className in listOf(
                "com.android.systemui.statusbar.phone.StatusBarIconView", // AOSP 13+ / modern forks
                "com.android.systemui.statusbar.StatusBarIconView"        // pre-13 / OEM legacy
            )) {
                try {
                    val cls = XposedHelpers.findClass(className, lp.classLoader)
                    var methods = 0
                    for (methodName in listOf("onDarkChanged", "setIconColor", "setColor", "updateIconColor")) {
                        try {
                            XposedBridge.hookAllMethods(cls, methodName, iconTintCallback)
                            methods++
                        } catch (_: Throwable) {
                            // Not present on this build - the next spelling/method is the point.
                        }
                    }
                    L.i("bar tint: $methods tint method(s) hooked on $className")
                } catch (_: Throwable) {
                    L.i("bar tint: $className not present on this ROM")
                }
            }
            // Source 2: the clock's text colour, through the framework class every clock builds on.
            try {
                val cls = XposedHelpers.findClass("android.widget.TextView", lp.classLoader)
                XposedBridge.hookAllMethods(cls, "setTextColor", clockTintCallback)
                L.i("bar tint: clock colour sampling installed (TextView.setTextColor)")
            } catch (t: Throwable) {
                L.w("bar tint: clock sampling unavailable: ${t.javaClass.simpleName}: ${t.message}")
            }
            L.i("bar appearance hooks installed - FR-15b")
        }
    }

    /**
     * Hooks `setTextColor` as *declared* on the clock's runtime class. Called from the host the first
     * time the clock is resolved: an OEM that overrides the method never reaches the base
     * `TextView` hook, so the override itself is hooked here. If the class does not declare it, the
     * base hook already covers the clock and this is a no-op.
     */
    private fun hookClockTint(clock: android.widget.TextView) {
        L.guard("DuoHook clock tint") {
            var hooked = 0
            for (parameterType in listOf(
                Int::class.javaPrimitiveType!!,
                android.content.res.ColorStateList::class.java
            )) {
                try {
                    val method = XposedHelpers.findMethodExact(
                        clock.javaClass, "setTextColor", parameterType
                    )
                    XposedBridge.hookMethod(method, clockTintCallback)
                    hooked++
                } catch (_: Throwable) {
                    // Not declared on this class - the base TextView hook already covers it.
                }
            }
            L.i("clock tint hook on ${clock.javaClass.name}: $hooked declared overload(s)")
        }
    }

    /** `StatusBarIconView`'s tint: resolve the colour out of the arguments and feed the debounced state. */
    private val iconTintCallback = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            try {
                // `onDarkChanged` carries a float `darkIntensity` and an int `tint`; some OEM
                // builds pass the intensity as an int too. [BarTint.pickTint] keeps only the
                // int(s) that can be a resolved colour, so an intensity is never read as one.
                val color = BarTint.pickTint(param.args)
                if (color != 0) {
                    if (BarTint.update(color)) monitor?.onBarAppearanceChanged()
                    return
                }
                // The pre-13 AOSP signature drives the icons off `darkIntensity` alone (the int
                // tint is 0/transparent and only applies inside tint areas): 1f is dark icons on
                // a light bar, 0f is light icons on a dark one. OEMs that never hand a usable
                // colour (the vivo report) still dispatch the intensity, so it is the colour here.
                val intensity = param.args.filterIsInstance<Float>().firstOrNull()
                if (intensity != null) {
                    val derived = if (intensity >= 0.5f) BarTint.BLACK else BarTint.WHITE
                    if (BarTint.update(derived)) monitor?.onBarAppearanceChanged()
                }
            } catch (t: Throwable) {
                L.w("bar tint: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /**
     * The clock's text colour. Guarded by identity: `setTextColor` runs on every TextView in
     * SystemUI, and only the status-bar clock's call carries the bar's colour. The clock is a
     * single-stable source (SystemUI sets it once per transition), so it commits directly.
     */
    private val clockTintCallback = object : XC_MethodHook() {
        override fun afterHookedMethod(param: MethodHookParam) {
            try {
                val host = host ?: return
                val view = param.thisObject as? android.widget.TextView ?: return
                if (view !== host.clockForTint()) return
                val color = when (val first = param.args.firstOrNull()) {
                    is Int -> first
                    is android.content.res.ColorStateList -> first.defaultColor
                    else -> return
                }
                if (color != 0 && BarTint.updateDirect(color)) monitor?.onBarAppearanceChanged()
            } catch (_: Throwable) {
                // Never let a colour sample disturb SystemUI.
            }
        }
    }

    private fun hookShadeHeader() {        // The controller that owns the header is the reliable hand-over: it is handed the header view
        // directly, whatever inflated it.
        L.guard("DuoHook shade header controller") {
            val cls = XposedHelpers.findClass(
                "com.android.systemui.shade.ShadeHeaderController", lp.classLoader
            )
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    L.guard("DuoHook shade header ctor") {
                        val view = param.args.firstOrNull() as? View ?: return@guard
                        L.i("shade header view: ${view.javaClass.name}")
                        shadeHeader = view
                        view.post { shadeHeader?.let { host?.attachShadeHeader(it) } }
                    }
                }
            })
        }
        L.guard("DuoHook shade header") {
            XposedHelpers.findAndHookMethod(
                "android.view.ViewStub", lp.classLoader, "inflate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        L.guard("DuoHook stub inflate") {
                            val view = param.result as? View ?: return@guard
                            val ctx = app ?: return@guard
                            val id = RomResources.id(ctx, rom, "shade_header_system_icons")
                            if (id == 0 || view.findViewById<View>(id) == null) return@guard
                            L.i("shade header inflated from a stub: ${view.javaClass.name}")
                            shadeHeader = view
                            view.post { shadeHeader?.let { host?.attachShadeHeader(it) } }
                        }
                    }
                }
            )
        }
    }

    private companion object {
        const val FIRST_DELAY_MS = 2_500L

        /** How long a late injection waits for the Application before it relies on the bar's context. */
        const val BOOTSTRAP_RETRY_MS = 500L
        const val MAX_BOOTSTRAP_ATTEMPTS = 20

        /** After a shade drag settles, the ROM re-shows its icon views once more. */
        const val SHADE_SETTLE_MS = 400L

        /** Give the restart broadcast a moment to finish before the process goes. */
        const val RESTART_DELAY_MS = 300L

        /** How long a burst of settings changes is allowed to settle before one apply runs. */
        const val SETTINGS_DEBOUNCE_MS = 250L
        const val RETRY_MS = 2_000L

        /**
         * How many times the icon strip is awaited after the status-bar window appears. Six attempts
         * (~12.5 s) covered OxygenOS, but the vivo X100s Pro report shows its SystemUI inflating the
         * strip later: the first round gave up ("attach failed after 6 attempts") and only recovered
         * because the app happened to push settings three seconds later — at a plain boot, with no app
         * running, the element would simply never appear. Twenty attempts (~40 s) covers the slow
         * inflation; each attempt is one cheap tree probe on the main handler.
         */
        const val MAX_ATTEMPTS = 20

        /**
         * How many times a boot-time stage read is retried when the settings provider is unreachable, and
         * the gap between them. Covers the app process still starting on ColorOS/realme without mistaking a
         * genuinely switched-off module (whose provider answers) for a broken one.
         */
        const val MAX_STAGE_RETRIES = 5
        const val STAGE_RETRY_MS = 1_500L
    }
}
