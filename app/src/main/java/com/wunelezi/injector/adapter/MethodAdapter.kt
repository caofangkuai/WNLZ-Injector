package com.wunelezi.injector.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.wunelezi.injector.R
import com.wunelezi.injector.databinding.ItemMethodBinding
import com.wunelezi.injector.model.InjectionMethod

/**
 * 注入方式列表适配器
 *
 * 不支持的方式名称标红显示
 */
class MethodAdapter(
    private val methods: List<InjectionMethod>,
    private val selectedMethod: InjectionMethod?,
    private val onItemClick: (InjectionMethod) -> Unit
) : RecyclerView.Adapter<MethodAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemMethodBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemMethodBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val method = methods[position]
        val supported = method.isSupported()

        with(holder.binding) {
            tvMethodName.text = method.displayName
            tvMethodDesc.text = method.description

            // 设备不支持时标红
            val nameColor = if (supported) {
                root.context.getColor(R.color.text_primary)
            } else {
                root.context.getColor(R.color.error_red)
            }
            tvMethodName.setTextColor(nameColor)

            // 状态标签
            if (supported) {
                tvMethodStatus.text = root.context.getString(R.string.supported)
                tvMethodStatus.setTextColor(root.context.getColor(R.color.success_green))
            } else {
                tvMethodStatus.text = root.context.getString(R.string.not_supported)
                tvMethodStatus.setTextColor(root.context.getColor(R.color.error_red))
            }

            root.setOnClickListener { onItemClick(method) }
        }
    }

    override fun getItemCount(): Int = methods.size
}
