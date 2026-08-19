package com.wunelezi.injector.util

import android.content.Context
import android.net.Uri
import com.wunelezi.injector.model.ImportResult
import com.wunelezi.injector.model.LoadResult
import com.wunelezi.injector.model.ModuleInfo
import org.json.JSONObject
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * 插件管理器
 *
 * 核心逻辑（纯 ContentUri 方案）：
 * 1. 通过 content:// URI 读取目标包的 plugins.txt
 * 2. 遍历 zip 中的 info.json，解析 name 和 author
 * 3. 删除：从 plugins.txt 移除条目 + 通过 ContentResolver.delete 删除 zip
 * 4. 导入：SAF 选择文件 → 通过 ContentUri 写入 UUID.zip → 更新 plugins.txt
 */
object PluginManager {

    /** 获取目标包名的 content URI 前缀（直接使用 files 根目录） */
    fun getBaseContentUri(packageName: String): String {
        return "content://$packageName.widget_file_provider/widget_external_files/"
    }

    /** plugins.txt 的 content URI */
    fun getPluginsTxtUri(packageName: String): String {
        return getBaseContentUri(packageName) + "plugins.txt"
    }

    /** 某个 zip 的 content URI */
    fun getZipUri(packageName: String, zipName: String): String {
        return getBaseContentUri(packageName) + zipName
    }

    /**
     * 读取 plugins.txt，返回 zip 名称列表
     */
    fun readPluginsTxt(context: Context, packageName: String): List<String> {
        return readPluginsTxtWithLogs(context, packageName).first
    }

    /**
     * 读取 plugins.txt，返回 zip 名称列表 + 日志
     */
    fun readPluginsTxtWithLogs(context: Context, packageName: String): Pair<List<String>, List<String>> {
        val logs = mutableListOf<String>()
        val pluginsUri = getPluginsTxtUri(packageName)

        logs.add("--- 读取 plugins.txt ---")
        logs.add("ContentUri: $pluginsUri")

        // 通过 content URI 读取
        val readResult = UriHelper.readUriWithDetail(context, pluginsUri)
        val content = readResult.content

        if (content != null) {
            logs.add("✓ ContentUri 读取成功, 长度: ${content.length}")
            logs.add("内容: ${content.take(200)}${if (content.length > 200) "..." else ""}")
        } else {
            logs.add("✗ ContentUri 读取失败: ${readResult.error}")
        }

        if (content.isNullOrBlank()) {
            logs.add("✗ plugins.txt 内容为空或读取失败")
            return Pair(emptyList(), logs)
        }

        val zipNames = content.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.endsWith(".zip") }
        logs.add("解析到 ${zipNames.size} 个 zip 条目")
        return Pair(zipNames, logs)
    }

    /**
     * 从 zip 中读取 info.json，解析 name 和 author
     */
    fun readZipInfo(context: Context, packageName: String, zipName: String): ModuleInfo? {
        return readZipInfoWithLogs(context, packageName, zipName).first
    }

    /**
     * 从 zip 中读取 info.json，返回 ModuleInfo + 日志
     */
    fun readZipInfoWithLogs(context: Context, packageName: String, zipName: String): Pair<ModuleInfo?, List<String>> {
        val logs = mutableListOf<String>()
        val zipUri = getZipUri(packageName, zipName)

        logs.add("  读取 zip: $zipName")
        logs.add("  ContentUri: $zipUri")

        // 通过 content URI 读取
        val ins: InputStream? = UriHelper.openInputStream(context, zipUri)
        if (ins != null) {
            logs.add("  ✓ ContentUri 打开成功")
        } else {
            logs.add("  ✗ ContentUri 打开失败")
            return Pair(null, logs)
        }

        return try {
            val zis = ZipInputStream(ins)
            var entry = zis.nextEntry
            var infoJson: String? = null
            while (entry != null) {
                if (entry.name == "info.json") {
                    val bytes = zis.readBytes()
                    infoJson = String(bytes, Charsets.UTF_8)
                    break
                }
                entry = zis.nextEntry
            }
            zis.close()

            if (infoJson != null) {
                logs.add("  ✓ 找到 info.json")
                val json = JSONObject(infoJson)
                val name = json.optString("name", zipName)
                val author = json.optString("author", "未知")
                logs.add("  name=$name, author=$author")
                Pair(ModuleInfo(zipName = zipName, name = name, author = author), logs)
            } else {
                logs.add("  ⚠ zip 中无 info.json, 使用 zip 名作为模块名")
                Pair(ModuleInfo(zipName = zipName, name = zipName, author = "未知"), logs)
            }
        } catch (e: Exception) {
            logs.add("  ✗ 解析失败: ${e::class.java.simpleName}: ${e.message}")
            Pair(ModuleInfo(zipName = zipName, name = zipName, author = "读取失败"), logs)
        }
    }

    /**
     * 加载所有模块信息
     */
    fun loadModules(context: Context, packageName: String): List<ModuleInfo> {
        return loadModulesWithLogs(context, packageName).modules
    }

    /**
     * 加载所有模块信息，返回 [LoadResult] 含详细日志
     */
    fun loadModulesWithLogs(context: Context, packageName: String): LoadResult {
        val logs = mutableListOf<String>()
        logs.add("===== 加载模块 =====")
        logs.add("包名: $packageName")

        val (zipNames, readLogs) = readPluginsTxtWithLogs(context, packageName)
        logs.addAll(readLogs)

        if (zipNames.isEmpty()) {
            logs.add("\n✗ 没有找到任何 zip 条目")
            return LoadResult(emptyList(), logs)
        }

        logs.add("\n--- 遍历 zip 文件 ---")
        val modules = mutableListOf<ModuleInfo>()
        for (zipName in zipNames) {
            val (module, zipLogs) = readZipInfoWithLogs(context, packageName, zipName)
            logs.addAll(zipLogs)
            if (module != null) {
                modules.add(module)
            }
        }

        logs.add("\n===== 加载完成: ${modules.size} 个模块 =====")
        return LoadResult(modules, logs)
    }

    /**
     * 删除模块
     *
     * 1. 从 plugins.txt 移除条目
     * 2. 通过 ContentResolver.delete 删除 zip 文件
     */
    fun deletePlugin(context: Context, packageName: String, zipName: String): Boolean {
        // 1. 读取当前列表
        val current = readPluginsTxt(context, packageName).toMutableList()
        current.remove(zipName)

        // 2. 写回 plugins.txt
        val updatedContent = current.joinToString("\n") + if (current.isNotEmpty()) "\n" else ""
        val pluginsUri = getPluginsTxtUri(packageName)
        val writeOk = UriHelper.writeUri(context, pluginsUri, updatedContent)

        // 3. 通过 content URI 删除 zip 文件
        val zipUri = getZipUri(packageName, zipName)
        try {
            val uri = Uri.parse(zipUri)
            context.contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            // 忽略
        }

        return writeOk
    }

    /**
     * 导入插件
     *
     * 1. 通过 ContentUri 将 SAF 选择的文件写入为 UUID.zip
     * 2. 更新 plugins.txt
     *
     * 返回 ImportResult，包含成功/失败标志和详细日志
     */
    fun importPlugin(context: Context, packageName: String, sourceUri: Uri): ImportResult {
        val uuid = UUID.randomUUID().toString()
        val zipName = "$uuid.zip"
        val zipUri = getZipUri(packageName, zipName)
        val logs = mutableListOf<String>()

        logs.add("===== 导入开始 =====")
        logs.add("目标包名: $packageName")
        logs.add("生成 UUID: $uuid")
        logs.add("目标 ContentUri: $zipUri")
        logs.add("源文件 Uri: $sourceUri")

        var writeOk = false

        // 通过 content URI 写入 zip
        logs.add("\n--- ContentUri 写入 zip ---")
        val dstOs = UriHelper.openOutputStream(context, zipUri)
        if (dstOs != null) {
            try {
                val srcIns = context.contentResolver.openInputStream(sourceUri)
                if (srcIns != null) {
                    srcIns.use { input ->
                        dstOs.use { output ->
                            input.copyTo(output)
                        }
                    }
                    writeOk = true
                    logs.add("✓ ContentUri 写入成功")
                } else {
                    logs.add("✗ 源文件 InputStream 为 null")
                }
            } catch (e: Exception) {
                logs.add("✗ ContentUri 写入失败: ${e::class.java.simpleName}: ${e.message}")
            }
        } else {
            logs.add("✗ ContentUri OutputStream 为 null (目标 ContentProvider 不可写)")
        }

        if (!writeOk) {
            logs.add("\n===== 写入失败, 导入终止 =====")
            return ImportResult(success = false, zipName = zipName, logs = logs)
        }

        // 更新 plugins.txt
        logs.add("\n--- 更新 plugins.txt ---")
        val (current, readLogs) = readPluginsTxtWithLogs(context, packageName)
        logs.addAll(readLogs)
        logs.add("当前插件数: ${current.size}")
        val currentMutable = current.toMutableList()
        currentMutable.add(zipName)
        val updatedContent = currentMutable.joinToString("\n") + "\n"
        val pluginsUri = getPluginsTxtUri(packageName)
        val writeResult = UriHelper.writeUriWithDetail(context, pluginsUri, updatedContent)
        val updated = writeResult.success

        if (updated) {
            logs.add("✓ ContentUri 写入 plugins.txt 成功")
        } else {
            logs.add("✗ ContentUri 写入 plugins.txt 失败: ${writeResult.error}")
        }

        // 验证写入结果
        logs.add("\n--- 验证写入结果 ---")
        val (verifyList, verifyLogs) = readPluginsTxtWithLogs(context, packageName)
        logs.addAll(verifyLogs)
        logs.add("验证: plugins.txt 中现有 ${verifyList.size} 个条目")
        if (verifyList.contains(zipName)) {
            logs.add("✓ 新导入的 zip 已在列表中")
        } else {
            logs.add("✗ 新导入的 zip 不在列表中!")
        }

        logs.add("\n===== 导入${if (updated) "成功" else "失败"} =====")
        return ImportResult(success = updated, zipName = zipName, logs = logs)
    }
}
