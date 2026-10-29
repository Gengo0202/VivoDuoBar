package io.github.kvmy666.duostatusbar.settings

import android.content.Context
import android.provider.Settings
import io.github.kvmy666.duostatusbar.hook.DuoGuard

/**
 * Reads the module's adb/developer stage override (see the module's `DuoGuard`).
 *
 * The stage key is a `Settings.Global` entry that wins over the app's own settings, so a
 * leftover `1` (icons only, no native drawing) pins the module to the Canvas fallback forever with no
 * visible reason — the exact "wifi icon not show" report. A normal app can read `Settings.Global`
 * without a permission, so the app can detect and explain it; clearing it needs root (the app has no
 * `WRITE_SECURE_SETTINGS`), which is why [clearCommand] is shown to the user as a fallback.
 *
 * The key is referenced from [DuoGuard] rather than re-spelled here: the app and the module inside
 * System UI must agree on it, and a duplicated string once drifted (the module renamed its keys, the
 * app kept reading the old ones, and every health check then said "not running").
 */
internal object StageOverride {

    const val KEY = DuoGuard.KEY_STAGE

    /** The override in force (0/1/2), or null when it has not been set. Never throws. */
    fun read(context: Context): Int? = try {
        Settings.Global.getInt(context.contentResolver, KEY, ABSENT).let {
            if (it in OFF..RIVE) it else null
        }
    } catch (_: Throwable) {
        null
    }

    /** What to tell the user when the app cannot clear it itself. */
    const val clearCommand = "adb shell settings delete global ${DuoGuard.KEY_STAGE}"

    private const val ABSENT = -1
    private const val OFF = 0
    private const val RIVE = 2
}
