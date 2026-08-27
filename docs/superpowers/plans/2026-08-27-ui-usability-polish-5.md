# UI 易用性打磨 第五波 Implementation Plan

> REQUIRED SUB-SKILL: superpowers:executing-plans. Checkbox tracking.

**Goal:** 统一文件对话框——全部走 `platform.FileDialogs`，UI 不再直接碰 `java.awt.FileDialog`。

**Architecture:** 给 `FileDialogs` 加 `saveFile`；替换 5 处原生 AWT 调用。行为不变（仍 AWT SAVE/LOAD，只是收进抽象）。分支 `feat/ui-usability-polish-5`。

## Global Constraints
- 无 Compose UI 测试框架；编译验证 + 手动运行。i18n 双语。每任务一提交。
- `FileDialogs.saveFile` 仿 `pickFile`：返回绝对路径 String，null=取消。

---

### Task H1: 给 FileDialogs 加 saveFile + 替换 FileExplorer 两处

**Files:** `platform/FileDialogs.kt`、`ui/FileExplorerScreen.kt`

- [ ] 加方法：
```kotlin
fun saveFile(title: String, defaultName: String? = null, currentPath: String? = null): String? {
    val dlg = FileDialog(Frame(), title, FileDialog.SAVE)
    parentDirOf(currentPath)?.let { dlg.directory = it }
    if (defaultName != null) dlg.file = defaultName
    dlg.isVisible = true
    val sel = dlg.file ?: return null
    return File(dlg.directory, sel).absolutePath
}
```
- [ ] FileExplorer upload（:111 LOAD）→ `FileDialogs.pickFile(Strings.t("upload"), currentPath = null)`，返回 path 作为 `localPath`。
- [ ] FileExplorer save/pull（:121 SAVE，硬编码 "Save"）→ `FileDialogs.saveFile(Strings.t("save_file"), defaultName = entry.name)`，修掉硬编码标题。
- [ ] 编译 + 手动验证：右键文件 Upload 选本地文件、Save 拉取保存，对话框正常；标题已 i18n。
- [ ] 提交：`refactor(desktop): unify file dialogs via FileDialogs.saveFile + FileExplorer`

### Task H2: 替换 4 个导出/保存点

**Files:** `ui/DeviceInfoScreen.kt`、`LogcatScreen.kt`、`ScreenshotScreen.kt`、`SystemInfoScreen.kt`

- [ ] DeviceInfoScreen 导出：`FileDialogs.saveFile(Strings.t("export_device_info_title"), "deviceinfo_$stamp.txt")` → `File(target).writeText(reportText)`。
- [ ] LogcatScreen 导出：`saveFile(save_logcat_title, "logcat_$stamp.txt")` → writeText(vm.export())。
- [ ] ScreenshotScreen 保存：`saveFile(save_screenshot_title, "screenshot_$stamp.png")` → writeBytes(bytes)。
- [ ] SystemInfoScreen 导出：`saveFile(si_export_title, "sysinfo_$stamp.txt")` → writeText(r)。
- [ ] 删除各文件 `import java.awt.FileDialog` / `java.awt.Frame`（不再直接用）；保留 `java.io.File`。
- [ ] 编译 + 手动验证：四页导出/保存对话框正常，文件写入成功。
- [ ] 提交：`refactor(desktop): route all export/save dialogs through FileDialogs`

## Self-Review
- 5 处原生 AWT 全替换，`FileDialogs` 现有 `pickFile`/`pickDirectory` 不变。FileExplorer 硬编码 "Save" 顺手 i18n。行为保持 AWT 原生（注释说明这是可靠性取舍）。
