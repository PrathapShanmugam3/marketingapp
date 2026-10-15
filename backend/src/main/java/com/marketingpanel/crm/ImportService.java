package com.marketingpanel.crm;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.marketingpanel.common.*;
import com.marketingpanel.common.Row;
import com.marketingpanel.jobs.JobHandler;
import com.marketingpanel.jobs.JobService;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;

/** P09 Import Contacts: CSV/XLSX up to 5 MB and 10,000 rows, processed by a background job. */
@Service
public class ImportService implements JobHandler {
    public static final int MAX_ROWS = 10_000;
    private static final Pattern NAME = Pattern.compile("^[\\p{L}\\p{M} .'&-]{2,100}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final List<String> FIELDS = List.of("fullName", "phone", "email", "city", "dateOfBirth", "language");
    private static final List<DateTimeFormatter> DATES = List.of(DateTimeFormatter.ISO_LOCAL_DATE, DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"), DateTimeFormatter.ofPattern("d/M/yyyy"));

    private final Db db;
    private final FileStore store;
    private final JobService jobs;
    private final PlanService plans;
    private final ApplicationEventPublisher events;
    private final AuditService audit;

    public ImportService(Db db, FileStore store, JobService jobs, PlanService plans, ApplicationEventPublisher events, AuditService audit) {
        this.db = db;
        this.store = store;
        this.jobs = jobs;
        this.plans = plans;
        this.events = events;
        this.audit = audit;
    }

    public record Parsed(List<String> headers, List<List<String>> rows) {}

    /** Upload step: validates the file signature, size and row count; stores the file and returns headers + 10 preview rows. */
    public Map<String, Object> upload(long ws, long userId, String fileName, byte[] data) {
        String msg = "Upload a CSV or Excel file under 5 MB.";
        if (data.length == 0 || data.length > 5 << 20) throw ApiException.invalid("file", "import.size", msg);
        boolean xlsx = data.length > 4 && data[0] == 'P' && data[1] == 'K';
        boolean csvOk = !xlsx && looksLikeText(data);
        String lower = fileName == null ? "" : fileName.toLowerCase();
        if ((xlsx && !lower.endsWith(".xlsx")) || (csvOk && !lower.endsWith(".csv")) || (!xlsx && !csvOk))
            throw ApiException.invalid("file", "import.type", msg);
        Parsed p = parse(data, xlsx);
        if (p.rows().size() > MAX_ROWS) throw ApiException.invalid("file", "import.rows", "The file has more than 10,000 rows.");
        if (p.headers().isEmpty()) throw ApiException.invalid("file", "import.empty", "The first row must contain column names.");
        String url = store.save(data, xlsx ? "xlsx" : "csv");
        long id = db.insertInto("contact_imports", "workspace_id", ws, "file_name", clip(fileName, 255), "file_url", url, "column_mapping", "{}",
                "consent_confirmed", false, "total_rows", p.rows().size(), "created_by", userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("headers", p.headers());
        out.put("totalRows", p.rows().size());
        out.put("preview", p.rows().stream().limit(10).toList());
        out.put("suggestedMapping", suggest(p.headers()));
        return out;
    }

    private static String clip(String s, int n) {
        return s == null ? "file" : s.length() > n ? s.substring(0, n) : s;
    }

    private static boolean looksLikeText(byte[] d) {
        int n = Math.min(d.length, 4096);
        for (int i = 0; i < n; i++) if (d[i] == 0) return false;
        return true;
    }

    static Parsed parse(byte[] data, boolean xlsx) {
        try {
            List<List<String>> all = new ArrayList<>();
            if (xlsx) {
                try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(data))) {
                    Sheet sh = wb.getSheetAt(0);
                    DataFormatter fmt = new DataFormatter();
                    for (org.apache.poi.ss.usermodel.Row r : sh) {
                        List<String> cells = new ArrayList<>();
                        int last = Math.max(r.getLastCellNum(), 0);
                        for (int c = 0; c < last; c++) {
                            Cell cell = r.getCell(c);
                            cells.add(cell == null ? "" : fmt.formatCellValue(cell).trim());
                        }
                        if (cells.stream().anyMatch(s -> !s.isEmpty())) all.add(cells);
                        if (all.size() > MAX_ROWS + 2) break;
                    }
                }
            } else {
                String text = new String(data, StandardCharsets.UTF_8);
                if (text.startsWith("﻿")) text = text.substring(1);
                try (CSVParser csv = CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(true).setTrim(true).build()
                        .parse(new InputStreamReader(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))) {
                    for (CSVRecord rec : csv) {
                        List<String> cells = new ArrayList<>();
                        rec.forEach(cells::add);
                        all.add(cells);
                        if (all.size() > MAX_ROWS + 2) break;
                    }
                }
            }
            if (all.isEmpty()) return new Parsed(List.of(), List.of());
            return new Parsed(all.get(0), all.subList(1, all.size()));
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.invalid("file", "import.type", "Upload a CSV or Excel file under 5 MB.");
        }
    }

    private Map<String, String> suggest(List<String> headers) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i).toLowerCase().replaceAll("[^a-z]", "");
            String f = null;
            if (h.contains("name")) f = "fullName";
            else if (h.contains("phone") || h.contains("mobile") || h.contains("whatsapp") || h.equals("number")) f = "phone";
            else if (h.contains("mail")) f = "email";
            else if (h.contains("city") || h.contains("town")) f = "city";
            else if (h.contains("birth") || h.equals("dob")) f = "dateOfBirth";
            else if (h.contains("lang")) f = "language";
            if (f != null && !m.containsValue(f)) m.put(String.valueOf(i), f);
        }
        return m;
    }

    // ------------------------------------------------------------ validation

    public record RowResult(Map<String, Object> values, String error) {}

    static RowResult validateRow(List<String> cells, Map<Integer, String> mapping, String region) {
        Map<String, Object> v = new LinkedHashMap<>();
        for (var e : mapping.entrySet()) {
            String raw = e.getKey() < cells.size() ? cells.get(e.getKey()).trim() : "";
            if (!raw.isEmpty()) v.put(e.getValue(), raw);
        }
        String name = Norm.name((String) v.get("fullName"));
        if (name == null || !NAME.matcher(name).matches()) return new RowResult(v, "Enter a valid name (2-100 letters).");
        String phone = Phones.e164((String) v.get("phone"), region);
        if (phone == null) return new RowResult(v, "Enter a valid mobile number.");
        String email = Norm.email((String) v.get("email"));
        if (email != null && (email.length() > 150 || !EMAIL.matcher(email).matches())) return new RowResult(v, "Enter a valid email address.");
        String city = Norm.name((String) v.get("city"));
        if (city != null && city.length() > 60) return new RowResult(v, "City is too long.");
        LocalDate dob = null;
        if (v.get("dateOfBirth") != null) {
            for (DateTimeFormatter f : DATES) {
                try {
                    dob = LocalDate.parse((String) v.get("dateOfBirth"), f);
                    break;
                } catch (Exception ignored) {
                    // try next
                }
            }
            if (dob == null || dob.isAfter(LocalDate.now()) || dob.isBefore(LocalDate.now().minusYears(120)))
                return new RowResult(v, "Enter a valid date of birth.");
        }
        String lang = v.get("language") == null ? null : ((String) v.get("language")).toLowerCase();
        if (lang != null) {
            if (lang.startsWith("ta")) lang = "ta";
            else if (lang.startsWith("en")) lang = "en";
            else return new RowResult(v, "Language must be en or ta.");
        }
        Map<String, Object> clean = new LinkedHashMap<>();
        clean.put("fullName", name);
        clean.put("phone", phone);
        clean.put("email", email);
        clean.put("city", city);
        clean.put("dateOfBirth", dob);
        clean.put("language", lang);
        return new RowResult(clean, null);
    }

    private static Map<Integer, String> mapping(JsonNode m) {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (m == null) return out;
        m.fields().forEachRemaining(e -> {
            if (!e.getValue().isNull() && !e.getValue().asText().isBlank()) out.put(Integer.parseInt(e.getKey()), e.getValue().asText());
        });
        return out;
    }

    private static String regionFor(String callingCode) {
        try {
            String r = PhoneNumberUtil.getInstance().getRegionCodeForCountryCode(Integer.parseInt(callingCode == null ? "91" : callingCode.replace("+", "")));
            return "ZZ".equals(r) ? "IN" : r;
        } catch (Exception e) {
            return "IN";
        }
    }

    private void checkMapping(JsonNode m) {
        Map<Integer, String> map = mapping(m);
        Set<String> seen = new HashSet<>();
        for (String f : map.values()) {
            if (!FIELDS.contains(f)) throw ApiException.badRequest("Unknown field " + f);
            if (!seen.add(f)) throw ApiException.invalid("mapping", "import.mapping.dup", "Each field can be mapped only once.");
        }
        if (!map.containsValue("phone")) throw ApiException.invalid("mapping", "import.mapping.phone", "Map a column to Mobile number.");
        if (!map.containsValue("fullName")) throw ApiException.invalid("mapping", "import.mapping.name", "Map a column to Name.");
    }

    public Map<String, Object> preview(long ws, long importId, JsonNode mappingNode, String callingCode) {
        Row imp = load(ws, importId);
        checkMapping(mappingNode);
        Parsed p = readFile(imp);
        Map<Integer, String> map = mapping(mappingNode);
        String region = regionFor(callingCode);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < Math.min(10, p.rows().size()); i++) {
            RowResult r = validateRow(p.rows().get(i), map, region);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("row", i + 2);
            m.put("values", r.values());
            m.put("error", r.error());
            out.add(m);
        }
        return Map.of("rows", out);
    }

    private Row load(long ws, long id) {
        return db.row("select * from contact_imports where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Import"));
    }

    private Parsed readFile(Row imp) {
        byte[] data = store.read(imp.s("file_url"));
        if (data == null) throw ApiException.state("The uploaded file is no longer available. Upload it again.");
        return parse(data, imp.s("file_url").endsWith(".xlsx"));
    }

    public record StartRequest(JsonNode mapping, String defaultCountryCode, String duplicateMode, Long tagId, String newTagName, boolean consentConfirmed) {}

    public Map<String, Object> start(long ws, long importId, StartRequest r) {
        Row imp = load(ws, importId);
        if (!"UPLOADED".equals(imp.s("status"))) throw ApiException.state("This import has already started.");
        if (!r.consentConfirmed()) throw ApiException.invalid("consentConfirmed", "import.consent", "Confirm you have consent to message these contacts.");
        checkMapping(r.mapping());
        String dup = "UPDATE".equals(r.duplicateMode()) ? "UPDATE" : "SKIP";
        Long tagId = r.tagId();
        if (tagId != null && !db.exists("select 1 from tags where id=:t and workspace_id=:ws", "t", tagId, "ws", ws))
            throw ApiException.invalid("tagId", "import.tag", "Choose an existing tag.");
        if (tagId == null && r.newTagName() != null && !r.newTagName().isBlank()) {
            String n = Norm.plain(Norm.name(r.newTagName()));
            if (n == null || n.length() > 40) throw ApiException.invalid("newTagName", "tag.name", "Tag name can be 1-40 characters.");
            tagId = db.scalar("select id from tags where workspace_id=:ws and lower(name)=lower(:n)", Long.class, "ws", ws, "n", n);
            if (tagId == null) tagId = db.insertInto("tags", "workspace_id", ws, "name", n, "color", "#12A388");
        }
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("mapping", Json.mapper().convertValue(r.mapping(), Map.class));
        cfg.put("country", r.defaultCountryCode() == null ? "91" : r.defaultCountryCode());
        cfg.put("tagId", tagId);
        db.update("update contact_imports set column_mapping=:m, duplicate_mode=:d, consent_confirmed=1, status='PROCESSING' where id=:id",
                "m", Json.write(cfg), "d", dup, "id", importId);
        jobs.enqueue("IMPORT_CONTACTS", Map.of("importId", importId));
        audit.log("CONTACTS_IMPORT_STARTED", "contact_imports", importId);
        return status(ws, importId);
    }

    public Map<String, Object> status(long ws, long id) {
        Map<String, Object> m = load(ws, id).api();
        m.remove("columnMapping");
        return m;
    }

    public List<Map<String, Object>> list(long ws) {
        return db.list("select id,file_name,total_rows,imported_rows,updated_rows,failed_rows,status,error_report_url,created_at,completed_at " +
                "from contact_imports where workspace_id=:ws order by id desc limit 50", "ws", ws);
    }

    // ------------------------------------------------------------------ job

    @Override
    public String type() {
        return "IMPORT_CONTACTS";
    }

    @Override
    public void handle(JsonNode payload) {
        long id = payload.get("importId").asLong();
        Row imp = db.row("select * from contact_imports where id=:id", "id", id).orElse(null);
        if (imp == null || !"PROCESSING".equals(imp.s("status"))) return;
        long ws = imp.l("workspace_id");
        try {
            run(imp, ws);
        } catch (Exception e) {
            db.update("update contact_imports set status='FAILED', completed_at=:now where id=:id", "now", Time.now(), "id", id);
            throw e instanceof RuntimeException re ? re : new RuntimeException(e);
        }
    }

    @Override
    public void onDead(JsonNode payload, String error) {
        db.update("update contact_imports set status='FAILED', completed_at=:now where id=:id and status<>'COMPLETED'", "now", Time.now(), "id", payload.get("importId").asLong());
    }

    private void run(Row imp, long ws) {
        JsonNode cfg = Json.tree(imp.s("column_mapping"));
        Map<Integer, String> map = mapping(cfg.get("mapping"));
        String region = regionFor(cfg.path("country").asText("91"));
        Long tagId = cfg.hasNonNull("tagId") ? cfg.get("tagId").asLong() : null;
        boolean update = "UPDATE".equals(imp.s("duplicate_mode"));
        Parsed p = readFile(imp);
        long max = plans.plan(ws).l("max_contacts");
        long current = db.count("select count(*) from contacts where workspace_id=:ws and deleted_at is null", "ws", ws);
        Set<String> seen = new HashSet<>();
        StringBuilder errors = new StringBuilder("row,reason,data\n");
        int imported = 0, updated = 0, failed = 0;
        Long createdBy = imp.lo("created_by");
        boolean limitHit = false;
        for (int i = 0; i < p.rows().size(); i++) {
            int rowNo = i + 2;
            List<String> cells = p.rows().get(i);
            RowResult rr = validateRow(cells, map, region);
            if (rr.error() != null) {
                failed++;
                errors.append(rowNo).append(',').append(ContactService.csv(rr.error())).append(',').append(ContactService.csv(String.join("|", cells))).append('\n');
                continue;
            }
            String phone = (String) rr.values().get("phone");
            if (!seen.add(phone)) {
                failed++;
                errors.append(rowNo).append(",Duplicate number in this file (first row wins),").append(ContactService.csv(String.join("|", cells))).append('\n');
                continue;
            }
            Optional<Row> ex = db.row("select * from contacts where workspace_id=:ws and phone=:p", "ws", ws, "p", phone);
            long cid;
            if (ex.isPresent() && ex.get().t("deleted_at") == null) {
                if (!update) {
                    failed++;
                    errors.append(rowNo).append(",Already exists (skipped),").append(ContactService.csv(String.join("|", cells))).append('\n');
                    continue;
                }
                cid = ex.get().l("id");
                db.update("update contacts set full_name=:n, email=coalesce(:e,email), city=coalesce(:c,city), date_of_birth=coalesce(:d,date_of_birth), " +
                                "language=coalesce(:l,language), opt_in_status=if(opt_in_status='UNKNOWN','OPTED_IN',opt_in_status), " +
                                "opt_in_source=if(opt_in_status='OPTED_IN' and opt_in_source is null,'IMPORT',opt_in_source), " +
                                "opted_in_at=if(opted_in_at is null and opt_in_status='OPTED_IN',:now,opted_in_at) where id=:id",
                        "n", rr.values().get("fullName"), "e", rr.values().get("email"), "c", rr.values().get("city"), "d", rr.values().get("dateOfBirth"),
                        "l", rr.values().get("language"), "now", Time.now(), "id", cid);
                updated++;
            } else {
                if (current >= max) {
                    failed++;
                    limitHit = true;
                    errors.append(rowNo).append(",Plan contact limit reached,").append(ContactService.csv(String.join("|", cells))).append('\n');
                    continue;
                }
                if (ex.isPresent()) {
                    cid = ex.get().l("id");
                    db.update("update contacts set full_name=:n,email=:e,city=:c,date_of_birth=:d,language=:l,opt_in_status='OPTED_IN',opt_in_source='IMPORT'," +
                                    "opted_in_at=:now,opted_out_at=null,deleted_at=null,custom_fields=null,created_by=:u where id=:id",
                            "n", rr.values().get("fullName"), "e", rr.values().get("email"), "c", rr.values().get("city"), "d", rr.values().get("dateOfBirth"),
                            "l", rr.values().get("language"), "now", Time.now(), "u", createdBy, "id", cid);
                    db.update("delete from contact_tags where contact_id=:id", "id", cid);
                } else {
                    cid = db.insertInto("contacts", "workspace_id", ws, "full_name", rr.values().get("fullName"), "phone", phone,
                            "email", rr.values().get("email"), "city", rr.values().get("city"), "date_of_birth", rr.values().get("dateOfBirth"),
                            "language", rr.values().get("language"), "opt_in_status", "OPTED_IN", "opt_in_source", "IMPORT",
                            "opted_in_at", Time.now(), "created_by", createdBy);
                }
                current++;
                imported++;
                events.publishEvent(new Events.ContactCreated(ws, cid));
            }
            if (tagId != null) {
                int n = db.update("insert ignore into contact_tags (contact_id, tag_id) values (:c,:t)", "c", cid, "t", tagId);
                if (n > 0) events.publishEvent(new Events.TagAdded(ws, cid, tagId));
            }
            if (i % 200 == 0)
                db.update("update contact_imports set imported_rows=:i, updated_rows=:u, failed_rows=:f where id=:id", "i", imported, "u", updated, "f", failed, "id", imp.l("id"));
        }
        String errUrl = failed > 0 ? store.save(errors.toString().getBytes(StandardCharsets.UTF_8), "csv") : null;
        db.update("update contact_imports set imported_rows=:i, updated_rows=:u, failed_rows=:f, error_report_url=:e, status='COMPLETED', completed_at=:now where id=:id",
                "i", imported, "u", updated, "f", failed, "e", errUrl, "now", Time.now(), "id", imp.l("id"));
        if (limitHit) {
            db.insert("insert into notifications (workspace_id,type,title,body,link) values (:ws,'IMPORT_LIMIT','Import stopped at your plan limit','Upgrade your plan to import the remaining contacts.','/settings/billing')", "ws", ws);
        }
    }
}
