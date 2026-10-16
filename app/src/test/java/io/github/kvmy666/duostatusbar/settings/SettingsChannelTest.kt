package io.github.kvmy666.duostatusbar.settings

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.content.res.Configuration
import android.database.MatrixCursor
import androidx.test.core.app.ApplicationProvider
import io.github.kvmy666.duostatusbar.hook.DuoSettingsClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/**
 * The app ↔ module channel is the newest surface and the one with the least device coverage, so its contract
 * is pinned here: the settings must survive a round trip through the provider *as the module reads them*, and
 * the provider must expose exactly the columns the module asks for.
 *
 * This is the test that catches the failure mode I cannot see from either side alone: the app writes
 * `size_percent`, the module reads `size_percent`, and a typo in one of those strings would otherwise be
 * discovered by a user whose size slider does nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsChannelTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `settings survive a write and a read`() {
        val written = DuoSettings(
            enabled = true,
            useRive = false,
            showPercent = false,
            sizePercent = 120,
            offsetX = -12,
            splitIndicators = true,
            indicatorsOffsetX = 36,
            tapAction = "toggle_flashlight",
            doubleTapAction = "show_notifications",
            longPressAction = "take_screenshot",
            indicatorsTapAction = "show_quick_settings",
            indicatorsDoubleTapAction = "toggle_wifi",
            indicatorsLongPressAction = "toggle_power_saving",
            iconColor = "black",
            hideOtherIcons = false
        )
        DuoPrefs.write(context, written)
        assertEquals(written, DuoPrefs.read(context))
    }

    @Test
    fun `older split settings inherit ring actions for the icons`() {
        context.getSharedPreferences("duo_settings", Context.MODE_PRIVATE).edit()
            .clear()
            .putString(DuoPrefs.COL_TAP, "toggle_flashlight")
            .putString(DuoPrefs.COL_DOUBLE_TAP, "show_notifications")
            .putString(DuoPrefs.COL_LONG_PRESS, "take_screenshot")
            .apply()

        val read = DuoPrefs.read(context, DuoOrientation.PORTRAIT)

        assertEquals(read.tapAction, read.indicatorsTapAction)
        assertEquals(read.doubleTapAction, read.indicatorsDoubleTapAction)
        assertEquals(read.longPressAction, read.indicatorsLongPressAction)
    }

    @Test
    fun `unlocked ring and indicator offsets retain their two hundred dp cap`() {
        DuoPrefs.write(
            context,
            DuoSettings(sizePercent = 9_999, offsetX = 9_999, percentHeight = 9_999, indicatorsOffsetX = 9_999)
        )
        val read = DuoPrefs.read(context)
        assertEquals(DuoPrefs.MAX_SIZE, read.sizePercent)
        assertEquals(DuoPrefs.MAX_UNLOCKED_RING_OFFSET, read.offsetX)
        assertEquals(DuoPrefs.MAX_PERCENT_HEIGHT, read.percentHeight)
        assertEquals(DuoPrefs.MAX_INDICATORS_OFFSET, read.indicatorsOffsetX)
    }

    @Test
    fun `screen locked ring offset is not artificially capped`() {
        DuoPrefs.write(
            context,
            DuoSettings(splitIndicators = true, lockRing = true, offsetX = 9_999)
        )

        assertEquals(9_999, DuoPrefs.read(context).offsetX)
    }

    @Test
    fun `the revision moves on every write so the module can notice changes`() {
        val first = DuoPrefs.write(context, DuoSettings(enabled = true))
        val second = DuoPrefs.write(context, DuoSettings(enabled = false))
        assertTrue("revision must increase", second > first)
    }

    @Test
    fun `the row the module reads has one value per column, in the declared order`() {
        val row = DuoSettingsProvider.rowFor(
            DuoSettings(
                enabled = true,
                useRive = false,
                showPercent = false,
                sizePercent = 130,
                offsetX = 7,
                tapAction = "toggle_flashlight",
                doubleTapAction = "no_action",
                longPressAction = "take_screenshot",
                indicatorsTapAction = "show_quick_settings",
                indicatorsDoubleTapAction = "toggle_wifi",
                indicatorsLongPressAction = "toggle_power_saving",
                percentHeight = 40,
                splitIndicators = true,
                indicatorsOffsetX = -18
            ),
            revision = 42L
        )
        assertEquals(
            "a column added or reordered without the module being updated is the silent failure this guards",
            DuoPrefs.COLUMNS.size,
            row.size
        )
        assertEquals(1, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_ENABLED)])
        assertEquals(0, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_USE_RIVE)])
        assertEquals(0, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_SHOW_PERCENT)])
        assertEquals(130, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_SIZE_PERCENT)])
        assertEquals(7, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_OFFSET_X)])
        assertEquals(1, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_LIVE_APPLY)])
        assertEquals(1, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_CLOCK_FONT)])
        assertEquals(42L, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_REVISION)])
        assertEquals("toggle_flashlight", row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_TAP)])
        assertEquals("no_action", row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_DOUBLE_TAP)])
        assertEquals("take_screenshot", row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_LONG_PRESS)])
        assertEquals("show_quick_settings", row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_INDICATORS_TAP)])
        assertEquals("toggle_wifi", row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_INDICATORS_DOUBLE_TAP)])
        assertEquals("toggle_power_saving", row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_INDICATORS_LONG_PRESS)])
        assertEquals(1000, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_REVEAL_MS)])
        // FR-15b / FR-08b: the two newest columns must travel too, or the module would never see them.
        assertEquals("auto", row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_ICON_COLOR)])
        // Default is now FR-08b (keep the other icons), so an untouched setting travels as 0.
        assertEquals(0, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_HIDE_OTHER_ICONS)])
        // Both statuses may take the middle by default, so the legacy "hide both" column stays off.
        assertEquals(0, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_NETWORK_ONLY)])
        assertEquals(1, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_SHOW_AIRPLANE)])
        assertEquals(1, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_SHOW_DND)])
        assertEquals(DuoPrefs.DND_MIDDLE, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_DND_MODE)])
        // The dual-SIM line choice travels as a string, defaulting to the automatic data line.
        assertEquals("auto", row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_SIM_CHOICE)])
        assertEquals(40, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_PERCENT_HEIGHT)])
        assertEquals(1, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_SPLIT_INDICATORS)])
        assertEquals(-18, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_INDICATORS_OFFSET_X)])
        // Off unless the user asks: a missing choice keeps the cellular dots.
        assertEquals(0, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_WIFI_DOTS)])
        assertEquals(DuoPrefs.DEFAULT_EDGE_PADDING, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_EDGE_PADDING)])
        assertEquals(0, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_LOCK_RING)])
        // One set published alone is copied into the landscape columns, so rotation does not go blank.
        assertEquals(130, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.LAND_PREFIX + DuoPrefs.COL_SIZE_PERCENT)])
        assertEquals(7, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.LAND_PREFIX + DuoPrefs.COL_OFFSET_X)])
    }

    @Test
    fun `airplane and do not disturb travel through the channel on their own`() {
        DuoPrefs.write(context, DuoSettings(showAirplane = true, dndMode = DuoPrefs.DND_OFF))
        val read = DuoPrefs.read(context)
        assertEquals(true, read.showAirplane)
        assertEquals(DuoPrefs.DND_OFF, read.dndMode)
        val row = DuoSettingsProvider.rowFor(read, revision = 1L)
        assertEquals(1, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_SHOW_AIRPLANE)])
        assertEquals(0, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_SHOW_DND)])
        assertEquals(DuoPrefs.DND_OFF, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_DND_MODE)])
        // One of them is still allowed, so an older module must not treat this as "hide both".
        assertEquals(0, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_NETWORK_ONLY)])
    }

    @Test
    fun `an old network-only choice keeps both statuses out until the user picks again`() {
        context.getSharedPreferences("duo_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean(DuoPrefs.COL_NETWORK_ONLY, true)
            .apply()
        val read = DuoPrefs.read(context)
        assertEquals(false, read.showAirplane)
        assertEquals(DuoPrefs.DND_OFF, read.dndMode)
    }

    @Test
    fun `edge spacing travels through the channel and can be zero`() {
        DuoPrefs.write(context, DuoSettings(edgePadding = 0))
        assertEquals(0, DuoPrefs.read(context).edgePadding)
        DuoPrefs.write(context, DuoSettings(edgePadding = 9_999))
        assertEquals(DuoPrefs.MAX_EDGE_PADDING, DuoPrefs.read(context).edgePadding)
        val row = DuoSettingsProvider.rowFor(DuoSettings(edgePadding = 25), revision = 1L)
        assertEquals(25, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_EDGE_PADDING)])
        assertEquals(25, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.LAND_PREFIX + DuoPrefs.COL_EDGE_PADDING)])
    }

    @Test
    fun `locking the ring travels through the channel and defaults off`() {
        context.getSharedPreferences("duo_settings", Context.MODE_PRIVATE).edit().clear().commit()
        DuoPrefs.write(context, DuoSettings(lockRing = true, splitIndicators = true), DuoOrientation.PORTRAIT)
        DuoPrefs.write(context, DuoSettings(lockRing = false, sizePercent = 110), DuoOrientation.LANDSCAPE)
        assertEquals(true, DuoPrefs.read(context, DuoOrientation.PORTRAIT).lockRing)
        assertEquals(false, DuoPrefs.read(context, DuoOrientation.LANDSCAPE).lockRing)
        val row = DuoSettingsProvider.rowFor(
            DuoPrefs.read(context, DuoOrientation.PORTRAIT),
            revision = 1L,
            DuoPrefs.read(context, DuoOrientation.LANDSCAPE)
        )
        assertEquals(1, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_LOCK_RING)])
        assertEquals(0, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.LAND_PREFIX + DuoPrefs.COL_LOCK_RING)])
        val columns = DuoPrefs.PORTRAIT_COLUMNS.filter { it != DuoPrefs.COL_LOCK_RING }.toTypedArray()
        val full = DuoSettingsProvider.rowFor(DuoSettings(lockRing = true), 1L)
        val values = full.copyOfRange(0, DuoPrefs.PORTRAIT_COLUMNS.size).toMutableList()
        values.removeAt(DuoPrefs.PORTRAIT_COLUMNS.indexOf(DuoPrefs.COL_LOCK_RING))
        val cursor = MatrixCursor(columns).apply { addRow(values) }
        cursor.use {
            it.moveToFirst()
            val read = DuoSettingsClient.fromCursor(it, Configuration.ORIENTATION_PORTRAIT)
            assertEquals(false, read.lockRing)
        }
    }

    @Test
    fun `a row without edge spacing keeps the full slot`() {
        val columns = DuoPrefs.PORTRAIT_COLUMNS.filter { it != DuoPrefs.COL_EDGE_PADDING }.toTypedArray()
        val full = DuoSettingsProvider.rowFor(DuoSettings(sizePercent = 100), 1L)
        val values = full.copyOfRange(0, DuoPrefs.PORTRAIT_COLUMNS.size).toMutableList()
        values.removeAt(DuoPrefs.PORTRAIT_COLUMNS.indexOf(DuoPrefs.COL_EDGE_PADDING))
        val cursor = MatrixCursor(columns).apply { addRow(values) }
        cursor.moveToFirst()
        val read = DuoSettingsClient.fromCursor(cursor, Configuration.ORIENTATION_PORTRAIT)
        assertEquals(DuoPrefs.DEFAULT_EDGE_PADDING, read.edgePadding)
    }

    @Test
    fun `do not disturb on the signal dots travels through the channel`() {
        DuoPrefs.write(context, DuoSettings(dndMode = DuoPrefs.DND_DOTS))
        assertEquals(DuoPrefs.DND_DOTS, DuoPrefs.read(context).dndMode)
        val row = DuoSettingsProvider.rowFor(DuoPrefs.read(context), revision = 1L)
        assertEquals(DuoPrefs.DND_DOTS, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_DND_MODE)])
        // Dots do not take the middle, so an older module must not also draw the moon there.
        assertEquals(0, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_SHOW_DND)])
        assertEquals(0, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_NETWORK_ONLY)])
        val cursor = MatrixCursor(DuoPrefs.COLUMNS).apply { addRow(row.toList()) }
        cursor.moveToFirst()
        val read = DuoSettingsClient.fromCursor(cursor, Configuration.ORIENTATION_PORTRAIT)
        assertEquals(DuoPrefs.DND_DOTS, read.dndMode)
        assertEquals(false, read.showDnd)
    }

    @Test
    fun `a row without the do not disturb mode keeps the older switch`() {
        val columns = DuoPrefs.PORTRAIT_COLUMNS.filter { it != DuoPrefs.COL_DND_MODE }.toTypedArray()
        val full = DuoSettingsProvider.rowFor(DuoSettings(dndMode = DuoPrefs.DND_MIDDLE), 1L)
        val values = full.copyOfRange(0, DuoPrefs.PORTRAIT_COLUMNS.size).toMutableList()
        values.removeAt(DuoPrefs.PORTRAIT_COLUMNS.indexOf(DuoPrefs.COL_DND_MODE))
        val on = MatrixCursor(columns).apply { addRow(values) }
        on.moveToFirst()
        assertEquals(DuoPrefs.DND_MIDDLE, DuoSettingsClient.fromCursor(on, Configuration.ORIENTATION_PORTRAIT).dndMode)

        val offValues = values.toMutableList()
        offValues[columns.indexOf(DuoPrefs.COL_SHOW_DND)] = 0
        val off = MatrixCursor(columns).apply { addRow(offValues) }
        off.moveToFirst()
        assertEquals(DuoPrefs.DND_OFF, DuoSettingsClient.fromCursor(off, Configuration.ORIENTATION_PORTRAIT).dndMode)
    }

    @Test
    fun `the wifi-dots toggle travels through the channel`() {
        DuoPrefs.write(context, DuoSettings(wifiDots = true))
        assertEquals(true, DuoPrefs.read(context).wifiDots)
        val row = DuoSettingsProvider.rowFor(DuoPrefs.read(context), revision = 1L)
        assertEquals(1, row[DuoPrefs.COLUMNS.indexOf(DuoPrefs.COL_WIFI_DOTS)])
    }

    @Test
    fun `an arrival duration is snapped to one the Rive file can actually play`() {
        // The file holds one timeline at five speeds. Anything between two of them has to become one of
        // them, or the state machine would simply never fire and the element would never arrive.
        for (choice in DuoPrefs.REVEAL_CHOICES) {
            assertEquals(choice, DuoPrefs.nearestReveal(choice))
            assertEquals("just under $choice", choice, DuoPrefs.nearestReveal(choice - 1))
        }
        assertEquals(500, DuoPrefs.nearestReveal(0))
        assertEquals(1500, DuoPrefs.nearestReveal(99_999))
        // 624 is nearer 500 than 750; 626 tips the other way. The boundary is the midpoint.
        assertEquals(500, DuoPrefs.nearestReveal(624))
        assertEquals(750, DuoPrefs.nearestReveal(626))
    }

    @Test
    fun `a provider that cannot be reached is not mistaken for the user switching the module off`() {
        // The ColorOS/realme boot failure: the provider does not answer. It has to be read as
        // "unreachable" (so the module retries), never as the default `enabled=false` (which would pin the
        // module to stage 0 and make it look switched off). A provider that returns no cursor is exactly
        // what a dead one does on device.
        val dead = object : ContentProvider() {
            override fun onCreate() = true
            override fun query(
                uri: Uri, projection: Array<out String>?, selection: String?,
                selectionArgs: Array<out String>?, sortOrder: String?
            ): Cursor? = null
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
            override fun update(
                uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?
            ): Int = 0
        }
        ShadowContentResolver.registerProviderInternal(DuoPrefs.AUTHORITY, dead)

        val result = DuoSettingsClient.read(context)
        assertFalse("an unreachable provider must not look like an explicit 'off'", result.enabled)
        assertTrue("the failure has to be distinguishable to the stage logic", DuoSettingsClient.providerUnreachable)
    }

    @Test
    fun `status history keeps the newest reports and ignores repeats`() {
        DuoPrefs.writeStatus(context, "stage=1 renderer=Canvas")
        DuoPrefs.writeStatus(context, "stage=1 renderer=Canvas")   // repeat: ignored
        DuoPrefs.writeStatus(context, "stage=2 renderer=Rive")
        assertEquals("stage=2 renderer=Rive", DuoPrefs.status(context))
        val history = DuoPrefs.statusHistory(context)
        assertEquals("the repeat must not be stored twice", 2, history.size)
        assertTrue(history.last().endsWith("stage=2 renderer=Rive"))
    }

    @Test
    fun `portrait and landscape settings are stored apart`() {
        context.getSharedPreferences("duo_settings", Context.MODE_PRIVATE).edit().clear().commit()
        DuoPrefs.write(
            context,
            DuoSettings(enabled = true, sizePercent = 80, offsetX = 10, iconColor = "black"),
            DuoOrientation.PORTRAIT
        )
        DuoPrefs.write(
            context,
            DuoSettings(enabled = false, sizePercent = 160, offsetX = -30, iconColor = "white", wifiDots = true),
            DuoOrientation.LANDSCAPE
        )
        val portrait = DuoPrefs.read(context, DuoOrientation.PORTRAIT)
        val landscape = DuoPrefs.read(context, DuoOrientation.LANDSCAPE)
        assertEquals(80, portrait.sizePercent)
        assertEquals(10, portrait.offsetX)
        assertEquals(true, portrait.enabled)
        assertEquals("black", portrait.iconColor)
        assertEquals(false, portrait.wifiDots)
        assertEquals(160, landscape.sizePercent)
        assertEquals(-30, landscape.offsetX)
        assertEquals(false, landscape.enabled)
        assertEquals("white", landscape.iconColor)
        assertEquals(true, landscape.wifiDots)
    }

    @Test
    fun `landscape starts as a copy of portrait until it is edited`() {
        context.getSharedPreferences("duo_settings", Context.MODE_PRIVATE).edit().clear().commit()
        DuoPrefs.write(context, DuoSettings(sizePercent = 140, offsetX = 12, showPercent = false), DuoOrientation.PORTRAIT)
        val landscape = DuoPrefs.read(context, DuoOrientation.LANDSCAPE)
        assertEquals(140, landscape.sizePercent)
        assertEquals(12, landscape.offsetX)
        assertEquals(false, landscape.showPercent)
    }

    @Test
    fun `the module reads the set that matches the status bar orientation`() {
        val portrait = DuoSettings(
            enabled = true,
            sizePercent = 80,
            offsetX = 4,
            tapAction = "toggle_flashlight",
            percentHeight = 20
        )
        val landscape = DuoSettings(
            enabled = false,
            sizePercent = 170,
            offsetX = -22,
            tapAction = "take_screenshot",
            percentHeight = 90,
            splitIndicators = true,
            wifiDots = true,
            dndMode = DuoPrefs.DND_DOTS
        )
        val cursor = MatrixCursor(DuoPrefs.COLUMNS).apply {
            addRow(DuoSettingsProvider.rowFor(portrait, 7L, landscape).toList())
        }
        cursor.moveToFirst()
        val inPortrait = DuoSettingsClient.fromCursor(cursor, Configuration.ORIENTATION_PORTRAIT)
        val inLandscape = DuoSettingsClient.fromCursor(cursor, Configuration.ORIENTATION_LANDSCAPE)
        assertEquals(80, inPortrait.sizePercent)
        assertEquals(4, inPortrait.offsetX)
        assertEquals(true, inPortrait.enabled)
        assertEquals("toggle_flashlight", inPortrait.tapAction)
        assertEquals(20, inPortrait.percentHeight)
        assertEquals(7L, inPortrait.revision)
        assertEquals(170, inLandscape.sizePercent)
        assertEquals(-22, inLandscape.offsetX)
        assertEquals(false, inLandscape.enabled)
        assertEquals("take_screenshot", inLandscape.tapAction)
        assertEquals(90, inLandscape.percentHeight)
        assertEquals(true, inLandscape.splitIndicators)
        assertEquals(true, inLandscape.wifiDots)
        assertEquals(DuoPrefs.DND_DOTS, inLandscape.dndMode)
        assertEquals(DuoPrefs.DND_MIDDLE, inPortrait.dndMode)
        assertEquals(7L, inLandscape.revision)
    }

    @Test
    fun `a row without landscape columns keeps the portrait set in landscape`() {
        val full = DuoSettingsProvider.rowFor(DuoSettings(sizePercent = 111, offsetX = 6), 3L)
        val cursor = MatrixCursor(DuoPrefs.PORTRAIT_COLUMNS).apply {
            addRow(full.copyOfRange(0, DuoPrefs.PORTRAIT_COLUMNS.size).toList())
        }
        cursor.moveToFirst()
        val inLandscape = DuoSettingsClient.fromCursor(cursor, Configuration.ORIENTATION_LANDSCAPE)
        assertEquals(111, inLandscape.sizePercent)
        assertEquals(6, inLandscape.offsetX)
        assertEquals(3L, inLandscape.revision)
    }
}
