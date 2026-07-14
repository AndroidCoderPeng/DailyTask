package com.pengxh.daily.app.utils

import com.pengxh.kt.lite.utils.SaveKeyValues
import java.time.DayOfWeek

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

    fun loadConfiguredWorkdays(): Set<DayOfWeek> {
        val raw = SaveKeyValues.loadString(
            Constant.CUSTOM_WORKDAYS_KEY,
            serializeWorkdays(defaultWorkdays)
        )
        return loadConfiguredWorkdaysFromRaw(raw)
    }

    fun loadConfiguredWorkdaysFromRaw(raw: String): Set<DayOfWeek> {
        val parsed = raw.split(",")
            .mapNotNull { token ->
                token.trim().toIntOrNull()?.let { value ->
                    orderedDays.firstOrNull { it.value == value }
                }
            }
            .toSet()
        return if (parsed.isEmpty()) defaultWorkdays else parsed
    }

    fun saveConfiguredWorkdays(workdays: Set<DayOfWeek>) {
        SaveKeyValues.saveString(Constant.CUSTOM_WORKDAYS_KEY, serializeWorkdays(workdays))
    }

    fun serializeWorkdays(workdays: Set<DayOfWeek>): String {
        val normalized = orderedDays
            .filter { it in workdays }
            .map { it.value.toString() }
        return if (normalized.isEmpty()) {
            orderedDays.take(5).joinToString(",") { it.value.toString() }
        } else {
            normalized.joinToString(",")
        }
    }

    fun formatWorkdays(workdays: Set<DayOfWeek>): String {
        return orderedDays
            .filter { it in workdays }
            .joinToString("、") { dayNameMap[it].orEmpty() }
    }

    fun getOrderedDays(): List<DayOfWeek> {
        return orderedDays
    }

    fun getDayLabel(dayOfWeek: DayOfWeek): String {
        return dayNameMap[dayOfWeek].orEmpty()
    }
}
