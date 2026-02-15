# Phoneme-Constrained Real-Time Reading Alignment

## Plain Java Proof of Concept (POC)

---

## 1. Problem Statement

### 1.1 What we are building

We are building a **phoneme-constrained real-time reading alignment system**.

Given:

* A predefined sentence or word
* A predefined **ordered phoneme sequence** for each word
* A child reading aloud

The system must:

* Listen continuously
* Detect **phonemes**, not words
* Enforce **strict phoneme order**
* Advance phoneme state **only** when the expected phoneme is detected
* Declare a word complete **only** when all its phonemes are matched
* Never skip phonemes or words
* Work **offline**
* Operate in near real-time

This is **not** speech-to-text.

---

### 1.2 What this POC explicitly excludes

This POC **does NOT** include:

* Android
* UI
* Highlighting
* G2P automation
* Noise suppression
* Confidence thresholds
* Silence detection
* Language-model-based word recognition

The sole purpose is to **prove the core invariant**:

> A word cannot complete unless its phonemes are spoken in order.

---

## 2. Why a Plain Java POC

### 2.1 Reasoning

The hardest part of the system is **phoneme-constrained decoding logic**, not:

* Android audio
* UI rendering
* Permissions
* Lifecycle management

A plain Java desktop POC:

* Removes platform noise
* Enables deterministic testing
* Allows rapid iteration
* Produces reusable core logic

Once this works, Android becomes a **mechanical port**.

---

## 3. High-Level Solution Overview

### 3.1 Pipeline (correct)

```
Audio → phoneme probabilities → constrained phoneme matcher → state updates
```

### 3.2 Pipeline (explicitly forbidden)

```
Audio → text → compare to expected text
```

No recognized text is ever used.

---

## 4. Technology Choice

### 4.1 Speech Engine

Use **Vosk (Java bindings)**

Reason:

* Kaldi-based decoder
* Offline
* Streaming
* Can expose phoneme / phone-level output
* Grammar-constrained decoding possible

No model training required.

---

## 5. POC Scope

### 5.1 Fixed Input

* One hardcoded word or sentence
* One hardcoded phoneme sequence per word

Example:

```
Word: kamala
Phonemes: ka → ma → la
```

### 5.2 Output

Console logs only:

```
[ka] matched
[ma] matched
[la] matched
WORD COMPLETE
```

---

## 6. Directory Structure

```
phoneme-poc/
├── model/
│   └── vosk-model-en-us/
├── audio/
│   └── kamala.wav
├── src/
│   ├── Main.java
│   ├── AudioInput.java
│   ├── PhonemeRecognizer.java
│   ├── PhonemeConstraintEngine.java
│   └── PhonemeState.java
└── pom.xml / build.gradle
```

---

## 7. Audio Input Layer

### 7.1 Initial Mode: WAV file (mandatory)

Do **not** start with microphone.

Requirements:

* 16 kHz
* Mono
* PCM 16-bit

This ensures deterministic behavior.

---

### 7.2 AudioInput.java (reference skeleton)

```java
public class AudioInput {

    public static byte[] readWav(String path) throws Exception {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(new File(path))) {
            AudioFormat format = ais.getFormat();

            if (format.getSampleRate() != 16000 || format.getChannels() != 1) {
                throw new IllegalArgumentException("WAV must be 16kHz mono");
            }

            return ais.readAllBytes();
        }
    }
}
```

---

## 8. Speech / Phoneme Recognition Layer

### 8.1 Model Loading

* Use a standard English Vosk model
* No custom training
* Load once at startup

---

### 8.2 PhonemeRecognizer.java (reference skeleton)

```java
public class PhonemeRecognizer {

    private final Model model;
    private final Recognizer recognizer;

    public PhonemeRecognizer(String modelPath) {
        this.model = new Model(modelPath);
        this.recognizer = new Recognizer(model, 16000);
    }

    public void acceptAudio(byte[] audioChunk) {
        recognizer.acceptWaveForm(audioChunk, audioChunk.length);
    }

    public String getPartialResult() {
        return recognizer.getPartialResult();
    }
}
```

**Important:**

* Ignore `getResult()` text
* Use partial/phone-level output only

---

## 9. Phoneme Constraint Engine (Core Logic)

This is the **heart of the system**.

### 9.1 Responsibilities

* Maintain expected phoneme sequence
* Track current phoneme index
* Advance only on exact match
* Never reset unless explicitly commanded
* Never skip

---

### 9.2 PhonemeState.java

```java
public class PhonemeState {
    private final List<String> phonemes;
    private int index = 0;

    public PhonemeState(List<String> phonemes) {
        this.phonemes = phonemes;
    }

    public String expected() {
        if (index >= phonemes.size()) return null;
        return phonemes.get(index);
    }

    public boolean advanceIfMatch(String phoneme) {
        if (phoneme.equals(expected())) {
            index++;
            return true;
        }
        return false;
    }

    public boolean isComplete() {
        return index == phonemes.size();
    }
}
```

---

### 9.3 PhonemeConstraintEngine.java

```java
public class PhonemeConstraintEngine {

    private final PhonemeState state;

    public PhonemeConstraintEngine(PhonemeState state) {
        this.state = state;
    }

    public void onPhonemeDetected(String phoneme) {
        if (state.advanceIfMatch(phoneme)) {
            System.out.println("Matched phoneme: " + phoneme);

            if (state.isComplete()) {
                System.out.println("WORD COMPLETE");
            }
        }
    }
}
```

Rules:

* No else logic
* No penalties
* No retries
* Waiting is allowed forever

---

## 10. Phoneme Extraction Strategy (POC-level)

For the POC:

* Parse phoneme tokens from recognizer output
* Normalize to your simplified phoneme inventory

Example normalization:

```
"AH" → "a"
"K"  → "ka"
"M"  → "ma"
"L"  → "la"
```

This mapping can be **hardcoded** initially.

---

## 11. Main Orchestration

### 11.1 Main.java (reference flow)

```java
public class Main {

    public static void main(String[] args) throws Exception {

        // Hardcoded phoneme sequence
        List<String> phonemes = List.of("ka", "ma", "la");

        PhonemeState state = new PhonemeState(phonemes);
        PhonemeConstraintEngine engine = new PhonemeConstraintEngine(state);

        PhonemeRecognizer recognizer = new PhonemeRecognizer("model/vosk-model-en-us");

        byte[] audio = AudioInput.readWav("audio/kamala.wav");

        // Stream audio in chunks (simulate real time)
        int chunkSize = 320; // ~20 ms at 16kHz
        for (int i = 0; i < audio.length; i += chunkSize) {
            int len = Math.min(chunkSize, audio.length - i);
            byte[] chunk = Arrays.copyOfRange(audio, i, i + len);

            recognizer.acceptAudio(chunk);

            String partial = recognizer.getPartialResult();

            // TODO: extract phoneme(s) from partial
            String detectedPhoneme = PhonemeParser.parse(partial);

            if (detectedPhoneme != null) {
                engine.onPhonemeDetected(detectedPhoneme);
            }
        }
    }
}
```

---

## 12. Success Criteria (POC Exit Conditions)

This POC is successful **only if**:

1. Phonemes advance strictly in order
2. Incorrect phonemes do not advance state
3. Word completion happens exactly once
4. Slow speech still completes
5. Fast speech does not skip

If any fail, **do not proceed to Android**.

---

## 13. What Comes After This POC (Not Implemented Here)

* Multi-word sentences
* Per-word grammar regeneration
* Sliding phoneme buffers
* Microphone input
* Android AudioRecord
* UI highlighting

All of these are downstream and **depend on this POC working**.

---

## 14. Final Instruction to the Builder LLM

When giving this document to Gemini or Claude, instruct it:

> Implement exactly what is described.
> Do not introduce Android, UI, STT text matching, confidence thresholds, silence logic, or word recognition.
> Treat phoneme order enforcement as a hard invariant.

---