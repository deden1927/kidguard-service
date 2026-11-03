# Panduan Pembuatan Bot Telegram, Build APK, dan Instalasi KidGuard

Dokumen ini menjelaskan langkah teknis pembuatan Bot Telegram pribadi, pengambilan Chat ID Orang Tua, pembuatan file APK di Android Studio, serta pengaturan izin pada perangkat anak.

---

## Bagian 1: Membuat Bot Telegram & Mengambil Token (`@BotFather`)

1. Buka aplikasi Telegram di ponsel Orang Tua.
2. Ketik `@BotFather` pada kolom pencarian, lalu pilih akun resmi bertanda centang biru.
3. Ketuk tombol **Start** atau kirim pesan `/start`.
4. Kirim perintah `/newbot`.
5. Masukkan nama tampilan bot sesuai keinginan Anda (contoh: `Pantauan HP Anak`).
6. Masukkan username untuk bot yang diakhiri kata `bot` tanpa spasi (contoh: `pantauan_anak_budi_bot`).
7. `@BotFather` akan membalas dengan pesan berisi **HTTP API Token** (contoh format: `7123456789:AAHxyz1234567890abcdef`).
8. Salin kode Token tersebut.
9. Buka tautan bot baru Anda yang diberikan oleh `@BotFather` (contoh: `t.me/pantauan_anak_budi_bot`), lalu ketuk tombol **Start** agar bot diizinkan mengirim pesan ke akun Telegram Anda.

---

## Bagian 2: Mengetahui Angka Chat ID Orang Tua

Aplikasi KidGuard memakai sistem daftar putih (*Whitelist Chat ID*). Hanya akun Telegram dengan Chat ID yang didaftarkan yang dapat mengirim perintah dan menerima laporan.

1. Di aplikasi Telegram Orang Tua, cari bot `@userinfobot` atau `@RawDataBot`.
2. Ketuk **Start** atau kirim pesan apa saja ke bot tersebut.
3. Bot akan membalas dengan angka **Id** akun Telegram Anda (contoh: `987654321`).
4. Catat angka tersebut untuk dimasukkan ke kolom **Chat ID Telegram Orang Tua** di aplikasi KidGuard.

---

## Bagian 3: Cara Build File APK di Android Studio

1. Buka **Android Studio** di komputer Anda.
2. Pilih **Open**, lalu arahkan ke folder proyek:
   `D:\DEDEN_PROJECT\Monitoring Device Anak`
3. Tunggu proses **Gradle Sync** selesai (pastikan komputer terhubung ke internet untuk mengunduh dependensi AndroidX dan Kotlin Coroutines).
4. Pada bilah menu atas Android Studio, pilih **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. Setelah proses kompilasi selesai, klik tautan **locate** pada notifikasi kanan bawah, atau ambil file APK langsung di lokasi:
   `app\build\outputs\apk\debug\app-debug.apk`
6. Kirimkan file `app-debug.apk` tersebut ke ponsel anak, atau hubungkan ponsel anak ke komputer menggunakan kabel USB (dengan fitur **USB Debugging** aktif) lalu klik tombol **Run ('app')** di Android Studio.

---

## Bagian 4: Setup Awal & Aktivasi Izin di Ponsel Anak

Setelah aplikasi **KidGuard** terpasang di ponsel anak, lakukan urutan berikut:

1. **Masukkan Kredensial Telegram & PIN**:
   - Isi **Token Bot Telegram** dari Bagian 1.
   - Isi **Chat ID Telegram Orang Tua** dari Bagian 2.
   - Isi **PIN Proteksi Aplikasi** (4 sampai 8 digit angka) agar anak tidak dapat membuka halaman pengaturan KidGuard.
   - Ketuk tombol **Simpan Konfigurasi & Jalankan Layanan**.
   - Ketuk **Kirim Pesan Tes ke Telegram** dan pastikan pesan konfirmasi beserta papan tombol cepat muncul di Telegram Orang Tua.

2. **Aktifkan Seluruh Izin Sistem Wajib** (pastikan semua berstatus `[AKTIF]`):
   - **Layanan Aksesibilitas KidGuard**: Ketuk tombol **Buka Pengaturan Aksesibilitas**, pilih **Aplikasi Terinstal / Layanan yang Diunduh**, pilih **KidGuard Pemantau Aktivitas**, lalu nyalakan sakelarnya.
     *(Catatan untuk Android 13 ke atas: jika sakelar aksesibilitas terkunci dengan pesan "Pengaturan Terbatas / Restricted Setting", buka **Pengaturan Android > Aplikasi > KidGuard**, ketuk ikon titik tiga di pojok kanan atas, pilih **Izinkan Pengaturan Terbatas**, lalu kembali mengaktifkan Aksesibilitas).*
   - **Izin Data Penggunaan (Usage Stats)**: Ketuk tombolnya, cari **KidGuard**, lalu aktifkan izin akses data penggunaan.
   - **Izin Lokasi Presisi (GPS)**: Ketuk tombolnya, pilih **Izinkan sepanjang waktu (Allow all the time)** dan aktifkan lokasi akurat.
   - **Izin Tampil di Atas Aplikasi Lain**: Ketuk tombolnya, cari **KidGuard**, lalu izinkan tampil di atas aplikasi lain.
   - **Abaikan Optimasi Baterai (Anti-Kill)**: Ketuk tombolnya, lalu pilih **Izinkan / Jangan optimalkan** agar sistem tidak menutup koneksi bot di latar belakang.
   - **Administrator Perangkat (Cegah Hapus Aplikasi)**: Ketuk tombolnya, lalu pilih **Aktifkan (Activate)** agar tombol Uninstall aplikasi terkunci.
   - **Akses Notifikasi (Pemantau Pesan WhatsApp)**: Ketuk tombolnya, cari **Layanan Sistem**, lalu nyalakan akses notifikasi. Pesan WhatsApp baru (nama pengirim/grup + cuplikan pesan) akan otomatis diteruskan ke chat Telegram orang tua. Fitur ini dapat dimatikan kapan saja dengan perintah `/wa off`.

3. **Kunci Halaman Pengaturan**:
   - Ketuk tombol **Kunci Kembali Halaman Pengaturan Ini** sebelum menyerahkan ponsel kepada anak.

---

## Bagian 5: Daftar Perintah Telegram untuk Orang Tua

Kirimkan perintah berikut dari chat Telegram Anda ke bot:

- `/menu` atau `/start` : Menampilkan papan tombol cepat di Telegram.
- `/status` : Menampilkan aplikasi yang sedang dibuka anak saat ini, judul video YouTube aktif, persentase baterai, status cas, WiFi/data seluler, dan daftar blokir.
- `/yt` atau `/riwayat` : Menampilkan 15 riwayat video YouTube dan aplikasi terakhir yang dibuka beserta jamnya.
- `/screentime` : Menampilkan rekap durasi pemakaian per aplikasi sejak pukul 00:00 hari ini.
- `/ss` : Mengambil tangkapan layar (*screenshot*) ponsel anak saat itu juga dan mengirimkannya sebagai foto ke chat.
- `/lokasi` : Mengambil titik GPS perangkat anak dan mengirim peta interaktif beserta tautan Google Maps.
- `/pesan <teks>` : Memunculkan jendela peringatan di tengah layar ponsel anak disertai bunyi alarm singkat (contoh: `/pesan Waktunya belajar, berhenti main game sekarang`).
- `/lock` : Mengunci layar ponsel anak dengan layar penuh *"Waktunya Istirahat / Belajar"* (panggilan darurat tetap dapat diakses).
- `/unlock` : Membuka kembali kunci layar ponsel anak.
- `/block <nama>` : Memblokir aplikasi atau game tertentu (contoh: `/block roblox` atau `/block tiktok`).
- `/unblock <nama>` : Membuka kembali blokir aplikasi atau game (contoh: `/unblock roblox`).
- `/wa` : Menampilkan status pemantauan pesan WhatsApp.
- `/wa on` : Menyalakan pemantauan pesan WhatsApp (nama pengirim/grup + cuplikan pesan diteruskan ke chat).
- `/wa off` : Mematikan pemantauan pesan WhatsApp.
