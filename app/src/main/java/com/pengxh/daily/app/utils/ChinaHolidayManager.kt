package com.pengxh.daily.app.utils

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object ChinaHolidayManager {
    private const val kTag = "ChinaHolidayManager"
    private const val CACHE_KEY = "holidayConfig"

    private val scope = CoroutineScope(SupervisorJob())

    @Volatile
    var listener: OnSyncHolidayListener? = null

    private sealed class DownloadResult {
        data class Success(val content: String) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * CDN 镜像源
     * */
    private val cdnUrlTemplates = listOf(
        "https://cdn.jsdelivr.net/npm/chinese-days/dist/years/%s.json",
        "https://fastly.jsdelivr.net/npm/chinese-days/dist/years/%s.json",
        "https://registry.npmmirror.com/chinese-days/latest/files/dist/years/%s.json"
    )
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile
    private var holidayDates: Set<LocalDate> = emptySet()

    @Volatile
    private var workdayDates: Set<LocalDate> = emptySet()

    private val isSyncing = AtomicBoolean(false)
    private val dataMutex = Mutex()

    fun cancel() {
        scope.cancel()
    }

    fun updateChinaHolidayData() {
        if (!isSyncing.compareAndSet(false, true)) {
            Log.w(kTag, "Already syncing, skip duplicate request")
            return
        }
        scope.launch {
            try {
                tryLoadFromCache()
                if (holidayDates.isNotEmpty()) return@launch
                fetchAndParse()
            } finally {
                isSyncing.set(false)
            }
        }
    }

    private suspend fun tryLoadFromCache() {
        return withContext(Dispatchers.IO) {
            val cache = ConfigStore.get().load(CACHE_KEY)
            if (cache.size() == 0) return@withContext

            val cachedYear = cache.get("year")?.asInt ?: return@withContext
            val currentYear = LocalDate.now().year
            if (cachedYear != currentYear) {
                Log.d(kTag, "Cache year $cachedYear != current $currentYear, will re-fetch")
                return@withContext
            }

            dataMutex.withLock {
                parseJsonToMemory(cache)
            }
            notifySuccess("节假日数据同步完成（缓存）")
        }
    }

    private suspend fun fetchAndParse() {
        val currentYear = LocalDate.now().year
        val urls = cdnUrlTemplates.map { String.format(it, currentYear) }

        var resultJson: String? = null

        for (url in urls) {
            val outcome = downloadJson(url)
            when (outcome) {
                is DownloadResult.Success -> {
                    resultJson = outcome.content
                    break
                }

                is DownloadResult.Error -> Log.w(kTag, "Network error: ${outcome.message}")
            }
        }

        if (resultJson == null) {
            notifyError("节假日数据下载失败：所有镜像源均不可用")
            return
        }

        handleHolidayData(resultJson)
    }

    private suspend fun downloadJson(urlString: String): DownloadResult {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(urlString).build()
                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    if (!response.isSuccessful || body.isEmpty()) {
                        DownloadResult.Error("HTTP ${response.code}")
                    } else {
                        try {
                            // 仅做Json格式校验，返回值不必处理
                            val element = JsonParser.parseString(body)
                        } catch (e: Exception) {
                            return@withContext DownloadResult.Error("Invalid JSON")
                        }
                        DownloadResult.Success(body)
                    }
                }
            } catch (e: IOException) {
                DownloadResult.Error(e.message ?: "Unknown")
            }
        }
    }

    private suspend fun handleHolidayData(rawJson: String) {
        val root = try {
            JsonParser.parseString(rawJson).asJsonObject
        } catch (e: Exception) {
            Log.e(kTag, "JSON parse failed", e)
            notifyError("节假日数据解析失败：JSON 无效")
            return
        }

        dataMutex.withLock {
            parseJsonToMemory(root)
        }

        // 写入缓存
        val cacheObj = JsonObject().apply {
            addProperty("year", LocalDate.now().year)
            add("holidays", root.get("holidays"))
            add("workdays", root.get("workdays"))
        }
        ConfigStore.get().save(CACHE_KEY, cacheObj)

        notifySuccess("节假日数据同步完成（网络）")
    }

    private fun parseJsonToMemory(root: JsonObject) {
        val holidaysObj = root.getAsJsonObject("holidays") ?: JsonObject()
        holidayDates = holidaysObj.keySet().mapNotNull { parseDate(it) }.toSet()

        val workdaysObj = root.getAsJsonObject("workdays") ?: JsonObject()
        workdayDates = workdaysObj.keySet().mapNotNull { parseDate(it) }.toSet()

        Log.d(kTag, "Parsed ${holidayDates.size} holidays, ${workdayDates.size} workdays")
    }

    private fun parseDate(dateStr: String): LocalDate? {
        return try {
            LocalDate.parse(dateStr, dateFormatter)
        } catch (e: Exception) {
            Log.w(kTag, "Invalid date format: $dateStr", e)
            null
        }
    }

    /**
     * 给定日期是否为法定节假日（含调休放假，不含普通周末）
     * */
    fun isHoliday(date: LocalDate): Boolean {
        return date in holidayDates
    }

    /**
     * 给定日期是否为调休补班日
     * */
    fun isWorkday(date: LocalDate): Boolean {
        return date in workdayDates
    }

    private suspend fun notifySuccess(message: String) {
        withContext(Dispatchers.Main) {
            listener?.onSyncSuccess(message)
        }
    }

    private suspend fun notifyError(message: String) {
        withContext(Dispatchers.Main) {
            listener?.onSyncError(message)
        }
    }

    interface OnSyncHolidayListener {
        fun onSyncSuccess(message: String)
        fun onSyncError(message: String)
    }
}