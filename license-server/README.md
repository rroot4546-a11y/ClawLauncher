# ClawLauncher License Server

## الملفات
- `index.js` — السيرفر (Express + Firestore)
- `package.json` — المتطلبات
- `serviceAccountKey.json` — مفتاح Firebase (سري!)

## الاستضافة على أي VPS (Railway, Render, DigitalOcean, etc.)

### 1. ارفع الملفات
```bash
git clone <your-repo>
cd license-server
```

### 2. حط ملف serviceAccountKey.json
نفس الملف اللي نزلته من Firebase Console

### 3. تشغيل
```bash
npm install
PORT=3001 ADMIN_KEY=claw-roox-admin-2026 node index.js
```

### 4. غيّر URL بالتطبيق
بملف `LicenseManager.kt` غيّر:
```kotlin
private val LICENSE_API = "https://your-new-domain.com/api"
```

---

## الاستضافة على Firebase Cloud Functions (مجاني)

### 1. نزّل Firebase CLI
```bash
npm install -g firebase-tools
firebase login
```

### 2. اعمل مشروع
```bash
firebase init functions
```

### 3. انسخ الكود من `firebase-functions/index.js`

### 4. انشر
```bash
firebase deploy --only functions
```

### 5. URL الجديد
```
https://us-central1-clawlauncher-license.cloudfunctions.net/api
```

---

## API Endpoints

### Public
- `POST /api/activate` — تفعيل مفتاح
  - Body: `{ "key": "CLAW-...", "deviceId": "...", "deviceName": "..." }`
- `POST /api/verify` — تحقق من مفتاح
  - Body: `{ "key": "CLAW-...", "deviceId": "..." }`

### Admin (يحتاج Header: `Authorization: Bearer ADMIN_KEY`)
- `POST /api/admin/generate` — إنشاء مفتاح
  - Body: `{ "duration": 30, "plan": "monthly", "note": "اسم" }`
- `POST /api/admin/revoke` — إيقاف مفتاح
  - Body: `{ "key": "CLAW-..." }`
- `POST /api/admin/unrevoke` — إرجاع مفتاح
- `POST /api/admin/extend` — تمديد
  - Body: `{ "key": "CLAW-...", "days": 30 }`
- `POST /api/admin/unbind` — فك ربط جهاز
- `GET /api/admin/list` — كل المفاتيح
- `GET /api/health` — حالة السيرفر
