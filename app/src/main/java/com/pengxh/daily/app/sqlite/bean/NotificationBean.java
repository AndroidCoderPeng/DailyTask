package com.pengxh.daily.app.sqlite.bean;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "notice_record_table")
public class NotificationBean {
    @PrimaryKey(autoGenerate = true)
    private int id;//主键ID

    private String packageName;
    private String notificationTitle;
    private String notificationMsg;
    private String postTime;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    public String getNotificationTitle() {
        return notificationTitle;
    }

    public void setNotificationTitle(String notificationTitle) {
        this.notificationTitle = notificationTitle;
    }

    public String getNotificationMsg() {
        return notificationMsg;
    }

    public void setNotificationMsg(String notificationMsg) {
        this.notificationMsg = notificationMsg;
    }

    public String getPostTime() {
        return postTime;
    }

    public void setPostTime(String postTime) {
        this.postTime = postTime;
    }
}
