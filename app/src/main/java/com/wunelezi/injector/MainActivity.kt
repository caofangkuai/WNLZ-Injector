package com.wunelezi.injector

import android.app.Dialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Toast
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

    /** SAF 文件选择 launcher */
    private val openFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            importPlugin(uri)
        }
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

        // 启动时自动加载模块数量（静默，不报错）
        loadModuleSummary()
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
