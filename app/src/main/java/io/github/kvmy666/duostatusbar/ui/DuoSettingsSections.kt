package io.github.kvmy666.duostatusbar.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.kvmy666.duostatusbar.BuildConfig
import io.github.kvmy666.duostatusbar.DuoRivePreview
import io.github.kvmy666.duostatusbar.DuoRiveStill
import io.github.kvmy666.duostatusbar.R
import io.github.kvmy666.duostatusbar.hook.DuoMapping
import io.github.kvmy666.duostatusbar.hook.DuoPart
import io.github.kvmy666.duostatusbar.settings.DuoPrefs
import io.github.kvmy666.duostatusbar.settings.DuoSettings
import io.github.kvmy666.duostatusbar.settings.UpdateInfo

// ------------------------------------------------------------------ Battery icon
@Composable
internal fun CustomizeSection(
    settings: DuoSettings,
    onUpdate: (DuoSettings) -> Unit,
    matches: SearchGate,
    onRestart: () -> Unit
) {
    val screenWidthDp = LocalConfiguration.current.screenWidthDp.coerceAtLeast(1)
    if (matches(
            stringResource(R.string.section_customize), stringResource(R.string.section_element),
            stringResource(R.string.settings_master),
            stringResource(R.string.settings_master_detail),             stringResource(R.string.settings_percent),
            stringResource(R.string.settings_percent_detail),
            stringResource(R.string.settings_percent_height),
            stringResource(R.string.settings_percent_height_detail),
            stringResource(R.string.settings_split),
            stringResource(R.string.settings_split_detail),
            stringResource(R.string.settings_lock_ring),
            stringResource(R.string.settings_lock_ring_detail),
            stringResource(R.string.settings_indicators_position),
            stringResource(R.string.settings_edge_padding),
            stringResource(R.string.settings_edge_padding_detail),
            stringResource(R.string.settings_size),
            stringResource(R.string.settings_position), stringResource(R.string.settings_live_apply),
            stringResource(R.string.settings_show_charging_bolt),
            stringResource(R.string.settings_show_charging_bolt_detail)
        )
    ) Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(stringResource(R.string.section_customize))
            Text(
                text = stringResource(R.string.settings_customize_detail),
                style = MaterialTheme.typography.bodySmall
            )
            SettingSwitch(
                label = stringResource(R.string.settings_master),
                detail = stringResource(R.string.settings_master_detail),
                checked = settings.enabled,
                preview = {
                    // Off is the element *absent*: an empty chip, not a ring reading "0".
                    DuoSettingPreview(
                        off = demo(level = 0, showPercent = false),
                        on = demo()
                    )
                }
            ) { onUpdate(settings.copy(enabled = it)) }
            SettingSwitch(
                label = stringResource(R.string.settings_percent),
                detail = stringResource(R.string.settings_percent_detail),
                checked = settings.showPercent,
                enabled = settings.enabled,
                preview = {
                    DuoSettingPreview(off = demo(showPercent = false), on = demo(showPercent = true))
                }
            ) { onUpdate(settings.copy(showPercent = it)) }

            SettingSwitch(
                label = stringResource(R.string.settings_show_charging_bolt),
                detail = stringResource(R.string.settings_show_charging_bolt_detail),
                checked = settings.showChargingBolt,
                enabled = settings.enabled,
                preview = {
                    DuoSettingPreview(
                        off = demo(charging = true, showCharging = false),
                        on = demo(charging = true, showCharging = true)
                    )
                }
            ) { onUpdate(settings.copy(showChargingBolt = it)) }

            LabelledSlider(
                label = "${stringResource(R.string.settings_percent_height)} ${settings.percentHeight}%",
                value = settings.percentHeight.toFloat(),
                range = DuoPrefs.MIN_PERCENT_HEIGHT.toFloat()..DuoPrefs.MAX_PERCENT_HEIGHT.toFloat(),
                enabled = settings.enabled && settings.showPercent,
                preview = {
                    DuoSettingPreview(
                        off = demo(percentHeight = DuoPrefs.MIN_PERCENT_HEIGHT),
                        on = demo(percentHeight = DuoPrefs.MAX_PERCENT_HEIGHT)
                    )
                }
            ) { onUpdate(settings.copy(percentHeight = it.toInt())) }
            Text(
                text = stringResource(R.string.settings_percent_height_detail),
                style = MaterialTheme.typography.bodySmall
            )

            SettingSwitch(
                label = stringResource(R.string.settings_split),
                detail = stringResource(R.string.settings_split_detail),
                checked = settings.splitIndicators,
                enabled = settings.enabled,
                preview = {
                    DuoSettingPreview(
                        off = demo(),
                        on = DuoPart.RING.apply(demo())
                    )
                }
            ) { onUpdate(settings.copy(splitIndicators = it)) }

            LabelledSlider(
                label = "${stringResource(R.string.settings_size)} ${settings.sizePercent}%",
                value = settings.sizePercent.toFloat(),
                range = DuoPrefs.MIN_SIZE.toFloat()..DuoPrefs.MAX_SIZE.toFloat(),
                enabled = settings.enabled,
                preview = {
                    DuoSettingPreview(
                        off = demo(),
                        on = demo(),
                        scaleFrom = DuoPrefs.MIN_SIZE.toFloat() / DuoPrefs.MAX_SIZE
                    )
                }
            ) { onUpdate(settings.copy(sizePercent = it.toInt())) }
            Text(
                text = stringResource(R.string.settings_size_restart),
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                onClick = onRestart,
                enabled = settings.enabled,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.settings_restart)) }

            SettingSwitch(
                label = stringResource(R.string.settings_live_apply),
                detail = stringResource(R.string.settings_live_apply_detail),
                checked = settings.liveApply,
                enabled = settings.enabled
            ) { onUpdate(settings.copy(liveApply = it)) }

            if (settings.splitIndicators) {
                SettingSwitch(
                    label = stringResource(R.string.settings_lock_ring),
                    detail = stringResource(R.string.settings_lock_ring_detail),
                    checked = settings.lockRing,
                    enabled = settings.enabled
                ) { locked ->
                    onUpdate(
                        settings.copy(
                            lockRing = locked,
                            offsetX = if (locked) {
                                settings.offsetX
                            } else {
                                settings.offsetX.coerceIn(
                                    -DuoPrefs.MAX_UNLOCKED_RING_OFFSET,
                                    DuoPrefs.MAX_UNLOCKED_RING_OFFSET
                                )
                            }
                        )
                    )
                }
            }
            PositionEditor(
                offsetDp = settings.offsetX,
                maxOffsetDp = if (settings.splitIndicators && settings.lockRing) {
                    positionOffsetLimit(screenWidthDp, centered = true)
                } else {
                    DuoPrefs.MAX_UNLOCKED_RING_OFFSET
                },
                enabled = settings.enabled,
                centered = settings.splitIndicators && settings.lockRing,
                label = if (settings.splitIndicators) stringResource(R.string.settings_ring_position) else null,
                hint = when {
                    settings.splitIndicators && settings.lockRing ->
                        stringResource(R.string.settings_ring_position_locked_hint)
                    settings.splitIndicators -> stringResource(R.string.settings_ring_position_hint)
                    else -> null
                },
                visual = if (settings.splitIndicators) {
                    DuoPart.RING.apply(demo())
                } else {
                    null
                }
            ) { onUpdate(settings.copy(offsetX = it)) }
            if (settings.splitIndicators) {
                PositionEditor(
                    offsetDp = settings.indicatorsOffsetX,
                    maxOffsetDp = DuoPrefs.MAX_INDICATORS_OFFSET,
                    enabled = settings.enabled,
                    label = stringResource(R.string.settings_indicators_position),
                    hint = stringResource(R.string.settings_indicators_position_hint),
                    visual = DuoPart.INDICATORS.apply(demo())
                ) { onUpdate(settings.copy(indicatorsOffsetX = it)) }
            }

            LabelledSlider(
                label = "${stringResource(R.string.settings_edge_padding)} ${settings.edgePadding}%",
                value = settings.edgePadding.toFloat(),
                range = DuoPrefs.MIN_EDGE_PADDING.toFloat()..DuoPrefs.MAX_EDGE_PADDING.toFloat(),
                enabled = settings.enabled
            ) { onUpdate(settings.copy(edgePadding = it.toInt())) }
            Text(
                text = stringResource(R.string.settings_edge_padding_detail),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// -------------------------------------------------------------------- Animations
@Composable
internal fun AnimationsSection(
    settings: DuoSettings,
    onUpdate: (DuoSettings) -> Unit,
    matches: SearchGate
) {
    if (matches(
            stringResource(R.string.section_animations), stringResource(R.string.settings_animations),
            stringResource(R.string.settings_anim_speed), stringResource(R.string.settings_anim_arrival),
            stringResource(R.string.settings_anim_departure), stringResource(R.string.settings_anim_charging)
        )
    ) Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(stringResource(R.string.section_animations))
            SettingSwitch(
                label = stringResource(R.string.settings_animations),
                detail = stringResource(R.string.settings_animations_detail),
                checked = settings.animationsEnabled,
                enabled = settings.enabled
            ) { onUpdate(settings.copy(animationsEnabled = it)) }

            // The arrival is one Rive timeline at five speeds, so this is a choice, not a number.
            val speedIndex = (DuoPrefs.REVEAL_CHOICES.size - 1 -
                    DuoPrefs.REVEAL_CHOICES.indexOf(settings.revealMs).coerceAtLeast(0))
            LabelledSlider(
                label = "${stringResource(R.string.settings_anim_speed)}: ${SPEED_LABELS[speedIndex]}",
                value = speedIndex.toFloat(),
                range = 0f..(DuoPrefs.REVEAL_CHOICES.size - 1).toFloat(),
                steps = DuoPrefs.REVEAL_CHOICES.size - 2,
                enabled = settings.enabled && settings.animationsEnabled
            ) { value ->
                val index = (DuoPrefs.REVEAL_CHOICES.size - 1 - value.toInt())
                    .coerceIn(0, DuoPrefs.REVEAL_CHOICES.size - 1)
                onUpdate(settings.copy(revealMs = DuoPrefs.REVEAL_CHOICES[index]))
            }

            SettingSwitch(
                label = stringResource(R.string.settings_anim_arrival),
                detail = stringResource(R.string.settings_anim_arrival_detail),
                checked = settings.arrivalEnabled,
                enabled = settings.enabled && settings.animationsEnabled,
                preview = {
                    DuoRivePreview(fireReveal = true) { demo() }
                }
            ) { onUpdate(settings.copy(arrivalEnabled = it)) }
            SettingSwitch(
                label = stringResource(R.string.settings_anim_departure),
                detail = stringResource(R.string.settings_anim_departure_detail),
                checked = settings.departureEnabled,
                enabled = settings.enabled && settings.animationsEnabled,
                preview = {
                    DuoRivePreview(fireReveal = true) { phase ->
                        if (phase < 0.5f) demo() else demo().copy(visible = false)
                    }
                }
            ) { onUpdate(settings.copy(departureEnabled = it)) }
            SettingSwitch(
                label = stringResource(R.string.settings_anim_charging),
                detail = stringResource(R.string.settings_anim_charging_detail),
                checked = settings.chargingEnabled,
                enabled = settings.enabled && settings.animationsEnabled,
                preview = {
                    DuoRivePreview { phase -> demo(charging = phase >= 0.5f) }
                }
            ) { onUpdate(settings.copy(chargingEnabled = it)) }
        }
    }
}

// ------------------------------------------------------------------- Appearance
@Composable
internal fun AppearanceSection(
    settings: DuoSettings,
    onUpdate: (DuoSettings) -> Unit,
    matches: SearchGate
) {
    if (matches(stringResource(R.string.section_look), stringResource(R.string.settings_renderer),
            stringResource(R.string.settings_renderer_detail), stringResource(R.string.settings_clock_font),
            stringResource(R.string.settings_icon_color),
            stringResource(R.string.settings_middle_slot),
            stringResource(R.string.settings_show_airplane),
            stringResource(R.string.settings_show_dnd),
            stringResource(R.string.settings_dnd_detail),
            stringResource(R.string.settings_dnd_off),
            stringResource(R.string.settings_dnd_middle),
            stringResource(R.string.settings_dnd_dots),
            stringResource(R.string.settings_wifi_dots),
            stringResource(R.string.settings_wifi_dots_detail),
            stringResource(R.string.settings_sim))
    ) Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(stringResource(R.string.section_look))
            SettingSwitch(
                label = stringResource(R.string.settings_renderer),
                detail = stringResource(R.string.settings_renderer_detail),
                checked = settings.useRive,
                enabled = settings.enabled
            ) { onUpdate(settings.copy(useRive = it)) }
            SettingSwitch(
                label = stringResource(R.string.settings_clock_font),
                detail = stringResource(R.string.settings_clock_font_detail),
                checked = settings.systemClockFont,
                enabled = settings.enabled
            ) { onUpdate(settings.copy(systemClockFont = it)) }
            OptionPicker(
                label = stringResource(R.string.settings_icon_color),
                options = listOf(
                    "auto" to stringResource(R.string.settings_icon_color_auto),
                    "black" to stringResource(R.string.settings_icon_color_black),
                    "white" to stringResource(R.string.settings_icon_color_white)
                ),
                selectedKey = settings.iconColor,
                enabled = settings.enabled
            ) { onUpdate(settings.copy(iconColor = it)) }
            Text(
                text = stringResource(R.string.settings_middle_slot),
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = stringResource(R.string.settings_middle_slot_detail),
                style = MaterialTheme.typography.bodySmall
            )
            SettingSwitch(
                label = stringResource(R.string.settings_show_airplane),
                detail = null,
                checked = settings.showAirplane,
                enabled = settings.enabled,
                preview = {
                    DuoSettingPreview(
                        off = demo(airplane = true, showAirplane = false),
                        on = demo(airplane = true, showAirplane = true)
                    )
                }
            ) { onUpdate(settings.copy(showAirplane = it)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF101014)),
                    contentAlignment = Alignment.Center
                ) {
                    DuoRiveStill(
                        visual = dndPreview(settings.dndMode),
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OptionPicker(
                        label = stringResource(R.string.settings_show_dnd),
                        options = listOf(
                            DuoPrefs.DND_OFF to stringResource(R.string.settings_dnd_off),
                            DuoPrefs.DND_MIDDLE to stringResource(R.string.settings_dnd_middle),
                            DuoPrefs.DND_DOTS to stringResource(R.string.settings_dnd_dots)
                        ),
                        selectedKey = settings.dndMode,
                        enabled = settings.enabled
                    ) { onUpdate(settings.copy(dndMode = it)) }
                    Text(
                        text = stringResource(R.string.settings_dnd_detail),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            SettingSwitch(
                label = stringResource(R.string.settings_wifi_dots),
                detail = stringResource(R.string.settings_wifi_dots_detail),
                checked = settings.wifiDots,
                enabled = settings.enabled,
                preview = {
                    DuoSettingPreview(
                        off = demo(airplane = true, wifiLevel = 2, wifiOn = true),
                        on = demo(airplane = true, wifiLevel = 2, wifiOn = true, wifiDots = true)
                    )
                }
            ) { onUpdate(settings.copy(wifiDots = it)) }
            OptionPicker(
                label = stringResource(R.string.settings_sim),
                options = listOf(
                    "auto" to stringResource(R.string.settings_sim_auto),
                    "sim1" to stringResource(R.string.settings_sim_1),
                    "sim2" to stringResource(R.string.settings_sim_2)
                ),
                selectedKey = settings.simChoice,
                enabled = settings.enabled
            ) { onUpdate(settings.copy(simChoice = it)) }
            Text(
                text = stringResource(R.string.settings_sim_detail),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// -------------------------------------------------------------- Status bar icons
// Issue #4: an optional Shizuku path for hiding the stock icons when the module's own
// view-hiding leaves them behind. Independent of the LSPosed module, so it is safe when unused.
@Composable
internal fun IconsSection(
    settings: DuoSettings,
    onUpdate: (DuoSettings) -> Unit,
    matches: SearchGate
) {
    if (matches(stringResource(R.string.section_icons), stringResource(R.string.settings_hide_icons),
            stringResource(R.string.settings_hide_icons_detail),
            stringResource(R.string.settings_hide_other_icons))
    ) Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle(stringResource(R.string.section_icons))
            SettingSwitch(
                label = stringResource(R.string.settings_hide_other_icons),
                detail = stringResource(R.string.settings_hide_other_icons_detail),
                checked = settings.hideOtherIcons,
                enabled = settings.enabled
            ) { onUpdate(settings.copy(hideOtherIcons = it)) }
            IconHidingSetting(enabled = settings.enabled)
        }
    }
}

// ------------------------------------------------------------------ Tap actions
@Composable
internal fun ActionsSection(
    settings: DuoSettings,
    onUpdate: (DuoSettings) -> Unit,
    matches: SearchGate,
    autoExpand: Boolean
) {
    if (matches(stringResource(R.string.section_actions), stringResource(R.string.settings_tap),
            stringResource(R.string.settings_double_tap), stringResource(R.string.settings_long_press))
    ) Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle(stringResource(R.string.section_actions))
            Text(
                text = if (autoExpand) {
                    stringResource(R.string.settings_gestures_note)
                } else {
                    stringResource(R.string.settings_gestures_missing)
                },
                style = MaterialTheme.typography.bodySmall
            )
            if (settings.splitIndicators) {
                Text(
                    text = stringResource(R.string.settings_ring_actions),
                    style = MaterialTheme.typography.titleSmall
                )
            }
            ActionPicker(
                label = stringResource(R.string.settings_tap),
                selectedKey = settings.tapAction,
                enabled = autoExpand && settings.enabled
            ) { onUpdate(settings.copy(tapAction = it)) }
            ActionPicker(
                label = stringResource(R.string.settings_double_tap),
                selectedKey = settings.doubleTapAction,
                enabled = autoExpand && settings.enabled
            ) { onUpdate(settings.copy(doubleTapAction = it)) }
            ActionPicker(
                label = stringResource(R.string.settings_long_press),
                selectedKey = settings.longPressAction,
                enabled = autoExpand && settings.enabled
            ) { onUpdate(settings.copy(longPressAction = it)) }
            if (settings.splitIndicators) {
                Text(
                    text = stringResource(R.string.settings_indicator_actions),
                    style = MaterialTheme.typography.titleSmall
                )
                ActionPicker(
                    label = stringResource(R.string.settings_tap),
                    selectedKey = settings.indicatorsTapAction,
                    enabled = autoExpand && settings.enabled
                ) { onUpdate(settings.copy(indicatorsTapAction = it)) }
                ActionPicker(
                    label = stringResource(R.string.settings_double_tap),
                    selectedKey = settings.indicatorsDoubleTapAction,
                    enabled = autoExpand && settings.enabled
                ) { onUpdate(settings.copy(indicatorsDoubleTapAction = it)) }
                ActionPicker(
                    label = stringResource(R.string.settings_long_press),
                    selectedKey = settings.indicatorsLongPressAction,
                    enabled = autoExpand && settings.enabled
                ) { onUpdate(settings.copy(indicatorsLongPressAction = it)) }
            }
            Text(
                text = stringResource(R.string.settings_download_tap_note),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// ------------------------------------------------------------------------ About
@Composable
internal fun AboutSection(
    matches: SearchGate,
    status: String,
    history: List<String>,
    moduleLoadAt: Long,
    accessGranted: Boolean,
    problem: String,
    onProblemChange: (String) -> Unit,
    collecting: Boolean,
    onCollect: () -> Unit,
    logSent: Boolean,
    exporting: Boolean,
    onExport: () -> Unit,
    checkUpdates: Boolean,
    onToggleUpdates: (Boolean) -> Unit,
    checkingUpdate: Boolean,
    onCheckNow: () -> Unit,
    updateMessage: String,
    updateInfo: UpdateInfo?,
    onDownload: () -> Unit
) {
    val context = LocalContext.current
    // Sending is only allowed when the report will actually be useful: the user described the problem
    // AND there is at least one source of evidence — root, Shizuku, or the module's own pushed log
    // (the "my log shares fine" case, which needs no root).
    val described = problem.trim().isNotEmpty()
    val hasModuleLog = moduleLoadAt > 0L
    val canSend = described && (accessGranted || hasModuleLog)
    if (matches(stringResource(R.string.section_about), stringResource(R.string.settings_status),
            stringResource(R.string.settings_collect_log),
            stringResource(R.string.settings_contact_developer),
            stringResource(R.string.settings_check_updates), stringResource(R.string.settings_donate))
    ) Card(
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle(stringResource(R.string.section_about))
            // The raw module status (stage/renderer/attached/…) travels in the report, never on screen —
            // it is developer jargon, not something a user should have to read.
            Text(
                text = when {
                    moduleLoadAt > 0L ->
                        stringResource(R.string.settings_module_loaded, formatTimestamp(moduleLoadAt))
                    else -> stringResource(R.string.settings_module_never)
                },
                style = MaterialTheme.typography.bodySmall
            )
            // Let the user say what went wrong; it travels with the log so a fix can start from the
            // description rather than from a guess. Then one button packs status + logs and sends them.
            OutlinedTextField(
                value = problem,
                onValueChange = onProblemChange,
                label = { Text(stringResource(R.string.settings_problem)) },
                placeholder = { Text(stringResource(R.string.settings_problem_hint)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            if (!canSend) {
                Text(
                    text = stringResource(
                        if (!described) R.string.settings_send_need_description
                        else R.string.settings_send_need_access
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Button(
                onClick = onCollect,
                enabled = !collecting && canSend,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(
                        if (collecting) R.string.settings_collecting else R.string.settings_collect_log
                    )
                )
            }
            // Direct line to the developer's Telegram, for a question that is not a bug report.
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_CHAT_URL)))
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.settings_contact_developer))
            }
            if (logSent) {
                Text(
                    text = stringResource(R.string.settings_log_sent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
            // Optional elevated capture for the log: root (asked for explicitly) or Shizuku. With
            // neither, the report still carries the module's own log and the device facts.
            RootAccessSetting()
            // Export: save the same report anywhere with the system file picker, independent of
            // Telegram or a network. The escape hatch when the upload path is not available.
            OutlinedButton(
                onClick = onExport,
                enabled = !exporting && !collecting && canSend,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(
                        if (exporting) R.string.settings_collecting else R.string.settings_export_log
                    )
                )
            }
            // ----- Updates -----
            SettingSwitch(
                label = stringResource(R.string.settings_check_updates),
                detail = stringResource(R.string.settings_check_updates_detail),
                checked = checkUpdates
            ) { on -> onToggleUpdates(on) }
            OutlinedButton(
                onClick = onCheckNow,
                enabled = !checkingUpdate,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(
                        if (checkingUpdate) R.string.settings_checking else R.string.settings_check_now
                    )
                )
            }
            if (updateMessage.isNotEmpty()) {
                Text(updateMessage, style = MaterialTheme.typography.bodySmall)
            }
            updateInfo?.let { info ->
                if (info.apkUrl.isNotBlank()) {
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.settings_update_download))
                    }
                } else {
                    Text(
                        stringResource(R.string.settings_update_no_apk),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Button(
                onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DONATE_URL))) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_paypal),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_donate))
            }
            Text(
                text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * FR-09: the fixed snapshot the setting demos morph from and to.
 *
 * One battery level for every demo, so the rows are comparable and the only thing that changes between
 * the two ends is the setting being demonstrated.
 */
private fun demo(
    level: Int = DEMO_LEVEL,
    charging: Boolean = false,
    showPercent: Boolean = true,
    airplane: Boolean = false,
    dnd: Boolean = false,
    percentHeight: Int = DuoPrefs.DEFAULT_PERCENT_HEIGHT,
    wifiLevel: Int = 3,
    wifiOn: Boolean = true,
    wifiDots: Boolean = false,
    showAirplane: Boolean = true,
    showDnd: Boolean = true,
    dndDots: Boolean = false,
    showCharging: Boolean = true,
    cellLevel: Int = 4
) = DuoMapping.visual(
    level = level,
    charging = charging,
    saver = false,
    showPercent = showPercent,
    wifiLevel = wifiLevel,
    cellLevel = cellLevel,
    airplane = airplane,
    dnd = dnd,
    wifiOn = wifiOn,
    percentHeight = percentHeight,
    wifiDots = wifiDots,
    showAirplane = showAirplane,
    showDnd = showDnd,
    showCharging = showCharging,
    dndDots = dndDots
)

/** The Do Not Disturb row, drawn as if that status is on, in the mode the user picked. */
private fun dndPreview(mode: String) = when (mode) {
    DuoPrefs.DND_DOTS -> demo(dnd = true, showDnd = false, dndDots = true, cellLevel = 2)
    DuoPrefs.DND_OFF -> demo(dnd = true, showDnd = false)
    else -> demo(dnd = true, showDnd = true)
}

/** FR-09: a level that shows a clear half-full ring rather than an empty or full one. */
private const val DEMO_LEVEL = 40

/** FR-25: the five arrival speeds, slowest first, matching [DuoPrefs.REVEAL_CHOICES] reversed. */
private val SPEED_LABELS = listOf("Slow", "Relaxed", "Normal", "Brisk", "Fast")
