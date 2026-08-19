package com.wunelezi.injector.util

import android.content.Context
import android.net.Uri
import com.wunelezi.injector.model.ImportResult
import com.wunelezi.injector.model.LoadResult
import com.wunelezi.injector.model.ModuleInfo
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * 插件管理器
 *
 * 核心逻辑：
 * 1. 通过 content:// URI 读取目标包的 plugins.txt
 * 2. 如果不存在，通过 FileHelper 漏洞方案创建，不行用 Shizuku
 * 3. 遍历 zip 中的 info.json，解析 name 和 author
 * 4. 删除：从 plugins.txt 移除条目 + 删除 zip 文件
 * 5. 导入：SAF 选择文件 → 以 UUID.zip 写入目标路径 → 更新 plugins.txt
 */
object PluginManager {

    /** 获取目标包名的 content URI 前缀 */
    fun getBaseContentUri(packageName: String): String {
        return "content://$packageName.widget_file_provider/widget_external_files/WNLZ-Injector/"
    }

    /** plugins.txt 的 content URI */
    fun getPluginsTxtUri(packageName: String): String {
        return getBaseContentUri(packageName) + "plugins.txt"
    }

    /** 某个 zip 的 content URI */
    fun getZipUri(packageName: String, zipName: String): String {
        return getBaseContentUri(packageName) + zipName
    }

    /** 获取目标包名的 Android/data 外部文件路径 */
    fun getBaseFilePath(packageName: String): String {
        return "/sdcard/Android/data/$packageName/files/WNLZ-Injector/"
    }

    /** plugins.txt 的文件路径 */
    fun getPluginsTxtPath(packageName: String): String {
        return getBaseFilePath(packageName) + "plugins.txt"
    }

    /**
     * 读取 plugins.txt，返回 zip 名称列表
     *
     * 如果 content URI 读取失败，尝试创建文件后重试
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
        val pluginsPath = getPluginsTxtPath(packageName)

        logs.add("--- 读取 plugins.txt ---")
        logs.add("ContentUri: $pluginsUri")
        logs.add("文件路径: $pluginsPath")

        // 尝试通过 content URI 读取
        val readResult = UriHelper.readUriWithDetail(context, pluginsUri)
        var content: String? = readResult.content

        if (content != null) {
            logs.add("✓ ContentUri 读取成功, 长度: ${content.length}")
            logs.add("内容: ${content.take(200)}${if (content.length > 200) "..." else ""}")
        } else {
            logs.add("✗ ContentUri 读取失败: ${readResult.error}")

            // 尝试文件读取
            logs.add("尝试直接文件读取...")
            val revisedPath = FileHelper.getRevisePath(pluginsPath)
            try {
                val file = File(revisedPath ?: pluginsPath)
                logs.add("文件路径(修正): ${file.absolutePath}")
                if (file.exists()) {
                    content = file.readText()
                    logs.add("✓ 文件读取成功, 长度: ${content.length}")
                } else {
                    logs.add("✗ 文件不存在")
                }
            } catch (e: Exception) {
                logs.add("✗ 文件读取失败: ${e::class.java.simpleName}: ${e.message}")
            }
        }

        if (content == null) {
            logs.add("尝试创建 plugins.txt...")
            val created = ensurePluginsTxtExists(context, packageName)
            logs.add("创建结果: $created")
            if (created) {
                // 重试读取
                val retryResult = UriHelper.readUriWithDetail(context, pluginsUri)
                content = retryResult.content
                if (content != null) {
                    logs.add("✓ 重试 ContentUri 读取成功")
                } else {
                    logs.add("✗ 重试 ContentUri 读取失败: ${retryResult.error}")
                    // 再试文件
                    val revisedPath = FileHelper.getRevisePath(pluginsPath)
                    try {
                        val file = File(revisedPath ?: pluginsPath)
                        if (file.exists()) {
                            content = file.readText()
                            logs.add("✓ 重试文件读取成功")
                        }
                    } catch (e: Exception) {
                        logs.add("✗ 重试文件读取失败: ${e.message}")
                    }
                }
            }
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
     * 确保 plugins.txt 文件存在
     *
     * 策略：FileHelper 漏洞方案 → Shizuku
     */
    fun ensurePluginsTxtExists(context: Context, packageName: String): Boolean {
        val pluginsPath = getPluginsTxtPath(packageName)
        val pluginsDir = getBaseFilePath(packageName)

        // 方案1：FileHelper 漏洞方案直接创建
        val revisedDir = FileHelper.getReviseFile(File(pluginsDir))
        val revisedFile = FileHelper.getReviseFile(File(pluginsPath))

        try {
            // 创建目录
            val actualDir = revisedDir ?: File(pluginsDir)
            if (!actualDir.exists()) {
                actualDir.mkdirs()
            }
            // 创建文件
            val actualFile = revisedFile ?: File(pluginsPath)
            if (!actualFile.exists()) {
                actualFile.parentFile?.mkdirs()
                actualFile.createNewFile()
            }
            if (actualFile.exists()) return true
        } catch (e: Exception) {
            // 忽略，继续尝试 Shizuku
        }

        // 方案2：Shizuku 授权创建
        if (ShizukuHelper.isAvailable()) {
            if (ShizukuHelper.hasPermission()) {
                return createPluginsTxtViaShizuku(packageName)
            }
            // 无权限，返回 false 由调用方处理
            return false
        }

        return false
    }

    /**
     * 通过 Shizuku 创建 plugins.txt
     */
    private fun createPluginsTxtViaShizuku(packageName: String): Boolean {
        val pluginsPath = getPluginsTxtPath(packageName)
        val pluginsDir = getBaseFilePath(packageName)
        ShizukuHelper.ensureDir(pluginsDir)
        return ShizukuHelper.createFile(pluginsPath)
    }

    /**
     * 从 zip 中读取 info.json，解析 name 和 author
     */
    fun readZipInfo(context: Context, packageName: String, zipName: String): ModuleInfo? {
        val zipUri = getZipUri(packageName, zipName)

        // 尝试通过 content URI 读取
        var ins: InputStream? = UriHelper.openInputStream(context, zipUri)

        if (ins == null) {
            // content URI 失败，尝试直接文件读取
            val zipPath = getBaseFilePath(packageName) + zipName
            val revisedPath = FileHelper.getRevisePath(zipPath)
            val zipFile = File(revisedPath ?: zipPath)
            if (zipFile.exists()) {
                ins = FileInputStream(zipFile)
            }
        }

        if (ins == null) return null

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
                val json = JSONObject(infoJson)
                val name = json.optString("name", zipName)
                val author = json.optString("author", "未知")
                ModuleInfo(zipName = zipName, name = name, author = author)
            } else {
                // 没有 info.json，用 zip 名作为模块名
                ModuleInfo(zipName = zipName, name = zipName, author = "未知")
            }
        } catch (e: Exception) {
            ModuleInfo(zipName = zipName, name = zipName, author = "读取失败")
        }
    }

    /**
     * 从 zip 中读取 info.json，返回 ModuleInfo + 日志
     */
    fun readZipInfoWithLogs(context: Context, packageName: String, zipName: String): Pair<ModuleInfo?, List<String>> {
        val logs = mutableListOf<String>()
        val zipUri = getZipUri(packageName, zipName)
        val zipPath = getBaseFilePath(packageName) + zipName

        logs.add("  读取 zip: $zipName")
        logs.add("  ContentUri: $zipUri")
        logs.add("  文件路径: $zipPath")

        // 尝试通过 content URI 读取
        var ins: InputStream? = UriHelper.openInputStream(context, zipUri)
        if (ins != null) {
            logs.add("  ✓ ContentUri 打开成功")
        } else {
            logs.add("  ✗ ContentUri 打开失败, 尝试文件读取")
            val revisedPath = FileHelper.getRevisePath(zipPath)
            try {
                val zipFile = File(revisedPath ?: zipPath)
                logs.add("  文件路径(修正): ${zipFile.absolutePath}")
                if (zipFile.exists()) {
                    ins = FileInputStream(zipFile)
                    logs.add("  ✓ 文件打开成功, 大小: ${zipFile.length()} bytes")
                } else {
                    logs.add("  ✗ 文件不存在")
                    return Pair(null, logs)
                }
            } catch (e: Exception) {
                logs.add("  ✗ 文件打开失败: ${e::class.java.simpleName}: ${e.message}")
                return Pair(null, logs)
            }
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
     * 2. 删除实际 zip 文件
     */
    fun deletePlugin(context: Context, packageName: String, zipName: String): Boolean {
        // 1. 读取当前列表
        val current = readPluginsTxt(context, packageName).toMutableList()
        current.remove(zipName)

        // 2. 写回 plugins.txt
        val updatedContent = current.joinToString("\n") + if (current.isNotEmpty()) "\n" else ""
        val pluginsUri = getPluginsTxtUri(packageName)
        var writeOk = UriHelper.writeUri(context, pluginsUri, updatedContent)

        if (!writeOk) {
            // content URI 写入失败，尝试文件写入
            val pluginsPath = getPluginsTxtPath(packageName)
            val revisedPath = FileHelper.getRevisePath(pluginsPath)
            try {
                val file = File(revisedPath ?: pluginsPath)
                file.writeText(updatedContent)
                writeOk = true
            } catch (e: Exception) {
                // 尝试 Shizuku
                if (ShizukuHelper.isAvailable() && ShizukuHelper.hasPermission()) {
                    // 写入临时文件再用 Shizuku 移动
                    val tmpFile = File(context.cacheDir, "plugins_tmp.txt")
                    tmpFile.writeText(updatedContent)
                    ShizukuHelper.copyToTarget(tmpFile.absolutePath, pluginsPath)
                    tmpFile.delete()
                    writeOk = true
                }
            }
        }

        // 3. 删除 zip 文件
        val zipUri = getZipUri(packageName, zipName)
        try {
            // 通过 content URI 删除
            val uri = Uri.parse(zipUri)
            context.contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            // 忽略，尝试文件删除
        }

        // 尝试文件删除
        val zipPath = getBaseFilePath(packageName) + zipName
        val revisedZipPath = FileHelper.getRevisePath(zipPath)
        try {
            val zipFile = File(revisedZipPath ?: zipPath)
            if (zipFile.exists()) zipFile.delete()
        } catch (e: Exception) {
            // Shizuku 删除
            if (ShizukuHelper.isAvailable() && ShizukuHelper.hasPermission()) {
                ShizukuHelper.deleteFile(zipPath)
            }
        }

        return writeOk
    }

    /**
     * 导入插件
     *
     * 1. 将 SAF 选择的文件复制为 UUID.zip 到目标路径
     * 2. 更新 plugins.txt
     *
     * 返回 ImportResult，包含成功/失败标志和详细日志
     */
    fun importPlugin(context: Context, packageName: String, sourceUri: Uri): ImportResult {
        val uuid = UUID.randomUUID().toString()
        val zipName = "$uuid.zip"
        val zipPath = getBaseFilePath(packageName) + zipName
        val zipUri = getZipUri(packageName, zipName)
        val logs = mutableListOf<String>()

        logs.add("===== 导入开始 =====")
        logs.add("目标包名: $packageName")
        logs.add("生成 UUID: $uuid")
        logs.add("目标路径: $zipPath")
        logs.add("ContentUri: $zipUri")
        logs.add("源文件 Uri: $sourceUri")

        var writeOk = false

        // 方案1：通过 content URI 写入
        logs.add("\n--- 方案1: ContentUri 写入 ---")
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

        // 方案2：FileHelper 漏洞方案
        if (!writeOk) {
            logs.add("\n--- 方案2: FileHelper 漏洞方案 ---")
            val dirPath = getBaseFilePath(packageName)
            try {
                val dir = FileHelper.getReviseFile(File(dirPath)) ?: File(dirPath)
                logs.add("目录路径: ${dir.absolutePath}")
                if (!dir.exists()) {
                    val mkdirOk = dir.mkdirs()
                    logs.add("创建目录: ${if (mkdirOk) "成功" else "失败"}")
                }
                val file = FileHelper.getReviseFile(File(zipPath)) ?: File(zipPath)
                logs.add("文件路径: ${file.absolutePath}")
                file.parentFile?.mkdirs()
                val srcIns = context.contentResolver.openInputStream(sourceUri)
                if (srcIns != null) {
                    srcIns.use { input ->
                        FileOutputStream(file).use { output ->
                            input.copyTo(output)
                        }
                    }
                    writeOk = true
                    logs.add("✓ FileHelper 漏洞方案写入成功")
                } else {
                    logs.add("✗ 源文件 InputStream 为 null")
                }
            } catch (e: Exception) {
                logs.add("✗ FileHelper 漏洞方案失败: ${e::class.java.simpleName}: ${e.message}")
            }
        }

        // 方案3：Shizuku
        if (!writeOk) {
            logs.add("\n--- 方案3: Shizuku 授权方案 ---")
            val shizukuAvailable = ShizukuHelper.isAvailable()
            logs.add("Shizuku 可用: $shizukuAvailable")
            if (shizukuAvailable) {
                val hasPermission = ShizukuHelper.hasPermission()
                logs.add("Shizuku 已授权: $hasPermission")
                if (hasPermission) {
                    val tmpFile = File(context.cacheDir, "import_$uuid.zip")
                    logs.add("临时缓存文件: ${tmpFile.absolutePath}")
                    try {
                        val srcIns = context.contentResolver.openInputStream(sourceUri)
                        if (srcIns != null) {
                            srcIns.use { input ->
                                FileOutputStream(tmpFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            logs.add("写入临时缓存成功, 大小: ${tmpFile.length()} bytes")
                            val copyOk = ShizukuHelper.copyToTarget(tmpFile.absolutePath, zipPath)
                            logs.add("Shizuku 复制到目标: ${if (copyOk) "成功" else "失败"}")
                            writeOk = copyOk
                        } else {
                            logs.add("✗ 源文件 InputStream 为 null")
                        }
                    } catch (e: Exception) {
                        logs.add("✗ Shizuku 方案失败: ${e::class.java.simpleName}: ${e.message}")
                    } finally {
                        tmpFile.delete()
                    }
                }
            }
        }

        if (!writeOk) {
            logs.add("\n===== 三种方案均失败, 导入终止 =====")
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
        var updated = writeResult.success

        if (updated) {
            logs.add("✓ ContentUri 写入 plugins.txt 成功")
        } else {
            logs.add("✗ ContentUri 写入 plugins.txt 失败: ${writeResult.error}")
            logs.add("尝试文件写入...")
            val pluginsPath = getPluginsTxtPath(packageName)
            val revisedPath = FileHelper.getRevisePath(pluginsPath)
            try {
                val file = File(revisedPath ?: pluginsPath)
                logs.add("plugins.txt 路径: ${file.absolutePath}")
                file.writeText(updatedContent)
                updated = true
                logs.add("✓ 文件写入 plugins.txt 成功")
            } catch (e: Exception) {
                logs.add("✗ 文件写入失败: ${e::class.java.simpleName}: ${e.message}")
                if (ShizukuHelper.isAvailable() && ShizukuHelper.hasPermission()) {
                    logs.add("尝试 Shizuku 写入 plugins.txt")
                    val tmpFile = File(context.cacheDir, "plugins_tmp.txt")
                    tmpFile.writeText(updatedContent)
                    val copyOk = ShizukuHelper.copyToTarget(tmpFile.absolutePath, pluginsPath)
                    tmpFile.delete()
                    updated = copyOk
                    logs.add("Shizuku 写入 plugins.txt: ${if (copyOk) "成功" else "失败"}")
                }
            }
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
