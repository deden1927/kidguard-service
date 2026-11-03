# DESIGN.md: KidGuard Android UI Direction

Status: **draft without direction** (dibuat menggunakan default jujur R-37 karena dokumen arahan visual eksternal belum disediakan sebelumnya).

## Design Read
Reading this as: utility parental-control Android setup and screen-intervention app for parents and children, in a high-contrast civic-utility style (`draft without direction`), dial `ENERGY 1 / RHYTHM 1 / MOTION 1`.

## Three Dials
- **ENERGY: 1 (Calm)**: Tampilan tenang, lugas, dan fokus pada kejelasan status izin serta kendali orang tua tanpa dekorasi berlebihan.
- **RHYTHM: 1 (Uniform, Predictable)**: Susunan kartu vertikal satu kolom yang konsisten agar orang tua dapat menyelesaikan langkah konfigurasi secara berurutan tanpa kebingungan.
- **MOTION: 1 (Static / State-Only)**: Tanpa animasi berulang atau efek transisi dekoratif; perubahan visual hanya terjadi saat status izin, fokus input, atau status koneksi berubah.

## Color System (WCAG AA Verified via `contrast-check.py`)
Palet dibatasi pada 2 warna inti netral + 1 warna aksen fungsional + indikator semantik yang selalu didampingi label teks (`[AKTIF]` / `[BELUM AKTIF]`).

### Light Mode (`values/colors.xml`)
- `bg_canvas`: `#F7F6F2` (Latar utama kertas hangat, mengurangi silau)
- `bg_surface`: `#FFFFFF` (Permukaan kartu konfigurasi)
- `text_primary`: `#141A17` (Rasio kontras `17.65:1` di atas `#FFFFFF`, `16.32:1` di atas `#F7F6F2` -> **PASS AA/AAA**)
- `text_secondary`: `#45504B` (Rasio kontras `8.39:1` di atas `#FFFFFF`, `7.76:1` di atas `#F7F6F2` -> **PASS AA/AAA**)
- `text_hint_and_border`: `#596560` (Rasio kontras `6.08:1` di atas `#FFFFFF`, `5.62:1` di atas `#F7F6F2` -> **PASS AA** untuk teks maupun batas komponen 3:1)
- `accent_primary`: `#0F5C4C` dengan teks `#FFFFFF` (Rasio kontras `7.91:1` -> **PASS AA/AAA**)
- `status_ok`: `#145A32` di atas `#FFFFFF` (Rasio kontras `8.27:1` -> **PASS AA/AAA**)
- `status_danger`: `#9E2A2B` di atas `#FFFFFF` (Rasio kontras `7.45:1` -> **PASS AA/AAA**)

### Dark Mode (`values-night/colors.xml`)
- `bg_canvas`: `#121514` (Latar utama batu tulis gelap)
- `bg_surface`: `#1B201E` (Permukaan kartu konfigurasi)
- `text_primary`: `#F0F4F2` (Rasio kontras `14.88:1` di atas `#1B201E`, `16.56:1` di atas `#121514` -> **PASS AA/AAA**)
- `text_secondary`: `#B8C4BE` (Rasio kontras `9.18:1` di atas `#1B201E`, `10.22:1` di atas `#121514` -> **PASS AA/AAA**)
- `text_hint_and_border`: `#7E8F87` (Rasio kontras `4.85:1` di atas `#1B201E` -> **PASS AA**)
- `accent_primary`: `#4CC9B0` dengan teks `#0D1F1B` (Rasio kontras `8.37:1` -> **PASS AA/AAA**)
- `status_ok`: `#7DCEA0` di atas `#1B201E` (Rasio kontras `8.81:1` -> **PASS AA/AAA**)
- `status_danger`: `#FF8A8A` di atas `#1B201E` (Rasio kontras `7.28:1` -> **PASS AA/AAA**)

## Keputusan Desain Satu Baris (R-31)
- **Warna**: Hijau tua (`#0F5C4C` / `#4CC9B0`) dipilih sebagai satu-satunya warna aksen karena memberi sinyal otoritas utilitas pada tombol aksi utama sekaligus memenuhi rasio kontras di atas 7.9:1.
- **Tata Letak (Layout)**: Urutan satu kolom vertikal mengikuti alur kerja nyata orang tua: verifikasi PIN, konfigurasi kredensial Telegram, daftar izin sistem, lalu pantauan log lokal.
- **Tipografi**: Menggunakan font sistem `sans-serif` dengan skala `sp` (`14sp` isi, `16sp` subjudul, `20sp` judul bagian, `24sp` judul layar) agar mendukung pembesaran teks aksesibilitas Android hingga 200% tanpa terpotong.
- **Jarak (Spacing)**: Skala kelipatan `8dp` (`8dp` antar elemen terkait, `16dp` padding dalam kartu, `24dp` pemisah antar kelompok tugas) memisahkan kelompok pengaturan tanpa menyisakan ruang kosong berlebihan di layar ponsel.
- **Penggunaan Kartu**: Kartu berbatas tegas (`1dp` border `#596560`/`#7E8F87`, radius `12dp`, tanpa bayangan mengambang) dipakai hanya untuk mengelompokkan empat tugas berbeda: Gerbang PIN, Kredensial Bot, Checklist Izin, dan Log Aktivitas.
- **Ikon & Aset Visual**: Tidak menggunakan ikon dekoratif AI atau emoji pada antarmuka; hanya memakai satu vektor perisai geometris sederhana untuk ikon peluncur aplikasi dan penanda teks eksplisit (`[AKTIF]` / `[BELUM AKTIF]`).
