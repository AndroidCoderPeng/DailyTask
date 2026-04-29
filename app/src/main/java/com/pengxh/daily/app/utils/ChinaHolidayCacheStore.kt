package com.pengxh.daily.app.utils

import com.pengxh.kt.lite.utils.SaveKeyValues
import org.json.JSONObject
import java.time.LocalDate

object ChinaHolidayCacheStore {

    private const val CACHE_KEY_PREFIX = "CHINA_HOLIDAY_CACHE_YEAR_"

    fun saveYear(year: Int, rawJson: String, source: String): Boolean {
        val parsed = parseChineseDaysYear(year, rawJson, source) ?: return false
        val cache = JSONObject().apply {
            put("year", parsed.year)
            put("source", parsed.source)
            put("updatedAt", parsed.updatedAt)
            put("holidays", toJsonObject(parsed.holidays))
            put("workdays", toJsonObject(parsed.workdays))
        }
        SaveKeyValues.putValue(cacheKey(year), cache.toString())
        SaveKeyValues.putValue(Constant.CHINA_HOLIDAY_CACHE_UPDATE_AT_KEY, parsed.updatedAt)
        return true
    }

    fun loadYear(year: Int): ChinaHolidayCalendar.HolidayYearData? {
        val json = SaveKeyValues.getValue(cacheKey(year), "") as String
        if (json.isBlank()) {
            return null
        }
        return try {
            val root = JSONObject(json)
            val cachedYear = root.optInt("year", -1)
            if (cachedYear != year) {
                return null
            }
            val holidays = parseDateMap(root.optJSONObject("holidays"), year)
            if (holidays.isEmpty()) {
                return null
            }
            ChinaHolidayCalendar.HolidayYearData(
                year = year,
                holidays = holidays,
                workdays = parseDateMap(root.optJSONObject("workdays"), year),
                updatedAt = root.optLong("updatedAt", 0L),
                source = root.optString("source", "远程节假日缓存")
            )
        } catch (e: Exception) {
            LogFileManager.writeLog("读取节假日缓存失败：${e.message}")
            null
        }
    }

    fun isFresh(year: Int, maxAgeMillis: Long): Boolean {
        val cached = loadYear(year) ?: return false
        val now = System.currentTimeMillis()
        return cached.updatedAt > 0 && now - cached.updatedAt in 0..maxAgeMillis
    }

    private fun parseChineseDaysYear(
        year: Int,
        rawJson: String,
        source: String
    ): ChinaHolidayCalendar.HolidayYearData? {
        return try {
            val root = JSONObject(rawJson)
            val holidays = parseChineseDaysMap(root.optJSONObject("holidays"), year)
            if (holidays.isEmpty()) {
                LogFileManager.writeLog("节假日远程数据为空，忽略本次更新：$year")
                return null
            }
            val workdays = parseChineseDaysMap(root.optJSONObject("workdays"), year)
            if (!ChinaHolidayCalendar.isConsistentWithKnownOfficialYear(
                    year,
                    holidays.keys,
                    workdays.keys
                )
            ) {
                LogFileManager.writeLog("节假日远程数据与内置官方表不一致，忽略本次更新：$year")
                return null
            }
            ChinaHolidayCalendar.HolidayYearData(
                year = year,
                holidays = holidays,
                workdays = workdays,
                updatedAt = System.currentTimeMillis(),
                source = source
            )
        } catch (e: Exception) {
            LogFileManager.writeLog("解析节假日远程数据失败：${e.message}")
            null
        }
    }

    private fun parseChineseDaysMap(jsonObject: JSONObject?, year: Int): Map<LocalDate, String> {
        val result = linkedMapOf<LocalDate, String>()
        if (jsonObject == null) {
            return result
        }
        val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val dateText = keys.next()
            val date = runCatching { LocalDate.parse(dateText) }.getOrNull() ?: continue
            if (date.year != year) {
                continue
            }
            val name = normalizeChineseDaysName(jsonObject.optString(dateText))
            if (name.isNotBlank()) {
                result[date] = name
            }
        }
        return result
    }

    private fun parseDateMap(jsonObject: JSONObject?, year: Int): Map<LocalDate, String> {
        val result = linkedMapOf<LocalDate, String>()
        if (jsonObject == null) {
            return result
        }
        val keys = jsonObject.keys()
        while (keys.hasNext()) {
            val dateText = keys.next()
            val date = runCatching { LocalDate.parse(dateText) }.getOrNull() ?: continue
            if (date.year == year) {
                val name = jsonObject.optString(dateText)
                if (name.isNotBlank()) {
                    result[date] = name
                }
            }
        }
        return result
    }

    private fun normalizeChineseDaysName(value: String): String {
        val parts = value.split(",")
        return parts.getOrNull(1)?.trim()
            ?: parts.firstOrNull()?.trim()
            ?: ""
    }

    private fun toJsonObject(map: Map<LocalDate, String>): JSONObject {
        return JSONObject().apply {
            map.forEach { (date, name) ->
                put(date.toString(), name)
            }
        }
    }

    private fun cacheKey(year: Int): String = "$CACHE_KEY_PREFIX$year"
}
