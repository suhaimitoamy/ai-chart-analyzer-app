# XAUUSD ICT Chart Analyzer App

Aplikasi Android untuk analisa chart XAU/USD berbasis konsep ICT / Smart Money Concepts dengan candle real-time, rule-based engine lokal, dan market event feed.

Aplikasi ini dibuat untuk membantu mapping market secara real-time, bukan untuk simulasi backtest.

## Fungsi Utama

- Membaca harga real-time XAU/USD dari Twelve Data
- Menyimpan candle ke database lokal
- Membentuk candle multi-timeframe melalui CandleBuilder
- Analisa ICT hanya berjalan saat tombol **Analisis ICT Sekarang** diklik
- AI berbayar tidak wajib digunakan karena analisa utama sudah memakai rule-based SMC engine lokal
- Terminal menampilkan event market secara otomatis
- Session / killzone otomatis mengikuti timezone dan DST
- History Trade hanya mencatat setup yang sudah menyentuh TP atau STOP

## Timeframe

Aplikasi mendukung beberapa timeframe:

- M1
- M5
- M15
- M30
- H1
- H4
- D1
- W1

## Data Candle

Saat bot dijalankan, aplikasi akan:

1. Mengambil historical candle melalui REST API
2. Menyimpan candle ke Room Database
3. Menjalankan WebSocket real-time
4. Membentuk candle baru dari tick real-time
5. Menyimpan candle yang sudah close ke database lokal

Candle tidak dibuat ulang dari screenshot. Candle dibangun dari data harga real-time.

## ICT / SMC Engine

Engine lokal membaca candle dan melakukan mapping:

- Market Structure
- BOS
- MSS / CHoCH
- Liquidity
- Buy-side Liquidity
- Sell-side Liquidity
- Liquidity Sweep
- Fair Value Gap
- Order Block
- Premium / Discount
- Equilibrium
- OTE Zone
- Displacement
- Trade Setup

## POI Status

POI dibagi menjadi dua status:

### ACTIVE

POI dekat dengan harga live dan masih relevan untuk diperhatikan.

### CONTEXT

POI valid tetapi jaraknya masih jauh dari harga live.

POI ini tetap ditampilkan agar user tidak kehilangan konteks market.

Dengan sistem ini, OB dan FVG tidak langsung dihapus hanya karena jaraknya jauh.

## Terminal Market Event Feed

Terminal tidak hanya menampilkan status bot, tetapi juga event market seperti:

- Candle closed
- BOS
- MSS
- CISD
- Liquidity sweep
- FVG formed
- Order Block detected
- Premium / Discount change
- Displacement candle
- Setup active
- TP1 reached
- TP2 reached
- STOP reached

Terminal berfungsi sebagai live market journal.

## Session dan Killzone

Session berjalan otomatis berdasarkan waktu real:

- Asian Kill Zone
- London Judas Swing
- London Open Kill Zone
- New York Judas Swing
- New York Open Kill Zone
- Silver Bullet
- Swing Session

Aplikasi menyesuaikan perubahan waktu seperti EST, EDT, BST, dan DST secara otomatis.

## ICT Concepts Covered

Dashboard menampilkan status konsep ICT yang sedang aktif:

- Market Structure
- Order Block
- Fair Value Gap
- Liquidity
- Premium / Discount
- Kill Zones
- Trade Setup

Setiap konsep menampilkan:

- Status aktif / tidak aktif
- Timeframe
- Level atau zona harga
- Ringkasan kondisi market

## History Trade

History Trade hanya mencatat trade setelah setup selesai.

Alurnya:

1. User klik **Analisis ICT Sekarang**
2. Jika setup valid, Terminal menampilkan **SETUP ACTIVE**
3. Aplikasi memantau harga live
4. Jika TP1 tercapai, Terminal memberi notifikasi
5. Jika TP2 atau STOP tercapai, hasil masuk ke History Trade

History Trade tidak lagi dipakai sebagai jurnal analisis biasa.

## API Key

Aplikasi membutuhkan Twelve Data API Key untuk data harga real-time.

DeepSeek API Key bersifat opsional karena analisa utama sudah memakai rule-based local engine.

## Batasan

Aplikasi ini bukan robot auto-trade.

Aplikasi tidak membuka posisi otomatis.

Aplikasi hanya membantu:

- Membaca market
- Menyimpan candle
- Mapping ICT
- Memberi informasi event market
- Menampilkan setup jika valid
- Mencatat hasil setup berdasarkan harga live

Keputusan entry tetap berada pada user.

## Status Project

Project ini masih dalam tahap pengembangan.

Fokus utama saat ini:

- Menstabilkan tampilan UI
- Merapikan dashboard
- Menyempurnakan detail market event
- Membuat hasil analisa lebih mudah dibaca
- Menyamakan pengalaman penggunaan seperti dashboard TradingView-style
- Memperbaiki bug kecil yang muncul setelah testing di device
