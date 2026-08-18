package com.wunelezi.injector.widget

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.wunelezi.injector.R
import com.wunelezi.injector.adapter.MethodAdapter
import com.wunelezi.injector.databinding.DialogMethodListBinding
import com.wunelezi.injector.model.InjectionMethod

/**
 * 注入方式选择自定义组件
 *
 * 点击后弹出 BottomSheet 展示所有可用注入方式。
 * 设备不支持的方案名称标红；选择不支持的方案时会弹出
 * 设备不支持对话框，点击"确定"后才真正选中。
 */
class MethodSelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private var tvMethodName: TextView
    private var tvStatus: TextView
    private var ivArrow: ImageView

    private var selectedMethod: InjectionMethod? = null
    private var onMethodSelectedListener: ((InjectionMethod) -> Unit)? = null

    /** 所有可用的注入方式 */
    private val methods = InjectionMethod.values().toList()

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.view_method_selector, this, true)

        tvMethodName = findViewById(R.id.tvMethodName)
        tvStatus = findViewById(R.id.tvStatus)
        ivArrow = findViewById(R.id.ivArrow)

        setOnClickListener { showMethodDialog() }
    }

    /**
     * 弹出注入方式选择 BottomSheet
     */
    private fun showMethodDialog() {
        val dialog = BottomSheetDialog(context)
        val binding = DialogMethodListBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)

        binding.rvMethodList.layoutManager = LinearLayoutManager(context)
        binding.rvMethodList.adapter = MethodAdapter(methods, selectedMethod) { method ->
            dialog.dismiss()
            handleMethodSelection(method)
        }

        dialog.show()
    }

    /**
     * 处理方式选择逻辑
     *
     * 若设备不支持该方式，弹出确认对话框。
     */
    private fun handleMethodSelection(method: InjectionMethod) {
        if (method.isSupported()) {
            selectMethod(method)
        } else {
            // 设备不支持 —— 弹出确认对话框
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dialog_unsupported_title)
                .setMessage(R.string.dialog_unsupported_msg)
                .setPositiveButton(R.string.action_confirm) { _, _ ->
                    selectMethod(method)
                }
                .setNegativeButton(R.string.action_cancel, null)
                .setCancelable(true)
                .show()
        }
    }

    /**
     * 正式选中某个注入方式，更新 UI
     */
    private fun selectMethod(method: InjectionMethod) {
        selectedMethod = method
        updateUI()
        onMethodSelectedListener?.invoke(method)
    }

    /**
     * 更新组件显示状态
     */
    private fun updateUI() {
        val method = selectedMethod
        if (method == null) {
            tvMethodName.text = context.getString(R.string.hint_select_method)
            tvMethodName.setTextColor(context.getColor(R.color.text_hint))
            tvStatus.visibility = View.GONE
        } else {
            tvMethodName.text = method.displayName
            val supported = method.isSupported()
            tvMethodName.setTextColor(
                context.getColor(if (supported) R.color.text_primary else R.color.error_red)
            )
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = context.getString(
                if (supported) R.string.supported else R.string.not_supported
            )
            tvStatus.setTextColor(
                context.getColor(if (supported) R.color.success_green else R.color.error_red)
            )
        }
    }

    /** 获取当前选中的注入方式 */
    fun getSelectedMethod(): InjectionMethod? = selectedMethod

    /** 设置选中监听 */
    fun setOnMethodSelectedListener(listener: (InjectionMethod) -> Unit) {
        onMethodSelectedListener = listener
    }
}
