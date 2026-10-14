package com.marketingpanel.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.*;

/** P20 AI caption assistant. Output is always editable text; it is never sent automatically. */
@Service
public class AiService {
    private static final Logger log = LoggerFactory.getLogger(AiService.class);
    private final Db db;
    private final Settings settings;
    private final PlanService plans;
    private final RestClient http = RestClient.create();

    public AiService(Db db, Settings settings, PlanService plans) {
        this.db = db;
        this.settings = settings;
        this.plans = plans;
    }

    public record GenerateRequest(String type, String topic, String language, String tone) {}

    public int limit(long ws) {
        return switch (plans.plan(ws).s("code")) {
            case "AGENCY" -> 1000;
            case "GROWTH" -> 200;
            default -> 0;
        };
    }

    public long used(long ws) {
        LocalDate first = LocalDate.now(Time.IST).withDayOfMonth(1);
        return db.count("select count(*) from ai_generations where workspace_id=:ws and created_at >= :t", "ws", ws, "t", Time.startOfIstDayUtc(first));
    }

    public Map<String, Object> usage(long ws) {
        return Map.of("used", used(ws), "limit", limit(ws), "enabled", plans.hasFeature(ws, "ai"));
    }

    public Map<String, Object> generate(long ws, long userId, GenerateRequest r) {
        plans.requireFeature(ws, "ai");
        List<FieldErr> errs = new ArrayList<>();
        String topic = Norm.plain(r.topic());
        if (topic == null || topic.length() < 5 || topic.length() > 300) errs.add(new FieldErr("topic", "ai.topic", "Describe the offer in a few words."));
        else if (Norm.hasControlChars(topic)) errs.add(new FieldErr("topic", "vr.text.unsupported", "Remove unsupported characters."));
        String type = r.type() == null ? "CAPTION" : r.type();
        if (!List.of("CAPTION", "OFFER_MESSAGE", "REPLY", "HASHTAGS").contains(type)) errs.add(new FieldErr("type", "ai.type", "Choose what to generate."));
        if (!List.of("en", "ta").contains(r.language())) errs.add(new FieldErr("language", "ai.language", "Choose a language."));
        if (!List.of("Friendly", "Festive", "Professional").contains(r.tone())) errs.add(new FieldErr("tone", "ai.tone", "Choose a tone."));
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        long used = used(ws);
        if (used >= limit(ws)) throw ApiException.planLimit("You have used all " + limit(ws) + " AI generations for this month.");
        String out = null;
        int tokens = 0;
        if (!settings.aiApiKey.isBlank()) {
            try {
                JsonNode n = http.post().uri("https://api.anthropic.com/v1/messages").header("x-api-key", settings.aiApiKey)
                        .header("anthropic-version", "2023-06-01").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .body(Map.of("model", settings.aiModel, "max_tokens", 700, "system", system(type, r.language(), r.tone()),
                                "messages", List.of(Map.of("role", "user", "content", topic)))).retrieve().body(JsonNode.class);
                out = n.path("content").path(0).path("text").asText(null);
                tokens = n.path("usage").path("input_tokens").asInt() + n.path("usage").path("output_tokens").asInt();
            } catch (Exception e) {
                log.warn("AI provider failed, using the built-in generator: {}", e.toString());
            }
        }
        if (out == null || out.isBlank()) {
            out = fallback(type, topic, r.language(), r.tone());
            tokens = out.length() / 4;
        }
        out = Norm.stripTags(out);
        long id = db.insertInto("ai_generations", "workspace_id", ws, "user_id", userId, "type", type, "language", r.language(),
                "prompt", topic.length() > 1000 ? topic.substring(0, 1000) : topic, "output", out, "tokens_used", tokens);
        return Map.of("id", id, "output", out, "used", used + 1, "limit", limit(ws));
    }

    private String system(String type, String lang, String tone) {
        String l = "ta".equals(lang) ? "Tamil" : "English";
        String what = switch (type) {
            case "OFFER_MESSAGE" -> "a short WhatsApp marketing message (under 300 characters) for a small Indian business, with {{1}} as the customer-name placeholder";
            case "REPLY" -> "a polite reply to a customer enquiry";
            case "HASHTAGS" -> "10 relevant hashtags";
            default -> "an Instagram/Facebook caption with a few emojis and 3-5 hashtags";
        };
        return "You write marketing copy for small businesses in India. Write " + what + " in " + l + ". Tone: " + tone
                + ". Output only the text. Do not promise rewards for installs and do not invent prices.";
    }

    static String fallback(String type, String topic, String lang, String tone) {
        boolean ta = "ta".equals(lang);
        String t = topic.trim();
        String[] words = t.toLowerCase().replaceAll("[^\\p{L}\\p{N} ]", " ").split("\\s+");
        StringBuilder tags = new StringBuilder();
        int n = 0;
        for (String w : words) {
            if (w.length() >= 4 && n < 5) {
                tags.append('#').append(w).append(' ');
                n++;
            }
        }
        tags.append("#smallbusiness #shoplocal");
        String emoji = switch (tone) {
            case "Festive" -> "🪔✨";
            case "Professional" -> "✅";
            default -> "😊";
        };
        return switch (type) {
            case "HASHTAGS" -> tags.toString();
            case "OFFER_MESSAGE" -> ta
                    ? "வணக்கம் {{1}}! " + t + " " + emoji + " மேலும் அறிய பதில் அனுப்புங்கள்."
                    : "Hi {{1}}! " + t + " " + emoji + " Reply to know more.";
            case "REPLY" -> ta
                    ? "வணக்கம்! உங்கள் கேள்விக்கு நன்றி. " + t + " மேலும் உதவி தேவைப்பட்டால் தெரிவிக்கவும்."
                    : "Hello! Thank you for reaching out. " + t + " Let us know if you need anything else.";
            default -> switch (tone) {
                case "Festive" -> ta
                        ? "🪔 " + t + "\n\nஇந்த பண்டிகை காலத்தில் எங்களுடன் கொண்டாடுங்கள்! ✨\n\n" + tags
                        : "🪔 " + t + "\n\nCelebrate this festive season with us! ✨ Visit today and make it special.\n\n" + tags;
                case "Professional" -> ta
                        ? t + "\n\nதரமான சேவை, நம்பகமான அனுபவம். இன்றே தொடர்பு கொள்ளுங்கள்.\n\n" + tags
                        : t + "\n\nQuality service and a trusted experience. Get in touch with us today.\n\n" + tags;
                default -> ta
                        ? "✨ " + t + "\n\nஇன்றே வந்து பாருங்கள்! உங்கள் வருகைக்காக காத்திருக்கிறோம் 😊\n\n" + tags
                        : "✨ " + t + "\n\nVisit us today, we can't wait to see you! 😊\n\n" + tags;
            };
        };
    }

    public List<Map<String, Object>> history(long ws) {
        return db.list("select id, type, language, prompt, output, created_at from ai_generations where workspace_id=:ws order by id desc limit 20", "ws", ws);
    }
}
