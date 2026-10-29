package io.github.kvmy666.duostatusbar.hook.rom


/**
 * What a ROM is called and where its status-bar icon strip lives (FR-01/02).
 *
 * The project's rule is that nothing ROM-specific is guessed: every id here is either **measured on a
 * device** or explicitly marked unverified, and [RomAdapter.notes] says which. The runtime additionally
 * logs every id it probes and what it found (`container <name> -> <class or null>`), so an unsupported ROM
 * produces evidence to add an adapter from — not silence.
 *
 * The AOSP spelling comes first in every list on purpose: it is the name the module targets, and a ROM that
 * keeps it works without a ROM-specific entry at all.
 */
internal data class RomAdapter(
    val id: String,
    val label: String,
    val systemUiPackage: String,
    /** Ordered probes for the icon strip; the first that resolves to a view wins. */
    val containerIds: List<String>,
    /** The battery view, used only to measure the slot the element replaces. */
    val batteryId: String,
    /** The status-bar clock, used only to swap its typeface (the module never hides it). */
    val clockId: String = "clock",
    /** Where the facts came from — "measured" is a device, "unverified" is a guess to be replaced. */
    val notes: String,
    /**
     * Lowercase substrings of the build identity that select this profile from an asset. Empty for the
     * code defaults, so only profiles loaded from data (see [RomProfiles]) carry a match.
     */
    val match: List<String> = emptyList(),
    /** True only when every id was read off a device. Asset data may override only when measured. */
    val measured: Boolean = false,
    /**
     * True when the element's vertical centre must be anchored to the ROM's own icon row (the clock,
     * else the battery, else the icon container) instead of the status-bar window's centre.
     *
     * vivo OriginOS is the case this exists for: its status-bar window is taller than the visible
     * icon band (the punch-hole area is inside the window but above the icons), so centring on the
     * window put the ring visibly above the row the stock icons sit in — the constant vertical offset
     * reported on the X100s Pro. Anchoring to the clock (which the ROM itself centres in the row)
     * follows the row wherever the ROM puts it. Default false: every other ROM keeps the window-centre
     * behaviour exactly as before.
     */
    val anchorY: Boolean = false
)

/**
 * Picks the adapter from build identity, as a pure function so the choice itself is unit-tested instead of
 * being debugged on a phone.
 */
internal object RomDetection {

    private const val AOSP = "com.android.systemui"

    fun forThisRom(
        manufacturer: String,
        brand: String,
        product: String,
        display: String
    ): RomAdapter {
        val haystack = haystack(manufacturer, brand, product, display)
        return when {
            haystack.contains("samsung") -> samsung()
            haystack.contains("oneplus") || haystack.contains("oxygen") -> oxygenOs()
            haystack.contains("oppo") || haystack.contains("oplus") || haystack.contains("coloros") ->
                colorOs()
            haystack.contains("xiaomi") || haystack.contains("redmi") || haystack.contains("poco") ->
                hyperOs()
            haystack.contains("vivo") || haystack.contains("iqoo") || haystack.contains("originos") ->
                originOs()
            else -> aosp()
        }
    }

    /**
     * The same choice, but a **measured** profile loaded from data (see [RomProfiles]) is accepted
     * first. Only `measured` profiles may override, so a data edit can add a device we have read but
     * can never silently replace an unverified guess — the code stays the source of truth otherwise.
     * Kept as an overload so the pure four-argument function (and every test on it) is unchanged.
     */
    fun forThisRom(
        manufacturer: String,
        brand: String,
        product: String,
        display: String,
        profiles: List<RomAdapter>
    ): RomAdapter {
        val haystack = haystack(manufacturer, brand, product, display)
        profiles.firstOrNull { profile ->
            profile.measured && profile.match.any { haystack.contains(it.lowercase()) }
        }?.let { return it }
        return forThisRom(manufacturer, brand, product, display)
    }

    private fun haystack(manufacturer: String, brand: String, product: String, display: String): String =
        listOf(manufacturer, brand, product, display).joinToString(" ").lowercase()

    /**
     * Measured on the OnePlus 15 (CPH2747), Android 16 / OxygenOS 16.0.9.400: the strip is a `LinearLayout`
     * with the AOSP id `system_icons`, holding `statusIcons` (the stock icons) and `battery` (83 × 61 px).
     *
     * The later spellings are only fallbacks for a build that renamed the strip; the measured one is first.
     */
    private fun oxygenOs(): RomAdapter = RomAdapter(
        id = "oxygenos",
        label = "OxygenOS / OnePlus",
        systemUiPackage = AOSP,
        containerIds = listOf("system_icons", "system_icons_container", "status_bar_end_side_content"),
        batteryId = "battery",
        notes = "measured on OxygenOS 16 (CPH2747): system_icons -> LinearLayout, battery 83x61 px",
        match = listOf("oneplus", "oxygen"),
        measured = true
    )

    /**
     * OPPO ColorOS 14 and 16 (Android 14 / 16).
     *
     * No ColorOS device has been measured. The ids are the AOSP ones the OnePlus build keeps plus the
     * spellings seen in OPPO trees; the adapter says so, and the runtime logs which ids resolved — so a
     * report turns these guesses into measured facts. The tree-walk fallback in the host still attaches
     * when none of these match.
     */
    private fun colorOs(): RomAdapter = RomAdapter(
        id = "coloros",
        label = "ColorOS / OPPO (unverified)",
        systemUiPackage = AOSP,
        containerIds = listOf(
            "system_icons",
            "system_icons_container",
            "status_bar_end_side_content",
            "status_bar_contents",
            "statusIcons"
        ),
        batteryId = "battery",
        notes = "unverified: no ColorOS 14/16 device measured; AOSP ids first, then OPPO spellings",
        match = listOf("oppo", "oplus", "coloros")
    )

    /**
     * Samsung One UI 6 (Android 14) and later.
     *
     * No Samsung device has been measured. One UI keeps many AOSP ids (`system_icons`, `statusIcons`,
     * `battery`, `clock`) but re-hosts them in its own containers, so the AOSP names are tried first and
     * the One UI spellings after; the tree-walk fallback covers the rest. Marked unverified on purpose.
     */
    private fun samsung(): RomAdapter = RomAdapter(
        id = "samsung",
        label = "Samsung One UI (unverified)",
        systemUiPackage = AOSP,
        containerIds = listOf(
            "system_icons",
            "systemIcons",
            "statusIcons",
            "status_icons",
            "system_icons_container",
            "status_bar_contents"
        ),
        batteryId = "battery",
        notes = "unverified: no Samsung device measured; AOSP ids first, then One UI spellings " +
            "(systemIcons/status_icons); the battery/status-icons parent fallback covers the rest",
        match = listOf("samsung")
    )

    /**
     * Measured on the Xiaomi 24129PN74G (HyperOS 3, Android 17, SDK 37). HyperOS keeps the AOSP id
     * `system_icons` but re-hosts it: it is a `MiuiStatusBatteryContainer` that holds the stock
     * `statusIcons`, the Wi-Fi/cellular combos and the battery, sitting inside `system_icon_area`
     * (a `MiuiNotificationStatusContainer`). The module injects into `system_icons`, which is why the
     * element appears with the battery correctly.
     *
     * `system_icon_area` is the first fallback: it is the wider strip the same container lives in, so a
     * build that renames `system_icons` still attaches beside the icons. `status_bar_icons` is the
     * `FrameLayout` above both. The AOSP name stays first so a stock-AOSP Xiaomi needs no special case.
     */
    private fun hyperOs(): RomAdapter = RomAdapter(
        id = "hyperos",
        label = "HyperOS / MIUI",
        systemUiPackage = AOSP,
        containerIds = listOf(
            "system_icons",
            "system_icon_area",
            "status_bar_icons",
            "statusIcons",
            "system_icons_container"
        ),
        batteryId = "battery",
        notes = "measured on HyperOS 3 (24129PN74G, Android 17): system_icons -> " +
            "MiuiStatusBatteryContainer holding statusIcons + battery; system_icon_area is the parent",
        match = listOf("xiaomi", "redmi", "poco"),
        measured = true
    )

    /**
     * vivo OriginOS 5/6 (Android 15/16), adapted for the X100s Pro user report (OriginOS 6, Android 16,
     * rooted, LSPosed). vivo keeps the AOSP ids (`system_icons`, `statusIcons`, `battery`, `clock`) in
     * a tree-walk-reachable form — the generic build attached — but two of its measurements differ
     * enough to break the stock maths, both handled here:
     *
     *  - the status-bar *window* is taller than the visible icon band (punch-hole headroom above the
     *    icons), so window-centre vertical anchoring sat the ring above the icon row. [anchorY] moves
     *    the anchor to the ROM's own icon row (the clock, else the battery) instead;
     *  - transient re-measures of the vivo icon container report the full window height, which the
     *    generic `measuredWidth` fallback read as a slot width — the occasional giant element. The
     *    slot base is now clamped against the framework `status_bar_height` (see [SlotGeometry]).
     *
     * The container ids are the AOSP spellings first, then the OEM-area spellings other
     * vivo-class trees keep; the runtime logs every probe, so a report can turn these into measured
     * facts exactly like the HyperOS profile did.
     */
    private fun originOs(): RomAdapter = RomAdapter(
        id = "originos",
        label = "OriginOS / vivo (adapted)",
        systemUiPackage = AOSP,
        containerIds = listOf(
            "system_icons",
            "system_icons_container",
            "status_bar_end_side_content",
            "status_bar_contents",
            "statusIcons"
        ),
        batteryId = "battery",
        notes = "adapted for vivo X100s Pro (OriginOS 6, Android 16): AOSP ids first; " +
            "icon-row vertical anchor and clamped slot base - see the class comment",
        match = listOf("vivo", "iqoo", "originos"),
        anchorY = true
    )

    private fun aosp(): RomAdapter = RomAdapter(
        id = "aosp",
        label = "AOSP / close to stock",
        systemUiPackage = AOSP,
        containerIds = listOf("system_icons", "system_icons_container"),
        batteryId = "battery",
        notes = "AOSP baseline: the strip is a LinearLayout with id system_icons"
    )
}
