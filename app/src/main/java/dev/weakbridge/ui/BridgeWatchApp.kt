package dev.weakbridge.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.*
import dev.weakbridge.BridgeApplication
import dev.weakbridge.BridgeSettings
import dev.weakbridge.BuildConfig
import dev.weakbridge.R
import dev.weakbridge.ble.BridgeStatus
import dev.weakbridge.ble.ConnectionState
import dev.weakbridge.service.BridgeForegroundService as Bridge
import kotlinx.coroutines.launch

private val Accent = Color(0xFF7BDDFF)
private val Good = Color(0xFF8DE2BB)
private val Muted = Color(0xFFAAB5C6)
private val Panel = Color(0xFF151B24)
private val Danger = Color(0xFFFFA49A)

private enum class Page(@StringRes val title: Int) {
    HOME(R.string.ui_connection),
    CONNECTION(R.string.ui_connection),
    SETTINGS(R.string.ui_settings),
    NOTIFICATIONS(R.string.ui_notifications),
    APPS(R.string.ui_app_filters),
    CATEGORIES(R.string.ui_categories),
    QUIET(R.string.ui_quiet_hours),
    QUIET_START(R.string.ui_quiet_start),
    QUIET_END(R.string.ui_quiet_end),
    COOLDOWN(R.string.ui_cooldown),
    DIAGNOSTICS(R.string.ui_diagnostics),
    HELP(R.string.ui_help),
}

private data class PreferencesSnapshot(
    val address: String?,
    val enabled: Boolean,
    val autoReconnect: Boolean,
    val media: Boolean,
    val lowPower: Boolean,
    val diagnostics: Boolean,
    val notificationsGrouped: Boolean,
    val hideNotificationContent: Boolean,
    val quietHoursEnabled: Boolean,
    val quietStartHour: Int,
    val quietEndHour: Int,
    val alertCooldownSeconds: Int,
    val categories: List<Boolean>,
    val allowedApps: Map<String, Boolean>,
) {
    constructor(
        settings: BridgeSettings,
        appIds: List<String>,
    ) : this(
        settings.address,
        settings.enabled,
        settings.autoReconnect,
        settings.media,
        settings.lowPower,
        settings.diagnostics,
        settings.notificationsGrouped,
        settings.hideNotificationContent,
        settings.quietHoursEnabled,
        settings.quietStartHour,
        settings.quietEndHour,
        settings.alertCooldownSeconds,
        (0..11).map(settings::category),
        appIds.associateWith(settings::appAllowed),
    )
}

@Composable
internal fun BridgeWatchApp(
    app: BridgeApplication,
    bluetoothGranted: Boolean,
    notificationGranted: Boolean,
    onPermissions: () -> Unit,
    onAppSettings: () -> Unit,
    onAction: (String, String?) -> Unit,
) {
    MaterialTheme(
        colors =
            Colors(
                primary = Accent,
                primaryVariant = Color(0xFF174C66),
                secondary = Good,
                secondaryVariant = Color(0xFF21543D),
                background = Color.Black,
                surface = Panel,
                onPrimary = Color(0xFF002738),
                onSecondary = Color(0xFF073623),
                onBackground = Color.White,
                onSurface = Color.White,
                error = Danger,
                onError = Color(0xFF390B06),
            )
    ) {
        val status by app.status.collectAsStateWithLifecycle()
        val devices by app.devices.collectAsStateWithLifecycle()
        val deviceListTitle by app.deviceListTitle.collectAsStateWithLifecycle()
        val observedApps by app.observedApps.collectAsStateWithLifecycle()
        val categories = stringArrayResource(R.array.ui_notification_categories)
        var pages by rememberSaveable { mutableStateOf(listOf(Page.HOME.name)) }
        val page = Page.valueOf(pages.last())
        var confirm by rememberSaveable { mutableStateOf<String?>(null) }
        var settingsRevision by remember { mutableIntStateOf(0) }
        var snapshot by remember { mutableStateOf("") }
        var copied by remember { mutableStateOf(false) }
        val clipboard = LocalClipboardManager.current
        val settings = app.settings
        // Immutable values keep lazy rows up to date without replacing their scroll state.
        val preferences =
            remember(settingsRevision, status, devices, observedApps) {
                PreferencesSnapshot(settings, observedApps.map { it.id })
            }
        val go: (Page) -> Unit = { target ->
            copied = false
            val previous = pages.indexOf(target.name)
            pages = if (previous >= 0) pages.take(previous + 1) else pages + target.name
            if (target == Page.CONNECTION) onAction(Bridge.BONDS, null)
            if (target == Page.DIAGNOSTICS) {
                snapshot = Bridge.active?.diagnosticText().orEmpty()
            }
        }
        val back: () -> Unit = {
            if (confirm != null) confirm = null else if (pages.size > 1) pages = pages.dropLast(1)
        }
        val changed: () -> Unit = {
            settingsRevision++
            if (Bridge.active != null) onAction(Bridge.CONFIG, null)
        }
        BackHandler(page != Page.HOME || confirm != null, onBack = back)
        val savedPages = rememberSaveableStateHolder()
        SwipeToDismissBox(
            onDismissed = back,
            hasBackground = page != Page.HOME || confirm != null,
            backgroundKey = "background",
            contentKey = "${page.name}:$confirm",
            modifier = Modifier.fillMaxSize(),
        ) { background ->
            if (background) {
                Box(
                    Modifier.fillMaxSize().background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_ui_back),
                        stringResource(R.string.ui_back),
                        tint = Accent,
                    )
                }
            } else {
                savedPages.SaveableStateProvider("${page.name}:$confirm") {
                    val initialIndex =
                        when (page) {
                            Page.QUIET_START -> preferences.quietStartHour
                            Page.QUIET_END -> preferences.quietEndHour
                            else -> 0
                        }
                    WatchList(initialIndex) {
                        item {
                            PageTitle(
                                if (page == Page.HOME) "WeakBridge" else stringResource(page.title)
                            )
                        }
                        if (confirm != null) {
                            item {
                                Note(
                                    stringResource(
                                        if (confirm == Bridge.FORGET) R.string.ui_forget_confirm
                                        else R.string.ui_disconnect_confirm
                                    )
                                )
                            }
                            item {
                                Action(
                                    stringResource(R.string.ui_confirm),
                                    primary = true,
                                    danger = true,
                                ) {
                                    onAction(confirm!!, null)
                                    confirm = null
                                    go(Page.HOME)
                                }
                            }
                            item { Action(stringResource(R.string.ui_cancel), onClick = back) }
                        } else
                            when (page) {
                                Page.HOME -> {
                                    item { HomeStatus(status, compact = true) }
                                    if (status.state == ConnectionState.ERROR)
                                        item { Note(status.detail) }
                                    item {
                                        ServicesCard(
                                            status,
                                            preferences.media,
                                            bluetoothGranted,
                                            notificationGranted,
                                        )
                                    }
                                    if (!bluetoothGranted || !notificationGranted) {
                                        item {
                                            Action(
                                                stringResource(R.string.ui_permissions),
                                                R.drawable.ic_ui_shield,
                                                primary = true,
                                                onClick = onPermissions,
                                            )
                                        }
                                        item {
                                            Action(
                                                stringResource(R.string.ui_permission_settings),
                                                onClick = onAppSettings,
                                            )
                                        }
                                    }
                                    if (
                                        status.state == ConnectionState.DISCONNECTED ||
                                            status.state == ConnectionState.ERROR
                                    ) {
                                        item {
                                            Action(
                                                stringResource(
                                                    if (preferences.address == null)
                                                        R.string.ui_connect_iphone
                                                    else R.string.ui_reconnect
                                                ),
                                                R.drawable.ic_bridge,
                                                primary = true,
                                            ) {
                                                if (preferences.address == null) go(Page.CONNECTION)
                                                else onAction(Bridge.CONNECT, null)
                                            }
                                        }
                                    }
                                    item {
                                        Action(
                                            stringResource(R.string.ui_connection),
                                            R.drawable.ic_bridge,
                                        ) {
                                            go(Page.CONNECTION)
                                        }
                                    }
                                    item {
                                        Action(
                                            stringResource(R.string.ui_notifications),
                                            R.drawable.ic_ui_bell,
                                        ) {
                                            go(Page.NOTIFICATIONS)
                                        }
                                    }
                                    item {
                                        Action(
                                            stringResource(R.string.ui_settings),
                                            R.drawable.ic_ui_settings,
                                        ) {
                                            go(Page.SETTINGS)
                                        }
                                    }
                                    if (preferences.address == null) {
                                        item {
                                            Action(
                                                stringResource(R.string.ui_help),
                                                R.drawable.ic_ui_help,
                                            ) {
                                                go(Page.HELP)
                                            }
                                        }
                                    }
                                    item { Note(stringResource(R.string.ui_standalone)) }
                                }
                                Page.CONNECTION -> {
                                    item {
                                        HomeStatus(status, compact = preferences.address != null)
                                    }
                                    item { Note(status.detail) }
                                    item { Section(stringResource(R.string.ui_saved_device)) }
                                    item {
                                        Note(
                                            preferences.address?.let { address ->
                                                devices.firstOrNull { it.first == address }?.second
                                                    ?: stringResource(R.string.ui_saved_device)
                                            } ?: stringResource(R.string.ui_no_saved_device)
                                        )
                                    }
                                    if (!bluetoothGranted || !notificationGranted) {
                                        item { Note(stringResource(R.string.ui_permissions_body)) }
                                        item {
                                            Action(
                                                stringResource(R.string.ui_permissions),
                                                R.drawable.ic_ui_shield,
                                                primary = true,
                                                onClick = onPermissions,
                                            )
                                        }
                                        item {
                                            Action(
                                                stringResource(R.string.ui_permission_settings),
                                                onClick = onAppSettings,
                                            )
                                        }
                                    }
                                    if (preferences.address != null)
                                        item {
                                            Action(
                                                stringResource(R.string.ui_reconnect),
                                                R.drawable.ic_bridge,
                                                primary = true,
                                            ) {
                                                onAction(Bridge.CONNECT, null)
                                            }
                                        }
                                    item {
                                        Action(
                                            stringResource(R.string.ui_connect_iphone),
                                            R.drawable.ic_bridge,
                                            primary = preferences.address == null,
                                        ) {
                                            onAction(Bridge.PAIR, null)
                                        }
                                    }
                                    item { Note(stringResource(R.string.ui_pair_window)) }
                                    item { Section(deviceListTitle) }
                                    item { Note(stringResource(R.string.ui_devices_help)) }
                                    if (devices.isEmpty())
                                        item { Note(stringResource(R.string.ui_no_devices)) }
                                    items(devices, key = { it.first }) { (address, name) ->
                                        Action(
                                            label = name,
                                            icon =
                                                if (address == preferences.address)
                                                    R.drawable.ic_ui_check
                                                else R.drawable.ic_bridge,
                                            secondary = address.takeLast(5),
                                        ) {
                                            onAction(Bridge.CONNECT, address)
                                        }
                                    }
                                    item {
                                        Action(stringResource(R.string.ui_refresh_devices)) {
                                            onAction(Bridge.BONDS, null)
                                        }
                                    }
                                    item {
                                        Action(
                                            stringResource(R.string.ui_help),
                                            R.drawable.ic_ui_help,
                                        ) {
                                            go(Page.HELP)
                                        }
                                    }
                                    item { Section(stringResource(R.string.ui_scan)) }
                                    item { Note(stringResource(R.string.ui_scan_note)) }
                                    item {
                                        Action(stringResource(R.string.ui_scan)) {
                                            onAction(Bridge.SCAN, null)
                                        }
                                    }
                                    if (preferences.address != null)
                                        item {
                                            Action(
                                                stringResource(R.string.ui_forget),
                                                danger = true,
                                            ) {
                                                confirm = Bridge.FORGET
                                            }
                                        }
                                    if (
                                        preferences.enabled ||
                                            status.state != ConnectionState.DISCONNECTED
                                    )
                                        item {
                                            Action(
                                                stringResource(R.string.ui_disconnect),
                                                danger = true,
                                            ) {
                                                confirm = Bridge.DISCONNECT
                                            }
                                        }
                                }
                                Page.SETTINGS -> {
                                    item {
                                        Setting(
                                            stringResource(R.string.ui_auto_reconnect),
                                            stringResource(R.string.ui_auto_reconnect_body),
                                            preferences.autoReconnect,
                                        ) {
                                            settings.autoReconnect = it
                                            changed()
                                        }
                                    }
                                    item {
                                        Setting(
                                            stringResource(R.string.ui_media_setting),
                                            stringResource(R.string.ui_media_setting_body),
                                            preferences.media,
                                        ) {
                                            settings.media = it
                                            changed()
                                        }
                                    }
                                    item {
                                        Setting(
                                            stringResource(R.string.ui_low_power),
                                            stringResource(R.string.ui_low_power_body),
                                            preferences.lowPower,
                                        ) {
                                            settings.lowPower = it
                                            changed()
                                        }
                                    }
                                    item {
                                        Action(
                                            stringResource(R.string.ui_notifications),
                                            R.drawable.ic_ui_bell,
                                        ) {
                                            go(Page.NOTIFICATIONS)
                                        }
                                    }
                                    item {
                                        Action(
                                            stringResource(R.string.ui_diagnostics),
                                            R.drawable.ic_ui_chart,
                                        ) {
                                            go(Page.DIAGNOSTICS)
                                        }
                                    }
                                    item {
                                        Action(
                                            stringResource(R.string.ui_help),
                                            R.drawable.ic_ui_help,
                                        ) {
                                            go(Page.HELP)
                                        }
                                    }
                                    item {
                                        Note(
                                            stringResource(
                                                R.string.ui_version,
                                                BuildConfig.VERSION_NAME,
                                            )
                                        )
                                    }
                                }
                                Page.NOTIFICATIONS -> {
                                    item {
                                        Setting(
                                            stringResource(R.string.ui_group_notifications),
                                            stringResource(R.string.ui_group_notifications_body),
                                            preferences.notificationsGrouped,
                                        ) {
                                            settings.notificationsGrouped = it
                                            changed()
                                        }
                                    }
                                    item {
                                        Setting(
                                            stringResource(R.string.ui_hide_content),
                                            stringResource(R.string.ui_hide_content_body),
                                            preferences.hideNotificationContent,
                                        ) {
                                            settings.hideNotificationContent = it
                                            changed()
                                        }
                                    }
                                    item {
                                        Action(
                                            stringResource(R.string.ui_app_filters),
                                            R.drawable.ic_ui_apps,
                                            secondary = stringResource(R.string.ui_app_filters_body),
                                        ) {
                                            go(Page.APPS)
                                        }
                                    }
                                    item {
                                        Action(
                                            stringResource(R.string.ui_categories),
                                            R.drawable.ic_ui_bell,
                                        ) {
                                            go(Page.CATEGORIES)
                                        }
                                    }
                                    item {
                                        Action(
                                            stringResource(R.string.ui_quiet_hours),
                                            R.drawable.ic_ui_moon,
                                            secondary =
                                                if (preferences.quietHoursEnabled)
                                                    "${hour(preferences.quietStartHour)} – ${hour(preferences.quietEndHour)}"
                                                else stringResource(R.string.ui_disabled),
                                        ) {
                                            go(Page.QUIET)
                                        }
                                    }
                                    item {
                                        Action(
                                            stringResource(R.string.ui_cooldown),
                                            R.drawable.ic_ui_bell,
                                            secondary = cooldown(preferences.alertCooldownSeconds),
                                        ) {
                                            go(Page.COOLDOWN)
                                        }
                                    }
                                }
                                Page.APPS -> {
                                    item { Note(stringResource(R.string.ui_app_filters_body)) }
                                    if (observedApps.isEmpty())
                                        item { Note(stringResource(R.string.ui_no_apps)) }
                                    items(observedApps, key = { it.id }) { appInfo ->
                                        Setting(
                                            appInfo.name,
                                            appInfo.id,
                                            preferences.allowedApps[appInfo.id] != false,
                                        ) {
                                            settings.setAppAllowed(appInfo.id, it)
                                            changed()
                                        }
                                    }
                                }
                                Page.CATEGORIES -> {
                                    item { Note(stringResource(R.string.ui_categories_body)) }
                                    // Array order matches the category IDs specified by ANCS.
                                    items(categories.indices.toList(), key = { it }) { index ->
                                        Setting(
                                            categories[index],
                                            null,
                                            preferences.categories[index],
                                        ) {
                                            settings.setCategory(index, it)
                                            changed()
                                        }
                                    }
                                }
                                Page.QUIET -> {
                                    item {
                                        Setting(
                                            stringResource(R.string.ui_quiet_hours),
                                            stringResource(R.string.ui_quiet_hours_body),
                                            preferences.quietHoursEnabled,
                                        ) {
                                            settings.quietHoursEnabled = it
                                            changed()
                                        }
                                    }
                                    item {
                                        Action(
                                            stringResource(R.string.ui_quiet_start),
                                            secondary = hour(preferences.quietStartHour),
                                        ) {
                                            go(Page.QUIET_START)
                                        }
                                    }
                                    item {
                                        Action(
                                            stringResource(R.string.ui_quiet_end),
                                            secondary = hour(preferences.quietEndHour),
                                        ) {
                                            go(Page.QUIET_END)
                                        }
                                    }
                                    if (preferences.quietStartHour == preferences.quietEndHour)
                                        item { Note(stringResource(R.string.ui_quiet_equal)) }
                                }
                                Page.QUIET_START,
                                Page.QUIET_END -> {
                                    item { Note(stringResource(R.string.ui_choose_hour)) }
                                    items((0..23).toList()) { value ->
                                        val selected =
                                            value ==
                                                if (page == Page.QUIET_START)
                                                    preferences.quietStartHour
                                                else preferences.quietEndHour
                                        Action(
                                            hour(value),
                                            if (selected) R.drawable.ic_ui_check else null,
                                        ) {
                                            if (page == Page.QUIET_START)
                                                settings.quietStartHour = value
                                            else settings.quietEndHour = value
                                            changed()
                                            go(Page.QUIET)
                                        }
                                    }
                                }
                                Page.COOLDOWN -> {
                                    item { Note(stringResource(R.string.ui_cooldown_body)) }
                                    items(listOf(0, 3, 10, 30)) { seconds ->
                                        Action(
                                            cooldown(seconds),
                                            if (seconds == preferences.alertCooldownSeconds)
                                                R.drawable.ic_ui_check
                                            else null,
                                        ) {
                                            settings.alertCooldownSeconds = seconds
                                            changed()
                                            go(Page.NOTIFICATIONS)
                                        }
                                    }
                                }
                                Page.DIAGNOSTICS -> {
                                    item {
                                        Setting(
                                            stringResource(R.string.ui_diagnostics_toggle),
                                            stringResource(R.string.ui_diagnostics_body),
                                            preferences.diagnostics,
                                        ) {
                                            settings.diagnostics = it
                                            changed()
                                        }
                                    }
                                    item { Section(stringResource(R.string.ui_latest_event)) }
                                    item { Note(status.detail) }
                                    item {
                                        Note(
                                            snapshot.ifEmpty {
                                                stringResource(R.string.ui_service_stopped)
                                            },
                                            align = TextAlign.Start,
                                        )
                                    }
                                    item {
                                        Action(stringResource(R.string.ui_clear_counters)) {
                                            Bridge.active?.clearDiagnostics()
                                            snapshot = Bridge.active?.diagnosticText().orEmpty()
                                            copied = false
                                        }
                                    }
                                    item {
                                        Action(
                                            stringResource(
                                                if (copied) R.string.ui_copied
                                                else R.string.ui_copy_diagnostics
                                            )
                                        ) {
                                            clipboard.setText(
                                                AnnotatedString(
                                                    "WeakBridge ${BuildConfig.VERSION_NAME}\n${status.state}\n${status.detail}\n$snapshot"
                                                )
                                            )
                                            copied = true
                                        }
                                    }
                                }
                                Page.HELP -> {
                                    helpCard(
                                        R.string.ui_help_step_one,
                                        R.string.ui_help_step_one_body,
                                    )
                                    item {
                                        Action(
                                            stringResource(R.string.ui_permissions),
                                            R.drawable.ic_ui_shield,
                                            onClick = onPermissions,
                                        )
                                    }
                                    item {
                                        Action(
                                            stringResource(R.string.ui_permission_settings),
                                            onClick = onAppSettings,
                                        )
                                    }
                                    helpCard(
                                        R.string.ui_help_step_two,
                                        R.string.ui_help_step_two_body,
                                    )
                                    item {
                                        Action(
                                            stringResource(R.string.ui_connect_iphone),
                                            R.drawable.ic_bridge,
                                            primary = true,
                                        ) {
                                            onAction(Bridge.PAIR, null)
                                        }
                                    }
                                    helpCard(
                                        R.string.ui_help_step_three,
                                        R.string.ui_help_step_three_body,
                                    )
                                    item {
                                        Action(stringResource(R.string.ui_saved_devices)) {
                                            go(Page.CONNECTION)
                                        }
                                    }
                                    helpCard(
                                        R.string.ui_help_notifications,
                                        R.string.ui_help_notifications_body,
                                    )
                                    helpCard(R.string.ui_help_music, R.string.ui_help_music_body)
                                }
                            }
                        if (page != Page.HOME && confirm == null)
                            item {
                                Action(
                                    stringResource(R.string.ui_back),
                                    R.drawable.ic_ui_back,
                                    onClick = back,
                                )
                            }
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchList(initialIndex: Int, content: LazyListScope.() -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LazyColumn(
            state = state,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 28.dp),
            modifier =
                Modifier.fillMaxSize()
                    .onRotaryScrollEvent { event ->
                        scope.launch { state.scrollBy(event.verticalScrollPixels) }
                        true
                    }
                    .focusRequester(focus)
                    .focusable(),
            content = content,
        )
        PositionIndicator(lazyListState = state)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
}

@Composable
private fun HomeStatus(status: BridgeStatus, compact: Boolean = false) {
    val ready = status.state == ConnectionState.CONNECTED
    val color = if (ready) Good else if (status.state == ConnectionState.ERROR) Danger else Accent
    val title =
        when (status.state) {
            ConnectionState.CONNECTED -> R.string.ui_connected
            ConnectionState.DISCONNECTED -> R.string.ui_disconnected
            ConnectionState.BONDING -> R.string.ui_pairing
            ConnectionState.SCANNING -> R.string.ui_scanning
            ConnectionState.ERROR -> R.string.ui_error
            else -> R.string.ui_connecting
        }
    val body =
        when (status.state) {
            ConnectionState.CONNECTED -> R.string.ui_ready_body
            ConnectionState.DISCONNECTED -> R.string.ui_disconnected_body
            ConnectionState.BONDING -> R.string.ui_pairing_body
            ConnectionState.SCANNING -> R.string.ui_scanning_body
            ConnectionState.ERROR -> R.string.ui_error_body
            else -> R.string.ui_connecting_body
        }
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Panel)
                .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(Modifier.size(7.dp).background(color, CircleShape))
            Text(stringResource(title), color = color, style = MaterialTheme.typography.title3)
        }
        if (!compact)
            Text(stringResource(body), style = MaterialTheme.typography.caption2, color = Muted)
    }
}

@Composable
private fun ServicesCard(
    status: BridgeStatus,
    mediaEnabled: Boolean,
    bluetooth: Boolean,
    notifications: Boolean,
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Panel)
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ServiceRow(
            R.drawable.ic_ui_bell,
            R.string.ui_notifications,
            status,
            status.notifications,
            true,
            bluetooth && notifications,
        )
        ServiceRow(
            R.drawable.ic_ui_music,
            R.string.ui_music,
            status,
            status.media,
            mediaEnabled,
            bluetooth,
        )
    }
}

@Composable
private fun ServiceRow(
    @DrawableRes icon: Int,
    @StringRes title: Int,
    status: BridgeStatus,
    subscribed: Boolean,
    enabled: Boolean,
    permission: Boolean,
) {
    val text =
        when {
            !enabled -> R.string.ui_disabled
            !permission -> R.string.ui_service_permission
            subscribed -> R.string.ui_service_ready
            status.state == ConnectionState.CONNECTED -> R.string.ui_service_missing
            status.state in
                setOf(
                    ConnectionState.DISCOVERING_SERVICES,
                    ConnectionState.AUTHORIZING,
                    ConnectionState.SUBSCRIBING,
                ) -> R.string.ui_service_setup
            else -> R.string.ui_service_waiting
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            painterResource(icon),
            null,
            modifier = Modifier.size(20.dp),
            tint = if (subscribed && permission && enabled) Good else Muted,
        )
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.button)
            Text(stringResource(text), style = MaterialTheme.typography.caption2, color = Muted)
        }
    }
}

@Composable
private fun PageTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.title2,
        textAlign = TextAlign.Center,
        modifier =
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).semantics {
                heading()
            },
    )
}

@Composable
private fun Section(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.caption1,
        color = Accent,
        textAlign = TextAlign.Center,
        modifier =
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp).semantics { heading() },
    )
}

@Composable
private fun Note(label: String, align: TextAlign = TextAlign.Center) {
    Text(
        label,
        color = Muted,
        style = MaterialTheme.typography.caption2,
        textAlign = align,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun Action(
    label: String,
    @DrawableRes icon: Int? = null,
    primary: Boolean = false,
    danger: Boolean = false,
    secondary: String? = null,
    onClick: () -> Unit,
) {
    val background =
        if (primary) {
            if (danger) Danger else Accent
        } else Panel
    val foreground = if (primary) Color(0xFF002738) else if (danger) Danger else Color.White
    Chip(
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.button) },
        secondaryLabel =
            secondary?.let {
                {
                    Text(
                        it,
                        style = MaterialTheme.typography.caption2,
                        color = if (primary) foreground else Muted,
                    )
                }
            },
        icon =
            icon?.let {
                {
                    Icon(
                        painterResource(it),
                        null,
                        tint = foreground,
                        modifier = Modifier.size(22.dp),
                    )
                }
            },
        colors = ChipDefaults.chipColors(backgroundColor = background, contentColor = foreground),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
    )
}

@Composable
private fun Setting(
    label: String,
    description: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel)) {
        ToggleChip(
            checked = checked,
            onCheckedChange = onChange,
            label = { Text(label, style = MaterialTheme.typography.button) },
            toggleControl = { Switch(checked = checked) },
            colors =
                ToggleChipDefaults.toggleChipColors(
                    checkedStartBackgroundColor = Panel,
                    checkedEndBackgroundColor = Panel,
                    uncheckedStartBackgroundColor = Panel,
                    uncheckedEndBackgroundColor = Panel,
                ),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        )
        if (description != null) {
            Text(
                description,
                style = MaterialTheme.typography.caption2,
                color = Muted,
                modifier =
                    Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            )
        }
    }
}

private fun LazyListScope.helpCard(@StringRes title: Int, @StringRes body: Int) {
    item { Section(stringResource(title)) }
    item { Note(stringResource(body)) }
}

@Composable
private fun cooldown(seconds: Int): String =
    if (seconds == 0) stringResource(R.string.ui_no_cooldown)
    else stringResource(R.string.ui_seconds, seconds)

private fun hour(value: Int) = "%02d:00".format(value)
