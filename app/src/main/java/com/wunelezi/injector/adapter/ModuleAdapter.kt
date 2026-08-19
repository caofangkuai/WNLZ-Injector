package com.wunelezi.injector.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wunelezi.injector.databinding.ItemModuleBinding
import com.wunelezi.injector.model.ModuleInfo

/**
 * 模块列表适配器
 *
 * 支持长按进入选择模式，选择后可通过删除按钮批量删除。
 */
class ModuleAdapter(
    private val modules: MutableList<ModuleInfo>
) : RecyclerView.Adapter<ModuleAdapter.ViewHolder>() {

    /** 当前是否处于选择模式 */
    private var selectionMode = false

    /** 被选中的位置集合 */
    private val selectedPositions = mutableSetOf<Int>()

    /** 长按进入选择模式的回调 */
    var onSelectionStarted: (() -> Unit)? = null

    /** 选择项变化时的回调 (返回选中数量) */
    var onSelectionChanged: ((Int) -> Unit)? = null

    class ViewHolder(val binding: ItemModuleBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemModuleBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val module = modules[position]
        val isSelected = selectedPositions.contains(position)

        with(holder.binding) {
            tvModuleName.text = module.name
            tvModuleAuthor.text = "作者: ${module.author}"
            tvModuleZipName.text = module.zipName

            // 选择模式下显示 checkbox
            cbSelect.visibility = if (selectionMode) View.VISIBLE else View.GONE
            cbSelect.isChecked = isSelected

            // 选中状态高亮
            root.setBackgroundColor(
                if (isSelected) 0x33000000
                else 0
            )
        }

        // 点击：选择模式下切换选中；否则无操作
        holder.binding.root.setOnClickListener {
            if (selectionMode) {
                toggleSelection(position)
            }
        }

        // 长按：进入选择模式并选中当前项
        holder.binding.root.setOnLongClickListener {
            if (!selectionMode) {
                selectionMode = true
                selectedPositions.clear()
                selectedPositions.add(position)
                notifyDataSetChanged()
                onSelectionStarted?.invoke()
                onSelectionChanged?.invoke(1)
            } else {
                toggleSelection(position)
            }
            true
        }
    }

    private fun toggleSelection(position: Int) {
        if (selectedPositions.contains(position)) {
            selectedPositions.remove(position)
        } else {
            selectedPositions.add(position)
        }
        notifyItemChanged(position)
        onSelectionChanged?.invoke(selectedPositions.size)

        // 如果取消了所有选择，退出选择模式
        if (selectedPositions.isEmpty()) {
            exitSelectionMode()
        }
    }

    /** 退出选择模式 */
    fun exitSelectionMode() {
        selectionMode = false
        selectedPositions.clear()
        notifyDataSetChanged()
    }

    /** 获取被选中的模块列表 */
    fun getSelectedModules(): List<ModuleInfo> {
        return selectedPositions.mapNotNull { if (it < modules.size) modules[it] else null }
    }

    /** 从列表中移除指定模块（删除后调用） */
    fun removeModules(toRemove: Set<String>) {
        val iterator = modules.iterator()
        var index = 0
        while (iterator.hasNext()) {
            val module = iterator.next()
            if (toRemove.contains(module.zipName)) {
                iterator.remove()
                notifyItemRemoved(index)
            } else {
                index++
            }
        }
        // 重新绑定所有item以更新position
        selectedPositions.clear()
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = modules.size
}
