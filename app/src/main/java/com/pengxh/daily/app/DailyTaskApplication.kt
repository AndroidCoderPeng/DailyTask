package com.pengxh.daily.app

import android.app.Application
import androidx.room.Room.databaseBuilder
import com.pengxh.daily.app.utils.DailyTaskDataBase
import com.pengxh.kt.lite.utils.SaveKeyValues
import kotlin.properties.Delegates


/**
 * @author: Pengxh
 * @email: 290677893@qq.com
 * @date: 2019/12/25 13:19
 */
class DailyTaskApplication : Application() {

    companion object {
        private var application: DailyTaskApplication by Delegates.notNull()

        fun get() = application
    }

    lateinit var dataBase: DailyTaskDataBase

    override fun onCreate() {
        super.onCreate()
        application = this
        SaveKeyValues.initSharedPreferences(this)
        dataBase = databaseBuilder(this, DailyTaskDataBase::class.java, "DailyTask.db")
            .allowMainThreadQueries()
            .build()
    }
}