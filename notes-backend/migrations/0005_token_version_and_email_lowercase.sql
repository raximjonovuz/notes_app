-- 0005_token_version_and_email_lowercase.sql
-- A7 (OWASP top-10) — autentifikatsiya mustahkamligi.
--
--  (1) users.token_version — har bir JWT da yoziladi; oshganda eski
--      tokenlar avtomatik "eskiradi" (verifyToken solishtiradi). Bu
--      POST /auth/logout ning mexanikasi: token_version++ qilsak,
--      eski token bilan kelgan keyingi so'rovlar 401 qaytaradi.
--  (2) users.email — pastki registrga normallashtiramiz. Avval
--      to'qnashuvlarni hal qilamiz: bir xil pastki-registrli email
--      li bir nechta qator bo'lsa, MIN(id) saqlanadi, qolganlarining
--      emailiga "_dup_<id>" suffiksi qo'shiladi (FK CASCADE orqali
--      notelarni yo'qotmaslik uchun).

ALTER TABLE users ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;

-- 1) Duplikatlarni hal qilish: eng kichik id li qatorni saqlaymiz,
--    qolganlarini vaqtinchalik unique nom bilan yozamiz.
UPDATE users
   SET email = lower(email) || '_dup_' || id
 WHERE id NOT IN (
       SELECT MIN(id) FROM users GROUP BY lower(email)
     );

-- 2) Qolganlarini pastki registrga o'tkazamiz.
UPDATE users
   SET email = lower(email)
 WHERE email != lower(email);

-- Rate-limit jadvali: (key, window_start) PRIMARY KEY. Key — masalan
-- "auth:login:1.2.3.4", window_start — daqiqaning boshi (epoch
-- daqiqa). Scheduled task (cron trigger) eski window_start larni
-- tozalab turadi; hozircha vaqt o'tgan yozuvlar shunchalik oz
-- bo'lib qoladiki, indekslash arzon.
CREATE TABLE IF NOT EXISTS rate_limits (
    key TEXT NOT NULL,
    window_start INTEGER NOT NULL,
    count INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (key, window_start)
);

CREATE INDEX IF NOT EXISTS idx_rate_limits_window ON rate_limits(window_start);
