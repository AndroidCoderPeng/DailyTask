package com.pengxh.daily.app

import com.google.gson.JsonObject
import com.pengxh.daily.app.utils.ChinaHolidayManager
import com.pengxh.daily.app.utils.ConfigStore
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.DayFlag
import com.pengxh.daily.app.utils.TaskScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.lang.reflect.Field
import java.time.DayOfWeek
import java.time.LocalDate

class TaskSchedulerDayDecisionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val savedFields = mutableListOf<Triple<Field, Any?, Any?>>()
    private val classify = TaskScheduler::class.java.getDeclaredMethod(
        "classifyDay", LocalDate::class.java
    ).apply { isAccessible = true }

    @Before
    fun setUp() {
        val holidays = setOf(
            LocalDate.parse("2026-01-02"),
            LocalDate.parse("2026-04-04")
        )
        val workdays = setOf(LocalDate.parse("2026-01-04"))
        replaceField(ChinaHolidayManager, "holidayDatesByYear", mapOf(2026 to holidays))
        replaceField(ChinaHolidayManager, "workdayDatesByYear", mapOf(2026 to workdays))
        val instance =
            ConfigStore::class.java.getDeclaredField("instance").apply { isAccessible = true }
        savedFields += Triple(instance, null, instance.get(null))
    }

    @After
    fun tearDown() {
        for ((field, owner, value) in savedFields.asReversed()) {
            field.set(owner, value)
        }
    }

    @Test
    fun overlappingDayTypes_followPriority() {
        data class Scenario(val date: String, val workdays: Set<DayOfWeek>, val expected: DayFlag)

        val weekdays = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
        )
        val scenarios = listOf(
            Scenario("2026-01-04", weekdays, DayFlag.SubstituteWorkday),
            Scenario("2026-01-04", weekdays + DayOfWeek.SUNDAY, DayFlag.SubstituteWorkday),
            Scenario("2026-01-02", weekdays, DayFlag.Holiday),
            Scenario("2026-01-02", weekdays - DayOfWeek.FRIDAY, DayFlag.Holiday),
            Scenario("2026-04-04", weekdays, DayFlag.Holiday),
            Scenario("2026-04-04", weekdays + DayOfWeek.SATURDAY, DayFlag.Holiday),
            Scenario("2026-01-10", weekdays, DayFlag.RestDay),
            Scenario("2026-01-10", weekdays + DayOfWeek.SATURDAY, DayFlag.Workday),
            Scenario("2026-01-05", weekdays - DayOfWeek.MONDAY, DayFlag.RestDay),
            Scenario("2026-01-05", weekdays, DayFlag.Workday)
        )

        for (scenario in scenarios) {
            installWorkdays(scenario.workdays)
            val date = LocalDate.parse(scenario.date)
            val actualDay = classify.invoke(TaskScheduler, date) as DayFlag
            assertEquals("Classification for $scenario", scenario.expected, actualDay)
        }
    }

    private fun installWorkdays(workdays: Set<DayOfWeek>) {
        val raw = workdays.joinToString(",") { it.value.toString() }
        val root = JsonObject().apply {
            add(Constant.CUSTOM_WORKDAYS_KEY, JsonObject().apply { addProperty("value", raw) })
        }
        val file = temporaryFolder.newFile()
        file.writeText(root.toString())
        val instance =
            ConfigStore::class.java.getDeclaredField("instance").apply { isAccessible = true }
        instance.set(null, null)
        ConfigStore.init(file.absolutePath)
    }

    private fun replaceField(owner: Any, name: String, value: Any) {
        val field = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
        savedFields += Triple(field, owner, field.get(owner))
        field.set(owner, value)
    }
}
