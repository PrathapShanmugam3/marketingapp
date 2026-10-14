package com.marketingpanel.common;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class NotificationService {
    private final Db db;

    public NotificationService(Db db) {
        this.db = db;
    }

    public void notify(long workspaceId, Long userId, String type, String title, String body, String link) {
        db.insert("insert into notifications (workspace_id,user_id,type,title,body,link) values (:ws,:u,:t,:ti,:b,:l)",
                "ws", workspaceId, "u", userId, "t", type, "ti", cut(title, 120), "b", cut(body, 500), "l", link);
    }

    /** Notify at most once per `hours` for the same type (low wallet, quality alerts). */
    public void notifyOnce(long workspaceId, String type, int hours, String title, String body, String link) {
        boolean recent = db.exists("select 1 from notifications where workspace_id=:ws and type=:t and created_at > :since limit 1",
                "ws", workspaceId, "t", type, "since", Time.now().minusHours(hours));
        if (!recent) notify(workspaceId, null, type, title, body, link);
    }

    public void notifyOwners(long workspaceId, String type, String title, String body, String link) {
        for (Row r : db.rows("select user_id from workspace_members where workspace_id=:ws and role in ('OWNER','AGENCY_ADMIN') " +
                "and invite_status='ACCEPTED' and user_id is not null", "ws", workspaceId)) {
            notify(workspaceId, r.l("user_id"), type, title, body, link);
        }
    }

    public List<Map<String, Object>> list(long ws, long userId, boolean unreadOnly, int limit) {
        return db.list("select * from notifications where workspace_id=:ws and (user_id is null or user_id=:u) " +
                        (unreadOnly ? "and read_at is null " : "") + "order by id desc limit " + Math.min(limit, 100),
                "ws", ws, "u", userId);
    }

    public long unread(long ws, long userId) {
        return db.count("select count(*) from notifications where workspace_id=:ws and (user_id is null or user_id=:u) and read_at is null",
                "ws", ws, "u", userId);
    }

    public void markRead(long ws, long userId, Long id) {
        db.update("update notifications set read_at=:now where workspace_id=:ws and (user_id is null or user_id=:u) and read_at is null" +
                (id != null ? " and id=:id" : ""), "now", Time.now(), "ws", ws, "u", userId, "id", id);
    }

    private static String cut(String s, int n) {
        return s == null ? null : (s.length() > n ? s.substring(0, n) : s);
    }
}
