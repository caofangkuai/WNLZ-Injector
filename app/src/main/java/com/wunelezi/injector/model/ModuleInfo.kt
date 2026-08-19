package com.wunelezi.injector.model

/**
 * 插件模块信息
 *
 * 从 zip 中的 info.json 解析得到
 */
data class ModuleInfo(
    val zipName: String,
    val name: String,
    val author: String
)
