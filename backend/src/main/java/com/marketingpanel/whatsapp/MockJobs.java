package com.marketingpanel.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketingpanel.campaign.CampaignStats;
import com.marketingpanel.common.*;
import com.marketingpanel.inbox.DirectSendService;
import com.marketingpanel.jobs.JobHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

import java.util.*;

/** Small job handlers: sandbox delivery simulation and "send this template to this contact". */
@Configuration
public class MockJobs {
    @Bean
    JobHandler waMockStatus(WebhookProcessor processor, Db db, CampaignStats stats) {
        return new JobHandler() {
            @Override
            public String type() {
                return "WA_MOCK_STATUS";
            }

            @Override
            public void handle(JsonNode p) {
                Set<Long> touched = new LinkedHashSet<>();
                processor.applyStatus(p.get("wamid").asText(), p.get("status").asText(), Time.now(), null, null, touched);
                for (long cid : touched) {
                    db.row("select workspace_id, status from campaigns where id=:id", "id", cid)
                            .ifPresent(c -> stats.push(c.l("workspace_id"), cid, c.s("status"), stats.refresh(cid)));
                }
            }
        };
    }

    @Bean
    JobHandler templateDirect(Db db, DirectSendService send) {
        return new JobHandler() {
            @Override
            public String type() {
                return "TEMPLATE_DIRECT";
            }

            @Override
            public void handle(JsonNode p) {
                long ws = p.get("workspaceId").asLong(), contactId = p.get("contactId").asLong(), templateId = p.get("templateId").asLong();
                Row tpl = db.row("select variable_count from wa_templates where id=:id", "id", templateId).orElse(null);
                Row contact = db.row("select full_name from contacts where id=:id", "id", contactId).orElse(null);
                if (tpl == null || contact == null) return;
                List<String> params = new ArrayList<>();
                if (p.has("variables")) p.get("variables").forEach(n -> params.add(n.asText()));
                String first = contact.s("full_name").trim().split("\\s+")[0];
                for (int i = params.size(); i < tpl.i("variable_count"); i++) params.add(i == 0 ? first : "-");
                try {
                    send.sendTemplate(ws, contactId, templateId, params, null, p.has("urlSuffix") ? List.of(p.get("urlSuffix").asText()) : null, null, p.path("enforceCap").asBoolean(false));
                } catch (ApiException e) {
                    // business rule failures (not connected, insufficient balance) are not retryable
                }
            }
        };
    }
}
