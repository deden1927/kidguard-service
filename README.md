# KidGuard — Pemantau & Kontrol HP Anak via Telegram

Aplikasi Android native (Kotlin) untuk memantau dan mengontrol HP anak **tanpa server/VPS**. Seluruh komunikasi berjalan langsung antara HP anak dan **Telegram Bot API**: orang tua mengirim perintah dari chat Telegram, HP anak mengeksekusinya lewat service Android dan membalas dengan laporan, foto, atau lokasi.

Aplikasi sengaja tampil di HP anak dengan nama **"Layanan Sistem"** (layanan aksesibilitas: *"Sinkronisasi Layanan Sistem"*) agar tidak mudah dikenali dan dimatikan oleh anak.

| | |
|---|---|
| Platform | Android 8.0+ (minSdk 26), targetSdk 34 |
| Bahasa | Kotlin 1.9.22, vanilla Android Views (tanpa Compose) |
| Build | Gradle KTS, AGP 8.2.2, JDK 17 |
| Dependensi | androidx.core-ktx, appcompat, material, kotlinx-coroutines-android |
| Backend | Telegram Bot API via HTTP long-polling (tanpa library eksternal) |

---

## Fitur Utama

**Pantau (real-time):**
- Aplikasi yang sedang dibuka anak + judul video YouTube yang sedang ditonton
- Riwayat 15 aplikasi & video YouTube terakhir
- Rekap durasi layar (screen time) per aplikasi sejak 00:00
- Screenshot layar HP anak saat itu juga (`/ss`)
- Lokasi GPS + tautan Google Maps (`/lokasi`)
- Status baterai, charging, jaringan (WiFi/data), status kunci layar & daftar blokir (`/status`)

**Kontrol:**
- Kunci layar penuh "Waktunya Istirahat / Belajar" (`/lock`, `/unlock`) — panggilan darurat tetap bisa diakses
- Pop-up pesan peringatan di tengah layar anak + alarm singkat (`/pesan <teks>`)
- Blokir/buka blokir aplikasi atau game (`/block`, `/unblock`) — aplikasi yang cocok langsung ditutup
- Papan tombol cepat (reply keyboard) di chat orang tua

**Notifikasi otomatis ke Telegram:**
- Anak membuka aplikasi/game baru (cooldown 60 detik)
- Anak mencoba membuka aplikasi yang diblokir
- Judul video YouTube berubah
- Baterai lemah (< 15%, tidak charging)
- Pesan WhatsApp baru (nama pengirim/grup + cuplikan, cooldown 20 detik) — bisa dimatikan dengan `/wa off`
- Perangkat dinyalakan ulang, device admin diaktif/dinonaktifkan

**Anti-tamper:**
- PIN orang tua di aplikasi (4–8 digit) — anak tidak bisa membuka halaman pengaturan
- Device Admin (`force-lock`) mengunci tombol Uninstall
- Whitelist Chat ID: perintah dari chat lain ditolak otomatis
- Foreground service + `BOOT_COMPLETED` receiver agar service hidup kembali setelah restart

---

## Arsitektur

Tidak ada server sendiri. HP anak menjalankan kumpulan Android service yang berbicara langsung dengan Telegram Bot API:

```text
HP ANAK (aplikasi KidGuard)                    TELEGRAM BOT API                  HP ORANG TUA
────────────────────────────                  ───────────────────                ─────────────────
GuardAccessibilityService ─┐  deteksi app,
WhatsAppMonitorService ────┼── judul YouTube,
GuardDeviceAdminReceiver ──┤  screenshot, blokir
BootCompletedReceiver ─────┘        │
        │ enqueueInstantAlert()     ▼
        │                    TelegramMonitorService
        └──────────────────► (foreground service,
                             long-poll getUpdates 25s)
                                   │  ▲
                        POST sendMessage/sendPhoto/sendLocation
                                   │  │  GET /bot<token>/getUpdates
                                   ▼  │
                              api.telegram.org ───────► chat orang tua
                                                        (ketik /status /ss /lock ...)
                                   │
                                   ▼
                          BotCommandRouter.handleUpdate()
                             └─ cek whitelist chat ID
                                ├─ OverlayManager (lock / pesan)
                                ├─ UsageStatsHelper (screentime)
                                ├─ LocationHelper (GPS)
                                ├─ GuardAccessibilityService (screenshot, blokir)
                                └─ AppPrefs (state, riwayat, daftar blokir)
```

**Alur data:**
- *Event → orang tua:* event di HP anak → `enqueueInstantAlert()` → `TelegramMonitorService` → HTTP POST ke Bot API → pesan muncul di chat orang tua.
- *Perintah → HP anak:* orang tua kirim perintah → Bot API menyimpannya → HP anak menariknya via `getUpdates` (long-poll 25 detik) → `BotCommandRouter` mengeksekusi → balasan dikirim via Bot API.

---

## Daftar Perintah Telegram

Semua perintah diproses di `BotCommandRouter.handleUpdate()`. Teks dinormalkan lebih dulu (emoji label tombol cepat dipotong, lowercase, suffix `@namabot` dibuang). Sebelum dispatch ada **cek whitelist**: chat ID pengirim harus sama dengan `parentChatId` yang terdaftar, kalau tidak perintah ditolak tanpa dieksekusi.

| Perintah / alias | Fungsi | Implementasi teknis |
|---|---|---|
| `/start`, `/menu` | Menu kontrol + papan tombol cepat | Kirim teks menu + reply keyboard 4 baris |
| `/status` | Laporan status perangkat | Baterai (`ACTION_BATTERY_CHANGED` sticky broadcast), jaringan (`ConnectivityManager`), app aktif & judul YouTube dari `AppPrefs`, status layanan aksesibilitas, daftar blokir, status monitor WhatsApp |
| `/yt`, `/riwayat` | 15 riwayat terakhir | `prefs.getRecentActivities(limit = 15)` + `formatActivityEntries()` |
| `/screentime` | Rekap durasi layar hari ini | `UsageStatsManager.queryAndAggregateUsageStats` (00:00 → sekarang), filter < 30 detik, top 15 |
| `/ss` | Screenshot perangkat | `AccessibilityService.takeScreenshot()` (API 30+) → PNG di `cacheDir` → upload multipart via `sendPhoto` → file dihapus |
| `/lokasi` | Titik GPS + Google Maps | `LocationManager` (GPS → NETWORK → last-known, timeout 10 s) → `sendLocation` + detail akurasi |
| `/pesan <teks>` | Pop-up peringatan di layar anak | `OverlayManager.showParentMessage()` (overlay + `ToneGenerator` alarm 600 ms) |
| `/lock [alasan]` | Kunci layar | `prefs.isDeviceLocked = true` + `showLockScreen()` |
| `/unlock` | Buka kunci layar | `prefs.isDeviceLocked = false` + `hideLockScreen()` |
| `/block <nama>` | Blokir aplikasi/game | `prefs.addBlockedKeyword()` (lowercase, substring match) + `recheckCurrentForegroundApp()` agar langsung berlaku |
| `/unblock <nama>` | Hapus blokir | `removeBlockedKeyword()` + tutup overlay blokir bila tidak dalam status lock |
| `/wa`, `/whatsapp` `[on\|off]` | Status/toggle monitor WhatsApp | Argumen `on/aktif/nyala` atau `off/mati/nonaktif`; cek akses notifikasi via `Settings.Secure` |
| *(selain itu)* | — | Balasan "perintah tidak dikenali" + kirim ulang papan tombol |

---

## Struktur Project

```text
Monitoring Device Anak/
├── app/
│   ├── build.gradle.kts                # Konfigurasi modul Android (SDK 34, deps)
│   └── src/main/
│       ├── AndroidManifest.xml         # Izin, service, receiver
│       ├── res/
│       │   ├── layout/
│       │   │   ├── activity_main.xml           # UI setup: PIN gate, kredensial, checklist izin, log
│       │   │   ├── overlay_lock_screen.xml     # Overlay layar kunci
│       │   │   └── overlay_parent_message.xml  # Overlay pesan orang tua
│       │   ├── drawable/               # 7 aset XML: background kartu/input/tombol/status + ikon launcher
│       │   ├── values/                 # colors, dimens, strings, themes (+ values-night, values-sw600dp/840dp)
│       │   └── xml/                    # accessibility_service_config, device_admin_receiver
│       └── java/com/kidguard/app/
│           ├── MainActivity.kt                 # Satu-satunya Activity: setup & checklist izin
│           ├── data/
│           │   ├── AppPrefs.kt                 # SharedPreferences: token, chat ID, PIN, blokir, log
│           │   └── ActivityModels.kt           # ActivityEntry, ActivityType, DTO Telegram
│           ├── service/
│           │   ├── TelegramMonitorService.kt   # Foreground service + long-polling Bot API
│           │   ├── GuardAccessibilityService.kt# Sensor: app aktif, YouTube, screenshot, blokir
│           │   ├── WhatsAppMonitorService.kt   # NotificationListenerService untuk WhatsApp
│           │   ├── BootCompletedReceiver.kt    # Hidupkan service setelah reboot/update
│           │   └── GuardDeviceAdminReceiver.kt # Anti-uninstall (force-lock)
│           ├── telegram/
│           │   ├── TelegramClient.kt           # HTTP client Bot API (HttpURLConnection + org.json)
│           │   └── BotCommandRouter.kt         # Parser & eksekutor perintah + whitelist
│           └── util/
│               ├── UsageStatsHelper.kt         # Screen time harian
│               ├── LocationHelper.kt           # GPS sekali-jaman
│               └── OverlayManager.kt           # Window overlay kunci & pesan
├── .github/workflows/build-apk.yml     # CI: build debug APK + upload artifact
├── DESIGN.md                           # Arahan desain UI (palet, kontras WCAG, dial desain)
├── implementation_plan.md              # Rencana arsitektur & fitur
└── PANDUAN_INSTALASI_DAN_BOT.md        # Panduan @BotFather, build APK, aktivasi izin
```

---

## Lapisan Frontend (UI)

Satu Activity tunggal (`MainActivity`) dengan vanilla Android Views — `ScrollView` + `LinearLayout` vertikal, tanpa Fragment, tanpa ViewBinding (semua view di-`findViewById`). Tidak ada navigasi antar-layar; yang ada pergantian visibility antar-kartu.

### Alur layar

| Tahap | Mekanisme |
|---|---|
| Gerbang PIN | `applyPinGateVisibility()`: tampil jika PIN sudah diset dan sesi belum dibuka; state sesi bertahan saat rotasi (`onSaveInstanceState`) |
| Panel orang tua | Form Token Bot + Chat ID + PIN, badge status layanan, tombol simpan/tes/stop/kunci-panel |
| Checklist izin | 8 izin dengan tag `[AKTIF]` / `[BELUM AKTIF]`, dicek ulang di `onCreate`, `onResume`, dan callback permission launcher |
| Log aktivitas | Pratinjau 15 entri terakhir dari `AppPrefs`, refresh/clear manual |

Tombol-tombol izin membuka halaman Settings sistem masing-masing (aksesibilitas, usage access, overlay, battery optimization, device admin, notification listener) atau meminta izin runtime (lokasi fine/coarse/background, `POST_NOTIFICATIONS` di API 33+), semuanya dibungkus `startActivitySafely()` dengan fallback pesan error.

### Sistem desain

- **Tema:** `Theme.KidGuard` (`Theme.AppCompat.DayNight.NoActionBar`), light + dark lengkap, toggle manual persisten via `AppPrefs.themeMode`.
- **Warna:** palet netral + aksen hijau tua. Light: canvas `#F7F6F2`, surface `#FFFFFF`, aksen `#0F5C4C`. Dark: canvas `#121514`, surface `#1B201E`, aksen `#4CC9B0`. Status: hijau `#145A32` / merah `#9E2A2B` (light).
- **Kontras:** seluruh pasangan teks/latar di atas ambang WCAG AA (terverifikasi di `DESIGN.md`, mis. `text_primary` 17.65:1 di atas putih).
- **Tipografi:** font sistem, skala `sp` via token `dimens` (judul 22sp, seksi 18sp, body 14sp; naik di tablet `sw600dp`/`sw840dp`).
- **Spacing:** padding layar/kartu 16dp, `min_touch_target` 48dp.
- **Tablet:** hanya override `dimens` (padding & ukuran teks) — struktur tetap satu kolom, tidak ada `layout-sw600dp`.

### Overlay

Kedua overlay adalah window `WindowManager` (bukan Activity) dan hanya tampil jika `OverlayManager.canShowOverlay()` true (layanan aksesibilitas aktif **atau** izin "tampil di atas aplikasi lain"):

- **Layar kunci** (`overlay_lock_screen.xml`) — dipicu `/lock`, mode blokir aplikasi, atau restore setelah reboot. Tombol panggilan darurat selalu ada; buka kunci dengan PIN orang tua.
- **Pesan orang tua** (`overlay_parent_message.xml`) — dipicu `/pesan <teks>`, disertai alarm singkat 600 ms, ditutup lewat tombol "Saya Sudah Membaca Pesan Ini".

### Aksesibilitas

Touch target minimum 48dp, status selalu dibarengi label teks (bukan hanya warna), satuan `sp` untuk font scaling, `supportsRtl`, `labelFor` pada input, `importantForAutofill="no"` pada field token/PIN. Catatan: belum ada live-region/`announceForAccessibility` untuk pesan error dinamis.

---

## Lapisan Backend

### Modul

| File | Class | Tanggung jawab |
|---|---|---|
| `service/TelegramMonitorService.kt` | `TelegramMonitorService` | Foreground service pusat: long-polling perintah, kirim notifikasi, cek baterai lemah |
| `service/GuardAccessibilityService.kt` | `GuardAccessibilityService` | Deteksi app aktif, ekstraksi judul YouTube, eksekusi blokir, screenshot, kunci layar |
| `service/WhatsAppMonitorService.kt` | `WhatsAppMonitorService` | Baca notifikasi WhatsApp & teruskan ke Telegram |
| `service/BootCompletedReceiver.kt` | `BootCompletedReceiver` | Hidupkan service setelah `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED` |
| `service/GuardDeviceAdminReceiver.kt` | `GuardDeviceAdminReceiver` | Notifikasi keamanan device admin (anti-uninstall) |
| `telegram/BotCommandRouter.kt` | `BotCommandRouter` | Parsing & eksekusi perintah + whitelist chat ID |
| `telegram/TelegramClient.kt` | `TelegramClient` (object) | Klien HTTP Bot API: `getMe`, `getUpdates`, `sendMessage`, `sendPhoto`, `sendLocation` |
| `util/UsageStatsHelper.kt` | `UsageStatsHelper` (object) | Screen time harian + resolusi label aplikasi |
| `util/LocationHelper.kt` | `LocationHelper` (object) | Titik GPS sekali-jalan (GPS → NETWORK → last-known) |
| `util/OverlayManager.kt` | `OverlayManager` (object) | Window overlay kunci layar & pesan orang tua |
| `data/AppPrefs.kt` | `AppPrefs` | Seluruh state di SharedPreferences + log aktivitas ring-buffer |
| `data/ActivityModels.kt` | `ActivityType`, `ActivityEntry`, `AppUsageSummary`, `TelegramUpdate`, `TelegramApiResult` | Model data & DTO |

### Penyimpanan data

`AppPrefs` memakai SharedPreferences `kidguard_secure_prefs` (`MODE_PRIVATE`): `bot_token`, `parent_chat_id`, `parent_pin`, `device_locked`, `lock_message`, `last_update_offset`, `theme_mode`, `current_app_name`, `current_package`, `current_yt_title`, `low_battery_alert_sent`, `whatsapp_monitor_enabled`, `blocked_keywords`, dan `activity_log_json`.

Log aktivitas disimpan sebagai JSON array (`ActivityEntry`: timestamp, package, nama app, detail, tipe) dengan **ring buffer maksimal 100 entri**, operasi tulis/baca `@Synchronized`. Tipe log: `APP_OPEN`, `YOUTUBE_VIDEO`, `BLOCKED_ATTEMPT`, `WHATSAPP_MESSAGE`, `SYSTEM_EVENT`.

### Izin & API Android

Manifest mendeklarasikan 15 permission, antara lain: `INTERNET`, `FOREGROUND_SERVICE` (+ `FOREGROUND_SERVICE_SPECIAL_USE`, `FOREGROUND_SERVICE_LOCATION`), `RECEIVE_BOOT_COMPLETED`, lokasi (fine/coarse/background), `SYSTEM_ALERT_WINDOW`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `PACKAGE_USAGE_STATS`, `QUERY_ALL_PACKAGES`, `POST_NOTIFICATIONS`, `BIND_NOTIFICATION_LISTENER_SERVICE`.

Komponen utama:
- `GuardAccessibilityService` — `typeWindowStateChanged|typeWindowContentChanged`, `flagReportViewIds`, `canRetrieveWindowContent`, `canTakeScreenshot`, `notificationTimeout=150`
- `TelegramMonitorService` — `foregroundServiceType="specialUse|location"`, `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` di API 34+
- `WhatsAppMonitorService` — `NotificationListenerService`
- `GuardDeviceAdminReceiver` — policy `force-lock`
- `allowBackup="false"` mencegah pencadangan data sensitif

### Model keamanan

- **Whitelist Chat ID** — satu-satunya otentikasi perintah; chat lain ditolak tanpa eksekusi.
- **PIN orang tua** — gerbang aplikasi & overlay kunci (catatan: bila PIN belum diset, overlay kunci bisa dibuka tanpa PIN).
- **Device Admin** — mengunci Uninstall; usaha menonaktifkan admin memicu notifikasi ke orang tua.
- **Overlay tanpa prompt** — memakai `TYPE_ACCESSIBILITY_OVERLAY` saat layanan aksesibilitas aktif.

### Anti-kill & baterai

- Foreground service dengan notifikasi ongoing prioritas rendah (channel `IMPORTANCE_LOW`)
- `START_STICKY` + `BootCompletedReceiver` (`BOOT_COMPLETED`, `QUICKBOOT_POWERON`, `MY_PACKAGE_REPLACED`)
- Long-polling `getUpdates` `timeout=25s` (read timeout 35s); loop jeda 1,5 s saat idle, 5 s saat bot belum dikonfigurasi
- `lastUpdateOffset` persisten agar perintah tidak hilang/terproses dua kali saat restart
- Izin `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` diminta saat setup; cek baterai memakai sticky broadcast sekali per iterasi polling

---

## Build & CI

**Lokal (Android Studio):** buka folder project → Gradle Sync → *Build > Build APK(s)* → output di `app/build/outputs/apk/debug/app-debug.apk`. Panduan lengkap (termasuk pembuatan bot via `@BotFather` dan aktivasi izin di HP anak) ada di [`PANDUAN_INSTALASI_DAN_BOT.md`](PANDUAN_INSTALASI_DAN_BOT.md).

**CI (`.github/workflows/build-apk.yml`):** trigger pada push/PR ke `main`/`master` + `workflow_dispatch`. Runner `ubuntu-latest`: checkout → JDK 17 Temurin → Gradle 8.5 → accept SDK licenses → `gradle assembleDebug --stacktrace` → upload artifact `LayananSistem-Debug-APK` (retensi 14 hari).

---

## Catatan Pengembangan

Temuan dari analisis kode yang berguna untuk kontribusi lanjutan:

1. **Kredensial plaintext** — bot token, chat ID, PIN, dan log aktivitas disimpan di SharedPreferences tanpa enkripsi (`EncryptedSharedPreferences`/Keystore belum dipakai).
2. **`DESIGN.md` belum sepenuhnya sinkron dengan kode** — skala tipografi di dokumen (14/16/20/24sp) berbeda dari `dimens.xml` (14/18/22sp), klaim spacing kelipatan 8dp padahal ada 10dp/20dp, dan ikon peluncur yang dijelaskan sebagai "perisai" ternyata aset gear. Ikon launcher juga belum memakai adaptive icon.
3. **Token warna tak terpakai** — `overlay_card`, `overlay_text_primary`, `overlay_text_secondary`, `overlay_border` didefinisikan tapi tidak direferensikan layout mana pun.
4. **String hardcoded** — beberapa pesan validasi di `MainActivity` masih literal, belum di `strings.xml`.
5. **Tanpa test framework** — verifikasi API saat ini manual (serve + cek perilaku); belum ada unit/UI test.
6. **Batasan platform** — screenshot hanya API 30+ dan bisa ditolak sistem (mis. konten DRM); `getUpdates` hanya memproses pesan teks; tidak ada retry/backoff eksplisit untuk kegagalan jaringan.

---

## Dokumentasi Terkait

- [`DESIGN.md`](DESIGN.md) — arahan desain UI: palet, rasio kontras WCAG, dial ENERGY/RHYTHM/MOTION
- [`implementation_plan.md`](implementation_plan.md) — rencana arsitektur, daftar fitur, detail teknis deteksi
- [`PANDUAN_INSTALASI_DAN_BOT.md`](PANDUAN_INSTALASI_DAN_BOT.md) — panduan @BotFather, build APK, aktivasi izin, daftar perintah
