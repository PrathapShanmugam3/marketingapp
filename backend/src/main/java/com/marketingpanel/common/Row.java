package com.marketingpanel.common;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One database row with snake_case column keys and typed getters. */
public final class Row {
    private static final Set<String> JSON_COLUMNS = Set.of(
            "features", "custom_fields", "filter_json", "column_mapping", "buttons_json", "sample_values_json",
            "fields_json", "audience_json", "variable_mapping_json", "blocks_json", "trigger_config", "steps_json",
            "scopes", "attachments_json", "media_json", "payload", "old_values", "new_values", "allowed_ips", "geo", "fraud_flags", "params");

    private final Map<String, Object> m;

    public Row(Map<String, Object> m) {
        this.m = m;
    }

    public Map<String, Object> raw() {
        return m;
    }

    public boolean has(String c) {
        return m.get(c) != null;
    }

    public Object get(String c) {
        return m.get(c);
    }

    public long l(String c) {
        Object o = m.get(c);
        if (o == null) throw new IllegalStateException("Column " + c + " is null");
        return ((Number) o).longValue();
    }

    public Long lo(String c) {
        Object o = m.get(c);
        return o == null ? null : ((Number) o).longValue();
    }

    public int i(String c) {
        return (int) l(c);
    }

    public String s(String c) {
        Object o = m.get(c);
        if (o == null) return null;
        if (o instanceof byte[] b) return new String(b, java.nio.charset.StandardCharsets.UTF_8);
        return o.toString();
    }

    public boolean b(String c) {
        Object o = m.get(c);
        if (o == null) return false;
        if (o instanceof Boolean x) return x;
        return ((Number) o).intValue() != 0;
    }

    public BigDecimal d(String c) {
        Object o = m.get(c);
        if (o == null) return null;
        if (o instanceof BigDecimal x) return x;
        if (o instanceof BigInteger x) return new BigDecimal(x);
        return new BigDecimal(o.toString());
    }

    public LocalDateTime t(String c) {
        Object o = m.get(c);
        if (o == null) return null;
        if (o instanceof LocalDateTime x) return x;
        if (o instanceof Timestamp x) return x.toLocalDateTime();
        return LocalDateTime.parse(o.toString().replace(' ', 'T'));
    }

    public byte[] bytes(String c) {
        return (byte[]) m.get(c);
    }

    public LocalDate date(String c) {
        Object o = m.get(c);
        if (o == null) return null;
        if (o instanceof LocalDate x) return x;
        if (o instanceof java.sql.Date x) return x.toLocalDate();
        return LocalDate.parse(o.toString());
    }

    /** API-friendly map: camelCase keys, JSON columns parsed, binary columns dropped. */
    public Map<String, Object> api() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : m.entrySet()) {
            Object v = e.getValue();
            if (v instanceof byte[]) continue;
            if (v instanceof String s && JSON_COLUMNS.contains(e.getKey()) && !s.isBlank()) {
                try {
                    v = Json.mapper().readValue(s, Object.class);
                } catch (Exception ignored) {
                    // leave as string
                }
            }
            if (v instanceof BigInteger bi) v = bi.longValue();
            out.put(camel(e.getKey()), v);
        }
        return out;
    }

    public static List<Map<String, Object>> api(List<Row> rows) {
        return rows.stream().map(Row::api).toList();
    }

    public static String camel(String snake) {
        StringBuilder sb = new StringBuilder(snake.length());
        boolean up = false;
        for (char ch : snake.toCharArray()) {
            if (ch == '_') {
                up = true;
            } else {
                sb.append(up ? Character.toUpperCase(ch) : ch);
                up = false;
            }
        }
        return sb.toString();
    }
}
