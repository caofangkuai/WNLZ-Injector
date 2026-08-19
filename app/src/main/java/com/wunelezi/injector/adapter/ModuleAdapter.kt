package com.wunelezi.injector.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wunelezi.injector.databinding.ItemModuleBinding
import com.wunelezi.injector.model.ModuleInfo

/**
 * 模块列表适配器（只读展示，删除通过 ItemTouchHelper 处理）
 */
class ModuleAdapter(
    private val modules: List<ModuleInfo>
) : RecyclerView.Adapter<ModuleAdapter.ViewHolder>() {

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
            tvModuleName.text = module.name
            tvModuleAuthor.text = "作者: ${module.author}"
            tvModuleZipName.text = module.zipName
        }
    }

    override fun getItemCount(): Int = modules.size

    /** 获取指定位置的模块 */
    fun getItem(position: Int): ModuleInfo = modules[position]
}
