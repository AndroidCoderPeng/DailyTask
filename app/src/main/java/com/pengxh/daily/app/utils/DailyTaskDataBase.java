package com.pengxh.daily.app.utils;

import androidx.room.Database;
import androidx.room.RoomDatabase;

import com.pengxh.daily.app.bean.DailyTaskBean;
import com.pengxh.daily.app.bean.NotificationBean;
import com.pengxh.daily.app.dao.DailyTaskBeanDao;
import com.pengxh.daily.app.dao.NotificationBeanDao;

@Database(entities = {DailyTaskBean.class, NotificationBean.class}, version = 1)
public abstract class DailyTaskDataBase extends RoomDatabase {
    public abstract DailyTaskBeanDao dailyTaskDao();

    public abstract NotificationBeanDao noticeDao();
}
