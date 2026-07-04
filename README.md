# TACIT

*Every voice note, read.*

Personal, sideloaded Android tool that identifies which WhatsApp voice note is playing (Shazam-style
acoustic fingerprint over a persistent on-disk index) and transcribes it on-device with whisper-small
(sherpa-onnx). Internal audio via MediaProjection + AudioPlaybackCapture, with a mic foreground-service
fallback when the screen isn't shared. WhatsApp play-taps are detected via an accessibility service.
The transcript appears in a floating card right over the chat.

UI is 100% Jetpack Compose ("Editorial Ink" design system — Fraunces + Inter, paper/ink/amber),
with a guided onboarding flow, home dashboard, transcript search/reader, and settings.
Transcripts can be deleted from the reader view to re-transcribe on-demand.

**Summaries (optional):** an on-device LLM (Qwen2.5-1.5B-Instruct via MediaPipe LLM Inference,
one-time ~1.6 GB download from `litert-community`) turns each transcript into a short summary
plus action items, in the note's own language. Both the overlay card and the in-app reader have
Summary | Transcript tabs; play-tap transcripts summarize automatically, older notes on first open.

**Cloud (optional):** sign in with Google (Firebase Auth, config via `app/src/main/assets/google-services.json`
— git-ignored) against the companion [tacit-cloud](../tacit-cloud) Go server for transcript
backup/sync across installs, cloud transcription (Sarvam AI — sharper Hinglish), and cloud
summaries (gpt-4o-mini). Everything falls back to the on-device path when signed out or offline,
and the on-device models can be deleted from Settings to reclaim ~2 GB once cloud is set up.

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

The whisper-small model (~360 MB) is **not** bundled either. Onboarding downloads it on the
"Bring the words on-device" step (three files — `small-encoder.int8.onnx`, `small-decoder.int8.onnx`,
`small-tokens.txt` — from `huggingface.co/csukuangfj/sherpa-onnx-whisper-small` into the app's `filesDir`).
It can be re-downloaded from Settings → Whisper model.

## First-run setup (in the app)

The app walks you through everything on first launch — each permission with the reason it's needed,
then the model download and the fingerprint index, auto-advancing as steps complete:

1. Microphone, overlay, all-files access, notifications (optional).
2. Accessibility service (deep-links to Settings → Installed apps → TACIT).
3. Whisper model download (~360 MB, progress shown).
4. Fingerprint index of the WhatsApp Voice Notes folder.

Then open a WhatsApp chat and play a voice note. Optional: start **Precision listening**
(screen share) from Home for the most accurate capture — mic fallback works without it.

## Notes

- minSdk 30, targetSdk 36. APK packages arm64-v8a only (see `abiFilters`); add `x86_64` for an emulator.
- Compose is enabled through AGP's built-in Kotlin: the `org.jetbrains.kotlin.plugin.compose` version in
  `gradle/libs.versions.toml` must match the Kotlin embedded in the AGP release (2.2.10 for AGP 9.2.1).
- Transcripts are cached on-device and searchable in-app (Home → search). New notes are auto-transcribed;
  the existing backlog is transcribed on-demand (the first time you play each).
- Settings → Developer → **Preview overlay card** replays fake listening → match → transcript sequences
  over the screen, for iterating on the overlay design without WhatsApp.
