package com.wunelezi.injector

import android.app.Dialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.wunelezi.injector.adapter.AppAdapter
import com.wunelezi.injector.adapter.ModuleAdapter
import com.wunelezi.injector.databinding.ActivityMainBinding
import com.wunelezi.injector.databinding.DialogAppListBinding
import com.wunelezi.injector.databinding.DialogModuleListBinding
import com.wunelezi.injector.model.AppInfo
import com.wunelezi.injector.model.ImportResult
import com.wunelezi.injector.model.ModuleInfo
import com.wunelezi.injector.util.PluginManager
import com.wunelezi.injector.util.ShizukuHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 当前模块列表数据（供删除后刷新） */
    private var moduleList: MutableList<ModuleInfo> = mutableListOf()
    private var moduleAdapter: ModuleAdapter? = null
    private var moduleDialog: Dialog? = null

    /** SAF 文件选择 launcher */
    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            importPlugin(uri)
        }
    }

    /** 标准存储权限请求 launcher (Android 10 及以下) */
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            Toast.makeText(this, R.string.toast_storage_granted, Toast.LENGTH_SHORT).show()
            checkAndRequestAllFilesAccess()
        } else {
            Toast.makeText(this, R.string.toast_storage_denied, Toast.LENGTH_SHORT).show()
            checkAndRequestAllFilesAccess()
        }
    }

    /** 所有文件访问权限返回结果 */
    private val allFilesAccessLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(this, R.string.toast_all_files_granted, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.toast_all_files_denied, Toast.LENGTH_SHORT).show()
            }
        }
        // 无论结果如何, 继续检查 Shizuku
        checkAndRequestShizukuPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // 包名输入框 —— 长按弹出非系统应用列表
        binding.etPackageName.setOnLongClickListener {
            showAppListDialog()
            true
        }

        // 注入模块选择卡片 —— 点击弹出模块列表
        binding.cardModules.setOnClickListener { showModuleListDialog() }

        // 注入按钮
        binding.btnInject.setOnClickListener {
            onInjectClicked()
        }

        // 初始更新模块摘要
        updateModuleSummary()

        // 自动申请所有文件管理权限
        autoRequestAllPermissions()
    }

    // ==================== 权限自动申请 ====================

    /**
     * 自动申请所有权限的入口
     *
     * 顺序：标准存储权限 → 所有文件访问权限 → Shizuku 权限
     */
    private fun autoRequestAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+：先检查是否有 MANAGE_EXTERNAL_STORAGE
            if (!Environment.isExternalStorageManager()) {
                checkAndRequestAllFilesAccess()
            } else {
                // 已有所有文件访问权限, 检查 Shizuku
                checkAndRequestShizukuPermission()
            }
        } else {
            // Android 10 及以下：申请标准存储权限
            val permissions = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val readGranted = ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
                val writeGranted = ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
                if (!readGranted) permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                if (!writeGranted && Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                    permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            if (permissions.isNotEmpty()) {
                storagePermissionLauncher.launch(permissions.toTypedArray())
            } else {
                checkAndRequestShizukuPermission()
            }
        }
    }

    /**
     * 申请"所有文件访问权限" (MANAGE_EXTERNAL_STORAGE, Android 11+)
     */
    private fun checkAndRequestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    allFilesAccessLauncher.launch(intent)
                } catch (e: Exception) {
                    // 某些设备不支持直接跳转, 尝试通用页面
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        allFilesAccessLauncher.launch(intent)
                    } catch (e2: Exception) {
                        Toast.makeText(this, R.string.toast_all_files_unsupported, Toast.LENGTH_LONG).show()
                        checkAndRequestShizukuPermission()
                    }
                }
            } else {
                checkAndRequestShizukuPermission()
            }
        }
    }

    /**
     * 检查并申请 Shizuku 权限
     */
    private fun checkAndRequestShizukuPermission() {
        if (!ShizukuHelper.isAvailable()) {
            // Shizuku 未运行, 提示但不阻塞
            return
        }
        if (!ShizukuHelper.hasPermission()) {
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(this@MainActivity, R.string.toast_shizuku_granted, Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, R.string.toast_shizuku_denied, Toast.LENGTH_SHORT).show()
                    }
                    ShizukuHelper.removeListener(this)
                }
            }
            ShizukuHelper.requestPermission(listener)
        }
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
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
        dialogBinding.rvModuleList.layoutManager = LinearLayoutManager(this)
        dialogBinding.rvModuleList.adapter = moduleAdapter

        // 右滑删除
        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.RIGHT
        ) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.bindingAdapterPosition
                if (pos < 0 || pos >= moduleList.size) return
                val module = moduleList[pos]
                lifecycleScope.launch(Dispatchers.IO) {
                    val ok = PluginManager.deletePlugin(this@MainActivity, packageName, module.zipName)
                    withContext(Dispatchers.Main) {
                        if (ok) {
                            moduleList.removeAt(pos)
                            moduleAdapter?.notifyItemRemoved(pos)
                            Toast.makeText(this@MainActivity, R.string.toast_delete_success, Toast.LENGTH_SHORT).show()
                            updateModuleSummary()
                        } else {
                            moduleAdapter?.notifyItemChanged(pos)
                            Toast.makeText(this@MainActivity, R.string.toast_delete_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
        touchHelper.attachToRecyclerView(dialogBinding.rvModuleList)

        dialogBinding.btnModuleClose.setOnClickListener { moduleDialog?.dismiss() }

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
        dialogBinding.progressBar.visibility = android.view.View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val modules = PluginManager.loadModules(this@MainActivity, packageName)
            withContext(Dispatchers.Main) {
                dialogBinding.progressBar.visibility = android.view.View.GONE
                moduleList.clear()
                moduleList.addAll(modules)
                moduleAdapter?.notifyDataSetChanged()
                if (modules.isEmpty()) {
                    dialogBinding.tvEmpty.visibility = android.view.View.VISIBLE
                }
            }
        }
    }

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

        // 如果可以复制到剪贴板，提供复制按钮
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
            val modules = PluginManager.loadModules(this@MainActivity, packageName)
            withContext(Dispatchers.Main) {
                moduleList.clear()
                moduleList.addAll(modules)
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

    // ==================== 注入按钮逻辑 ====================

    private fun onInjectClicked() {
        val packageName = binding.etPackageName.text?.toString()?.trim().orEmpty()
        if (packageName.isEmpty()) {
            Toast.makeText(this, R.string.toast_no_package, Toast.LENGTH_SHORT).show()
            return
        }

        // 占位逻辑 —— 仅提示
        Toast.makeText(this, R.string.toast_injecting, Toast.LENGTH_SHORT).show()
    }
}
