-- Marketing suite additions: e-mail/SMS broadcasts, push, chatbot, catalog, coupons and loyalty, content and SEO,
-- ad tracking, social DMs and extra social platforms. All tables are tenant scoped by workspace_id.

ALTER TABLE `social_accounts` MODIFY `platform` ENUM('FACEBOOK','INSTAGRAM','YOUTUBE','LINKEDIN','X') NOT NULL;
ALTER TABLE `social_posts` MODIFY `post_type` ENUM('IMAGE','CAROUSEL','VIDEO','TEXT') NOT NULL;

ALTER TABLE `wallet_transactions` MODIFY `reference_type` ENUM('PAYMENT','CAMPAIGN','CAMPAIGN_MESSAGE','CONVERSION','MANUAL','BROADCAST') NOT NULL;

ALTER TABLE `contacts` ADD COLUMN `email_unsub_at` DATETIME(3) NULL AFTER `opted_out_at`, ADD COLUMN `sms_unsub_at` DATETIME(3) NULL AFTER `email_unsub_at`;

UPDATE plans SET features = JSON_MERGE_PATCH(features, JSON_OBJECT('analytics', true)) WHERE code = 'STARTER';
UPDATE plans SET features = JSON_MERGE_PATCH(features, JSON_OBJECT('analytics', true, 'email', true, 'sms', true, 'push', true, 'chatbot', true, 'catalog', true, 'loyalty', true, 'content', true, 'ads', true, 'dm', true)) WHERE code IN ('GROWTH','AGENCY');

-- ---------------------------------------------------------------- e-mail and SMS broadcasts
CREATE TABLE `broadcasts` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT UNSIGNED NOT NULL,
  `channel` ENUM('EMAIL','SMS','PUSH') NOT NULL,
  `name` VARCHAR(100) NOT NULL,
  `subject` VARCHAR(150) NULL,
  `body` TEXT NOT NULL,
  `audience_type` ENUM('ALL_OPTED_IN','TAGS','SEGMENT') NOT NULL DEFAULT 'ALL_OPTED_IN',
  `audience_json` JSON NULL,
  `status` ENUM('DRAFT','SCHEDULED','SENDING','COMPLETED','CANCELLED','FAILED') NOT NULL DEFAULT 'DRAFT',
  `scheduled_at` DATETIME(3) NULL,
  `started_at` DATETIME(3) NULL,
  `completed_at` DATETIME(3) NULL,
  `total_count` INT NOT NULL DEFAULT 0,
  `sent_count` INT NOT NULL DEFAULT 0,
  `failed_count` INT NOT NULL DEFAULT 0,
  `click_count` INT NOT NULL DEFAULT 0,
  `unsub_count` INT NOT NULL DEFAULT 0,
  `cost` DECIMAL(12,4) NOT NULL DEFAULT 0,
  `created_by` BIGINT UNSIGNED NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `ix_bc_ws` (`workspace_id`, `channel`, `id`),
  KEY `ix_bc_due` (`status`, `scheduled_at`),
  CONSTRAINT `fk_broadcasts_ws` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation};

CREATE TABLE `broadcast_recipients` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `broadcast_id` BIGINT UNSIGNED NOT NULL,
  `contact_id` BIGINT UNSIGNED NOT NULL,
  `status` ENUM('PENDING','SENT','FAILED','SKIPPED') NOT NULL DEFAULT 'PENDING',
  `error` VARCHAR(200) NULL,
  `sent_at` DATETIME(3) NULL,
  `clicked_at` DATETIME(3) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_bcr` (`broadcast_id`, `contact_id`),
  KEY `ix_bcr_status` (`broadcast_id`, `status`),
  CONSTRAINT `fk_bcr_bc` FOREIGN KEY (`broadcast_id`) REFERENCES `broadcasts` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation};

CREATE TABLE `push_subscriptions` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT UNSIGNED NOT NULL,
  `contact_id` BIGINT UNSIGNED NULL,
  `endpoint` VARCHAR(500) NOT NULL,
  `endpoint_hash` CHAR(64) NOT NULL,
  `p256dh` VARCHAR(200) NULL,
  `auth` VARCHAR(100) NULL,
  `active` TINYINT(1) NOT NULL DEFAULT 1,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_push` (`workspace_id`, `endpoint_hash`),
  CONSTRAINT `fk_push_ws` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation};

-- ---------------------------------------------------------------- chatbot
CREATE TABLE `chatbot_rules` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT UNSIGNED NOT NULL,
  `name` VARCHAR(80) NOT NULL,
  `match_type` ENUM('EXACT','CONTAINS','FIRST_MESSAGE','FALLBACK') NOT NULL DEFAULT 'CONTAINS',
  `keywords` JSON NULL,
  `reply_text` VARCHAR(1000) NOT NULL,
  `then_tag_id` BIGINT UNSIGNED NULL,
  `handoff` TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Mark the conversation for a human',
  `priority` INT NOT NULL DEFAULT 100,
  `active` TINYINT(1) NOT NULL DEFAULT 1,
  `hit_count` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `ix_cb_ws` (`workspace_id`, `active`, `priority`),
  CONSTRAINT `fk_chatbot_ws` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation};

-- ---------------------------------------------------------------- catalog
CREATE TABLE `products` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT UNSIGNED NOT NULL,
  `name` VARCHAR(120) NOT NULL,
  `description` VARCHAR(1000) NULL,
  `sku` VARCHAR(40) NULL,
  `category` VARCHAR(60) NULL,
  `price` DECIMAL(12,2) NOT NULL,
  `compare_at_price` DECIMAL(12,2) NULL,
  `image_url` VARCHAR(500) NULL,
  `in_stock` TINYINT(1) NOT NULL DEFAULT 1,
  `active` TINYINT(1) NOT NULL DEFAULT 1,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `ix_prod_ws` (`workspace_id`, `active`),
  CONSTRAINT `fk_products_ws` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation};

-- ---------------------------------------------------------------- coupons, loyalty, referrals
CREATE TABLE `coupons` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT UNSIGNED NOT NULL,
  `code` VARCHAR(20) NOT NULL,
  `description` VARCHAR(200) NULL,
  `discount_type` ENUM('PERCENT','FLAT') NOT NULL,
  `discount_value` DECIMAL(10,2) NOT NULL,
  `max_discount` DECIMAL(10,2) NULL,
  `min_order` DECIMAL(12,2) NOT NULL DEFAULT 0,
  `max_redemptions` INT NULL,
  `per_contact_limit` INT NOT NULL DEFAULT 1,
  `valid_from` DATETIME(3) NULL,
  `valid_until` DATETIME(3) NULL,
  `active` TINYINT(1) NOT NULL DEFAULT 1,
  `redeemed_count` INT NOT NULL DEFAULT 0,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_coupon` (`workspace_id`, `code`),
  CONSTRAINT `fk_coupons_ws` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation};

CREATE TABLE `coupon_redemptions` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT UNSIGNED NOT NULL,
  `coupon_id` BIGINT UNSIGNED NOT NULL,
  `contact_id` BIGINT UNSIGNED NULL,
  `order_amount` DECIMAL(12,2) NOT NULL,
  `discount_amount` DECIMAL(12,2) NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `ix_cr_coupon` (`coupon_id`, `contact_id`),
  KEY `ix_cr_ws` (`workspace_id`, `created_at`),
  CONSTRAINT `fk_cr_coupon` FOREIGN KEY (`coupon_id`) REFERENCES `coupons` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation};

CREATE TABLE `loyalty_settings` (
  `workspace_id` BIGINT UNSIGNED NOT NULL,
  `enabled` TINYINT(1) NOT NULL DEFAULT 1,
  `points_per_100` INT NOT NULL DEFAULT 5,
  `rupees_per_point` DECIMAL(6,2) NOT NULL DEFAULT 1.00,
  `referral_bonus` INT NOT NULL DEFAULT 100,
  `referee_bonus` INT NOT NULL DEFAULT 50,
  `silver_at` INT NOT NULL DEFAULT 500,
  `gold_at` INT NOT NULL DEFAULT 2000,
  PRIMARY KEY (`workspace_id`),
  CONSTRAINT `fk_ls_ws` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation};

CREATE TABLE `loyalty_accounts` (
  `workspace_id` BIGINT UNSIGNED NOT NULL,
  `contact_id` BIGINT UNSIGNED NOT NULL,
  `points` INT NOT NULL DEFAULT 0,
  `lifetime_points` INT NOT NULL DEFAULT 0,
  `referral_code` VARCHAR(12) NOT NULL,
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`workspace_id`, `contact_id`),
  UNIQUE KEY `uk_loy_code` (`referral_code`),
  CONSTRAINT `fk_la_ws` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation};

CREATE TABLE `loyalty_ledger` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT UNSIGNED NOT NULL,
  `contact_id` BIGINT UNSIGNED NOT NULL,
  `points` INT NOT NULL COMMENT 'Signed',
  `balance_after` INT NOT NULL,
  `reason` ENUM('PURCHASE','REDEEM','REFERRAL','REFERRED','ADJUST') NOT NULL,
  `note` VARCHAR(200) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `ix_ll` (`workspace_id`, `contact_id`, `id`),
  CONSTRAINT `fk_ll_ws` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation};

CREATE TABLE `referrals` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT UNSIGNED NOT NULL,
  `referrer_contact_id` BIGINT UNSIGNED NOT NULL,
  `referred_contact_id` BIGINT UNSIGNED NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_ref` (`workspace_id`, `referred_contact_id`),
  CONSTRAINT `fk_ref_ws` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation};

-- ---------------------------------------------------------------- content, blog and SEO
CREATE TABLE `content_items` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT UNSIGNED NOT NULL,
  `type` ENUM('BLOG','IDEA','SOCIAL','EMAIL','VIDEO') NOT NULL DEFAULT 'BLOG',
  `title` VARCHAR(150) NOT NULL,
  `slug` VARCHAR(100) NULL,
  `body_html` MEDIUMTEXT NULL,
  `cover_url` VARCHAR(500) NULL,
  `seo_title` VARCHAR(70) NULL,
  `seo_description` VARCHAR(160) NULL,
  `keywords` VARCHAR(200) NULL,
  `status` ENUM('IDEA','DRAFT','SCHEDULED','PUBLISHED') NOT NULL DEFAULT 'DRAFT',
  `publish_at` DATETIME(3) NULL,
  `published_at` DATETIME(3) NULL,
  `view_count` INT NOT NULL DEFAULT 0,
  `author_id` BIGINT UNSIGNED NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_content_slug` (`workspace_id`, `slug`),
  KEY `ix_content_cal` (`workspace_id`, `publish_at`),
  CONSTRAINT `fk_content_ws` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation};

CREATE TABLE `seo_audits` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT UNSIGNED NOT NULL,
  `url` VARCHAR(500) NOT NULL,
  `score` INT NOT NULL,
  `result_json` JSON NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `ix_seo_ws` (`workspace_id`, `id`),
  CONSTRAINT `fk_seoa_ws` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation};

CREATE TABLE `seo_keywords` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT UNSIGNED NOT NULL,
  `keyword` VARCHAR(100) NOT NULL,
  `target_url` VARCHAR(500) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_seokw` (`workspace_id`, `keyword`),
  CONSTRAINT `fk_seokw_ws` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation};

CREATE TABLE `seo_rankings` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `keyword_id` BIGINT UNSIGNED NOT NULL,
  `checked_on` DATE NOT NULL,
  `position` INT NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_seorank` (`keyword_id`, `checked_on`),
  CONSTRAINT `fk_seorank_kw` FOREIGN KEY (`keyword_id`) REFERENCES `seo_keywords` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation};

-- ---------------------------------------------------------------- ads
CREATE TABLE `ad_campaigns` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT UNSIGNED NOT NULL,
  `platform` ENUM('GOOGLE','META','YOUTUBE','LINKEDIN','X','OTHER') NOT NULL,
  `name` VARCHAR(100) NOT NULL,
  `objective` ENUM('AWARENESS','TRAFFIC','LEADS','SALES','APP_INSTALLS') NOT NULL DEFAULT 'LEADS',
  `daily_budget` DECIMAL(12,2) NOT NULL DEFAULT 0,
  `total_budget` DECIMAL(12,2) NULL,
  `start_date` DATE NULL,
  `end_date` DATE NULL,
  `status` ENUM('DRAFT','ACTIVE','PAUSED','ENDED') NOT NULL DEFAULT 'DRAFT',
  `external_id` VARCHAR(60) NULL,
  `utm_campaign` VARCHAR(60) NULL,
  `notes` VARCHAR(500) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `ix_ads_ws` (`workspace_id`, `status`),
  CONSTRAINT `fk_ads_ws` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation};

CREATE TABLE `ad_metrics` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `ad_campaign_id` BIGINT UNSIGNED NOT NULL,
  `day` DATE NOT NULL,
  `spend` DECIMAL(12,2) NOT NULL DEFAULT 0,
  `impressions` INT NOT NULL DEFAULT 0,
  `clicks` INT NOT NULL DEFAULT 0,
  `conversions` INT NOT NULL DEFAULT 0,
  `revenue` DECIMAL(12,2) NOT NULL DEFAULT 0,
  `source` ENUM('MANUAL','SYNC','IMPORT') NOT NULL DEFAULT 'MANUAL',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_adm` (`ad_campaign_id`, `day`),
  CONSTRAINT `fk_adm_ad` FOREIGN KEY (`ad_campaign_id`) REFERENCES `ad_campaigns` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation};

-- ---------------------------------------------------------------- Instagram / Facebook DMs
CREATE TABLE `dm_threads` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `workspace_id` BIGINT UNSIGNED NOT NULL,
  `social_account_id` BIGINT UNSIGNED NOT NULL,
  `platform_user_id` VARCHAR(60) NOT NULL,
  `display_name` VARCHAR(100) NULL,
  `contact_id` BIGINT UNSIGNED NULL,
  `last_message_preview` VARCHAR(200) NULL,
  `last_inbound_at` DATETIME(3) NULL,
  `unread_count` INT NOT NULL DEFAULT 0,
  `status` ENUM('OPEN','RESOLVED') NOT NULL DEFAULT 'OPEN',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dm` (`social_account_id`, `platform_user_id`),
  KEY `ix_dm_ws` (`workspace_id`, `status`, `updated_at`),
  CONSTRAINT `fk_dm_ws` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation};

CREATE TABLE `dm_messages` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `thread_id` BIGINT UNSIGNED NOT NULL,
  `direction` ENUM('IN','OUT') NOT NULL,
  `body` VARCHAR(2000) NOT NULL,
  `external_id` VARCHAR(80) NULL,
  `status` ENUM('RECEIVED','SENT','FAILED') NOT NULL DEFAULT 'RECEIVED',
  `sent_by` BIGINT UNSIGNED NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dmm_ext` (`thread_id`, `external_id`),
  KEY `ix_dmm` (`thread_id`, `id`),
  CONSTRAINT `fk_dmm_thread` FOREIGN KEY (`thread_id`) REFERENCES `dm_threads` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation};

