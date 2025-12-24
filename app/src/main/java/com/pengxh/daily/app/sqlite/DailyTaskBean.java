package com.pengxh.daily.app.sqlite;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "daily_task_table")
public class DailyTaskBean {
    @PrimaryKey(autoGenerate = true)
    private int id;//主键ID

    private String time;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }
}
