package com.marketingpanel.common;

import com.fasterxml.jackson.databind.JsonNode;

/** Tiny server-side HTML helpers for public pages (forms, branded notices). Everything is escaped. */
public final class Html {
    private Html() {}

    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    public static String shell(String title, String body) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<title>" + esc(title) + "</title><style>" + CSS + "</style></head><body><main>" + body + "</main></body></html>";
    }

    public static String ended(String message) {
        return shell("Link ended", "<div class=\"card center\"><h1>This link has ended</h1><p>" + esc(message) + "</p></div>");
    }

    private static final String CSS = ":root{--bg:#f6f8fb;--fg:#0b1a33;--mut:#5b6b82;--acc:#12a388;--bd:#dde3ec}" +
            "@media(prefers-color-scheme:dark){:root{--bg:#0b1220;--fg:#e8eef8;--mut:#97a6bd;--bd:#26324a}}" +
            "*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}" +
            "main{max-width:520px;margin:0 auto;padding:24px 16px}.card{background:color-mix(in srgb,var(--bg) 70%,#fff 30%);border:1px solid var(--bd);border-radius:16px;padding:24px}" +
            ".center{text-align:center}h1{font-size:1.4rem;margin:0 0 8px}p{color:var(--mut)}label{display:block;font-weight:600;margin:14px 0 4px;font-size:.9rem}" +
            "input,select{width:100%;padding:12px;border:1px solid var(--bd);border-radius:10px;background:transparent;color:var(--fg);font:inherit}" +
            "button{width:100%;margin-top:18px;padding:13px;border:0;border-radius:10px;background:var(--acc);color:#fff;font:inherit;font-weight:700;cursor:pointer}" +
            "button[disabled]{opacity:.6}.err{color:#d92d20;font-size:.85rem;margin-top:4px}.ok{color:var(--acc);font-weight:600}.hp{position:absolute;left:-9999px}" +
            ".consent{display:flex;gap:10px;align-items:flex-start;margin-top:16px;font-size:.9rem}.consent input{width:auto;margin-top:4px}";

    public static String formPage(Row form, boolean devCaptcha) {
        StringBuilder f = new StringBuilder();
        for (JsonNode fld : Json.tree(form.s("fields_json"))) {
            String key = fld.get("key").asText();
            String type = fld.path("type").asText("text");
            String label = fld.path("label").asText(key);
            boolean req = fld.path("required").asBoolean();
            f.append("<label for=\"f_").append(esc(key)).append("\">").append(esc(label)).append(req ? " *" : "").append("</label>");
            if ("select".equals(type)) {
                f.append("<select id=\"f_").append(esc(key)).append("\" name=\"").append(esc(key)).append("\"><option value=\"\">Choose…</option>");
                for (JsonNode o : fld.path("options")) f.append("<option>").append(esc(o.asText())).append("</option>");
                f.append("</select>");
            } else {
                String t = "number".equals(type) ? "number" : "date".equals(type) ? "date" : "phone".equals(key) ? "tel" : "text";
                f.append("<input id=\"f_").append(esc(key)).append("\" name=\"").append(esc(key)).append("\" type=\"").append(t).append("\"")
                        .append("phone".equals(key) ? " inputmode=\"tel\" autocomplete=\"tel\"" : "").append(req ? " required" : "").append(">");
            }
            f.append("<div class=\"err\" data-err=\"").append(esc(key)).append("\"></div>");
        }
        String slug = form.s("slug");
        String body = "<div class=\"card\"><h1>" + esc(form.s("title")) + "</h1>" +
                (form.s("description") == null ? "" : "<p>" + esc(form.s("description")) + "</p>") +
                "<form id=\"lf\" novalidate>" + f +
                "<input class=\"hp\" tabindex=\"-1\" autocomplete=\"off\" name=\"website\" aria-hidden=\"true\">" +
                "<label class=\"consent\"><input type=\"checkbox\" id=\"consent\"><span>" + esc(form.s("consent_text")) + "</span></label>" +
                "<div class=\"err\" data-err=\"consent\"></div><button id=\"go\" type=\"submit\">Submit</button><p id=\"msg\"></p></form></div>" +
                "<script>(function(){var f=document.getElementById('lf'),b=document.getElementById('go');" +
                "f.addEventListener('submit',function(e){e.preventDefault();b.disabled=true;" +
                "document.querySelectorAll('[data-err]').forEach(function(x){x.textContent=''});" +
                "var d={fields:{},consent:document.getElementById('consent').checked,website:f.website.value,captchaToken:'" + (devCaptcha ? "dev" : "") + "'};" +
                "new FormData(f).forEach(function(v,k){if(k!=='website')d.fields[k]=v});" +
                "fetch('/f/" + esc(slug) + "',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(d)}).then(function(r){return r.json()}).then(function(j){" +
                "if(j.ok){f.innerHTML='<p class=\"ok\">'+j.message+'</p>'}else{(j.fieldErrors||[]).forEach(function(x){var el=document.querySelector('[data-err=\"'+x.field+'\"]');if(el)el.textContent=x.message});" +
                "if(!(j.fieldErrors||[]).length)document.getElementById('msg').textContent=j.message;b.disabled=false}}).catch(function(){document.getElementById('msg').textContent='Network error. Try again.';b.disabled=false})});})();</script>";
        return shell(form.s("title"), body);
    }
}
