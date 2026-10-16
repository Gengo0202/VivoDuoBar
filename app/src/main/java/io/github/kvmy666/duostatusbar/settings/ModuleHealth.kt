package io.github.kvmy666.duostatusbar.settings

import android.content.Context

/** How the module is doing, as the app can tell from stores it can read without root. */
internal enum class ModuleState {
    /** Loaded and reporting. */
    OK,

    /** LSPosed has not injected the module into System UI (never loaded). */
    NEVER_LOADED,

    /** The app APK was updated after the module last loaded: System UI still runs the old code. */
    NEEDS_RESTART,

    /** The module loaded but never reported: the settings channel is broken (the One UI 8 case). */
    NOT_REPORTING
}

internal data class ModuleHealth(val state: ModuleState, val loadedAt: Long, val appUpdatedAt: Long)

/**
 * Diagnoses the module from two stores the app can always read, even when the settings provider is
 * invisible to System UI:
 *
 *  - `Settings.Global.duo_statusbar_last_load`, which the module stamps *before* the gate; and
 *  - the app's own APK `lastUpdateTime`.
 *
 * That distinguishes the two failures a "no report yet" state hides: the app was updated and System UI
 * was never restarted (the running module is the old code), versus the module loaded but its provider
 * call is rejected (`Unknown authority …`, measured on One UI 8) so it can never report.
 */
internal object ModuleHealthCheck {

    fun of(context: Context): ModuleHealth {
        val loadedAt = DuoPrefs.moduleLoadTime(context)
        val statusBlank = DuoPrefs.status(context).isBlank()
        val updatedAt = appUpdatedAt(context)
        val heartbeat = DuoPrefs.heartbeat(context)
        return ModuleHealth(
            decide(loadedAt, updatedAt, statusBlank, heartbeat, System.currentTimeMillis()),
            loadedAt,
            updatedAt
        )
    }

    /**
     * Pure decision, so the rule is unit-tested rather than only exercised on a phone.
     *
     * [heartbeatAt] is the module's "alive right now" stamp and [nowMs] the clock. The module is only
     * trusted when that stamp is present and fresh: a heartbeat of 0 means it never ran (or the running
     * build predates the heartbeat), and an old one means it was unscoped and System UI restarted without
     * it. Both are [ModuleState.NEVER_LOADED], so a report taken then cannot ship the previous run's
     * dump/status as if the module were healthy (measured: unscope, restart, send -> full but stale).
     */
    fun decide(
        loadedAt: Long,
        appUpdatedAt: Long,
        statusBlank: Boolean,
        heartbeatAt: Long = 0L,
        nowMs: Long = 0L
    ): ModuleState = when {
        loadedAt <= 0L -> ModuleState.NEVER_LOADED
        heartbeatAt <= 0L -> ModuleState.NEVER_LOADED
        nowMs > 0L && nowMs - heartbeatAt > DuoPrefs.HEARTBEAT_STALE_MS -> ModuleState.NEVER_LOADED
        // The APK changed after the module last loaded, so System UI is running the previous build.
        appUpdatedAt > loadedAt -> ModuleState.NEEDS_RESTART
        statusBlank -> ModuleState.NOT_REPORTING
        else -> ModuleState.OK
    }

    @Suppress("DEPRECATION")
    private fun appUpdatedAt(context: Context): Long = try {
        context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
    } catch (_: Throwable) {
        0L
    }
}
