package com.marketingpanel.crm;

import com.marketingpanel.auth.Auth;
import com.marketingpanel.auth.Roles;
import com.marketingpanel.common.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** P10 Tags & Segments. */
@RestController
@RequestMapping("/api/v1")
public class TagSegmentController {
    private final Db db;
    private final AuditService audit;

    public TagSegmentController(Db db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    @GetMapping("/tags")
    @PreAuthorize(Roles.MEMBER)
    public List<Map<String, Object>> tags() {
        return db.list("select t.*, (select count(*) from contact_tags ct join contacts c on c.id=ct.contact_id where ct.tag_id=t.id and c.deleted_at is null) as contact_count " +
                "from tags t where t.workspace_id=:ws order by t.name", "ws", Auth.ws());
    }

    @PostMapping("/tags")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> createTag(@Valid @RequestBody CrmDtos.TagRequest r) {
        long ws = Auth.ws();
        if (db.exists("select 1 from tags where workspace_id=:ws and lower(name)=lower(:n)", "ws", ws, "n", r.name()))
            throw ApiException.duplicate("name", "tag.exists", "A tag with this name already exists.");
        long id = db.insertInto("tags", "workspace_id", ws, "name", r.name(), "color", r.color());
        return db.one("select * from tags where id=:id", "id", id).orElseThrow();
    }

    @PutMapping("/tags/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> updateTag(@PathVariable long id, @Valid @RequestBody CrmDtos.TagRequest r) {
        long ws = Auth.ws();
        db.row("select id from tags where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Tag"));
        if (db.exists("select 1 from tags where workspace_id=:ws and lower(name)=lower(:n) and id<>:id", "ws", ws, "n", r.name(), "id", id))
            throw ApiException.duplicate("name", "tag.exists", "A tag with this name already exists.");
        db.update("update tags set name=:n, color=:c where id=:id", "n", r.name(), "c", r.color(), "id", id);
        return db.one("select * from tags where id=:id", "id", id).orElseThrow();
    }

    @DeleteMapping("/tags/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> deleteTag(@PathVariable long id) {
        long ws = Auth.ws();
        db.row("select id from tags where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Tag"));
        boolean used = db.exists("select 1 from campaigns where workspace_id=:ws and status='SCHEDULED' and audience_type='TAGS' " +
                "and json_contains(audience_json, cast(:id as json), '$.tagIds')", "ws", ws, "id", String.valueOf(id));
        if (used) throw ApiException.state("This tag is used by a scheduled campaign and cannot be deleted.");
        db.update("delete from tags where id=:id", "id", id);
        audit.log("TAG_DELETED", "tags", id);
        return Map.of("ok", true);
    }

    // ------------------------------------------------------------- segments

    @GetMapping("/segments")
    @PreAuthorize(Roles.MEMBER)
    public List<Map<String, Object>> segments() {
        return db.list("select * from segments where workspace_id=:ws order by name", "ws", Auth.ws());
    }

    @PostMapping("/segments/preview")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> preview(@RequestBody Map<String, Object> filter) {
        return Map.of("count", count(Auth.ws(), Json.tree(filter)));
    }

    @PostMapping("/segments")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> createSegment(@Valid @RequestBody CrmDtos.SegmentRequest r) {
        long ws = Auth.ws();
        long cnt = count(ws, r.filter());
        if (db.exists("select 1 from segments where workspace_id=:ws and lower(name)=lower(:n)", "ws", ws, "n", r.name()))
            throw ApiException.duplicate("name", "segment.exists", "A segment with this name already exists.");
        long id = db.insertInto("segments", "workspace_id", ws, "name", r.name(), "filter_json", r.filter().toString(),
                "contact_count", cnt, "created_by", Auth.userId());
        return db.one("select * from segments where id=:id", "id", id).orElseThrow();
    }

    @PutMapping("/segments/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> updateSegment(@PathVariable long id, @Valid @RequestBody CrmDtos.SegmentRequest r) {
        long ws = Auth.ws();
        db.row("select id from segments where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Segment"));
        long cnt = count(ws, r.filter());
        if (db.exists("select 1 from segments where workspace_id=:ws and lower(name)=lower(:n) and id<>:id", "ws", ws, "n", r.name(), "id", id))
            throw ApiException.duplicate("name", "segment.exists", "A segment with this name already exists.");
        db.update("update segments set name=:n, filter_json=:f, contact_count=:c where id=:id", "n", r.name(), "f", r.filter().toString(), "c", cnt, "id", id);
        return db.one("select * from segments where id=:id", "id", id).orElseThrow();
    }

    @DeleteMapping("/segments/{id}")
    @PreAuthorize(Roles.MANAGER)
    public Map<String, Object> deleteSegment(@PathVariable long id) {
        long ws = Auth.ws();
        db.row("select id from segments where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Segment"));
        boolean used = db.exists("select 1 from campaigns where workspace_id=:ws and status='SCHEDULED' and audience_type='SEGMENT' " +
                "and json_extract(audience_json, '$.segmentId') = :id", "ws", ws, "id", id);
        if (used) throw ApiException.state("This segment is used by a scheduled campaign and cannot be deleted.");
        db.update("delete from segments where id=:id", "id", id);
        return Map.of("ok", true);
    }

    private long count(long ws, com.fasterxml.jackson.databind.JsonNode filter) {
        AudienceQuery q = AudienceQuery.fromFilter(filter);
        Map<String, Object> p = new java.util.LinkedHashMap<>(q.params);
        p.put("ws", ws);
        Object[] kv = new Object[p.size() * 2];
        int i = 0;
        for (var e : p.entrySet()) {
            kv[i++] = e.getKey();
            kv[i++] = e.getValue();
        }
        return db.count("select count(*) from contacts c where " + AudienceQuery.BASE + " and (" + q.sql + ")", kv);
    }
}
