# Vosk Realtime Phoneme Highlighter

Phoneme/syllable-constrained real-time alignment POC using Vosk. It supports:
- Offline chunked audio runs (CLI) for testing
- A WebSocket server for live microphone streaming
- A React frontend for live highlighting

## Requirements

- Java 17
- Maven
- FFmpeg in PATH (for non-WAV audio)
- Vosk models in `model/`
- Node.js (for the frontend)

## Models Setup

Place Vosk models under `model/`:
- `model/vosk-model-small-en-us-0.15`
- `model/vosk-model-en-us-0.22`
- `model/vosk-model-en-in-0.5`

You can download all required models with:

```powershell
.\download_models.ps1
```

The script installs:
- `vosk-model-small-en-us-0.15`
- `vosk-model-en-us-0.22`
- `vosk-model-en-in-0.5`

## CLI Usage

### 1) Constrained phoneme matching (file-based, chunked)

Runs the two-pass analysis + strict phoneme sequence matching:

```powershell
mvn -Dexec.mainClass="com.phonemepoc.Main" -Dexec.args="model/vosk-model-small-en-us-0.15 audio/ishan.m4a" exec:java
```

Edit the expected sequence in [Main.java](file:///c:/Users/user/IdeaProjects/vosk-realtime-phoneme-highlighter/src/main/java/com/phonemepoc/Main.java#L25-L29):

- `PHONEME_SEQUENCE` controls the strict order the matcher enforces
- `AUDIO_PATH` controls the input file

### 2) Word → phoneme mapping (unconstrained)

Runs Vosk in free mode, then maps recognized words to CMU Dict phonemes:

```powershell
mvn -Dexec.mainClass="com.phonemepoc.VoskWordPhonemeRunner" exec:java
```

## Realtime Server + Frontend

### 1) Start the WebSocket server

```powershell
mvn -Dexec.mainClass="com.phonemepoc.RealtimePhonemeServer" exec:java
```

The server listens on `ws://localhost:8887`.

### 2) Start the frontend

```powershell
cd frontend
npm install
npm run dev
```

Open the Vite URL shown in the terminal and allow microphone access.

## Mapping Data (How to Provide Phoneme/Syllable Mappings)

There are three mapping paths in this project.

### A) Grammar Mode (strict tokens)

The frontend sends a grammar phrase which is treated as a strict token sequence.
Example input in the UI:

```
ka ma la
```

The server uses this as a grammar constraint; Vosk will only emit these tokens.
No text or dictionary mapping is used in this mode.

### B) Auto Mode (text → syllable tokens)

Auto mode turns text into a strict syllable/token sequence using:

[syllables.json](file:///c:/Users/user/IdeaProjects/vosk-realtime-phoneme-highlighter/src/main/resources/syllables.json)

Each word maps to an ordered list of tokens with a display label:

```json
"kamala": [
  { "token": "car", "display": "K-AA" },
  { "token": "ma", "display": "M-AA" },
  { "token": "la", "display": "L-AA" }
]
```

To add your own word:
- Add a lowercase key in `syllables.json`
- Provide `token` values that Vosk can reliably recognize
- Use `display` for the UI label only

### C) Free Mode (word recognition → CMU Dict phonemes)

Free mode uses Vosk word recognition and then maps each word to ARPAbet
phonemes using the CMU dictionary.

You can extend mapping in two ways:
- Add entries to `src/main/resources/cmudict.dict`
- Add hardcoded entries in [CmuDictPhonemeMapper.java](file:///c:/Users/user/IdeaProjects/vosk-realtime-phoneme-highlighter/src/main/java/com/phonemepoc/CmuDictPhonemeMapper.java#L88-L96)

## Audio Format Notes

Audio is expected at 16 kHz, mono, 16-bit PCM. Non-WAV formats are converted
via FFmpeg in [AudioInput.java](file:///c:/Users/user/IdeaProjects/vosk-realtime-phoneme-highlighter/src/main/java/com/phonemepoc/AudioInput.java#L37-L111).
