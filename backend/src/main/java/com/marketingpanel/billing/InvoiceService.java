package com.marketingpanel.billing;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.marketingpanel.common.*;
import com.marketingpanel.common.Row;
import com.marketingpanel.config.Settings;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.Map;

/** GST invoices numbered MP/2026-27/0001 per financial year (April to March, IST). */
@Service
public class InvoiceService {
    private final Db db;
    private final Settings settings;

    public InvoiceService(Db db, Settings settings) {
        this.db = db;
        this.settings = settings;
    }

    public static String financialYear(LocalDate d) {
        int y = d.getMonthValue() >= 4 ? d.getYear() : d.getYear() - 1;
        return y + "-" + String.format("%02d", (y + 1) % 100);
    }

    /** Creates a PAID invoice; call inside the transaction that records the payment. */
    public long create(long ws, String type, Gst gst, String customerGstin, String placeOfSupply) {
        String fy = financialYear(LocalDate.now(Time.IST));
        for (int attempt = 0; attempt < 5; attempt++) {
            Long max = db.scalar("select max(cast(substring_index(invoice_number,'/',-1) as unsigned)) from invoices where invoice_number like :p for update", Long.class,
                    "p", "MP/" + fy + "/%");
            String number = "MP/" + fy + "/" + String.format("%04d", (max == null ? 0 : max) + 1 + attempt);
            try {
                return db.insertInto("invoices", "workspace_id", ws, "invoice_number", number, "type", type, "taxable_amount", gst.taxable(), "cgst", gst.cgst(),
                        "sgst", gst.sgst(), "igst", gst.igst(), "total", gst.total(), "customer_gstin", customerGstin, "place_of_supply", placeOfSupply,
                        "status", "PAID", "issued_at", Time.now());
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // another transaction took this number; try the next one
            }
        }
        throw new IllegalStateException("Could not allocate an invoice number");
    }

    public byte[] pdf(long ws, long id) {
        Row inv = db.row("select * from invoices where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Invoice"));
        Row w = db.row("select * from workspaces where id=:id", "id", ws).orElseThrow();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 48, 48, 54, 48);
            PdfWriter.getInstance(doc, out);
            doc.open();
            Font h = new Font(Font.HELVETICA, 20, Font.BOLD), b = new Font(Font.HELVETICA, 10), bb = new Font(Font.HELVETICA, 10, Font.BOLD);
            doc.add(new Paragraph("TAX INVOICE", h));
            doc.add(new Paragraph(settings.companyName + (settings.companyGstin.isBlank() ? "" : "  |  GSTIN " + settings.companyGstin) + "  |  " + settings.companyState, b));
            doc.add(new Paragraph(" "));
            PdfPTable meta = new PdfPTable(2);
            meta.setWidthPercentage(100);
            meta.addCell(cell("Invoice no", bb));
            meta.addCell(cell(inv.s("invoice_number"), b));
            meta.addCell(cell("Date", bb));
            meta.addCell(cell(Time.fmt(inv.t("issued_at")), b));
            meta.addCell(cell("Billed to", bb));
            meta.addCell(cell(w.s("name") + (w.s("address_line") == null ? "" : ", " + w.s("address_line")) + ", " + w.s("city") + ", " + w.s("state"), b));
            meta.addCell(cell("Customer GSTIN", bb));
            meta.addCell(cell(inv.s("customer_gstin") == null ? "-" : inv.s("customer_gstin"), b));
            meta.addCell(cell("Place of supply", bb));
            meta.addCell(cell(inv.s("place_of_supply"), b));
            doc.add(meta);
            doc.add(new Paragraph(" "));
            PdfPTable t = new PdfPTable(new float[]{3, 1});
            t.setWidthPercentage(100);
            String desc = switch (inv.s("type")) {
                case "SUBSCRIPTION" -> "Marketing Panel subscription";
                case "WALLET" -> "Message wallet credit";
                case "SETUP" -> "Setup and onboarding";
                default -> "Service";
            };
            t.addCell(cell(desc, b));
            t.addCell(right(Money.format(inv.d("taxable_amount")), b));
            if (inv.d("igst").signum() > 0) {
                t.addCell(cell("IGST 18%", b));
                t.addCell(right(Money.format(inv.d("igst")), b));
            } else {
                t.addCell(cell("CGST 9%", b));
                t.addCell(right(Money.format(inv.d("cgst")), b));
                t.addCell(cell("SGST 9%", b));
                t.addCell(right(Money.format(inv.d("sgst")), b));
            }
            t.addCell(cell("Total", bb));
            t.addCell(right(Money.format(inv.d("total")), bb));
            doc.add(t);
            doc.add(new Paragraph(" "));
            doc.add(new Paragraph("This is a computer generated invoice.", new Font(Font.HELVETICA, 8)));
            doc.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static PdfPCell cell(String s, Font f) {
        PdfPCell c = new PdfPCell(new Phrase(s, f));
        c.setPadding(6);
        return c;
    }

    private static PdfPCell right(String s, Font f) {
        PdfPCell c = cell(s, f);
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return c;
    }

    public Map<String, Object> summary(Row inv) {
        return inv.api();
    }
}
