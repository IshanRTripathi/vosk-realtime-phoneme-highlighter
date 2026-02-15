package com.phonemepoc;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import org.vosk.LogLevel;
import org.vosk.LibVosk;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * APPROACH 3: Vosk Word Recognition → CMU Dict Phoneme Mapping
 *
 * Uses Vosk for reliable word recognition (what it's good at),
 * then looks up each recognized word's phoneme decomposition in
 * the CMU Pronouncing Dictionary.
 *
 * Pipeline:
 * Audio → Vosk (word recognition) → CMU Dict (word → phonemes) → phoneme list
 *
 * Usage: mvn compile exec:java
 * -Dexec.mainClass="com.phonemepoc.VoskWordPhonemeRunner"
 */
public class VoskWordPhonemeRunner {

    private static final String MODEL_PATH = "model/vosk-model-small-en-us-0.15";
    private static final String AUDIO_PATH = "audio/test.m4a";
    private static final int CHUNK_SIZE = 4096;
    private static final Gson GSON = new Gson();

    public static void main(String[] args) throws Exception {
        String modelPath = args.length > 0 ? args[0] : MODEL_PATH;
        String audioPath = args.length > 1 ? args[1] : AUDIO_PATH;

        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║  Approach 3: Vosk Words → CMU Dict Phonemes      ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Model: " + modelPath);
        System.out.println("Audio: " + audioPath);
        System.out.println();

        // ── Load Audio ────────────────────────────────────────────
        System.out.println("── Loading Audio ──────────────────────────────────");
        byte[] audio = AudioInput.readAudio(audioPath);
        System.out.println();

        // ── Load CMU Dictionary ───────────────────────────────────
        System.out.println("── Loading CMU Pronouncing Dictionary ─────────────");
        CmuDictPhonemeMapper cmuDict = new CmuDictPhonemeMapper();
        System.out.println();

        // ── Vosk Recognition (no grammar) ─────────────────────────
        System.out.println("── Vosk Word Recognition (unconstrained) ──────────");

        LibVosk.setLogLevel(LogLevel.WARNINGS);
        List<WordEvent> recognizedWords = new ArrayList<>();

        try (Model model = new Model(modelPath);
                Recognizer recognizer = new Recognizer(model, 16000)) {

            recognizer.setWords(true);

            for (int i = 0; i < audio.length; i += CHUNK_SIZE) {
                int len = Math.min(CHUNK_SIZE, audio.length - i);
                byte[] chunk = Arrays.copyOfRange(audio, i, i + len);

                if (recognizer.acceptWaveForm(chunk, len)) {
                    parseWords(recognizer.getResult(), recognizedWords);
                }
            }

            // Final result
            parseWords(recognizer.getFinalResult(), recognizedWords);
        }

        // ── Word → Phoneme Mapping ────────────────────────────────
        System.out.println();
        System.out.println("── Word → Phoneme Mapping ─────────────────────────");
        System.out.println();

        List<PhonemeEvent> allPhonemes = new ArrayList<>();

        System.out.println("  Words recognized by Vosk:");
        System.out.println("  ─────────────────────────────────────────────────");

        for (WordEvent w : recognizedWords) {
            List<String> phonemes = cmuDict.getPhonemes(w.word);
            String phonemeStr = cmuDict.getPhonemeString(w.word);

            System.out.printf("  %-15s → %-30s [%.0f-%.0fms, conf=%.3f]%n",
                    w.word, phonemeStr,
                    w.startSec * 1000, w.endSec * 1000, w.confidence);

            if (phonemes != null) {
                // Calculate approximate per-phoneme timing
                double durationMs = (w.endSec - w.startSec) * 1000;
                double perPhonemeMs = durationMs / phonemes.size();

                for (int i = 0; i < phonemes.size(); i++) {
                    long startMs = (long) (w.startSec * 1000 + i * perPhonemeMs);
                    long endMs = (long) (w.startSec * 1000 + (i + 1) * perPhonemeMs);
                    allPhonemes.add(new PhonemeEvent(
                            phonemes.get(i), startMs, endMs, w.confidence, w.word));
                }
            }
        }

        // ── Phoneme Breakdown ─────────────────────────────────────
        System.out.println();
        System.out.println("── Full Phoneme Breakdown ─────────────────────────");
        System.out.println("  Total phonemes: " + allPhonemes.size());
        System.out.println();

        if (!allPhonemes.isEmpty()) {
            System.out.println("  #   Start    End      Phoneme    From Word");
            System.out.println("  ──  ───────  ───────  ─────────  ─────────");
            for (int i = 0; i < allPhonemes.size(); i++) {
                PhonemeEvent e = allPhonemes.get(i);
                System.out.printf("  %-3d %5dms  %5dms  %-9s  %s%n",
                        i + 1, e.startMs, e.endMs, e.phoneme, e.sourceWord);
            }

            System.out.println();
            System.out.println("  Phoneme sequence: "
                    + allPhonemes.stream()
                            .map(e -> e.phoneme)
                            .reduce((a, b) -> a + " " + b)
                            .orElse("(none)"));
        } else {
            System.out.println("  ⚠ No phonemes derived (no words recognized).");
        }

        System.out.println();
        System.out.println("══════════════════════════════════════════════════");
    }

    /**
     * Parses a Vosk result JSON to extract word-level details with timing.
     */
    private static void parseWords(String json, List<WordEvent> words) {
        try {
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            if (obj == null || !obj.has("result"))
                return;

            JsonArray results = obj.getAsJsonArray("result");
            for (int i = 0; i < results.size(); i++) {
                JsonObject wordObj = results.get(i).getAsJsonObject();
                String word = wordObj.get("word").getAsString().trim();
                double conf = wordObj.get("conf").getAsDouble();
                double start = wordObj.get("start").getAsDouble();
                double end = wordObj.get("end").getAsDouble();

                if (!word.isEmpty()) {
                    words.add(new WordEvent(word, start, end, conf));
                    System.out.printf("  [WORD] %-15s %6.3f-%6.3fs  conf=%.3f%n",
                            word, start, end, conf);
                }
            }
        } catch (Exception e) {
            // Silently skip malformed JSON
        }
    }

    private static class WordEvent {
        final String word;
        final double startSec;
        final double endSec;
        final double confidence;

        WordEvent(String word, double startSec, double endSec, double confidence) {
            this.word = word;
            this.startSec = startSec;
            this.endSec = endSec;
            this.confidence = confidence;
        }
    }

    private static class PhonemeEvent {
        final String phoneme;
        final long startMs;
        final long endMs;
        final double confidence;
        final String sourceWord;

        PhonemeEvent(String phoneme, long startMs, long endMs, double confidence, String sourceWord) {
            this.phoneme = phoneme;
            this.startMs = startMs;
            this.endMs = endMs;
            this.confidence = confidence;
            this.sourceWord = sourceWord;
        }
    }
}
