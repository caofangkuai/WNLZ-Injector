package com.wunelezi.injector

import android.app.Dialog
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.wunelezi.injector.adapter.AppAdapter
import com.wunelezi.injector.adapter.ModuleAdapter
import com.wunelezi.injector.databinding.ActivityMainBinding
import com.wunelezi.injector.databinding.DialogAppListBinding
import com.wunelezi.injector.databinding.DialogModuleListBinding
import com.wunelezi.injector.model.AppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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

        // 恢复已选模块的摘要显示
        updateModuleSummary()
    }

    // ==================== 应用列表对话框 ====================

    /**
     * 弹出应用选择对话框，长按触发。
     * 后台加载非系统应用列表并展示。
     */
    private fun showAppListDialog() {
        val dialog = Dialog(this, R.style.Theme_WNLZInjector_NoAnim)
        val dialogBinding = DialogAppListBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        // 全屏对话框
        dialog.window?.let { window ->
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            window.setGravity(Gravity.CENTER)
        }

        val adapter = AppAdapter { app ->
            // 选中应用 → 自动填写包名
            binding.etPackageName.setText(app.packageName)
            binding.etPackageName.setSelection(app.packageName.length)
            dialog.dismiss()
        }

        dialogBinding.rvAppList.layoutManager = LinearLayoutManager(this)
        dialogBinding.rvAppList.adapter = adapter

        // 关闭按钮
        dialogBinding.btnClose.setOnClickListener { dialog.dismiss() }

        // 搜索过滤
        var allApps: List<AppInfo> = emptyList()
        dialogBinding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString()?.trim()?.lowercase().orEmpty()
                val filtered = if (query.isEmpty()) {
                    allApps
                } else {
                    allApps.filter {
                        it.appName.lowercase().contains(query) ||
                            it.packageName.lowercase().contains(query)
                    }
                }
                adapter.submitList(filtered)
            }
        })

        dialog.setOnDismissListener {
            allApps = emptyList()
        }

        dialog.show()

        // 后台加载应用列表
        dialogBinding.progressBar.visibility = android.view.View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            allApps = loadNonSystemApps()
            withContext(Dispatchers.Main) {
                dialogBinding.progressBar.visibility = android.view.View.GONE
                adapter.submitList(allApps)
            }
        }
    }

    /**
     * 加载所有非系统应用（用户安装的第三方应用）
     */
    private fun loadNonSystemApps(): List<AppInfo> {
        val pm = packageManager
        val packages = pm.getInstalledApplications(0)
        return packages
            .filter {
                (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            }
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

    /**
     * 弹出模块多选对话框。
     * 列出 AndroidManifest 中带有 wnlzmodule meta-data 的应用。
     * 选中状态持久化到 SharedPreferences。
     */
    private fun showModuleListDialog() {
        val dialog = Dialog(this, R.style.Theme_WNLZInjector_NoAnim)
        val dialogBinding = DialogModuleListBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)

        dialog.window?.let { window ->
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
            )
            window.setGravity(Gravity.CENTER)
        }

        // 恢复之前持久化的选中状态
        val savedSelection = ModulePrefs.getSelectedModules(this)

        val adapter = ModuleAdapter { /* 单项点击仅切换勾选 */ }
        dialogBinding.rvModuleList.layoutManager = LinearLayoutManager(this)
        dialogBinding.rvModuleList.adapter = adapter

        dialogBinding.btnModuleClose.setOnClickListener { dialog.dismiss() }

        // 确认按钮 —— 保存并更新摘要
        dialogBinding.btnModuleConfirm.setOnClickListener {
            val selected = adapter.getSelected()
            ModulePrefs.saveSelectedModules(this, selected)
            updateModuleSummary()
            val msg = getString(R.string.toast_modules_saved, selected.size)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()

        // 后台加载模块列表
        dialogBinding.progressBar.visibility = android.view.View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val modules = loadWnlzModules()
            withContext(Dispatchers.Main) {
                dialogBinding.progressBar.visibility = android.view.View.GONE
                if (modules.isEmpty()) {
                    dialogBinding.tvEmpty.visibility = android.view.View.VISIBLE
                } else {
                    adapter.submitList(modules, savedSelection)
                }
            }
        }
    }

    /**
     * 加载所有带有 wnlzmodule meta-data 的应用
     *
     * 目标: AndroidManifest 中含有
     *   <meta-data android:name="wnlzmodule" android:value="true" />
     */
    private fun loadWnlzModules(): List<AppInfo> {
        val pm = packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        return packages
            .filter { appInfo ->
                appInfo.metaData?.let { meta ->
                    meta.getBoolean("wnlzmodule", false)
                } ?: false
            }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
            .map { appInfo ->
                AppInfo(
                    appName = pm.getApplicationLabel(appInfo).toString(),
                    packageName = appInfo.packageName,
                    icon = appInfo.loadIcon(pm)
                )
            }
    }

    /**
     * 更新主界面上模块选择的摘要文本
     */
    private fun updateModuleSummary() {
        val selected = ModulePrefs.getSelectedModules(this)
        binding.tvModuleSummary.text = if (selected.isEmpty()) {
            getString(R.string.hint_no_module_selected)
        } else {
            "已选 ${selected.size} 个模块"
        }
        binding.tvModuleSummary.setTextColor(
            getColor(if (selected.isEmpty()) R.color.text_hint else R.color.text_primary)
        )
    }

    // ==================== 注入按钮逻辑 ====================

    /**
     * 注入按钮点击处理（占位逻辑）
     */
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
