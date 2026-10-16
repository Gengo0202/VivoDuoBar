package io.github.kvmy666.duostatusbar.hook

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import io.github.kvmy666.duostatusbar.L

/**
 * The cellular platform reads behind the Duo element.
 *
 * Every reader returns the caller's current value when it cannot read — a status bar that shows a
 * slightly stale number is far better than a status bar that throws (FR-21).
 */
internal object CellReader {

    /** `Settings.Global.MOBILE_DATA`. Hidden from the SDK stub, so the name is spelled out. */
    const val MOBILE_DATA_SETTING = "mobile_data"

    /** Cellular spheres 0..4. */
    fun cellLevel(context: Context, airplane: Boolean, current: Int): Int = try {
        if (airplane) 0
        else {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            tm?.signalStrength?.level?.coerceIn(0, 4) ?: current
        }
    } catch (t: Throwable) {
        L.w("cellLevel: ${t.message}")
        current
    }

    /**
     * Per-SIM cellular levels (0..4), in SIM-slot order, or an empty list when there is only one active
     * subscription. The dual-SIM display splits the four spheres into two pairs, one per line, so it needs
     * both levels at once (the user's request; iOS shows both carriers' signal strength).
     */
    @SuppressLint("MissingPermission")
    fun cellLevels(context: Context, airplane: Boolean): List<Int> = try {
        if (airplane) emptyList()
        else {
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            val subs = sm?.activeSubscriptionInfoList.orEmpty().sortedBy { it.simSlotIndex }
            if (subs.size < 2) emptyList() else subs.map { levelForSubscription(context, it.subscriptionId) }
        }
    } catch (t: Throwable) {
        L.w("cellLevels: ${t.message}")
        emptyList()
    }

    /** One subscription's signal level (0..4); 0 when it cannot be read. */
    private fun levelForSubscription(context: Context, subId: Int): Int = try {
        val base = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val perSub = base?.let { tm ->
            TelephonyManager::class.java
                .getMethod("createForSubscriptionId", Int::class.javaPrimitiveType)
                .invoke(tm, subId) as? TelephonyManager
        }
        ((perSub ?: base)?.signalStrength?.level ?: 0).coerceIn(0, 4)
    } catch (t: Throwable) {
        L.w("cellLevel[$subId]: ${t.message}")
        0
    }

    /**
     * The cellular generation the phone is on ("5G"/"4G"/"3G"/"2G"), or empty when there is no
     * service. Empty is a real answer here (unknown type, or mobile data switched off), so it is
     * returned as-is rather than masked by the last value; only a failed read keeps the caller's
     * value (FR-21).
     *
     * Data off is empty on purpose. The voice radio stays registered, so the network type and NR
     * state would still say 5G/4G; the middle slot is the data-path label, and with data off it goes.
     */
    @SuppressLint("MissingPermission")
    fun networkGeneration(context: Context, airplane: Boolean, current: String): String = try {
        if (airplane) ""
        else {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val data = tm?.dataNetworkType ?: TelephonyManager.NETWORK_TYPE_UNKNOWN
            val type = if (data != TelephonyManager.NETWORK_TYPE_UNKNOWN) data
            else tm?.voiceNetworkType ?: TelephonyManager.NETWORK_TYPE_UNKNOWN
            DuoMapping.networkGeneration(
                type,
                nrConnected = isNrConnected(tm),
                dataEnabled = isMobileDataEnabled(context)
            )
        }
    } catch (t: Throwable) {
        L.w("networkGeneration: ${t.message}")
        current
    }

    /**
     * Whether the user has mobile data switched on for the default data line.
     *
     * Unreadable means "still on": blanking a working 5G/4G label is worse than keeping it (FR-21).
     * The settings key is only the fallback; `TelephonyManager.isDataEnabled()` is the toggle itself,
     * including the per-SIM switch the quick-settings tile writes.
     */
    @SuppressLint("MissingPermission")
    fun isMobileDataEnabled(context: Context): Boolean = try {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (tm != null) tm.isDataEnabled else mobileDataSetting(context)
    } catch (t: Throwable) {
        L.w("isMobileDataEnabled: ${t.message}")
        mobileDataSetting(context)
    }

    /**
     * `Settings.Global.MOBILE_DATA` (`"mobile_data"`). Hidden from the SDK stub, so the name is
     * spelled out. Defaults to on when the setting cannot be read.
     */
    private fun mobileDataSetting(context: Context): Boolean = try {
        Settings.Global.getInt(context.contentResolver, MOBILE_DATA_SETTING, 1) == 1
    } catch (_: Throwable) {
        true
    }

    /**
     * Whether the radio is actually on NR (5G).
     *
     * On 5G NSA - which is what most carriers run - `getDataNetworkType()` reports LTE even while the
     * stock bar shows 5G, which is why the element said 4G on a 5G phone. `ServiceState.getNrState()`
     * is what the stock OxygenOS bar itself reads (`OplusMobileSignalExImpl`), so it is read here the
     * same way. Both calls are hidden/reflected and guarded: unreadable means "not NR", which only
     * costs a 5G label, never the status bar.
     */
    private fun isNrConnected(tm: TelephonyManager?): Boolean = try {
        if (tm == null) false
        else {
            val serviceState = TelephonyManager::class.java
                .getMethod("getServiceState")
                .invoke(tm)
            val nrState = serviceState?.let {
                it.javaClass.getMethod("getNrState").invoke(it) as? Int
            }
            // ServiceState.NR_STATE_CONNECTED (2) / NR_STATE_NOT_RESTRICTED (3).
            nrState == 2 || nrState == 3
        }
    } catch (t: Throwable) {
        false
    }
}
