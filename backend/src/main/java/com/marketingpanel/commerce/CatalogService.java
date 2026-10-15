package com.marketingpanel.commerce;

import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import com.marketingpanel.inbox.DirectSendService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/** Product catalog: manage items, publish a public catalog page and share a product with a customer on WhatsApp. */
@Service
public class CatalogService {
    private final Db db;
    private final PlanService plans;
    private final AuditService audit;
    private final DirectSendService send;
    private final Settings settings;

    public CatalogService(Db db, PlanService plans, AuditService audit, DirectSendService send, Settings settings) {
        this.db = db;
        this.plans = plans;
        this.audit = audit;
        this.send = send;
        this.settings = settings;
    }

    public record ProductRequest(String name, String description, String sku, String category, BigDecimal price, BigDecimal compareAtPrice, String imageUrl, Boolean inStock, Boolean active) {}

    public List<Map<String, Object>> list(long ws, String q) {
        return db.list("select * from products where workspace_id=:ws" + (q != null && q.trim().length() >= 2 ? " and (name like :q or sku like :q or category like :q)" : "") + " order by id desc limit 500",
                "ws", ws, "q", q == null ? null : "%" + q.trim() + "%");
    }

    private Map<String, Object> clean(Object unused, ProductRequest r) {
        List<FieldErr> errs = new ArrayList<>();
        String name = Norm.plain(r.name());
        if (name == null || name.length() < 2 || name.length() > 120) errs.add(new FieldErr("name", "prod.name", "Name must be 2 to 120 characters."));
        if (r.price() == null || r.price().signum() <= 0 || r.price().compareTo(new BigDecimal("10000000")) > 0) errs.add(new FieldErr("price", "prod.price", "Enter a price above zero."));
        if (r.compareAtPrice() != null && r.price() != null && r.compareAtPrice().compareTo(r.price()) <= 0) errs.add(new FieldErr("compareAtPrice", "prod.compare", "The original price must be higher than the selling price."));
        String img = Norm.text(r.imageUrl());
        if (img != null && !(img.startsWith("/files/") || UrlGuard.isPublicHttps(img, 500))) errs.add(new FieldErr("imageUrl", "vr.url.invalid", "Upload an image or enter a valid https:// link."));
        String desc = Norm.plain(r.description());
        if (desc != null && desc.length() > 1000) errs.add(new FieldErr("description", "vr.size", "Description is too long."));
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", desc);
        m.put("sku", Norm.text(r.sku()));
        m.put("category", Norm.plain(r.category()));
        m.put("price", r.price().setScale(2, java.math.RoundingMode.HALF_UP));
        m.put("compare_at_price", r.compareAtPrice());
        m.put("image_url", img);
        m.put("in_stock", r.inStock() == null || r.inStock());
        m.put("active", r.active() == null || r.active());
        return m;
    }


    public Map<String, Object> create(long ws, ProductRequest r) {
        plans.requireFeature(ws, "catalog");
        Map<String, Object> m = clean(null, r);
        if (db.count("select count(*) from products where workspace_id=:ws", "ws", ws) >= 2000) throw ApiException.planLimit("You can list up to 2,000 products.");
        Object[] kv = new Object[m.size() * 2 + 2];
        kv[0] = "workspace_id";
        kv[1] = ws;
        int i = 2;
        for (var e : m.entrySet()) {
            kv[i++] = e.getKey();
            kv[i++] = e.getValue();
        }
        long id = db.insertInto("products", kv);
        audit.log("PRODUCT_CREATED", "products", id);
        return db.one("select * from products where id=:id", "id", id).orElseThrow();
    }

    public Map<String, Object> update(long ws, long id, ProductRequest r) {
        db.row("select id from products where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Product"));
        db.updateTable("products", "id", id, clean(null, r), "workspace_id", ws);
        return db.one("select * from products where id=:id", "id", id).orElseThrow();
    }

    public void delete(long ws, long id) {
        if (db.update("delete from products where id=:id and workspace_id=:ws", "id", id, "ws", ws) == 0) throw ApiException.notFound("Product");
    }

    /** Sends the product (image, price, short description, link to the catalog page) inside the open 24-hour window. */
    public Map<String, Object> share(long ws, long userId, long productId, long contactId) {
        Row p = db.row("select * from products where id=:id and workspace_id=:ws and active=1", "id", productId, "ws", ws).orElseThrow(() -> ApiException.notFound("Product"));
        Row conv = db.row("select * from conversations where workspace_id=:ws and contact_id=:c", "ws", ws, "c", contactId)
                .orElseThrow(() -> ApiException.state("This customer has not messaged you yet, so you can only reach them with an approved template."));
        String text = "*" + p.s("name") + "*\n" + Money.format(p.d("price")) + (p.d("compare_at_price") != null ? " (was " + Money.format(p.d("compare_at_price")) + ")" : "")
                + (p.s("description") == null ? "" : "\n" + p.s("description")) + (p.b("in_stock") ? "" : "\nCurrently out of stock.") + "\n" + settings.publicBaseUrl + "/c/" + ws;
        String img = p.s("image_url");
        String media = img == null ? null : (img.startsWith("/") ? settings.publicBaseUrl + img : img);
        String id = send.sendText(ws, conv.l("id"), text, media, "image", userId);
        return Map.of("ok", true, "messageId", id);
    }

    // ------------------------------------------------------------------ public page /c/{ws}
    public String page(long ws) {
        Row w = db.row("select name, city, phone, brand_color from workspaces where id=:id and status in ('TRIAL','ACTIVE') and deleted_at is null", "id", ws).orElseThrow(() -> ApiException.notFound("Catalog"));
        StringBuilder b = new StringBuilder("<div class=\"wrap\"><h1>" + Html.esc(w.s("name")) + "</h1><p class=\"muted\">" + Html.esc(w.s("city") == null ? "" : w.s("city")) + "</p><div class=\"grid\">");
        List<Row> items = db.rows("select * from products where workspace_id=:ws and active=1 order by category, id desc limit 500", "ws", ws);
        if (items.isEmpty()) b.append("<p>No products yet.</p>");
        for (Row p : items) {
            String img = p.s("image_url");
            b.append("<div class=\"item\">");
            if (img != null) b.append("<img loading=\"lazy\" alt=\"").append(Html.esc(p.s("name"))).append("\" src=\"").append(Html.esc(img)).append("\">");
            b.append("<h3>").append(Html.esc(p.s("name"))).append("</h3><div class=\"price\">").append(Html.esc(Money.format(p.d("price"))));
            if (p.d("compare_at_price") != null) b.append(" <s>").append(Html.esc(Money.format(p.d("compare_at_price")))).append("</s>");
            b.append("</div>");
            if (p.s("description") != null) b.append("<p>").append(Html.esc(p.s("description"))).append("</p>");
            if (!p.b("in_stock")) b.append("<p class=\"muted\">Out of stock</p>");
            else if (w.s("phone") != null) b.append("<a class=\"btn\" rel=\"noopener\" href=\"https://wa.me/").append(Html.esc(w.s("phone").replace("+", ""))).append("?text=")
                    .append(java.net.URLEncoder.encode("Hi, I am interested in " + p.s("name"), java.nio.charset.StandardCharsets.UTF_8)).append("\">Order on WhatsApp</a>");
            b.append("</div>");
        }
        b.append("</div></div><style>.wrap{max-width:960px;margin:0 auto;padding:16px}.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:14px}.item{border:1px solid #ddd;border-radius:12px;padding:12px}")
                .append(".item img{width:100%;aspect-ratio:1;object-fit:cover;border-radius:8px}.price{font-weight:700}.muted{color:#667}.btn{display:inline-block;background:")
                .append(w.s("brand_color") != null && w.s("brand_color").matches("^#[0-9a-fA-F]{6}$") ? w.s("brand_color") : "#128C7E").append(";color:#fff;padding:8px 12px;border-radius:8px;text-decoration:none}</style>");
        return Html.shell(w.s("name") + " catalog", b.toString());
    }
}
