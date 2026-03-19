package com.pengxh.daily.app.sqlite.bean;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "notice_record_table")
public class NotificationBean {
    @PrimaryKey(autoGenerate = true)
    private int id;//主键ID

    private String packageName;
    private String noticeTitle;
    private String noticeMessage;
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

    public String getNoticeTitle() {
        return noticeTitle;
    }

    public void setNoticeTitle(String noticeTitle) {
        this.noticeTitle = noticeTitle;
    }

    public String getNoticeMessage() {
        return noticeMessage;
    }

    public void setNoticeMessage(String noticeMessage) {
        this.noticeMessage = noticeMessage;
    }

    public String getPostTime() {
        return postTime;
    }

    public void setPostTime(String postTime) {
        this.postTime = postTime;
    }
}
