-- 0002_soft_delete_and_change_tracking.sql
-- BUG 2: DELETE o'rniga soft-delete (deleted_at). BUG 4: since=ms o'rniga
-- client_updated_at + change_log ishlatamiz. BUG 5: server updated_at =
-- max(client_updated_at, prev client_updated_at+1) — hech qachon
-- wall-clock MAX qilmaydi, shu bilan clock-skew safe bo'ladi.

ALTER TABLE notes ADD COLUMN deleted_at INTEGER;
ALTER TABLE notes ADD COLUMN client_updated_at INTEGER NOT NULL DEFAULT 0;
ALTER TABLE notes ADD COLUMN client_created_at INTEGER NOT NULL DEFAULT 0;
ALTER TABLE notes ADD COLUMN change_id INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_notes_deleted_at ON notes(user_id, deleted_at);
CREATE INDEX IF NOT EXISTS idx_notes_change_id ON notes(user_id, change_id);

-- change_log — monotonic sync cursor (BUG 4).
-- Har upsert/delete'ni yozib boramiz; client `?since=<changeId>` yuborganida
-- shu jadval bo'yicha qaytaramiz. `updated_at > since` degan millisekund
-- yo'qotishlaridan xoli.
CREATE TABLE IF NOT EXISTS change_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id INTEGER NOT NULL,
    note_id TEXT NOT NULL,
    op TEXT NOT NULL,             -- 'upsert' yoki 'delete'
    ts INTEGER NOT NULL DEFAULT (strftime('%s', 'now') * 1000)
);

CREATE INDEX IF NOT EXISTS idx_change_log_user_id ON change_log(user_id, id);

-- BUG 3: 3-way merge common-ancestor saqlash uchun yordamchi jadval.
-- sync_state(server_id, user_id, last_change_id) — hozircha faqat cursor.
-- 3-way merge uchun client base_* ustunlari NoteEntity ichida (offline ishlashi uchun).
CREATE TABLE IF NOT EXISTS note_sync_state (
    server_id TEXT PRIMARY KEY,
    user_id INTEGER NOT NULL,
    last_change_id INTEGER NOT NULL,
    last_sync_ts INTEGER NOT NULL DEFAULT (strftime('%s', 'now') * 1000)
);

CREATE INDEX IF NOT EXISTS idx_sync_state_user ON note_sync_state(user_id);
