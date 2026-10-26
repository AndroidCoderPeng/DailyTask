package com.pengxh.daily.app.utils

import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object ChinaHolidayManager {
    private const val kTag = "ChinaHolidayManager"
    private const val LEGACY_CACHE_KEY = "holidayConfig"
    private const val CACHE_KEY_PREFIX = "holidayConfig_"

    private val _syncResult = MutableSharedFlow<SyncResult>(extraBufferCapacity = 1)
    val syncResult = _syncResult.asSharedFlow()

    /**
     * CDN 镜像源
     * */
    private val cdnUrlTemplates = listOf(
        "https://cdn.jsdelivr.net/npm/chinese-days/dist/years/%s.json",
        "https://fastly.jsdelivr.net/npm/chinese-days/dist/years/%s.json",
        "https://registry.npmmirror.com/chinese-days/latest/files/dist/years/%s.json"
    )

    sealed class SyncResult {
        data class Success(val content: String) : SyncResult()
        data class Error(val message: String) : SyncResult()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile
    private var holidayDatesByYear: Map<Int, Set<LocalDate>> = emptyMap()

    @Volatile
    private var workdayDatesByYear: Map<Int, Set<LocalDate>> = emptyMap()

    private val syncLock = Any()
    private var isSyncing = false
    private var forceRefreshPending = false
    private val dataMutex = Mutex()

    fun updateChinaHolidayData(forceRefresh: Boolean = false) {
        val shouldStart = synchronized(syncLock) {
            if (isSyncing) {
                if (forceRefresh) {
                    forceRefreshPending = true
                }
                Log.w(kTag, "Already syncing, ${if (forceRefresh) "queue" else "skip"} request")
                false
            } else {
                isSyncing = true
                true
            }
        }

        if (shouldStart) {
            launchSync(forceRefresh)
        }
    }

    private fun launchSync(forceRefresh: Boolean) {
        scope.launch {
            try {
                synchronizeYears(forceRefresh)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(kTag, "Holiday data sync failed", e)
                if (forceRefresh) {
                    notifyError("节假日数据同步失败：${e.message ?: e.javaClass.simpleName}")
                }
            } finally {
                val runPendingRefresh = synchronized(syncLock) {
                    if (forceRefreshPending) {
                        forceRefreshPending = false
                        true
                    } else {
                        isSyncing = false
                        false
                    }
                }
                if (runPendingRefresh) {
                    launchSync(forceRefresh = true)
                }
            }
        }
    }

    private suspend fun synchronizeYears(forceRefresh: Boolean) {
        val currentYear = LocalDate.now().year
        val years = listOf(currentYear, currentYear + 1)
        val cachedData = mutableMapOf<Int, CachedHolidayData>()

        for (year in years) {
            loadFromCache(year)?.let { cachedData[year] = it }
        }

        val yearsToFetch = if (forceRefresh) {
            years
        } else {
            years.filterNot { it in cachedData }
        }
        val updatedData = mutableMapOf<Int, CachedHolidayData>()

        for (year in yearsToFetch) {
            val json = fetchHolidayJson(year)
            val data = json?.let { handleHolidayData(year, it) }
            if (data != null) {
                updatedData[year] = data
                cachedData[year] = data
            } else {
                Log.w(kTag, "No usable holiday data returned for $year")
            }
        }

        if (forceRefresh) {
            reportRefreshResult(currentYear, years.last(), updatedData, cachedData)
        }
    }

    private suspend fun loadFromCache(year: Int): CachedHolidayData? {
        return withContext(Dispatchers.IO) {
            val configStore = ConfigStore.get()
            val cache = configStore.load(cacheKey(year)).takeIf { it.size() > 0 }
                ?: configStore.load(LEGACY_CACHE_KEY).takeIf { it.size() > 0 }
                ?: return@withContext null

            val cachedYear = cache.get("year")?.takeIf { it.isJsonPrimitive }?.asInt
                ?: return@withContext null
            if (cachedYear != year) {
                Log.d(kTag, "Cache year $cachedYear != requested $year, will re-fetch")
                return@withContext null
            }

            dataMutex.withLock {
                updateMemoryForYear(year, parseJsonForYear(cache))
            }
            val holidayDates = holidayDatesByYear[year].orEmpty()
            val workdayDates = workdayDatesByYear[year].orEmpty()
            if (holidayDates.isEmpty() && workdayDates.isEmpty()) {
                return@withContext null
            }

            val updatedAt = cache.get("updatedAt")?.takeIf { it.isJsonPrimitive }?.asLong
            CachedHolidayData(updatedAt)
        }
    }

    private suspend fun fetchHolidayJson(year: Int): String? {
        val urls = cdnUrlTemplates.map { String.format(it, year) }

        var resultJson: String? = null

        for (url in urls) {
            val outcome = downloadJson(url)
            when (outcome) {
                is SyncResult.Success -> {
                    resultJson = outcome.content
                    break
                }

                is SyncResult.Error -> Log.w(kTag, "Network error: ${outcome.message}")
            }
        }

        if (resultJson == null) {
            return null
        }

        return resultJson
    }

    private suspend fun downloadJson(urlString: String): SyncResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.d(kTag, "Requesting holiday data: $urlString")
                val request = Request.Builder().url(urlString).build()
                okHttpClient.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    if (!response.isSuccessful || body.isEmpty()) {
                        SyncResult.Error("HTTP ${response.code}")
                    } else {
                        try {
                            // 仅做Json格式校验，返回值不必处理
                            val element = JsonParser.parseString(body)
                        } catch (_: Exception) {
                            return@withContext SyncResult.Error("Invalid JSON")
                        }
                        SyncResult.Success(body)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SyncResult.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private suspend fun handleHolidayData(
        year: Int,
        rawJson: String
    ): CachedHolidayData? {
        val root = try {
            JsonParser.parseString(rawJson).asJsonObject
        } catch (e: Exception) {
            Log.e(kTag, "JSON parse failed", e)
            return null
        }

        val data = parseJsonForYear(root)
        if (data.holidays.isEmpty() && data.workdays.isEmpty()) {
            Log.w(kTag, "Holiday data for $year contains no valid dates")
            return null
        }

        dataMutex.withLock {
            updateMemoryForYear(year, data)
        }

        // 写入缓存
        val updatedAt = System.currentTimeMillis()
        val cacheObj = JsonObject().apply {
            addProperty("year", year)
            addProperty("updatedAt", updatedAt)
            add("holidays", root.get("holidays"))
            add("workdays", root.get("workdays"))
        }
        ConfigStore.get().save(cacheKey(year), cacheObj)

        return CachedHolidayData(updatedAt)
    }

    private suspend fun reportRefreshResult(
        currentYear: Int,
        nextYear: Int,
        updatedData: Map<Int, CachedHolidayData>,
        cachedData: Map<Int, CachedHolidayData>
    ) {
        val currentUpdated = updatedData[currentYear]
        val currentCached = cachedData[currentYear]
        val nextUpdated = updatedData[nextYear]
        val nextCached = cachedData[nextYear]
        val nextYearStatus = when {
            nextUpdated != null -> "；已预载${nextYear}年数据"
            nextCached != null -> "；保留${nextYear}年缓存（${formatCacheTime(nextCached)}）"
            else -> "；${nextYear}年数据暂不可用（可能尚未发布）"
        }

        when {
            currentUpdated != null -> notifySuccess(
                "节假日数据更新完成（${currentYear}年，${formatCacheTime(currentUpdated)}）" +
                        nextYearStatus
            )

            currentCached != null -> notifySuccess(
                "当年网络刷新失败，已回退到${currentYear}年缓存（" +
                        "${formatCacheTime(currentCached)}）$nextYearStatus"
            )

            else -> notifyError(
                "当前年度节假日数据刷新失败，且没有可用缓存$nextYearStatus"
            )
        }
    }

    private fun formatCacheTime(data: CachedHolidayData): String {
        return data.updatedAtMillis?.let(::formatTimestamp) ?: "更新时间未知"
    }

    private fun formatTimestamp(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date(timestamp))
    }

    private fun cacheKey(year: Int): String = "$CACHE_KEY_PREFIX$year"

    private data class CachedHolidayData(
        val updatedAtMillis: Long?
    )

    private data class HolidayYearData(
        val holidays: Set<LocalDate>,
        val workdays: Set<LocalDate>
    )

    private fun parseJsonForYear(root: JsonObject): HolidayYearData {
        val holidaysObj = root.getAsJsonObject("holidays") ?: JsonObject()
        val holidays = holidaysObj.keySet().mapNotNull { parseDate(it) }.toSet()

        val workdaysObj = root.getAsJsonObject("workdays") ?: JsonObject()
        val workdays = workdaysObj.keySet().mapNotNull { parseDate(it) }.toSet()

        return HolidayYearData(holidays, workdays)
    }

    private fun updateMemoryForYear(year: Int, data: HolidayYearData) {
        holidayDatesByYear = holidayDatesByYear + (year to data.holidays)
        workdayDatesByYear = workdayDatesByYear + (year to data.workdays)
        Log.d(
            kTag,
            "Parsed $year: ${data.holidays.size} holidays, ${data.workdays.size} workdays"
        )
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
        return date in holidayDatesByYear[date.year].orEmpty()
    }

    /**
     * 给定日期是否为调休补班日
     * */
    fun isWorkday(date: LocalDate): Boolean {
        return date in workdayDatesByYear[date.year].orEmpty()
    }

    fun cancel() {
        scope.cancel()
    }

    private suspend fun notifySuccess(message: String) {
        _syncResult.emit(SyncResult.Success(message))
    }

    private suspend fun notifyError(message: String) {
        _syncResult.emit(SyncResult.Error(message))
    }
}