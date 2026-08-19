package com.wunelezi.injector

import android.content.Context
import android.content.SharedPreferences

/**
 * 模块选择持久化工具
 *
 * 使用 SharedPreferences 存储已选中的模块包名集合。
 */
object ModulePrefs {

    private const val PREFS_NAME = "wnlz_modules"
    private const val KEY_SELECTED = "selected_modules"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** 读取已选中的模块包名集合 */
    fun getSelectedModules(context: Context): Set<String> {
        return getPrefs(context).getStringSet(KEY_SELECTED, emptySet()) ?: emptySet()
    }

    /** 保存选中的模块包名集合 */
    fun saveSelectedModules(context: Context, packages: Set<String>) {
        getPrefs(context).edit().putStringSet(KEY_SELECTED, packages).apply()
    }
}
