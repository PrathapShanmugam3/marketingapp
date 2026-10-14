package com.marketingpanel.crm;

import com.marketingpanel.common.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class ContactService {
    private static final Pattern CF_KEY = Pattern.compile("^[a-z0-9_]{1,30}$");
    private final Db db;
    private final PlanService plans;
    private final AuditService audit;
    private final ApplicationEventPublisher events;

    public ContactService(Db db, PlanService plans, AuditService audit, ApplicationEventPublisher events) {
        this.db = db;
        this.plans = plans;
        this.audit = audit;
        this.events = events;
    }

    // -------------------------------------------------------------- queries

    public record Filter(String q, List<Long> tagIds, String optIn, String city, LocalDate from, LocalDate to) {}

    private AudienceFragment where(long ws, Filter f) {
        StringBuilder sb = new StringBuilder("c.workspace_id=:ws and c.deleted_at is null");
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("ws", ws);
        if (f.q() != null && !f.q().isBlank()) {
            String q = f.q().trim();
            if (q.length() < 2 || q.length() > 50) throw ApiException.invalid("q", "search.min", "Type at least 2 characters.");
            sb.append(" and (c.full_name like :q or c.phone like :q or c.email like :q)");
            p.put("q", "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (f.tagIds() != null && !f.tagIds().isEmpty()) {
            if (f.tagIds().size() > 10) throw ApiException.invalid("tagIds", "filter.tags", "Choose up to 10 tags.");
            sb.append(" and exists (select 1 from contact_tags ct where ct.contact_id=c.id and ct.tag_id in (:tagIds))");
            p.put("tagIds", f.tagIds());
        }
        if (f.optIn() != null && !f.optIn().isBlank()) {
            if (!List.of("OPTED_IN", "OPTED_OUT", "UNKNOWN").contains(f.optIn())) throw ApiException.badRequest("Unknown opt-in status.");
            sb.append(" and c.opt_in_status=:optIn");
            p.put("optIn", f.optIn());
        }
        if (f.city() != null && !f.city().isBlank()) {
            sb.append(" and c.city=:city");
            p.put("city", f.city().trim());
        }
        if (f.from() != null) {
            sb.append(" and c.created_at >= :from");
            p.put("from", Time.startOfIstDayUtc(f.from()));
        }
        if (f.to() != null) {
            sb.append(" and c.created_at < :to");
            p.put("to", Time.startOfIstDayUtc(f.to().plusDays(1)));
        }
        return new AudienceFragment(sb.toString(), p);
    }

    private record AudienceFragment(String sql, Map<String, Object> params) {}

    public Map<String, Object> list(long ws, Filter f, Long cursor, int size) {
        if (size != 25 && size != 50 && size != 100) size = 25;
        AudienceFragment w = where(ws, f);
        Map<String, Object> p = new LinkedHashMap<>(w.params());
        String sql = "select c.* from contacts c where " + w.sql();
        if (cursor != null) {
            sql += " and c.id < :cursor";
            p.put("cursor", cursor);
        }
        sql += " order by c.id desc limit " + (size + 1);
        List<Row> rows = db.rowsMap(sql, p);
        boolean more = rows.size() > size;
        if (more) rows = rows.subList(0, size);
        List<Map<String, Object>> items = withTags(rows);
        long total = db.count("select count(*) from contacts c where " + w.sql(), flat(w.params()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("nextCursor", more ? rows.get(rows.size() - 1).l("id") : null);
        out.put("total", total);
        return out;
    }

    private static Object[] flat(Map<String, Object> m) {
        Object[] kv = new Object[m.size() * 2];
        int i = 0;
        for (var e : m.entrySet()) {
            kv[i++] = e.getKey();
            kv[i++] = e.getValue();
        }
        return kv;
    }

    private List<Map<String, Object>> withTags(List<Row> rows) {
        if (rows.isEmpty()) return List.of();
        List<Long> ids = rows.stream().map(r -> r.l("id")).toList();
        Map<Long, List<Map<String, Object>>> tags = new HashMap<>();
        for (Row t : db.rows("select ct.contact_id, t.id, t.name, t.color from contact_tags ct join tags t on t.id=ct.tag_id where ct.contact_id in (:ids)", "ids", ids)) {
            tags.computeIfAbsent(t.l("contact_id"), k -> new ArrayList<>())
                    .add(Map.of("id", t.l("id"), "name", t.s("name"), "color", t.s("color")));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Row r : rows) {
            Map<String, Object> m = r.api();
            m.put("tags", tags.getOrDefault(r.l("id"), List.of()));
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> get(long ws, long id) {
        Row r = db.row("select * from contacts where id=:id and workspace_id=:ws and deleted_at is null", "id", id, "ws", ws)
                .orElseThrow(() -> ApiException.notFound("Contact"));
        return withTags(List.of(r)).get(0);
    }

    // -------------------------------------------------------------- writes

    private void validate(CrmDtos.ContactRequest r) {
        List<FieldErr> errs = new ArrayList<>();
        if (r.dateOfBirth() != null) {
            int age = Period.between(r.dateOfBirth(), LocalDate.now()).getYears();
            if (age < 1 || age > 120) errs.add(new FieldErr("dateOfBirth", "contact.dob", "Enter a valid date of birth."));
        }
        if (r.whatsappConsent() && r.optInSource() == null)
            errs.add(new FieldErr("optInSource", "contact.consent.source", "Choose how consent was collected."));
        if (r.customFields() != null) {
            if (r.customFields().size() > 10) errs.add(new FieldErr("customFields", "contact.custom.count", "Up to 10 custom fields."));
            for (var e : r.customFields().entrySet()) {
                if (!CF_KEY.matcher(e.getKey()).matches())
                    errs.add(new FieldErr("customFields", "contact.custom.key", "Field names use lowercase letters, numbers and _."));
                if (e.getValue() != null && e.getValue().length() > 200)
                    errs.add(new FieldErr("customFields", "contact.custom.value", "Value is too long (max 200)."));
            }
        }
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
    }

    private String customJson(Map<String, String> cf) {
        if (cf == null || cf.isEmpty()) return null;
        Map<String, String> clean = new LinkedHashMap<>();
        cf.forEach((k, v) -> clean.put(k, v == null ? "" : Norm.plain(v)));
        return Json.write(clean);
    }

    public Map<String, Object> create(long ws, Long userId, CrmDtos.ContactRequest r) {
        validate(r);
        if (!Phones.valid(r.phone())) throw ApiException.invalid("phone", "vr.mobile.invalid", "Enter a valid 10-digit mobile number.");
        List<Long> tags = r.tagIds() == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(r.tagIds()));
        assertTags(ws, tags);
        Optional<Row> existing = db.row("select id, deleted_at from contacts where workspace_id=:ws and phone=:p", "ws", ws, "p", r.phone());
        if (existing.isPresent() && existing.get().t("deleted_at") == null) {
            throw new ApiException(ErrorCode.DUPLICATE, "A contact with this number already exists. Open it?",
                    List.of(new FieldErr("phone", "contact.phone.exists", "A contact with this number already exists. Open it?")),
                    Map.of("existingId", existing.get().l("id")), null);
        }
        plans.checkContactLimit(ws, 1);
        LocalDateTime now = Time.now();
        String optStatus = r.whatsappConsent() ? "OPTED_IN" : "UNKNOWN";
        long id;
        if (existing.isPresent()) { // restore a soft-deleted contact
            id = existing.get().l("id");
            db.update("update contacts set full_name=:n,email=:e,city=:c,date_of_birth=:d,language=:l,opt_in_status=:o,opt_in_source=:s," +
                            "opted_in_at=:oa,opted_out_at=null,custom_fields=:cf,created_by=:u,deleted_at=null where id=:id",
                    "n", r.fullName(), "e", r.email(), "c", r.city(), "d", r.dateOfBirth(), "l", r.language(), "o", optStatus,
                    "s", r.whatsappConsent() ? r.optInSource() : null, "oa", r.whatsappConsent() ? now : null, "cf", customJson(r.customFields()),
                    "u", userId, "id", id);
            db.update("delete from contact_tags where contact_id=:id", "id", id);
        } else {
            id = db.insertInto("contacts", "workspace_id", ws, "full_name", r.fullName(), "phone", r.phone(), "email", r.email(),
                    "city", r.city(), "date_of_birth", r.dateOfBirth(), "language", r.language(), "opt_in_status", optStatus,
                    "opt_in_source", r.whatsappConsent() ? r.optInSource() : null, "opted_in_at", r.whatsappConsent() ? now : null,
                    "custom_fields", customJson(r.customFields()), "created_by", userId);
        }
        for (Long t : tags) addTagRow(id, t);
        audit.log("CONTACT_CREATED", "contacts", id);
        events.publishEvent(new Events.ContactCreated(ws, id));
        for (Long t : tags) events.publishEvent(new Events.TagAdded(ws, id, t));
        return get(ws, id);
    }

    public Map<String, Object> update(long ws, long id, CrmDtos.ContactRequest r) {
        validate(r);
        Row cur = db.row("select * from contacts where id=:id and workspace_id=:ws and deleted_at is null", "id", id, "ws", ws)
                .orElseThrow(() -> ApiException.notFound("Contact"));
        if (!Phones.valid(r.phone())) throw ApiException.invalid("phone", "vr.mobile.invalid", "Enter a valid 10-digit mobile number.");
        if (db.exists("select 1 from contacts where workspace_id=:ws and phone=:p and id<>:id and deleted_at is null", "ws", ws, "p", r.phone(), "id", id))
            throw ApiException.duplicate("phone", "contact.phone.exists", "A contact with this number already exists. Open it?");
        List<Long> tags = r.tagIds() == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(r.tagIds()));
        assertTags(ws, tags);
        String status = cur.s("opt_in_status");
        String source = cur.s("opt_in_source");
        LocalDateTime optedInAt = cur.t("opted_in_at");
        if (r.whatsappConsent() && !"OPTED_IN".equals(status)) {
            if ("OPTED_OUT".equals(status))
                throw ApiException.state("An opted-out customer can opt in again only by messaging you or filling a form.");
            status = "OPTED_IN";
            source = r.optInSource();
            optedInAt = Time.now();
        } else if (!r.whatsappConsent() && "OPTED_IN".equals(status)) {
            // Un-ticking consent in the editor means the proof is withdrawn.
            status = "UNKNOWN";
            source = null;
        }
        db.update("update contacts set full_name=:n,phone=:p,email=:e,city=:c,date_of_birth=:d,language=:l,opt_in_status=:o,opt_in_source=:s," +
                        "opted_in_at=:oa,custom_fields=:cf where id=:id and workspace_id=:ws",
                "n", r.fullName(), "p", r.phone(), "e", r.email(), "c", r.city(), "d", r.dateOfBirth(), "l", r.language(), "o", status,
                "s", source, "oa", optedInAt, "cf", customJson(r.customFields()), "id", id, "ws", ws);
        Set<Long> before = new HashSet<>();
        db.rows("select tag_id from contact_tags where contact_id=:id", "id", id).forEach(t -> before.add(t.l("tag_id")));
        db.update("delete from contact_tags where contact_id=:id", "id", id);
        for (Long t : tags) addTagRow(id, t);
        audit.log("CONTACT_UPDATED", "contacts", id);
        for (Long t : tags) if (!before.contains(t)) events.publishEvent(new Events.TagAdded(ws, id, t));
        return get(ws, id);
    }

    private void assertTags(long ws, List<Long> tags) {
        if (tags.size() > 20) throw ApiException.invalid("tagIds", "contact.tags.max", "A contact can have up to 20 tags.");
        if (tags.isEmpty()) return;
        long found = db.count("select count(*) from tags where workspace_id=:ws and id in (:ids)", "ws", ws, "ids", tags);
        if (found != tags.size()) throw ApiException.invalid("tagIds", "contact.tags.unknown", "One of the tags does not exist.");
    }

    private void addTagRow(long contactId, long tagId) {
        db.update("insert ignore into contact_tags (contact_id, tag_id) values (:c,:t)", "c", contactId, "t", tagId);
    }

    public void delete(long ws, long id) {
        assertDeletable(ws, List.of(id));
        int n = db.update("update contacts set deleted_at=:now where id=:id and workspace_id=:ws and deleted_at is null", "now", Time.now(), "id", id, "ws", ws);
        if (n == 0) throw ApiException.notFound("Contact");
        audit.log("CONTACT_DELETED", "contacts", id);
    }

    private void assertDeletable(long ws, List<Long> ids) {
        if (ids.isEmpty()) return;
        boolean active = db.exists("select 1 from campaign_messages cm join campaigns c on c.id=cm.campaign_id where cm.workspace_id=:ws " +
                "and cm.contact_id in (:ids) and cm.status='QUEUED' and c.status in ('QUEUED','SENDING','PAUSED') limit 1", "ws", ws, "ids", ids);
        if (active) throw ApiException.state("Contacts in an active campaign cannot be deleted.");
    }

    // ----------------------------------------------------------------- bulk

    public Map<String, Object> bulk(long ws, CrmDtos.BulkRequest r, Filter filterFromBody) {
        List<Long> ids;
        if (r.allMatching()) {
            AudienceFragment w = where(ws, filterFromBody);
            ids = new ArrayList<>();
            for (Row x : db.rowsMap("select c.id from contacts c where " + w.sql() + " limit 10001", w.params())) ids.add(x.l("id"));
        } else {
            ids = r.ids() == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(r.ids()));
        }
        if (ids.isEmpty()) throw ApiException.badRequest("Select at least one contact.");
        if (ids.size() > 10_000) throw ApiException.invalid("ids", "bulk.max", "Select up to 10,000 contacts.");
        // restrict to this workspace's contacts
        List<Long> own = new ArrayList<>();
        for (List<Long> chunk : chunks(ids, 1000))
            db.rows("select id from contacts where workspace_id=:ws and deleted_at is null and id in (:ids)", "ws", ws, "ids", chunk).forEach(x -> own.add(x.l("id")));
        int affected = 0;
        switch (r.action()) {
            case "DELETE" -> {
                if (own.size() > 50 && !"DELETE".equals(r.confirm()))
                    throw ApiException.invalid("confirm", "bulk.confirm", "Type DELETE to confirm.");
                assertDeletable(ws, own);
                for (List<Long> chunk : chunks(own, 1000))
                    affected += db.update("update contacts set deleted_at=:now where workspace_id=:ws and id in (:ids)", "now", Time.now(), "ws", ws, "ids", chunk);
                audit.log("CONTACTS_BULK_DELETED", "contacts", null, null, Map.of("count", affected));
            }
            case "TAG", "UNTAG" -> {
                if (r.tagId() == null || !db.exists("select 1 from tags where id=:t and workspace_id=:ws", "t", r.tagId(), "ws", ws))
                    throw ApiException.invalid("tagId", "bulk.tag", "Choose a tag.");
                for (Long cid : own) {
                    if ("TAG".equals(r.action())) {
                        if (db.count("select count(*) from contact_tags where contact_id=:c", "c", cid) >= 20) continue;
                        int n = db.update("insert ignore into contact_tags (contact_id, tag_id) values (:c,:t)", "c", cid, "t", r.tagId());
                        if (n > 0) events.publishEvent(new Events.TagAdded(ws, cid, r.tagId()));
                        affected += n;
                    } else {
                        affected += db.update("delete from contact_tags where contact_id=:c and tag_id=:t", "c", cid, "t", r.tagId());
                    }
                }
            }
            default -> throw ApiException.badRequest("Unknown action.");
        }
        return Map.of("affected", affected);
    }

    private static <T> List<List<T>> chunks(List<T> l, int n) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < l.size(); i += n) out.add(l.subList(i, Math.min(l.size(), i + n)));
        return out;
    }

    // ---------------------------------------------------------------- export

    public String exportCsv(long ws, Filter f) {
        AudienceFragment w = where(ws, f);
        StringBuilder sb = new StringBuilder("name,phone,email,city,opt_in_status,opt_in_source,created_at\n");
        for (Row r : db.rowsMap("select c.* from contacts c where " + w.sql() + " order by c.id limit 50000", w.params())) {
            sb.append(csv(r.s("full_name"))).append(',').append(csv(r.s("phone"))).append(',').append(csv(r.s("email"))).append(',')
                    .append(csv(r.s("city"))).append(',').append(r.s("opt_in_status")).append(',').append(csv(r.s("opt_in_source"))).append(',')
                    .append(r.t("created_at")).append('\n');
        }
        audit.log("CONTACTS_EXPORTED", "contacts", null);
        return sb.toString();
    }

    public static String csv(String s) {
        if (s == null) return "";
        String v = s;
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) v = "'" + v; // CSV injection guard
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) return "\"" + v.replace("\"", "\"\"") + "\"";
        return v;
    }

    // ------------------------------------------------------------ opt in/out

    /** Opt out from a STOP keyword or an admin action; exits running automations. */
    public void optOut(long ws, long contactId) {
        int n = db.update("update contacts set opt_in_status='OPTED_OUT', opted_out_at=:now where id=:id and workspace_id=:ws and opt_in_status<>'OPTED_OUT'",
                "now", Time.now(), "id", contactId, "ws", ws);
        if (n > 0) {
            db.update("update automation_runs r join automations a on a.id=r.automation_id set r.status='EXITED', r.exit_reason='opted out', r.finished_at=:now " +
                    "where r.contact_id=:c and r.status in ('RUNNING','WAITING')", "now", Time.now(), "c", contactId);
            events.publishEvent(new Events.OptedOut(ws, contactId));
        }
    }

    /** A customer who messages again or fills a form may opt back in (SRS P08). */
    public void optInBySelf(long ws, long contactId, String source) {
        db.update("update contacts set opt_in_status='OPTED_IN', opt_in_source=:s, opted_in_at=:now, opted_out_at=null " +
                "where id=:id and workspace_id=:ws and opt_in_status='OPTED_OUT'", "s", source, "now", Time.now(), "id", contactId, "ws", ws);
    }
}
