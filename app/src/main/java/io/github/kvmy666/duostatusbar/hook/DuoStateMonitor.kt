package io.github.kvmy666.duostatusbar.hook

import android.animation.ValueAnimator
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.hardware.display.DisplayManager
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import io.github.kvmy666.duostatusbar.L
import io.github.kvmy666.duostatusbar.settings.DuoPrefs

/**
 * Watches the state the Duo element shows and pushes it into the drawing.
 *
 * Receiver-driven rather than polled (NFR-2): battery, power-save, airplane, rotation and screen
 * events are broadcasts; Wi-Fi and cellular are read through [SystemReaders]. Nothing polls, and no
 * hook name is guessed (FR-26); if a read fails the element keeps its last value (FR-21).
 */
internal class DuoStateMonitor(private val context: Context, private val host: DuoIconHost) {

    private var level = 100
    /** What the ring is actually drawn at; it chases [level] so the fill animates instead of jumping. */
    private var displayedLevel = 0
    private var fill: ValueAnimator? = null
    private var charging = false
    private var saver = false
    private var airplane = false
    /** FR-25: false plays the departure as the screen goes off. */
    private var visible = true
    private var dnd = false
    private var wifiLevel = 3
    private var cellLevel = 4
    /** Whether the Wi-Fi radio is on: off hands the middle slot to the cellular generation (FR-06). */
    private var wifiOn = true
    /** Whether Wi-Fi is the active data path; false means the phone is really on mobile data. */
    private var wifiActive = true
    /** Whether that active Wi-Fi path is validated (has real internet); see [SystemReaders.isWifiValidated]. */
    private var wifiValidated = true
    /** The cellular generation shown when Wi-Fi is off: "5G"/"4G"/"3G"/"2G", or empty. */
    private var networkText = ""
    private var registered = false
    /** When the last arrival fired, for the AOD burst guard. */
    private var lastRevealAt = 0L
    private val downloadDisplay = DownloadProgress.DisplaySelection()

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Keeps the "module is alive" stamp fresh while this process lives, so the app can tell a running
     * module from one that was just unscoped and left behind (System UI restarted without it). Cheap: a
     * single Settings.Global write every [HEARTBEAT_MS], no wake lock, stopped with the monitor.
     */
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            runCatching { DuoGuard(context).noteHeartbeat() }
            handler.postDelayed(this, HEARTBEAT_MS)
        }
    }

    /** True while [mobileDataObserver] is registered. */
    private var watchingMobileData = false

    /**
     * The mobile-data toggle writes `mobile_data` or `mobile_data<subscriptionId>`. Neither is a
     * broadcast the monitor already receives, and voice service stays up, so without this the 5G/4G
     * label would sit until the next unrelated refresh.
     */
    private val mobileDataObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            val key = uri?.lastPathSegment
            if (key == null || key.startsWith(CellReader.MOBILE_DATA_SETTING)) refresh()
        }
    }

    /** Removes the pending hide when the screen comes back before the departure has finished. */
    private val hideRunnable = Runnable { host.setElementsVisible(false) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            try {
                when (intent?.action) {
                    Intent.ACTION_BATTERY_CHANGED -> onBatteryChanged(intent)
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> onPowerSaveChanged()
                    Intent.ACTION_AIRPLANE_MODE_CHANGED -> onAirplaneChanged()
                    NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED,
                    AudioManager.RINGER_MODE_CHANGED_ACTION -> onDndChanged()
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> onScreenOn()
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()
                    Intent.ACTION_CONFIGURATION_CHANGED -> onRotation()
                    WifiManager.RSSI_CHANGED_ACTION,
                    WifiManager.WIFI_STATE_CHANGED_ACTION,
                    // Which network is actually carrying data can change without the Wi-Fi radio doing
                    // anything (a captive portal drops the default route to cellular), so the active
                    // path is re-read on connectivity changes too.
                    ConnectivityManager.CONNECTIVITY_ACTION,
                    ACTION_SERVICE_STATE_CHANGED,
                    // Mobile data on/off changes the connection without moving the Wi-Fi radio or the
                    // voice service state, so the generation has to be re-read on the data connection too.
                    ACTION_ANY_DATA_STATE -> refresh()
                    DownloadProgress.ACTION -> onDownload(intent)
                }
            } catch (t: Throwable) {
                L.e("receiver ${intent?.action}: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    private fun onBatteryChanged(intent: Intent) {
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val target = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, level) * 100 / scale
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
        val wasCharging = charging
        charging = plugged || status == BatteryManager.BATTERY_STATUS_CHARGING
        if (charging != wasCharging) L.i("charging -> $charging")
        setLevel(target)
    }

    /** Asks the punch-hole module for the download it is already showing, if any. */
    private fun requestDownloadState() {
        try {
            L.i("download query send target=${context.packageName}")
            context.sendBroadcast(
                Intent(DownloadProgress.ACTION_QUERY).setPackage(context.packageName)
            )
        } catch (t: Throwable) {
            L.w("download query: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun onDownload(intent: Intent?) {
        if (intent == null) return
        val next = DownloadProgress.from(intent)
        val prev = DownloadProgress.current
        DownloadProgress.current = next
        downloadDisplay.update(next.active)
        host.setDownloadTap(next.active, ::toggleDownloadDisplay)
        // One line per start/stop, not per progress tick: Punch Hole publishes many updates a second,
        // and a line each (twice, logcat plus XposedBridge) floods the log and the in-memory ring,
        // hides real diagnostics and adds jank inside SystemUI. Only the transitions matter here.
        if (prev.active != next.active) {
            L.i(
                if (next.active) "download -> on ${next.progress}% ${DownloadProgress.trace(next)}"
                else "download -> off"
            )
        }
        render()
    }

    private fun toggleDownloadDisplay() {
        if (!downloadDisplay.toggle()) return
        L.i("download display -> ${if (downloadDisplay.showingDownload) "download" else "battery"}")
        render()
    }

    private fun onPowerSaveChanged() {
        saver = SystemReaders.isPowerSaveOn(context)
        render()
    }

    private fun onAirplaneChanged() {
        airplane = SystemReaders.isAirplaneOn(context)
        // Re-read the network generation too: airplane on blanks it, airplane off restores the label
        // the slot may need if Wi-Fi is also off.
        refresh()
    }

    // FR-06: the middle slot shows the moon while DND/silent is on. Both signals are event-driven (no
    // polling): the zen filter, and the ringer dropping to silent.
    private fun onDndChanged() {
        val was = dnd
        dnd = SystemReaders.isDndOn(context)
        if (dnd != was) L.i("dnd -> $dnd (middle slot now shows the moon)")
        render()
    }

    // FR-25: reveal on every screen-on and every unlock. The ring re-fills from 0 with it, so the fill
    // animation is part of the arrival rather than a one-off at boot.
    //
    // Only when the device is actually interactive. The always-on display cycles doze -> suspend -> off
    // -> on, and every one of those fires SCREEN_ON: measured on the device, one lock/AOD cycle produced
    // ~25 arrivals, so the element re-arrived over and over instead of sitting still. isInteractive() is
    // false throughout the AOD, which is exactly the line between "the user woke the phone" and "the
    // panel blinked".
    private fun onScreenOn() {
        // The element comes back on ANY screen-on, the AOD's included: it belongs to the status bar, and
        // the AOD has one. What it must not do there is *arrive* - the AOD pulses several times a second,
        // and an arrival per pulse is the flashing this started as. So the display is restored
        // unconditionally and the animation only when the user actually woke the phone.
        handler.removeCallbacks(hideRunnable)
        host.setElementsVisible(true)
        visible = true
        // FR-25: the arrival animation is optional. With it off the element simply appears; the fill
        // still catches up below.
        if (host.arrivalEnabled && isInteractive() && revealAllowed()) {
            host.revealAll(host.revealMs)
            restartFill()
        } else {
            setLevel(level)
        }
    }

    // FR-25: the departure plays as the screen goes, so the element leaves with the rest of the display
    // rather than blinking out with it. Tied to the SCREEN, not the lock: the lock screen is supposed to
    // show the element (FR-03b), so locking must not dismiss it. Same AOD guard, for the same reason.
    private fun onScreenOff() {
        // The departure plays as the panel goes, then the element comes off the display entirely. It has
        // to be the whole view, not just the Rive `visible` flag: on the always-on display the bar is
        // re-laid out several times a second, and an element that is only half hidden flickers with it.
        // The AOD has its own status bar, so the element has no business being there.
        handler.removeCallbacks(hideRunnable)
        if (host.departureEnabled) {
            visible = false
            render()
            handler.postDelayed(hideRunnable, DEPART_HIDE_MS)
        } else {
            // No departure animation: take the element off the display at once, and leave `visible`
            // alone so the Rive machine never plays the Depart timeline.
            host.setElementsVisible(false)
        }
    }

    /** Rotation re-inflates the strip: hide the stock views again. */
    private fun onRotation() {
        host.reapplyHiding()
        render()
    }

    fun start() {
        if (registered) return
        registered = true
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_CONFIGURATION_CHANGED)
                addAction(WifiManager.RSSI_CHANGED_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(ConnectivityManager.CONNECTIVITY_ACTION)
                addAction(ACTION_SERVICE_STATE_CHANGED)
                addAction(ACTION_ANY_DATA_STATE)
                addAction(DownloadProgress.ACTION)
            }
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            L.i("download receiver registered ctxPkg=${context.packageName} action=${DownloadProgress.ACTION}")
            // The sticky battery broadcast is not guaranteed to be delivered to the receiver at
            // registration, so read it directly: without this the element shows its default 100 % (and
            // fills the ring to match) until the next battery change, which on some devices is minutes.
            readInitialBattery()
            saver = SystemReaders.isPowerSaveOn(context)
            airplane = SystemReaders.isAirplaneOn(context)
            dnd = SystemReaders.isDndOn(context)
            watchMobileData()
            refresh()
            requestDownloadState()
            runCatching { DuoGuard(context).noteHeartbeat() }
            handler.postDelayed(heartbeatRunnable, HEARTBEAT_MS)
            L.i("state monitor up: level=$level charging=$charging saver=$saver airplane=$airplane dnd=$dnd")
        } catch (t: Throwable) {
            L.e("monitor start failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** Reads the current battery/charging state from the sticky broadcast, guarded. */
    private fun readInitialBattery() {
        try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (intent != null) onBatteryChanged(intent)
        } catch (t: Throwable) {
            L.w("initial battery read: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    fun stop() {
        if (!registered) return
        registered = false
        host.setDownloadTap(false, ::toggleDownloadDisplay)
        runCatching { fill?.cancel() }
        runCatching { handler.removeCallbacks(hideRunnable) }
        runCatching { handler.removeCallbacks(heartbeatRunnable) }
        unwatchMobileData()
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun watchMobileData() {
        if (watchingMobileData) return
        try {
            context.contentResolver.registerContentObserver(
                Settings.Global.CONTENT_URI,
                true,
                mobileDataObserver
            )
            watchingMobileData = true
        } catch (t: Throwable) {
            L.w("mobile data watch: ${t.message}")
        }
    }

    private fun unwatchMobileData() {
        if (!watchingMobileData) return
        watchingMobileData = false
        runCatching { context.contentResolver.unregisterContentObserver(mobileDataObserver) }
    }

    /**
     * Rate-limits arrivals to one per [REVEAL_DEBOUNCE_MS].
     *
     * `isInteractive()` is the principled filter, but it is not perfect: the AOD still produced ~6
     * arrivals per cycle after it (measured), because some of the doze transitions report interactive
     * for a moment. A status bar element that re-arrives six times while the phone is in a pocket is a
     * bug whatever the reason, so a burst is collapsed to a single arrival as well.
     */
    private fun revealAllowed(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRevealAt < REVEAL_DEBOUNCE_MS) return false
        lastRevealAt = now
        return true
    }

    /**
     * Whether the user is actually looking at the phone, as opposed to the always-on display.
     *
     * Read live rather than cached: the whole point is the AOD's rapid doze/suspend cycling, and a
     * cached answer would be stale exactly when it matters. Defaults to true when unreadable, so a
     * failure means "behave as before" rather than "never animate again".
     */
    private fun isInteractive(): Boolean = try {
        // The display's own state, not PowerManager.isInteractive(): during the AOD the panel pulses
        // DOZE -> ON -> DOZE and isInteractive() reports true for those moments, which is how a single
        // AOD cycle still produced 3 arrivals after the first fix. Only STATE_ON means the user is
        // actually looking at the phone.
        val displays = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
        val display = displays?.getDisplay(Display.DEFAULT_DISPLAY)
        if (display != null) {
            display.state == Display.STATE_ON
        } else {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            power?.isInteractive ?: true
        }
    } catch (t: Throwable) {
        L.w("isInteractive: ${t.javaClass.simpleName}: ${t.message}")
        true
    }

    /**
     * FR-15b: the bar's own icon colour changed, so redraw the element in the matching colour. Called
     * from the `StatusBarIconView` hook; the redraw is deduplicated downstream, so the several calls a
     * light/dark transition makes cost nothing.
     */
    fun onBarAppearanceChanged() {
        render()
    }

    /** Re-reads Wi-Fi, cellular and the network generation, then redraws. Never throws. */
    fun refresh() {        try {
            wifiOn = SystemReaders.isWifiEnabled(context, wifiOn)
            wifiActive = SystemReaders.isWifiActive(context, wifiActive)
            wifiValidated = SystemReaders.isWifiValidated(context)
            wifiLevel = SystemReaders.wifiLevel(context, wifiLevel)
            // On a dual-SIM phone the user chooses which line the spheres follow; "auto" is the default
            // data line, which the default TelephonyManager already reports.
            val levels = SystemReaders.cellLevels(context, airplane)
            cellLevel = when {
                levels.size < 2 -> SystemReaders.cellLevel(context, airplane, cellLevel)
                host.simChoice == "sim1" -> levels.getOrElse(0) { cellLevel }
                host.simChoice == "sim2" -> levels.getOrElse(1) { levels.getOrElse(0) { cellLevel } }
                else -> SystemReaders.cellLevel(context, airplane, cellLevel)
            }
            val generation = SystemReaders.networkGeneration(context, airplane, networkText)
            if (generation != networkText) {
                L.i("network -> ${generation.ifEmpty { "off" }}")
                networkText = generation
            }
            render()
        } catch (t: Throwable) {
            L.w("refresh: ${t.message}")
        }
    }

    /**
     * Animates the ring from where it is to [target] rather than snapping (user feedback: transitions
     * should be animated). Runs from 0 on first attach, so the element fills up on the reveal.
     */
    private fun setLevel(target: Int) {
        level = target
        // The master animation switch also owns the ring fill: off means the level snaps.
        if (!host.animationsEnabled) {
            fill?.cancel()
            displayedLevel = target
            render()
            return
        }
        if (target == displayedLevel && fill?.isRunning != true) {
            render()
            return
        }
        fill?.cancel()
        fill = ValueAnimator.ofInt(displayedLevel, target).apply {
            // The fill follows the arrival speed, so "faster animations" means a faster fill too.
            duration = FILL_MS * host.revealMs / 1000L
            addUpdateListener {
                displayedLevel = it.animatedValue as Int
                render()
            }
            start()
        }
    }

    /** Draws the ring from empty again, then fills to the current level - the reveal's fill half. */
    private fun restartFill() {
        displayedLevel = 0
        setLevel(level)
    }

    /**
     * FR-15b: black or white for the element, matching the bar. A manual choice in the app wins; "auto"
     * uses the colour captured from SystemUI's own icons, falling back to the system day/night setting.
     */
    private fun fgColor(): Int {
        val color = when (host.iconColor) {
            "black" -> BarTint.BLACK
            "white" -> BarTint.WHITE
            else -> BarTint.fgColor(context)
        }
        // Log only on a real change: it names the colour and where it came from, which is the one fact a
        // "the colour does not follow the bar" report is missing.
        if (color != lastFg) {
            lastFg = color
            val name = when (color) {
                BarTint.BLACK -> "black"
                BarTint.WHITE -> "white"
                else -> "0x${color.toString(16)}"
            }
            L.i("icon colour -> $name (setting=${host.iconColor})")
        }
        return color
    }

    /** The last colour handed to the drawing, so the log line above fires only on a change. */
    private var lastFg = Int.MIN_VALUE
    private fun render() {
        // Every element the host owns - the main bar's and, on the lock screen, the keyguard bar's.
        if (host.duo == null) return
        try {
            val downloadState = downloadDisplay.apply(DownloadProgress.current)
            // Physical charging still controls the green tint. Only a visible bolt owns the
            // percentage seat and its narrower gap.
            val chargingVisual = charging && host.showChargingBolt
            val downloadPercent = downloadState.displayedPercent(chargingVisual)
            val percentSeat = host.showPercent || downloadPercent != null
            val visual = DuoMapping.visual(
                    level = displayedLevel,
                    charging = charging,
                    saver = saver,
                    showPercent = host.showPercent,
                    fgColor = fgColor(),
                    wifiLevel = wifiLevel,
                    cellLevel = cellLevel,
                    airplane = airplane,
                    dnd = dnd,
                    visible = visible,
                    wifiOn = wifiOn,
                    wifiConnected = wifiActive && wifiValidated && wifiLevel > 0,
                    networkText = networkText,
                    animateCharge = host.chargingEnabled && host.showChargingBolt,
                    showCharging = host.showChargingBolt,
                    showAirplane = host.showAirplane,
                    showDnd = host.showDnd,
                    dndDots = host.dndMode == DuoPrefs.DND_DOTS,
                    percentHeight = host.percentHeight,
                    wifiDots = host.wifiDots,
                    // The wash must use the same visible gap as the base ring. A physically charging
                    // battery still tints green when its bolt is hidden, but both strokes use the
                    // percentage geometry in that case.
                    download = downloadState.toWash(chargingVisual, percentSeat),
                    downloadPercent = downloadPercent
                )
            host.render(visual)
        } catch (t: Throwable) {
            L.e("render: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private companion object {
        const val TAG = "DuoSB"

        /** How long the ring takes to fill to a new percentage (4x slower per user feedback). */
        const val FILL_MS = 2_400L

        /** A burst of SCREEN_ON within this window is one arrival, not several. */
        const val REVEAL_DEBOUNCE_MS = 900L

        /** How long the departure gets before the element is taken off the display. */
        const val DEPART_HIDE_MS = 450L

        /** `TelephonyManager.ACTION_SERVICE_STATE_CHANGED`, spelled out: the constant is not public. */
        const val ACTION_SERVICE_STATE_CHANGED = "android.intent.action.SERVICE_STATE"

        /** `TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED`, spelled out: the constant is hidden. */
        const val ACTION_ANY_DATA_STATE = "android.intent.action.ANY_DATA_STATE"

        /** How often the module proves it is alive; the app treats a much older stamp as "gone". */
        private const val HEARTBEAT_MS = 20_000L
    }
}
