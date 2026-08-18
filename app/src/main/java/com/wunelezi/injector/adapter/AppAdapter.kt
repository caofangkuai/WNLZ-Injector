package com.wunelezi.injector.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wunelezi.injector.databinding.ItemAppBinding
import com.wunelezi.injector.model.AppInfo

/**
 * 应用列表适配器
 *
 * 支持动态更新列表（用于搜索过滤）
 */
class AppAdapter(
    private val onItemClick: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.ViewHolder>() {

    private val apps = mutableListOf<AppInfo>()

    class ViewHolder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAppBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = apps[position]
        with(holder.binding) {
            ivAppIcon.setImageDrawable(app.icon)
            tvAppName.text = app.appName
            tvAppPackage.text = app.packageName
            root.setOnClickListener { onItemClick(app) }
        }
    }

    override fun getItemCount(): Int = apps.size

    /** 提交新数据（全量替换） */
    fun submitList(list: List<AppInfo>) {
        apps.clear()
        apps.addAll(list)
        notifyDataSetChanged()
    }
}
