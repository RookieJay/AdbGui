package com.adbgui.core.device

import com.adbgui.core.log.Logger
import java.nio.file.Files
import java.nio.file.Path

/** 录制目录的磁盘维护（spec 2026-10-10 D4/D5）：归位、过期/0 字节清理、总量上限、占用统计。
 *  纯文件操作、不依赖 adb 与协程基建，manager 持实例委托调用；所有方法同步阻塞，调用方负责跑在 io 上。 */
internal class LogcatRecordingMaintenance(
    private val logger: Logger,
    private val clock: () -> Long,
) {
    /** 启动清理：归位 → 7 天 → 0 字节陈旧 → 总量上限（spec D4/D5）。cap 由调用方（manager，读 settings）传入。 */
    fun cleanupOnStartup(dir: Path, capBytes: Long, active: Set<Path>) {
        reorganizeLegacyFiles(dir)
        val autoFiles = autoLogFiles(dir) ?: return
        val retentionCutoff = clock() - RETENTION_MS
        val zeroByteCutoff = clock() - ZERO_BYTE_MS
        autoFiles.filter { it.toPath() !in active }.forEach { f ->
            runCatching {
                when {
                    f.lastModified() < retentionCutoff -> f.delete()
                    f.length() == 0L && f.lastModified() < zeroByteCutoff -> f.delete()
                }
            }.onFailure { logger.warn("recording cleanup failed for ${f.name}: ${it.message}") }
        }
        enforceSizeCap(dir, capBytes, active)
    }

    /** 旧布局（文件在录制目录根）一次性归位：auto_*.log → auto/，其余 .log → manual/（spec D5）。
     *  文件名保留原样（含 auto_ 前缀）不重命名；挪不动的 WARN 跳过，下次启动再试。 */
    fun reorganizeLegacyFiles(dir: Path) {
        val root = dir.toFile()
        if (!root.isDirectory) return
        root.listFiles { f -> f.isFile && f.name.endsWith(".log") }?.forEach { f ->
            runCatching {
                val sub = if (f.name.startsWith("auto_")) "auto" else "manual"
                val target = dir.resolve(sub)
                Files.createDirectories(target)
                Files.move(f.toPath(), target.resolve(f.name))
            }.onFailure { logger.warn("failed to reorganize ${f.name}: ${it.message}") }
        }
    }

    /** 总量上限（spec D4）：auto/ 从旧到新删到 cap 以下；活动文件与 manual 不动；删不到只 WARN 不抛。 */
    fun enforceSizeCap(dir: Path, capBytes: Long, active: Set<Path>) {
        val files = autoLogFiles(dir) ?: return
        var total = files.sumOf { it.length() }
        if (total <= capBytes) return
        files.filter { it.toPath() !in active }
            .sortedBy { it.lastModified() }
            .forEach { f ->
                if (total <= capBytes) return@forEach
                val len = f.length()
                if (runCatching { f.delete() }.getOrDefault(false)) total -= len
            }
        if (total > capBytes) {
            logger.warn("recording size cap cannot be met (active files held); total=$total cap=$capBytes")
        }
    }

    /** auto/ + manual/ 下 .log 文件总字节数（设置页占用展示）。 */
    fun totalBytes(dir: Path): Long =
        listOf("auto", "manual").sumOf { sub ->
            dir.resolve(sub).toFile()
                .listFiles { f -> f.isFile && f.name.endsWith(".log") }?.sumOf { it.length() } ?: 0L
        }

    /** 目录切换迁移（spec D2）：旧目录根 .log 与 auto/、manual/ 子目录文件搬到新目录对应位置；
     *  逐文件失败跳过 + WARN；旧目录空了删空目录。调用方先停录制、后调用、再重启。 */
    fun migrateFiles(oldDir: Path, newDir: Path) {
        if (!oldDir.toFile().isDirectory) return
        runCatching {
            Files.createDirectories(newDir.resolve("auto"))
            Files.createDirectories(newDir.resolve("manual"))
            // 旧布局根文件（从未归位的）
            oldDir.toFile().listFiles { f -> f.isFile && f.name.endsWith(".log") }?.forEach { f ->
                val sub = if (f.name.startsWith("auto_")) "auto" else "manual"
                moveOrWarn(f.toPath(), newDir.resolve(sub).resolve(f.name))
            }
            listOf("auto", "manual").forEach { sub ->
                oldDir.resolve(sub).toFile().listFiles { f -> f.isFile }?.forEach { f ->
                    moveOrWarn(f.toPath(), newDir.resolve(sub).resolve(f.name))
                }
            }
            if (oldDir.toFile().listFiles()?.isEmpty() == true) {
                runCatching { Files.deleteIfExists(oldDir) }
            }
        }.onFailure { logger.warn("recording migration failed: ${it.message}") }
    }

    private fun moveOrWarn(src: Path, dst: Path) {
        runCatching { Files.move(src, dst) }
            .onFailure { logger.warn("migration skipped ${src.fileName}: ${it.message}") }
    }

    private fun autoLogFiles(dir: Path) =
        dir.resolve("auto").toFile().listFiles { f -> f.isFile && f.name.endsWith(".log") }

    companion object {
        const val RETENTION_MS: Long = 7L * 24 * 60 * 60 * 1000
        const val ZERO_BYTE_MS: Long = 24L * 60 * 60 * 1000
    }
}
