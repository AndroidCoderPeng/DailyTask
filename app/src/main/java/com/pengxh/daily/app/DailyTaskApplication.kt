package com.pengxh.daily.app

import android.app.Application
import androidx.room.Room.databaseBuilder
import com.pengxh.daily.app.sqlite.DailyTaskDataBase
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.kt.lite.utils.SaveKeyValues
import com.tencent.bugly.crashreport.CrashReport


/**
 * @author: Pengxh
 * @email: 290677893@qq.com
 * @date: 2019/12/25 13:19
 */
class DailyTaskApplication : Application() {

    companion object {
        private lateinit var application: DailyTaskApplication

        fun get(): DailyTaskApplication = application

        internal fun initApplication(app: DailyTaskApplication) {
            application = app
        }
    }

    lateinit var dataBase: DailyTaskDataBase

    override fun onCreate() {
        super.onCreate()
        initApplication(this)
        SaveKeyValues.initSharedPreferences(this)
        LogFileManager.initLogFile(this)

        val isDebugMode = BuildConfig.DEBUG
        CrashReport.initCrashReport(this, "ecbdc9baf5", isDebugMode)

        dataBase = databaseBuilder(this, DailyTaskDataBase::class.java, "DailyTask.db")
            .allowMainThreadQueries()
            .build()
    }
}