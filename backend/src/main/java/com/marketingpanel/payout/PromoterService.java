package com.marketingpanel.payout;

import com.marketingpanel.auth.OtpService;
import com.marketingpanel.common.*;
import com.marketingpanel.config.Settings;
import com.marketingpanel.payment.GatewayRegistry;
import com.marketingpanel.payment.PaymentGateway;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
import java.util.*;
import java.util.regex.Pattern;

/** P34 sign-up & KYC, P35 offer wall and links, P36 payout methods. */
@Service
public class PromoterService {
    static final Pattern PAN = Pattern.compile("^[A-Z]{3}P[A-Z][0-9]{4}[A-Z]$");
    private static final Pattern UPI = Pattern.compile("^[a-zA-Z0-9._-]{2,256}@[a-zA-Z]{2,64}$");
    private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");
    private final Db db;
    private final Crypto crypto;
    private final KycVerifier kyc;
    private final GatewayRegistry gateways;
    private final OtpService otp;
    private final RateLimiter limiter;
    private final Settings settings;
    private final AuditService audit;
    private final PasswordEncoder encoder;

    public PromoterService(Db db, Crypto crypto, KycVerifier kyc, GatewayRegistry gateways, OtpService otp, RateLimiter limiter, Settings settings, AuditService audit, PasswordEncoder encoder) {
        this.db = db;
        this.crypto = crypto;
        this.kyc = kyc;
        this.gateways = gateways;
        this.otp = otp;
        this.limiter = limiter;
        this.settings = settings;
        this.audit = audit;
        this.encoder = encoder;
    }

    public Row byUser(long userId) {
        return db.row("select * from promoters where user_id=:u", "u", userId).orElseThrow(() -> ApiException.forbidden("Join as a promoter first."));
    }

    public Map<String, Object> profile(long userId) {
        Row p = byUser(userId);
        Map<String, Object> m = p.api();
        m.remove("panEnc");
        m.remove("panEncHash");
        m.put("fullName", db.scalar("select full_name from users where id=:u", String.class, "u", userId));
        m.put("kycRequiredForPayout", !"VERIFIED".equals(p.s("kyc_status")));
        m.put("paidTotal", db.scalar("select coalesce(sum(net_amount),0) from payouts where promoter_id=:p and status='SUCCESS'", BigDecimal.class, "p", p.l("id")));
        m.put("referralLink", settings.frontendUrl + "/earn/signup?ref=" + p.s("referral_code"));
        return m;
    }

    private String newCode() {
        String c;
        do {
            c = Ids.randomUpper(8);
        } while (db.exists("select 1 from promoters where referral_code=:c", "c", c));
        return c;
    }

    /** Creates the promoter profile for an existing user (business user who also wants to earn). */
    public Map<String, Object> becomePromoter(long userId, String referralCode, boolean acceptTerms) {
        if (!acceptTerms) throw ApiException.invalid("acceptTerms", "promoter.terms", "Please accept the promoter terms.");
        if (db.exists("select 1 from promoters where user_id=:u", "u", userId)) throw ApiException.duplicate("This mobile already has a promoter account.");
        Long ref = referredBy(referralCode);
        long id = db.insertInto("promoters", "user_id", userId, "referral_code", newCode(), "referred_by_id", ref);
        db.update("update users set account_type=if(account_type='BUSINESS','BOTH',account_type) where id=:u", "u", userId);
        audit.log(null, userId, "PROMOTER_JOINED", "promoters", id, null, null);
        return profile(userId);
    }

    public Long referredBy(String code) {
        if (code == null || code.isBlank()) return null;
        if (!code.matches("^[A-Z0-9]{6,12}$")) throw ApiException.invalid("referralCode", "promoter.referral", "This referral code is not valid.");
        Long id = db.scalar("select id from promoters where referral_code=:c and status='ACTIVE'", Long.class, "c", code);
        if (id == null) throw ApiException.invalid("referralCode", "promoter.referral", "This referral code is not valid.");
        return id;
    }

    // ------------------------------------------------------------------ KYC

    public record KycRequest(String pan, String nameOnPan, LocalDate dateOfBirth, Boolean acceptTerms) {}

    public Map<String, Object> verifyKyc(long userId, KycRequest r) {
        Row p = byUser(userId);
        limiter.check("kyc:" + userId, 5, Duration.ofDays(1));
        List<FieldErr> errs = new ArrayList<>();
        String pan = r.pan() == null ? "" : r.pan().trim().toUpperCase();
        if (!PAN.matcher(pan).matches()) errs.add(new FieldErr("pan", "kyc.pan", "Enter a valid PAN like ABCDE1234F."));
        String name = Norm.name(r.nameOnPan());
        if (name == null || !name.matches("^[\\p{L}\\p{M} .'&-]{2,100}$")) errs.add(new FieldErr("nameOnPan", "vr.name.invalid", "Enter a valid name (2-100 letters)."));
        if (r.dateOfBirth() == null || Period.between(r.dateOfBirth(), LocalDate.now()).getYears() < 18) errs.add(new FieldErr("dateOfBirth", "kyc.age", "You must be 18 or older to earn."));
        if (!Boolean.TRUE.equals(r.acceptTerms())) errs.add(new FieldErr("acceptTerms", "promoter.terms", "Please accept the promoter terms."));
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        String hash = Hashing.sha256(pan);
        if (db.exists("select 1 from promoters where pan_enc_hash=:h and id<>:id", "h", hash, "id", p.l("id")))
            throw ApiException.duplicate("pan", "kyc.pan.used", "This PAN is already linked to another promoter account.");
        String registered = kyc.verifyPan(pan, name, r.dateOfBirth());
        int score = registered == null ? 0 : NameMatch.score(name, registered);
        if (score < 80) {
            db.update("update promoters set kyc_status='REJECTED' where id=:id", "id", p.l("id"));
            throw ApiException.invalid("nameOnPan", "kyc.name", "Name does not match PAN records.");
        }
        db.update("update promoters set kyc_status='VERIFIED', pan_enc=:e, pan_enc_hash=:h, pan_last4=:l, legal_name=:n, dob=:d where id=:id",
                "e", crypto.encrypt(pan), "h", hash, "l", pan.substring(6, 10), "n", registered, "d", r.dateOfBirth(), "id", p.l("id"));
        audit.log(null, userId, "KYC_VERIFIED", "promoters", p.l("id"), null, Map.of("panLast4", pan.substring(6, 10)));
        return profile(userId);
    }

    public void setPostbackUrl(long userId, String url) {
        Row p = byUser(userId);
        String u = url == null || url.isBlank() ? null : url.trim();
        if (u != null) {
            if (!UrlGuard.isPublicHttps(u.replace("{click_id}", "x").replace("{sub1}", "x").replace("{payout}", "1").replace("{event}", "x").replace("{txn_id}", "x").replace("{status}", "x"), 2000))
                throw ApiException.invalid("postbackUrl", "vr.url.invalid", "Enter a valid https:// link.");
            if (!u.contains("{click_id}") && !u.contains("{sub1}")) throw ApiException.invalid("postbackUrl", "promoter.postback", "The URL must include {click_id} or {sub1}.");
        }
        db.update("update promoters set postback_url=:u where id=:id", "u", u, "id", p.l("id"));
    }

    // ---------------------------------------------------------- offer wall

    public List<Map<String, Object>> offerWall(long userId, String q, String eventType, String category, BigDecimal minPayout, BigDecimal maxPayout) {
        Row p = byUser(userId);
        StringBuilder sb = new StringBuilder("select o.id, o.name, o.event_type, o.event_name, o.promoter_payout, o.incentive_allowed, o.category, o.hold_days, o.attribution_window_hours, " +
                "o.starts_at, o.ends_at, a.name as app_name, a.icon_url from offers o join workspaces w on w.id=o.workspace_id left join apps a on a.id=o.app_id " +
                "where o.status='ACTIVE' and w.status in ('ACTIVE','TRIAL') and (o.starts_at is null or o.starts_at <= :now) and (o.ends_at is null or o.ends_at > :now)");
        List<Object> kv = new ArrayList<>(List.of("now", Time.now()));
        if (q != null && !q.isBlank()) {
            sb.append(" and o.name like :q");
            kv.add("q");
            kv.add("%" + q.trim() + "%");
        }
        if (eventType != null && !eventType.isBlank()) {
            sb.append(" and o.event_type=:e");
            kv.add("e");
            kv.add(eventType);
        }
        if (category != null && !category.isBlank()) {
            sb.append(" and o.category=:c");
            kv.add("c");
            kv.add(category);
        }
        if (minPayout != null) {
            sb.append(" and o.promoter_payout >= :mn");
            kv.add("mn");
            kv.add(minPayout);
        }
        if (maxPayout != null) {
            sb.append(" and o.promoter_payout <= :mx");
            kv.add("mx");
            kv.add(maxPayout);
        }
        sb.append(" order by o.promoter_payout desc limit 200");
        List<Map<String, Object>> rows = db.list(sb.toString(), kv.toArray());
        for (Map<String, Object> m : rows) {
            m.put("link", settings.publicBaseUrl + "/o/" + m.get("id") + "/" + p.s("referral_code"));
            m.put("warning", Boolean.TRUE.equals(m.get("incentiveAllowed")) ? null : "Do not promise rewards for installing.");
        }
        return rows;
    }

    public Map<String, Object> stats(long userId) {
        Row p = byUser(userId);
        long id = p.l("id");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("clicks", db.count("select count(*) from tracking_clicks where promoter_id=:p and is_bot=0", "p", id));
        out.put("conversions", db.count("select count(*) from conversions where promoter_id=:p and status<>'REJECTED'", "p", id));
        out.put("pending", p.d("balance_pending"));
        out.put("available", p.d("balance_available"));
        out.put("lifetimeEarned", p.d("lifetime_earned"));
        out.put("paid", db.scalar("select coalesce(sum(net_amount),0) from payouts where promoter_id=:p and status='SUCCESS'", BigDecimal.class, "p", id));
        out.put("byDay", db.list("select date(convert_tz(clicked_at,'+00:00','+05:30')) as day, count(*) as clicks from tracking_clicks where promoter_id=:p and is_bot=0 and clicked_at > :t group by day order by day",
                "p", id, "t", Time.now().minusDays(30)));
        out.put("byOffer", db.list("select o.id, o.name, (select count(*) from tracking_clicks c where c.promoter_id=:p and c.offer_id=o.id and c.is_bot=0) as clicks, " +
                "(select count(*) from conversions v where v.promoter_id=:p and v.offer_id=o.id and v.status<>'REJECTED') as conversions from offers o where o.id in " +
                "(select offer_id from tracking_clicks where promoter_id=:p) order by clicks desc limit 50", "p", id));
        out.put("recentConversions", db.list("select v.id, v.event_type, v.promoter_payout, v.status, v.created_at, v.hold_until, o.name as offer_name from conversions v join offers o on o.id=v.offer_id " +
                "where v.promoter_id=:p order by v.id desc limit 20", "p", id));
        return out;
    }

    public List<Map<String, Object>> ledger(long userId) {
        return db.list("select id, type, amount, bucket, balance_after, note, created_at from earnings_ledger where promoter_id=:p order by id desc limit 100", "p", byUser(userId).l("id"));
    }

    // ------------------------------------------------------ payout methods

    public record MethodRequest(String type, String upiId, String holderName, String accountNumber, String accountNumberConfirm, String ifsc, String otp) {}

    public Map<String, Object> sendOtp(long userId, String purpose) {
        if (!List.of("PAYOUT_METHOD", "WITHDRAW").contains(purpose)) throw ApiException.badRequest("Unsupported purpose.");
        String phone = db.scalar("select phone from users where id=:id", String.class, "id", userId);
        OtpService.Sent s = otp.send(phone, purpose);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("resendAfterSeconds", s.resendAfterSeconds());
        m.put("sentTo", Phones.mask(phone));
        if (s.devOtp() != null) m.put("devOtp", s.devOtp());
        return m;
    }

    public List<Map<String, Object>> methods(long userId) {
        return db.list("select id, type, account_last4, ifsc, holder_name, name_match_score, is_verified, is_default, cooldown_until, created_at, " +
                "(cooldown_until is not null and cooldown_until > :now) as in_cooldown from payout_methods where promoter_id=:p and deleted_at is null order by id", "p", byUser(userId).l("id"), "now", Time.now());
    }

    public Map<String, Object> addMethod(long userId, MethodRequest r) {
        Row p = byUser(userId);
        if (!"VERIFIED".equals(p.s("kyc_status"))) throw new ApiException(ErrorCode.KYC_REQUIRED, "Complete KYC before adding a payout method.");
        String phone = db.scalar("select phone from users where id=:id", String.class, "id", userId);
        otp.verify(phone, "PAYOUT_METHOD", r.otp());
        if (db.count("select count(*) from payout_methods where promoter_id=:p and deleted_at is null", "p", p.l("id")) >= 3)
            throw ApiException.planLimit("You can save up to 3 payout methods. Remove one first.");
        PaymentGateway gw = gateways.primary();
        String holder = Norm.name(r.holderName());
        List<FieldErr> errs = new ArrayList<>();
        if (holder == null || !holder.matches("^[\\p{L}\\p{M} .'&-]{2,100}$")) errs.add(new FieldErr("holderName", "vr.name.invalid", "Enter a valid name (2-100 letters)."));
        if (!errs.isEmpty()) throw ApiException.invalid(errs);
        int score;
        long id;
        PaymentGateway g = gw;
        if ("UPI".equals(r.type())) {
            String upi = r.upiId() == null ? "" : r.upiId().trim();
            if (!UPI.matcher(upi).matches()) throw ApiException.invalid("upiId", "payout.upi", "This UPI ID could not be verified.");
            PaymentGateway.VpaCheck v = g.verifyUpi(upi);
            if (!v.valid()) throw ApiException.invalid("upiId", "payout.upi", "This UPI ID could not be verified.");
            score = NameMatch.score(p.s("legal_name"), v.holderName());
            if (score < 80) throw ApiException.invalid("holderName", "payout.name", "Account name does not match your PAN name.");
            id = db.insertInto("payout_methods", "promoter_id", p.l("id"), "type", "UPI", "upi_id_enc", crypto.encrypt(upi), "holder_name", v.holderName() == null ? holder : holder,
                    "name_match_score", score, "is_verified", true, "cooldown_until", Time.now().plusHours(24));
        } else if ("BANK".equals(r.type())) {
            String acct = r.accountNumber() == null ? "" : r.accountNumber().trim();
            if (!acct.matches("^\\d{9,18}$")) throw ApiException.invalid("accountNumber", "payout.account", "Enter a valid account number (9 to 18 digits).");
            if (!acct.equals(r.accountNumberConfirm() == null ? "" : r.accountNumberConfirm().trim())) throw ApiException.invalid("accountNumberConfirm", "payout.account.mismatch", "Account numbers do not match.");
            String ifsc = r.ifsc() == null ? "" : r.ifsc().trim().toUpperCase();
            if (!IFSC.matcher(ifsc).matches()) throw ApiException.invalid("ifsc", "payout.ifsc", "Enter a valid 11-character IFSC.");
            PaymentGateway.BankCheck b = g.pennyDrop(acct, ifsc, holder);
            if (!b.valid()) throw ApiException.invalid("accountNumber", "payout.penny", "The bank account could not be verified.");
            score = NameMatch.score(p.s("legal_name"), b.holderName());
            if (score < 80) throw ApiException.invalid("holderName", "payout.name", "Account name does not match your PAN name.");
            id = db.insertInto("payout_methods", "promoter_id", p.l("id"), "type", "BANK", "account_number_enc", crypto.encrypt(acct), "account_last4", acct.substring(acct.length() - 4),
                    "ifsc", ifsc, "holder_name", holder, "name_match_score", score, "is_verified", true, "cooldown_until", Time.now().plusHours(24));
        } else {
            throw ApiException.invalid("type", "payout.type", "Choose UPI or Bank.");
        }
        if (db.count("select count(*) from payout_methods where promoter_id=:p and deleted_at is null and is_default=1", "p", p.l("id")) == 0)
            db.update("update payout_methods set is_default=1 where id=:id", "id", id);
        audit.log(null, userId, "PAYOUT_METHOD_ADDED", "payout_methods", id, null, Map.of("type", r.type()));
        return methods(userId).stream().filter(m -> ((Number) m.get("id")).longValue() == id).findFirst().orElseThrow();
    }

    public void deleteMethod(long userId, long id) {
        Row p = byUser(userId);
        if (db.exists("select 1 from payouts where payout_method_id=:m and status in ('REQUESTED','APPROVED','PROCESSING')", "m", id))
            throw ApiException.state("A payout using this method is still in progress.");
        int n = db.update("update payout_methods set deleted_at=:now, is_default=0 where id=:id and promoter_id=:p and deleted_at is null", "now", Time.now(), "id", id, "p", p.l("id"));
        if (n == 0) throw ApiException.notFound("Payout method");
        audit.log(null, userId, "PAYOUT_METHOD_REMOVED", "payout_methods", id, null, null);
    }

    public void makeDefault(long userId, long id) {
        Row p = byUser(userId);
        if (!db.exists("select 1 from payout_methods where id=:id and promoter_id=:p and deleted_at is null", "id", id, "p", p.l("id"))) throw ApiException.notFound("Payout method");
        db.update("update payout_methods set is_default=(id=:id) where promoter_id=:p and deleted_at is null", "id", id, "p", p.l("id"));
    }
}
