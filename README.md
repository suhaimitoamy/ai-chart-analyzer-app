# AI Chart Analyzer App

Aplikasi Android untuk analisis chart XAU/USD berbasis candle real-time dan rule-based ICT/SMC engine.

Aplikasi ini tidak bergantung pada Gemini. Analisis utama dibuat secara lokal dari candle yang tersimpan, live tick TwelveData, dan rule engine ICT.

## Fungsi Utama

- Mengambil harga live XAU/USD dari TwelveData WebSocket.
- Menyimpan candle ke database lokal Room.
- Membentuk candle multi-timeframe:
  - M1
  - M5
  - M15
  - M30
  - H1
  - H4
  - D1
  - W1
- Mengambil historical candle melalui TwelveData REST API.
- Menjalankan analisis hanya saat tombol **Analisis ICT Sekarang** ditekan.
- Menampilkan hasil analisis di dalam aplikasi, bukan hanya di terminal.
- Menyimpan riwayat hasil analisis ICT.
- Membuat laporan PDF performa trading lokal.

## Engine Analisis

Analisis saat ini memakai local rule-based ICT engine, bukan AI vision dan bukan Gemini.

Engine membaca:

- Live price dari cache tick terakhir.
- Candle LTF, MTF, dan HTF.
- Internal swing dan external swing.
- BOS / CHoCH / MSS.
- Fair Value Gap.
- Liquidity sweep.
- Qualified Order Block.
- Premium / Discount.
- OTE zone.
- Active POI.

## Akurasi Harga

Harga analisis memakai live tick terakhir dari TwelveData jika tersedia.

Jika live tick belum masuk, aplikasi fallback ke candle terakhir dari snapshot.

Perbedaan kecil dengan MT5 masih bisa terjadi karena feed broker XAUUSD.sc dan feed TwelveData tidak selalu identik.

## Deteksi POI

### Fair Value Gap

FVG dihitung memakai strict 3-candle logic:

- Bullish FVG: candle kanan low lebih tinggi dari candle kiri high.
- Bearish FVG: candle kanan high lebih rendah dari candle kiri low.

Filter tambahan:

- Minimal gap berbasis ATR.
- Candle tengah harus impulsif.
- FVG yang sudah filled tidak dianggap active POI.
- FVG terlalu jauh dari harga live tidak dijadikan POI aktif.

### Liquidity

Liquidity dihitung memakai:

- Pivot high dan pivot low.
- Pivot cluster.
- ATR tolerance.
- Wick sweep.
- Close reclaim.

### Order Block

Order Block tidak lagi sekadar candle bullish/bearish terakhir.

OB hanya dianggap qualified jika lolos beberapa confluence:

- Ada displacement.
- Ada opposing candle sebelum displacement.
- Selaras dengan bias.
- Dekat dengan harga aktif.
- Memiliki dukungan sweep, BOS/MSS, FVG, atau premium/discount alignment.

Jika OB tidak valid, aplikasi tidak memaksa zona OB muncul.

## Alur Kerja Aplikasi

```text
TwelveData REST API → Historical Candle → Database Room
TwelveData WebSocket → Live Tick → CandleBuilder → Multi-timeframe Candle
User klik Analisis ICT Sekarang → Local ICT Engine → JSON Result → UI Card
```

## API Key

Yang wajib:

```text
TWELVE_API_KEY
```

DeepSeek key bersifat opsional. Jika kosong, aplikasi tetap memakai local rule engine.

## Menjalankan Project

1. Buka project di Android Studio atau Antigravity.
2. Pastikan `.env` tersedia jika project memakai secrets plugin.
3. Isi TwelveData API key dari menu Settings di aplikasi.
4. Build dan jalankan APK.
5. Tekan tombol start bot.
6. Tunggu log REST candle tersimpan.
7. Klik **Analisis ICT Sekarang**.

## Catatan Penting

Aplikasi ini adalah alat bantu mapping chart, bukan sistem auto-entry.

Hasil analisis tetap perlu divalidasi manual sebelum digunakan untuk keputusan trading.
