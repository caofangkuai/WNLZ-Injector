package com.wunelezi.injector.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wunelezi.injector.databinding.ItemModuleBinding
import com.wunelezi.injector.model.AppInfo

/**
 * 模块多选适配器
 *
 * 点击 item 切换勾选状态
 */
class ModuleAdapter(
    private val onItemClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<ModuleAdapter.ViewHolder>() {

    private val modules = mutableListOf<AppInfo>()
    private val selected = mutableSetOf<String>()  // 选中的包名集合

    class ViewHolder(val binding: ItemModuleBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemModuleBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val module = modules[position]
        with(holder.binding) {
            ivModuleIcon.setImageDrawable(module.icon)
            tvModuleName.text = module.appName
            tvModulePackage.text = module.packageName
            cbModule.isChecked = selected.contains(module.packageName)
            root.setOnClickListener {
                if (selected.contains(module.packageName)) {
                    selected.remove(module.packageName)
                } else {
                    selected.add(module.packageName)
                }
                notifyItemChanged(position)
                onItemClick(module)
            }
        }
    }

    override fun getItemCount(): Int = modules.size

    /** 提交数据并恢复选中状态 */
    fun submitList(list: List<AppInfo>, preselected: Set<String> = emptySet()) {
        modules.clear()
        modules.addAll(list)
        selected.clear()
        selected.addAll(preselected)
        notifyDataSetChanged()
    }

    /** 获取选中的包名集合 */
    fun getSelected(): Set<String> = selected.toSet()

    /** 设置选中集合 */
    fun setSelected(packages: Set<String>) {
        selected.clear()
        selected.addAll(packages)
        notifyDataSetChanged()
    }
}
