package com.pengxh.daily.app

import android.app.Application
import androidx.room.Room.databaseBuilder
import com.pengxh.daily.app.extensions.notificationEnable
import com.pengxh.daily.app.sqlite.DailyTaskDataBase
import com.pengxh.daily.app.utils.AppConfig
import com.pengxh.daily.app.utils.ChinaHolidayManager
import com.pengxh.daily.app.utils.Constant
import com.pengxh.daily.app.utils.LogFileManager
import com.pengxh.daily.app.utils.MessageDispatcher
import com.pengxh.daily.app.utils.ProjectionSession
import com.pengxh.daily.app.utils.ResultSourceGuard


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
        AppConfig.initialize(this)

        // 一次性行为迁移
        if (!AppConfig.contains(Constant.SKIP_REST_DAY_KEY)) {
            AppConfig.saveBoolean(
                Constant.SKIP_REST_DAY_KEY,
                AppConfig.loadBoolean(Constant.SKIP_HOLIDAY_KEY, true)
            )
        }

        MessageDispatcher.initialize(this)
        LogFileManager.initLogFile(this)

        dataBase = databaseBuilder(this, DailyTaskDataBase::class.java, "DailyTask.db").build()

        ChinaHolidayManager.updateChinaHolidayData()

        // 上次使用截屏来源，冷启动后单次授权已失效，推送告警
        val resultSource = AppConfig.loadInt(Constant.RESULT_SOURCE_KEY, Constant.DEFAULT_INDEX)
        if (resultSource == 1 && !ProjectionSession.isStateActive()) {
            MessageDispatcher.sendCaptureDownAlert()
        }

        // 通知监听失效（权限被撤销），无人值守兜底
        if (!notificationEnable()) {
            MessageDispatcher.sendNotificationDownAlert()
        }

        // 冷启动自愈来源配置：截屏授权已随进程销毁，避免留下非法组合
        ResultSourceGuard.reconcile()
    }
}
