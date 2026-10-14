package com.marketingpanel;

import com.marketingpanel.auth.AuthUser;
import com.marketingpanel.auth.JwtService;
import com.marketingpanel.common.Hashing;
import com.marketingpanel.payment.MockPaymentGateway;
import com.marketingpanel.payout.EarningsLedger;
import com.marketingpanel.payout.LedgerGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyTest extends BaseIT {
    @Autowired MockPaymentGateway gateway;
    @Autowired JwtService jwt;
    @Autowired EarningsLedger earnings;
    @Autowired LedgerGuard guard;

    private String adminToken(String role) {
        long n = System.nanoTime() % 1_000_000_000L;
        long id = db.insertInto("users", "full_name", "Admin " + role, "email", "admin" + n + "@mp.test", "phone", "+9188" + String.format("%08d", n % 100_000_000), "password_hash", "x".repeat(60), "admin_role", role);
        return jwt.issue(new AuthUser(id, null, null, role, "BUSINESS", null));
    }

    private void pay(String orderRef, boolean ok) throws Exception {
        var r = mvc.perform(MockMvcRequestBuilders.post("/dev/pay/" + orderRef + "?result=" + (ok ? "success" : "failed"))).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(200);
        runJobs();
    }

    @Test
    void walletTopUpWithGstInvoiceAndSecurityChecks() throws Exception {
        Api.Session s = api.signup();
        assertThat(api.post("/api/v1/billing/topups", s.token(), Map.of("amount", 550)).status()).isEqualTo(400); // not a multiple of 100
        assertThat(api.post("/api/v1/billing/topups", s.token(), Map.of("amount", 400)).status()).isEqualTo(400);
        assertThat(api.post("/api/v1/billing/topups", s.token(), Map.of("amount", 500, "gstin", "29ABCDE1234F1Z5")).status()).isEqualTo(400); // GSTIN state does not match Tamil Nadu

        var order = api.post("/api/v1/billing/topups", s.token(), Map.of("amount", 500));
        assertThat(order.status()).isEqualTo(200);
        assertThat(new BigDecimal(order.text("/total"))).isEqualByComparingTo("590"); // 500 + 18% GST
        String ref = order.text("/orderRef");
        long paymentId = order.body().get("paymentId").asLong();
        // the browser callback alone never credits the wallet
        assertThat(api.get("/api/v1/billing/payments/" + paymentId, s.token()).text("/status")).isEqualTo("PENDING");
        assertThat(wallet.balance(s.workspaceId())).isEqualByComparingTo("0");

        pay(ref, true);
        assertThat(wallet.balance(s.workspaceId())).isEqualByComparingTo("500");
        var billing = api.get("/api/v1/billing", s.token());
        var inv = billing.body().at("/invoices/0");
        assertThat(inv.get("invoiceNumber").asText()).matches("MP/\\d{4}-\\d{2}/\\d{4}");
        assertThat(new BigDecimal(inv.get("cgst").asText())).isEqualByComparingTo("45");
        assertThat(new BigDecimal(inv.get("sgst").asText())).isEqualByComparingTo("45");
        assertThat(new BigDecimal(inv.get("igst").asText())).isEqualByComparingTo("0");
        var pdf = mvc.perform(MockMvcRequestBuilders.get("/api/v1/billing/invoices/" + inv.get("id").asLong() + "/pdf").header("Authorization", "Bearer " + s.token())).andReturn().getResponse();
        assertThat(new String(pdf.getContentAsByteArray(), 0, 4)).isEqualTo("%PDF");

        // a replayed webhook credits nothing twice
        pay(ref, true);
        assertThat(wallet.balance(s.workspaceId())).isEqualByComparingTo("500");
        assertThat(wallet.ledgerDifference(s.workspaceId())).isEqualByComparingTo("0");

        // inter-state buyer pays IGST
        Api.Session k = api.signup();
        db.update("update workspaces set state='Karnataka' where id=:id", "id", k.workspaceId());
        var o2 = api.post("/api/v1/billing/topups", k.token(), Map.of("amount", 1000));
        pay(o2.text("/orderRef"), true);
        var i2 = api.get("/api/v1/billing", k.token()).body().at("/invoices/0");
        assertThat(new BigDecimal(i2.get("igst").asText())).isEqualByComparingTo("180");
        assertThat(new BigDecimal(i2.get("cgst").asText())).isEqualByComparingTo("0");

        // amount mismatch: payment is held for finance, wallet untouched
        Api.Session m = api.signup();
        var o3 = api.post("/api/v1/billing/topups", m.token(), Map.of("amount", 500));
        mvc.perform(MockMvcRequestBuilders.get("/dev/pay/" + o3.text("/orderRef")));
        gateway.forceAmount(o3.text("/orderRef"), new BigDecimal("1.00"));
        pay(o3.text("/orderRef"), true);
        assertThat(wallet.balance(m.workspaceId())).isEqualByComparingTo("0");
        assertThat(db.scalar("select failure_reason from payments where order_ref=:r", String.class, "r", o3.text("/orderRef"))).startsWith("MISMATCH");
        assertThat(db.exists("select 1 from audit_logs where action='PAYMENT_MISMATCH'")).isTrue();

        // a forged webhook is rejected
        var bad = mvc.perform(MockMvcRequestBuilders.post("/webhooks/rupiyax").header("X-Signature", "deadbeef").contentType("application/json").content("{\"id\":\"e1\",\"type\":\"payment.success\",\"data\":{\"order_ref\":\"" + ref + "\"}}")).andReturn().getResponse();
        assertThat(bad.getStatus()).isEqualTo(403);
        String body = "{\"id\":\"evt_same\",\"type\":\"payment.failed\",\"data\":{\"order_ref\":\"zzz\"}}";
        for (int i = 0; i < 2; i++)
            assertThat(mvc.perform(MockMvcRequestBuilders.post("/webhooks/rupiyax").header("X-Signature", gateway.sign(body)).contentType("application/json").content(body)).andReturn().getResponse().getStatus()).isEqualTo(200);
        assertThat(db.count("select count(*) from webhook_events where event_id='evt_same'", "x", 1)).isEqualTo(1);
    }

    @Test
    void subscribeUpgradeAndDowngradeGuard() throws Exception {
        Api.Session s = api.signup();
        var o = api.post("/api/v1/billing/subscribe", s.token(), Map.of("planCode", "STARTER", "cycle", "MONTHLY"));
        assertThat(o.status()).isEqualTo(200); // trial is on GROWTH, STARTER is a downgrade but usage fits
        pay(o.text("/orderRef"), true);
        var w = api.get("/api/v1/workspace", s.token());
        assertThat(w.text("/status")).isEqualTo("ACTIVE");
        assertThat(w.text("/planCode")).isEqualTo("STARTER");
        assertThat(api.post("/api/v1/auth/refresh", null, null).status()).isEqualTo(401);
        // STARTER has no social, forms or AI
        assertThat(api.post("/api/v1/ai/generate", s.token(), Map.of("topic", "Diwali sale on sarees", "language", "en", "tone", "Festive")).status()).isEqualTo(403);
        // 3 contacts, then pay for GROWTH and downgrade check on the way back is fine; a user count above the limit blocks it
        var up = api.post("/api/v1/billing/subscribe", s.token(), Map.of("planCode", "GROWTH", "cycle", "YEARLY"));
        assertThat(new BigDecimal(up.text("/taxable"))).isEqualByComparingTo("19990");
        pay(up.text("/orderRef"), true);
        assertThat(api.get("/api/v1/workspace", s.token()).text("/planCode")).isEqualTo("GROWTH");
        api.post("/api/v1/team", s.token(), Map.of("email", "staff1@example.com", "role", "STAFF"));
        api.post("/api/v1/team", s.token(), Map.of("email", "staff2@example.com", "role", "MANAGER"));
        var down = api.post("/api/v1/billing/subscribe", s.token(), Map.of("planCode", "STARTER", "cycle", "MONTHLY"));
        assertThat(down.status()).isEqualTo(409);
        assertThat(down.text("/message")).contains("team members");
        assertThat(api.post("/api/v1/team", s.token(), Map.of("email", "staff3@example.com", "role", "STAFF")).status()).isEqualTo(403); // growth allows 3 users
    }

    @Test
    void offerPostbackPromoterEarningsAndPayout() throws Exception {
        // advertiser
        Api.Session adv = api.signup();
        topUp(adv.workspaceId(), "10000");
        var app = api.post("/api/v1/apps", adv.token(), Map.of("name", "Shop App", "androidPackage", "in.shop.app",
                "playStoreUrl", "https://play.google.com/store/apps/details?id=in.shop.app", "webFallbackUrl", "https://shop.in/app"));
        long appId = app.body().get("id").asLong();
        var tooMuch = api.post("/api/v1/offers", adv.token(), offerBody(appId, 100, 81));
        assertThat(tooMuch.status()).isEqualTo(400); // payout above price x (1 - 20% margin)
        var offer = api.post("/api/v1/offers", adv.token(), offerBody(appId, 100, 80));
        assertThat(offer.status()).isEqualTo(200);
        long offerId = offer.body().get("id").asLong();
        String secret = offer.text("/postbackSecret");
        assertThat(secret).hasSize(64);
        assertThat(api.get("/api/v1/offers/" + offerId, adv.token()).body().has("postbackSecret")).isFalse(); // shown once
        assertThat(api.patch("/api/v1/offers/" + offerId + "/status", adv.token(), Map.of("status", "ACTIVE")).status()).isEqualTo(409); // not tested yet
        assertThat(api.post("/api/v1/offers/" + offerId + "/test", adv.token(), null).body().get("passed").asBoolean()).isTrue();
        assertThat(api.patch("/api/v1/offers/" + offerId + "/status", adv.token(), Map.of("status", "ACTIVE")).text("/status")).isEqualTo("ACTIVE");
        assertThat(db.count("select count(*) from tracking_clicks where offer_id=:o", "o", offerId)).isEqualTo(0); // test click cleaned up

        // promoter: sign up with OTP, KYC, payout method
        Api.Session pr = promoterSignup("Ravi Kumar");
        var wall = api.get("/api/v1/earn/offers", pr.token());
        assertThat(wall.body().size()).isEqualTo(1);
        String link = wall.body().at("/0/link").asText();
        String code = link.substring(link.lastIndexOf('/') + 1);
        assertThat(api.post("/api/v1/earn/kyc", pr.token(), Map.of("pan", "BADPAN", "nameOnPan", "Ravi Kumar", "dateOfBirth", "1995-05-05", "acceptTerms", true)).status()).isEqualTo(400);
        assertThat(api.post("/api/v1/earn/kyc", pr.token(), Map.of("pan", "ABCPE1234F", "nameOnPan", "Ravi Kumar", "dateOfBirth", "2015-05-05", "acceptTerms", true)).status()).isEqualTo(400); // under 18
        assertThat(api.post("/api/v1/earn/kyc", pr.token(), Map.of("pan", "ABCPE1234Z", "nameOnPan", "Ravi Kumar", "dateOfBirth", "1995-05-05", "acceptTerms", true)).status()).isEqualTo(400); // name mismatch
        var kyc = api.post("/api/v1/earn/kyc", pr.token(), Map.of("pan", "ABCPE1234F", "nameOnPan", "Ravi Kumar", "dateOfBirth", "1995-05-05", "acceptTerms", true));
        assertThat(kyc.text("/kycStatus")).isEqualTo("VERIFIED");
        assertThat(kyc.text("/panLast4")).isEqualTo("234F");
        assertThat(kyc.body().toString()).doesNotContain("ABCPE1234F");
        Api.Session pr2 = promoterSignup("Other Person");
        assertThat(api.post("/api/v1/earn/kyc", pr2.token(), Map.of("pan", "ABCPE1234F", "nameOnPan", "Other Person", "dateOfBirth", "1990-01-01", "acceptTerms", true)).status()).isEqualTo(409); // one PAN, one account

        // click -> postback
        var click = mvc.perform(MockMvcRequestBuilders.get("/o/" + offerId + "/" + code + "?sub1=abc").header("User-Agent", "Mozilla/5.0 (Windows NT 10.0)")).andReturn().getResponse();
        assertThat(click.getStatus()).isEqualTo(302);
        String location = click.getHeader("Location");
        assertThat(location).startsWith("https://shop.in/app?click_id=");
        String clickId = location.substring(location.indexOf("click_id=") + 9);
        assertThat(clickId).hasSize(26);
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/o/" + offerId + "/" + code).header("User-Agent", "Googlebot")).andReturn().getResponse().getStatus()).isEqualTo(302);

        String sig = Hashing.hmacSha256(secret, clickId + "|T1|install");
        assertThat(postback(offerId, clickId, "T1", "install", "bad")).isEqualTo("INVALID_SIGNATURE");
        assertThat(postback(offerId, "01ARZ3NDEKTSV4RRFFQ69G5FAV", "T1", "install", Hashing.hmacSha256(secret, "01ARZ3NDEKTSV4RRFFQ69G5FAV|T1|install"))).isEqualTo("UNKNOWN_CLICK");
        assertThat(postback(offerId, clickId, "T1", "install", sig)).isEqualTo("ACCEPTED");
        assertThat(postback(offerId, clickId, "T1", "install", sig)).isEqualTo("DUPLICATE");
        assertThat(postback(offerId, clickId, "T2", "install", Hashing.hmacSha256(secret, clickId + "|T2|install"))).isEqualTo("DUPLICATE"); // one install per click
        assertThat(wallet.balance(adv.workspaceId())).isEqualByComparingTo("9900");
        var prof = api.get("/api/v1/earn/profile", pr.token());
        assertThat(new BigDecimal(prof.text("/balancePending"))).isEqualByComparingTo("80");
        assertThat(db.exists("select 1 from conversions where click_id=:c and json_search(fraud_flags,'one','LOW_CTIT') is not null", "c", clickId)).isTrue(); // converted within seconds: flagged
        assertThat(db.count("select count(*) from postback_logs where offer_id=:o", "o", offerId)).isEqualTo(5);

        // advertiser approves early: pending -> available; a second conversion is rejected and refunded
        long conv1 = db.scalar("select id from conversions where click_id=:c", Long.class, "c", clickId);
        assertThat(api.patch("/api/v1/conversions/" + conv1, adv.token(), Map.of("action", "approve")).text("/status")).isEqualTo("APPROVED");
        prof = api.get("/api/v1/earn/profile", pr.token());
        assertThat(new BigDecimal(prof.text("/balanceAvailable"))).isEqualByComparingTo("80");
        assertThat(new BigDecimal(prof.text("/balancePending"))).isEqualByComparingTo("0");
        String click2 = clickFor(offerId, code);
        assertThat(postback(offerId, click2, "T3", "install", Hashing.hmacSha256(secret, click2 + "|T3|install"))).isEqualTo("ACCEPTED");
        long conv2 = db.scalar("select id from conversions where click_id=:c", Long.class, "c", click2);
        assertThat(api.patch("/api/v1/conversions/" + conv2, adv.token(), Map.of("action", "reject")).status()).isEqualTo(400); // reason required
        assertThat(api.patch("/api/v1/conversions/" + conv2, adv.token(), Map.of("action", "reject", "reason", "Fraud")).text("/status")).isEqualTo("REJECTED");
        assertThat(wallet.balance(adv.workspaceId())).isEqualByComparingTo("9900"); // 10000 - 100 (approved) - 100 + 100 refund
        assertThat(new BigDecimal(api.get("/api/v1/earn/profile", pr.token()).text("/balancePending"))).isEqualByComparingTo("0");
        assertThat(api.patch("/api/v1/conversions/" + conv1, adv.token(), Map.of("action", "reject", "reason", "Fraud")).status()).isEqualTo(409); // after the hold only Finance can reverse

        // earn enough to withdraw: add available balance through the ledger (as hold release would)
        db.tx(() -> earnings.post(db.scalar("select id from promoters where user_id=:u", Long.class, "u", pr.userId()), "ADJUSTMENT", new BigDecimal("120"), "AVAILABLE", null, null, "test credit"));
        // payout method needs OTP and sits in a 24 h cooldown
        var otp = api.post("/api/v1/earn/otp", pr.token(), Map.of("purpose", "PAYOUT_METHOD"));
        var method = api.post("/api/v1/earn/payout-methods", pr.token(), Map.of("type", "UPI", "upiId", "ravi.kumar@okaxis", "holderName", "Ravi Kumar", "otp", otp.text("/devOtp")));
        assertThat(method.status()).isEqualTo(200);
        assertThat(method.body().get("inCooldown").asBoolean()).isTrue();
        long methodId = method.body().get("id").asLong();
        var otpBad = api.post("/api/v1/earn/otp", pr.token(), Map.of("purpose", "PAYOUT_METHOD"));
        assertThat(api.post("/api/v1/earn/payout-methods", pr.token(), Map.of("type", "UPI", "upiId", "someone.else@okaxis", "holderName", "Ravi Kumar", "otp", otpBad.text("/devOtp"))).status()).isEqualTo(400); // name does not match PAN
        var w1 = api.post("/api/v1/earn/otp", pr.token(), Map.of("purpose", "WITHDRAW"));
        var early = api.post("/api/v1/earn/withdrawals", pr.token(), Map.of("amount", 100, "methodId", methodId, "otp", w1.text("/devOtp")));
        assertThat(early.status()).isEqualTo(422); // cooldown
        assertThat(early.text("/code")).isEqualTo("PAYOUT_LIMIT");
        db.update("update payout_methods set cooldown_until=null where id=:id", "id", methodId);

        assertThat(api.post("/api/v1/earn/withdrawals", pr.token(), Map.of("amount", 50, "methodId", methodId, "otp", "000000")).status()).isEqualTo(422); // below Rs 100 minimum
        assertThat(api.post("/api/v1/earn/withdrawals", pr.token(), Map.of("amount", 500, "methodId", methodId, "otp", "000000")).status()).isEqualTo(402); // above balance
        var wrongOtp = api.post("/api/v1/earn/withdrawals", pr.token(), Map.of("amount", 150, "methodId", methodId, "otp", "000000"));
        assertThat(wrongOtp.status()).isEqualTo(400);
        assertThat(wrongOtp.text("/code")).isEqualTo("OTP_INVALID");
        var w2 = api.post("/api/v1/earn/otp", pr.token(), Map.of("purpose", "WITHDRAW")); // 30 s resend rule applies
        assertThat(w2.status()).isEqualTo(429);
        db.update("update otp_codes set created_at = date_sub(created_at, interval 20 minute)");
        var w3 = api.post("/api/v1/earn/otp", pr.token(), Map.of("purpose", "WITHDRAW"));
        var wd = api.post("/api/v1/earn/withdrawals", pr.token(), Map.of("amount", 150, "methodId", methodId, "otp", w3.text("/devOtp")));
        assertThat(wd.status()).isEqualTo(200);
        assertThat(wd.text("/status")).isEqualTo("APPROVED"); // low risk and under Rs 5,000: auto-approved
        assertThat(new BigDecimal(api.get("/api/v1/earn/profile", pr.token()).text("/balanceAvailable"))).isEqualByComparingTo("50"); // money left the balance first
        runJobs();
        var done = api.get("/api/v1/earn/withdrawals", pr.token()).body().get(0);
        assertThat(done.get("status").asText()).isEqualTo("SUCCESS");
        assertThat(done.get("utr").asText()).startsWith("UTR");
        assertThat(earnings.verify()).isEmpty();
        assertThat(guard.check()).isEmpty();
    }

    @Test
    void largeWithdrawalNeedsFinanceTwoPersonApprovalAndFailureReturnsMoney() throws Exception {
        Api.Session pr = promoterSignup("Anu Priya");
        api.post("/api/v1/earn/kyc", pr.token(), Map.of("pan", "ABCPA1234F", "nameOnPan", "Anu Priya", "dateOfBirth", "1992-02-02", "acceptTerms", true));
        long pid = db.scalar("select id from promoters where user_id=:u", Long.class, "u", pr.userId());
        db.tx(() -> earnings.post(pid, "ADJUSTMENT", new BigDecimal("200000"), "AVAILABLE", null, null, "test credit"));
        var otp = api.post("/api/v1/earn/otp", pr.token(), Map.of("purpose", "PAYOUT_METHOD"));
        long methodId = api.post("/api/v1/earn/payout-methods", pr.token(), Map.of("type", "BANK", "accountNumber", "123456789012", "accountNumberConfirm", "123456789012",
                "ifsc", "SBIN0001234", "holderName", "Anu Priya", "otp", otp.text("/devOtp"))).body().get("id").asLong();
        db.update("update payout_methods set cooldown_until=null where id=:id", "id", methodId);
        db.update("update otp_codes set created_at = date_sub(created_at, interval 20 minute)");

        // Rs 49,500 fails at the bank in the sandbox; Rs 30,000 succeeds. Both need Finance (over Rs 5,000)
        long failing = withdraw(pr, 49500, methodId);
        db.update("update payouts set requested_at = date_sub(requested_at, interval 2 day) where id=:id", "id", failing); // daily limit is Rs 50,000
        db.update("update otp_codes set created_at = date_sub(created_at, interval 20 minute)");
        long ok = withdraw(pr, 30000, methodId);
        assertThat(db.scalar("select status from payouts where id=:id", String.class, "id", failing)).isEqualTo("REQUESTED");
        String f1 = adminToken("FINANCE"), f2 = adminToken("FINANCE");
        assertThat(api.post("/api/v1/admin/payouts/batches", pr.token(), Map.of("payoutIds", List.of(failing), "action", "APPROVE")).status()).isEqualTo(403); // promoters cannot approve
        assertThat(api.post("/api/v1/admin/payouts/batches", f1, Map.of("payoutIds", List.of(failing), "action", "REJECT", "reason", "x")).status()).isEqualTo(400);
        assertThat(api.get("/api/v1/admin/payouts", f1).body().size()).isEqualTo(2);

        // batch of Rs 79,500 is under Rs 1,00,000: a single approval is enough
        var approve = api.post("/api/v1/admin/payouts/batches", f1, Map.of("payoutIds", List.of(failing, ok), "action", "APPROVE"));
        assertThat(approve.status()).isEqualTo(200);
        assertThat(approve.body().get("approved").asInt()).isEqualTo(2);
        runJobs();
        assertThat(db.scalar("select status from payouts where id=:id", String.class, "id", ok)).isEqualTo("SUCCESS");
        assertThat(db.scalar("select status from payouts where id=:id", String.class, "id", failing)).isEqualTo("FAILED");
        // the failed payout gave the money back with a PAYOUT_REVERSAL row
        assertThat(db.exists("select 1 from earnings_ledger where payout_id=:id and type='PAYOUT_REVERSAL' and amount=49500", "id", failing)).isTrue();
        assertThat(new BigDecimal(api.get("/api/v1/earn/profile", pr.token()).text("/balanceAvailable"))).isEqualByComparingTo("170000"); // 200000 - 30000 paid
        assertThat(earnings.verify()).isEmpty();

        // two-person rule above Rs 1,00,000
        db.update("update otp_codes set created_at = date_sub(created_at, interval 20 minute)");
        db.update("update payouts set requested_at = date_sub(requested_at, interval 2 day) where id=:id", "id", ok);
        long big1 = withdraw(pr, 50000, methodId);
        db.update("update payouts set requested_at = date_sub(requested_at, interval 2 day) where id=:id", "id", big1);
        db.update("update otp_codes set created_at = date_sub(created_at, interval 20 minute)");
        long big2 = withdraw(pr, 50000, methodId);
        db.update("update payouts set requested_at = date_sub(requested_at, interval 3 day) where id=:id", "id", big2);
        db.update("update otp_codes set created_at = date_sub(created_at, interval 20 minute)");
        long big3 = withdraw(pr, 6000, methodId); // over Rs 5,000: not auto-approved
        db.update("update payouts set amount=60000, net_amount=60000 where id=:id", "id", big3); // batch total Rs 1,60,000
        var first = api.post("/api/v1/admin/payouts/batches", f1, Map.of("payoutIds", List.of(big1, big2, big3), "action", "APPROVE"));
        assertThat(first.body().get("awaitingSecondApproval").asInt()).isEqualTo(3);
        assertThat(db.scalar("select status from payouts where id=:id", String.class, "id", big1)).isEqualTo("REQUESTED");
        assertThat(api.post("/api/v1/admin/payouts/batches", f1, Map.of("payoutIds", List.of(big1, big2, big3), "action", "APPROVE")).status()).isEqualTo(409); // same person twice
        var second = api.post("/api/v1/admin/payouts/batches", f2, Map.of("payoutIds", List.of(big1, big2, big3), "action", "APPROVE"));
        assertThat(second.body().get("approved").asInt()).isEqualTo(3);
    }

    @Test
    void adminSupportAndDashboard() throws Exception {
        Api.Session s = api.signup();
        String su = adminToken("SUPER_ADMIN");
        var ws = api.get("/api/v1/admin/workspaces?q=" + s.workspaceId(), su);
        assertThat(ws.body().at("/items").toString()).contains("\"id\":" + s.workspaceId());
        assertThat(api.get("/api/v1/admin/workspaces", s.token()).status()).isEqualTo(403); // business users cannot reach admin
        assertThat(api.patch("/api/v1/admin/workspaces/" + s.workspaceId(), su, Map.of("status", "SUSPENDED")).status()).isEqualTo(400); // reason needed
        assertThat(api.post("/api/v1/admin/workspaces/" + s.workspaceId() + "/wallet-adjustments", su, Map.of("amount", 0, "note", "A valid note for this")).status()).isEqualTo(400);
        assertThat(api.post("/api/v1/admin/workspaces/" + s.workspaceId() + "/wallet-adjustments", su, Map.of("amount", 250, "note", "Goodwill credit for outage")).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/admin/workspaces/" + s.workspaceId() + "/wallet-adjustments", su, Map.of("amount", -50, "note", "Correction of the earlier credit")).status()).isEqualTo(200);
        assertThat(wallet.balance(s.workspaceId())).isEqualByComparingTo("200");
        assertThat(wallet.ledgerDifference(s.workspaceId())).isEqualByComparingTo("0");
        assertThat(api.post("/api/v1/admin/message-rates", su, Map.of("category", "MARKETING", "metaRate", 0.9, "sellRate", 0, "effectiveFrom", java.time.LocalDate.now().toString())).status()).isEqualTo(400);
        assertThat(api.post("/api/v1/admin/message-rates", su, Map.of("category", "MARKETING", "metaRate", 0.9, "sellRate", 1.3, "effectiveFrom", java.time.LocalDate.now().plusDays(10).toString())).status()).isEqualTo(200);

        var sus = api.patch("/api/v1/admin/workspaces/" + s.workspaceId(), su, Map.of("status", "SUSPENDED", "reason", "Spam complaints from customers"));
        assertThat(sus.text("/status")).isEqualTo("SUSPENDED");
        assertThat(api.post("/api/v1/contacts", s.token(), Map.of("fullName", "Blocked One", "phone", "9876500888")).status()).isEqualTo(403); // writes blocked while suspended
        assertThat(api.get("/api/v1/contacts", s.token()).status()).isEqualTo(200); // reads still work
        api.patch("/api/v1/admin/workspaces/" + s.workspaceId(), su, Map.of("status", "ACTIVE"));
        assertThat(api.post("/api/v1/contacts", s.token(), Map.of("fullName", "Allowed One", "phone", "9876500889")).status()).isEqualTo(200);
        assertThat(db.count("select count(*) from audit_logs where action like 'WORKSPACE_STATUS_%' and workspace_id=:ws", "ws", s.workspaceId())).isEqualTo(2);
        assertThat(api.get("/api/v1/admin/stats", su).body().has("mrr")).isTrue();

        // support ticket: owner raises, staff replies (internal notes stay hidden)
        assertThat(api.post("/api/v1/support/tickets", s.token(), Map.of("category", "BILLING", "subject", "Hi", "message", "short")).status()).isEqualTo(400);
        var t = api.post("/api/v1/support/tickets", s.token(), Map.of("category", "BILLING", "subject", "Invoice question", "message", "I need the GST invoice for my last wallet top-up please."));
        assertThat(t.status()).isEqualTo(200);
        long tid = t.body().get("id").asLong();
        String sup = adminToken("SUPPORT");
        assertThat(api.post("/api/v1/support/tickets/" + tid + "/messages", sup, Map.of("body", "Checking with finance", "internal", true)).status()).isEqualTo(200);
        assertThat(api.post("/api/v1/support/tickets/" + tid + "/messages", sup, Map.of("body", "Your invoice is attached in billing.")).status()).isEqualTo(200);
        var view = api.get("/api/v1/support/tickets/" + tid, s.token());
        assertThat(view.body().at("/messages").size()).isEqualTo(2); // internal note hidden from the customer
        assertThat(view.text("/status")).isEqualTo("WAITING_CUSTOMER");
        Api.Session other = api.signup();
        assertThat(api.get("/api/v1/support/tickets/" + tid, other.token()).status()).isEqualTo(404);
        for (int i = 0; i < 5; i++) api.post("/api/v1/support/tickets", s.token(), Map.of("category", "OTHER", "subject", "Another question " + i, "message", "Question number " + i + " about my account settings."));
        assertThat(api.post("/api/v1/support/tickets", s.token(), Map.of("category", "OTHER", "subject", "One too many", "message", "This should be refused as too many open tickets.")).status()).isEqualTo(409);

        // dashboard
        var d = api.get("/api/v1/dashboard", s.token());
        assertThat(d.status()).isEqualTo(200);
        assertThat(d.body().has("messagesSent")).isTrue();
        assertThat(api.get("/api/v1/dashboard?from=2020-01-01&to=2026-01-01", s.token()).status()).isEqualTo(400); // over a year
    }

    // ----------------------------------------------------------------- helpers

    private Map<String, Object> offerBody(long appId, int price, int payout) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", "Install Shop App");
        b.put("appId", appId);
        b.put("eventType", "INSTALL");
        b.put("advertiserPrice", price);
        b.put("promoterPayout", payout);
        b.put("dailyCap", 100);
        b.put("totalCap", 1000);
        b.put("holdDays", 7);
        b.put("attributionWindowHours", 168);
        b.put("minCtitSeconds", 10);
        b.put("incentiveAllowed", false);
        return b;
    }

    private Api.Session promoterSignup(String name) throws Exception {
        long n = System.nanoTime() % 100_000_000L;
        String phone = "+9197" + String.format("%08d", n);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fullName", name);
        body.put("phone", phone);
        body.put("email", "promo" + n + "@example.com");
        body.put("password", "Str0ng@Pass1");
        body.put("confirmPassword", "Str0ng@Pass1");
        body.put("acceptTerms", true);
        body.put("captchaToken", "x");
        var s1 = api.post("/api/v1/earn/signup", null, body);
        assertThat(s1.status()).isEqualTo(200);
        body.put("otp", s1.text("/devOtp"));
        body.put("signupToken", s1.text("/signupToken"));
        var s2 = api.post("/api/v1/earn/signup", null, body);
        assertThat(s2.status()).isEqualTo(200);
        return new Api.Session(s2.text("/accessToken"), s2.body().at("/user/id").asLong(), 0, phone, null);
    }

    private String postback(long offer, String click, String txn, String event, String sig) throws Exception {
        var r = mvc.perform(MockMvcRequestBuilders.get("/pb?offer=" + offer + "&click_id=" + click + "&txn_id=" + txn + "&event=" + event + "&sig=" + sig)).andReturn().getResponse();
        assertThat(r.getStatus()).isEqualTo(200); // always 200
        return om.readTree(r.getContentAsString()).get("result").asText();
    }

    private String clickFor(long offer, String code) throws Exception {
        String loc = mvc.perform(MockMvcRequestBuilders.get("/o/" + offer + "/" + code).header("User-Agent", "Mozilla/5.0 (Windows NT 10.0)")).andReturn().getResponse().getHeader("Location");
        return loc.substring(loc.indexOf("click_id=") + 9);
    }

    private long withdraw(Api.Session pr, int amount, long methodId) throws Exception {
        var otp = api.post("/api/v1/earn/otp", pr.token(), Map.of("purpose", "WITHDRAW"));
        assertThat(otp.status()).as(otp.body().toString()).isEqualTo(200);
        var r = api.post("/api/v1/earn/withdrawals", pr.token(), Map.of("amount", amount, "methodId", methodId, "otp", otp.text("/devOtp")));
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        return r.body().get("id").asLong();
    }
}
