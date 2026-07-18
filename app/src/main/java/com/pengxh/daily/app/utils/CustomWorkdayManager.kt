package com.pengxh.daily.app.utils

import com.pengxh.kt.lite.utils.SaveKeyValues
import java.time.DayOfWeek
import java.time.LocalDate

object CustomWorkdayManager {
    private val orderedDays = listOf(
        DayOfWeek.MONDAY,
        DayOfWeek.TUESDAY,
        DayOfWeek.WEDNESDAY,
        DayOfWeek.THURSDAY,
        DayOfWeek.FRIDAY,
        DayOfWeek.SATURDAY,
        DayOfWeek.SUNDAY
    )

    private val defaultWorkdays = orderedDays.take(5).toSet()

    private val dayNameMap = mapOf(
        DayOfWeek.MONDAY to "周一",
        DayOfWeek.TUESDAY to "周二",
        DayOfWeek.WEDNESDAY to "周三",
        DayOfWeek.THURSDAY to "周四",
        DayOfWeek.FRIDAY to "周五",
        DayOfWeek.SATURDAY to "周六",
        DayOfWeek.SUNDAY to "周日"
    )

    fun loadWorkdays(): Set<DayOfWeek> {
        val raw = SaveKeyValues.loadString(
            Constant.CUSTOM_WORKDAYS_KEY, serializeWorkdays(defaultWorkdays)
        )
        return loadWorkdaysFromRaw(raw)
    }

    fun loadWorkdaysFromRaw(raw: String): Set<DayOfWeek> {
        val parsed = raw.split(",").mapNotNull { token ->
            token.trim().toIntOrNull()?.let { value ->
                orderedDays.firstOrNull { it.value == value }
            }
        }.toSet()
        return parsed.ifEmpty { defaultWorkdays }
    }

    fun saveWorkdays(workdays: Set<DayOfWeek>) {
        SaveKeyValues.saveString(Constant.CUSTOM_WORKDAYS_KEY, serializeWorkdays(workdays))
    }

    fun serializeWorkdays(workdays: Set<DayOfWeek>): String {
        val normalized = orderedDays.filter {
            it in workdays
        }.map {
            it.value.toString()
        }
        return if (normalized.isEmpty()) {
            defaultWorkdays.joinToString(",") {
                it.value.toString()
            }
        } else {
            normalized.joinToString(",")
        }
    }

    fun formatWorkdays(workdays: Set<DayOfWeek>): String {
        val workdayNames = orderedDays.filter {
            it in workdays
        }.joinToString("、") {
            dayNameMap[it].orEmpty()
        }

        return when (workdays.size) {
            7 -> "每天"
            else -> workdayNames
        }
    }

    fun getOrderedDays(): List<DayOfWeek> {
        return orderedDays
    }

    fun getDayLabel(dayOfWeek: DayOfWeek): String {
        return dayNameMap[dayOfWeek].orEmpty()
    }


    /**
     * 判断今天是否是用户设置的一周固定休息日（按星期几配置）
     */
    fun isWeekdayRestDay(date: LocalDate): Boolean {
        val workdays = loadWorkdays()
        return date.dayOfWeek !in workdays
    }
}
