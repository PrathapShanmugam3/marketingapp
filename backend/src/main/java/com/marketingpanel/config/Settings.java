package com.marketingpanel.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/** Central, typed view of the `app.*` configuration. */
@Component
public class Settings {
    public final String role;
    public final String publicBaseUrl;
    public final String frontendUrl;
    public final String trackDomain;
    public final String jwtSecret;
    public final String encryptionKey;
    public final boolean cookieSecure;
    public final String ipHashSalt;
    public final List<String> corsOrigins;
    public final String internalToken;
    public final boolean otpDevEcho;
    public final List<String> adminIpAllowlist;
    public final String captchaSecret;
    public final boolean whatsappMock;
    public final String graphUrl;
    public final int waRatePerSecond;
    public final String metaAppId;
    public final String metaAppSecret;
    public final String metaVerifyToken;
    public final boolean rupiyaxMock;
    public final String rupiyaxBaseUrl;
    public final String rupiyaxApiKey;
    public final String rupiyaxApiSecret;
    public final String rupiyaxWebhookSecret;
    public final String aiApiKey;
    public final String aiModel;
    public final String storageDir;
    public final String companyState;
    public final String companyName;
    public final String companyGstin;
    public final int trialDays;
    public final String trialPlan;
    public final int offerMinMarginPercent;
    public final BigDecimal tdsPercent;
    public final BigDecimal payoutMin;
    public final BigDecimal payoutMax;
    public final int payoutMaxPerDay;
    public final int autoApproveRisk;
    public final BigDecimal autoApproveAmount;
    public final BigDecimal batchSecondApproval;
    public final boolean skipDns;
    public final String cnameTarget;
    public final String serverIp;
    public final boolean jobsEnabled;

    public Settings(
            @Value("${app.role}") String role,
            @Value("${app.public-base-url}") String publicBaseUrl,
            @Value("${app.frontend-url}") String frontendUrl,
            @Value("${app.track-domain}") String trackDomain,
            @Value("${app.security.jwt-secret}") String jwtSecret,
            @Value("${app.security.encryption-key}") String encryptionKey,
            @Value("${app.security.cookie-secure}") boolean cookieSecure,
            @Value("${app.security.ip-hash-salt}") String ipHashSalt,
            @Value("${app.security.cors-origins}") String corsOrigins,
            @Value("${app.security.internal-token}") String internalToken,
            @Value("${app.otp.dev-echo}") boolean otpDevEcho,
            @Value("${app.admin.ip-allowlist:}") String adminIpAllowlist,
            @Value("${app.captcha.secret:}") String captchaSecret,
            @Value("${app.whatsapp.mock}") boolean whatsappMock,
            @Value("${app.whatsapp.graph-url}") String graphUrl,
            @Value("${app.whatsapp.rate-per-second}") int waRatePerSecond,
            @Value("${app.meta.app-id:}") String metaAppId,
            @Value("${app.meta.app-secret:}") String metaAppSecret,
            @Value("${app.meta.verify-token}") String metaVerifyToken,
            @Value("${app.rupiyax.mock}") boolean rupiyaxMock,
            @Value("${app.rupiyax.base-url}") String rupiyaxBaseUrl,
            @Value("${app.rupiyax.api-key:}") String rupiyaxApiKey,
            @Value("${app.rupiyax.api-secret:}") String rupiyaxApiSecret,
            @Value("${app.rupiyax.webhook-secret}") String rupiyaxWebhookSecret,
            @Value("${app.ai.api-key:}") String aiApiKey,
            @Value("${app.ai.model}") String aiModel,
            @Value("${app.storage.dir}") String storageDir,
            @Value("${app.company.state}") String companyState,
            @Value("${app.company.name}") String companyName,
            @Value("${app.company.gstin:}") String companyGstin,
            @Value("${app.trial.days}") int trialDays,
            @Value("${app.trial.plan}") String trialPlan,
            @Value("${app.offers.min-margin-percent}") int offerMinMarginPercent,
            @Value("${app.payout.tds-percent}") BigDecimal tdsPercent,
            @Value("${app.payout.min-amount}") BigDecimal payoutMin,
            @Value("${app.payout.max-amount}") BigDecimal payoutMax,
            @Value("${app.payout.max-per-day}") int payoutMaxPerDay,
            @Value("${app.payout.auto-approve-risk}") int autoApproveRisk,
            @Value("${app.payout.auto-approve-amount}") BigDecimal autoApproveAmount,
            @Value("${app.payout.batch-second-approval}") BigDecimal batchSecondApproval,
            @Value("${app.domains.skip-dns}") boolean skipDns,
            @Value("${app.domains.cname-target}") String cnameTarget,
            @Value("${app.domains.server-ip}") String serverIp,
            @Value("${app.jobs.enabled}") boolean jobsEnabled) {
        this.role = role;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
        this.frontendUrl = frontendUrl.replaceAll("/+$", "");
        this.trackDomain = trackDomain;
        this.jwtSecret = jwtSecret;
        this.encryptionKey = encryptionKey;
        this.cookieSecure = cookieSecure;
        this.ipHashSalt = ipHashSalt;
        this.corsOrigins = split(corsOrigins);
        this.internalToken = internalToken;
        this.otpDevEcho = otpDevEcho;
        this.adminIpAllowlist = split(adminIpAllowlist);
        this.captchaSecret = captchaSecret;
        this.whatsappMock = whatsappMock;
        this.graphUrl = graphUrl;
        this.waRatePerSecond = waRatePerSecond;
        this.metaAppId = metaAppId;
        this.metaAppSecret = metaAppSecret;
        this.metaVerifyToken = metaVerifyToken;
        this.rupiyaxMock = rupiyaxMock;
        this.rupiyaxBaseUrl = rupiyaxBaseUrl;
        this.rupiyaxApiKey = rupiyaxApiKey;
        this.rupiyaxApiSecret = rupiyaxApiSecret;
        this.rupiyaxWebhookSecret = rupiyaxWebhookSecret;
        this.aiApiKey = aiApiKey;
        this.aiModel = aiModel;
        this.storageDir = storageDir;
        this.companyState = companyState;
        this.companyName = companyName;
        this.companyGstin = companyGstin;
        this.trialDays = trialDays;
        this.trialPlan = trialPlan;
        this.offerMinMarginPercent = offerMinMarginPercent;
        this.tdsPercent = tdsPercent;
        this.payoutMin = payoutMin;
        this.payoutMax = payoutMax;
        this.payoutMaxPerDay = payoutMaxPerDay;
        this.autoApproveRisk = autoApproveRisk;
        this.autoApproveAmount = autoApproveAmount;
        this.batchSecondApproval = batchSecondApproval;
        this.skipDns = skipDns;
        this.cnameTarget = cnameTarget;
        this.serverIp = serverIp;
        this.jobsEnabled = jobsEnabled;
    }

    private static List<String> split(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    public boolean runsWorkers() {
        return jobsEnabled && !"api".equalsIgnoreCase(role);
    }
}
