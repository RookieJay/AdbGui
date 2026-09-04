package com.adbgui.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.AlertDialog
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import com.adbgui.core.domain.DeviceGroupBy
import com.adbgui.core.domain.DeviceView
import com.adbgui.desktop.ui.i18n.Strings

@Composable
fun DeviceListPane(
    vm: DeviceListViewModel,
    modifier: Modifier = Modifier,
    settingsVm: SettingsViewModel? = null,
    selected: String? = null,
    onSelect: (DeviceView) -> Unit = {},
    onReconnect: (String, Int) -> Unit = { _, _ -> },
    onOpenConnect: () -> Unit = {},
) {
    var showPair by remember { mutableStateOf(false) }
    val items by vm.items.collectAsState(initial = emptyList())
    val allDevices by vm.devices.collectAsState()
    val error by vm.error.collectAsState()
    val busy by vm.busy.collectAsState()
    val settings = settingsVm?.settings?.collectAsState()?.value ?: com.adbgui.core.settings.Settings()
    var groupMenuOpen by remember { mutableStateOf(false) }
    // Tag pending deletion (null = no dialog). Set by a tag-group header's delete button;
    // confirming clears that tag from every device bearing it in one shot — saves unsetting it
    // per-device.
    var tagToDelete by remember { mutableStateOf<String?>(null) }
    // Collapsed groups: transient (per session), default expanded. Keyed by group key so it
    // survives a settings round-trip as long as the same group key reappears.
    val collapsed = remember { mutableStateMapOf<String, Boolean>() }
    // Existing tags across all known devices — offered as quick-pick chips when setting a tag.
    val existingTags = remember(allDevices) {
        allDevices.mapNotNull { it.tag }.filter { it.isNotBlank() }.distinct()
    }
    val groupKeys = remember(items) { items.filterIsInstance<DeviceListItem.Header>().map { it.key } }
    val hasGroups = groupKeys.isNotEmpty()

    Surface(modifier = modifier, color = MaterialTheme.colors.surface) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(Strings.t("devices"), style = MaterialTheme.typography.subtitle1)
                Spacer(Modifier.width(8.dp))
                // Group-by selector. A compact dropdown; selection persists via settings.json.
                Box {
                    TextButton(onClick = { groupMenuOpen = true }) {
                        Text(
                            Strings.t("group_by") + ": " + groupByLabel(settings.deviceGroupBy),
                            style = MaterialTheme.typography.caption,
                        )
                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = groupMenuOpen, onDismissRequest = { groupMenuOpen = false }) {
                        DeviceGroupBy.entries.forEach { mode ->
                            DropdownMenuItem(onClick = {
                                groupMenuOpen = false
                                settingsVm?.setDeviceGroupBy(mode)
                            }) { Text(groupByLabel(mode)) }
                        }
                    }
                }
                // Expand-all / collapse-all. Only when grouped (NONE has no headers to toggle).
                if (hasGroups) {
                    IconButton(onClick = { collapsed.clear() }, enabled = groupKeys.any { collapsed[it] == true }) {
                        Icon(Icons.Filled.UnfoldMore, contentDescription = Strings.t("expand_all"))
                    }
                    IconButton(onClick = { groupKeys.forEach { collapsed[it] = true } }, enabled = groupKeys.any { collapsed[it] != true }) {
                        Icon(Icons.Filled.UnfoldLess, contentDescription = Strings.t("collapse_all"))
                    }
                }
                Spacer(Modifier.weight(1f))
                if (busy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp).padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                }
                TextButton(onClick = { showPair = true }) { Text(Strings.t("pair")) }
                IconButton(onClick = onOpenConnect) {
                    Icon(Icons.Filled.Add, contentDescription = Strings.t("connect"))
                }
            }
            Divider()

            // Device list (or empty-state guidance card when no devices)
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (items.isEmpty()) {
                    EmptyState(
                        title = Strings.t("no_device_hint"),
                        actionLabel = Strings.t("connect_first_device"),
                        onAction = onOpenConnect,
                        secondaryActionLabel = Strings.t("pair"),
                        onSecondaryAction = { showPair = true },
                    )
                } else {
                    // Hide device rows whose group is collapsed; headers stay so the user can
                    // expand again. (NONE mode has no headers and groupKey="" is never toggled,
                    // so all rows stay visible.)
                    val visibleItems = items.filterNot {
                        it is DeviceListItem.Device && collapsed[it.groupKey] == true
                    }
                    val listState = rememberLazyListState()
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                    ) {
                        items(visibleItems, key = { item -> item.key() }) { item ->
                            when (item) {
                                is DeviceListItem.Header -> {
                                    // Tag groups (but not the "tag_none" bucket) can be deleted
                                    // outright — clears that tag from every device bearing it.
                                    val canDeleteTag =
                                        settings.deviceGroupBy == DeviceGroupBy.TAG && item.key != "tag_none"
                                    GroupHeaderRow(
                                        header = item,
                                        collapsed = collapsed[item.key] == true,
                                        onToggle = {
                                            val now = collapsed[item.key] == true
                                            collapsed[item.key] = !now
                                        },
                                        onDeleteTag = if (canDeleteTag) {
                                            { tagToDelete = item.key }
                                        } else null,
                                    )
                                }
                                is DeviceListItem.Device -> DeviceRow(
                                    device = item.view,
                                    selected = selected,
                                    existingTags = existingTags,
                                    onRename = { newAlias -> vm.setAlias(item.view.serial, newAlias) },
                                    onSetTag = { newTag -> vm.setTag(item.view.serial, newTag) },
                                    onForget = { vm.forget(item.view.serial) },
                                    onDisconnect = { vm.disconnect(item.view.serial) },
                                    onSelect = { onSelect(item.view) },
                                    onReconnect = onReconnect,
                                )
                            }
                        }
                    }
                    // Right-edge scrollbar — same pattern as LogcatScreen/CdpDebugScreen so the
                    // user can drag/see position when the list is longer than the pane.
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(listState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }

            // Inline error — dismissible so a stale-port hint (which can be long) doesn't
            // permanently block the list. Cleared via vm.clearError(); also auto-cleared on
            // the next connect/reconnect attempt.
            error?.let { InlineMessageBanner(it, MessageKind.Error, onDismiss = { vm.clearError() }) }
        }
    }

    if (showPair) {
        PairDialog(
            vm = vm,
            onDismiss = { showPair = false; vm.clearError() },
        )
    }

    tagToDelete?.let { tag ->
        AlertDialog(
            onDismissRequest = { tagToDelete = null },
            title = { Text(Strings.t("delete_tag_confirm_title")) },
            text = { Text(Strings.t("delete_tag_confirm_body").format(tag)) },
            confirmButton = {
                DangerButton(onClick = {
                    tagToDelete = null
                    allDevices.filter { it.tag == tag }.forEach { vm.setTag(it.serial, null) }
                }) { Text(Strings.t("delete")) }
            },
            dismissButton = { TextButton(onClick = { tagToDelete = null }) { Text(Strings.t("cancel")) } },
        )
    }
}

@Composable
private fun GroupHeaderRow(
    header: DeviceListItem.Header,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onDeleteTag: (() -> Unit)? = null,
) {
    // A sticky-feeling section header: collapse chevron + label + online/total counts. The
    // online dot+count stays visible when the group is collapsed, so the user can still tell
    // which group has a connected device without expanding every group. The toggle is scoped to
    // the label block so a click on the delete button (when present) doesn't also collapse.
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colors.background)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).clickable { onToggle() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (collapsed) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown,
                contentDescription = if (collapsed) Strings.t("expand") else Strings.t("collapse"),
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                groupLabel(header.key),
                style = MaterialTheme.typography.caption,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.width(6.dp))
            if (header.onlineCount > 0) {
                StatusDot(isLive = true)
                Spacer(Modifier.width(2.dp))
                Text(
                    "${header.onlineCount}/${header.count}",
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                )
            } else {
                Text(
                    Strings.t("group_count").format(header.count),
                    style = MaterialTheme.typography.caption,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.4f),
                )
            }
        }
        if (onDeleteTag != null) {
            IconButton(onClick = onDeleteTag, modifier = Modifier.size(20.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = Strings.t("delete_tag"),
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                )
            }
        }
    }
}

/** Localize a stable group key produced by [com.adbgui.core.device.DeviceListOrganizer]. */
private fun groupLabel(key: String): String = when (key) {
    "type_usb" -> Strings.t("group_type_usb")
    "type_wireless" -> Strings.t("group_type_wireless")
    "type_unknown" -> Strings.t("group_type_unknown")
    "status_online" -> Strings.t("group_status_online")
    "status_offline" -> Strings.t("group_status_offline")
    "subnet_none" -> Strings.t("group_subnet_none")
    "tag_none" -> Strings.t("group_tag_none")
    else -> key  // subnet strings ("10.0.0") and custom tag strings display as-is
}

private fun groupByLabel(mode: DeviceGroupBy): String = when (mode) {
    DeviceGroupBy.NONE -> Strings.t("group_none")
    DeviceGroupBy.TYPE -> Strings.t("group_type")
    DeviceGroupBy.STATUS -> Strings.t("group_status")
    DeviceGroupBy.SUBNET -> Strings.t("group_subnet")
    DeviceGroupBy.TAG -> Strings.t("group_tag")
}

/** Stable LazyColumn key for a [DeviceListItem]. */
private fun DeviceListItem.key(): String = when (this) {
    is DeviceListItem.Header -> "header:$key"
    is DeviceListItem.Device -> "device:${view.serial}"
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DeviceRow(
    device: DeviceView,
    selected: String? = null,
    existingTags: List<String> = emptyList(),
    onRename: (String?) -> Unit,
    onSetTag: (String?) -> Unit,
    onForget: () -> Unit,
    onDisconnect: () -> Unit,
    onSelect: () -> Unit = {},
    onReconnect: (String, Int) -> Unit = { _, _ -> },
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var aliasDraft by remember { mutableStateOf(device.alias ?: "") }
    var tagDraft by remember { mutableStateOf(device.tag ?: "") }
    var showTagDialog by remember { mutableStateOf(false) }
    var showForgetConfirm by remember { mutableStateOf(false) }
    val isSelected = device.serial == selected
    val rowBg = if (isSelected) MaterialTheme.colors.primary.copy(alpha = 0.14f) else Color.Transparent

    Column(modifier = Modifier.fillMaxWidth().background(rowBg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clickable { onSelect() }
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            if (event.type == PointerEventType.Press &&
                                event.button == PointerButton.Secondary
                            ) {
                                menuOpen = true
                            }
                        }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(isLive = device.isLive)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (renaming) {
                    val focusRequester = remember { FocusRequester() }
                    androidx.compose.runtime.LaunchedEffect(renaming) {
                        if (renaming) focusRequester.requestFocus()
                    }
                    TextField(
                        value = aliasDraft,
                        onValueChange = { aliasDraft = it },
                        modifier = Modifier.widthIn(max = 180.dp)
                            .focusRequester(focusRequester)
                            .onPreviewKeyEvent { e ->
                                when (e.key) {
                                    Key.Enter, Key.NumPadEnter -> {
                                        onRename(aliasDraft.ifBlank { null })
                                        renaming = false
                                        true
                                    }
                                    Key.Escape -> {
                                        renaming = false
                                        true
                                    }
                                    else -> false
                                }
                            },
                        singleLine = true,
                    )
                    Row {
                        TextButton(onClick = {
                            onRename(aliasDraft.ifBlank { null })
                            renaming = false
                        }) { Text(Strings.t("ok")) }
                        TextButton(onClick = { renaming = false }) { Text(Strings.t("cancel")) }
                    }
                } else {
                    Text(
                        text = device.alias ?: device.serial,
                        style = MaterialTheme.typography.body1,
                    )
                    val sub = buildString {
                        append(device.serial)
                        device.wirelessIp?.let { append(" · $it:${device.wirelessPort ?: 5555}") }
                    }
                    Text(sub, style = MaterialTheme.typography.caption)
                    // Show the grouping tag (if any) so the user can tell which custom group
                    // a device belongs to without switching to "group by tag" mode.
                    device.tag?.takeIf { it.isNotBlank() }?.let { tag ->
                        Text(
                            "🏷 $tag",
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.primary.copy(alpha = 0.8f),
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = Strings.t("more"))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (device.wirelessIp != null && device.wirelessPort != null) {
                        DropdownMenuItem(onClick = {
                            menuOpen = false
                            onReconnect(device.wirelessIp!!, device.wirelessPort!!)
                        }) { Text(Strings.t("reconnect")) }
                    }
                    DropdownMenuItem(onClick = {
                        menuOpen = false
                        aliasDraft = device.alias ?: ""
                        renaming = true
                    }) { Text(Strings.t("rename")) }
                    DropdownMenuItem(onClick = {
                        menuOpen = false
                        tagDraft = device.tag ?: ""
                        showTagDialog = true
                    }) { Text(Strings.t("set_tag")) }
                    DropdownMenuItem(onClick = {
                        menuOpen = false
                        onDisconnect()
                    }) { Text(Strings.t("disconnect")) }
                    DropdownMenuItem(onClick = {
                        menuOpen = false
                        showForgetConfirm = true
                    }) { Text(Strings.t("forget")) }
                }
            }
        }
        Divider()
    }

    if (showTagDialog) {
        AlertDialog(
            onDismissRequest = { showTagDialog = false },
            title = { Text(Strings.t("set_tag")) },
            text = {
                Column {
                    TextField(
                        value = tagDraft,
                        onValueChange = { tagDraft = it },
                        singleLine = true,
                        placeholder = { Text(Strings.t("tag_placeholder")) },
                    )
                    // Quick-pick chips for tags already in use on other devices — saves typing
                    // and keeps spelling consistent so "group by tag" actually coalesces.
                    if (existingTags.isNotEmpty()) {
                        Spacer(Modifier.size(8.dp))
                        Text(
                            Strings.t("tag_suggestions"),
                            style = MaterialTheme.typography.caption,
                            color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.size(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            existingTags.forEach { t ->
                                val active = t == device.tag
                                Surface(
                                    shape = MaterialTheme.shapes.small,
                                    color = if (active) MaterialTheme.colors.primary.copy(alpha = 0.18f) else MaterialTheme.colors.onSurface.copy(alpha = 0.08f),
                                    modifier = Modifier.clickable { tagDraft = t },
                                ) {
                                    Text(
                                        t,
                                        style = MaterialTheme.typography.caption,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showTagDialog = false
                    onSetTag(tagDraft.ifBlank { null })
                }) { Text(Strings.t("ok")) }
            },
            dismissButton = {
                TextButton(onClick = { showTagDialog = false }) { Text(Strings.t("cancel")) }
            },
        )
    }

    if (showForgetConfirm) {
        AlertDialog(
            onDismissRequest = { showForgetConfirm = false },
            title = { Text(Strings.t("forget_confirm_title")) },
            text = { Text(Strings.t("forget_confirm_body").format(device.alias ?: device.serial)) },
            confirmButton = { DangerButton(onClick = { showForgetConfirm = false; onForget() }) { Text(Strings.t("forget")) } },
            dismissButton = { TextButton(onClick = { showForgetConfirm = false }) { Text(Strings.t("cancel")) } },
        )
    }
}
