package com.pengxh.daily.app.sqlite;

import androidx.room.Database;
import androidx.room.RoomDatabase;

import com.pengxh.daily.app.sqlite.bean.DailyTaskBean;
import com.pengxh.daily.app.sqlite.bean.NotificationBean;
import com.pengxh.daily.app.sqlite.dao.DailyTaskBeanDao;
import com.pengxh.daily.app.sqlite.dao.NotificationBeanDao;

@Database(entities = {DailyTaskBean.class, NotificationBean.class}, version = 1)
public abstract class DailyTaskDataBase extends RoomDatabase {
    public abstract DailyTaskBeanDao dailyTaskDao();

    public abstract NotificationBeanDao noticeDao();
}
