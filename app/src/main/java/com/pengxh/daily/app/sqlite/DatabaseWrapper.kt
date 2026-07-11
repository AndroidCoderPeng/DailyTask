package com.pengxh.daily.app.sqlite

import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.sqlite.bean.NotificationBean
import java.time.LocalDate

object DatabaseWrapper {
    private val dailyTaskDao by lazy { DailyTaskApplication.get().dataBase.dailyTaskDao() }

    fun loadAllTask(): ArrayList<DailyTaskBean> {
        return dailyTaskDao.loadAll() as ArrayList<DailyTaskBean>
    }

    fun isTaskTimeExist(time: String): Boolean {
        return dailyTaskDao.queryTaskByTime(time) > 0
    }

    fun updateTask(bean: DailyTaskBean) {
        dailyTaskDao.update(bean)
    }

    fun deleteTask(bean: DailyTaskBean) {
        dailyTaskDao.delete(bean)
    }

    fun insert(bean: DailyTaskBean) {
        dailyTaskDao.insert(bean)
    }

    /*****************************************************************************************/
    private val noticeDao by lazy { DailyTaskApplication.get().dataBase.noticeDao() }

    fun loadCurrentDayNotice(): MutableList<NotificationBean> {
        return noticeDao.loadCurrentDayNotice("${LocalDate.now()}")
    }

    fun insertNotice(bean: NotificationBean) {
        noticeDao.insert(bean)
    }
}
