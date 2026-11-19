package com.pengxh.daily.app

import com.google.gson.JsonObject
import com.pengxh.daily.app.utils.ConfigStore
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.CustomWorkdayManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.random.Random

class CustomWorkdayManagerTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val instanceField = ConfigStore::class.java.getDeclaredField("instance").apply {
        isAccessible = true
    }
    private var originalStore: Any? = null

    @Before
    fun setUp() {
        originalStore = instanceField.get(null)
    }

    @After
    fun tearDown() {
        instanceField.set(null, originalStore)
    }

    @Test
    fun randomlyOrderedWorkdayConfigurations_classifyEveryDay() {
        val days = DayOfWeek.entries.toTypedArray()
        val configurations = (1 until (1 shl days.size)).shuffled(Random(2026))
        val start = LocalDate.of(2026, 12, 28)

        for (mask in configurations) {
            val workdays = days.filter { mask and (1 shl (it.value - 1)) != 0 }.toSet()
            val raw = workdays.shuffled(Random(mask)).joinToString(",") { it.value.toString() }
            installConfiguration(raw)

            assertEquals("Loaded workdays for $raw", workdays, CustomWorkdayManager.loadWorkdays())
            for (offset in 0L until 14L) {
                val date = start.plusDays(offset)
                assertEquals(
                    "Rest day for $date with workdays $raw",
                    date.dayOfWeek !in workdays,
                    CustomWorkdayManager.isWeekdayRestDay(date)
                )
            }
        }
    }

    @Test
    fun missingOrInvalidConfiguration_usesDefaultWeekdays() {
        val defaultWorkdays = setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
        )
        for (raw in listOf(null, "", "0,8,invalid")) {
            installConfiguration(raw)
            assertEquals(
                "Default workdays for $raw",
                defaultWorkdays,
                CustomWorkdayManager.loadWorkdays()
            )
            val monday = LocalDate.of(2026, 1, 5)
            for (offset in 0L until 7L) {
                val date = monday.plusDays(offset)
                assertEquals(
                    "Default rest day for $date with configuration $raw",
                    date.dayOfWeek !in defaultWorkdays,
                    CustomWorkdayManager.isWeekdayRestDay(date)
                )
            }
        }
    }

    private fun installConfiguration(raw: String?) {
        val root = JsonObject()
        if (raw != null) {
            root.add(Constant.CUSTOM_WORKDAYS_KEY, JsonObject().apply { addProperty("value", raw) })
        }
        val file = temporaryFolder.newFile()
        file.writeText(root.toString())
        instanceField.set(null, null)
        ConfigStore.init(file.absolutePath)
    }
}