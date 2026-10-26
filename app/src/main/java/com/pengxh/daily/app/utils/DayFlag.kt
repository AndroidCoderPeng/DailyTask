package com.pengxh.daily.app.utils

sealed class DayFlag(val label: String, val shouldSkip: Boolean) {
    data object SubstituteWorkday : DayFlag("补班日", false)
    data object Holiday : DayFlag("节假日", true)
    data object RestDay : DayFlag("休息日", true)
    data object Workday : DayFlag("工作日", false)
}