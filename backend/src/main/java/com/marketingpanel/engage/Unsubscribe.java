package com.marketingpanel.engage;

import com.marketingpanel.common.Db;
import com.marketingpanel.common.Hashing;
import com.marketingpanel.common.Html;
import com.marketingpanel.common.Time;
import com.marketingpanel.config.Settings;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** One-click, signed unsubscribe links for e-mail (and SMS) broadcasts: /u/{token}. */
@RestController
public class Unsubscribe {
    private final Db db;
    private final Settings settings;

    public Unsubscribe(Db db, Settings settings) {
        this.db = db;
        this.settings = settings;
    }

    public String link(long ws, long contactId, long broadcastId, String channel) {
        String payload = ws + ":" + contactId + ":" + broadcastId + ":" + channel;
        String p = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return settings.publicBaseUrl + "/u/" + p + "." + Hashing.hmacSha256(settings.jwtSecret, "unsub:" + payload).substring(0, 24);
    }

    @GetMapping(value = "/u/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> unsubscribe(@PathVariable String token) {
        try {
            int dot = token.lastIndexOf('.');
            String payload = new String(Base64.getUrlDecoder().decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            if (!Hashing.constantEquals(Hashing.hmacSha256(settings.jwtSecret, "unsub:" + payload).substring(0, 24), token.substring(dot + 1)))
                return ResponseEntity.status(400).body(Html.shell("Unsubscribe", "<div class=\"card center\"><h1>This link is not valid</h1></div>"));
            String[] a = payload.split(":");
            long ws = Long.parseLong(a[0]);
            long contact = Long.parseLong(a[1]);
            long bc = Long.parseLong(a[2]);
            String col = "SMS".equals(a[3]) ? "sms_unsub_at" : "email_unsub_at";
            int n = db.update("update contacts set " + col + "=:now where id=:id and workspace_id=:ws and " + col + " is null", "now", Time.now(), "id", contact, "ws", ws);
            if (n > 0 && bc > 0) db.update("update broadcasts set unsub_count=unsub_count+1 where id=:id and workspace_id=:ws", "id", bc, "ws", ws);
            return ResponseEntity.ok(Html.shell("Unsubscribed", "<div class=\"card center\"><h1>You are unsubscribed</h1><p>You will not receive further " + ("SMS".equals(a[3]) ? "text messages" : "e-mails") + " from this business.</p></div>"));
        } catch (Exception e) {
            return ResponseEntity.status(400).body(Html.shell("Unsubscribe", "<div class=\"card center\"><h1>This link is not valid</h1></div>"));
        }
    }
}
