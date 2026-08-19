package com.wunelezi.injector.model

/**
 * 导入结果
 *
 * 记录导入过程中每一步的详细信息，无论成功或失败都保留日志
 */
data class ImportResult(
    val success: Boolean,
    val zipName: String,
    val logs: List<String>
)
