# Xulosa

`02_XATOLAR_HISOBOTI.md`dagi sarlavhalar qayta sanaldi. IDlar T-01…T-06 (6), TB-01…TB-05 (5), R-01…R-03 (3), D-01…D-04 (4): jami **18 topilma**. Jiddiylikning to'g'ri natijasi **1 Kritik, 7 Yuqori, 9 O'rta, 1 Past** (T: 1K/3Y/2O; TB: 2Y/3O; R: 1Y/2O; D: 1Y/2O/1P).

Eng xavfli beshtalik: T-02 (selection tashqarisidagi format yo'qoladi), T-03 (V2.6 header span decode buzilgan), T-04 (H1/H2/P semantic model yo'q), TB-02 (bo'sh checklistdan chiqish yo'q) va R-01 (Teal/Clay Redga clamp bo'ladi). T-01 ham ko'zga ko'rinadigan, lekin ma'lumot yo'qotmaydigan yuqori UX xatosidir.

Tavsiya etilgan ketma-ketlik: avval test va compatibility adapter bilan v3 document modelini qo'shish; keyin bitta `TextBlock` fieldda paragraph/span resolver; keyin semantic checklist; so'ng palette/Room v12; oxirida eski v2 UI va redundant ustunlarni olib tashlash. Har oraliq commit debug build qilinadigan bo'lishi shart.

Tasdiqlanmaganlar: real qurilmadagi FPS, OEM IME xulqi va Google Keepning foydalanuvchi preferenceiga bog'liq checked-sort defaulti. Hisobot faqat kod daliliga tayangan; source o'zgartirilmadi.
