package com.pengxh.daily.app

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.pengxh.daily.app.utils.ChinaHolidayManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class ChinaHolidayManagerTest {

    private lateinit var originalHolidays: Any
    private lateinit var originalWorkdays: Any

    @Before
    fun setUp() {
        originalHolidays = checkNotNull(field("holidayDatesByYear").get(ChinaHolidayManager))
        originalWorkdays = checkNotNull(field("workdayDatesByYear").get(ChinaHolidayManager))

        val fixture = checkNotNull(javaClass.getResourceAsStream("/chinese_days_2026.json")) {
            "Missing 2026 holiday fixture"
        }.bufferedReader().use { it.readText() }
        val parse = ChinaHolidayManager::class.java.getDeclaredMethod(
            "parseJsonForYear", JsonObject::class.java
        ).apply { isAccessible = true }
        val parsed = parse.invoke(ChinaHolidayManager, JsonParser.parseString(fixture).asJsonObject)
        val holidays = parsed.javaClass.getDeclaredField("holidays").apply { isAccessible = true }
        val workdays = parsed.javaClass.getDeclaredField("workdays").apply { isAccessible = true }

        field("holidayDatesByYear").set(ChinaHolidayManager, mapOf(2026 to holidays.get(parsed)))
        field("workdayDatesByYear").set(ChinaHolidayManager, mapOf(2026 to workdays.get(parsed)))
    }

    @After
    fun tearDown() {
        field("holidayDatesByYear").set(ChinaHolidayManager, originalHolidays)
        field("workdayDatesByYear").set(ChinaHolidayManager, originalWorkdays)
    }

    @Test
    fun official2026HolidayAndMakeupWorkdays_areRecognizedForEveryDate() {
        val holidays = listOf(
            range("2026-01-01", "2026-01-03"),
            range("2026-02-15", "2026-02-23"),
            range("2026-04-04", "2026-04-06"),
            range("2026-05-01", "2026-05-05"),
            range("2026-06-19", "2026-06-21"),
            range("2026-09-25", "2026-09-27"),
            range("2026-10-01", "2026-10-07")
        )
        val makeupWorkdays = setOf(
            "2026-01-04", "2026-02-14", "2026-02-28",
            "2026-05-09", "2026-09-20", "2026-10-10"
        ).map(LocalDate::parse).toSet()

        var date = LocalDate.of(2026, 1, 1)
        while (date.year == 2026) {
            assertEquals(
                "Holiday: $date",
                holidays.any { date in it },
                ChinaHolidayManager.isHoliday(date)
            )
            assertEquals(
                "Makeup workday: $date",
                date in makeupWorkdays,
                ChinaHolidayManager.isWorkday(date)
            )
            date = date.plusDays(1)
        }
    }

    @Test
    fun datesInOtherYears_doNotUse2026Data() {
        for (date in listOf("2025-10-01", "2027-10-01", "2025-09-20", "2027-09-20")) {
            assertEquals(
                "Holiday: $date",
                false,
                ChinaHolidayManager.isHoliday(LocalDate.parse(date))
            )
            assertEquals(
                "Makeup workday: $date",
                false,
                ChinaHolidayManager.isWorkday(LocalDate.parse(date))
            )
        }
    }

    private fun range(start: String, end: String): ClosedRange<LocalDate> =
        LocalDate.parse(start)..LocalDate.parse(end)

    private fun field(name: String) =
        ChinaHolidayManager::class.java.getDeclaredField(name).apply { isAccessible = true }
}