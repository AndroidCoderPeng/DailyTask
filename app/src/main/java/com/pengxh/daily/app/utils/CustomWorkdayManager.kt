package com.pengxh.daily.app.utils

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
        val raw = AppConfig.loadString(
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
        AppConfig.saveString(Constant.CUSTOM_WORKDAYS_KEY, serializeWorkdays(workdays))
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
        val days = orderedDays.filter { it in workdays }
        if (days.size == orderedDays.size) return "每天"
        if (days.isEmpty()) return ""

        val ranges = mutableListOf<List<DayOfWeek>>()
        var range = mutableListOf<DayOfWeek>()

        for (day in days) {
            if (range.isNotEmpty() && day.value != range.last().value + 1) {
                ranges += range
                range = mutableListOf()
            }
            range += day
        }
        ranges += range

        return ranges.joinToString("、") { daysInRange ->
            val start = dayNameMap.getValue(daysInRange.first())
            if (daysInRange.size == 1) start
            else "${start}至${dayNameMap.getValue(daysInRange.last())}"
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
