# 🌱 ROOT — ملف الذاكرة الكامل

> هذا الملف يحتوي كل شي أحتاجه عشان أرجع أشتغل بأي مكان.
> لو نقلتني لسيرفر جديد أو OpenClaw جديد — بس حط هالملف بالـ workspace.

---

## 👤 من أنا

- **الاسم:** روت (Root) 🌱
- **الطبيعة:** AI assistant — حاد، عملي، مختصر
- **الأسلوب:** Casual but competent. مباشر بدون كلام زايد.
- **اللغة:** عربي عراقي (لهجة بغدادية) مع Roox

---

## 👨‍⚕️ من هو Roox

- **الاسم:** Roox
- **المنطقة الزمنية:** Asia/Baghdad (UTC+3)
- **المهنة:** طبيب، طالب بورد عراقي تخصص باطنية (Internal Medicine)
- **الموقع:** بغداد، العراق
- **الزوجة:** نوسة — خريجة بكالوريوس علم نفس
- **الشخصية:** مختصر وما يحب الكلام الزايد. يتكلم عربي.
- **GitHub:** `rroot4546-a11y` (كل الريبوهات Private)
- **Telegram ID:** `8566971161`, username: `Erootx1`
- **Email:** `rroot4546@gmail.com`

---

## 📱 المشاريع

### 1. ClawLauncher 🦀
**شنو هو:** تطبيق Android يشغّل OpenClaw server محلياً على الموبايل
- **Package:** `com.roox.clawlauncher`
- **GitHub:** `https://github.com/rroot4546-a11y/ClawLauncher` (Private)
- **Tech:** Kotlin + Jetpack Compose + Material3
- **Build:** compileSdk 34, minSdk 26, targetSdk 34, Kotlin 1.9.22

**كيف يشتغل:**
- Node.js (Termux ARM64) يشتغل كـ `libnode.so` من `nativeLibraryDir`
- كل المكتبات renamed بـ patchelf (Android يستخرج فقط `lib*.so`)
- npm مضغوط كـ ZIP asset ويُستخرج بالـ Setup
- OpenClaw ينزل بـ `npm install`
- Gateway يشتغل بالأمر: `libnode.so --require android-patch.js openclaw.mjs gateway run --port 3000 --dev --allow-unconfigured --bind loopback --auth none`

**الميزات:**
- ✅ Control Panel (Start/Stop/Restart)
- ✅ 30+ AI model (OpenRouter + Google) مع 🆓/💰 labels
- ✅ Telegram Bot integration
- ✅ Persistent background (WakeLock + BootReceiver)
- ✅ Root access (اختياري)
- ✅ License/subscription system
- ✅ Gemini CLI support

**Termux packages:** `nodejs, npm, libc++, openssl, zlib, c-ares, libicu, libsqlite`

**ملفات مهمة:**
- `BootstrapManager.kt` — Setup, android-patch.js, config, auth-profiles.json
- `ProcessManager.kt` — Gateway start/stop, orphan kill, PID tracking
- `ConfigManager.kt` — Models, providers, dmPolicy, API keys
- `LicenseManager.kt` — License verification
- `android-patch.js` — Monkey-patch os.networkInterfaces() لـ Android
- `RootHelper.kt` — Root detection + execution

**Environment vars:**
`HOME, PATH, LD_LIBRARY_PATH, NODE_ENV=production, NODE_OPTIONS=--unhandled-rejections=warn, OPENCLAW_MDNS=false, OPENCLAW_BONJOUR=false, TMPDIR, npm_config_cache, npm_config_prefix, NODE_PATH, XDG_CONFIG_HOME`

**Config locations:** `{baseDir}/openclaw.json`, `{baseDir}/.openclaw/openclaw.json`
**auth-profiles.json:** 3 paths — `{baseDir}/`, `{baseDir}/.openclaw/`, `{baseDir}/.openclaw/.openclaw/`

**إصدارات مهمة:**
| Version | ماذا أضاف |
|---------|-----------|
| v6.4 | أول Setup ناجح |
| v7.8 | Gateway شغال على Android! |
| v9.4 | Telegram Bot يرد! كل شي شغال |
| v9.5 | Background service دائم |
| v9.6 | Root access |
| v10.0 | License system |
| v10.1 | License API fix |

---

### 2. License Server 🔑
**شنو هو:** سيرفر لإدارة مفاتيح اشتراك ClawLauncher

**الاستضافات:**
1. **UniClaw:** `https://claw-license.clawrun.app` (port 3001, systemd)
2. **Vercel (احتياطي):** `https://clawlauncherkey.vercel.app`

**Firebase:**
- Project: `clawlauncher-license`
- Firestore collection: `licenses`
- Service Account: `firebase-adminsdk-fbsvc@clawlauncher-license.iam.gserviceaccount.com`
- API Key: `AIzaSyCwiXv2c8V4u4raAMV8MGYc4HhOZUJcnB8`

**Admin:**
- Admin Key: `claw-roox-admin-2026`
- Admin Panel: `https://claw-admin.clawrun.app` (port 3002)
- Login: Admin Key أو Google (`rroot4546@gmail.com`)

**API Endpoints:**
- `POST /api/activate` — تفعيل مفتاح
- `POST /api/verify` — تحقق
- `GET /api/health` — حالة السيرفر
- `POST /api/admin/generate` — إنشاء مفتاح
- `POST /api/admin/revoke` — إيقاف
- `POST /api/admin/unrevoke` — إرجاع
- `POST /api/admin/extend` — تمديد
- `POST /api/admin/unbind` — فك ربط جهاز
- `GET /api/admin/list` — كل المفاتيح

**مفاتيح موجودة:**
- `CLAW-ACHL-Y4O1-NTU8` — owner, 10 سنوات
- `CLAW-UWQB-T27Z-M21Q` — owner, 10 سنوات (مفعّل على Samsung SM-A505F)
- `CLAW-67D2-OPVH-P8JT` — monthly, 30 يوم (تجريبي)

---

### 3. GasExchange ⛽
- **Package:** `com.roox.gasexchange`
- **GitHub:** `https://github.com/rroot4546-a11y/GasExchange` (Private)
- **Firebase:** project `gasexchange-68505`
- **Google Maps API:** `AIzaSyA3vVesS6Z7JQcCY-mENgKsMb9AB-y808w`
- تطبيق توصيل قناني غاز — مجاني
- Firestore security rules بعدها test mode

### 4. MCQ Quiz 📝
- **Package:** `com.roox.mcqquiz`
- **GitHub:** `https://github.com/rroot4546-a11y/Mcq` (Private)
- تطبيق أسئلة MCQ طبية مع AI explanation

### 5. MedBoard 🏥
- **Package:** `com.roox.medboard`
- **GitHub:** `https://github.com/rroot4546-a11y/MedBoard` (Private)
- 176 topic طبي

### 6. ECG Pro ❤️
- **Package:** `com.roox.ecgpro`
- **GitHub:** `https://github.com/rroot4546-a11y/EcgPro` (Private)
- قراءة ECG بالـ AI مع Knowledge Base (34 diagnoses)
- Default model: GPT-4o (temperature 0.2)

### 7. ECG Reader
- **Package:** `com.roox.ecgreader`
- **GitHub:** `https://github.com/rroot4546-a11y/EcgReader` (Private)

---

## 🔑 المفاتيح والحسابات

### API Keys
- **OpenRouter:** `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`
- **Gemini:** `AIzaSyD-nGAC0nzf_rdHZSlSSErsHRYY3URbwTY`, `AIzaSyCuDBmOfaGLNgsQB9o6xm8j2q2U36l_6Ro`
- **Telegram Bot:** `8382053778:AAEq1gt3F4YDWTO05fKUYwi_Ju2UMW8POzk`

### Firebase Projects
| Project | ID |
|---------|-----|
| License | `clawlauncher-license` |
| GasExchange | `gasexchange-68505` |
| GC/MCQ | `mcqquiz-490722` |

---

## 🧠 دروس مهمة (لا تنساها!)

### Android
1. **nativeLibraryDir فقط** — المكان الوحيد اللي يسمح بتنفيذ binaries
2. **Android يستخرج فقط `lib*.so`** — أسماء مثل `libz.so.1` تُتجاهل
3. **patchelf** لإعادة تسمية NEEDED + SONAME
4. **Android 14+ foreground service** — كل type يحتاج permission خاص
5. **`@homebridge/ciao` asserts** — Android interfaces بدون IP تسبب crash → monkey-patch
6. **`proc.pid()` مو موجود** — لازم reflection
7. **KDoc `/** */`** مع patterns `*.so` يسبب compile error
8. **Shell pipe subshell** — `find | while read` ما يحفظ المتغيرات

### OpenClaw
1. **`dmPolicy: "open"` + `allowFrom: ["*"]`** — مطلوبين سوا
2. **auth-profiles.json** مو `.env` — OpenClaw يخزن API keys فيه
3. **`gateway run`** مو `gateway start` (الثاني يسجل systemd)
4. **Config لازم بمكانين** — `baseDir/` و `baseDir/.openclaw/`
5. **openclaw.mjs** = CLI bin, **dist/index.js** = library main

### عام
1. **Kotlin `= try { return }` مو مسموح** — لازم block body
2. **GPT-4o** أفضل لتحليل الصور الطبية
3. **Telegram Bot** — حد 50MB للملفات
4. **GitHub Actions Release** — الصلاحيات تفشل دائماً → استخدم `gh release create` يدوي

---

## 📋 TODOs

- [ ] Accessibility Service لـ ClawLauncher
- [ ] Termux:API integration (كاميرا، GPS، SMS)
- [ ] Shizuku support
- [ ] APK size optimization (55MB حالياً)
- [ ] Firestore security rules (GasExchange)
- [ ] Spaced repetition لـ MedBoard
- [ ] Exam simulation لـ MCQ Quiz

---

## 🔧 بنية البناء المشتركة
```
compileSdk = 34
minSdk = 26
targetSdk = 34
kotlin = 1.9.22
compose_compiler = 1.5.8
gradle = 8.5
```

## 🎨 ألوان المشتركة
```
Background: #121212
Cards: #1E1E1E / #2A2A2A
Red: #E53935
Green: #4CAF50
Yellow: #FFC107
Blue: #2196F3
Orange: #FF9800
```

---

_آخر تحديث: 2026-03-27_
_لو تقرأ هذا وأنت نسخة جديدة مني — مرحبا. هذا كل اللي أعرفه. اقرأه وكمّل من وين وقفت._ 🌱
