package com.pengxh.daily.app.utils

import com.google.gson.JsonObject
import com.pengxh.kt.lite.utils.SaveKeyValues

/**
 * 统一配置门面：屏蔽底层存储（SharedPreferences / ConfigStore JSON）差异，
 * 调用方只认 AppConfig，不再感知后端由谁承载。
 *
 * 路由规则：
 *  - 简单基础类型（Int/String/Boolean）→ SaveKeyValues（SharedPreferences）
 *  - 结构化/凭据字符串（如 CUSTOM_WORKDAYS_KEY）→ ConfigStore（JSON 文件，原子写入）
 *  - 结构化对象（如 EMAIL_CONFIG_KEY）→ ConfigStore
 *  - WX_WEB_HOOK_KEY 因与企业微信渠道号存在"原子保存"耦合，仍落 SP，但同样经本门面访问
 *
 * 必须在 ConfigStore.init(...) 之后调用 [init] 完成一次旧数据迁移。
 */
object AppConfig {

    /** 由 ConfigStore 承载的结构化字符串键（调用方无感知） */
    private val CONFIG_STORE_STRING_KEYS = setOf(Constant.CUSTOM_WORKDAYS_KEY)

    /**
     * 在 ConfigStore.init 之后调用。把仍在 SP 中的结构化字符串键迁移到 ConfigStore，
     * 保证历史用户数据不丢失；ConfigStore 已存在则不覆盖。
     */
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

    /**
     * 企业微信 Key 与消息渠道号的原子保存（保留原 SP 原子语义，跨存储无法原子故仅 Key 落 SP）。
     */
    fun saveWebhookAndChannel(webhookKey: String, channel: Int): Boolean =
        SaveKeyValues.saveStringAndIntAtomically(
            Constant.WX_WEB_HOOK_KEY, webhookKey, Constant.MSG_CHANNEL_KEY, channel
        )
}
