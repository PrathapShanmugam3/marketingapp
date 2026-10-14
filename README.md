# Marketing Panel

WhatsApp marketing, lead capture, reviews, social posting, app-promotion offers and promoter payouts for small businesses in India.
Implements `Marketing_Panel_SRS_v1.0` on the supplied `schema.sql`.

| Part | Stack |
|---|---|
| `backend/` | Java 21, Spring Boot 3.3, MySQL 8, Flyway, JWT + rotating refresh cookie, STOMP/WebSocket |
| `frontend/` | React 18, Vite, TypeScript, Tailwind, TanStack Query, React Hook Form + Zod, i18next (English / தமிழ்) |

Three portals share one login and one API:

* **Business panel** (`/`) - contacts and import, leads board, WhatsApp connect and templates, campaigns (A/B tests, scheduling, live progress), inbox, Google reviews, lead forms, social posts, automations, short links and QR, landing pages, app promotion, offers and conversions, team, billing and wallet, domains, API keys, agency client workspaces, support.
* **Promoter portal** (`/earn`) - join, dashboard, offer wall with tracking links, PAN KYC, bank/UPI payout methods, withdrawals, history, support.
* **Admin console** (`/admin`) - platform stats, workspaces (suspend, wallet adjustments), message rates and plans, finance (payout queue with two-person approval, promoters, fraud flags, reconciliation, ledger check), audit log, dead jobs, support tickets.

## Run it

### Docker (MySQL + API + web)

```bash
cp .env.example .env        # set secrets; set ADMIN_BOOTSTRAP_* to create the first staff login
docker compose up --build   # http://localhost
```

### Local development

```bash
# 1. MySQL 8 with an empty database user that may create tables
# 2. API (http://localhost:8080)
cd backend
DB_USER=root DB_PASSWORD=secret OTP_DEV_ECHO=true \
ADMIN_BOOTSTRAP_EMAIL=admin@example.com ADMIN_BOOTSTRAP_PHONE=9000000001 ADMIN_BOOTSTRAP_PASSWORD='a-long-password-1' \
mvn spring-boot:run
# 3. Web (http://localhost:5173, proxies the API)
cd frontend && npm install && npm run dev
```

With the defaults every external service is a **mock**: WhatsApp sends are simulated, payments open a local `/dev/pay/<order>` page, social posts and KYC are sandboxed, and OTPs are returned in the API response when `OTP_DEV_ECHO=true` (shown as a toast by the UI). Never enable `OTP_DEV_ECHO` in production.

### Tests

```bash
cd backend  && mvn test        # integration tests against a MySQL/MariaDB database (TEST_DB_URL, TEST_DB_USER, TEST_DB_PASSWORD)
cd frontend && npm test && npm run build
```

## Going live (what you must supply)

| Capability | Variables |
|---|---|
| WhatsApp Cloud API | `WHATSAPP_MOCK=false`, `META_APP_ID`, `META_APP_SECRET`, `META_VERIFY_TOKEN`; webhook `https://<host>/webhooks/meta` |
| RupiyaX payments and payouts | `RUPIYAX_MOCK=false`, `RUPIYAX_BASE_URL`, `RUPIYAX_API_KEY`, `RUPIYAX_API_SECRET`, `RUPIYAX_WEBHOOK_SECRET`; webhook `https://<host>/webhooks/rupiyax` |
| Social posting | `SOCIAL_MOCK=false` and a Meta app with page publishing permissions |
| AI writing help | `AI_API_KEY` (falls back to a built-in generator when empty) |
| CAPTCHA | `CAPTCHA_SECRET` (backend) and `VITE_TURNSTILE_SITE_KEY` (frontend build) |
| Secrets | `JWT_SECRET`, `ENCRYPTION_KEY` (base64, 32 bytes), `IP_HASH_SALT`, `INTERNAL_TOKEN`, `COOKIE_SECURE=true` |
| Custom domains | Caddy (`frontend/Caddyfile`) issues certificates on demand after the backend confirms DNS ownership (`/internal/tls/ask`) |

Run several instances with `APP_ROLE=api` (HTTP only) and `APP_ROLE=worker` (job queue and schedulers); `all` does both. Scheduled jobs use ShedLock so only one instance runs each.

## Design notes

* **Modular monolith**, package per bounded context under `com.marketingpanel`.
* **Money is append-only.** `wallet_transactions` and `earnings_ledger` rows are written under row locks; campaigns reserve the estimated cost, refund each undelivered message and settle after 48 hours. A nightly `LedgerGuard` compares balances with the ledgers and blocks payouts on any difference.
* **Payments are confirmed twice**: a signed webhook, then a server-side status call that must match order, amount and currency, before a wallet is credited or a plan activated.
* **Postbacks** use `HMAC-SHA256(secret, click_id|txn_id|event)`, always answer HTTP 200, and run the SRS checks in order (signature, IP allow-list, click age, duplicates, caps, fraud rules) before a conversion is held and released after the offer's hold period.
* **Payouts** debit the ledger first, use an idempotent `payout_ref`, need a second approver above ₹1,00,000, and are auto-approved only for low-risk small amounts.
* **Jobs** live in the MySQL `jobs` table (`FOR UPDATE SKIP LOCKED`, back-off 1 m / 5 m / 15 m / 1 h / 6 h, then DEAD with an `onDead` hook shown in the admin console).

## Deviations and assumptions

* Persistence uses **Spring JDBC (`NamedParameterJdbcTemplate`)** rather than JPA, to keep the money paths and the supplied schema explicit.
* `V2__additions.sql` extends the supplied schema (campaign A/B columns, `system_flags`, payment GST fields, extra OTP purposes and similar). `V1` is the supplied `schema.sql` unchanged apart from a collation placeholder.
* The **RupiyaX API contract** is not in the SRS (Appendix B is marked to be supplied). `RupiyaXGateway` is written against an assumed REST shape behind the `PaymentGateway` port; adjust that one class when the real contract arrives.
* Meta OAuth embedded sign-up and social OAuth are represented by accepting the resulting codes/tokens in the API; the screens ask for the token details instead of running the OAuth pop-up.
* KYC verification, IP intelligence and SMS/e-mail delivery are behind ports with sandbox implementations (`KycVerifier`, `IpIntelligence`, `Notifier`).
