package com.pengxh.kt.lite.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object SaveKeyValues {
    private lateinit var sp: SharedPreferences

    fun initialize(context: Context) {
        val packageName = context.packageName
        //获取到的包名带有“.”方便命名，取最后一个作为sp文件名
        val split = packageName.split(".")
        val fileName = if (split.isNotEmpty()) split.last() else "default_prefs"
        sp = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    }

    // ==================== put ====================
    @Deprecated("请使用基本类型save方法")
    fun putValue(key: String, any: Any?) {
        if (key.isBlank()) {
            return
        }
        if (any == null) {
            removeKey(key)
            return
        }
        when (any) {
            is String -> saveString(key, any)
            is Int -> saveInt(key, any)
            is Boolean -> saveBoolean(key, any)
            is Float -> saveFloat(key, any)
            is Long -> saveLong(key, any)
            else -> saveString(key, any.toString())
        }
    }

    fun saveString(key: String, value: String) {
        if (key.isBlank()) return
        if (!::sp.isInitialized) return
        try {
            sp.edit { putString(key, value) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveInt(key: String, value: Int) {
        if (key.isBlank()) return
        if (!::sp.isInitialized) return
        try {
            sp.edit { putInt(key, value) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveLong(key: String, value: Long) {
        if (key.isBlank()) return
        if (!::sp.isInitialized) return
        try {
            sp.edit { putLong(key, value) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveFloat(key: String, value: Float) {
        if (key.isBlank()) return
        if (!::sp.isInitialized) return
        try {
            sp.edit { putFloat(key, value) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun saveBoolean(key: String, value: Boolean) {
        if (key.isBlank()) return
        if (!::sp.isInitialized) return
        try {
            sp.edit { putBoolean(key, value) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ==================== get ====================
    @Deprecated("请使用基本类型load方法")
    fun getValue(key: String, defaultObject: Any): Any? {
        if (key.isBlank()) {
            return null
        }
        return when (defaultObject) {
            is String -> loadString(key, defaultObject)
            is Int -> loadInt(key, defaultObject)
            is Boolean -> loadBoolean(key, defaultObject)
            is Float -> loadFloat(key, defaultObject)
            is Long -> loadLong(key, defaultObject)
            else -> defaultObject
        }
    }

    fun loadString(key: String, defaultValue: String = ""): String {
        if (key.isBlank()) return defaultValue
        if (!::sp.isInitialized) return defaultValue
        return sp.getString(key, defaultValue) ?: defaultValue
    }

    fun loadInt(key: String, defaultValue: Int = 0): Int {
        if (key.isBlank()) return defaultValue
        if (!::sp.isInitialized) return defaultValue
        return sp.getInt(key, defaultValue)
    }

    fun loadLong(key: String, defaultValue: Long = 0L): Long {
        if (key.isBlank()) return defaultValue
        if (!::sp.isInitialized) return defaultValue
        return sp.getLong(key, defaultValue)
    }

    fun loadFloat(key: String, defaultValue: Float = 0f): Float {
        if (key.isBlank()) return defaultValue
        if (!::sp.isInitialized) return defaultValue
        return sp.getFloat(key, defaultValue)
    }

    fun loadBoolean(key: String, defaultValue: Boolean = false): Boolean {
        if (key.isBlank()) return defaultValue
        if (!::sp.isInitialized) return defaultValue
        return sp.getBoolean(key, defaultValue)
    }

    /**
     * 移除某个key和value
     */
    fun removeKey(key: String) {
        if (key.isBlank()) return
        if (!::sp.isInitialized) return
        try {
            sp.edit { remove(key) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 清除所有数据
     */
    fun clearAll() {
        if (!::sp.isInitialized) return
        try {
            sp.edit { clear() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 查询某个key是否存在
     */
    fun containsKey(key: String): Boolean {
        if (!::sp.isInitialized) return false
        if (key.isBlank()) {
            return false
        }
        return sp.contains(key)
    }
}