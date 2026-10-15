package com.marketingpanel.engage;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.common.*;
import com.marketingpanel.inbox.DirectSendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Keyword chatbot for WhatsApp: first matching active rule (by priority) answers inside the 24-hour window.
 * Safeguards: never answers opted-out contacts or STOP words, at most 5 automatic replies per conversation per hour,
 * and the fallback rule fires at most once every 6 hours per conversation.
 */
@Service
public class ChatbotService {
    private static final Logger log = LoggerFactory.getLogger(ChatbotService.class);
    private static final Set<String> STOP_WORDS = Set.of("stop", "unsubscribe", "நிறுத்து");
    private final Db db;
    private final DirectSendService send;
    private final PlanService plans;
    private final AuditService audit;
    private final NotificationService notifications;
    private final ApplicationEventPublisher events;

    public ChatbotService(Db db, DirectSendService send, PlanService plans, AuditService audit, NotificationService notifications, ApplicationEventPublisher events) {
        this.db = db;
        this.send = send;
        this.plans = plans;
        this.audit = audit;
        this.notifications = notifications;
        this.events = events;
    }

    public record RuleRequest(String name, String matchType, List<String> keywords, String replyText, Long thenTagId, Boolean handoff, Integer priority, Boolean active) {}

    public List<Map<String, Object>> list(long ws) {
        return db.list("select * from chatbot_rules where workspace_id=:ws order by priority, id", "ws", ws);
    }

    private void validate(long ws, RuleRequest r) {
        List<FieldErr> errs = new ArrayList<>();
        String name = Norm.plain(r.name());
        if (name == null || name.length() < 2 || name.length() > 80) errs.add(new FieldErr("name", "bot.name", "Name must be 2 to 80 characters."));
        if (!List.of("EXACT", "CONTAINS", "FIRST_MESSAGE", "FALLBACK").contains(r.matchType())) errs.add(new FieldErr("matchType", "bot.type", "Choose when this rule applies."));
        else if (List.of("EXACT", "CONTAINS").contains(r.matchType())) {
            List<String> k = clean(r.keywords());
            if (k.isEmpty() || k.size() > 20) errs.add(new FieldErr("keywords", "bot.keywords", "Add 1 to 20 keywords."));
            else if (k.stream().anyMatch(STOP_WORDS::contains)) errs.add(new FieldErr("keywords", "bot.stop", "Opt-out words are reserved and cannot be used."));
        }
        String reply = Norm.plain(r.replyText());
        if (reply == null || reply.isBlank() || reply.length() > 1000) errs.add(new FieldErr("replyText", "bot.reply", "Write a reply of up to 1000 characters."));
        if (r.thenTagId() != null && !db.exists("select 1 from tags where id=:t and workspace_id=:ws", "t", r.thenTagId(), "ws", ws)) errs.add(new FieldErr("thenTagId", "bot.tag", "Choose one of your tags."));
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
    }

    static List<String> clean(List<String> in) {
        if (in == null) return List.of();
        return in.stream().map(s -> s == null ? "" : s.trim().toLowerCase()).filter(s -> !s.isEmpty() && s.length() <= 40).distinct().toList();
    }

    public Map<String, Object> create(long ws, RuleRequest r) {
        plans.requireFeature(ws, "chatbot");
        validate(ws, r);
        if (db.count("select count(*) from chatbot_rules where workspace_id=:ws", "ws", ws) >= 50) throw ApiException.planLimit("You can have up to 50 chatbot rules.");
        long id = db.insertInto("chatbot_rules", "workspace_id", ws, "name", Norm.plain(r.name()), "match_type", r.matchType(), "keywords", Json.write(clean(r.keywords())),
                "reply_text", Norm.plain(r.replyText()), "then_tag_id", r.thenTagId(), "handoff", Boolean.TRUE.equals(r.handoff()), "priority", r.priority() == null ? 100 : r.priority(),
                "active", r.active() == null || r.active());
        audit.log("CHATBOT_RULE_CREATED", "chatbot_rules", id);
        return db.one("select * from chatbot_rules where id=:id", "id", id).orElseThrow();
    }

    public Map<String, Object> update(long ws, long id, RuleRequest r) {
        db.row("select id from chatbot_rules where id=:id and workspace_id=:ws", "id", id, "ws", ws).orElseThrow(() -> ApiException.notFound("Rule"));
        validate(ws, r);
        db.update("update chatbot_rules set name=:n, match_type=:m, keywords=:k, reply_text=:r, then_tag_id=:t, handoff=:h, priority=:p, active=:a where id=:id",
                "n", Norm.plain(r.name()), "m", r.matchType(), "k", Json.write(clean(r.keywords())), "r", Norm.plain(r.replyText()), "t", r.thenTagId(),
                "h", Boolean.TRUE.equals(r.handoff()), "p", r.priority() == null ? 100 : r.priority(), "a", r.active() == null || r.active(), "id", id);
        return db.one("select * from chatbot_rules where id=:id", "id", id).orElseThrow();
    }

    public void delete(long ws, long id) {
        if (db.update("delete from chatbot_rules where id=:id and workspace_id=:ws", "id", id, "ws", ws) == 0) throw ApiException.notFound("Rule");
    }

    /** Dry run for the "try it" box on the rules page. */
    public Map<String, Object> test(long ws, String text, boolean first) {
        Row r = match(ws, text == null ? "" : text, first, true);
        return r == null ? Map.of("matched", false) : Map.of("matched", true, "ruleId", r.l("id"), "ruleName", r.s("name"), "reply", r.s("reply_text"));
    }

    Row match(long ws, String text, boolean firstMessage, boolean allowFallback) {
        String t = text.trim().toLowerCase();
        for (Row r : db.rows("select * from chatbot_rules where workspace_id=:ws and active=1 order by priority, id", "ws", ws)) {
            switch (r.s("match_type")) {
                case "EXACT" -> {
                    if (keywords(r).contains(t)) return r;
                }
                case "CONTAINS" -> {
                    if (keywords(r).stream().anyMatch(t::contains)) return r;
                }
                case "FIRST_MESSAGE" -> {
                    if (firstMessage) return r;
                }
                default -> {}
            }
        }
        if (allowFallback) {
            for (Row r : db.rows("select * from chatbot_rules where workspace_id=:ws and active=1 and match_type='FALLBACK' order by priority, id limit 1", "ws", ws)) return r;
        }
        return null;
    }

    private static List<String> keywords(Row r) {
        JsonNode n = r.s("keywords") == null ? null : Json.tree(r.s("keywords"));
        List<String> out = new ArrayList<>();
        if (n != null) n.forEach(x -> out.add(x.asText().toLowerCase()));
        return out;
    }

    @EventListener
    public void onInbound(Events.InboundMessage e) {
        try {
            handle(e);
        } catch (Exception ex) {
            log.warn("Chatbot failed for workspace {}: {}", e.workspaceId(), ex.toString());
        }
    }

    private void handle(Events.InboundMessage e) {
        long ws = e.workspaceId();
        String text = e.text() == null ? "" : e.text().trim();
        if (text.isEmpty() || STOP_WORDS.contains(text.toLowerCase())) return;
        if (!plans.hasFeature(ws, "chatbot")) return;
        if (!db.exists("select 1 from chatbot_rules where workspace_id=:ws and active=1 limit 1", "ws", ws)) return;
        Row conv = db.row("select * from conversations where workspace_id=:ws and contact_id=:c", "ws", ws, "c", e.contactId()).orElse(null);
        if (conv == null || conv.lo("assigned_to") != null) return; // a person owns this conversation
        long inbound = db.count("select count(*) from messages where conversation_id=:c and direction='IN'", "c", conv.l("id"));
        long botLastHour = db.count("select count(*) from messages where conversation_id=:c and direction='OUT' and sent_by is null and type='TEXT' and created_at > :t",
                "c", conv.l("id"), "t", Time.now().minusHours(1));
        if (botLastHour >= 5) return;
        boolean fallbackOk = db.count("select count(*) from chatbot_rules r where r.workspace_id=:ws and r.match_type='FALLBACK' and exists (select 1 from messages m where m.conversation_id=:c and m.direction='OUT' and m.sent_by is null and m.body=r.reply_text and m.created_at > :t)",
                "ws", ws, "c", conv.l("id"), "t", Time.now().minusHours(6)) == 0;
        Row rule = match(ws, text, inbound <= 1, fallbackOk);
        if (rule == null) return;
        String wamid = send.sendBotText(ws, conv.l("id"), rule.s("reply_text"));
        if (wamid == null) return;
        db.update("update chatbot_rules set hit_count=hit_count+1 where id=:id", "id", rule.l("id"));
        if (rule.lo("then_tag_id") != null) {
            int n = db.update("insert ignore into contact_tags (contact_id, tag_id) values (:c,:t)", "c", e.contactId(), "t", rule.l("then_tag_id"));
            if (n > 0) events.publishEvent(new Events.TagAdded(ws, e.contactId(), rule.l("then_tag_id")));
        }
        if (rule.b("handoff")) notifications.notifyOwners(ws, "BOT_HANDOFF", "A customer needs a person", "The chatbot rule \"" + rule.s("name") + "\" asked for a human to continue this chat.", "/inbox");
    }
}
