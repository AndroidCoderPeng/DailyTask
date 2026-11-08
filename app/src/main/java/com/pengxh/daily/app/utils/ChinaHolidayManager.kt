package com.pengxh.daily.app.utils

import android.annotation.SuppressLint
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

object ChinaHolidayManager {
    private const val kTag = "ChinaHolidayManager"
    private const val LEGACY_CACHE_KEY = "holidayConfig"
    private const val CACHE_KEY_PREFIX = "holidayConfig_"

    /**
     * 自动补拉保险丝：防止高频请求 CDN
     * - 触发条件：当年无数据（跨年必中）或缓存超过 30 天，条件不满足时一个请求都不发
     * - 限频：每日最多一次自动尝试，失败后按 1h→2h→4h→…→封顶 24h 指数退避
     * - 手动刷新（forceRefresh=true）不受此限制
     */
    private const val MIN_RETRY_INTERVAL_MILLIS = 60 * 60 * 1000L
    private const val MAX_BACKOFF_MILLIS = 24 * 60 * 60 * 1000L
    private const val STALE_THRESHOLD_DAYS = 30L
    private const val AUTO_SYNC_TIMEOUT_MILLIS = 10_000L
    private val _syncGeneration = MutableStateFlow(0L)
    private val chinaZone = ZoneId.of(Constant.CHINA_ZONE_ID)

    private val _syncResult = MutableSharedFlow<SyncResult>(extraBufferCapacity = 1)
    val syncResult = _syncResult.asSharedFlow()

    private val _currentYearUpdatedAt = MutableStateFlow<Long?>(null)
    val currentYearUpdatedAt = _currentYearUpdatedAt.asStateFlow()

    private val _dataState = MutableStateFlow<DataState>(DataState.Loading)
    val dataState = _dataState.asStateFlow()

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

    @Volatile
    private var lastAutoSyncAttemptMillis = 0L

    @Volatile
    private var consecutiveFailures = 0

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
        // 新一轮同步开始前先回到 Loading，否则旧年份的 Ready 会让 awaitDataReady 瞬间通过，跨年后按空数据误判
        _dataState.value = DataState.Loading
        scope.launch {
            try {
                synchronizeYears(forceRefresh)
                _syncGeneration.value += 1
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _syncGeneration.value += 1
                Log.e(kTag, "Holiday data sync failed", e)
                _dataState.value = DataState.Unavailable
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
                    launchSync(true)
                }
            }
        }
    }

    private suspend fun synchronizeYears(forceRefresh: Boolean) {
        val currentYear = LocalDate.now(chinaZone).year
        val years = listOf(currentYear, currentYear + 1)
        val cachedData = mutableMapOf<Int, CachedHolidayData>()

        for (year in years) {
            loadFromCache(year)?.let { cachedData[year] = it }
        }

        _currentYearUpdatedAt.value = cachedData[currentYear]?.updatedAtMillis

        // 当年缓存已可用就先发布就绪态：次年的慢请求/失败不再阻塞当日判定
        if (isCurrentYearCovered()) {
            _dataState.value = DataState.Ready()
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

                if (year == currentYear) {
                    _currentYearUpdatedAt.value = data.updatedAtMillis
                    // 当年数据到手就先发布就绪，不被次年的慢请求拖住
                    if (isCurrentYearCovered()) {
                        _dataState.value = DataState.Ready()
                    }
                }
            } else {
                Log.w(kTag, "No usable holiday data returned for $year")
            }
        }

        // 汇总当年数据可用性（缓存加载/联网拉取完成后）
        _dataState.value = if (isCurrentYearCovered()) {
            DataState.Ready()
        } else {
            DataState.Unavailable
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

    @SuppressLint("CheckResult")
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
                            JsonParser.parseString(body)
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
        val nextYearState = when {
            nextUpdated != null -> "，\r\n并且已预载${nextYear}年节假日数据！"
            nextCached != null -> "，并保留${nextYear}年缓存数据！"
            else -> "。\r\n${nextYear}年数据暂不可用（可能尚未发布）"
        }

        when {
            currentUpdated != null -> notifySuccess("${currentYear}年节假日数据更新完成$nextYearState")
            currentCached != null -> notifySuccess("刷新节假日数据失败，已回退到${currentYear}年缓存$nextYearState")
            else -> notifyError("当前年度节假日数据刷新失败，且没有可用缓存$nextYearState")
        }
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
            kTag, "Parsed $year: ${data.holidays.size} holidays, ${data.workdays.size} workdays"
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

    /**
     * 等待当年节假日数据就绪（内存表非空即视为就绪，读本地缓存也算）。
     * 超时或确认不可用时返回 false，由调用方决定降级策略。
     */
    suspend fun awaitDataReady(timeoutMillis: Long = 15_000L): Boolean {
        withTimeoutOrNull(timeoutMillis) {
            dataState.first { it !is DataState.Loading }
        }
        return dataState.value is DataState.Ready
    }

    /** 当年是否有可用数据（不等待） */
    fun isCurrentYearCovered(): Boolean {
        val year = LocalDate.now(chinaZone).year
        return holidayDatesByYear[year].orEmpty().isNotEmpty()
                || workdayDatesByYear[year].orEmpty().isNotEmpty()
    }

    /**
     * 每日保鲜检查：当年无数据或缓存陈旧时，
     * 自动补拉一次。受「每日最多一次 + 失败指数退避」双重限频，
     * 条件不满足时一个请求都不发。手动刷新（forceRefresh=true）不受此限制。
     */
    suspend fun ensureFreshFor(date: LocalDate) {
        if (!shouldAutoSync(date)) return

        lastAutoSyncAttemptMillis = System.currentTimeMillis()
        val updatedAtBefore = currentYearUpdatedAt.value
        val generationBefore = _syncGeneration.value

        // 强制刷新
        updateChinaHolidayData(true)

        val finished = withTimeoutOrNull(AUTO_SYNC_TIMEOUT_MILLIS) {
            _syncGeneration.first { it > generationBefore }
        } != null

        val refreshed = finished && currentYearUpdatedAt.value?.let {
            it != updatedAtBefore
        } == true
        if (refreshed) {
            consecutiveFailures = 0
        } else {
            consecutiveFailures++
            Log.w(kTag, "Auto sync failed, consecutiveFailures=$consecutiveFailures")
        }
    }

    private fun shouldAutoSync(date: LocalDate): Boolean {
        val covered = isCurrentYearCovered()
        // 陈旧度实时计算（而非同步时刻的快照），长跑进程也能触发补拉
        val stale = covered && _currentYearUpdatedAt.value?.let {
            System.currentTimeMillis() - it > TimeUnit.DAYS.toMillis(STALE_THRESHOLD_DAYS)
        } == true
        if (covered && !stale) return false

        // 每日最多一次自动尝试
        val lastAttemptDate = lastAutoSyncAttemptMillis.takeIf {
            it > 0
        }?.let {
            Instant.ofEpochMilli(it).atZone(chinaZone).toLocalDate()
        }
        if (lastAttemptDate == date) return false

        // 失败指数退避：1h → 2h → 4h → … → 封顶 24h
        val backoffMillis = minOf(
            MIN_RETRY_INTERVAL_MILLIS shl consecutiveFailures.coerceAtMost(20),
            MAX_BACKOFF_MILLIS
        )
        return System.currentTimeMillis() - lastAutoSyncAttemptMillis >= backoffMillis
    }

    private suspend fun notifySuccess(message: String) {
        _syncResult.emit(SyncResult.Success(message))
    }

    private suspend fun notifyError(message: String) {
        _syncResult.emit(SyncResult.Error(message))
    }
}