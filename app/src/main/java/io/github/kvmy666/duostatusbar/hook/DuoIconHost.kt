package io.github.kvmy666.duostatusbar.hook

import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import io.github.kvmy666.duostatusbar.L
import io.github.kvmy666.duostatusbar.hook.rom.RomDetection
import io.github.kvmy666.duostatusbar.hook.rom.RomProfiles
import io.github.kvmy666.duostatusbar.hook.rom.RomResources

/**
 * Puts the Duo element into the status bar and takes the stock icons out of it.
 *
 * Both targets were measured on the device in Phase 0 (`docs/devicereport-oos16.md`), not guessed:
 *
 *     EndSideContentLayout id=status_bar_end_side_content  329x90 @859,38
 *       LinearLayout id=system_icons                       329x61 @859,52  <- we insert here
 *         StatusIconContainer id=statusIcons               246x61          <- stock icons
 *         StatBatteryMeterView id=battery                   83x61 @1105,52  <- battery slot
 *
 * FR-08 wants the stock icons *really* gone, so every child of `id=system_icons` except our own view
 * is set to GONE **and** given zero width — not covered by an overlay. The views stay in the tree
 * (this ROM still references them), they simply draw nothing and occupy nothing.
 *
 * Ids are resolved against SystemUI's own resources at runtime, so a ROM that spells them differently
 * still works as long as the AOSP names are present; `system_icons` is tried first, then the
 * OxygenOS spellings.
 */
internal class DuoIconHost(private val context: Context) {

    /** Where the element view is actually added. Usually the strip; see [overlay]. */
    private var host: ViewGroup? = null
    private var root: View? = null
    private var element: DuoElement? = null

    /**
     * The inner icons, drawn by a second view of the same element when [ModuleSettings.splitIndicators]
     * is on. Null in the original layout, where [element] draws the ring and the icons together.
     */
    private var indicators: DuoElement? = null

    /**
     * The strip the stock icons live in and are hidden from. Normally the same as [host]; on a ROM whose
     * strip is a custom container that refuses foreign children (HyperOS's `MiuiStatusBatteryContainer`
     * measured the element 0x0 and nothing drew), [host] is a standard ancestor and this stays the strip.
     */
    private var hideStrip: ViewGroup? = null

    /**
     * True when the element is drawn *outside* the icon strip and positioned over the battery. HyperOS 3
     * is the measured case: `system_icons` is a `MiuiStatusBatteryContainer` that lays out only its own
     * children, so the element was silently 0x0. The element instead goes into the bar's plain
     * `FrameLayout` and is anchored to the battery, whose layout is kept (INVISIBLE) for the anchor.
     */
    private var overlay = false

    /** The battery the overlay is anchored to; null unless [overlay]. */
    private var anchorBattery: View? = null

    /**
     * True when the overlay anchor is Samsung One UI 8's `CombinedStatusView` rather than a battery. It is
     * not a child of the hidden strip, so it is hidden directly ([anchorOriginalVisibility] remembers what
     * to put back) and it has to be re-found on re-attach instead of falling back to the stub battery.
     */
    private var overlayOnCombined = false

    /** The anchor's visibility before the overlay hid it, so teardown can put the stock cluster back. */
    private var anchorOriginalVisibility: Int? = null

    /**
     * FR-03b: the status bar is not one bar. Read out of the device's SystemUI
     * (`reverse/SystemUI-device.apk`), three of them carry their own icon strip:
     *
     *     status_bar          system_icons               the home screen / in-app bar  (attached first)
     *     keyguard_status_bar system_icons               the lock screen
     *     combined_qs_header  shade_header_system_icons  the pulled-down shade's header
     *
     * The latter two live in the `NotificationShade` window and draw their own stock icons over the
     * element, which is why the lock screen and the shade looked untouched. Each gets a slot: its own
     * element and its own hiding pass, on the same rule as the main bar - never hide a strip the
     * element is not drawing in.
     */
    /** One-shot log gates, so a repeated pass cannot spam the log (see [LogOnce]). */
    private val logOnce = LogOnce()

    private inner class ExtraBar(val name: String, val center: Boolean = true) {
        var container: ViewGroup? = null

        /** What the element's size is capped by, and what it is centred in when [center]. */
        var bar: View? = null
        var element: DuoElement? = null

        /** The inner-icon view for this bar, present only while the split layout is on. */
        var indicators: DuoElement? = null

        /**
         * The slot width to draw at. It is the width pinned for the main bar at attach — one size for
         * every bar — falling back to this bar's own measurement only when the main bar captured none.
         */
        var basePx = 0
    }

    /**
     * FR-03b: called as each icon view is added to a status icon container (hooked at
     * `StatusIconContainer.addView`).
     *
     * Hiding on a layout pass is not enough on the shade header: the ROM repopulates `statusIcons`
     * *after* the pass, so the icons came straight back. This hides each one at the moment it arrives,
     * which is the only moment the ROM cannot undo.
     */
    fun onStatusIconAdded(view: View) {
        try {
            if (view === element?.ui || view === indicators?.ui ||
                extras.any { it.element?.ui === view || it.indicators?.ui === view }
            ) return
            var parent: View? = view.parent as? View
            while (parent != null) {
                if (parent === host || extras.any { it.container === parent }) {
                    val container = parent
                    logOnce.once("added:${view.javaClass.simpleName}") {
                        L.i("hiding a status icon as it arrives: ${view.javaClass.simpleName} in " +
                                "${container.javaClass.simpleName}")
                    }
                    // FR-08b: with "hide other icons" off, only the icons Duo replaces are hidden; the
                    // silent/vibrate/alarm ones are left for the user.
                    if (settings.hideOtherIcons || hider.isReplaced(view)) {
                        hider.hide(view)
                    } else {
                        logOnce.once("kept:${view.javaClass.simpleName}") {
                            L.i("keeping a status icon: ${view.javaClass.simpleName} - FR-08b")
                        }
                    }
                    return
                }
                parent = parent.parent as? View
            }
            logOnce.once("unmanaged:${view.javaClass.simpleName}") {
                L.i("status icon arrived in an unmanaged container: ${view.javaClass.simpleName} " +
                        "parent=${(view.parent as? View)?.javaClass?.simpleName}")
            }
        } catch (t: Throwable) {
            L.w("icon added: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private val extras = ArrayList<ExtraBar>()
    private val guard = DuoGuard(context)
    private val rom = RomDetection.forThisRom(
        Build.MANUFACTURER.orEmpty(),
        Build.BRAND.orEmpty(),
        Build.PRODUCT.orEmpty(),
        Build.DISPLAY.orEmpty(),
        // Measured profiles from assets may override; only `measured` ones are accepted.
        RomProfiles.load(context)
    )

    /** Resolves the strip and the element's parent view from the tree; see [ContainerFinder]. */
    private val finder = ContainerFinder(context, rom, logOnce)
    private var settings = ModuleSettings.DEFAULT

    /** Size and position maths; owns the size/slot base captured once at attach (FR-03/17). */
    private val geometry = SlotGeometry(context, rom)

    /** Hides and restores the stock views (FR-08/21); owns their remembered original state. */
    private val hider = StockIconHider()

    /** Restyles the status-bar clock and remembers its original so switching off restores it. */
    private val clock = ClockFontController(context, rom)

    /** The element's own tap gestures (FR-05/18), driven from the status-bar touch hook. */
    private val gestures = ElementGestures(context)

    val duo: DuoElement? get() = element

    /** The ring and, when split, the icon cluster, on every bar. */
    private fun allElements(): List<DuoElement> =
        listOfNotNull(element, indicators) + extras.flatMap { listOfNotNull(it.element, it.indicators) }

    /** Views this module added, so a hiding pass never treats them as stock icons. */
    private fun ourViews(): List<View> = allElements().map { it.ui }

    /** Pushes a snapshot at every element that is drawing, in every bar. */
    fun render(v: DuoVisual) {
        for (target in allElements()) {
            try {
                target.render(v)
            } catch (t: Throwable) {
                L.w("render: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /**
     * FR-03b: puts the element into one of the *other* status bars - the keyguard's, or the shade
     * header's. [stripId] is the id of that bar's icon strip, measured from the device's SystemUI:
     * `system_icons` for the keyguard bar, `shade_header_system_icons` for the shade header.
     *
     * Only ever runs after the main bar is live, on the same rule as the main bar: never hide a strip
     * the element is not drawing in. If anything fails, that bar keeps its stock icons.
     */
    fun attachExtra(name: String, root: View, stripId: String): Boolean {
        if (extras.any { it.name == name }) return true
        if (element == null) return false
        return try {
            val stage = guard.stage()
            if (stage == DuoGuard.OFF) return false
            val id = RomResources.id(context, rom, stripId)
            if (id == 0) {
                L.w("$name: no id for $stripId - leaving its stock icons")
                return false
            }
            val found = root.findViewById<View>(id)
            if (found == null) {
                logOnce.once("missing:$name") {
                    L.i("$name: $stripId not in this window yet (id=$id) - waiting for it to be inflated")
                }
                return false
            }
            val target = found as? ViewGroup ?: return false
            if (target === host) return true // same strip as the main bar: nothing extra to do
            // Centre on the *bar*, not the window it lives in: the shade window is the whole screen, so
            // centring on it put the element 1300 px down the lock screen (measured).
            val bar = finder.findBar(target) ?: target
            attachInto(name, target, bar, center = true, elementRoot = root, stage = stage, logClass = bar.javaClass.simpleName)
        } catch (t: Throwable) {
            L.e("$name attach: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * FR-03b, the shade header. It is not in the shade window's tree at all - `ShadeHeaderController`
     * builds it - so it is handed over directly by the hook instead of being searched for.
     *
     * Its icon area is the `shade_header_system_icons` frame (measured from `combined_qs_header.xml`),
     * found by walking up from the `statusIcons` container the controller itself binds to.
     */
    fun attachShadeHeader(header: View): Boolean {
        if (extras.any { it.name == "shade header" }) return true
        val area = finder.findShadeIconsArea(header)
        if (area == null) {
            logOnce.once("missing:shade header") {
                L.w("shade header: no icon area found in ${header.javaClass.simpleName}")
            }
            return false
        }
        // Centred on the header, not the icon area: the area is a 0x0 strip at the header's end
        // (measured - it is laid out later), and the header is what has a real height to cap against.
        return attachExtraView("shade header", area, header, center = false)
    }

    /** Attaches into an already-known container (the shade header's icon area). */
    private fun attachExtraView(
        name: String,
        target: ViewGroup,
        cap: View? = null,
        center: Boolean = true
    ): Boolean {
        if (extras.any { it.name == name }) return true
        if (element == null) return false
        return try {
            val stage = guard.stage()
            if (stage == DuoGuard.OFF) return false
            if (target === host) return true
            attachInto(name, target, cap, center, elementRoot = target, stage = stage, logClass = target.javaClass.simpleName)
        } catch (t: Throwable) {
            L.e("$name attach: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * The shared body of every extra-bar attach: inject the element, hide that bar's stock icons once it
     * is drawing, and drop it cleanly if it never binds.
     *
     * [elementRoot] is what the renderer's hardware-acceleration check reads (the window for the
     * keyguard bar, the icon area for the shade header); [logClass] is the class name the success line
     * reports, which differs per bar and must stay stable.
     */
    private fun attachInto(
        name: String,
        target: ViewGroup,
        cap: View?,
        center: Boolean,
        elementRoot: View,
        stage: Int,
        logClass: String
    ): Boolean {
        val slot = ExtraBar(name, center)
        val candidate = createElement(elementRoot, stage, ringPart())
        slot.container = target
        slot.bar = cap ?: target
        // One size for every bar: reuse the width pinned for the main bar at attach, so the lock screen
        // and the shade header cannot draw the ring at a different size than the home bar (reported: "the
        // icon is bigger on the lock screen"). Falls back to this bar's own measurement only when the main
        // bar never captured one. The bar-height cap in [SlotGeometry.sidePx] still applies per bar.
        slot.basePx = if (geometry.slotBasePx > 0) geometry.slotBasePx else geometry.measuredWidth(target)
        slot.element = candidate
        extras.add(slot)
        allowOverflow(target)
        val side = geometry.sidePx(target, slot.bar, slot.basePx)
        candidate.ui.layoutParams = layoutParamsFor(target, side)
        target.addView(candidate.ui)
        applyExtraLayout(slot)
        syncExtraIndicators(slot)
        candidate.onReady {
            if (slot.element !== candidate) return@onReady
            hideStock(target, candidate.ui)
            candidate.reveal(settings.revealMs)
            L.i("Duo injected into $name ($logClass, ${side}px) - FR-03b")
        }
        candidate.onFailed {
            // Optional bar: drop the element and leave its stock icons alone.
            L.w("$name element did not bind - leaving its stock icons")
            runCatching { target.removeView(candidate.ui) }
            runCatching { candidate.teardown() }
            extras.remove(slot)
        }
        return true
    }

    /**
     * The container decides the LayoutParams type: the strips are LinearLayouts, the shade header is not.
     * Height is taller than [side] so the percentage can draw above the ring without moving the ring.
     */
    /** The ring's layout: full drawing size, with the strip keeping only the edge-spacing setting. */
    private fun layoutParamsFor(container: ViewGroup, side: Int): ViewGroup.LayoutParams =
        layoutParamsFor(
            container,
            side,
            RingGeometry.elementHeightPx(side),
            edgeInsetPx(side, settings.edgePadding)
        )

    /**
     * [inset] is the start margin, zero or negative. 0 keeps [width] in the strip. A negative margin
     * hands that much back, so icons Duo does not replace (sound, vibrate, alarm) can sit closer to
     * the screen edge. The icon cluster passes the full negative width and reserves nothing.
     */
    private fun layoutParamsFor(
        container: ViewGroup,
        width: Int,
        height: Int,
        inset: Int
    ): ViewGroup.LayoutParams {
        val lp = when (container) {
            is LinearLayout -> LinearLayout.LayoutParams(width, height)
            is android.widget.FrameLayout -> android.widget.FrameLayout.LayoutParams(width, height)
            else -> ViewGroup.LayoutParams(width, height)
        }
        // Fresh params start at 0, so a zero inset is already correct. Only a flowing strip uses it.
        if (inset != 0 && container is LinearLayout && lp is ViewGroup.MarginLayoutParams) {
            if (container.layoutDirection == View.LAYOUT_DIRECTION_RTL) lp.rightMargin = inset
            else lp.leftMargin = inset
        }
        return lp
    }

    /**
     * Gives [view] its drawing size and the strip margin [inset].
     *
     * Re-assigning [View.layoutParams] requests a layout pass, and a layout pass that resizes the Rive
     * view is what used to take System UI down. The size is therefore touched only when it changed.
     * A spacing change updates the margin alone, which does not resize the drawing.
     */
    private fun assignBox(view: View, container: ViewGroup, side: Int, inset: Int) {
        val height = RingGeometry.elementHeightPx(side)
        val lp = view.layoutParams
        if (lp == null || lp.width != side || lp.height != height) {
            view.layoutParams = layoutParamsFor(container, side, height, inset)
            view.requestLayout()
            return
        }
        if (container !is LinearLayout || lp !is ViewGroup.MarginLayoutParams) return
        val rtl = container.layoutDirection == View.LAYOUT_DIRECTION_RTL
        val leading = if (rtl) lp.rightMargin else lp.leftMargin
        val trailing = if (rtl) lp.leftMargin else lp.rightMargin
        if (leading == inset && trailing == 0) return
        lp.leftMargin = if (rtl) 0 else inset
        lp.rightMargin = if (rtl) inset else 0
        view.layoutParams = lp
    }

    private fun applyExtraLayout(slot: ExtraBar) {
        val target = slot.container ?: return
        val view = slot.element?.ui ?: return
        try {
            val side = geometry.sidePx(target, slot.bar, slot.basePx)
            assignBox(view, target, side, edgeInsetPx(side, settings.edgePadding))
            place(view, target, slot.bar, side, settings.offsetX, slot.center)
        } catch (t: Throwable) {
            L.w("${slot.name} layout: ${t.message}")
        }
    }

    /** FR-16: whether the percentage should be drawn — asked by the state monitor on every render. */
    val showPercent: Boolean get() = settings.showPercent

    /** 0 is the original percentage seat; 100 is fully raised. Asked on every render, so it moves live. */
    val percentHeight: Int get() = settings.percentHeight

    /** FR-15b: "auto", "black" or "white" — asked by the state monitor on every render. */
    val iconColor: String get() = settings.iconColor

    /** Whether Airplane mode may take the middle of the ring. Asked on every render. */
    val showAirplane: Boolean get() = settings.showAirplane

    /** How Do Not Disturb is drawn: "off", "middle", or "dots". Asked on every render. */
    val dndMode: String get() = settings.dndMode

    /** Whether Do Not Disturb may take the middle of the ring. Asked on every render. */
    val showDnd: Boolean get() = settings.showDnd

    /** Which cellular line the spheres follow on a dual-SIM phone: "auto", "sim1" or "sim2". */
    val simChoice: String get() = settings.simChoice

    /** When true, airplane mode turns the spheres into Wi-Fi, hiding them if Wi-Fi is off too. */
    val wifiDots: Boolean get() = settings.wifiDots

    /**
     * FR-08/08b: hides the stock views per the user's choice — everything, or only what Duo replaces.
     * One place so the main bar, the keyguard bar and the shade header can never disagree.
     */
    private fun hideStock(container: ViewGroup, keep: View?, keepLayout: View? = null) {
        val ours = ourViews()
        if (settings.hideOtherIcons) {
            hider.hideAllExcept(container, keep, keepLayout, ours)
        } else {
            hider.hideReplaced(container, keep, keepLayout, ours)
        }
    }

    /** FR-25: how long an arrival takes, in ms — asked by the monitor when it fires one. */
    val revealMs: Int get() = settings.revealMs

    // The Animations section (FR-25). The master gates the three individual switches.
    val animationsEnabled: Boolean get() = settings.animationsEnabled
    val arrivalEnabled: Boolean get() = settings.animationsEnabled && settings.arrivalEnabled
    val departureEnabled: Boolean get() = settings.animationsEnabled && settings.departureEnabled
    val chargingEnabled: Boolean get() = settings.animationsEnabled && settings.chargingEnabled

    /** FR-15: whether the charging bolt is drawn at all. Asked by the monitor on every render. */
    val showChargingBolt: Boolean get() = settings.showChargingBolt

    /**
     * Shows or hides every element's view outright.
     *
     * The always-on display is a different thing from the lock screen, and the element was trying to be
     * both: the AOD cycles doze -> suspend -> off -> on several times a second, and each cycle re-laid
     * out the bar the element lives in, so it flickered. The AOD has its own minimal status bar, so the
     * honest answer is to take the element off the display while it is dozing rather than leave it
     * half-drawn.
     */
    fun setElementsVisible(on: Boolean) {
        for (target in allElements()) {
            try {
                target.ui.visibility = if (on) View.VISIBLE else View.GONE
                // Off screen means no drawing at all: a looping Rive idle animation would otherwise keep
                // advancing and drawing into a hidden view for the whole process lifetime (battery).
                target.setRenderActive(on)
            } catch (t: Throwable) {
                L.w("visibility: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /** FR-25: fires the arrival on every bar, so the lock screen wakes with the rest of the element. */
    fun revealAll(ms: Int) {
        for (target in allElements()) {
            try {
                target.reveal(ms)
            } catch (t: Throwable) {
                L.w("reveal: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /**
     * Re-reads the user's settings and applies what can change while running (size, offset).
     * Called on attach and whenever the app says something changed, so no restart is needed.
     */
    fun refreshSettings(applySavedSize: Boolean = false): ModuleSettings {
        val fresh = DuoSettingsClient.read(context)
        val changed = fresh != settings
        val hideModeChanged = changed && fresh.hideOtherIcons != settings.hideOtherIcons
        settings = fresh
        if (changed) {
            L.i("settings rev ${fresh.revision}: size ${fresh.sizePercent}%, offset ${fresh.offsetX}dp, " +
                    "percent=${fresh.showPercent}, percentHeight=${fresh.percentHeight}%, " +
                    "split=${fresh.splitIndicators}, icons=${fresh.indicatorsOffsetX}dp, " +
                    "edge=${fresh.edgePadding}%, " +
                    "rive=${fresh.useRive}, live=${fresh.liveApply}"
            )
            // A drag must not resize the Rive view (that restart loop is why size is restart-only).
            // Rotation is one saved size for the orientation now on screen, so it is applied here.
            if (applySavedSize) geometry.applySize(fresh.sizePercent)
            if (fresh.liveApply || applySavedSize) {
                applyLayout()
                syncIndicators()
                for (slot in extras) {
                    applyExtraLayout(slot)
                    syncExtraIndicators(slot)
                }
            } else {
                // The safe path: size and position are saved but wait for the next start. Gestures and
                // the clock's font are not geometry, so they can still change live.
                element?.ui?.let { gestures.install(it, settings.tapAction, settings.doubleTapAction, settings.longPressAction) }
                L.i("live apply off - size/position take effect after Restart System UI")
            }
            clock.apply(root, settings.systemClockFont)
            if (hideModeChanged) applyHidingMode()
        }
        return fresh
    }

    /**
     * FR-08b: re-applies hiding after the user changed whether the other icons stay visible. The stock
     * views are put back first, so an icon that should now show is not left hidden, then the new pass
     * runs; the element stays exactly where it is.
     */
    private fun applyHidingMode() {
        if (!(element?.isReady ?: false)) return
        L.i("hiding mode changed (hideOtherIcons=${settings.hideOtherIcons}) - re-applying")
        hider.restore()
        reapplyHiding()
    }

    /** Size and offset come from the settings, so reshaping the element needs no re-injection (FR-03/17). */
    private fun applyLayout() {
        val target = host ?: return
        val view = element?.ui ?: return
        try {
            val side = geometry.sidePx(target, root)
            // assignBox only rewrites layoutParams when the box actually changed. Re-assigning them
            // (even to the same numbers) requests a layout pass, and a layout pass on the Rive
            // TextureView is what took System UI down on rotation.
            assignBox(view, target, side, edgeInsetPx(side, settings.edgePadding))
            place(view, target, root, side, settings.offsetX, center = true)
            installGestures(view)
            indicators?.ui?.let { installGestures(it) }
        } catch (t: Throwable) {
            L.w("applyLayout: ${t.message}")
        }
    }

    /**
     * Moves [view] by [offsetDp] and, unless this is an overlay, centres it the way the ring is centred.
     * The ring and the icon cluster share this so a split layout cannot drift from the original one.
     */
    private fun place(
        view: View,
        container: ViewGroup,
        windowRoot: View?,
        side: Int,
        offsetDp: Int,
        center: Boolean
    ) {
        if (overlay && container === host) {
            // The parent is a plain FrameLayout that places children top-left, so the element is moved
            // onto the battery by translation. On One UI 8 the anchor is not a child of the hidden
            // strip, so this is what actually removes the stock cluster.
            hideOverlayAnchor()
            anchorOnBattery(view, container, side, offsetDp)
            return
        }
        // A LinearLayout strip applies the edge-spacing setting as a start margin (see assignBox); a
        // FrameLayout parent (the shade header's icon area) ignores margins, so it is applied as
        // translation instead. Default 100 % is zero, so an existing user sees no change.
        val flowInset = if (container is LinearLayout) 0 else edgeInsetPx(side, settings.edgePadding)
        view.translationX = offsetDp * context.resources.displayMetrics.density + flowInset
        // The strip sits low in the window, so centring on it wastes the space above. Centre the
        // ring in the whole status bar instead, which is what lets it grow to the window height.
        // The view is taller than the ring; the extra shift keeps the ring put and leaves the
        // raised percentage in the space above it.
        view.translationY = (if (center) geometry.windowCenterShiftY(container, windowRoot) else 0f) +
            RingGeometry.ringAnchorShiftY(side)
    }

    private fun installGestures(view: View) {
        gestures.install(view, settings.tapAction, settings.doubleTapAction, settings.longPressAction)
    }

    /** The part the ring's view should draw for the current settings. */
    private fun ringPart(): DuoPart = if (settings.splitIndicators) DuoPart.RING else DuoPart.ALL

    /**
     * Adds or removes the icon-cluster view on the main bar. The ring's view stays where it was;
     * only its [DuoPart] changes, so turning the mode on does not rebuild the Rive surface that
     * already survived attach.
     */
    private fun syncIndicators() {
        val ring = element ?: return
        val container = host ?: return
        ring.part = ringPart()
        if (!settings.splitIndicators) {
            drop(indicators, container)
            indicators = null
            return
        }
        val stageRoot = root ?: return
        indicators = ensureCluster(
            indicators,
            container,
            ring.ui,
            root,
            stageRoot,
            basePx = 0,
            center = true
        ) { indicators = it }
    }

    /** The same cluster, for a keyguard bar or the shade header. */
    private fun syncExtraIndicators(slot: ExtraBar) {
        val ring = slot.element ?: return
        val container = slot.container ?: return
        ring.part = ringPart()
        if (!settings.splitIndicators) {
            drop(slot.indicators, container)
            slot.indicators = null
            return
        }
        val stageRoot = slot.bar ?: container
        slot.indicators = ensureCluster(
            slot.indicators,
            container,
            ring.ui,
            slot.bar,
            stageRoot,
            slot.basePx,
            slot.center
        ) { slot.indicators = it }
    }

    /**
     * Returns the icon-cluster view for one bar, creating it the first time the split layout is on.
     * [assign] publishes it before the ready callback, so a hiding pass can already see the view.
     */
    private fun ensureCluster(
        existing: DuoElement?,
        container: ViewGroup,
        ringView: View,
        windowRoot: View?,
        stageRoot: View,
        basePx: Int,
        center: Boolean,
        assign: (DuoElement?) -> Unit
    ): DuoElement? {
        val side = geometry.sidePx(container, windowRoot, basePx)
        existing?.let { cluster ->
            place(cluster.ui, container, windowRoot, side, settings.indicatorsOffsetX, center)
            installGestures(cluster.ui)
            return cluster
        }
        val stage = guard.stage()
        if (stage == DuoGuard.OFF) return null
        val candidate = createElement(stageRoot, stage, DuoPart.INDICATORS)
        // A LinearLayout would otherwise reserve a second slot and shove the clock. The cluster keeps
        // no strip space of its own; edge spacing belongs to the ring. Overlay parents stack children,
        // so the margin is only for the strip.
        candidate.ui.layoutParams = layoutParamsFor(
            container, side, RingGeometry.elementHeightPx(side), edgeInsetPx(side, 0)
        )
        val index = container.indexOfChild(ringView)
        if (index >= 0) container.addView(candidate.ui, index + 1) else container.addView(candidate.ui)
        assign(candidate)
        allowOverflow(container)
        place(candidate.ui, container, windowRoot, side, settings.indicatorsOffsetX, center)
        installGestures(candidate.ui)
        candidate.onReady {
            candidate.reveal(settings.revealMs)
            L.i("icon cluster injected (${side}px, offset ${settings.indicatorsOffsetX}dp)")
        }
        candidate.onFailed {
            L.w("icon cluster did not bind - drawing it with Canvas")
            val canvas = try {
                DuoCanvasView(context, DuoPart.INDICATORS).also { it.start() }
            } catch (t: Throwable) {
                L.e("icon cluster fallback failed: ${t.javaClass.simpleName}: ${t.message}")
                drop(candidate, container)
                assign(null)
                return@onFailed
            }
            swap(container, candidate, canvas, ringView)
            assign(canvas)
            place(canvas.ui, container, windowRoot, side, settings.indicatorsOffsetX, center)
            installGestures(canvas.ui)
            canvas.onReady { canvas.reveal(settings.revealMs) }
        }
        return candidate
    }

    /** Replaces [from] with [to] in [container], keeping it next to the ring. */
    private fun swap(container: ViewGroup, from: DuoElement, to: DuoElement, ringView: View) {
        val index = container.indexOfChild(from.ui).takeIf { it >= 0 }
            ?: (container.indexOfChild(ringView) + 1).takeIf { it > 0 }
        try {
            from.teardown()
            container.removeView(from.ui)
        } catch (t: Throwable) {
            L.w("cluster swap remove: ${t.message}")
        }
        to.ui.layoutParams = from.ui.layoutParams
        if (index != null && index <= container.childCount) container.addView(to.ui, index)
        else container.addView(to.ui)
    }

    private fun drop(view: DuoElement?, container: ViewGroup?) {
        view ?: return
        try {
            container?.removeView(view.ui)
            view.teardown()
        } catch (_: Throwable) {
        }
    }

    /** Lets the element draw outside the 61 px icon strip, up to the status bar window's bounds. */
    private fun allowOverflow(view: View) {
        var v: View? = view
        while (v is ViewGroup) {
            v.clipChildren = false
            v.clipToPadding = false
            v = v.parent as? View
        }
    }

    /**
     * Feeds a status-bar touch to the element's gestures when it lands on the element (FR-05/18).
     *
     * Called from a `dispatchTouchEvent` hook on the status bar, above the point where the bar swallows
     * touches, because the injected view never receives them. See [ElementGestures.handle].
     */
    fun handleElementTouch(event: MotionEvent): Boolean =
        gestures.handle(event, listOfNotNull(element?.ui, indicators?.ui))

    /**
     * Finds `system_icons`, injects the Duo element, hides what it replaces. True on success.
     *
     * How far this goes is decided by the guard, not by the caller: stage 0 does nothing at all, stage 1
     * draws with plain Canvas (no native code in the path), stage 2 uses Rive when the runtime and the
     * window both allow it and falls back to Canvas when they do not. The stock icons are hidden only for
     * an element that reports itself ready, so a failure leaves the stock status bar untouched rather
     * than empty (FR-21).
     */
    fun attach(statusBarRoot: View): Boolean {
        if (element != null) return true
        return try {
            val stage = guard.stage()
            if (stage == DuoGuard.OFF) {
                logOnce.once("gate") {
                    L.i("gated off - enable with: ${guard.enableHint}")
                }
                return false
            }
            var target = finder.findStatusIconsHost(statusBarRoot)
            var composeAnchor: View? = null
            if (target == null) {
                // Android 17 AOSP draws the status bar icons with Compose: there is no View strip to
                // inject into, only a `ComposeView`. The element is drawn in the surrounding ViewGroup
                // and anchored on that Compose view, which is hidden with INVISIBLE so it keeps its place.
                val compose = finder.findComposeIconView(statusBarRoot)
                val parent = compose?.parent as? ViewGroup
                if (compose != null && parent != null) {
                    L.i("Compose status bar: no View strip; drawing in ${parent.javaClass.simpleName} " +
                            "and hiding ${compose.javaClass.simpleName}")
                    composeAnchor = compose
                    target = parent
                }
            }
            if (target == null) {
                L.w("system_icons not found - status bar left untouched")
                return false
            }
            root = statusBarRoot
            hideStrip = target
            // Pin the slot width now, while the battery view still has its real width: once the stock
            // icons are hidden it reads 0 and the fallback would change the size mid-session.
            val basePx = geometry.measuredWidth(target)
            refreshSettings()
            val candidate = createElement(statusBarRoot, stage, ringPart())
            // Capture the size once per process: live size changes are deferred to a restart (see
            // [SlotGeometry.appliedSize]) because resizing the Rive view live used to take System UI down.
            geometry.capture(settings.sizePercent, basePx)
            logOnce.once("facts") {
                DuoSbFacts.report(context, statusBarRoot, target, geometry.widthPx(target))
            }
            // The element's real parent: the strip, unless the ROM's strip is a custom container that
            // refuses foreign children. HyperOS 3's MiuiStatusBatteryContainer measured it 0x0 - the icons
            // hid and nothing drew - so there the element goes into a plain FrameLayout ancestor and is
            // anchored over the battery.
            var parent = finder.chooseElementParent(target, statusBarRoot)
            overlay = parent !== target
            // Anchor on the battery when there is one; on a Compose bar there is no battery id, so the
            // Compose icon view is the anchor instead (it keeps its layout, hidden with INVISIBLE).
            anchorBattery = if (overlay) (finder.findBattery(target) ?: composeAnchor) else null
            if (overlay && anchorBattery == null) {
                // No battery to anchor to: fall back to the strip rather than draw at a random spot.
                overlay = false
            }
            // Samsung One UI 8 (Android 16): the AOSP `system_icons` is a dead stub and the real cluster is
            // a `CombinedStatusView` drawn beside it. Injecting into the stub drew a 0x0 element and hid
            // nothing (SM-S948N). When the strip is a stub and that view exists, anchor on it and draw in
            // the bar's own FrameLayout layer. Gated on both conditions, so no other ROM reaches this path.
            val combined = finder.findCombinedStatusView(statusBarRoot)
            if (combined != null && finder.isStubStrip(target)) {
                parent = finder.findOverlayContainer(target, statusBarRoot)
                overlay = true
                overlayOnCombined = true
                anchorBattery = combined
                L.i("One UI 8 layout: strip ${target.javaClass.simpleName} is a stub - drawing in " +
                        "${parent.javaClass.simpleName} anchored over ${combined.javaClass.simpleName}")
            }
            L.i("element container: ${parent.javaClass.simpleName}" +
                    if (overlay) " (overlay on ${anchorBattery?.javaClass?.simpleName}, strip ${target.javaClass.simpleName})" else "")
            val container = if (overlay) parent else target
            val side = geometry.sidePx(target, root)
            allowOverflow(container)
            candidate.ui.layoutParams = layoutParamsFor(container, side)
            container.addView(candidate.ui)
            host = container
            element = candidate
            applyLayout()
            syncIndicators()
            clock.apply(root, settings.systemClockFont)
            // The stock icons are hidden and the first reveal fires only once the element reports itself
            // live. A Rive state machine binds *after* this method returns (it needs the view attached to a
            // window), so hiding here would cover an empty slot; Canvas reports ready immediately.
            candidate.onReady { onElementReady(candidate, target) }
            candidate.onFailed { onElementFailed(candidate, target) }
            forgetAttemptsAfterSurvival(candidate)
            true
        } catch (t: Throwable) {
            L.e("attach failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** The element is live: now it is safe to take the stock icons out and fire the reveal (FR-08/25). */
    private fun onElementReady(candidate: DuoElement, target: ViewGroup) {
        if (element !== candidate) return
        try {
            // In overlay mode the element is not a child of the strip, so nothing is "kept" there; the
            // battery keeps its layout instead so the anchor stays valid (and survives rotation).
            hideStock(
                target,
                if (overlay) null else candidate.ui,
                if (overlay) anchorBattery else null
            )
            hideOverlayAnchor()
            // Hiding may have changed the strip's layout, so the overlay anchor is re-read after it.
            applyLayout()
            candidate.reveal(settings.revealMs)
            val width = candidate.ui.layoutParams?.width ?: 0
            L.i("Duo injected into ${target.javaClass.simpleName} (${width}px wide, ${settings.sizePercent}%)" +
                    if (overlay) " - overlaid over the battery" else "")
        } catch (t: Throwable) {
            L.w("onReady: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * The Rive element never bound its view model. Rather than leave an empty slot, it is replaced by the
     * no-native Canvas element, which draws the ring and the percentage from the same mapping (FR-21).
     */
    private fun onElementFailed(candidate: DuoElement, target: ViewGroup) {
        if (element !== candidate) return
        L.w("Rive element did not bind - falling back to Canvas")
        // Tell the app so it can ask the user to send the log; this is the failure we most need evidence for.
        DuoSettingsClient.reportFallback(context, "Rive did not bind on ${android.os.Build.MODEL}; using the simple drawing")
        val canvas = try {
            DuoCanvasView(context, ringPart()).also { it.start() }
        } catch (t: Throwable) {
            L.e("Canvas fallback failed: ${t.javaClass.simpleName}: ${t.message}")
            return
        }
        val container = host ?: target
        try {
            candidate.teardown()
            (candidate.ui.parent as? ViewGroup)?.removeView(candidate.ui)
        } catch (t: Throwable) {
            L.w("fallback remove: ${t.message}")
        }
        val width = geometry.widthPx(target)
        canvas.ui.layoutParams = layoutParamsFor(
            container, width, ViewGroup.LayoutParams.MATCH_PARENT, edgeInsetPx(width, settings.edgePadding)
        )
        container.addView(canvas.ui)
        element = canvas
        applyLayout()
        syncIndicators()
        canvas.onReady { onElementReady(canvas, target) }
        L.i("element: Canvas (fallback after Rive did not bind)")
    }

    private fun createElement(root: View, stage: Int, part: DuoPart = DuoPart.ALL): DuoElement {
        if (stage >= DuoGuard.RIVE) {
            riveElement(root, part)?.let { rive ->
                L.i("element: Rive (stage $stage, $part)")
                return rive
            }
        }
        val canvas = DuoCanvasView(context, part)
        canvas.start()
        L.i("element: Canvas (stage $stage, $part)")
        return canvas
    }

    /**
     * Rive, with everything that can fail checked *before* the native call.
     *
     * `RiveAnimationView` draws into a Surface taken from a TextureView, so it needs a hardware
     * accelerated window — without one there is no surface, and no reason to try. The attempt is recorded
     * *before* the view is constructed, because a native fault kills the process before anything can be
     * caught: that record is what stops a crash loop from repeating itself on the next boot.
     */
    private fun riveElement(root: View, part: DuoPart): DuoElement? {
        if (!root.isHardwareAccelerated) {
            L.w("status bar window is not hardware accelerated - Rive needs a Surface, using Canvas")
            DuoSettingsClient.reportFallback(context, "no hardware acceleration on ${android.os.Build.MODEL}; using the simple drawing")
            return null
        }
        if (!guard.riveAllowed()) {
            DuoSettingsClient.reportFallback(context, "Rive disabled after earlier failures; using the simple drawing")
            return null
        }
        guard.noteRiveAttempt()
        val rive = DuoRiveView(context, part)
        if (!rive.start()) {
            // A clean failure, not a death: hand the attempt back so the breaker only counts crashes.
            guard.clearRiveAttempts()
            rive.teardown()
            DuoSettingsClient.reportFallback(context, "Rive could not start on ${android.os.Build.MODEL}; using the simple drawing")
            return null
        }
        return rive
    }

    /** Once the drawing has survived a few seconds, clear the counter: the breaker tracks deaths only. */
    private fun forgetAttemptsAfterSurvival(element: DuoElement) {
        if (element !is DuoRiveView) return
        try {
            element.ui.postDelayed({ guard.clearRiveAttempts() }, SURVIVAL_MS)
        } catch (_: Throwable) {
        }
    }

    /**
     * Re-applies the hiding pass: the ROM re-shows its icon views whenever the icon set changes, so
     * this runs again on layout changes rather than only once.
     */
    fun reapplyHiding() {
        // FR-03b: the keyguard bar and the shade header are re-shown on every shade/lock transition,
        // so they get the same pass.
        reapplyExtraHiding()
        // The clock is re-inflated with the strip on some ROMs, so its font is re-applied here too.
        clock.apply(root, settings.systemClockFont)
        val strip = hideStrip ?: host ?: return
        // Never hide the stock icons over an element that is not drawing yet: a layout pass can arrive
        // before Rive has bound its view model, and hiding then would leave a blank stretch of status bar.
        if (!(element?.isReady ?: false)) return
        if (!ensureElementAttached()) {
            // Never leave a hole: if the element cannot live in the rebuilt strip, the stock icons come back
            // rather than an empty stretch of status bar. The next hide pass remembers them again.
            L.w("element could not be re-attached - restoring the stock icons instead of leaving a gap")
            hider.restore()
            return
        }
        try {
            // Hide in the strip, never in the element's own (overlay) parent.
            hideStock(
                strip,
                if (overlay) null else element?.ui,
                if (overlay) anchorBattery else null
            )
            hideOverlayAnchor()
        } catch (t: Throwable) {
            L.w("reapplyHiding: ${t.message}")
        }
    }

    /**
     * Rotation (and a changed icon set on some ROMs) re-inflates the whole strip, so our view is gone from the
     * tree while `host` still points at the *old* container. Hiding the stock icons in the new strip at that
     * moment would produce an empty status bar — the one failure this module must never cause. So the element
     * is re-attached first, and if that is not possible the caller is told to put the stock icons back.
     */
    private fun ensureElementAttached(): Boolean {
        val view = element?.ui ?: return true
        val target = host ?: return false
        if (view.parent === target && view.isAttachedToWindow) return true
        return try {
            (view.parent as? ViewGroup)?.removeView(view)
            val barRoot = root
            val fresh = barRoot?.let { finder.findStatusIconsHost(it) } ?: hideStrip ?: target
            hideStrip = fresh
            val combined = if (overlayOnCombined && barRoot != null) {
                finder.findCombinedStatusView(barRoot)
            } else {
                null
            }
            val parent = when {
                combined != null -> {
                    // A fresh CombinedStatusView after a rotation/re-inflate: re-anchor on it, in the bar's
                    // own layer, exactly as the first attach did.
                    if (anchorBattery !== combined) anchorOriginalVisibility = null
                    anchorBattery = combined
                    barRoot?.let { finder.findOverlayContainer(fresh, it) } ?: fresh
                }
                overlay && barRoot != null -> {
                    anchorBattery = finder.findBattery(fresh) ?: anchorBattery
                    finder.chooseElementParent(fresh, barRoot)
                }
                else -> fresh
            }
            host = parent
            parent.addView(view)
            indicators?.ui?.let { icon ->
                (icon.parent as? ViewGroup)?.removeView(icon)
                val index = parent.indexOfChild(view)
                if (index >= 0) parent.addView(icon, index + 1) else parent.addView(icon)
            }
            applyLayout()
            syncIndicators()
            L.i("element re-attached into ${parent.javaClass.simpleName} after the strip was rebuilt")
            true
        } catch (t: Throwable) {
            L.w("re-attach failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** Removes the element and puts the stock icons back exactly as they were. */
    fun teardown() {
        try {
            drop(indicators, host)
            indicators = null
            element?.let { host?.removeView(it.ui) }
            element?.teardown()
        } catch (_: Throwable) {
        }
        for (slot in extras) {
            try {
                drop(slot.indicators, slot.container)
                slot.indicators = null
                slot.element?.let { slot.container?.removeView(it.ui) }
                slot.element?.teardown()
            } catch (_: Throwable) {
            }
        }
        extras.clear()
        element = null
        hider.restore()
        // The One UI 8 anchor is hidden directly (it is not in the strip the hider restores), so its own
        // visibility is put back here or the stock cluster would be left invisible after teardown.
        try {
            anchorOriginalVisibility?.let { anchorBattery?.visibility = it }
        } catch (_: Throwable) {
        }
        clock.restore()
        geometry.reset()
        host = null
        hideStrip = null
        overlay = false
        overlayOnCombined = false
        anchorBattery = null
        anchorOriginalVisibility = null
        root = null
    }

    /** FR-03b: the same hide pass for every extra bar, once its own element is drawing. */
    private fun reapplyExtraHiding() {
        for (slot in extras) {
            val target = slot.container ?: continue
            val keep = slot.element?.ui ?: continue
            if (!(slot.element?.isReady ?: false)) continue
            try {
                hideStock(target, keep)
            } catch (t: Throwable) {
                L.w("${slot.name} hiding: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    // ----------------------------------------------------------------------------- internals

    /**
     * Keeps the overlay anchor out of sight without tearing down its layout.
     *
     * On the One UI 8 path the anchor (`CombinedStatusView`) is not a child of the hidden strip, so the
     * strip's hide pass never reaches it — this is what actually removes the stock cluster. On the other
     * overlay paths the anchor is a child of the strip and is already set INVISIBLE by the hide pass, so
     * this is a harmless re-assertion.
     */
    private fun hideOverlayAnchor() {
        if (!overlay) return
        val anchor = anchorBattery ?: return
        try {
            if (anchorOriginalVisibility == null) anchorOriginalVisibility = anchor.visibility
            // The ROM can re-show the stock battery/cluster on its own - an OEM charging animation
            // re-inflates the bar when the charger is plugged in - and this is the only moment we see it.
            // The line is the evidence for "the battery appears over Duo while charging".
            if (anchor.visibility == View.VISIBLE) {
                logOnce.once("anchorReshown") {
                    L.i("stock anchor ${anchor.javaClass.simpleName} was re-shown by the ROM - hiding it again")
                }
            }
            anchor.visibility = View.INVISIBLE
        } catch (t: Throwable) {
            L.w("anchor hide: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Moves [view] so its centre sits on the battery's centre, measured in the element parent's
     * coordinates. The battery is hidden with INVISIBLE (not GONE) in overlay mode, so its frame is real
     * and survives a rotation; [offsetDp] is the user's horizontal nudge.
     */
    private fun anchorOnBattery(view: View, parent: ViewGroup, side: Int, offsetDp: Int) {
        val battery = anchorBattery ?: return
        try {
            val parentLocation = IntArray(2)
            val batteryLocation = IntArray(2)
            parent.getLocationInWindow(parentLocation)
            battery.getLocationInWindow(batteryLocation)
            val density = context.resources.displayMetrics.density
            val height = RingGeometry.elementHeightPx(side)
            // The overlay parent is a FrameLayout, so a start margin is ignored: the edge-spacing
            // setting is applied as translation here instead. Default 100 % is a zero inset, so an
            // untouched device does not move; on HyperOS 3 a lower value now actually pulls the element
            // left off the battery (reported: "icon is all the way to the right", with no effect).
            view.translationX = batteryLocation[0] + battery.width / 2f - parentLocation[0] -
                    side / 2f + offsetDp * density + edgeInsetPx(side, settings.edgePadding)
            // height/2 is the view centre; the ring sits below that, so the anchor shift brings the
            // ring (not the empty space above it) onto the battery.
            view.translationY = batteryLocation[1] + battery.height / 2f - parentLocation[1] -
                    height / 2f + RingGeometry.ringAnchorShiftY(side)
        } catch (t: Throwable) {
            L.w("overlay anchor: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private companion object {
        const val SURVIVAL_MS = 4_000L
    }
}
