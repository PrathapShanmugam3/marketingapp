package com.marketingpanel.common;

import com.marketingpanel.auth.Auth;
import org.springframework.stereotype.Service;

/** FR-41: audit log of sensitive actions. */
@Service
public class AuditService {
    private final Db db;

    public AuditService(Db db) {
        this.db = db;
    }

    public void log(Long workspaceId, Long userId, String action, String entityType, Long entityId, Object oldV, Object newV) {
        db.insert("insert into audit_logs (workspace_id,user_id,action,entity_type,entity_id,old_values,new_values,ip_address,user_agent) " +
                        "values (:ws,:u,:a,:et,:eid,:o,:n,:ip,:ua)",
                "ws", workspaceId, "u", userId, "a", action, "et", entityType, "eid", entityId,
                "o", oldV == null ? null : Json.write(oldV), "n", newV == null ? null : Json.write(newV),
                "ip", ClientInfo.ip(), "ua", ClientInfo.userAgent());
    }

    /** Logs as the current caller and their workspace. */
    public void log(String action, String entityType, Long entityId, Object oldV, Object newV) {
        Long ws = null;
        Long uid = null;
        if (Auth.present()) {
            ws = Auth.user().workspaceId();
            uid = Auth.userId();
        }
        log(ws, uid, action, entityType, entityId, oldV, newV);
    }

    public void log(String action, String entityType, Long entityId) {
        log(action, entityType, entityId, null, null);
    }
}
