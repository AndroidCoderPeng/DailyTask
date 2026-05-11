package com.pengxh.daily.app.utils

import android.content.Context
import com.pengxh.kt.lite.extensions.isNetworkConnected
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.greenrobot.eventbus.EventBus
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

object ChinaHolidayRemoteUpdater {

    private const val SOURCE_NAME = "Chinese Days"
    private const val UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L
    private val urlTemplates = listOf(
        "https://cdn.jsdelivr.net/npm/chinese-days/dist/years/%d.json",
        "https://fastly.jsdelivr.net/npm/chinese-days/dist/years/%d.json",
        "https://unpkg.com/chinese-days/dist/years/%d.json"
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshingYears = mutableSetOf<Int>()
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    fun refreshIfNeeded(context: Context) {
        if (!context.isNetworkConnected()) {
            LogFileManager.writeLog("当前网络不可用，跳过节假日远程更新")
            return
        }

        val enabled = SaveKeyValues.getValue(Constant.SKIP_CHINA_HOLIDAY_KEY, false) as Boolean
        if (!enabled) {
            return
        }

        // 10月及以后，额外预取次年的数据；其余时间只取当年，并且指定时区，避免时区变化导致的数据不准确
        val now = LocalDate.now(ZoneId.of("Asia/Shanghai"))
        val years = if (now.monthValue >= 10) {
            listOf(now.year, now.year + 1)
        } else {
            listOf(now.year)
        }

        for (year in years) {
            if (ChinaHolidayCacheStore.isYearHolidayCached(year, UPDATE_INTERVAL_MS)) {
                LogFileManager.writeLog("节假日缓存已存在，跳过远程更新：year=$year")
                return
            }

            // 同步锁，避免重复刷新
            if (!markRefreshing(year)) {
                return
            }

            LogFileManager.writeLog("开始进行节假日远程更新：year=$year")
            scope.launch {
                try {
                    refreshYearHoliday(year)
                } finally {
                    clearRefreshing(year)
                    EventBus.getDefault().post(ApplicationEvent.HolidayDataStatusChanged)
                }
            }
        }
    }

    private fun refreshYearHoliday(year: Int) {
        var lastError = ""
        for (template in urlTemplates) {
            val url = template.format(year) // 去掉占位符，生成完整的URL
            val request = Request.Builder().url(url).get().build()
            try {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        lastError = "HTTP ${response.code}"
                        return@use
                    }
                    val body = response.body.string()
                    if (ChinaHolidayCacheStore.saveYear(year, body, SOURCE_NAME)) {
                        LogFileManager.writeLog("节假日远程更新成功：year=$year，source=$SOURCE_NAME")
                        return
                    }
                    lastError = "数据校验失败"
                }
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
            }
        }
        LogFileManager.writeLog("节假日远程更新失败：year=$year，reason=$lastError")
    }

    private fun markRefreshing(year: Int): Boolean {
        return synchronized(refreshingYears) {
            if (refreshingYears.contains(year)) {
                false
            } else {
                refreshingYears.add(year)
                true
            }
        }
    }

    private fun clearRefreshing(year: Int) {
        synchronized(refreshingYears) {
            refreshingYears.remove(year)
        }
    }
}
