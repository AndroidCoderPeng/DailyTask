package com.pengxh.daily.app.utils

import android.util.Log
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class ConfigStore private constructor(private val filePath: String) {

    companion object {
        private val kTag = "ConfigStore"
        private val gson = GsonBuilder().setPrettyPrinting().create()

        @Volatile
        private var instance: ConfigStore? = null

        /**
         * 初始化 ConfigStore 单例，必须在首次使用前调用。
         * @param filePath 配置文件完整路径，例如 "/data/data/.../files/task_config.json"
         */
        @Synchronized
        fun init(filePath: String) {
            if (instance != null) {
                Log.w(kTag, "ConfigStore already initialized, ignoring re-init")
                return
            }
            instance = ConfigStore(filePath)
        }

        fun get(): ConfigStore {
            return instance
                ?: throw IllegalStateException("ConfigStore not initialized. Call ConfigStore.init(filePath) first.")
        }
    }

    private val lock = ReentrantReadWriteLock()
    private val data = mutableMapOf<String, JsonObject>()

    init {
        readFromFile()
    }

    fun save(key: String, value: JsonObject) {
        lock.write {
            data[key] = value
            writeToFileSafe()
        }
    }

    fun load(key: String): JsonObject {
        return lock.read { data[key] ?: JsonObject() }
    }

    fun remove(key: String) {
        lock.write {
            if (data.remove(key) != null) {
                writeToFileSafe()
            }
        }
    }

    fun contains(key: String): Boolean {
        return lock.read { data.containsKey(key) }
    }

    fun loadAll(): Map<String, JsonObject> {
        return lock.read { data.toMap() }
    }

    fun keys(): Set<String> {
        return lock.read { data.keys.toSet() }
    }

    fun clear() {
        lock.write {
            data.clear()
            writeToFileSafe()
        }
    }

    /**
     * 强制将内存数据刷写到磁盘
     */
    fun flush() {
        lock.write { writeToFileSafe() }
    }

    private fun readFromFile() {
        val file = File(filePath)
        if (!file.exists()) return

        try {
            val text = file.readText()
            if (text.isBlank()) return
            val root = JsonParser.parseString(text).asJsonObject
            root.entrySet().forEach { (key, value) ->
                if (value.isJsonObject) {
                    data[key] = value.asJsonObject
                }
            }
        } catch (e: Exception) {
            Log.e(kTag, "Failed to parse config file: $filePath", e)
        }
    }

    private fun writeToFileSafe() {
        try {
            val root = JsonObject()
            data.forEach { (key, value) -> root.add(key, value) }
            val text = gson.toJson(root)
            File(filePath).writeText(text)
        } catch (e: Exception) {
            Log.e(kTag, "Failed to write config file: $filePath", e)
        }
    }
}