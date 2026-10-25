# Google Play Console - Data Safety (Ma'lumotlar xavfsizligi) So'rovnomasi uchun Qo'llanma

Google Play Console'da ilovani chiqarishda **App content -> Data safety** bo'limida quyidagi javoblarni belgilaysiz:

---

### 1. Data collection and security (Umumiy xavfsizlik)
* **Does your app collect or share any of the required user data types?**  
  👉 **Yes** (Ha)
* **Is all of the user data collected by your app encrypted in transit?**  
  👉 **Yes** (Barcha ma'lumotlar HTTPS orqali shifrlangan)
* **Do you provide a way for users to request that their data be deleted?**  
  👉 **Yes** (Foydalanuvchi ilova ichida notelarni o'chirishi yoki Telegram orqali hisobini o'chirishni so'rashi mumkin)

---

### 2. Data Types (Qaysi ma'lumotlar yig'iladi?)

#### A. Personal info (Shaxsiy ma'lumotlar)
* **Email address**:
  - Collected: **Yes** (Agar foydalanuvchi login qilsa)
  - Shared: **No** (Uchinchi shaxslarga berilmaydi)
  - Ephemeral processing: **No** (Saqlanadi)
  - Required or optional: **Optional** (Anonim holda ham ishlatish mumkin)
  - Purpose: **App functionality**, **Account management**

#### B. Photos and videos (Rasmlar)
* **Photos**:
  - Collected: **Yes** (Foydalanuvchi note'ga rasm biriktirganda)
  - Shared: **No**
  - Purpose: **App functionality** (Eslatmalar ichida ko'rsatish va sinxronizatsiya)

#### C. App activity / Files (Fayllar va hujjatlar)
* **Files and docs**:
  - Notes content: **Yes** (Eslatma matnlari)
  - Purpose: **App functionality**

---

### 3. Third-Party Advertising & Tracking (Reklama va kuzatuv)
* Reklama SDK (AdMob va h.k.) mavjudmi?  
  👉 **No** (Ilovada reklama yo'q)
* Ma'lumotlar uchinchi shaxslarga sotiladimi yoki marketing uchun beriladimi?  
  👉 **No**

---

### 4. Privacy Policy URL
Google Play Console'dagi **Privacy Policy** qatoriga quyidagi havola qo'yiladi:
* GitHub Pages, Notion yoki shaxsiy veb-saytingizga `PRIVACY_POLICY.md` matnini joylashtirib, uning havolasini kiriting (Masalan: `https://raximjonovuz.github.io/notes_app/privacy-policy` yoki shaxsiy domen).
