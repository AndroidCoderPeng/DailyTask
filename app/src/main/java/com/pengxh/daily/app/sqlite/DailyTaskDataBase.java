package com.pengxh.daily.app.sqlite;

import androidx.room.Database;
import androidx.room.RoomDatabase;

import com.pengxh.daily.app.sqlite.bean.DailyTaskBean;
import com.pengxh.daily.app.sqlite.bean.EmailConfigBean;
import com.pengxh.daily.app.sqlite.bean.NotificationBean;
import com.pengxh.daily.app.sqlite.dao.DailyTaskBeanDao;
import com.pengxh.daily.app.sqlite.dao.EmailConfigBeanDao;
import com.pengxh.daily.app.sqlite.dao.NotificationBeanDao;

@Database(entities = {DailyTaskBean.class, NotificationBean.class, EmailConfigBean.class}, version = 1)
public abstract class DailyTaskDataBase extends RoomDatabase {
    public abstract DailyTaskBeanDao dailyTaskDao();

    public abstract NotificationBeanDao noticeDao();

    public abstract EmailConfigBeanDao emailConfigDao();
}
