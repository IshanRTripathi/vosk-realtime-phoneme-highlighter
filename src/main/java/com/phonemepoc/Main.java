package com.phonemepoc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Main orchestrator for the Phoneme-Constrained Reading Alignment POC.
 *
 * Two-pass approach:
 * Pass 1 (Analysis): Dump ALL phoneme tokens detected from the audio for
 * reference
 * Pass 2 (Constraint): Run the strict phoneme order enforcement engine
 *
 * This is NOT speech-to-text. We never use recognized words.
 * We use grammar-constrained decoding to force the recognizer to output
 * only our target phoneme/syllable tokens.
 */
public class Main {

    // ── Configuration ─────────────────────────────────────────────
    private static final String MODEL_PATH = "model/vosk-model-small-en-us-0.15";
    private static final String AUDIO_PATH = "audio/ishan.m4a";

    // Hardcoded phoneme sequence for the word "kamala"
    private static final List<String> PHONEME_SEQUENCE = List.of("ka", "ma", "la");

    // Audio chunk size: 4096 bytes ≈ 128ms at 16kHz 16-bit mono
    private static final int CHUNK_SIZE = 4096;

    // ── Main ──────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║  Phoneme-Constrained Reading Alignment POC       ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");
        System.out.println();

        // Allow overriding paths via command-line args
        String modelPath = args.length > 0 ? args[0] : MODEL_PATH;
        String audioPath = args.length > 1 ? args[1] : AUDIO_PATH;

        System.out.println("Model: " + modelPath);
        System.out.println("Audio: " + audioPath);
        System.out.println("Target phonemes: " + PHONEME_SEQUENCE);
        System.out.println();

        // ── Load Audio ────────────────────────────────────────────
        System.out.println("── Loading Audio ──────────────────────────────────");
        byte[] audio = AudioInput.readAudio(audioPath);
        System.out.println();

        // ════════════════════════════════════════════════════════════
        // PASS 1: PHONEME ANALYSIS (Reference Breakdown)
        // ════════════════════════════════════════════════════════════
        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║  PASS 1: Audio Phoneme Analysis (Reference)      ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");
        System.out.println();

        List<PhonemeEvent> allDetected = analyzeAudio(audio, modelPath);

        System.out.println();
        System.out.println("── Phoneme Breakdown ──────────────────────────────");
        System.out.println("  Total tokens detected: " + allDetected.size());
        System.out.println();

        if (!allDetected.isEmpty()) {
            System.out.println("  #   Time(ms)   Token      Source");
            System.out.println("  ──  ─────────  ─────────  ──────");
            for (int i = 0; i < allDetected.size(); i++) {
                PhonemeEvent e = allDetected.get(i);
                System.out.printf("  %-3d %7dms   %-9s  %s%n",
                        i + 1, e.timeMs, e.token, e.source);
            }
        } else {
            System.out.println("  ⚠ No phoneme tokens detected from audio.");
            System.out.println("    This may mean the grammar tokens don't match");
            System.out.println("    what Vosk can decode from the audio.");
        }

        System.out.println();
        System.out.println("  Sequence detected: "
                + allDetected.stream()
                        .map(e -> "[" + e.token + "]")
                        .reduce((a, b) -> a + " → " + b)
                        .orElse("(none)"));
        System.out.println();

        // ════════════════════════════════════════════════════════════
        // PASS 2: CONSTRAINT MATCHING
        // ════════════════════════════════════════════════════════════
        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║  PASS 2: Phoneme Constraint Matching             ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");
        System.out.println();

        PhonemeState state = new PhonemeState(PHONEME_SEQUENCE);
        PhonemeConstraintEngine engine = new PhonemeConstraintEngine(state);

        try (PhonemeRecognizer recognizer = new PhonemeRecognizer(modelPath, PHONEME_SEQUENCE)) {
            int totalChunks = (int) Math.ceil((double) audio.length / CHUNK_SIZE);
            int chunkCount = 0;

            for (int i = 0; i < audio.length; i += CHUNK_SIZE) {
                int len = Math.min(CHUNK_SIZE, audio.length - i);
                byte[] chunk = Arrays.copyOfRange(audio, i, i + len);

                chunkCount++;
                boolean isBoundary = recognizer.acceptAudio(chunk, len);

                if (isBoundary) {
                    String result = recognizer.getResult();
                    List<String> tokens = PhonemeParser.parseResult(result);
                    for (String token : tokens) {
                        engine.onPhonemeDetected(token);
                    }
                } else {
                    String partial = recognizer.getPartialResult();
                    List<String> tokens = PhonemeParser.parsePartial(partial);
                    for (String token : tokens) {
                        engine.onPhonemeDetected(token);
                    }
                }

                // Stop early if word is complete
                if (state.isComplete()) {
                    System.out.println("\n[Main] Word completed at chunk "
                            + chunkCount + "/" + totalChunks
                            + " (" + String.format("%.0f", (chunkCount * 100.0 / totalChunks)) + "%)");
                    break;
                }
            }

            // Process any remaining audio in the recognizer's buffer
            if (!state.isComplete()) {
                String finalResult = recognizer.getFinalResult();
                List<String> tokens = PhonemeParser.parseResult(finalResult);
                for (String token : tokens) {
                    engine.onPhonemeDetected(token);
                }
            }
        }

        // ── Final Summary ─────────────────────────────────────────
        System.out.println();
        System.out.println("── Summary ────────────────────────────────────────");
        System.out.println("Final state: " + state);
        if (state.isComplete()) {
            System.out.println("✓ SUCCESS: All phonemes matched in order.");
        } else {
            System.out.println("✗ INCOMPLETE: Matched " + state.currentIndex()
                    + "/" + state.totalPhonemes() + " phonemes.");
            System.out.println("  Still expecting: " + state.expected());
        }
    }

    // ── Analysis Pass ─────────────────────────────────────────────

    /**
     * Runs through the entire audio and collects all phoneme tokens
     * that Vosk detects, with timing information for reference.
     */
    private static List<PhonemeEvent> analyzeAudio(byte[] audio, String modelPath) throws Exception {
        List<PhonemeEvent> events = new ArrayList<>();
        String lastPartialText = "";

        try (PhonemeRecognizer recognizer = new PhonemeRecognizer(modelPath, PHONEME_SEQUENCE)) {
            int chunkCount = 0;

            for (int i = 0; i < audio.length; i += CHUNK_SIZE) {
                int len = Math.min(CHUNK_SIZE, audio.length - i);
                byte[] chunk = Arrays.copyOfRange(audio, i, i + len);

                chunkCount++;
                int timeMs = (int) ((i / 2.0) / 16.0); // bytes → samples → ms at 16kHz

                boolean isBoundary = recognizer.acceptAudio(chunk, len);

                if (isBoundary) {
                    String result = recognizer.getResult();
                    List<String> tokens = PhonemeParser.parseResult(result);
                    for (String token : tokens) {
                        events.add(new PhonemeEvent(token, timeMs, "RESULT"));
                    }
                    lastPartialText = "";
                } else {
                    String partial = recognizer.getPartialResult();
                    List<String> tokens = PhonemeParser.parsePartial(partial);
                    String partialText = String.join(" ", tokens);

                    // Only record NEW tokens from partial results
                    if (!partialText.equals(lastPartialText) && !tokens.isEmpty()) {
                        // Find new tokens by comparing with last partial
                        String[] lastTokens = lastPartialText.isEmpty()
                                ? new String[0]
                                : lastPartialText.split("\\s+");
                        for (int t = lastTokens.length; t < tokens.size(); t++) {
                            events.add(new PhonemeEvent(tokens.get(t), timeMs, "PARTIAL"));
                        }
                        lastPartialText = partialText;
                    }
                }
            }

            // Final result
            String finalResult = recognizer.getFinalResult();
            List<String> tokens = PhonemeParser.parseResult(finalResult);
            int endTimeMs = (int) ((audio.length / 2.0) / 16.0);
            for (String token : tokens) {
                events.add(new PhonemeEvent(token, endTimeMs, "FINAL"));
            }
        }

        return events;
    }

    /**
     * Represents a single phoneme detection event with timing.
     */
    private static class PhonemeEvent {
        final String token;
        final int timeMs;
        final String source;

        PhonemeEvent(String token, int timeMs, String source) {
            this.token = token;
            this.timeMs = timeMs;
            this.source = source;
        }
    }
}
