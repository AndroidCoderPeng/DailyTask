package com.pengxh.daily.app.sqlite

import com.pengxh.daily.app.DailyTaskApplication
import com.pengxh.daily.app.sqlite.bean.DailyTaskBean
import com.pengxh.daily.app.sqlite.bean.EmailConfigBean
import com.pengxh.daily.app.sqlite.bean.NotificationBean
import com.pengxh.daily.app.utils.TimeKit
import com.pengxh.kt.lite.extensions.timestampToCompleteDate

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

    fun loadWeeklyNotice(startDate: String, endDate: String): MutableList<NotificationBean> {
        return noticeDao.loadWeeklyNotice(startDate, endDate)
    }

    fun loadCurrentDayNotice(): MutableList<NotificationBean> {
        return noticeDao.loadCurrentDayNotice(TimeKit.getTodayDate())
    }

    fun insertNotice(bean: NotificationBean) {
        noticeDao.insert(bean)
    }

    /*****************************************************************************************/
    private val emailConfigDao by lazy { DailyTaskApplication.get().dataBase.emailConfigDao() }

    fun insertConfig(outbox: String, authCode: String, inbox: String) {
        val bean = EmailConfigBean()
        bean.outbox = outbox
        bean.authCode = authCode
        bean.inbox = inbox
        bean.createTime = System.currentTimeMillis().timestampToCompleteDate()
        emailConfigDao.insert(bean)
    }

    fun loadAll(): List<EmailConfigBean> {
        return emailConfigDao.loadAll()
    }
}