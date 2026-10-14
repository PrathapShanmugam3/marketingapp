package com.marketingpanel.crm;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.common.ApiException;
import com.marketingpanel.common.Json;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a safe, parameterised WHERE fragment over `contacts c` from a segment definition.
 * Only whitelisted fields and operators are ever turned into SQL.
 */
public final class AudienceQuery {
    public final String sql;
    public final Map<String, Object> params;

    private AudienceQuery(String sql, Map<String, Object> params) {
        this.sql = sql;
        this.params = params;
    }

    public static final String BASE = "c.workspace_id=:ws and c.deleted_at is null";

    /** Marketing audience types used by campaigns. Always limited to OPTED_IN contacts. */
    public static AudienceQuery forCampaign(String type, JsonNode audience, long segmentFilterWs, java.util.function.Function<Long, String> segmentLoader) {
        Map<String, Object> p = new LinkedHashMap<>();
        StringBuilder sb = new StringBuilder(BASE + " and c.opt_in_status='OPTED_IN'");
        switch (type) {
            case "ALL_OPTED_IN" -> {}
            case "TAGS" -> {
                List<Long> ids = new ArrayList<>();
                if (audience != null && audience.has("tagIds")) audience.get("tagIds").forEach(n -> ids.add(n.asLong()));
                if (ids.isEmpty() || ids.size() > 10) throw ApiException.invalid("audience", "audience.tags", "Choose who should receive this.");
                sb.append(" and exists (select 1 from contact_tags ct where ct.contact_id=c.id and ct.tag_id in (:tagIds))");
                p.put("tagIds", ids);
            }
            case "SEGMENT" -> {
                long segId = audience == null ? 0 : audience.path("segmentId").asLong(0);
                String filter = segId == 0 ? null : segmentLoader.apply(segId);
                if (filter == null) throw ApiException.invalid("audience", "audience.segment", "Choose who should receive this.");
                AudienceQuery seg = fromFilter(Json.tree(filter));
                sb.append(" and (").append(seg.sql).append(")");
                p.putAll(seg.params);
            }
            default -> throw ApiException.invalid("audienceType", "audience.type", "Choose who should receive this.");
        }
        return new AudienceQuery(sb.toString(), p);
    }

    /** Segment rules: {"match":"AND|OR","conditions":[{"field","op","value"}]} (1-10 conditions). */
    public static AudienceQuery fromFilter(JsonNode filter) {
        JsonNode conds = filter == null ? null : filter.get("conditions");
        if (conds == null || !conds.isArray() || conds.isEmpty() || conds.size() > 10)
            throw ApiException.invalid("filter", "segment.rules", "Add at least one condition.");
        String join = "OR".equalsIgnoreCase(filter.path("match").asText("AND")) ? " or " : " and ";
        Map<String, Object> p = new LinkedHashMap<>();
        List<String> parts = new ArrayList<>();
        int i = 0;
        for (JsonNode c : conds) {
            String field = c.path("field").asText("");
            String op = c.path("op").asText("");
            JsonNode v = c.get("value");
            String k = "sv" + (i++);
            switch (field) {
                case "tag" -> {
                    long tagId = v == null ? 0 : v.asLong(0);
                    if (tagId <= 0) throw bad(field);
                    parts.add(("not_has".equals(op) ? "not exists" : "exists") + " (select 1 from contact_tags ct where ct.contact_id=c.id and ct.tag_id=:" + k + ")");
                    p.put(k, tagId);
                }
                case "city" -> {
                    String s = text(v);
                    if ("contains".equals(op)) {
                        parts.add("c.city like :" + k);
                        p.put(k, "%" + escape(s) + "%");
                    } else {
                        parts.add("c.city = :" + k);
                        p.put(k, s);
                    }
                }
                case "optIn" -> {
                    String s = text(v);
                    if (!List.of("OPTED_IN", "OPTED_OUT", "UNKNOWN").contains(s)) throw bad(field);
                    parts.add("c.opt_in_status = :" + k);
                    p.put(k, s);
                }
                case "language" -> {
                    String s = text(v);
                    if (!List.of("en", "ta").contains(s)) throw bad(field);
                    parts.add("c.language = :" + k);
                    p.put(k, s);
                }
                case "createdAt", "lastMessageAt" -> {
                    LocalDate d;
                    try {
                        d = LocalDate.parse(text(v));
                    } catch (Exception e) {
                        throw bad(field);
                    }
                    String col = "createdAt".equals(field) ? "c.created_at" : "c.last_message_at";
                    boolean before = "before".equals(op);
                    parts.add(col + (before ? " < :" : " >= :") + k);
                    p.put(k, d.atStartOfDay());
                }
                case "birthMonth" -> {
                    int m = v == null ? 0 : v.asInt(0);
                    if (m < 1 || m > 12) throw bad(field);
                    parts.add("month(c.date_of_birth) = :" + k);
                    p.put(k, m);
                }
                default -> throw bad(field);
            }
        }
        return new AudienceQuery(String.join(join, parts), p);
    }

    private static String text(JsonNode v) {
        String s = v == null ? "" : v.asText("").trim();
        if (s.isEmpty() || s.length() > 100) throw bad("value");
        return s;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static ApiException bad(String field) {
        return ApiException.invalid("filter", "segment.rules", "Check the segment rule for " + field + ".");
    }
}
