package com.adbgui.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adbgui.core.device.LogcatFilters
import com.adbgui.core.domain.LogcatLevel
import com.adbgui.desktop.ui.i18n.Strings

/** AS 式过滤栏（spec 2026-10-14 §3.6）：级别 / App / Tag 三个下拉 + 全文搜索（含正则 pill）。
 *  无内部过滤状态——全部 state 由 [LogcatScreen] 提升，经参数传入；任何变更构造完整
 *  [LogcatFilters] 回调 [onFilters]（Screen 更新本地 state 并转发 vm.setFilters）。
 *  过滤栏右侧的控制按钮（暂停/录制/清屏/复制/导出）留在 LogcatScreen——本组件只管过滤。 */
@Composable
fun LogcatFilterBar(
    vm: LogcatViewModel,
    levelSet: Set<LogcatLevel>,
    text: String,
    useRegex: Boolean,
    packageName: String?,
    tagInclude: String?,
    tagExcludes: List<String>,
    pidOnly: Int?,
    onFilters: (LogcatFilters) -> Unit,
    searchFocus: FocusRequester,
) {
    fun update(
        pkg: String? = packageName, tag: String? = tagInclude, excl: List<String> = tagExcludes,
        pid: Int? = pidOnly, lvl: Set<LogcatLevel> = levelSet, txt: String = text, regex: Boolean = useRegex,
    ) = onFilters(LogcatFilters(
        levelSet = lvl, packageName = pkg, pids = pid?.let { setOf(it) } ?: emptySet(),
        tagInclude = tag, tagExcludes = excl, text = txt.ifBlank { null }, textIsRegex = regex,
    ))

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // ---- 级别（多选，逻辑与旧 LogcatScreen 相同，迁入） ----
        var levelMenuOpen by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { levelMenuOpen = true }) {
                Text("${Strings.t("level")}: ${levelLabel(levelSet)}")
            }
            DropdownMenu(expanded = levelMenuOpen, onDismissRequest = { levelMenuOpen = false }) {
                LogcatLevel.entries.forEach { lvl ->
                    DropdownMenuItem(onClick = {
                        val ns = if (lvl in levelSet) levelSet - lvl else levelSet + lvl
                        levelMenuOpen = false
                        update(lvl = ns)
                    }) { Text("${if (lvl in levelSet) "✓" else "•"}  ${lvl.name}") }
                }
            }
        }

        // ---- App（包名 → pidof，spec D1/D2） ----
        var appMenuOpen by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { appMenuOpen = true; vm.loadPackages() }) {
                Text(
                    packageName ?: Strings.t("app_filter_all"),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
            }
            AppFilterDropdown(
                expanded = appMenuOpen,
                onDismiss = { appMenuOpen = false },
                vm = vm,
                packageName = packageName,
                onSelect = { appMenuOpen = false; update(pkg = it, pid = null) },
            )
        }

        // ---- Tag（来自当前缓冲，频次降序；include 单选 + 每行排除 + 清除排除） ----
        var tagMenuOpen by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { tagMenuOpen = true }) {
                Text(
                    tagInclude ?: Strings.t("tag_filter"),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp),
                )
            }
            TagFilterDropdown(
                expanded = tagMenuOpen,
                onDismiss = { tagMenuOpen = false },
                tags = remember(tagMenuOpen) { if (tagMenuOpen) vm.snapshotTags() else emptyList() },
                tagInclude = tagInclude,
                tagExcludes = tagExcludes,
                onSelect = { tagMenuOpen = false; update(tag = it) },
                onExclude = { update(excl = tagExcludes + it) },
                onClearInclude = { tagMenuOpen = false; update(tag = null) },
                onClearExcludes = { update(excl = emptyList()) },
            )
        }

        // ---- 全文搜索（迁移自旧 LogcatScreen，含正则 pill） ----
        SearchField(
            value = text,
            onValueChange = { update(txt = it) },
            placeholder = Strings.t("text_search"),
            regex = useRegex,
            onRegexChange = { update(regex = it) },
            modifier = Modifier.weight(1f).focusRequester(searchFocus),
        )
    }
}

@Composable
private fun AppFilterDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    vm: LogcatViewModel,
    packageName: String?,
    onSelect: (String?) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.widthIn(min = 320.dp)) {
        var query by remember { mutableStateOf("") }
        var showSystem by remember { mutableStateOf(false) }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text(Strings.t("search_package")) },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
            Checkbox(checked = showSystem, onCheckedChange = { showSystem = it })
            Text(Strings.t("show_system_apps"), style = MaterialTheme.typography.caption)
        }
        vm.packagesError.value?.let {
            Text(it, color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption,
                modifier = Modifier.padding(horizontal = 8.dp))
        }
        val pkgs = (vm.packages.value ?: emptyList())
            .filter { (showSystem || !it.isSystem) && it.name.contains(query, ignoreCase = true) }
        DropdownMenuItem(onClick = { onDismiss(); onSelect(null) }) {
            Text((if (packageName == null) "✓ " else "") + Strings.t("app_filter_all"))
        }
        if (pkgs.isEmpty()) {
            Text(Strings.t("no_tag_matches"), style = MaterialTheme.typography.caption,
                modifier = Modifier.padding(8.dp))
        }
        Column(modifier = Modifier.width(320.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            pkgs.forEach { p ->
                DropdownMenuItem(onClick = { onSelect(p.name) }) {
                    Text((if (p.name == packageName) "✓ " else "") + p.name, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun TagFilterDropdown(
    expanded: Boolean,
    onDismiss: () -> Unit,
    tags: List<Pair<String, Int>>,
    tagInclude: String?,
    tagExcludes: List<String>,
    onSelect: (String?) -> Unit,
    onExclude: (String) -> Unit,
    onClearInclude: () -> Unit,
    onClearExcludes: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.widthIn(min = 280.dp)) {
        var query by remember { mutableStateOf("") }
        OutlinedTextField(
            value = query, onValueChange = { query = it },
            placeholder = { Text(Strings.t("text_search")) },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        )
        DropdownMenuItem(onClick = onClearInclude) {
            Text((if (tagInclude == null) "✓ " else "") + Strings.t("tag_filter_all"))
        }
        if (tagExcludes.isNotEmpty()) {
            DropdownMenuItem(onClick = onClearExcludes) {
                Text("${Strings.t("clear_excludes")} (${tagExcludes.size})", style = MaterialTheme.typography.caption)
            }
        }
        val shown = tags.filter { it.first.contains(query, ignoreCase = true) }
        if (shown.isEmpty()) {
            Text(Strings.t("no_tag_matches"), style = MaterialTheme.typography.caption,
                modifier = Modifier.padding(8.dp))
        }
        Column(modifier = Modifier.width(280.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            shown.forEach { (tag, count) ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable { onSelect(tag) }) {
                    Text(
                        (if (tag == tagInclude) "✓ " else "") + "$tag ($count)",
                        maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    TextButton(onClick = { onExclude(tag) }) { Text(Strings.t("exclude"), style = MaterialTheme.typography.caption) }
                }
            }
        }
    }
}

private fun levelLabel(levelSet: Set<LogcatLevel>): String =
    if (levelSet.size == LogcatLevel.entries.size) Strings.t("level_all")
    else levelSet.joinToString(",") { it.name }
