package com.phonemepoc;

/**
 * The core constraint engine. Receives detected phonemes and enforces
 * strict sequential ordering. A word cannot complete unless its phonemes
 * are spoken in order.
 *
 * Rules:
 * - No else logic
 * - No penalties
 * - No retries
 * - Waiting is allowed forever
 */
public class PhonemeConstraintEngine {

    private final PhonemeState state;

    public PhonemeConstraintEngine(PhonemeState state) {
        this.state = state;
    }

    /**
     * Called when a phoneme is detected from the audio stream.
     * Advances the state only if the detected phoneme matches
     * the currently expected phoneme.
     *
     * @param phoneme the detected phoneme token
     */
    public void onPhonemeDetected(String phoneme) {
        if (phoneme == null || phoneme.isBlank()) {
            return;
        }

        String expected = state.expected();
        if (expected == null) {
            // Already complete, ignore further input
            return;
        }

        if (state.advanceIfMatch(phoneme)) {
            System.out.println("[" + phoneme + "] matched  ✓  ("
                    + state.currentIndex() + "/" + state.totalPhonemes() + ")");

            if (state.isComplete()) {
                System.out.println("═══════════════════════════════");
                System.out.println("  WORD COMPLETE");
                System.out.println("═══════════════════════════════");
            }
        } else {
            System.out.println("[" + phoneme + "] ignored (expecting: " + expected + ")");
        }
    }

    /**
     * Returns the underlying phoneme state.
     */
    public PhonemeState getState() {
        return state;
    }
}
