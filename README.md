# Proseody

*prose* + *prosody*: it turns written text into the rhythm and melody of speech. (Formerly "Sesli"; the package id `dev.aliberke.sesli` is kept so installs upgrade in place.)

A personal read-aloud app for Android: import text, web articles, PDFs, EPUBs, Word files or photos of pages, then listen with live sentence and word highlighting, using system voices or high-quality offline neural voices.

## Install
1. On your phone (Android 10 or newer, 64-bit), download `proseody-<version>-arm64.apk` from the [latest release](https://github.com/MechanicalMindVonMisses/proseody/releases/latest).
2. Open it and, when Android asks, allow your browser to install unknown apps. If Play Protect warns about an unknown developer, choose *Install anyway*.
3. Open Proseody, add something to read, then tap the voice icon in the reader to download the offline neural voices if you want them: Kokoro for English (334 MB) and Fahrettin for Turkish (64 MB). Wi-Fi recommended. System voices work without any download.

## Features
- Library with search, rename, delete, per-document progress and listening-time estimate
- Import: paste text, web link (article extraction, PDF links too), files (PDF, EPUB, DOCX, TXT, Markdown, HTML), photos and camera scans (on-device ML Kit OCR, scanned-PDF OCR fallback)
- Share to Proseody from any app (text, links, files, images)
- Reader: current sentence + word highlight, tap any word to jump there, auto-follow with "Back to reading"
- Player: play/pause, previous/next sentence, seek bar, speed 0.5×–4×, voice and engine picker (any installed Android TTS engine; Google TTS preferred), per-language voice memory, automatic language detection
- Offline neural voices (sherpa-onnx 1.13.8, downloaded in-app from the sherpa-onnx `tts-models` release): Kokoro v1.0 fp32 for English (11 curated US/UK speakers, default Heart) and Piper `tr_TR-fahrettin-medium` for Turkish. A newly installed model becomes the default voice for its language once.
- Background playback with media notification, lock-screen and headset controls; pauses on calls and when headphones are unplugged

## Layout
- `tts/SpeechEngine.kt` — sentence queue over `TextToSpeech`, word ranges from `onRangeStart` (time-based estimate for engines without it), audio focus
- `neural/` — model catalog, downloader (tar.bz2 via commons-compress into `Android/data/<pkg>/files/models/<id>/`), and `NeuralSpeaker`: one thread synthesizes ahead, one streams to AudioTrack and fires start/word/done events at the real playback position; word times are spread over the piece by word length and punctuation pauses
- `playback/PlaybackService.kt` — Media3 `SimpleBasePlayer` + `MediaSessionService` wrapping the engine
- `importers/` — web/HTML extraction (Jsoup), PDF (PdfBox-Android), EPUB, DOCX, Markdown, OCR (ML Kit)
- `text/Text.kt` — segmentation (ICU sentence breaks), cleanup, language detection
- `data/` — Room library; document text stored in `files/docs/<id>.txt`
- `ui/` — Compose screens (library, reader, sheets)

## Build and install
Requires JDK 17+ and the Android SDK (platform 35); set `sdk.dir` in `local.properties` or `ANDROID_HOME`.
```sh
./gradlew :app:assembleRelease          # Windows: gradlew.bat :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk   # phones; the x86_64 APK is for emulators
```
The first build downloads the sherpa-onnx AAR (~48 MB) from its GitHub release and verifies its SHA-256. Release builds are R8-minified and split per ABI. They are signed with the maintainer's private key when `~/.android-keys/proseody-release.properties` exists (it is never committed), otherwise with your local debug key; the `personal` build type is always debug-signed. On Windows, if a build fails on a locked lint jar, run `./gradlew --stop` first.

## Neural voice notes (Galaxy S23)
- Kokoro fp32: loads in ~1.1 s, synthesizes at ~0.65× real time (int8 was slower, ~0.75×). Playback starts with a ~30-char piece and pieces grow 30% each (cap 140 chars), so the next piece is always ready: no gaps at 1× and 1.5×; above ~2× expect stutter.
- Fahrettin: loads in ~0.6 s, ~0.1× real time, fine at any speed.
- Models copied in with `adb push` are invisible to the app (Android 11+ blocks shell-created folders in Android/data); install models through the app.
- `adb logcat -s NeuralSpeaker` shows load times, per-piece synthesis time and any "Gap" waits.

## License and credits
Proseody's own code is MIT-licensed (see `LICENSE`). It builds on:
- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0) for on-device neural TTS, fetched at build time
- [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) (Apache-2.0) and the Piper voice [fahrettin](https://github.com/rhasspy/piper) (trained on the CC0 NabuCasa voice dataset), downloaded by the app from the sherpa-onnx model releases on first use
- PdfBox-Android, Jsoup, Apache Commons Compress (Apache-2.0); ML Kit Text Recognition (Google, ML Kit terms); AndroidX/Jetpack Compose and Media3 (Apache-2.0)

## Ideas for later
- Exact word timing for Piper/Kokoro from the model's per-phoneme durations instead of the length-weighted estimate
- Daily listening goals / reminders, bookmarks and notes, sleep timer
