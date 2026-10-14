package com.marketingpanel.growth;

import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

/** P26 Custom domains: several domains per workspace, DNS check, automatic SSL through Caddy on-demand TLS. */
@Service
public class DomainService {
    private static final Pattern LABEL = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
    private static final Set<String> TWO_PART_TLDS = Set.of("co.in", "org.in", "net.in", "gov.in", "ac.in", "edu.in", "firm.in", "gen.in", "ind.in", "co.uk", "com.au");
    private final Db db;
    private final PlanService plans;
    private final DnsVerifier dns;
    private final Settings settings;
    private final NotificationService notifications;
    private final AuditService audit;

    public DomainService(Db db, PlanService plans, DnsVerifier dns, Settings settings, NotificationService notifications, AuditService audit) {
        this.db = db;
        this.plans = plans;
        this.dns = dns;
        this.settings = settings;
        this.notifications = notifications;
        this.audit = audit;
    }

    public record DomainRequest(String domain, String purpose, Boolean primary) {}

    static boolean validDomain(String d) {
        if (d == null || d.length() < 4 || d.length() > 253 || !d.equals(d.toLowerCase())) return false;
        String[] labels = d.split("\\.", -1);
        if (labels.length < 2) return false;
        for (String l : labels) if (!LABEL.matcher(l).matches()) return false;
        String tld = labels[labels.length - 1];
        if (!tld.matches("^[a-z]{2,24}$")) return false; // valid public suffix shape; IP addresses fail here
        return true;
    }

    static boolean isApex(String d) {
        String[] labels = d.split("\\.");
        if (labels.length == 2) return true;
        return labels.length == 3 && TWO_PART_TLDS.contains(labels[1] + "." + labels[2]);
    }

    public List<Map<String, Object>> list(long ws) {
        List<Map<String, Object>> rows = db.list("select * from custom_domains where workspace_id=:ws order by id", "ws", ws);
        for (Map<String, Object> m : rows) m.put("instructions", instructions((String) m.get("domain"), (String) m.get("verificationToken")));
        return rows;
    }

    public Map<String, Object> instructions(String domain, String token) {
        boolean apex = isApex(domain);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("txt", Map.of("host", "_mp-verify." + domain, "value", token));
        m.put("routing", apex ? Map.of("type", "A", "host", domain, "value", settings.serverIp) : Map.of("type", "CNAME", "host", domain, "value", settings.cnameTarget));
        return m;
    }

    public Map<String, Object> add(long ws, DomainRequest r) {
        String domain = r.domain() == null ? "" : r.domain().trim();
        List<FieldErr> errs = new ArrayList<>();
        String platformHost = UrlGuard.host(settings.publicBaseUrl);
        if (!validDomain(domain)) errs.add(new FieldErr("domain", "domain.invalid", "Enter a valid domain like offers.yourshop.in."));
        else if (domain.equals(platformHost) || domain.endsWith("." + platformHost) || settings.trackDomain.startsWith(domain))
            errs.add(new FieldErr("domain", "domain.platform", "Enter a valid domain like offers.yourshop.in."));
        else if (db.exists("select 1 from custom_domains where domain=:d", "d", domain)) throw ApiException.duplicate("domain", "domain.exists", "This domain is already connected.");
        if (r.purpose() == null || !List.of("WHITE_LABEL", "LANDING_PAGES", "LINKS").contains(r.purpose())) errs.add(new FieldErr("purpose", "domain.purpose", "Choose what this domain is for."));
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        long max = plans.plan(ws).l("max_domains");
        long used = db.count("select count(*) from custom_domains where workspace_id=:ws", "ws", ws);
        if (used >= max) throw ApiException.planLimit(max == 0 ? "Your plan does not include custom domains. Upgrade to connect one." : "Your plan allows " + max + " domains.");
        String token = Ids.randomHex(16);
        long id = db.insertInto("custom_domains", "workspace_id", ws, "domain", domain, "purpose", r.purpose(), "verification_token", token);
        if (Boolean.TRUE.equals(r.primary())) setPrimary(ws, id, r.purpose());
        audit.log("DOMAIN_ADDED", "custom_domains", id);
        return get(ws, id);
    }

    private void setPrimary(long ws, long id, String purpose) {
        db.update("update custom_domains set is_primary=0 where workspace_id=:ws and purpose=:p", "ws", ws, "p", purpose);
        db.update("update custom_domains set is_primary=1 where id=:id", "id", id);
    }

    public Map<String, Object> get(long ws, long id) {
        Map<String, Object> m = db.one("select * from custom_domains where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Domain"));
        m.put("instructions", instructions((String) m.get("domain"), (String) m.get("verificationToken")));
        return m;
    }

    public Map<String, Object> makePrimary(long ws, long id) {
        Row d = db.row("select * from custom_domains where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Domain"));
        if (!"VERIFIED".equals(d.s("dns_status"))) throw ApiException.state("Verify the domain first.");
        setPrimary(ws, id, d.s("purpose"));
        return get(ws, id);
    }

    /** Verify button: checks DNS live and reports the exact missing record. */
    public Map<String, Object> verify(long ws, long id) {
        Row d = db.row("select * from custom_domains where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Domain"));
        check(d);
        return get(ws, id);
    }

    private void check(Row d) {
        DnsVerifier.Result r = dns.verify(d.s("domain"), d.s("verification_token"), isApex(d.s("domain")));
        if (r.ok()) {
            db.update("update custom_domains set dns_status='VERIFIED', failure_reason=null, last_checked_at=:now where id=:id", "now", Time.now(), "id", d.l("id"));
        } else {
            db.update("update custom_domains set last_checked_at=:now, failure_reason=:f, dns_status=if(created_at < :cutoff,'FAILED',dns_status) where id=:id",
                    "now", Time.now(), "f", r.missing(), "cutoff", Time.now().minusHours(72), "id", d.l("id"));
        }
    }

    /** Re-check every 6 h for 72 h, then the status becomes FAILED with the exact missing record. */
    public int recheck() {
        int n = 0;
        for (Row d : db.rows("select * from custom_domains where dns_status='PENDING' and (last_checked_at is null or last_checked_at < :t) limit 200", "t", Time.now().minusHours(6))) {
            check(d);
            n++;
        }
        for (Row d : db.rows("select * from custom_domains where ssl_status='ACTIVE' and ssl_expires_at is not null and ssl_expires_at < :t", "t", Time.now().plusDays(30))) {
            db.update("update custom_domains set ssl_status='EXPIRING' where id=:id", "id", d.l("id"));
            notifications.notifyOwners(d.l("workspace_id"), "SSL_EXPIRING", "SSL certificate for " + d.s("domain") + " is renewing",
                    "If renewal fails we will tell you. Check your DNS records.", "/settings/domains");
        }
        return n;
    }

    public void delete(long ws, long id, boolean confirm) {
        Row d = db.row("select * from custom_domains where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Domain"));
        long pages = db.count("select count(*) from landing_pages where domain_id=:id", "id", id);
        long links = db.count("select count(*) from short_links where domain_id=:id", "id", id);
        if ((pages > 0 || links > 0) && !confirm)
            throw new ApiException(ErrorCode.INVALID_STATE, "This domain has " + pages + " landing page(s) and " + links + " link(s). They will fall back to the platform domain.",
                    List.of(), Map.of("pages", pages, "links", links, "needsConfirm", true), null);
        db.update("update landing_pages set domain_id=null where domain_id=:id", "id", id);
        db.update("update short_links set domain_id=null where domain_id=:id", "id", id);
        db.update("delete from custom_domains where id=:id", "id", id);
        audit.log("DOMAIN_REMOVED", "custom_domains", id, Map.of("domain", d.s("domain")), null);
    }

    /** Caddy on-demand TLS permission check: only verified domains get certificates. */
    public boolean allowTls(String domain) {
        if (domain == null) return false;
        Row d = db.row("select * from custom_domains where domain=:d and dns_status='VERIFIED'", "d", domain.toLowerCase()).orElse(null);
        if (d == null) return false;
        if ("PENDING".equals(d.s("ssl_status")) || "FAILED".equals(d.s("ssl_status")))
            db.update("update custom_domains set ssl_status='ACTIVE', ssl_expires_at=:e where id=:id", "e", Time.now().plus(Duration.ofDays(90)), "id", d.l("id"));
        return true;
    }
}
