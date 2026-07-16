package com.pengxh.daily.app

import android.app.Application
import android.os.Environment
import androidx.room.Room.databaseBuilder
import com.pengxh.daily.app.sqlite.DailyTaskDataBase
import com.pengxh.daily.app.utils.ConfigStore
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.kt.lite.utils.SaveKeyValues
import java.io.File
import java.io.IOException


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
        SaveKeyValues.initialize(this)
        MessageDispatcher.initialize(this)
        LogFileManager.initLogFile(this)

        // 初始化配置文件
        val dir = File(this.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "")
        val file = File(dir.toString() + File.separator + "DailyTaskConfig.json")
        if (!file.exists()) {
            try {
                file.createNewFile()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        ConfigStore.init(file.absolutePath)

        dataBase = databaseBuilder(this, DailyTaskDataBase::class.java, "DailyTask.db").build()
    }
}
