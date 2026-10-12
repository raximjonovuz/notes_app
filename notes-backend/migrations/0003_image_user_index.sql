-- 0003_image_user_index.sql
-- BUG 6: /files/<key> auth tekshiruvi uchun r2_key → user_id tez qidiruv.

CREATE INDEX IF NOT EXISTS idx_images_r2_user ON images(r2_key, user_id);
