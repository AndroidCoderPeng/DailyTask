package com.pengxh.daily.app.sqlite

import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.sqlite.bean.NotificationBean
import java.time.LocalDate

object DatabaseWrapper {
    private val dailyTaskDao by lazy { DailyTaskApplication.get().dataBase.dailyTaskDao() }

    suspend fun loadAllTask(): MutableList<DailyTaskBean> {
        return dailyTaskDao.loadAll()
    }

    suspend fun isTaskTimeExist(time: String): Boolean {
        return dailyTaskDao.queryTaskByTime(time) > 0
    }

    suspend fun updateTask(bean: DailyTaskBean) {
        dailyTaskDao.update(bean)
    }

    suspend fun deleteTask(bean: DailyTaskBean) {
        dailyTaskDao.delete(bean)
    }

    suspend fun insert(bean: DailyTaskBean) {
        dailyTaskDao.insert(bean)
    }

    /*****************************************************************************************/
    private val noticeDao by lazy { DailyTaskApplication.get().dataBase.noticeDao() }

    suspend fun loadCurrentDayNotice(): MutableList<NotificationBean> {
        return noticeDao.loadCurrentDayNotice("${LocalDate.now()}")
    }

    suspend fun insertNotice(bean: NotificationBean) {
        noticeDao.insert(bean)
    }
}
