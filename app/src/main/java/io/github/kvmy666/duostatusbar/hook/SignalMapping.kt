package io.github.kvmy666.duostatusbar.hook

/**
 * The Wi-Fi/cellular level mapping and the middle-slot occupant choice.
 *
 * Pure and unit-tested (`DuoMappingTest`), so a change here is caught off-device.
 */
object SignalMapping {

    /**
     * Maps the phone's Wi-Fi bar count onto Duo's three levels, so the ring agrees with the stock icon.
     *
     * The stock icon has four bars (1..4); Duo draws two arcs plus the dot (1..3). The user's mapping,
     * measured against the real icon: 1 and 2 bars -> 1, 3 bars -> 2, 4 bars -> 3. 0 is "no signal".
     */
    fun wifiBars(stockLevel: Int): Int = when {
        stockLevel <= 0 -> 0
        stockLevel <= 2 -> 1
        stockLevel == 3 -> 2
        else -> 3
    }

    /**
     * Wi-Fi layers, bottom-up: nothing connected dims everything, then the dot, the middle arc and
     * finally the outer arc appear as the signal improves (FR-25).
     */
    fun wifiOpacities(level: Int): Triple<Float, Float, Float> = when (level.coerceIn(0, 3)) {
        0 -> Triple(0.3f, 0.3f, 0.3f)
        1 -> Triple(0.3f, 0.3f, 1f)
        2 -> Triple(0.3f, 1f, 1f)
        else -> Triple(1f, 1f, 1f)
    }

    /** Four cellular spheres; `level` 0..4 spheres lit. */
    fun cellOpacities(level: Int): List<Float> {
        val n = level.coerceIn(0, 4)
        return (1..4).map { if (it <= n) 1f else 0.3f }
    }

    /** Four spheres, fully gone. Used when they have nothing left to report. */
    private val HIDDEN_SPHERES = listOf(0f, 0f, 0f, 0f)

    /**
     * What the four spheres under the ring should show.
     *
     * With [wifiDots] off, they are the cellular bars, dimmed to empty while airplane mode is on.
     * With it on they stay cellular whenever airplane mode is off. Airplane mode hands them to
     * Wi-Fi while that radio is on, and removes them when Wi-Fi is off too.
     */
    fun sphereOpacities(
        cellLevel: Int,
        wifiLevel: Int,
        airplane: Boolean,
        wifiOn: Boolean,
        wifiDots: Boolean
    ): List<Float> = when {
        !wifiDots || !airplane -> cellOpacities(if (airplane) 0 else cellLevel)
        wifiOn -> cellOpacities(wifiAsSpheres(wifiLevel))
        else -> HIDDEN_SPHERES
    }

    /**
     * Duo's Wi-Fi level (0..3) back onto four spheres.
     *
     * The glyph compresses the stock four-bar icon (1–2 bars → 1, 3 → 2, 4 → 3). The spheres have
     * a place for each stock bar, so each compressed step lands on the stronger bar it stands for.
     */
    private fun wifiAsSpheres(wifiLevel: Int): Int = when (wifiLevel.coerceIn(0, 3)) {
        0 -> 0
        1 -> 2
        2 -> 3
        else -> 4
    }

    /** The middle slot's occupants, in the order the state machine expects (see `scene.rml`). */
    const val MIDDLE_OFF = 0
    const val MIDDLE_WIFI = 1
    const val MIDDLE_AIRPLANE = 2
    const val MIDDLE_DND = 3

    /** The cellular generation shown when Wi-Fi is off (FR-06): "5G"/"4G"/"3G"/"2G". */
    const val MIDDLE_NETWORK = 4

    /**
     * FR-06/FR-16: airplane wins the slot, then DND, then a **connected** Wi-Fi, then the cellular
     * generation. [wifiConnected] is false when the radio is on but there is no network through it, in
     * which case the phone is on mobile data and the generation is the honest thing to show.
     *
     * [showAirplane] and [showDnd] are the user's choices. A status that is off is skipped, so the
     * slot stays on Wi-Fi or the generation. Wi-Fi and the generation are always eligible.
     */
    fun middleMode(
        airplane: Boolean,
        dnd: Boolean,
        wifiOn: Boolean = true,
        hasNetwork: Boolean = false,
        wifiConnected: Boolean = true,
        showAirplane: Boolean = true,
        showDnd: Boolean = true
    ): Int = when {
        showAirplane && airplane -> MIDDLE_AIRPLANE
        showDnd && dnd -> MIDDLE_DND
        wifiOn && wifiConnected -> MIDDLE_WIFI
        hasNetwork -> MIDDLE_NETWORK
        // Wi-Fi on but not connected, and no generation to name: keep the dim glyph rather than empty.
        wifiOn -> MIDDLE_WIFI
        else -> MIDDLE_OFF
    }

    /**
     * The cellular generation label for a `TelephonyManager.NETWORK_TYPE_*` value.
     *
     * Android-free on purpose: the type constants are spelled out as literals so this stays in the
     * pure, unit-tested half. Unknown or Wi-Fi-calling types map to empty, which leaves the slot off
     * rather than showing a label that means nothing.
     *
     * [dataEnabled] is the user's mobile-data switch. Voice service stays registered after that switch
     * is turned off, so the radio type would still read 5G/4G; the slot names the data path, and with
     * data off there is nothing to name.
     */
    fun networkGeneration(type: Int, nrConnected: Boolean = false, dataEnabled: Boolean = true): String {
        if (!dataEnabled) return ""
        // The phone's radio reports NR as connected. On 5G NSA the data network type is still LTE, so
        // this is the only honest 5G signal - it is what the stock OxygenOS bar reads (see
        // `OplusMobileSignalExImpl`: `ServiceState.getNrState()` 2 or 3 means 5G).
        if (nrConnected) return "5G"
        return when (type) {
            // NETWORK_TYPE_NR
            20 -> "5G"
            // NETWORK_TYPE_LTE, NETWORK_TYPE_LTE_CA
            13, 19 -> "4G"
            // UMTS, EVDO_0/A, HSDPA, HSUPA, HSPA, EVDO_B, EHRPD, HSPAP, TD_SCDMA
            3, 5, 6, 8, 9, 10, 12, 14, 15, 17 -> "3G"
            // GPRS, EDGE, CDMA, 1xRTT, IDEN, GSM
            1, 2, 4, 7, 11, 16 -> "2G"
            else -> ""
        }
    }
}
