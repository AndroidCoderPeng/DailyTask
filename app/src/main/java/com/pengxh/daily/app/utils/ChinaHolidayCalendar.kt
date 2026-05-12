package com.pengxh.daily.app.utils

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * 中国节假日和调休补班日判断。
 *
 * 2026 年数据来源：国务院办公厅《国务院办公厅关于2026年部分节假日安排的通知》
 * https://www.gov.cn/gongbao/2025/issue_12406/202511/content_7048922.html
 */
object ChinaHolidayCalendar {

    private val chinaZone = ZoneId.of("Asia/Shanghai")
    private val weekendDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    private val officialHolidayMap = mapOf(
        2026 to buildHolidayMap(
            HolidayRange("2026-01-01", "2026-01-03", "元旦"),
            HolidayRange("2026-02-15", "2026-02-23", "春节"),
            HolidayRange("2026-04-04", "2026-04-06", "清明节"),
            HolidayRange("2026-05-01", "2026-05-05", "劳动节"),
            HolidayRange("2026-06-19", "2026-06-21", "端午节"),
            HolidayRange("2026-09-25", "2026-09-27", "中秋节"),
            HolidayRange("2026-10-01", "2026-10-07", "国庆节")
        )
    )

    private val officialWorkdayMap = mapOf(
        2026 to mapOf(
            LocalDate.parse("2026-01-04") to "元旦",
            LocalDate.parse("2026-02-14") to "春节",
            LocalDate.parse("2026-02-28") to "春节",
            LocalDate.parse("2026-05-09") to "劳动节",
            LocalDate.parse("2026-09-20") to "国庆节",
            LocalDate.parse("2026-10-10") to "国庆节"
        )
    )

    fun evaluateToday(): DayInfo {
        return evaluate(LocalDate.now(chinaZone))
    }

    fun getDataStatus(): DataStatus {
        val today = LocalDate.now(chinaZone)
        ChinaHolidayCacheStore.loadYear(today.year)?.let {
            return DataStatus(
                year = today.year,
                source = it.source,
                updatedAt = it.updatedAt,
                hasOfficialAdjustment = true,
                holidayCount = it.holidays.size,
                workdayCount = it.workdays.size,
                todayInfo = evaluate(today)
            )
        }

        val holidayMap = officialHolidayMap[today.year]
        val workdayMap = officialWorkdayMap[today.year]
        if (holidayMap != null && workdayMap != null) {
            return DataStatus(
                year = today.year,
                source = "内置国务院节假日表",
                updatedAt = 0L,
                hasOfficialAdjustment = true,
                holidayCount = holidayMap.size,
                workdayCount = workdayMap.size,
                todayInfo = evaluate(today)
            )
        }

        return DataStatus(
            year = today.year,
            source = "周末规则",
            updatedAt = 0L,
            hasOfficialAdjustment = false,
            holidayCount = 0,
            workdayCount = 0,
            todayInfo = evaluate(today)
        )
    }

    fun isConsistentWithKnownOfficialYear(
        year: Int,
        holidayDates: Set<LocalDate>,
        workdayDates: Set<LocalDate>
    ): Boolean {
        val holidayMap = officialHolidayMap[year] ?: return true
        val workdayMap = officialWorkdayMap[year] ?: return true
        return holidayMap.keys == holidayDates && workdayMap.keys == workdayDates
    }

    fun evaluate(date: LocalDate): DayInfo {
        ChinaHolidayCacheStore.loadYear(date.year)?.let {
            return evaluateConfiguredYear(date, it.holidays, it.workdays, it.source)
        }

        val holidayMap = officialHolidayMap[date.year]
        val workdayMap = officialWorkdayMap[date.year]
        if (holidayMap != null && workdayMap != null) {
            return evaluateConfiguredYear(date, holidayMap, workdayMap, "内置国务院节假日表")
        }

        return if (date.dayOfWeek in weekendDays) {
            DayInfo(date, true, "周末休息日", false, "周末规则")
        } else {
            DayInfo(date, false, "工作日", false, "周末规则")
        }
    }

    private fun evaluateConfiguredYear(
        date: LocalDate,
        holidays: Map<LocalDate, String>,
        workdays: Map<LocalDate, String>,
        source: String
    ): DayInfo {
        workdays[date]?.let {
            return DayInfo(date, false, "${it}调休补班日", true, source)
        }
        holidays[date]?.let {
            return DayInfo(date, true, "${it}休息日", true, source)
        }
        return if (date.dayOfWeek in weekendDays) {
            DayInfo(date, true, "周末休息日", true, source)
        } else {
            DayInfo(date, false, "工作日", true, source)
        }
    }

    private fun buildHolidayMap(vararg ranges: HolidayRange): Map<LocalDate, String> {
        val result = mutableMapOf<LocalDate, String>()
        ranges.forEach { range ->
            var date = LocalDate.parse(range.start)
            val end = LocalDate.parse(range.end)
            while (!date.isAfter(end)) {
                result[date] = range.name
                date = date.plusDays(1)
            }
        }
        return result
    }

    data class DayInfo(
        val date: LocalDate,
        val shouldSkip: Boolean,
        val reason: String,
        val hasOfficialAdjustment: Boolean,
        val source: String
    )

    data class HolidayYearData(
        val year: Int,
        val holidays: Map<LocalDate, String>,
        val workdays: Map<LocalDate, String>,
        val updatedAt: Long,
        val source: String
    )

    data class DataStatus(
        val year: Int,
        val source: String,
        val updatedAt: Long,
        val hasOfficialAdjustment: Boolean,
        val holidayCount: Int,
        val workdayCount: Int,
        val todayInfo: DayInfo
    )

    private data class HolidayRange(
        val start: String,
        val end: String,
        val name: String
    )
}
