package com.marketingpanel.common;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.function.Supplier;

/**
 * Thin helper over NamedParameterJdbcTemplate. Parameters are passed as key/value pairs:
 * {@code db.rows("select * from tags where workspace_id=:ws", "ws", 5)}.
 * Only parameterised SQL is ever executed.
 */
@Component
public class Db {
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate tx;

    public Db(NamedParameterJdbcTemplate named, TransactionTemplate tx) {
        this.named = named;
        this.tx = tx;
    }

    public NamedParameterJdbcTemplate named() {
        return named;
    }

    private static MapSqlParameterSource params(Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("params must be key/value pairs");
        MapSqlParameterSource p = new MapSqlParameterSource();
        for (int i = 0; i < kv.length; i += 2) p.addValue((String) kv[i], kv[i + 1]);
        return p;
    }

    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public List<Row> rows(String sql, Object... kv) {
        return named.query(sql, params(kv), (rs, n) -> {
            var md = rs.getMetaData();
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                m.put(md.getColumnLabel(i).toLowerCase(Locale.ROOT), rs.getObject(i));
            }
            return new Row(m);
        });
    }

    public List<Row> rowsMap(String sql, Map<String, Object> p) {
        return rows(sql, flatten(p));
    }

    public Optional<Row> row(String sql, Object... kv) {
        List<Row> r = rows(sql, kv);
        return r.isEmpty() ? Optional.empty() : Optional.of(r.get(0));
    }

    /** API-ready rows (camelCase, JSON parsed). */
    public List<Map<String, Object>> list(String sql, Object... kv) {
        return Row.api(rows(sql, kv));
    }

    public Optional<Map<String, Object>> one(String sql, Object... kv) {
        return row(sql, kv).map(Row::api);
    }

    public long count(String sql, Object... kv) {
        Long v = named.queryForObject(sql, params(kv), Long.class);
        return v == null ? 0 : v;
    }

    public <T> T scalar(String sql, Class<T> type, Object... kv) {
        List<T> r = named.queryForList(sql, params(kv), type);
        return r.isEmpty() ? null : r.get(0);
    }

    public boolean exists(String sql, Object... kv) {
        return !named.queryForList(sql, params(kv), Long.class).isEmpty();
    }

    public int update(String sql, Object... kv) {
        return named.update(sql, params(kv));
    }

    public int updateMap(String sql, Map<String, Object> p) {
        return update(sql, flatten(p));
    }

    public long insert(String sql, Object... kv) {
        GeneratedKeyHolder kh = new GeneratedKeyHolder();
        named.update(sql, params(kv), kh);
        Number k = kh.getKey();
        return k == null ? 0 : k.longValue();
    }

    public long insertMap(String sql, Map<String, Object> p) {
        return insert(sql, flatten(p));
    }

    /** Insert helper: db.insertInto("tags", "workspace_id", 1, "name", "x"). */
    public long insertInto(String table, Object... kv) {
        StringBuilder cols = new StringBuilder();
        StringBuilder vals = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) {
                cols.append(',');
                vals.append(',');
            }
            cols.append('`').append(kv[i]).append('`');
            vals.append(':').append(kv[i]);
        }
        return insert("insert into `" + table + "` (" + cols + ") values (" + vals + ")", kv);
    }

    /** Dynamic update of whitelisted columns: db.updateTable("tags", "id", id, Map.of(...)). */
    public int updateTable(String table, String idCol, Object id, Map<String, Object> cols, Object... extraWhere) {
        if (cols.isEmpty()) return 0;
        StringBuilder set = new StringBuilder();
        List<Object> kv = new ArrayList<>();
        int n = 0;
        for (var e : cols.entrySet()) {
            if (n++ > 0) set.append(',');
            set.append('`').append(e.getKey()).append("`=:v_").append(e.getKey());
            kv.add("v_" + e.getKey());
            kv.add(e.getValue());
        }
        StringBuilder where = new StringBuilder("`" + idCol + "`=:__id");
        kv.add("__id");
        kv.add(id);
        for (int i = 0; i < extraWhere.length; i += 2) {
            where.append(" and `").append(extraWhere[i]).append("`=:w_").append(extraWhere[i]);
            kv.add("w_" + extraWhere[i]);
            kv.add(extraWhere[i + 1]);
        }
        return update("update `" + table + "` set " + set + " where " + where, kv.toArray());
    }

    public <T> T tx(Supplier<T> s) {
        return tx.execute(st -> s.get());
    }

    public void tx(Runnable r) {
        tx.executeWithoutResult(st -> r.run());
    }

    private static Object[] flatten(Map<String, Object> p) {
        Object[] kv = new Object[p.size() * 2];
        int i = 0;
        for (var e : p.entrySet()) {
            kv[i++] = e.getKey();
            kv[i++] = e.getValue();
        }
        return kv;
    }
}
