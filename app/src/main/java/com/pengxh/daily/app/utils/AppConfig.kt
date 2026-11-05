package com.pengxh.daily.app.utils

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import androidx.core.content.edit
import com.google.gson.JsonObject
import com.pengxh.daily.app.utils.AppConfig.CONFIG_STORE_KEYS
import com.pengxh.daily.app.utils.AppConfig.backendOf
import java.io.File
import java.io.IOException

/**
 * 统一持久化入口：业务层只认 [AppConfig]，不感知底层存储介质。
 *
 * - 简单键值 → SharedPreferences（本类私有实现）
 * - 结构化/需要人工检查的数据 → [ConfigStore]（JSON 文件引擎，也可被
 *   ChinaHolidayManager 等模块直接使用）
 *
 * 字符串 key 的后端由 [backendOf] 单点路由，读与写共用同一判断，
 * 新增 key 只需登记到 [CONFIG_STORE_KEYS]，读写自动保持对称。
 */
object AppConfig {

    private const val CONFIG_FILE_NAME = "DailyTaskConfig.json"
    private const val VALUE_FIELD = "value"

    private lateinit var sp: SharedPreferences

    /**
     * 落 ConfigStore 而不是 SP 的字符串 key
     * */
    private val CONFIG_STORE_KEYS = setOf(Constant.CUSTOM_WORKDAYS_KEY)

    private enum class Backend { SP, FILE }

    private fun backendOf(key: String): Backend =
        if (key in CONFIG_STORE_KEYS) Backend.FILE else Backend.SP

    /**
     * 唯一的初始化入口：SP、ConfigStore、旧数据迁移都在这里完成。
     * 在 Application.onCreate 最早期调用，晚于它的任何读写都是安全的。
     */
    fun initialize(context: Context) {
        sp = context.getSharedPreferences(
            context.packageName.substringAfterLast('.'), Context.MODE_PRIVATE
        )

        val configDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        val configFile = File(configDir, CONFIG_FILE_NAME)
        if (!configFile.exists()) {
            try {
                configFile.createNewFile()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        ConfigStore.init(configFile.absolutePath)

        migrateWorkdaysFromSp()
    }

    /** 历史遗留：
     * 工作日配置曾存于 SP，迁移到 ConfigStore（仅首次）
     * */
    private fun migrateWorkdaysFromSp() {
        val key = Constant.CUSTOM_WORKDAYS_KEY
        val store = ConfigStore.get()
        if (store.contains(key)) return
        val legacy = sp.getString(key, null)?.takeIf { it.isNotBlank() } ?: return
        store.save(key, JsonObject().apply { addProperty(VALUE_FIELD, legacy) })
    }

    // ===================== 字符串（按 backendOf 路由） =====================

    fun loadString(key: String, def: String): String = when (backendOf(key)) {
        Backend.FILE -> ConfigStore.get().load(key)
            .get(VALUE_FIELD)?.takeIf { it.isJsonPrimitive }?.asString ?: def

        Backend.SP -> sp.getString(key, def) ?: def
    }

    fun saveString(key: String, value: String) {
        when (backendOf(key)) {
            Backend.FILE -> ConfigStore.get().save(
                key, JsonObject().apply { addProperty(VALUE_FIELD, value) }
            )

            Backend.SP -> sp.edit { putString(key, value) }
        }
    }

    // ===================== 基础类型（SP） =====================

    fun loadInt(key: String, def: Int): Int = sp.getInt(key, def)

    fun saveInt(key: String, value: Int) = sp.edit { putInt(key, value) }

    fun loadBoolean(key: String, def: Boolean): Boolean = sp.getBoolean(key, def)

    fun saveBoolean(key: String, value: Boolean) = sp.edit { putBoolean(key, value) }

    fun loadLong(key: String, def: Long): Long = sp.getLong(key, def)

    fun saveLong(key: String, value: Long) = sp.edit { putLong(key, value) }

    fun loadFloat(key: String, def: Float): Float = sp.getFloat(key, def)

    fun saveFloat(key: String, value: Float) = sp.edit { putFloat(key, value) }

    // ===================== 结构化对象（ConfigStore） =====================

    fun loadJson(key: String): JsonObject = ConfigStore.get().load(key)

    fun saveJson(key: String, obj: JsonObject) = ConfigStore.get().save(key, obj)

    // TODO 企业微信 Key 与渠道号的原子保存（同一次 SP 提交）
    fun saveWebhookAndChannel(webhook: String, channel: Int) {
        sp.edit {
            putString(Constant.WX_WEB_HOOK_KEY, webhook)
            putInt(Constant.MSG_CHANNEL_KEY, channel)
        }
    }
}
