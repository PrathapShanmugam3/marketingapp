-- Additions on top of the SRS schema (V1). Each one supports a rule described in the SRS chapters 3, 4 and 6.

-- Frequency cap needs the category of every outgoing template message (P29: max N marketing messages / contact / 24 h).
ALTER TABLE `messages`
  ADD COLUMN `template_id` BIGINT UNSIGNED NULL COMMENT 'FK wa_templates for TEMPLATE messages' AFTER `type`,
  ADD COLUMN `category` ENUM('MARKETING','UTILITY','AUTHENTICATION') NULL COMMENT 'Template category, for frequency caps' AFTER `template_id`,
  ADD KEY `ix_msg_cat` (`workspace_id`, `direction`, `category`, `created_at`);

ALTER TABLE `workspaces`
  ADD COLUMN `marketing_freq_cap` TINYINT UNSIGNED NOT NULL DEFAULT 2 COMMENT 'Max marketing messages per contact per 24 h (1 to 5)',
  ADD COLUMN `suspend_reason` VARCHAR(255) NULL COMMENT 'Set by super admin on SUSPENDED';

-- A/B test and link tracking settings of a campaign (P14).
ALTER TABLE `campaigns`
  ADD COLUMN `track_links` TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Replace URLs in variables with short links',
  ADD COLUMN `ab_metric` ENUM('READ','CLICK') NULL COMMENT 'Winner metric',
  ADD COLUMN `ab_winner_after_hours` TINYINT UNSIGNED NULL COMMENT '1 to 24',
  ADD COLUMN `ab_test_percent` TINYINT UNSIGNED NULL COMMENT 'Test group 10 to 50 percent',
  ADD COLUMN `paused_by_system` TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Paused by failures or disconnect';

-- Small key/value flags written by system jobs (ledger check result blocks payouts).
CREATE TABLE `system_flags` (
  `name` VARCHAR(60) NOT NULL,
  `value` VARCHAR(500) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='System flags such as ledger check result';

-- Offers: test mode, category for the offer wall and secret rotation (old secret valid 24 h).
ALTER TABLE `offers`
  ADD COLUMN `category` VARCHAR(40) NULL COMMENT 'Offer wall category',
  ADD COLUMN `tested_at` DATETIME(3) NULL COMMENT 'A test click + postback succeeded',
  ADD COLUMN `postback_secret_prev_enc` VARBINARY(256) NULL COMMENT 'Previous secret, valid until prev_until',
  ADD COLUMN `postback_secret_prev_until` DATETIME(3) NULL,
  ADD COLUMN `pause_reason` VARCHAR(100) NULL COMMENT 'e.g. WALLET_LOW';

ALTER TABLE `tracking_clicks`
  ADD COLUMN `country` CHAR(2) NULL;

ALTER TABLE `promoters`
  ADD COLUMN `dob` DATE NULL COMMENT 'From KYC, 18+',
  ADD COLUMN `pan_enc_hash` CHAR(64) NULL COMMENT 'SHA-256 of PAN for one-PAN-one-account check',
  ADD UNIQUE KEY `uk_prom_pan` (`pan_enc_hash`);

ALTER TABLE `payouts`
  ADD COLUMN `first_approver_id` BIGINT UNSIGNED NULL COMMENT 'Two-person rule for large batches',
  ADD COLUMN `reject_reason` VARCHAR(255) NULL;

ALTER TABLE `payments`
  ADD COLUMN `plan_id` BIGINT UNSIGNED NULL COMMENT 'For SUBSCRIPTION payments',
  ADD COLUMN `billing_cycle` ENUM('MONTHLY','YEARLY') NULL,
  ADD COLUMN `taxable_amount` DECIMAL(12,2) NULL COMMENT 'Amount before GST (= wallet credit for top-ups)',
  ADD COLUMN `pay_url` VARCHAR(500) NULL,
  ADD COLUMN `customer_gstin` CHAR(15) NULL;

ALTER TABLE `social_post_targets`
  ADD COLUMN `attempts` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  ADD COLUMN `next_retry_at` DATETIME(3) NULL;

ALTER TABLE `ticket_messages`
  ADD COLUMN `attachments_json` JSON NULL COMMENT 'Up to 3 file URLs';

ALTER TABLE `short_links`
  ADD COLUMN `title` VARCHAR(100) NULL;
