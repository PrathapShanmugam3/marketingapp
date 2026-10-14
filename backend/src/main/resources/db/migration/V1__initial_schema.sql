-- Marketing Panel - MySQL 8 schema (generated from SRS v1.0)
-- Charset utf8mb4 for Tamil text. All times stored in UTC.


-- Subscription plans and their limits
CREATE TABLE `plans` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `code` VARCHAR(30) NOT NULL COMMENT 'STARTER, GROWTH, AGENCY',
  `name` VARCHAR(50) NOT NULL COMMENT 'Display name',
  `price_monthly` DECIMAL(10,2) NOT NULL COMMENT 'INR, excl. GST',
  `price_yearly` DECIMAL(10,2) NOT NULL COMMENT 'INR, excl. GST',
  `max_users` INT UNSIGNED NOT NULL COMMENT 'Users per workspace',
  `max_contacts` INT UNSIGNED NOT NULL COMMENT 'Contacts per workspace',
  `max_workspaces` INT UNSIGNED NOT NULL DEFAULT 1 COMMENT 'Client workspaces (agency)',
  `max_domains` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Custom domains allowed',
  `features` JSON NOT NULL COMMENT 'Feature flags, e.g. {"social":true}',
  `gateway_plan_monthly` VARCHAR(64) NULL COMMENT 'RupiyaX plan id',
  `gateway_plan_yearly` VARCHAR(64) NULL COMMENT 'RupiyaX plan id',
  `is_active` TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'Can be purchased',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_plans_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Subscription plans and their limits';

-- WhatsApp cost and sell price per category; change without a deploy
CREATE TABLE `message_rates` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `category` ENUM('MARKETING','UTILITY','AUTHENTICATION') NOT NULL COMMENT 'Meta category',
  `country_code` VARCHAR(5) NOT NULL DEFAULT '91' COMMENT 'Recipient calling code',
  `meta_rate` DECIMAL(10,4) NOT NULL COMMENT 'Meta charge per message, INR',
  `sell_rate` DECIMAL(10,4) NOT NULL COMMENT 'Charged to wallet, INR incl. margin',
  `effective_from` DATE NOT NULL COMMENT 'Rate start date',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rates` (`category`, `country_code`, `effective_from`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='WhatsApp cost and sell price per category; change without a deploy';

-- Login accounts (global, can join many workspaces)
CREATE TABLE `users` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `full_name` VARCHAR(100) NOT NULL COMMENT 'VR-01',
  `email` VARCHAR(150) NOT NULL COMMENT 'Lower-case, unique',
  `phone` VARCHAR(15) NOT NULL COMMENT 'E.164, e.g. +919876543210',
  `password_hash` CHAR(60) NOT NULL COMMENT 'BCrypt, cost 12',
  `email_verified_at` DATETIME(3) NULL,
  `phone_verified_at` DATETIME(3) NULL,
  `preferred_language` ENUM('en','ta') NOT NULL DEFAULT 'en' COMMENT 'UI language',
  `admin_role` ENUM('NONE','SUPER_ADMIN','FINANCE','SUPPORT') NOT NULL DEFAULT 'NONE' COMMENT 'Platform staff role',
  `account_type` ENUM('BUSINESS','PROMOTER','BOTH') NOT NULL DEFAULT 'BUSINESS' COMMENT 'Which portals the user sees',
  `status` ENUM('ACTIVE','LOCKED','DISABLED') NOT NULL DEFAULT 'ACTIVE',
  `failed_login_count` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Reset on success',
  `locked_until` DATETIME(3) NULL COMMENT 'Set after 5 failures',
  `last_login_at` DATETIME(3) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_users_email` (`email`),
  UNIQUE KEY `uk_users_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Login accounts (global, can join many workspaces)';

-- One business (tenant); agency clients point to a parent
CREATE TABLE `workspaces` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `parent_workspace_id` BIGINT UNSIGNED NULL COMMENT 'Agency owner workspace',
  `name` VARCHAR(100) NOT NULL COMMENT 'Business name',
  `business_type` ENUM('RETAIL','CLINIC','SALON','EDUCATION','REAL_ESTATE','RESTAURANT','AGENCY','OTHER') NOT NULL,
  `phone` VARCHAR(15) NOT NULL COMMENT 'E.164',
  `email` VARCHAR(150) NULL,
  `gstin` CHAR(15) NULL COMMENT 'VR-06',
  `address_line` VARCHAR(200) NULL,
  `city` VARCHAR(60) NOT NULL,
  `state` VARCHAR(60) NOT NULL COMMENT 'Decides CGST+SGST or IGST',
  `pincode` CHAR(6) NULL COMMENT 'VR-07',
  `language` ENUM('en','ta') NOT NULL DEFAULT 'en' COMMENT 'Default template language',
  `timezone` VARCHAR(40) NOT NULL DEFAULT 'Asia/Kolkata',
  `logo_url` VARCHAR(500) NULL COMMENT 'Object storage URL',
  `plan_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK plans',
  `status` ENUM('TRIAL','ACTIVE','PAST_DUE','SUSPENDED','CANCELLED') NOT NULL DEFAULT 'TRIAL',
  `trial_ends_at` DATETIME(3) NULL COMMENT 'Signup + 14 days',
  `wallet_balance` DECIMAL(12,4) NOT NULL DEFAULT 0.0000 COMMENT 'Message credits, INR',
  `brand_color` CHAR(7) NULL COMMENT 'White-label colour',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  `deleted_at` DATETIME(3) NULL COMMENT 'Soft delete marker',
  PRIMARY KEY (`id`),
  KEY `ix_ws_parent` (`parent_workspace_id`),
  KEY `ix_ws_status` (`status`),
  CONSTRAINT `fk_workspaces_parent_workspace_id` FOREIGN KEY (`parent_workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_workspaces_plan_id` FOREIGN KEY (`plan_id`) REFERENCES `plans` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='One business (tenant); agency clients point to a parent';

-- Which user can open which workspace, and with what role
CREATE TABLE `workspace_members` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `user_id` BIGINT UNSIGNED NULL COMMENT 'Null until invite accepted',
  `invite_email` VARCHAR(150) NULL COMMENT 'Email the invite was sent to',
  `role` ENUM('OWNER','MANAGER','STAFF','AGENCY_ADMIN') NOT NULL,
  `invite_status` ENUM('PENDING','ACCEPTED','REVOKED') NOT NULL DEFAULT 'ACCEPTED',
  `invite_token_hash` CHAR(64) NULL COMMENT 'SHA-256 of token',
  `invite_expires_at` DATETIME(3) NULL COMMENT 'Sent + 7 days',
  `invited_by` BIGINT UNSIGNED NULL COMMENT 'FK users',
  `joined_at` DATETIME(3) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_member` (`workspace_id`, `user_id`),
  UNIQUE KEY `uk_member_invite` (`invite_token_hash`),
  CONSTRAINT `fk_workspace_members_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_workspace_members_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_workspace_members_invited_by` FOREIGN KEY (`invited_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Which user can open which workspace, and with what role';

-- One-time codes for signup, login and password reset
CREATE TABLE `otp_codes` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `target` VARCHAR(150) NOT NULL COMMENT 'Phone or email',
  `purpose` ENUM('SIGNUP','LOGIN','RESET_PASSWORD','CHANGE_PHONE') NOT NULL,
  `code_hash` CHAR(60) NOT NULL COMMENT 'BCrypt of 6-digit code',
  `attempts` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Max 5',
  `expires_at` DATETIME(3) NOT NULL COMMENT 'Created + 5 min',
  `consumed_at` DATETIME(3) NULL,
  `ip_address` VARCHAR(45) NULL COMMENT 'IPv4/IPv6',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  PRIMARY KEY (`id`),
  KEY `ix_otp_target` (`target`, `purpose`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='One-time codes for signup, login and password reset';

-- Long-lived sessions (rotated on every refresh)
CREATE TABLE `refresh_tokens` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK users',
  `token_hash` CHAR(64) NOT NULL COMMENT 'SHA-256',
  `user_agent` VARCHAR(255) NULL,
  `ip_address` VARCHAR(45) NULL,
  `expires_at` DATETIME(3) NOT NULL COMMENT 'Issued + 30 days',
  `revoked_at` DATETIME(3) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rt_hash` (`token_hash`),
  CONSTRAINT `fk_refresh_tokens_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Long-lived sessions (rotated on every refresh)';

-- Gateway subscription / auto-renew mandate per workspace
CREATE TABLE `subscriptions` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `plan_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK plans',
  `billing_cycle` ENUM('MONTHLY','YEARLY') NOT NULL,
  `gateway` ENUM('RUPIYAX','RAZORPAY') NOT NULL DEFAULT 'RUPIYAX' COMMENT 'Adapter used',
  `gateway_subscription_id` VARCHAR(64) NULL COMMENT 'Mandate / subscription id',
  `status` ENUM('CREATED','AUTHENTICATED','ACTIVE','PENDING','HALTED','CANCELLED','COMPLETED','EXPIRED') NOT NULL COMMENT 'Mapped from gateway status',
  `current_period_start` DATETIME(3) NULL,
  `current_period_end` DATETIME(3) NULL,
  `cancel_at_period_end` TINYINT(1) NOT NULL DEFAULT 0,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sub_gw` (`gateway`, `gateway_subscription_id`),
  KEY `ix_sub_ws` (`workspace_id`, `status`),
  CONSTRAINT `fk_subscriptions_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_subscriptions_plan_id` FOREIGN KEY (`plan_id`) REFERENCES `plans` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Gateway subscription / auto-renew mandate per workspace';

-- GST invoices for plans, wallet top-ups and services
CREATE TABLE `invoices` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `invoice_number` VARCHAR(30) NOT NULL COMMENT 'e.g. MP/2026-27/0001',
  `type` ENUM('SUBSCRIPTION','WALLET','SETUP','SERVICE') NOT NULL,
  `taxable_amount` DECIMAL(12,2) NOT NULL,
  `cgst` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '9% intra-state',
  `sgst` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '9% intra-state',
  `igst` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT '18% inter-state',
  `total` DECIMAL(12,2) NOT NULL,
  `customer_gstin` CHAR(15) NULL COMMENT 'Copied at issue time',
  `place_of_supply` VARCHAR(60) NOT NULL COMMENT 'State',
  `status` ENUM('DRAFT','ISSUED','PAID','VOID') NOT NULL DEFAULT 'ISSUED',
  `pdf_url` VARCHAR(500) NULL,
  `issued_at` DATETIME(3) NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_inv_no` (`invoice_number`),
  KEY `ix_inv_ws` (`workspace_id`, `issued_at`),
  CONSTRAINT `fk_invoices_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='GST invoices for plans, wallet top-ups and services';

-- Money received from customers through RupiyaX (plans, wallet, services)
CREATE TABLE `payments` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `invoice_id` BIGINT UNSIGNED NULL COMMENT 'FK invoices',
  `gateway` ENUM('RUPIYAX','RAZORPAY') NOT NULL DEFAULT 'RUPIYAX',
  `order_ref` VARCHAR(40) NOT NULL COMMENT 'Our unique order id sent to gateway',
  `gateway_txn_id` VARCHAR(64) NULL COMMENT 'Gateway transaction id',
  `utr` VARCHAR(30) NULL COMMENT 'Bank / UPI reference',
  `purpose` ENUM('SUBSCRIPTION','WALLET','SETUP','SERVICE') NOT NULL,
  `amount` DECIMAL(12,2) NOT NULL COMMENT 'INR incl. GST',
  `currency` CHAR(3) NOT NULL DEFAULT 'INR',
  `method` VARCHAR(20) NULL COMMENT 'upi, card, netbanking',
  `status` ENUM('CREATED','PENDING','SUCCESS','FAILED','REFUNDED') NOT NULL DEFAULT 'CREATED' COMMENT 'SUCCESS only after server status check',
  `failure_reason` VARCHAR(255) NULL,
  `paid_at` DATETIME(3) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_pay_order` (`order_ref`),
  UNIQUE KEY `uk_pay_txn` (`gateway`, `gateway_txn_id`),
  KEY `ix_pay_ws` (`workspace_id`, `created_at`),
  CONSTRAINT `fk_payments_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_payments_invoice_id` FOREIGN KEY (`invoice_id`) REFERENCES `invoices` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Money received from customers through RupiyaX (plans, wallet, services)';

-- Ledger of message credits; balance never edited directly
CREATE TABLE `wallet_transactions` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `type` ENUM('CREDIT','DEBIT','REFUND','ADJUSTMENT') NOT NULL,
  `amount` DECIMAL(12,4) NOT NULL COMMENT 'Always positive',
  `balance_after` DECIMAL(12,4) NOT NULL COMMENT 'Snapshot after this row',
  `reference_type` ENUM('PAYMENT','CAMPAIGN','CAMPAIGN_MESSAGE','CONVERSION','MANUAL') NOT NULL COMMENT 'CONVERSION = offer cost',
  `reference_id` BIGINT UNSIGNED NULL COMMENT 'Id in the referenced table',
  `note` VARCHAR(255) NULL,
  `created_by` BIGINT UNSIGNED NULL COMMENT 'FK users',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  PRIMARY KEY (`id`),
  KEY `ix_wallet_ws` (`workspace_id`, `created_at`),
  CONSTRAINT `fk_wallet_transactions_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_wallet_transactions_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Ledger of message credits; balance never edited directly';

-- Customers of the business; opt-in tracked per contact
CREATE TABLE `contacts` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `full_name` VARCHAR(100) NOT NULL COMMENT 'VR-01',
  `phone` VARCHAR(15) NOT NULL COMMENT 'E.164',
  `email` VARCHAR(150) NULL,
  `city` VARCHAR(60) NULL,
  `date_of_birth` DATE NULL COMMENT 'Birthday campaigns',
  `language` ENUM('en','ta') NULL COMMENT 'Preferred template language',
  `opt_in_status` ENUM('OPTED_IN','OPTED_OUT','UNKNOWN') NOT NULL DEFAULT 'UNKNOWN' COMMENT 'Only OPTED_IN get marketing',
  `opt_in_source` ENUM('IMPORT','FORM','MANUAL','WHATSAPP','QR') NULL COMMENT 'Proof of consent',
  `opted_in_at` DATETIME(3) NULL,
  `opted_out_at` DATETIME(3) NULL,
  `custom_fields` JSON NULL COMMENT 'Up to 10 keys',
  `last_message_at` DATETIME(3) NULL,
  `created_by` BIGINT UNSIGNED NULL COMMENT 'FK users',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  `deleted_at` DATETIME(3) NULL COMMENT 'Soft delete marker',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_contact_phone` (`workspace_id`, `phone`),
  KEY `ix_contact_optin` (`workspace_id`, `opt_in_status`),
  KEY `ix_contact_name` (`workspace_id`, `full_name`),
  CONSTRAINT `fk_contacts_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_contacts_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Customers of the business; opt-in tracked per contact';

-- Labels used to group contacts
CREATE TABLE `tags` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `name` VARCHAR(40) NOT NULL COMMENT 'Unique per workspace',
  `color` CHAR(7) NOT NULL DEFAULT '#12A388' COMMENT 'Hex colour',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tag_name` (`workspace_id`, `name`),
  CONSTRAINT `fk_tags_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Labels used to group contacts';

-- Many-to-many link between contacts and tags
CREATE TABLE `contact_tags` (
  `contact_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK contacts',
  `tag_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK tags',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  PRIMARY KEY (`contact_id`, `tag_id`),
  KEY `ix_ct_tag` (`tag_id`),
  CONSTRAINT `fk_contact_tags_contact_id` FOREIGN KEY (`contact_id`) REFERENCES `contacts` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_contact_tags_tag_id` FOREIGN KEY (`tag_id`) REFERENCES `tags` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Many-to-many link between contacts and tags';

-- Saved audience filters reused by campaigns
CREATE TABLE `segments` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `name` VARCHAR(60) NOT NULL,
  `filter_json` JSON NOT NULL COMMENT 'Tags, city, opt-in, dates',
  `contact_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Cached, refreshed nightly',
  `created_by` BIGINT UNSIGNED NULL COMMENT 'FK users',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_seg_name` (`workspace_id`, `name`),
  CONSTRAINT `fk_segments_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_segments_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Saved audience filters reused by campaigns';

-- Excel/CSV import jobs and their results
CREATE TABLE `contact_imports` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `file_name` VARCHAR(255) NOT NULL COMMENT 'Original name',
  `file_url` VARCHAR(500) NOT NULL COMMENT 'Object storage',
  `column_mapping` JSON NOT NULL COMMENT 'File column -> field',
  `duplicate_mode` ENUM('SKIP','UPDATE') NOT NULL DEFAULT 'SKIP',
  `consent_confirmed` TINYINT(1) NOT NULL COMMENT 'User ticked consent box',
  `total_rows` INT UNSIGNED NOT NULL DEFAULT 0,
  `imported_rows` INT UNSIGNED NOT NULL DEFAULT 0,
  `updated_rows` INT UNSIGNED NOT NULL DEFAULT 0,
  `failed_rows` INT UNSIGNED NOT NULL DEFAULT 0,
  `status` ENUM('UPLOADED','PROCESSING','COMPLETED','FAILED') NOT NULL DEFAULT 'UPLOADED',
  `error_report_url` VARCHAR(500) NULL COMMENT 'CSV of failed rows',
  `created_by` BIGINT UNSIGNED NULL COMMENT 'FK users',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `completed_at` DATETIME(3) NULL,
  PRIMARY KEY (`id`),
  KEY `ix_import_ws` (`workspace_id`, `created_at`),
  CONSTRAINT `fk_contact_imports_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_contact_imports_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Excel/CSV import jobs and their results';

-- Connected WhatsApp Business number (one per workspace)
CREATE TABLE `wa_accounts` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `waba_id` VARCHAR(30) NOT NULL COMMENT 'WhatsApp Business Account id',
  `phone_number_id` VARCHAR(30) NOT NULL COMMENT 'Cloud API sender id',
  `display_phone` VARCHAR(20) NOT NULL,
  `verified_name` VARCHAR(100) NULL COMMENT 'Name shown to customers',
  `access_token_enc` VARBINARY(2048) NOT NULL COMMENT 'AES-256-GCM encrypted',
  `quality_rating` ENUM('GREEN','YELLOW','RED','UNKNOWN') NOT NULL DEFAULT 'UNKNOWN' COMMENT 'From webhook',
  `messaging_limit` VARCHAR(20) NULL COMMENT 'Current Meta tier',
  `status` ENUM('PENDING','CONNECTED','DISCONNECTED','RESTRICTED') NOT NULL DEFAULT 'PENDING',
  `connected_at` DATETIME(3) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wa_ws` (`workspace_id`),
  UNIQUE KEY `uk_wa_phone` (`phone_number_id`),
  CONSTRAINT `fk_wa_accounts_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Connected WhatsApp Business number (one per workspace)';

-- WhatsApp message templates and Meta approval status
CREATE TABLE `wa_templates` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `wa_account_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK wa_accounts',
  `meta_template_id` VARCHAR(40) NULL COMMENT 'Set after submit',
  `name` VARCHAR(512) NOT NULL COMMENT 'lower_snake_case',
  `language` VARCHAR(10) NOT NULL COMMENT 'en, en_US, ta',
  `category` ENUM('MARKETING','UTILITY','AUTHENTICATION') NOT NULL,
  `header_type` ENUM('NONE','TEXT','IMAGE','VIDEO','DOCUMENT') NOT NULL DEFAULT 'NONE',
  `header_text` VARCHAR(60) NULL,
  `header_media_url` VARCHAR(500) NULL COMMENT 'Sample for review',
  `body` VARCHAR(1024) NOT NULL COMMENT 'With {{1}}..{{n}}',
  `footer` VARCHAR(60) NULL,
  `buttons_json` JSON NULL COMMENT 'Quick reply / URL / phone',
  `variable_count` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `sample_values_json` JSON NULL COMMENT 'Required by Meta',
  `status` ENUM('DRAFT','PENDING','APPROVED','REJECTED','PAUSED','DISABLED') NOT NULL DEFAULT 'DRAFT',
  `rejection_reason` VARCHAR(500) NULL,
  `created_by` BIGINT UNSIGNED NULL COMMENT 'FK users',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tpl` (`wa_account_id`, `name`, `language`),
  KEY `ix_tpl_ws` (`workspace_id`, `status`),
  CONSTRAINT `fk_wa_templates_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_wa_templates_wa_account_id` FOREIGN KEY (`wa_account_id`) REFERENCES `wa_accounts` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_wa_templates_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='WhatsApp message templates and Meta approval status';

-- Public lead capture forms
CREATE TABLE `lead_forms` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `name` VARCHAR(60) NOT NULL COMMENT 'Internal name',
  `slug` VARCHAR(50) NOT NULL COMMENT 'Public URL part, global unique',
  `title` VARCHAR(100) NOT NULL,
  `description` VARCHAR(500) NULL,
  `fields_json` JSON NOT NULL COMMENT 'Max 10 fields',
  `consent_text` VARCHAR(300) NOT NULL COMMENT 'Shown with checkbox',
  `success_message` VARCHAR(200) NOT NULL,
  `auto_reply_template_id` BIGINT UNSIGNED NULL COMMENT 'FK wa_templates',
  `default_tag_id` BIGINT UNSIGNED NULL COMMENT 'FK tags',
  `is_active` TINYINT(1) NOT NULL DEFAULT 1,
  `submissions_count` INT UNSIGNED NOT NULL DEFAULT 0,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_form_slug` (`slug`),
  CONSTRAINT `fk_lead_forms_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_lead_forms_auto_reply_template_id` FOREIGN KEY (`auto_reply_template_id`) REFERENCES `wa_templates` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_lead_forms_default_tag_id` FOREIGN KEY (`default_tag_id`) REFERENCES `tags` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Public lead capture forms';

-- Enquiries and their follow-up pipeline
CREATE TABLE `leads` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `contact_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK contacts',
  `source` ENUM('FORM','WHATSAPP','CALL','WALK_IN','INSTAGRAM','FACEBOOK','REFERRAL','OTHER') NOT NULL,
  `lead_form_id` BIGINT UNSIGNED NULL COMMENT 'FK lead_forms',
  `status` ENUM('NEW','CONTACTED','INTERESTED','WON','LOST') NOT NULL DEFAULT 'NEW',
  `assigned_to` BIGINT UNSIGNED NULL COMMENT 'FK users',
  `expected_value` DECIMAL(12,2) NULL COMMENT 'INR',
  `follow_up_at` DATETIME(3) NULL COMMENT 'Reminder time',
  `lost_reason` VARCHAR(255) NULL COMMENT 'Required when LOST',
  `requirement` VARCHAR(1000) NULL COMMENT 'What the customer asked',
  `created_by` BIGINT UNSIGNED NULL COMMENT 'FK users',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  `deleted_at` DATETIME(3) NULL COMMENT 'Soft delete marker',
  PRIMARY KEY (`id`),
  KEY `ix_lead_status` (`workspace_id`, `status`),
  KEY `ix_lead_follow` (`workspace_id`, `follow_up_at`),
  CONSTRAINT `fk_leads_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_leads_contact_id` FOREIGN KEY (`contact_id`) REFERENCES `contacts` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_leads_lead_form_id` FOREIGN KEY (`lead_form_id`) REFERENCES `lead_forms` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_leads_assigned_to` FOREIGN KEY (`assigned_to`) REFERENCES `users` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_leads_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Enquiries and their follow-up pipeline';

-- Timeline of notes, calls and status changes on a lead
CREATE TABLE `lead_activities` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `lead_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK leads',
  `type` ENUM('NOTE','CALL','MESSAGE','STATUS_CHANGE','FOLLOW_UP') NOT NULL,
  `description` VARCHAR(1000) NOT NULL,
  `created_by` BIGINT UNSIGNED NULL COMMENT 'FK users',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  PRIMARY KEY (`id`),
  KEY `ix_la_lead` (`lead_id`, `created_at`),
  CONSTRAINT `fk_lead_activities_lead_id` FOREIGN KEY (`lead_id`) REFERENCES `leads` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_lead_activities_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Timeline of notes, calls and status changes on a lead';

-- Bulk WhatsApp sends to an audience
CREATE TABLE `campaigns` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `name` VARCHAR(100) NOT NULL COMMENT 'Unique per workspace',
  `template_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK wa_templates',
  `audience_type` ENUM('ALL_OPTED_IN','TAGS','SEGMENT') NOT NULL,
  `audience_json` JSON NULL COMMENT 'Tag ids or segment id',
  `variable_mapping_json` JSON NULL COMMENT '{{n}} -> field or fixed text',
  `header_media_url` VARCHAR(500) NULL,
  `scheduled_at` DATETIME(3) NULL COMMENT 'Null = send now',
  `status` ENUM('DRAFT','SCHEDULED','QUEUED','SENDING','PAUSED','COMPLETED','CANCELLED','FAILED') NOT NULL DEFAULT 'DRAFT',
  `total_recipients` INT UNSIGNED NOT NULL DEFAULT 0,
  `sent_count` INT UNSIGNED NOT NULL DEFAULT 0,
  `delivered_count` INT UNSIGNED NOT NULL DEFAULT 0,
  `read_count` INT UNSIGNED NOT NULL DEFAULT 0,
  `failed_count` INT UNSIGNED NOT NULL DEFAULT 0,
  `replied_count` INT UNSIGNED NOT NULL DEFAULT 0,
  `estimated_cost` DECIMAL(12,4) NOT NULL DEFAULT 0.0000 COMMENT 'Reserved from wallet',
  `actual_cost` DECIMAL(12,4) NOT NULL DEFAULT 0.0000,
  `started_at` DATETIME(3) NULL,
  `completed_at` DATETIME(3) NULL,
  `created_by` BIGINT UNSIGNED NULL COMMENT 'FK users',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_camp_name` (`workspace_id`, `name`),
  KEY `ix_camp_status` (`workspace_id`, `status`),
  KEY `ix_camp_sched` (`status`, `scheduled_at`),
  CONSTRAINT `fk_campaigns_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_campaigns_template_id` FOREIGN KEY (`template_id`) REFERENCES `wa_templates` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_campaigns_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Bulk WhatsApp sends to an audience';

-- A/B test variants of a campaign (2 to 3 templates)
CREATE TABLE `campaign_variants` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `campaign_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK campaigns',
  `label` CHAR(1) NOT NULL COMMENT 'A, B or C',
  `template_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK wa_templates',
  `split_percent` TINYINT UNSIGNED NOT NULL COMMENT '10 to 90; variants total 100',
  `sent_count` INT UNSIGNED NOT NULL DEFAULT 0,
  `read_count` INT UNSIGNED NOT NULL DEFAULT 0,
  `click_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'From tracked links',
  `is_winner` TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cv` (`campaign_id`, `label`),
  CONSTRAINT `fk_campaign_variants_campaign_id` FOREIGN KEY (`campaign_id`) REFERENCES `campaigns` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_campaign_variants_template_id` FOREIGN KEY (`template_id`) REFERENCES `wa_templates` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='A/B test variants of a campaign (2 to 3 templates)';

-- One row per recipient per campaign (fastest-growing table)
CREATE TABLE `campaign_messages` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `campaign_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK campaigns',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `contact_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK contacts',
  `variant_id` BIGINT UNSIGNED NULL COMMENT 'FK campaign_variants (A/B)',
  `phone` VARCHAR(15) NOT NULL COMMENT 'Copied at queue time',
  `wa_message_id` VARCHAR(80) NULL COMMENT 'wamid from Meta',
  `status` ENUM('QUEUED','SENT','DELIVERED','READ','FAILED') NOT NULL DEFAULT 'QUEUED',
  `error_code` VARCHAR(20) NULL COMMENT 'Meta error code',
  `error_message` VARCHAR(255) NULL,
  `cost` DECIMAL(10,4) NOT NULL DEFAULT 0.0000 COMMENT 'Charged on delivery',
  `queued_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `sent_at` DATETIME(3) NULL,
  `delivered_at` DATETIME(3) NULL,
  `read_at` DATETIME(3) NULL,
  `failed_at` DATETIME(3) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cm_contact` (`campaign_id`, `contact_id`),
  UNIQUE KEY `uk_cm_wamid` (`wa_message_id`),
  KEY `ix_cm_status` (`campaign_id`, `status`),
  KEY `ix_cm_contact` (`workspace_id`, `contact_id`),
  CONSTRAINT `fk_campaign_messages_campaign_id` FOREIGN KEY (`campaign_id`) REFERENCES `campaigns` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_campaign_messages_contact_id` FOREIGN KEY (`contact_id`) REFERENCES `contacts` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_campaign_messages_variant_id` FOREIGN KEY (`variant_id`) REFERENCES `campaign_variants` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='One row per recipient per campaign (fastest-growing table)';

-- Customer-owned domains for white-label, landing pages and short links
CREATE TABLE `custom_domains` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `domain` VARCHAR(253) NOT NULL COMMENT 'Lower-case host, e.g. offers.shop.in',
  `purpose` ENUM('WHITE_LABEL','LANDING_PAGES','LINKS') NOT NULL,
  `verification_token` CHAR(32) NOT NULL COMMENT 'Value for the TXT record',
  `dns_status` ENUM('PENDING','VERIFIED','FAILED') NOT NULL DEFAULT 'PENDING' COMMENT 'TXT + CNAME/A check',
  `ssl_status` ENUM('PENDING','ACTIVE','FAILED','EXPIRING') NOT NULL DEFAULT 'PENDING' COMMENT 'Auto Let''s Encrypt',
  `ssl_expires_at` DATETIME(3) NULL,
  `is_primary` TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Default for its purpose',
  `last_checked_at` DATETIME(3) NULL COMMENT 'Re-checked every 6 h',
  `failure_reason` VARCHAR(255) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_domain` (`domain`),
  KEY `ix_dom_ws` (`workspace_id`, `purpose`),
  CONSTRAINT `fk_custom_domains_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Customer-owned domains for white-label, landing pages and short links';

-- Customer mobile apps to promote
CREATE TABLE `apps` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `name` VARCHAR(100) NOT NULL,
  `android_package` VARCHAR(150) NULL COMMENT 'e.g. in.shop.app',
  `play_store_url` VARCHAR(500) NULL,
  `ios_app_id` VARCHAR(12) NULL COMMENT 'Numeric App Store id',
  `app_store_url` VARCHAR(500) NULL,
  `web_fallback_url` VARCHAR(500) NOT NULL COMMENT 'Desktop / other devices',
  `icon_url` VARCHAR(500) NULL COMMENT '512 x 512',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_app_pkg` (`workspace_id`, `android_package`),
  CONSTRAINT `fk_apps_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Customer mobile apps to promote';

-- Tracked short links and app smart links (device-aware redirect)
CREATE TABLE `short_links` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `domain_id` BIGINT UNSIGNED NULL COMMENT 'FK custom_domains; null = platform domain',
  `code` VARCHAR(30) NOT NULL COMMENT 'Path after the domain',
  `type` ENUM('URL','APP') NOT NULL COMMENT 'APP = smart store redirect',
  `app_id` BIGINT UNSIGNED NULL COMMENT 'FK apps (type APP)',
  `destination_url` VARCHAR(2000) NULL COMMENT 'Type URL',
  `utm_source` VARCHAR(100) NULL,
  `utm_medium` VARCHAR(100) NULL,
  `utm_campaign` VARCHAR(100) NULL COMMENT 'Passed to Play install referrer',
  `campaign_id` BIGINT UNSIGNED NULL COMMENT 'FK campaigns (auto-created links)',
  `click_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Cached',
  `unique_click_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Cached',
  `is_active` TINYINT(1) NOT NULL DEFAULT 1,
  `expires_at` DATETIME(3) NULL,
  `created_by` BIGINT UNSIGNED NULL COMMENT 'FK users',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_link_code` (`code`),
  KEY `ix_link_ws` (`workspace_id`, `created_at`),
  CONSTRAINT `fk_short_links_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_short_links_domain_id` FOREIGN KEY (`domain_id`) REFERENCES `custom_domains` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_short_links_app_id` FOREIGN KEY (`app_id`) REFERENCES `apps` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_short_links_campaign_id` FOREIGN KEY (`campaign_id`) REFERENCES `campaigns` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_short_links_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Tracked short links and app smart links (device-aware redirect)';

-- Every click on a short / smart link
CREATE TABLE `link_clicks` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `short_link_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK short_links',
  `contact_id` BIGINT UNSIGNED NULL COMMENT 'Known when sent in a campaign',
  `platform` ENUM('ANDROID','IOS','DESKTOP','OTHER') NOT NULL COMMENT 'From user agent',
  `redirected_to` ENUM('PLAY_STORE','APP_STORE','WEB','FALLBACK') NOT NULL,
  `country` CHAR(2) NULL COMMENT 'ISO code',
  `city` VARCHAR(60) NULL,
  `referrer` VARCHAR(255) NULL,
  `ip_hash` CHAR(64) NOT NULL COMMENT 'SHA-256 + salt, no raw IP',
  `is_bot` TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Excluded from stats',
  `clicked_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  KEY `ix_click_link` (`short_link_id`, `clicked_at`),
  CONSTRAINT `fk_link_clicks_short_link_id` FOREIGN KEY (`short_link_id`) REFERENCES `short_links` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_link_clicks_contact_id` FOREIGN KEY (`contact_id`) REFERENCES `contacts` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Every click on a short / smart link';

-- Simple landing pages on platform or custom domains
CREATE TABLE `landing_pages` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `domain_id` BIGINT UNSIGNED NULL COMMENT 'FK custom_domains',
  `path` VARCHAR(100) NOT NULL COMMENT 'e.g. diwali-offer',
  `title` VARCHAR(100) NOT NULL,
  `blocks_json` JSON NOT NULL COMMENT 'Ordered blocks, max 30',
  `lead_form_id` BIGINT UNSIGNED NULL COMMENT 'FK lead_forms (embedded)',
  `seo_title` VARCHAR(60) NULL,
  `seo_description` VARCHAR(160) NULL,
  `status` ENUM('DRAFT','PUBLISHED','UNPUBLISHED') NOT NULL DEFAULT 'DRAFT',
  `published_at` DATETIME(3) NULL,
  `created_by` BIGINT UNSIGNED NULL COMMENT 'FK users',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lp_path` (`workspace_id`, `domain_id`, `path`),
  CONSTRAINT `fk_landing_pages_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_landing_pages_domain_id` FOREIGN KEY (`domain_id`) REFERENCES `custom_domains` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_landing_pages_lead_form_id` FOREIGN KEY (`lead_form_id`) REFERENCES `lead_forms` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_landing_pages_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Simple landing pages on platform or custom domains';

-- Trigger-based flows: birthday, drip, keyword reply, follow-up
CREATE TABLE `automations` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `name` VARCHAR(100) NOT NULL COMMENT 'Unique per workspace',
  `trigger_type` ENUM('CONTACT_CREATED','TAG_ADDED','BIRTHDAY','FORM_SUBMITTED','KEYWORD','LEAD_STATUS','LINK_CLICKED') NOT NULL,
  `trigger_config` JSON NOT NULL COMMENT 'e.g. tag id, keyword, form id',
  `steps_json` JSON NOT NULL COMMENT '1 to 20 steps',
  `allow_reentry_days` SMALLINT UNSIGNED NOT NULL DEFAULT 30 COMMENT '0 = never re-enter',
  `status` ENUM('DRAFT','ACTIVE','PAUSED') NOT NULL DEFAULT 'DRAFT',
  `runs_count` INT UNSIGNED NOT NULL DEFAULT 0,
  `created_by` BIGINT UNSIGNED NULL COMMENT 'FK users',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_auto_name` (`workspace_id`, `name`),
  KEY `ix_auto_trigger` (`workspace_id`, `trigger_type`, `status`),
  CONSTRAINT `fk_automations_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_automations_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Trigger-based flows: birthday, drip, keyword reply, follow-up';

-- One contact's progress through an automation
CREATE TABLE `automation_runs` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `automation_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK automations',
  `contact_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK contacts',
  `current_step` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `status` ENUM('RUNNING','WAITING','COMPLETED','EXITED','FAILED') NOT NULL DEFAULT 'RUNNING',
  `next_run_at` DATETIME(3) NULL COMMENT 'When a WAIT ends',
  `exit_reason` VARCHAR(100) NULL COMMENT 'e.g. opted out, replied',
  `started_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `finished_at` DATETIME(3) NULL,
  PRIMARY KEY (`id`),
  KEY `ix_run_due` (`status`, `next_run_at`),
  KEY `ix_run_contact` (`automation_id`, `contact_id`),
  CONSTRAINT `fk_automation_runs_automation_id` FOREIGN KEY (`automation_id`) REFERENCES `automations` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_automation_runs_contact_id` FOREIGN KEY (`contact_id`) REFERENCES `contacts` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='One contact''s progress through an automation';

-- Developer API keys for integrations (POS, website, CRM)
CREATE TABLE `api_keys` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `name` VARCHAR(50) NOT NULL,
  `key_prefix` CHAR(8) NOT NULL COMMENT 'Shown in list, e.g. mp_live_',
  `key_hash` CHAR(64) NOT NULL COMMENT 'SHA-256; full key shown once',
  `scopes` JSON NOT NULL COMMENT 'e.g. ["contacts:write"]',
  `last_used_at` DATETIME(3) NULL,
  `expires_at` DATETIME(3) NULL,
  `revoked_at` DATETIME(3) NULL,
  `created_by` BIGINT UNSIGNED NULL COMMENT 'FK users',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_api_hash` (`key_hash`),
  CONSTRAINT `fk_api_keys_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_api_keys_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Developer API keys for integrations (POS, website, CRM)';

-- Inbox thread per contact; tracks the free 24-hour reply window
CREATE TABLE `conversations` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `contact_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK contacts',
  `last_inbound_at` DATETIME(3) NULL COMMENT 'Free-form reply allowed for 24 h after',
  `last_message_preview` VARCHAR(200) NULL,
  `unread_count` INT UNSIGNED NOT NULL DEFAULT 0,
  `assigned_to` BIGINT UNSIGNED NULL COMMENT 'FK users',
  `status` ENUM('OPEN','CLOSED') NOT NULL DEFAULT 'OPEN',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conv` (`workspace_id`, `contact_id`),
  KEY `ix_conv_recent` (`workspace_id`, `status`, `updated_at`),
  CONSTRAINT `fk_conversations_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_conversations_contact_id` FOREIGN KEY (`contact_id`) REFERENCES `contacts` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_conversations_assigned_to` FOREIGN KEY (`assigned_to`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Inbox thread per contact; tracks the free 24-hour reply window';

-- Individual inbox messages, in and out
CREATE TABLE `messages` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `conversation_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK conversations',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `direction` ENUM('IN','OUT') NOT NULL,
  `wa_message_id` VARCHAR(80) NULL COMMENT 'wamid, for dedupe',
  `type` ENUM('TEXT','IMAGE','DOCUMENT','AUDIO','VIDEO','TEMPLATE','BUTTON','LOCATION') NOT NULL,
  `body` TEXT NULL COMMENT 'Max 4096 chars',
  `media_url` VARCHAR(500) NULL,
  `status` ENUM('RECEIVED','SENT','DELIVERED','READ','FAILED') NOT NULL,
  `sent_by` BIGINT UNSIGNED NULL COMMENT 'FK users (OUT only)',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_msg_wamid` (`wa_message_id`),
  KEY `ix_msg_conv` (`conversation_id`, `created_at`),
  CONSTRAINT `fk_messages_conversation_id` FOREIGN KEY (`conversation_id`) REFERENCES `conversations` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_messages_sent_by` FOREIGN KEY (`sent_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Individual inbox messages, in and out';

-- Google review link and auto-request rule per workspace
CREATE TABLE `review_settings` (
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'PK + FK workspaces',
  `google_review_url` VARCHAR(500) NOT NULL COMMENT 'VR-08 + Google host',
  `template_id` BIGINT UNSIGNED NULL COMMENT 'FK wa_templates',
  `auto_send` TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Send after tag/visit',
  `trigger_tag_id` BIGINT UNSIGNED NULL COMMENT 'FK tags',
  `delay_minutes` INT UNSIGNED NOT NULL DEFAULT 60 COMMENT '15 to 10080',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  PRIMARY KEY (`workspace_id`),
  CONSTRAINT `fk_review_settings_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_review_settings_template_id` FOREIGN KEY (`template_id`) REFERENCES `wa_templates` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_review_settings_trigger_tag_id` FOREIGN KEY (`trigger_tag_id`) REFERENCES `tags` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Google review link and auto-request rule per workspace';

-- Each review link sent to a customer and whether it was opened
CREATE TABLE `review_requests` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `contact_id` BIGINT UNSIGNED NULL COMMENT 'Null for QR scans',
  `link_code` CHAR(10) NOT NULL COMMENT 'Short redirect code',
  `channel` ENUM('WHATSAPP','QR','LINK') NOT NULL,
  `sent_at` DATETIME(3) NULL,
  `clicked_at` DATETIME(3) NULL COMMENT 'First open',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rr_code` (`link_code`),
  KEY `ix_rr_ws` (`workspace_id`, `created_at`),
  CONSTRAINT `fk_review_requests_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_review_requests_contact_id` FOREIGN KEY (`contact_id`) REFERENCES `contacts` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Each review link sent to a customer and whether it was opened';

-- Daily snapshot of Google rating and review count
CREATE TABLE `review_stats` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `captured_on` DATE NOT NULL,
  `rating` DECIMAL(2,1) NOT NULL COMMENT '1.0 to 5.0',
  `review_count` INT UNSIGNED NOT NULL,
  `source` ENUM('API','MANUAL') NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_rs_day` (`workspace_id`, `captured_on`),
  CONSTRAINT `fk_review_stats_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Daily snapshot of Google rating and review count';

-- Connected Facebook Pages and Instagram Business accounts
CREATE TABLE `social_accounts` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `platform` ENUM('FACEBOOK','INSTAGRAM') NOT NULL,
  `external_id` VARCHAR(40) NOT NULL COMMENT 'Page id / IG user id',
  `name` VARCHAR(100) NOT NULL,
  `access_token_enc` VARBINARY(2048) NOT NULL COMMENT 'AES-256-GCM',
  `token_expires_at` DATETIME(3) NULL,
  `status` ENUM('CONNECTED','EXPIRED','DISCONNECTED') NOT NULL DEFAULT 'CONNECTED',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sa` (`workspace_id`, `platform`, `external_id`),
  CONSTRAINT `fk_social_accounts_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Connected Facebook Pages and Instagram Business accounts';

-- Scheduled or published social posts
CREATE TABLE `social_posts` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `caption` TEXT NULL COMMENT 'Max 2,200 chars (Instagram)',
  `post_type` ENUM('IMAGE','CAROUSEL','VIDEO','TEXT') NOT NULL,
  `media_json` JSON NULL COMMENT 'Ordered media URLs',
  `scheduled_at` DATETIME(3) NULL COMMENT 'Null = publish now',
  `status` ENUM('DRAFT','SCHEDULED','PUBLISHING','PUBLISHED','PARTIAL','FAILED','CANCELLED') NOT NULL DEFAULT 'DRAFT',
  `created_by` BIGINT UNSIGNED NULL COMMENT 'FK users',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  PRIMARY KEY (`id`),
  KEY `ix_sp_sched` (`status`, `scheduled_at`),
  KEY `ix_sp_ws` (`workspace_id`, `scheduled_at`),
  CONSTRAINT `fk_social_posts_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_social_posts_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Scheduled or published social posts';

-- Per-account publish result of a post
CREATE TABLE `social_post_targets` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `post_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK social_posts',
  `social_account_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK social_accounts',
  `platform_post_id` VARCHAR(80) NULL,
  `status` ENUM('PENDING','PUBLISHED','FAILED') NOT NULL DEFAULT 'PENDING',
  `error_message` VARCHAR(500) NULL,
  `published_at` DATETIME(3) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_spt` (`post_id`, `social_account_id`),
  CONSTRAINT `fk_social_post_targets_post_id` FOREIGN KEY (`post_id`) REFERENCES `social_posts` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_social_post_targets_social_account_id` FOREIGN KEY (`social_account_id`) REFERENCES `social_accounts` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Per-account publish result of a post';

-- AI caption / message requests (for usage limits and history)
CREATE TABLE `ai_generations` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `user_id` BIGINT UNSIGNED NULL COMMENT 'FK users',
  `type` ENUM('CAPTION','OFFER_MESSAGE','REPLY','HASHTAGS') NOT NULL,
  `language` ENUM('en','ta') NOT NULL,
  `prompt` VARCHAR(1000) NOT NULL,
  `output` TEXT NOT NULL,
  `tokens_used` INT UNSIGNED NOT NULL DEFAULT 0,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  PRIMARY KEY (`id`),
  KEY `ix_ai_ws` (`workspace_id`, `created_at`),
  CONSTRAINT `fk_ai_generations_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_ai_generations_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='AI caption / message requests (for usage limits and history)';

-- Database job queue (replaces a separate worker service)
CREATE TABLE `jobs` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `type` VARCHAR(40) NOT NULL COMMENT 'CAMPAIGN_BATCH, POST_PUBLISH, IMPORT, ...',
  `payload` JSON NOT NULL,
  `status` ENUM('PENDING','RUNNING','DONE','FAILED','DEAD') NOT NULL DEFAULT 'PENDING',
  `priority` TINYINT UNSIGNED NOT NULL DEFAULT 5 COMMENT '1 = highest',
  `run_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Not before',
  `attempts` TINYINT UNSIGNED NOT NULL DEFAULT 0,
  `max_attempts` TINYINT UNSIGNED NOT NULL DEFAULT 5 COMMENT 'Then DEAD',
  `locked_by` VARCHAR(64) NULL COMMENT 'Instance id',
  `locked_at` DATETIME(3) NULL,
  `last_error` VARCHAR(1000) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  PRIMARY KEY (`id`),
  KEY `ix_jobs_pick` (`status`, `run_at`, `priority`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Database job queue (replaces a separate worker service)';

-- Raw Meta and RupiyaX webhooks; dedupe and replay
CREATE TABLE `webhook_events` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `source` ENUM('META','RUPIYAX','RAZORPAY') NOT NULL,
  `event_id` VARCHAR(100) NOT NULL COMMENT 'Provider id or payload hash',
  `event_type` VARCHAR(60) NOT NULL,
  `payload` JSON NOT NULL,
  `signature_valid` TINYINT(1) NOT NULL,
  `processed_at` DATETIME(3) NULL,
  `error` VARCHAR(500) NULL,
  `received_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_wh_event` (`source`, `event_id`),
  KEY `ix_wh_pending` (`processed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Raw Meta and RupiyaX webhooks; dedupe and replay';

-- In-app notifications (follow-ups due, campaign done, low wallet)
CREATE TABLE `notifications` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `user_id` BIGINT UNSIGNED NULL COMMENT 'Null = all members',
  `type` VARCHAR(40) NOT NULL,
  `title` VARCHAR(120) NOT NULL,
  `body` VARCHAR(500) NULL,
  `link` VARCHAR(300) NULL COMMENT 'In-app route',
  `read_at` DATETIME(3) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  PRIMARY KEY (`id`),
  KEY `ix_notif_user` (`user_id`, `read_at`),
  CONSTRAINT `fk_notifications_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_notifications_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='In-app notifications (follow-ups due, campaign done, low wallet)';

-- Who changed what, for support and disputes
CREATE TABLE `audit_logs` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NULL COMMENT 'Null for platform actions',
  `user_id` BIGINT UNSIGNED NULL,
  `action` VARCHAR(60) NOT NULL COMMENT 'e.g. CAMPAIGN_SENT',
  `entity_type` VARCHAR(40) NOT NULL,
  `entity_id` BIGINT UNSIGNED NULL,
  `old_values` JSON NULL,
  `new_values` JSON NULL,
  `ip_address` VARCHAR(45) NULL,
  `user_agent` VARCHAR(255) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  PRIMARY KEY (`id`),
  KEY `ix_audit_ws` (`workspace_id`, `created_at`),
  KEY `ix_audit_entity` (`entity_type`, `entity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Who changed what, for support and disputes';

-- Lock table so @Scheduled jobs run once across instances (ShedLock)
CREATE TABLE `shedlock` (
  `name` VARCHAR(64) NOT NULL COMMENT 'Lock name',
  `lock_until` TIMESTAMP(3) NOT NULL,
  `locked_at` TIMESTAMP(3) NOT NULL,
  `locked_by` VARCHAR(255) NOT NULL,
  PRIMARY KEY (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Lock table so @Scheduled jobs run once across instances (ShedLock)';

-- Paid promotion offers: advertiser pays per install / sign-up / purchase, promoter earns
CREATE TABLE `offers` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NOT NULL COMMENT 'Tenant (FK workspaces)',
  `app_id` BIGINT UNSIGNED NULL COMMENT 'FK apps (app offers)',
  `name` VARCHAR(100) NOT NULL,
  `event_type` ENUM('INSTALL','REGISTRATION','PURCHASE','LEAD','CUSTOM') NOT NULL COMMENT 'What counts as a conversion',
  `event_name` VARCHAR(50) NULL COMMENT 'For CUSTOM, e.g. kyc_done',
  `landing_url` VARCHAR(2000) NOT NULL COMMENT 'Must contain {click_id}',
  `advertiser_price` DECIMAL(10,2) NOT NULL COMMENT 'Charged to advertiser wallet',
  `promoter_payout` DECIMAL(10,2) NOT NULL COMMENT 'Credited to promoter',
  `daily_cap` INT UNSIGNED NULL COMMENT 'Max conversions per day',
  `total_cap` INT UNSIGNED NULL COMMENT 'Max conversions overall',
  `attribution_window_hours` SMALLINT UNSIGNED NOT NULL DEFAULT 168 COMMENT 'Click to conversion, 1 to 720',
  `min_ctit_seconds` SMALLINT UNSIGNED NOT NULL DEFAULT 10 COMMENT 'Faster installs flagged as fraud',
  `incentive_allowed` TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'May promoters offer rewards to users',
  `per_promoter_daily_cap` INT UNSIGNED NULL,
  `hold_days` TINYINT UNSIGNED NOT NULL DEFAULT 7 COMMENT 'Pending before payable',
  `postback_secret_enc` VARBINARY(256) NOT NULL COMMENT 'HMAC key, AES encrypted',
  `allowed_ips` JSON NULL COMMENT 'Postback IP allow-list',
  `geo` JSON NULL COMMENT 'Allowed states / countries',
  `status` ENUM('DRAFT','ACTIVE','PAUSED','ENDED') NOT NULL DEFAULT 'DRAFT',
  `starts_at` DATETIME(3) NULL,
  `ends_at` DATETIME(3) NULL,
  `created_by` BIGINT UNSIGNED NULL COMMENT 'FK users',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  PRIMARY KEY (`id`),
  KEY `ix_offer_status` (`status`, `workspace_id`),
  CONSTRAINT `fk_offers_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_offers_app_id` FOREIGN KEY (`app_id`) REFERENCES `apps` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_offers_created_by` FOREIGN KEY (`created_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Paid promotion offers: advertiser pays per install / sign-up / purchase, promoter earns';

-- People who share offers and get paid (earn wallet)
CREATE TABLE `promoters` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK users',
  `referral_code` VARCHAR(12) NOT NULL COMMENT 'Public code in links',
  `referred_by_id` BIGINT UNSIGNED NULL COMMENT 'FK promoters (refer-a-promoter)',
  `kyc_status` ENUM('NOT_STARTED','PENDING','VERIFIED','REJECTED') NOT NULL DEFAULT 'NOT_STARTED' COMMENT 'Needed before payout',
  `pan_enc` VARBINARY(256) NULL COMMENT 'AES-256-GCM',
  `pan_last4` CHAR(4) NULL COMMENT 'For display only',
  `legal_name` VARCHAR(100) NULL COMMENT 'As on PAN',
  `balance_pending` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT 'On hold',
  `balance_available` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT 'Can withdraw',
  `lifetime_earned` DECIMAL(14,2) NOT NULL DEFAULT 0.00,
  `postback_url` VARCHAR(2000) NULL COMMENT 'Optional S2S postback to promoter / network',
  `risk_score` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 to 100',
  `status` ENUM('ACTIVE','ON_HOLD','BLOCKED') NOT NULL DEFAULT 'ACTIVE',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_prom_user` (`user_id`),
  UNIQUE KEY `uk_prom_code` (`referral_code`),
  CONSTRAINT `fk_promoters_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_promoters_referred_by_id` FOREIGN KEY (`referred_by_id`) REFERENCES `promoters` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='People who share offers and get paid (earn wallet)';

-- Each click on an offer link; click_id is passed to the app / advertiser
CREATE TABLE `tracking_clicks` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `click_id` CHAR(26) NOT NULL COMMENT 'ULID, unique',
  `offer_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK offers',
  `promoter_id` BIGINT UNSIGNED NULL COMMENT 'FK promoters',
  `short_link_id` BIGINT UNSIGNED NULL COMMENT 'FK short_links',
  `sub1` VARCHAR(100) NULL COMMENT 'Promoter''s own tracking values',
  `sub2` VARCHAR(100) NULL,
  `platform` ENUM('ANDROID','IOS','DESKTOP','OTHER') NOT NULL,
  `ip_hash` CHAR(64) NOT NULL,
  `user_agent` VARCHAR(255) NULL,
  `is_bot` TINYINT(1) NOT NULL DEFAULT 0,
  `is_vpn` TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'From IP intelligence lookup',
  `clicked_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_click_id` (`click_id`),
  KEY `ix_tc_offer` (`offer_id`, `clicked_at`),
  KEY `ix_tc_prom` (`promoter_id`, `clicked_at`),
  CONSTRAINT `fk_tracking_clicks_offer_id` FOREIGN KEY (`offer_id`) REFERENCES `offers` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_tracking_clicks_promoter_id` FOREIGN KEY (`promoter_id`) REFERENCES `promoters` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_tracking_clicks_short_link_id` FOREIGN KEY (`short_link_id`) REFERENCES `short_links` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Each click on an offer link; click_id is passed to the app / advertiser';

-- Install / sign-up / purchase confirmed by a postback
CREATE TABLE `conversions` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `offer_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK offers',
  `click_id` CHAR(26) NOT NULL COMMENT 'From postback',
  `promoter_id` BIGINT UNSIGNED NULL COMMENT 'FK promoters',
  `event_type` VARCHAR(30) NOT NULL,
  `external_txn_id` VARCHAR(100) NOT NULL COMMENT 'Advertiser''s unique id (dedupe)',
  `sale_amount` DECIMAL(12,2) NULL COMMENT 'For PURCHASE offers',
  `advertiser_price` DECIMAL(10,2) NOT NULL COMMENT 'Copied from offer',
  `promoter_payout` DECIMAL(10,2) NOT NULL COMMENT 'Copied from offer',
  `status` ENUM('PENDING','APPROVED','REJECTED','REVERSED','PAID') NOT NULL DEFAULT 'PENDING',
  `reject_reason` VARCHAR(255) NULL,
  `fraud_flags` JSON NULL COMMENT 'e.g. ["LOW_CTIT","SAME_IP"]',
  `seconds_to_convert` INT UNSIGNED NOT NULL COMMENT 'Click to install time (fraud signal)',
  `hold_until` DATETIME(3) NOT NULL COMMENT 'created + hold_days',
  `approved_at` DATETIME(3) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_conv_txn` (`offer_id`, `external_txn_id`),
  KEY `ix_conv_click` (`click_id`),
  KEY `ix_conv_hold` (`status`, `hold_until`),
  KEY `ix_conv_prom` (`promoter_id`, `created_at`),
  CONSTRAINT `fk_conversions_offer_id` FOREIGN KEY (`offer_id`) REFERENCES `offers` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_conversions_promoter_id` FOREIGN KEY (`promoter_id`) REFERENCES `promoters` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Install / sign-up / purchase confirmed by a postback';

-- Every postback received (IN) or fired to a promoter (OUT)
CREATE TABLE `postback_logs` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `direction` ENUM('IN','OUT') NOT NULL,
  `offer_id` BIGINT UNSIGNED NULL,
  `conversion_id` BIGINT UNSIGNED NULL,
  `url` VARCHAR(2000) NOT NULL COMMENT 'Received or called URL (secrets masked)',
  `params` JSON NULL,
  `source_ip` VARCHAR(45) NULL,
  `result` ENUM('ACCEPTED','DUPLICATE','INVALID_SIGNATURE','UNKNOWN_CLICK','WINDOW_EXPIRED','CAP_REACHED','IP_BLOCKED','OFFER_INACTIVE','SENT','SEND_FAILED') NOT NULL,
  `http_status` SMALLINT UNSIGNED NULL COMMENT 'For OUT',
  `attempt` TINYINT UNSIGNED NOT NULL DEFAULT 1,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  PRIMARY KEY (`id`),
  KEY `ix_pb_offer` (`offer_id`, `created_at`),
  KEY `ix_pb_result` (`result`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Every postback received (IN) or fired to a promoter (OUT)';

-- Promoter UPI IDs and bank accounts (encrypted, penny-drop verified)
CREATE TABLE `payout_methods` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `promoter_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK promoters',
  `type` ENUM('UPI','BANK') NOT NULL,
  `upi_id_enc` VARBINARY(512) NULL,
  `account_number_enc` VARBINARY(256) NULL,
  `account_last4` CHAR(4) NULL COMMENT 'Display only',
  `ifsc` CHAR(11) NULL,
  `holder_name` VARCHAR(100) NOT NULL,
  `name_match_score` TINYINT UNSIGNED NULL COMMENT 'Gateway name check, 0 to 100',
  `is_verified` TINYINT(1) NOT NULL DEFAULT 0,
  `is_default` TINYINT(1) NOT NULL DEFAULT 0,
  `cooldown_until` DATETIME(3) NULL COMMENT 'No payouts for 24 h after adding',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  `deleted_at` DATETIME(3) NULL COMMENT 'Soft delete marker',
  PRIMARY KEY (`id`),
  KEY `ix_pm_prom` (`promoter_id`),
  CONSTRAINT `fk_payout_methods_promoter_id` FOREIGN KEY (`promoter_id`) REFERENCES `promoters` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Promoter UPI IDs and bank accounts (encrypted, penny-drop verified)';

-- Money sent to promoters through the RupiyaX payout API
CREATE TABLE `payouts` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `promoter_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK promoters',
  `payout_method_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK payout_methods',
  `payout_ref` VARCHAR(40) NOT NULL COMMENT 'Our idempotency key',
  `amount` DECIMAL(12,2) NOT NULL COMMENT 'Requested',
  `tds_amount` DECIMAL(12,2) NOT NULL DEFAULT 0.00 COMMENT 'If applicable',
  `fee` DECIMAL(10,2) NOT NULL DEFAULT 0.00,
  `net_amount` DECIMAL(12,2) NOT NULL COMMENT 'Sent to bank / UPI',
  `mode` ENUM('UPI','IMPS','NEFT') NOT NULL,
  `gateway` ENUM('RUPIYAX','RAZORPAY') NOT NULL DEFAULT 'RUPIYAX',
  `gateway_payout_id` VARCHAR(64) NULL,
  `utr` VARCHAR(30) NULL COMMENT 'Bank reference',
  `status` ENUM('REQUESTED','APPROVED','PROCESSING','SUCCESS','FAILED','REVERSED','REJECTED') NOT NULL DEFAULT 'REQUESTED',
  `failure_reason` VARCHAR(255) NULL,
  `approved_by` BIGINT UNSIGNED NULL COMMENT 'FK users (admin)',
  `requested_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `processed_at` DATETIME(3) NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_payout_ref` (`payout_ref`),
  UNIQUE KEY `uk_payout_gw` (`gateway`, `gateway_payout_id`),
  KEY `ix_payout_status` (`status`, `requested_at`),
  KEY `ix_payout_prom` (`promoter_id`, `requested_at`),
  CONSTRAINT `fk_payouts_promoter_id` FOREIGN KEY (`promoter_id`) REFERENCES `promoters` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_payouts_payout_method_id` FOREIGN KEY (`payout_method_id`) REFERENCES `payout_methods` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_payouts_approved_by` FOREIGN KEY (`approved_by`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Money sent to promoters through the RupiyaX payout API';

-- Promoter money ledger; balances are always rebuilt from this
CREATE TABLE `earnings_ledger` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `promoter_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK promoters',
  `type` ENUM('EARNING','HOLD_RELEASE','PAYOUT','PAYOUT_REVERSAL','REVERSAL','TDS','ADJUSTMENT') NOT NULL,
  `amount` DECIMAL(12,2) NOT NULL COMMENT 'Signed: + credit, - debit',
  `bucket` ENUM('PENDING','AVAILABLE') NOT NULL COMMENT 'Which balance it moves',
  `balance_after` DECIMAL(12,2) NOT NULL COMMENT 'Of that bucket',
  `conversion_id` BIGINT UNSIGNED NULL COMMENT 'FK conversions',
  `payout_id` BIGINT UNSIGNED NULL COMMENT 'FK payouts',
  `note` VARCHAR(255) NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  PRIMARY KEY (`id`),
  KEY `ix_el_prom` (`promoter_id`, `created_at`),
  CONSTRAINT `fk_earnings_ledger_promoter_id` FOREIGN KEY (`promoter_id`) REFERENCES `promoters` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_earnings_ledger_conversion_id` FOREIGN KEY (`conversion_id`) REFERENCES `conversions` (`id`) ON DELETE SET NULL,
  CONSTRAINT `fk_earnings_ledger_payout_id` FOREIGN KEY (`payout_id`) REFERENCES `payouts` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Promoter money ledger; balances are always rebuilt from this';

-- Help requests from businesses and promoters
CREATE TABLE `support_tickets` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `workspace_id` BIGINT UNSIGNED NULL COMMENT 'Null for promoters',
  `user_id` BIGINT UNSIGNED NOT NULL COMMENT 'Raised by',
  `category` ENUM('BILLING','PAYOUT','WHATSAPP','TRACKING','ACCOUNT','OTHER') NOT NULL,
  `subject` VARCHAR(120) NOT NULL,
  `priority` ENUM('LOW','NORMAL','HIGH','URGENT') NOT NULL DEFAULT 'NORMAL',
  `status` ENUM('OPEN','WAITING_CUSTOMER','RESOLVED','CLOSED') NOT NULL DEFAULT 'OPEN',
  `assigned_to` BIGINT UNSIGNED NULL COMMENT 'Support staff',
  `reference_type` VARCHAR(30) NULL COMMENT 'e.g. PAYOUT',
  `reference_id` BIGINT UNSIGNED NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'Last change (UTC)',
  PRIMARY KEY (`id`),
  KEY `ix_ticket_status` (`status`, `priority`, `created_at`),
  KEY `ix_ticket_user` (`user_id`),
  CONSTRAINT `fk_support_tickets_workspace_id` FOREIGN KEY (`workspace_id`) REFERENCES `workspaces` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_support_tickets_user_id` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
  CONSTRAINT `fk_support_tickets_assigned_to` FOREIGN KEY (`assigned_to`) REFERENCES `users` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Help requests from businesses and promoters';

-- Conversation inside a support ticket
CREATE TABLE `ticket_messages` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `ticket_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK support_tickets',
  `sender_id` BIGINT UNSIGNED NOT NULL COMMENT 'FK users',
  `body` TEXT NOT NULL COMMENT 'Max 5,000 chars',
  `attachment_url` VARCHAR(500) NULL,
  `is_internal` TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Staff-only note',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  PRIMARY KEY (`id`),
  KEY `ix_tm_ticket` (`ticket_id`, `created_at`),
  CONSTRAINT `fk_ticket_messages_ticket_id` FOREIGN KEY (`ticket_id`) REFERENCES `support_tickets` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_ticket_messages_sender_id` FOREIGN KEY (`sender_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Conversation inside a support ticket';

-- Daily RupiyaX settlement reports for reconciliation
CREATE TABLE `settlements` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT 'Primary key',
  `gateway` ENUM('RUPIYAX','RAZORPAY') NOT NULL DEFAULT 'RUPIYAX',
  `settlement_ref` VARCHAR(64) NOT NULL,
  `settled_on` DATE NOT NULL,
  `gross_amount` DECIMAL(14,2) NOT NULL,
  `fees` DECIMAL(12,2) NOT NULL,
  `tax_on_fees` DECIMAL(12,2) NOT NULL,
  `net_amount` DECIMAL(14,2) NOT NULL,
  `matched_payments` INT UNSIGNED NOT NULL DEFAULT 0,
  `mismatch_count` INT UNSIGNED NOT NULL DEFAULT 0 COMMENT 'Needs finance review',
  `status` ENUM('IMPORTED','MATCHED','MISMATCH') NOT NULL DEFAULT 'IMPORTED',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'Row created (UTC)',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_settle` (`gateway`, `settlement_ref`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=${collation} COMMENT='Daily RupiyaX settlement reports for reconciliation';

-- Seed plans
INSERT INTO plans (code, name, price_monthly, price_yearly, max_users, max_contacts, max_workspaces, max_domains, features) VALUES
 ('STARTER', 'Starter', 799.00, 7990.00, 1, 2000, 1, 0, JSON_OBJECT('campaigns', true, 'reviews', true, 'crm', true, 'social', false, 'ai', false, 'forms', false)),
 ('GROWTH', 'Growth', 1999.00, 19990.00, 3, 10000, 1, 2, JSON_OBJECT('campaigns', true, 'reviews', true, 'crm', true, 'social', true, 'ai', true, 'forms', true, 'automations', true, 'app_promotion', true)),
 ('AGENCY', 'Agency', 4999.00, 49990.00, 10, 50000, 10, 25, JSON_OBJECT('campaigns', true, 'reviews', true, 'crm', true, 'social', true, 'ai', true, 'forms', true, 'white_label', true, 'automations', true, 'app_promotion', true, 'api', true));
INSERT INTO message_rates (category, country_code, meta_rate, sell_rate, effective_from) VALUES
 ('MARKETING', '91', 0.8631, 1.2000, '2026-01-01'),
 ('UTILITY', '91', 0.1150, 0.2500, '2026-01-01'),
 ('AUTHENTICATION', '91', 0.1150, 0.2500, '2026-01-01');
