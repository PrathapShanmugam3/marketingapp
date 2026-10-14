package com.marketingpanel.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.common.ApiException;
import com.marketingpanel.common.FieldErr;
import com.marketingpanel.common.Row;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Maps template variables {{n}} to contact fields or fixed text, with fallback text for optional fields. */
public final class VariableResolver {
    static final Set<String> FIELDS = Set.of("fullName", "firstName", "phone", "email", "city");
    static final Set<String> OPTIONAL = Set.of("email", "city");

    private VariableResolver() {}

    public static void validate(JsonNode mapping, int variableCount, List<FieldErr> errs) {
        for (int i = 1; i <= variableCount; i++) {
            JsonNode m = mapping == null ? null : mapping.get(String.valueOf(i));
            if (m == null || m.isNull()) {
                errs.add(new FieldErr("variableMapping", "campaign.var.missing", "Map every variable or give fallback text."));
                return;
            }
            String type = m.path("type").asText("");
            String value = m.path("value").asText("").trim();
            String fb = m.path("fallback").asText("").trim();
            if ("text".equals(type)) {
                if (value.isEmpty() || value.length() > 100) {
                    errs.add(new FieldErr("variableMapping", "campaign.var.text", "Fixed text can be 1 to 100 characters."));
                    return;
                }
            } else if ("field".equals(type)) {
                if (!FIELDS.contains(value)) {
                    errs.add(new FieldErr("variableMapping", "campaign.var.field", "Choose a contact field."));
                    return;
                }
                if (OPTIONAL.contains(value) && fb.isEmpty()) {
                    errs.add(new FieldErr("variableMapping", "campaign.var.fallback", "Map every variable or give fallback text."));
                    return;
                }
                if (fb.length() > 100) {
                    errs.add(new FieldErr("variableMapping", "campaign.var.text", "Fallback text can be up to 100 characters."));
                    return;
                }
            } else {
                errs.add(new FieldErr("variableMapping", "campaign.var.missing", "Map every variable or give fallback text."));
                return;
            }
        }
    }

    /** Resolved body parameters for one contact. A blank value after the fallback is a send-time failure. */
    public static List<String> resolve(JsonNode mapping, int variableCount, Row contact) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= variableCount; i++) {
            JsonNode m = mapping.get(String.valueOf(i));
            String v;
            if ("text".equals(m.path("type").asText())) {
                v = m.path("value").asText("");
            } else {
                v = switch (m.path("value").asText()) {
                    case "fullName" -> contact.s("full_name");
                    case "firstName" -> {
                        String n = contact.s("full_name");
                        yield n == null ? null : n.trim().split("\\s+")[0];
                    }
                    case "phone" -> contact.s("phone");
                    case "email" -> contact.s("email");
                    case "city" -> contact.s("city");
                    default -> null;
                };
                if (v == null || v.isBlank()) v = m.path("fallback").asText("");
            }
            if (v == null || v.isBlank()) throw new IllegalArgumentException("Variable " + i + " has no value for this contact");
            out.add(v.replace('\n', ' ').replace('\t', ' '));
        }
        return out;
    }

    public static ApiException bad(String msg) {
        return ApiException.invalid("variableMapping", "campaign.var.missing", msg);
    }
}
