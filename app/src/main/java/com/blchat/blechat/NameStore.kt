package com.blchat.blechat

import android.content.Context
import android.content.SharedPreferences

/**
 * ID -> 名称 映射表（本地持久化，可随广播同步）。
 * 键以 "name_" 前缀存入 SharedPreferences，本机自己的名字也按 myId 存。
 */
class NameStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("name_store", Context.MODE_PRIVATE)

    private companion object {
        const val PREFIX = "name_"
    }

    /** 查询某 ID 的显示名称；未设置或为空返回 null */
    fun getName(id: String): String? =
        prefs.getString(PREFIX + id, null)?.takeIf { it.isNotBlank() }

    /** 设置 / 覆盖某 ID 的显示名称；空名则删除 */
    fun setName(id: String, name: String) {
        if (name.isBlank()) prefs.edit().remove(PREFIX + id).apply()
        else prefs.edit().putString(PREFIX + id, name.trim()).apply()
    }

    /** 全部映射（含本机名字，按 myId 存），用于同步 */
    fun allEntries(): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for ((k, v) in prefs.all) {
            if (k.startsWith(PREFIX) && v is String && v.isNotBlank()) {
                map[k.removePrefix(PREFIX)] = v
            }
        }
        return map
    }

    /** 合并接收到的映射（幂等，覆盖同名） */
    fun merge(entries: Map<String, String>) {
        val e = prefs.edit()
        for ((id, name) in entries) {
            if (id.isNotBlank() && name.isNotBlank()) {
                e.putString(PREFIX + id, name.trim())
            }
        }
        e.apply()
    }
}
