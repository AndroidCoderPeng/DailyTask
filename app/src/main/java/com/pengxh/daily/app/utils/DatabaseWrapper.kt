package com.pengxh.daily.app.utils

import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.bean.DailyTaskBean
import com.pengxh.daily.app.bean.NotificationBean

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

    fun deleteAllNotice() {
        noticeDao.deleteAll()
    }

    fun loadNoticeByTime(pageSize: Int, offset: Int): MutableList<NotificationBean> {
        return noticeDao.loadNoticeByTime(pageSize, offset)
    }

    fun loadCurrentDayNotice(): MutableList<NotificationBean> {
        return noticeDao.loadCurrentDayNotice(TimeKit.getTodayDate())
    }

    fun insertNotice(bean: NotificationBean) {
        noticeDao.insert(bean)
    }
}