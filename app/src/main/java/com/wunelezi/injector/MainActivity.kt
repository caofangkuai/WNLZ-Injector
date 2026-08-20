package com.wunelezi.injector

import android.app.Dialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wunelezi.injector.adapter.AppAdapter
import com.wunelezi.injector.adapter.ModuleAdapter
import com.wunelezi.injector.databinding.ActivityMainBinding
import com.wunelezi.injector.databinding.DialogAppListBinding
import com.wunelezi.injector.databinding.DialogModuleListBinding
import com.wunelezi.injector.model.AppInfo
import com.wunelezi.injector.model.ImportResult
import com.wunelezi.injector.model.InjectionMethod
import com.wunelezi.injector.model.LoadResult
import com.wunelezi.injector.model.ModuleInfo
import com.wunelezi.injector.util.PluginManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 当前模块列表数据（供删除后刷新） */
    private var moduleList: MutableList<ModuleInfo> = mutableListOf()
    private var moduleAdapter: ModuleAdapter? = null
    private var moduleDialog: Dialog? = null

    /** StartAnyWhere 回调标志 */
    private val STARTANYWHERE_CALLBACK = "wnlz_startanywhere_callback"

    /** 持久化文件名 */
    private val PREFS_NAME = "wnlz_injector_prefs"
    private val KEY_PACKAGE_NAME = "key_package_name"
    private val KEY_INJECTION_METHOD = "key_injection_method"
    /** 注入 intent1/intent2 各 extra 的开关状态（key -> 是否启用） */
    private val KEY_INJECT_PARAM_ENABLED = "key_inject_param_enabled"

    /** 加载对话框 */
    private var loadingDialog: Dialog? = null

    /** 自定义官包版本号（格式: versionName_versionCode，空则自动检测） */
    private var customVersion: String? = null

    /** 自动检测到的版本号（格式: versionName_versionCode） */
    private var autoDetectedVersion: String? = null

    /** SAF 文件选择 launcher */
    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            importPlugin(uri)
        }
    }

    /** 持久化（包名 + 注入方式） */
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private fun savePackageName(name: String) {
        prefs.edit().putString(KEY_PACKAGE_NAME, name).apply()
    }

    private fun saveInjectionMethod(method: InjectionMethod) {
        prefs.edit().putString(KEY_INJECTION_METHOD, method.name).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // 恢复持久化的包名与注入方式
        val savedPackageName = prefs.getString(KEY_PACKAGE_NAME, "").orEmpty()
        if (savedPackageName.isNotEmpty()) {
            binding.etPackageName.setText(savedPackageName)
            binding.etPackageName.setSelection(savedPackageName.length)
        }
        val savedMethodName = prefs.getString(KEY_INJECTION_METHOD, null)
        if (savedMethodName != null) {
            runCatching { InjectionMethod.valueOf(savedMethodName) }
                .getOrNull()
                ?.let { binding.methodSelector.setSelectedMethod(it) }
        }

        // 包名输入框 —— 长按弹出非系统应用列表
        binding.etPackageName.setOnLongClickListener {
            showAppListDialog()
            true
        }

        // 包名输入框 —— 文本变化时自动检测版本号 + 持久化
        binding.etPackageName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val pkg = s?.toString()?.trim().orEmpty()
                savePackageName(pkg)
                if (pkg.isEmpty()) {
                    autoDetectedVersion = null
                    updateVersionMenuItem()
                } else {
                    detectPackageVersion(pkg)
                }
            }
        })

        // 注入方式选择持久化
        binding.methodSelector.setOnMethodSelectedListener { method ->
            saveInjectionMethod(method)
        }

        // 注入模块选择卡片 —— 点击弹出模块列表
        binding.cardModules.setOnClickListener { showModuleListDialog() }

        // 注入按钮
        binding.btnInject.setOnClickListener {
            onInjectClicked()
        }

        // 初始更新模块摘要
        updateModuleSummary()

        // 启动时自动加载模块数量（静默，不报错）
        loadModuleSummary()

        // 检查是否从 StartAnyWhere 回调返回
        checkStartAnyWhereCallback(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkStartAnyWhereCallback(intent)
    }

    /**
     * 检查是否从 StartAnyWhere 回调返回
     *
     * 如果 intent 包含特殊标志符，说明 URI 权限已获取，显示 toast 提示用户再次点击注入
     */
    private fun checkStartAnyWhereCallback(intent: Intent?) {
        if (intent?.getStringExtra(STARTANYWHERE_CALLBACK) == "true") {
            Toast.makeText(this, "权限已获取，请再次点击注入按钮", Toast.LENGTH_LONG).show()
            // 清除标志，避免旋转屏幕重复触发
            intent.removeExtra(STARTANYWHERE_CALLBACK)
        }
    }

    override fun onResume() {
        super.onResume()
        // 从后台恢复时重新加载模块数量（静默，不报错）
        loadModuleSummary()
    }

    /**
     * 静默加载模块数量，更新主页面摘要
     *
     * 加载失败或为空时仅显示"未加载模块"，不弹任何错误对话框
     */
    private fun loadModuleSummary() {
        val packageName = binding.etPackageName.text?.toString()?.trim().orEmpty()
        if (packageName.isEmpty()) {
            moduleList.clear()
            updateModuleSummary()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val modules = try {
                PluginManager.loadModules(this@MainActivity, packageName)
            } catch (e: Exception) {
                emptyList<ModuleInfo>()
            }
            withContext(Dispatchers.Main) {
                moduleList.clear()
                moduleList.addAll(modules)
                updateModuleSummary()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        // 包名输入框已有内容，立即检测版本号
        val pkg = binding.etPackageName.text?.toString()?.trim().orEmpty()
        if (pkg.isNotEmpty()) {
            detectPackageVersion(pkg)
        }
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_package_version -> {
                showVersionEditDialog()
                true
            }
            R.id.action_revoke_permissions -> {
                revokeAllPermissions()
                true
            }
            R.id.action_custom_intent -> {
                showCustomIntentDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ==================== 释放所有权限 ====================

    private fun revokeAllPermissions() {
        try {
            val resolver = contentResolver
            val permissions = resolver.persistedUriPermissions
            for (perm in permissions) {
                resolver.releasePersistableUriPermission(
                    perm.uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                Log.d("MainActivity", "已释放: " + perm.uri)
            }
            Toast.makeText(
                this,
                getString(R.string.toast_permissions_revoked) + " (" + permissions.size + " 个)",
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                this,
                getString(R.string.toast_permissions_revoke_failed) + ": " + e.toString(),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ==================== 应用列表对话框 ====================

    private fun showAppListDialog() {
        val dialog = Dialog(this, R.style.Theme_WNLZInjector_NoAnim)
        val dialogBinding = DialogAppListBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        dialog.window?.let { window ->
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            window.setGravity(Gravity.CENTER)
        }

        val adapter = AppAdapter { app ->
            binding.etPackageName.setText(app.packageName)
            binding.etPackageName.setSelection(app.packageName.length)
            dialog.dismiss()
        }

        dialogBinding.rvAppList.layoutManager = LinearLayoutManager(this)
        dialogBinding.rvAppList.adapter = adapter

        dialogBinding.btnClose.setOnClickListener { dialog.dismiss() }

        var allApps: List<AppInfo> = emptyList()
        dialogBinding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim()?.lowercase().orEmpty()
                val filtered = if (query.isEmpty()) allApps else allApps.filter {
                    it.appName.lowercase().contains(query) ||
                        it.packageName.lowercase().contains(query)
                }
                adapter.submitList(filtered)
            }
        })

        dialog.setOnDismissListener { allApps = emptyList() }

        dialog.show()

        dialogBinding.progressBar.visibility = android.view.View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            allApps = loadNonSystemApps()
            withContext(Dispatchers.Main) {
                dialogBinding.progressBar.visibility = android.view.View.GONE
                adapter.submitList(allApps)
            }
        }
    }

    private fun loadNonSystemApps(): List<AppInfo> {
        val pm = packageManager
        val packages = pm.getInstalledApplications(0)
        return packages
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
            .map { appInfo ->
                AppInfo(
                    appName = pm.getApplicationLabel(appInfo).toString(),
                    packageName = appInfo.packageName,
                    icon = appInfo.loadIcon(pm)
                )
            }
    }

    // ==================== 模块列表对话框 ====================

    private fun showModuleListDialog() {
        val packageName = binding.etPackageName.text?.toString()?.trim().orEmpty()
        if (packageName.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_package, Toast.LENGTH_SHORT).show()
            return
        }

        moduleDialog = Dialog(this, R.style.Theme_WNLZInjector_NoAnim)
        val dialogBinding = DialogModuleListBinding.inflate(layoutInflater)
        moduleDialog!!.setContentView(dialogBinding.root)

        moduleDialog!!.window?.let { window ->
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            window.setGravity(Gravity.CENTER)
        }

        moduleAdapter = ModuleAdapter(moduleList)

        // 长按进入选择模式时，显示删除按钮栏
        moduleAdapter!!.onSelectionStarted = {
            dialogBinding.selectionBar.visibility = View.VISIBLE
        }

        // 选择项变化时更新计数
        moduleAdapter!!.onSelectionChanged = { count ->
            dialogBinding.tvSelectionCount.text = "已选择 $count 项"
        }

        dialogBinding.rvModuleList.layoutManager = LinearLayoutManager(this)
        dialogBinding.rvModuleList.adapter = moduleAdapter

        // 删除按钮
        dialogBinding.btnDelete.setOnClickListener {
            val selected = moduleAdapter?.getSelectedModules().orEmpty()
            if (selected.isEmpty()) {
                Toast.makeText(this, R.string.toast_delete_failed, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.btn_delete)
                .setMessage("确认删除 ${selected.size} 个模块？")
                .setPositiveButton(R.string.action_confirm) { _, _ ->
                    val toDelete = selected.map { it.zipName }.toSet()
                    lifecycleScope.launch(Dispatchers.IO) {
                        var allOk = true
                        for (zipName in toDelete) {
                            val ok = PluginManager.deletePlugin(this@MainActivity, packageName, zipName)
                            if (!ok) allOk = false
                        }
                        withContext(Dispatchers.Main) {
                            moduleAdapter?.removeModules(toDelete)
                            moduleAdapter?.exitSelectionMode()
                            dialogBinding.selectionBar.visibility = View.GONE
                            updateModuleSummary()
                            Toast.makeText(
                                this@MainActivity,
                                if (allOk) R.string.toast_delete_success else R.string.toast_delete_failed,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        dialogBinding.btnModuleClose.setOnClickListener {
            // 如果在选择模式，先退出选择模式
            moduleAdapter?.exitSelectionMode()
            dialogBinding.selectionBar.visibility = View.GONE
            moduleDialog?.dismiss()
        }

        // 导入按钮
        dialogBinding.btnImport.setOnClickListener {
            openFileLauncher.launch(arrayOf("application/zip", "*/*"))
        }

        moduleDialog!!.setOnDismissListener {
            moduleDialog = null
            moduleAdapter = null
        }

        moduleDialog!!.show()

        // 加载模块
        dialogBinding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val loadResult = PluginManager.loadModulesWithLogs(this@MainActivity, packageName)
            withContext(Dispatchers.Main) {
                dialogBinding.progressBar.visibility = View.GONE
                moduleList.clear()
                moduleList.addAll(loadResult.modules)
                moduleAdapter?.notifyDataSetChanged()
                if (loadResult.modules.isEmpty()) {
                    dialogBinding.tvEmpty.visibility = View.VISIBLE
                    showLoadErrorDialog(loadResult)
                }
            }
        }
    }

    // ==================== 导入插件 ====================

    /**
     * 导入插件
     */
    private fun importPlugin(uri: Uri) {
        val packageName = binding.etPackageName.text?.toString()?.trim().orEmpty()
        if (packageName.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_package, Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val result = PluginManager.importPlugin(this@MainActivity, packageName, uri)
            withContext(Dispatchers.Main) {
                if (result.success) {
                    // 导入成功也展示详情, 方便用户确认
                    showImportResultDialog(result, packageName)
                } else {
                    showImportErrorDialog(result)
                }
            }
        }
    }

    /**
     * 显示导入成功详情对话框（含刷新列表）
     */
    private fun showImportResultDialog(result: ImportResult, packageName: String) {
        val logsText = result.logs.joinToString("\n")
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_import_success_title)
            .setMessage(logsText)
            .setPositiveButton(R.string.action_refresh) { _, _ ->
                refreshModuleList(packageName)
            }
            .setNeutralButton(R.string.action_copy_log) { _, _ ->
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                val clip = android.content.ClipData.newPlainText("import_log", logsText)
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * 显示导入失败详情对话框
     */
    private fun showImportErrorDialog(result: ImportResult) {
        val logsText = result.logs.joinToString("\n")
        val builder = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_import_failed_title)
            .setMessage(logsText)
            .setPositiveButton(R.string.action_confirm, null)

        builder.setNeutralButton(R.string.action_copy_log) { _, _ ->
            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
            val clip = android.content.ClipData.newPlainText("import_error_log", logsText)
            clipboard?.setPrimaryClip(clip)
            Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
        }

        builder.show()
    }

    /**
     * 显示模块加载详情对话框（列表为空时调用）
     */
    private fun showLoadErrorDialog(result: LoadResult) {
        val logsText = result.logs.joinToString("\n")
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_load_empty_title)
            .setMessage(logsText)
            .setPositiveButton(R.string.action_confirm, null)
            .setNeutralButton(R.string.action_copy_log) { _, _ ->
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                val clip = android.content.ClipData.newPlainText("load_log", logsText)
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * 刷新模块列表
     */
    private fun refreshModuleList(packageName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val loadResult = PluginManager.loadModulesWithLogs(this@MainActivity, packageName)
            withContext(Dispatchers.Main) {
                moduleList.clear()
                moduleList.addAll(loadResult.modules)
                moduleAdapter?.notifyDataSetChanged()
                updateModuleSummary()
                if (loadResult.modules.isEmpty()) {
                    showLoadErrorDialog(loadResult)
                }
            }
        }
    }

    /**
     * 更新模块摘要
     */
    private fun updateModuleSummary() {
        binding.tvModuleSummary.text = if (moduleList.isEmpty()) {
            getString(R.string.hint_no_module)
        } else {
            "已加载 ${moduleList.size} 个模块"
        }
        binding.tvModuleSummary.setTextColor(
            getColor(if (moduleList.isEmpty()) R.color.text_hint else R.color.text_primary)
        )
    }

    // ==================== 官包版本号 ====================

    /**
     * 自动检测包名的版本号，更新 [autoDetectedVersion] 并刷新菜单项
     */
    private fun detectPackageVersion(packageName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val version = try {
                val pm = packageManager
                val packageInfo = pm.getPackageInfo(packageName, 0)
                val vName = packageInfo.versionName ?: "unknown"
                val vCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    packageInfo.longVersionCode.toString()
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toString()
                }
                "${vName}_${vCode}"
            } catch (e: Exception) {
                null
            }
            withContext(Dispatchers.Main) {
                autoDetectedVersion = version
                updateVersionMenuItem()
            }
        }
    }

    /**
     * 更新菜单项标题，显示当前版本号
     */
    private fun updateVersionMenuItem() {
        // 优先显示自定义版本，否则显示自动检测版本
        val display = customVersion ?: autoDetectedVersion
        val menuItem = binding.toolbar.menu?.findItem(R.id.action_package_version)
        if (menuItem != null) {
            menuItem.title = if (display != null) {
                "官包版本号: $display"
            } else {
                getString(R.string.action_package_version)
            }
        }
    }

    /**
     * 弹出版本号编辑对话框
     */
    private fun showVersionEditDialog() {
        val currentDisplay = customVersion ?: autoDetectedVersion ?: ""

        val input = com.google.android.material.textfield.TextInputEditText(this).apply {
            setText(currentDisplay)
            hint = getString(R.string.dialog_version_hint)
            setSelection(length())
            inputType = android.text.InputType.TYPE_CLASS_TEXT
        }

        val container = com.google.android.material.textfield.TextInputLayout(this).apply {
            addView(input)
            hint = getString(R.string.dialog_version_hint)
            setBoxStrokeColorStateList(
                android.content.res.ColorStateList.valueOf(
                    getColor(R.color.primary)
                )
            )
            setPadding(48, 16, 48, 8)
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_version_title)
            .setView(container)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) {
                    // 留空 = 使用自动检测
                    customVersion = null
                    if (autoDetectedVersion != null) {
                        Toast.makeText(this, R.string.dialog_version_empty, Toast.LENGTH_SHORT).show()
                    }
                } else {
                    customVersion = text
                    Toast.makeText(this, R.string.toast_version_updated, Toast.LENGTH_SHORT).show()
                }
                updateVersionMenuItem()
            }
            .setNeutralButton(R.string.action_auto_detect) { _, _ ->
                customVersion = null
                if (autoDetectedVersion != null) {
                    Toast.makeText(this, R.string.dialog_version_empty, Toast.LENGTH_SHORT).show()
                }
                updateVersionMenuItem()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    /**
     * 注入 Intent 参数开关
     *
     * 将「自定义 Intent」改为：以开关（Switch）逐个控制注入时
     * intent1 / intent2 各自附带的 extra 是否生效。
     */
    private data class InjectParamDef(val group: String, val key: String, val label: String)

    /** 注入流程中可被单独开关的 extras（顺序即界面展示顺序） */
    private val INJECT_PARAM_DEFS = listOf(
        InjectParamDef("intent1", "openSDK_LOG.AssistActivity.ExtraIntent", "intent1 · ExtraIntent(intent2)"),
        InjectParamDef("intent1", "key_extra_pending_intent", "intent1 · PendingIntent"),
        InjectParamDef("intent1", "is_login", "intent1 · is_login"),
        InjectParamDef("intent2", STARTANYWHERE_CALLBACK, "intent2 · callback"),
        InjectParamDef("intent2", "key_request_code", "intent2 · key_request_code"),
        InjectParamDef("intent2", "appid", "intent2 · appid"),
        InjectParamDef("intent2", "for_result", "intent2 · for_result"),
    )

    /** 读取某 extra 的开关状态（默认开启） */
    private fun isInjectParamEnabled(key: String): Boolean {
        val map = org.json.JSONObject(prefs.getString(KEY_INJECT_PARAM_ENABLED, "{}").orEmpty())
        return if (map.has(key)) map.getBoolean(key) else true
    }

    /** 持久化某 extra 的开关状态 */
    private fun saveInjectParamEnabled(key: String, enabled: Boolean) {
        val map = org.json.JSONObject(prefs.getString(KEY_INJECT_PARAM_ENABLED, "{}").orEmpty())
        map.put(key, enabled)
        prefs.edit().putString(KEY_INJECT_PARAM_ENABLED, map.toString()).apply()
    }

    /**
     * 自定义 Intent 对话框：以开关形式控制注入 intent1 / intent2 各自附带的每个 extra。
     * 开关状态即时持久化，点击注入按钮时按开关组装 intent。
     */
    private fun showCustomIntentDialog() {
        val ctx = this

        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 16, 48, 16)
        }

        var currentGroup = ""
        for (def in INJECT_PARAM_DEFS) {
            if (def.group != currentGroup) {
                currentGroup = def.group
                root.addView(android.widget.TextView(ctx).apply {
                    text = if (def.group == "intent1") "intent1 附带参数" else "intent2 附带参数"
                    textSize = 14f
                    setPadding(0, 16, 0, 4)
                    setTextColor(getColor(R.color.text_primary))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
            }
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 6, 0, 6)
            }
            val label = android.widget.TextView(ctx).apply {
                text = def.label
                textSize = 13f
                layoutParams = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val sw = android.widget.Switch(ctx).apply {
                isChecked = isInjectParamEnabled(def.key)
                setOnCheckedChangeListener { _, checked -> saveInjectParamEnabled(def.key, checked) }
            }
            row.addView(label)
            row.addView(sw)
            root.addView(row)
        }

        val scroll = android.widget.ScrollView(ctx).apply { addView(root) }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("注入 Intent 参数开关")
            .setMessage("开启 / 关闭注入时 intent1 与 intent2 各自附带的 extra。开关即时保存，点击注入按钮时按此生效。")
            .setView(scroll)
            .setPositiveButton(R.string.action_confirm, null)
            .show()
    }

    // ==================== 注入按钮逻辑 ====================

    private fun onInjectClicked() {
        val packageName = binding.etPackageName.text?.toString()?.trim().orEmpty()
        if (packageName.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_package, Toast.LENGTH_SHORT).show()
            return
        }

        val method = binding.methodSelector.getSelectedMethod()
        if (method == null) {
            Toast.makeText(this, R.string.toast_no_method, Toast.LENGTH_SHORT).show()
            return
        }

        if (method == InjectionMethod.START_ANYWHERE) {
            startStartAnyWhereInjection(packageName)
        } else {
            Toast.makeText(this, R.string.toast_injecting, Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * StartAnyWhere 注入流程
     *
     * 1. 新线程执行，显示加载 dialog
     * 2. 构造目标 dex 的 content URI
     * 3. 尝试读取 URI 判断是否有权限
     * 4. 无权限：通过 AssistActivity + PendingIntent 授权
     * 5. 有权限：toast "准备注入"
     */
    private fun startStartAnyWhereInjection(targetPackage: String) {
        // 显示加载 dialog
        showLoadingDialog()

        Thread {
            try {
                // 获取目标包名版本信息
                val pm = packageManager
                val packageInfo = pm.getPackageInfo(targetPackage, 0)
                val versionName = packageInfo.versionName ?: "unknown"
                val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                    packageInfo.longVersionCode.toString()
                } else {
                    @Suppress("DEPRECATION")
                    packageInfo.versionCode.toString()
                }

                // 构造 dex 文件的 content URI
                // 使用自定义版本号（如果有），否则使用自动检测的版本号
                val versionSegment = this@MainActivity.customVersion ?: "${versionName}_${versionCode}"
                val dexUriStr = "content://com.netease.x19.osdkcommon.fileprovider/name/data/data/$targetPackage/app_ntp0/$versionSegment/.unzip/classes.dex"
                val dexUri = Uri.parse(dexUriStr)

                // 尝试读取 URI 判断是否有权限
                var hasPermission = false
                try {
                    val resolver = contentResolver
                    resolver.takePersistableUriPermission(
                        dexUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    val ins = resolver.openInputStream(dexUri)
                    if (ins != null) {
                        // 尝试读取几个字节
                        val buffer = ByteArray(4)
                        ins.read(buffer)
                        ins.close()
                        hasPermission = true
                    }
                } catch (e: Exception) {
                    // 无权限
                    hasPermission = false
                }

                if (hasPermission) {
                    // 已有权限，准备注入
                    runOnUiThread {
                        hideLoadingDialog()
                        Toast.makeText(this, "准备注入", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    // 无权限，通过 AssistActivity + PendingIntent 授权
                    // intent2: 合并「系统设置主页」与「分享请求参数」，作为 PendingIntent 与 ExtraIntent 的统一载体

                    // 先获取 MIME type，捕获 getType 的错误（校验 dexUri 可访问）
                    var mimeType: String? = null
                    try {
                        mimeType = contentResolver.getType(dexUri)
                    } catch (e: Exception) {
                        runOnUiThread {
                            hideLoadingDialog()
                            showInjectErrorDialog(e)
                        }
                        return@Thread
                    }

                    if (mimeType == null) {
                        runOnUiThread {
                            hideLoadingDialog()
                            val err = Exception("Failed to get type for: $dexUriStr\n\nContentResolver.getType() 返回 null，目标 URI 可能不存在或无权访问。")
                            showInjectErrorDialog(err)
                        }
                        return@Thread
                    }

                    // intent2: 合并「自身 MainActivity + 授权 flags」与「分享请求参数」，
                    // 作为 PendingIntent 与 ExtraIntent 的统一载体。
                    // PendingIntent 触发时不再跳到系统设置，而是回到本 app 的 MainActivity，
                    // 由 MainActivity 的 onActivityResult 拿到 URI 授权结果（FLAG_GRANT_* 仍然有效）。
                    val intent2 = Intent()
                        .setComponent(ComponentName(packageName, "com.wunelezi.injector.MainActivity"))
                        .setDataAndType(dexUri, mimeType)
                        .addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                            Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    if (isInjectParamEnabled(STARTANYWHERE_CALLBACK)) intent2.putExtra(STARTANYWHERE_CALLBACK, "true")
                    if (isInjectParamEnabled("key_request_code")) intent2.putExtra("key_request_code", 0x2782)
                    if (isInjectParamEnabled("appid")) intent2.putExtra("appid", "1106798370")
                    if (isInjectParamEnabled("for_result")) intent2.putExtra("for_result", false)

                    // 创建 PendingIntent（基于合并后的 intent2）
                    val pendingIntent = android.app.PendingIntent.getActivity(
                        this, 0, intent2,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )

                    // intent1: 根据 targetPackage 是否为自身包动态选择 AssistActivity
                    //   自身包 → 本 app 自带 AssistActivity（com.wunelezi.injector.AssistActivity）
                    //   目标包 → 目标 app 内的腾讯 SDK AssistActivity（com.tencent.connect.common.AssistActivity）
                    val assistActivityCls = if (targetPackage == packageName) {
                        "com.wunelezi.injector.AssistActivity"
                    } else {
                        "com.tencent.connect.common.AssistActivity"
                    }
                    val intent1 = Intent()
                        .setComponent(ComponentName(targetPackage, assistActivityCls))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                    if (isInjectParamEnabled("openSDK_LOG.AssistActivity.ExtraIntent")) intent1.putExtra("openSDK_LOG.AssistActivity.ExtraIntent", intent2)
                    if (isInjectParamEnabled("key_extra_pending_intent")) intent1.putExtra("key_extra_pending_intent", pendingIntent)
                    if (isInjectParamEnabled("is_login")) intent1.putExtra("is_login", true)

                    runOnUiThread {
                        hideLoadingDialog()
                        Toast.makeText(this, "正在获取权限...", Toast.LENGTH_SHORT).show()
                        // 走 StartAnyWhere.pullSpecialActivity 链路启动 AssistActivity
                        com.cfks.startanywhere.StartAnyWhere.pullSpecialActivity(this, intent1)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    hideLoadingDialog()
                    showInjectErrorDialog(e)
                }
            }
        }.start()
    }

    /**
     * 显示注入失败错误对话框
     */
    private fun showInjectErrorDialog(e: Exception) {
        val errorMsg = StringBuilder()
        errorMsg.append("注入失败\n\n")
        errorMsg.append("异常类型: ${e::class.java.simpleName}\n")
        errorMsg.append("错误信息: ${e.message}\n\n")
        errorMsg.append("完整堆栈:\n")
        e.stackTrace.take(15).forEach { stackTraceElement ->
            errorMsg.append("  at $stackTraceElement\n")
        }

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("注入失败")
            .setMessage(errorMsg.toString())
            .setPositiveButton(R.string.action_confirm, null)
            .setNeutralButton(R.string.action_copy_log) { _, _ ->
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                val clip = android.content.ClipData.newPlainText("inject_error", errorMsg.toString())
                clipboard?.setPrimaryClip(clip)
                Toast.makeText(this, R.string.toast_copied, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    /**
     * 显示圆圈加载 dialog
     */
    private fun showLoadingDialog() {
        if (loadingDialog?.isShowing == true) return
        loadingDialog = Dialog(this).apply {
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        }
        val progressBar = android.widget.ProgressBar(this).apply {
            isIndeterminate = true
        }
        loadingDialog?.setContentView(progressBar)
        loadingDialog?.window?.let { window ->
            window.setLayout(200, 200)
            window.setGravity(Gravity.CENTER)
            window.setBackgroundDrawableResource(android.R.color.transparent)
        }
        loadingDialog?.show()
    }

    /**
     * 隐藏加载 dialog
     */
    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }
}
