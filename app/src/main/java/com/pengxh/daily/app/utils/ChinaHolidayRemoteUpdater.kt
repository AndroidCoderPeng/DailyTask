package com.pengxh.daily.app.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

    private val chinaZone = ZoneId.of("Asia/Shanghai")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshingYears = mutableSetOf<Int>()
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    fun refreshCurrentAndNextYearIfNeeded(context: Context, force: Boolean = false) {
        val enabled = SaveKeyValues.getValue(
            Constant.SKIP_CHINA_HOLIDAY_KEY, false
        ) as Boolean
        if (!enabled && !force) {
            return
        }
        if (!isNetworkAvailable(context.applicationContext)) {
            LogFileManager.writeLog("当前网络不可用，跳过节假日远程更新")
            return
        }

        val today = LocalDate.now(chinaZone)
        val years = if (today.monthValue >= 10) {
            listOf(today.year, today.year + 1)
        } else {
            listOf(today.year)
        }
        years.forEach { refreshYearIfNeeded(it, force) }
    }

    private fun refreshYearIfNeeded(year: Int, force: Boolean) {
        if (!force && ChinaHolidayCacheStore.isFresh(year, UPDATE_INTERVAL_MS)) {
            return
        }
        if (!markRefreshing(year)) {
            return
        }
        scope.launch {
            try {
                refreshYear(year)
            } finally {
                clearRefreshing(year)
                EventBus.getDefault().post(ApplicationEvent.HolidayDataStatusChanged)
            }
        }
    }

    private fun refreshYear(year: Int) {
        var lastError = ""
        for (template in urlTemplates) {
            val url = template.format(year)
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

    private fun isNetworkAvailable(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
