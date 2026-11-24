# HearIt

Lightweight, 100% free Pixel-style **Now Playing** app built for **Samsung Galaxy S23 Ultra (One UI)**.

## Key Features
- **Screen-On Instant Recognition:** Listens only when you wake your screen (0% CPU & 0% mic while off) and skips automatically if the phone is in your pocket or on a call.
- **On-Device YAMNet Gatekeeper:** Uses `yamnet.tflite` (0.975s 16 kHz mono) to ignore speech and silence before fingerprinting.
- **100% Free Shazam-Compatible Fingerprinting:** Pure-Kotlin constellation signature generator (`ShazamSignature.kt`) + public discovery lookup + offline Room queue.
- **Pinned Lock-Screen Card:** Separate from the background listener so swiping away a song card never stops HearIt from listening. Includes One UI Live Update (`android.requestPromotedOngoing`) support plus 1-tap **Spotify** and **YT Music** buttons.
- **Spotify & YouTube Music Auto-Logger:** Reads `MediaSession` metadata with 0% mic and 0% battery.
- **Quick Settings Tiles:** **HearIt Now** (instant ID / quick re-arm) and **HearIt Earbuds** (`AudioPlaybackCapture` for YouTube/Reels while wearing headphones).
- **Audio Library:** Date-grouped history, search, favorites, play counts, and **CSV / JSON export**.

## Mobile-Only Build (GitHub Actions)
1. Upload the contents of this folder to a new GitHub repository.
2. Open the **Actions** tab and run **Build HearIt APK** (automatically downloads `yamnet.tflite` and builds `app-release.apk` for `arm64-v8a`).
3. Download `HearIt-v1.0.0-arm64` from the workflow artifacts and install on your S23 Ultra.
