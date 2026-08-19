package com.wunelezi.injector.model

/**
 * 模块加载结果
 *
 * 包含加载到的模块列表和过程中的详细日志
 */
data class LoadResult(
    val modules: List<ModuleInfo>,
    val logs: List<String>
)
