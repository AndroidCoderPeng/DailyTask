package com.pengxh.daily.app.sqlite;

import androidx.room.Database;
import androidx.room.RoomDatabase;

@Database(entities = {DailyTaskBean.class, NotificationBean.class}, version = 1)
public abstract class DailyTaskDataBase extends RoomDatabase {
    public abstract DailyTaskBeanDao dailyTaskDao();

    public abstract NotificationBeanDao noticeDao();
}
