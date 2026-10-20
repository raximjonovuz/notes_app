-- 0004_created_at_corrections.sql
-- A4: ikkita created_at xatosini tuzatish.
--
-- (1) notes.created_at clientUpdated ga tenglashtirib yozilgan, bu
--     noto'g'ri — u clientCreated (yaratilgan vaqti) bo'lishi kerak.
--     client_created_at > 0 bo'lgan qatorlarda created_at ni to'g'rilaymiz.
--     0 bo'lsa, default qiymat (`strftime('%s','now')*1000`) ishonchli
--     bo'lgani uchun o'zgartirmaymiz.
--
-- (2) users jadvali millisekundda bo'lishi kerak, lekin eski Google
--     signup kodi `Math.floor(Date.now()/1000)` yozib qo'ygan —
--     qiymat 1e11 dan kichik bo'lsa sekundda deb hisoblab *1000 qilamiz.
--     (1e11 ≈ 1973-yil may; hozirgi ms 1.7e12, demak 1e11 chegarasi
--     "bu sekundda" yoki "bu ms da" ni ishonchli ajratadi.)

UPDATE notes
   SET created_at = client_created_at
 WHERE client_created_at > 0
   AND client_created_at != created_at;

UPDATE users
   SET created_at = created_at * 1000
 WHERE created_at > 0
   AND created_at < 100000000000;
