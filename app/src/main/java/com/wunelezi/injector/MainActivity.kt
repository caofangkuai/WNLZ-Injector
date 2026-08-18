package com.wunelezi.injector

import android.app.Dialog
import android.content.pm.ApplicationInfo
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
import com.wunelezi.injector.databinding.ActivityMainBinding
import com.wunelezi.injector.databinding.DialogAppListBinding
import com.wunelezi.injector.model.AppInfo
import com.wunelezi.injector.model.InjectionMethod
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

        // 注入按钮
        binding.btnInject.setOnClickListener {
            onInjectClicked()
        }
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
            // 清理引用防止内存泄漏
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
                // 排除系统应用，保留用户安装的第三方应用
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

        val method = binding.methodSelector.getSelectedMethod()
        if (method == null) {
            Toast.makeText(this, R.string.toast_no_method, Toast.LENGTH_SHORT).show()
            return
        }

        // 占位逻辑 —— 仅提示
        Toast.makeText(this, R.string.toast_injecting, Toast.LENGTH_SHORT).show()
    }
}
