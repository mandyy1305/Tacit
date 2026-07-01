# Antiwispr

Personal, sideloaded Android tool that identifies which WhatsApp voice note is playing (Shazam-style
acoustic fingerprint over a persistent on-disk index) and transcribes it on-device with whisper-small
(sherpa-onnx). Internal audio via MediaProjection + AudioPlaybackCapture, with a mic foreground-service
fallback when the screen isn't shared. WhatsApp play-taps are detected via an accessibility service.

> Note: this reads WhatsApp's private data (accessibility + audio capture + file access). It's a
> personal/sideload experiment, not a Play-Store-distributable app.

## Build prerequisite: vendor the sherpa-onnx AAR

The on-device ASR uses the prebuilt **sherpa-onnx** Android AAR. It's ~54 MB, so it is **not** committed
to the repo (it's git-ignored). Download the pinned release once into `app/libs/` before building:

```bash
# from the repo root
mkdir -p app/libs
curl -L -o app/libs/sherpa-onnx.aar \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.3/sherpa-onnx-1.13.3.aar
```

`app/build.gradle.kts` references it via `implementation(files("libs/sherpa-onnx.aar"))`. Without it the
build will fail to resolve `com.k2fsa.sherpa.onnx.*`.

## Whisper model (downloaded at runtime, not in the repo)

The whisper-small model (~360 MB) is **not** bundled either. In the app, tap **Download Whisper model**
once; it fetches three files (`small-encoder.int8.onnx`, `small-decoder.int8.onnx`, `small-tokens.txt`)
from `huggingface.co/csukuangfj/sherpa-onnx-whisper-small` into the app's `filesDir`.

## First-run setup (in the app)

1. Grant permissions: microphone, overlay (SYSTEM_ALERT_WINDOW), all-files access, notifications.
2. Enable the accessibility service (Settings → Accessibility → Antiwispr).
3. Download the Whisper model.
4. Build the fingerprint index (scans the WhatsApp Voice Notes folder).
5. Optional: start a screen-share session for best capture accuracy (mic fallback works without it).

Then open a WhatsApp chat and play a voice note.

## Notes

- minSdk 30, targetSdk 36. APK packages arm64-v8a only (see `abiFilters`); add `x86_64` for an emulator.
- Transcripts are cached on-device and searchable in-app. New notes are auto-transcribed; the existing
  backlog is transcribed on-demand (the first time you play each).
