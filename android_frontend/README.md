# Offline Video Subtitle Maker (Android)

## Overview
Offline Video Subtitle Maker is a fully offline Android application that extracts audio from a locally selected video, transcribes the speech using the Vosk speech-to-text engine, and generates subtitles in SRT format. The app does not use the internet or any external APIs. All processing happens on-device, and outputs are saved to the app’s internal storage for your privacy.

The app uses:
- MediaExtractor and MediaCodec to decode audio from the selected video
- A custom pipeline to convert audio to mono 16 kHz PCM WAV
- Vosk for offline speech recognition
- A subtitle generator that formats recognized text into SRT with timecodes
- WorkManager foreground tasks to ensure long processing runs reliably

## Features
- Completely offline operation: no network access and no external APIs
- Import a Vosk speech model via the Android Storage Access Framework (SAF)
- Select any local video via SAF; no storage permissions required
- Extracts audio to a mono 16 kHz WAV file suitable for offline STT
- Generates SRT subtitles with timestamps, with lightweight segment merging and line wrapping
- Displays progress and status updates during processing
- Saves outputs into an easy-to-find per-session folder inside internal storage

## Requirements
- Android 11 (API 30) or later
- A valid Vosk model (zipped or as a directory). For example, a model from the AlphaCephei/Vosk releases
- Enough free storage to hold extracted audio (WAV) and the model files

## Model setup (Vosk) - Import instructions
You must import a Vosk model before transcription can start.

- Use the “Import Model” button on the main screen to launch the Storage Access Framework picker.
- You can select:
  - A .zip file containing a Vosk model; the app will extract it.
  - A directory containing the Vosk model; the app will copy it.
- The app will place the model into:
  - filesDir/models/vosk/<modelName>
- The import process automatically flattens a single top-level folder (common in Vosk zips), and verifies the model looks valid. If validation fails, an error is shown and no model is retained.

Supported sources:
- ACTION_OPEN_DOCUMENT for a file (e.g., .zip)
- ACTION_OPEN_DOCUMENT_TREE for a directory

After import, the app displays the model name in the UI (Model: <name>).

## How to use
1. Launch the app.
2. Tap “Import Model”:
   - Select a Vosk model zip file or a model folder via the system file picker (SAF).
   - Wait for the app to extract/copy and validate the model. The status will show success or any errors.
3. Tap “Select Video”:
   - Choose a local video via the system file picker (SAF).
   - The selected video name and URI will be shown in the status panel.
4. Tap “Start Processing” (enabled when a model and a video are selected):
   - The app begins a foreground WorkManager task that:
     - Creates a new output session directory
     - Extracts audio to a mono 16 kHz WAV
     - Runs offline transcription with Vosk
     - Generates an SRT file from recognized segments
   - You will see status lines (e.g., “Extracting audio…”, “Transcribing…”, “Generating SRT…”).
5. Completion:
   - The app displays the absolute paths to the generated WAV and SRT files.
   - You can then access or share these files as needed (e.g., using a FileProvider path if you add a share flow later).

## Where files are saved
- Models:
  - filesDir/models/vosk/<modelName>
- Outputs per session:
  - filesDir/transcripts/yyyyMMdd_HHmmss/
  - Inside each session directory you will find:
    - audio.wav — the extracted mono 16 kHz PCM WAV
    - subtitles.srt — the generated subtitles with timestamps

The session directory is created at the start of processing and is unique per run.

## Troubleshooting
- Model missing:
  - Symptom: “Start Processing” is disabled or worker fails with “Vosk model not found.”
  - Fix: Tap “Import Model” again and select a valid Vosk model zip/folder. Ensure the archive actually contains a Vosk model and not just a wrapper folder with unrelated content.
- Unsupported audio/DRM or no decodable track:
  - Symptom: Failure during “Extracting audio…”; error like “No decodable audio track found or audio is DRM-protected.”
  - Fix: Try a different video, or re-encode the audio to a supported codec. The app relies on platform decoders via MediaCodec, and cannot process DRM-protected content.
- WAV format mismatch:
  - Symptom: Error “WAV format mismatch. Expected 16kHz, mono, 16-bit PCM.”
  - Fix: This indicates an unexpected WAV input to the recognizer. The app’s pipeline should always produce 16 kHz mono 16-bit PCM, so if you modified the pipeline or used an external WAV, ensure it meets the expected format.
- Processing takes a long time:
  - Symptom: Long-running foreground notification with slow progress.
  - Fix: Large or long videos will take time. Ensure your device has enough CPU and free storage. Keep the device awake and plugged in if possible.
- SAF read permission issues:
  - Symptom: Permission or access errors when picking files/folders.
  - Fix: Re-select the source and ensure you grant the app access via the system picker. If you updated or moved the source after selection, re-pick it.

## Privacy & Offline
- The app does not use the internet and does not call any external APIs.
- All processing is on-device.
- All files (models, WAVs, SRTs) are stored in the app’s internal filesDir.

## Known limitations
- Only the first decodable audio track is used.
- DRM-protected or unsupported codecs cannot be processed.
- Recognition quality and language support depend entirely on the chosen Vosk model.
- Very long videos will take longer to process and require more storage for the WAV file.
- Current UI provides basic progress and status; advanced sharing/management of outputs is not yet implemented.

## How to run in the preview
- Build:
  - ./gradlew build
- Install on a connected device/emulator:
  - ./gradlew :app:installDebug
- Launch the app on the device and follow the usage steps above (import model, select video, start processing).

A hosted preview link may be provided for interactive demos. When using a preview, model import still uses the system file picker and requires uploading/attaching a Vosk model asset.

## Credits
- Vosk speech recognition engine (alphacephei.com / Vosk)
- AndroidX WorkManager, AppCompat, Core KTX
- MediaExtractor/MediaCodec for audio decoding

