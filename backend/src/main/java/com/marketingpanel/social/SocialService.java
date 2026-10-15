package com.marketingpanel.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.auth.AuthUser;
import com.marketingpanel.common.*;
import com.marketingpanel.jobs.JobHandler;
import com.marketingpanel.jobs.JobService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/** P19 Social composer: Facebook and Instagram scheduling and publishing, with 3 retries (1, 5, 15 min) before the creator is told. */
@Service
public class SocialService implements JobHandler {
    private static final int[] RETRY_MINUTES = {1, 5, 15};
    private static final Pattern HASHTAG = Pattern.compile("#\\p{L}[\\p{L}\\p{N}_]*");
    private final Db db;
    private final Crypto crypto;
    private final SocialClient client;
    private final PlanService plans;
    private final JobService jobs;
    private final NotificationService notifications;
    private final AuditService audit;

    public SocialService(Db db, Crypto crypto, SocialClient client, PlanService plans, JobService jobs, NotificationService notifications, AuditService audit) {
        this.db = db;
        this.crypto = crypto;
        this.client = client;
        this.plans = plans;
        this.jobs = jobs;
        this.notifications = notifications;
        this.audit = audit;
    }

    public record AccountRequest(String platform, String externalId, String name, String accessToken, String tokenExpiresAt) {}

    public record PostRequest(List<Long> accountIds, String caption, String postType, List<String> media, String scheduledAt) {}

    public List<Map<String, Object>> accounts(long ws) {
        return db.list("select id, platform, external_id, name, token_expires_at, status, created_at from social_accounts where workspace_id=:ws order by id", "ws", ws);
    }

    public Map<String, Object> connect(long ws, AccountRequest r) {
        plans.requireFeature(ws, "social");
        List<FieldErr> errs = new ArrayList<>();
        if (!List.of("FACEBOOK", "INSTAGRAM", "YOUTUBE", "LINKEDIN", "X").contains(r.platform())) errs.add(new FieldErr("platform", "social.platform", "Choose a supported platform."));
        if (r.externalId() == null || r.externalId().isBlank() || r.externalId().length() > 40) errs.add(new FieldErr("externalId", "social.id", "Enter the page or account id."));
        if (r.name() == null || r.name().isBlank() || r.name().length() > 100) errs.add(new FieldErr("name", "social.name", "Enter the account name."));
        if (r.accessToken() == null || r.accessToken().isBlank()) errs.add(new FieldErr("accessToken", "social.token", "Connect your account again."));
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        LocalDateTime exp = r.tokenExpiresAt() == null || r.tokenExpiresAt().isBlank() ? null : Time.parseUtc(r.tokenExpiresAt());
        byte[] enc = crypto.encrypt(r.accessToken());
        db.update("insert into social_accounts (workspace_id, platform, external_id, name, access_token_enc, token_expires_at, status) values (:ws,:p,:e,:n,:t,:x,'CONNECTED') " +
                        "on duplicate key update name=:n, access_token_enc=:t, token_expires_at=:x, status='CONNECTED'",
                "ws", ws, "p", r.platform(), "e", r.externalId(), "n", Norm.plain(r.name()), "t", enc, "x", exp);
        audit.log("SOCIAL_CONNECTED", "social_accounts", null, null, Map.of("platform", r.platform()));
        return Map.of("accounts", accounts(ws));
    }

    public void disconnect(long ws, long id) {
        int n = db.update("update social_accounts set status='DISCONNECTED' where id=:id and workspace_id=:ws", "id", id, "ws", ws);
        if (n == 0) throw ApiException.notFound("Account");
    }

    public Map<String, Object> create(AuthUser me, PostRequest r) {
        long ws = me.workspaceId();
        plans.requireFeature(ws, "social");
        List<FieldErr> errs = new ArrayList<>();
        List<Long> ids = r.accountIds() == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(r.accountIds()));
        List<Row> accounts = ids.isEmpty() ? List.of() : db.rows("select * from social_accounts where workspace_id=:ws and id in (:ids)", "ws", ws, "ids", ids);
        if (accounts.isEmpty() || accounts.size() != ids.size() || accounts.stream().anyMatch(a -> !"CONNECTED".equals(a.s("status"))))
            errs.add(new FieldErr("accountIds", "social.accounts", "Choose at least one account."));
        for (Row a : accounts) {
            if (a.t("token_expires_at") != null && a.t("token_expires_at").isBefore(Time.now())) {
                db.update("update social_accounts set status='EXPIRED' where id=:id", "id", a.l("id"));
                throw ApiException.state("The connection for " + a.s("name") + " expired. Ask the Owner to reconnect it.");
            }
        }
        String type = r.postType() == null ? "" : r.postType();
        if (!List.of("IMAGE", "CAROUSEL", "VIDEO", "TEXT").contains(type)) errs.add(new FieldErr("postType", "social.type", "Choose a post type."));
        List<String> media = r.media() == null ? List.of() : r.media();
        String caption = Norm.plain(r.caption());
        boolean ig = accounts.stream().anyMatch(a -> "INSTAGRAM".equals(a.s("platform")));
        if (caption != null && Norm.hasControlChars(caption)) errs.add(new FieldErr("caption", "vr.text.unsupported", "Remove unsupported characters."));
        boolean yt = accounts.stream().anyMatch(a -> "YOUTUBE".equals(a.s("platform")));
        boolean x = accounts.stream().anyMatch(a -> "X".equals(a.s("platform")));
        boolean li = accounts.stream().anyMatch(a -> "LINKEDIN".equals(a.s("platform")));
        if (x && caption != null && caption.length() > 280) errs.add(new FieldErr("caption", "social.caption.x", "X allows up to 280 characters."));
        if (li && caption != null && caption.length() > 3000) errs.add(new FieldErr("caption", "social.caption.li", "LinkedIn allows up to 3,000 characters."));
        if (yt && !"VIDEO".equals(type)) errs.add(new FieldErr("postType", "social.yt", "YouTube posts need a video."));
        if (yt && caption != null && caption.length() > 5000) errs.add(new FieldErr("caption", "social.caption.yt", "YouTube descriptions allow up to 5,000 characters."));
        if (ig && caption != null) {
            if (caption.length() > 2200 || countHashtags(caption) > 30) errs.add(new FieldErr("caption", "social.caption.ig", "Instagram allows up to 2,200 characters and 30 hashtags."));
        }
        if (caption != null && caption.length() > 63206) errs.add(new FieldErr("caption", "social.caption.fb", "Facebook allows up to 63,206 characters."));
        if ("TEXT".equals(type)) {
            if (ig) errs.add(new FieldErr("postType", "social.text.ig", "Instagram posts need an image or video."));
            if (caption == null || caption.isBlank()) errs.add(new FieldErr("caption", "social.caption.required", "Write a caption."));
        } else if ("CAROUSEL".equals(type)) {
            if (media.size() < 2 || media.size() > 10) errs.add(new FieldErr("media", "social.carousel", "A carousel needs 2 to 10 images."));
        } else if (!"".equals(type) && media.size() != 1) {
            errs.add(new FieldErr("media", "social.media", "Add one image or video."));
        }
        if (ig && !"TEXT".equals(type) && media.isEmpty()) errs.add(new FieldErr("media", "social.media.ig", "Instagram posts need media."));
        for (String m : media) if (!UrlGuard.isPublicHttps(m, 500) && !m.startsWith("http://localhost")) errs.add(new FieldErr("media", "vr.url.invalid", "Enter a valid https:// link."));
        LocalDateTime when = null;
        if (r.scheduledAt() != null && !r.scheduledAt().isBlank()) {
            when = Time.parseUtc(r.scheduledAt());
            if (when.isBefore(Time.now().plusMinutes(10)) || when.isAfter(Time.now().plusDays(90)))
                errs.add(new FieldErr("scheduledAt", "vr.future.invalid", "Choose a time at least 10 minutes from now."));
        }
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        long id = db.tx(() -> {
            long pid = db.insertInto("social_posts", "workspace_id", ws, "caption", caption, "post_type", type, "media_json", Json.write(media),
                    "scheduled_at", r.scheduledAt() == null || r.scheduledAt().isBlank() ? null : Time.parseUtc(r.scheduledAt()),
                    "status", r.scheduledAt() == null || r.scheduledAt().isBlank() ? "PUBLISHING" : "SCHEDULED", "created_by", me.userId());
            for (Row a : accounts) db.insertInto("social_post_targets", "post_id", pid, "social_account_id", a.l("id"));
            return pid;
        });
        if (when == null) jobs.enqueue("POST_PUBLISH", Map.of("postId", id), Time.now(), 4, 1);
        audit.log("SOCIAL_POST_CREATED", "social_posts", id);
        return get(ws, id);
    }

    static int countHashtags(String s) {
        var m = HASHTAG.matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }

    public Map<String, Object> get(long ws, long id) {
        Map<String, Object> p = db.one("select * from social_posts where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Post"));
        p.put("targets", db.list("select t.*, a.platform, a.name as account_name from social_post_targets t join social_accounts a on a.id=t.social_account_id where t.post_id=:id", "id", id));
        return p;
    }

    public List<Map<String, Object>> calendar(long ws, LocalDateTime from, LocalDateTime to) {
        return db.list("select id, caption, post_type, scheduled_at, status, created_at from social_posts where workspace_id=:ws and " +
                "coalesce(scheduled_at, created_at) >= :f and coalesce(scheduled_at, created_at) < :t order by coalesce(scheduled_at, created_at)", "ws", ws, "f", from, "t", to);
    }

    public Map<String, Object> cancel(long ws, long id) {
        Row p = db.row("select * from social_posts where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Post"));
        if (!"SCHEDULED".equals(p.s("status")) && !"DRAFT".equals(p.s("status"))) throw ApiException.state("Only scheduled posts can be cancelled.");
        db.update("update social_posts set status='CANCELLED' where id=:id", "id", id);
        return get(ws, id);
    }

    // -------------------------------------------------------------- publish

    /** Scheduler: moves due posts to PUBLISHING and enqueues the publish job. */
    public int enqueueDue() {
        int n = 0;
        for (Row p : db.rows("select id from social_posts where status='SCHEDULED' and scheduled_at <= :now limit 100", "now", Time.now())) {
            if (db.update("update social_posts set status='PUBLISHING' where id=:id and status='SCHEDULED'", "id", p.l("id")) > 0) {
                jobs.enqueue("POST_PUBLISH", Map.of("postId", p.l("id")), Time.now(), 4, 1);
                n++;
            }
        }
        return n;
    }

    @Override
    public String type() {
        return "POST_PUBLISH";
    }

    @Override
    public void handle(JsonNode payload) {
        long id = payload.get("postId").asLong();
        Row post = db.row("select * from social_posts where id=:id", "id", id).orElse(null);
        if (post == null || !"PUBLISHING".equals(post.s("status"))) return;
        List<String> media = new ArrayList<>();
        if (post.s("media_json") != null) Json.list(post.s("media_json")).forEach(o -> media.add(String.valueOf(o)));
        for (Row t : db.rows("select t.*, a.platform, a.external_id, a.access_token_enc, a.name from social_post_targets t join social_accounts a on a.id=t.social_account_id " +
                "where t.post_id=:id and t.status='PENDING' and (t.next_retry_at is null or t.next_retry_at <= :now)", "id", id, "now", Time.now())) {
            try {
                String pid = client.publish(new SocialClient.PublishRequest(t.s("platform"), t.s("external_id"), crypto.decrypt(t.bytes("access_token_enc")),
                        post.s("caption"), post.s("post_type"), media));
                db.update("update social_post_targets set status='PUBLISHED', platform_post_id=:p, published_at=:now, error_message=null where id=:id", "p", pid, "now", Time.now(), "id", t.l("id"));
            } catch (Exception e) {
                int attempts = t.i("attempts") + 1;
                String msg = e.getMessage() == null ? e.toString() : (e.getMessage().length() > 500 ? e.getMessage().substring(0, 500) : e.getMessage());
                if (attempts > RETRY_MINUTES.length) {
                    db.update("update social_post_targets set status='FAILED', attempts=:a, error_message=:m where id=:id", "a", attempts, "m", msg, "id", t.l("id"));
                } else {
                    db.update("update social_post_targets set attempts=:a, error_message=:m, next_retry_at=:n where id=:id", "a", attempts, "m", msg,
                            "n", Time.now().plusMinutes(RETRY_MINUTES[attempts - 1]), "id", t.l("id"));
                }
            }
        }
        Row nextDue = db.row("select min(next_retry_at) as t, count(*) as n from social_post_targets where post_id=:id and status='PENDING'", "id", id).orElse(null);
        if (nextDue != null && nextDue.l("n") > 0) { // some accounts still waiting for a retry: finish later, never mark the post done early
            LocalDateTime when = nextDue.t("t") == null ? Time.now().plusMinutes(1) : nextDue.t("t");
            jobs.enqueue("POST_PUBLISH", Map.of("postId", id), when, 4, 1);
            return;
        }
        long published = db.count("select count(*) from social_post_targets where post_id=:id and status='PUBLISHED'", "id", id);
        long failed = db.count("select count(*) from social_post_targets where post_id=:id and status='FAILED'", "id", id);
        String status = failed == 0 ? "PUBLISHED" : published == 0 ? "FAILED" : "PARTIAL";
        db.update("update social_posts set status=:s where id=:id", "s", status, "id", id);
        if (failed > 0) {
            notifications.notify(post.l("workspace_id"), post.lo("created_by"), "SOCIAL_FAILED", "A social post could not be published",
                    failed + " account(s) failed after 3 retries.", "/social");
        }
    }
}
