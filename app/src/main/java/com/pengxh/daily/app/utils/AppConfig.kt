package com.pengxh.daily.app.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.JsonObject
import com.pengxh.daily.app.utils.AppConfig.CONFIG_STORE_STRING_KEYS
import com.pengxh.daily.app.utils.AppConfig.backendOf
import com.pengxh.daily.app.utils.AppConfig.init
import com.pengxh.daily.app.utils.AppConfig.loadString
import com.pengxh.daily.app.utils.AppConfig.saveString

/**
 * 统一配置门面：对业务层只暴露 [AppConfig] 一个入口。
 *
 * 分层说明：
 * - SP（SharedPreferences）：轻量键值存储，实现内联为本类的私有方法，外部不可见；
 * - [ConfigStore]：底层 JSON 文件存储引擎（独立文件），可被本类和
 *   ChinaHolidayManager 等需要结构化数据的模块直接使用。
 *
 * 字符串 key 的存储后端由 [backendOf] 单点路由决定，读（[loadString]）与写
 * （[saveString]）共用同一个判断；新增 key 只需修改 [CONFIG_STORE_STRING_KEYS]，
 * 读写自动保持对称（曾因读写分别判断导致保存后读不回来的 bug）。
 */
object AppConfig {

    private lateinit var sp: SharedPreferences

    /** 需要落到 ConfigStore（JSON 文件）而不是 SP 的字符串 key */
    private val CONFIG_STORE_STRING_KEYS = setOf(Constant.CUSTOM_WORKDAYS_KEY)

    private enum class Backend { SP, CONFIG_STORE }

    private fun backendOf(key: String): Backend =
        if (key in CONFIG_STORE_STRING_KEYS) Backend.CONFIG_STORE else Backend.SP

    /**
     * 初始化 SP。必须在 Application.onCreate调用，
     * 且需在 [ConfigStore.init] 之前（[init] 的迁移逻辑会读 SP）。
     */
    fun initialize(context: Context) {
        val fileName = context.packageName.split(".").lastOrNull() ?: "default_prefs"
        sp = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    }

    /** SP 就绪且 ConfigStore 初始化后调用，把历史遗留的 SP 数据迁移到 ConfigStore（仅一次） */
    fun init() {
        migrateFromSpIfAbsent(Constant.CUSTOM_WORKDAYS_KEY) { spValue ->
            JsonObject().apply { addProperty("value", spValue) }
        }
    }

    private fun migrateFromSpIfAbsent(key: String, toJson: (String) -> JsonObject) {
        if (key in CONFIG_STORE_STRING_KEYS && !ConfigStore.get().contains(key)) {
            val spValue = spLoadString(key, "")
            if (spValue.isNotBlank()) {
                ConfigStore.get().save(key, toJson(spValue))
            }
        }
    }

    // ===================== 字符串（按 backendOf 路由） =====================

    fun loadString(key: String, def: String): String {
        if (key.isBlank()) return def
        return when (backendOf(key)) {
            Backend.CONFIG_STORE -> {
                val json = ConfigStore.get().load(key)
                json.get("value")?.takeIf { it.isJsonPrimitive }?.asString ?: def
            }

            Backend.SP -> spLoadString(key, def)
        }
    }

    fun saveString(key: String, value: String) {
        if (key.isBlank()) return
        when (backendOf(key)) {
            Backend.CONFIG_STORE ->
                ConfigStore.get().save(key, JsonObject().apply { addProperty("value", value) })

            Backend.SP -> spEdit { putString(key, value) }
        }
    }

    // ===================== 基础类型（固定走 SP） =====================

    fun loadInt(key: String, def: Int): Int = if (spReady()) sp.getInt(key, def) else def

    fun saveInt(key: String, value: Int) = spEdit { putInt(key, value) }

    fun loadBoolean(key: String, def: Boolean): Boolean =
        if (spReady()) sp.getBoolean(key, def) else def

    fun saveBoolean(key: String, value: Boolean) = spEdit { putBoolean(key, value) }

    fun loadLong(key: String, def: Long): Long = if (spReady()) sp.getLong(key, def) else def

    fun saveLong(key: String, value: Long) = spEdit { putLong(key, value) }

    fun loadFloat(key: String, def: Float): Float = if (spReady()) sp.getFloat(key, def) else def

    fun saveFloat(key: String, value: Float) = spEdit { putFloat(key, value) }

    // ===================== 结构化对象（ConfigStore） =====================

    fun loadJson(key: String): JsonObject = ConfigStore.get().load(key)

    fun saveJson(key: String, obj: JsonObject) {
        ConfigStore.get().save(key, obj)
    }

    // 企业微信 Key 与渠道号的原子保存（同一次 SP 提交）
    fun saveWebhookAndChannel(webhook: String, channel: Int): Boolean {
        if (!spReady()) return false
        return try {
            sp.edit {
                putString(Constant.WX_WEB_HOOK_KEY, webhook)
                putInt(Constant.MSG_CHANNEL_KEY, channel)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    // ===================== SP 私有实现 =====================

    private fun spReady() = ::sp.isInitialized

    private fun spEdit(action: SharedPreferences.Editor.() -> Unit) {
        if (!spReady()) return
        try {
            sp.edit(action = action)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun spLoadString(key: String, def: String): String {
        if (!spReady()) return def
        return sp.getString(key, def) ?: def
    }
}
