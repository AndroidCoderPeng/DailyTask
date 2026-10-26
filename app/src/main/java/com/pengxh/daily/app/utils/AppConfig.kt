package com.pengxh.daily.app.utils

import com.google.gson.JsonObject
import com.pengxh.kt.lite.utils.SaveKeyValues

/**
 * 统一配置门面：屏蔽 SP 与 ConfigStore 的差异，调用方只认 AppConfig。
 * - 基础类型走 SaveKeyValues(SP)
 * - 结构化/凭据字符串与对象走 ConfigStore。
 */
object AppConfig {

    private val CONFIG_STORE_STRING_KEYS = setOf(Constant.CUSTOM_WORKDAYS_KEY)

    fun init() {
        migrateFromSpIfAbsent(Constant.CUSTOM_WORKDAYS_KEY) { spValue ->
            JsonObject().apply { addProperty("value", spValue) }
        }
    }

    private fun migrateFromSpIfAbsent(key: String, toJson: (String) -> JsonObject) {
        if (key in CONFIG_STORE_STRING_KEYS && !ConfigStore.get().contains(key)) {
            val spValue = SaveKeyValues.loadString(key, "")
            if (spValue.isNotBlank()) {
                ConfigStore.get().save(key, toJson(spValue))
            }
        }
    }

    // ===================== 基础类型（SP） =====================
    fun loadInt(key: String, def: Int) = SaveKeyValues.loadInt(key, def)
    fun loadBoolean(key: String, def: Boolean) = SaveKeyValues.loadBoolean(key, def)
    fun loadString(key: String, def: String) = SaveKeyValues.loadString(key, def)
    fun saveInt(key: String, value: Int) = SaveKeyValues.saveInt(key, value)
    fun saveBoolean(key: String, value: Boolean) = SaveKeyValues.saveBoolean(key, value)

    // ===================== 字符串（按路由落 SP 或 ConfigStore） =====================
    fun saveString(key: String, value: String) {
        if (key in CONFIG_STORE_STRING_KEYS) {
            ConfigStore.get().save(key, JsonObject().apply { addProperty("value", value) })
        } else {
            SaveKeyValues.saveString(key, value)
        }
    }

    // ===================== 结构化对象（ConfigStore） =====================
    fun loadJson(key: String): JsonObject = ConfigStore.get().load(key)

    fun saveJson(key: String, obj: JsonObject) {
        ConfigStore.get().save(key, obj)
    }

    // 企业微信 Key 与渠道号的原子保存
    fun saveWebhookAndChannel(webhookKey: String, channel: Int): Boolean =
        SaveKeyValues.saveStringAndIntAtomically(
            Constant.WX_WEB_HOOK_KEY, webhookKey, Constant.MSG_CHANNEL_KEY, channel
        )
}
