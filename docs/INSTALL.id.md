# Instal Clear TV Islamic langkah demi langkah

<p><a href="INSTALL.md">English</a> · <b>Bahasa Indonesia</b> · <a href="../README.id.md">Kembali ke README</a></p>

Panduan ini menjelaskan cara memasang launcher melalui jaringan Wi-Fi. Anda memerlukan Android TV atau Google TV Android 8 ke atas, komputer, dan jaringan yang sama.

## 1. Siapkan TV

1. Buka **Settings → Device Preferences → About**, lalu tekan **Build** tujuh kali untuk mengaktifkan Developer options.
2. Di **Developer options**, aktifkan **USB debugging** (serta Network/ADB debugging jika tersedia).
3. Catat alamat IP TV dari **Settings → Network & Internet**.

## 2. Siapkan komputer

Pasang Python dari [python.org](https://www.python.org/downloads/), unduh repositori dari [GitHub](https://github.com/Andy-clou/cleartv-Islamic), lalu buka terminal di folder repo.

Buat lingkungan Python dan pasang kebutuhan alat:

```bash
python3 -m venv tools/venv
# macOS/Linux
tools/venv/bin/pip install -r tools/requirements.txt
# Windows PowerShell: tools\venv\Scripts\pip install -r tools\requirements.txt
```

Sambungkan ke TV dengan alamat IP yang dicatat sebelumnya:

```bash
# macOS/Linux
export TV_HOST=192.168.1.50
tools/venv/bin/python -I tools/tv.py --probe
# Windows PowerShell
$env:TV_HOST = "192.168.1.50"
tools\venv\Scripts\python -I tools\tv.py --probe
```

Saat TV meminta izin debugging, pilih **Always allow from this computer** dan tekan OK. Jalankan perintah probe sekali lagi.

## 3. Instal launcher

Gunakan APK siap pakai jika tersedia:

```bash
# macOS/Linux
APK=dist/clear-tv-islamic.apk launcher/build-and-install.sh
# Windows PowerShell
launcher\build-and-install.ps1 -Apk dist\clear-tv-islamic.apk
```

Tekan tombol Home pada remote setelah skrip menyatakan instalasi selesai.

## 4. Atur tema Islami

Folder `themes/islamik` sudah berisi konfigurasi contoh. Pengaturan utamanya:

```json
{
  "auto_location": true,
  "weather": true,
  "download_masjid": true,
  "download_azan": true,
  "azan": true,
  "reduced_motion": true
}
```

Launcher meminta izin lokasi untuk menentukan kota/kabupaten. Jadwal salat dan tanggal Hijriah berasal dari API myQuran; cuaca berasal dari Open-Meteo. Foto masjid untuk pelindung layar dan audio azan bawaan diunduh ke penyimpanan lokal aplikasi jika belum tersedia. File azan khusus dari tema tetap dipakai dan tidak ditimpa. Koneksi internet dibutuhkan untuk mengambil data, audio, atau foto; data yang sudah tersimpan dapat ditampilkan dari cache.

Untuk mengirim tema ke TV:

```bash
TV_HOST=192.168.1.50 tools/theme.sh themes/islamik
```

Di Windows PowerShell:

```powershell
tools\theme.ps1 -TvHost 192.168.1.50 themes\islamik
```

Aktifkan screensaver launcher melalui pengaturan TV atau jalankan:

```bash
settings put secure screensaver_components com.gathin.tvhome/.ClockDream
```

## Bantuan

Jika koneksi gagal, pastikan komputer dan TV berada di Wi-Fi yang sama, USB debugging aktif, alamat IP benar, dan permintaan izin telah disetujui di TV. Lihat [dokumentasi tema](../themes/README.md) dan [daftar perangkat](DEVICES.md) untuk informasi tambahan.
