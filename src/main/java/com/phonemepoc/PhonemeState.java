package com.phonemepoc;

import java.util.List;

/**
 * Tracks the expected ordered phoneme sequence and the current position.
 * Advances only when the exact expected phoneme is detected.
 * Never skips, never resets unless explicitly commanded.
 */
public class PhonemeState {

    private final List<String> phonemes;
    private int index = 0;

    public PhonemeState(List<String> phonemes) {
        if (phonemes == null || phonemes.isEmpty()) {
            throw new IllegalArgumentException("Phoneme list must not be empty");
        }
        this.phonemes = List.copyOf(phonemes); // immutable copy
    }

    /**
     * Returns the currently expected phoneme, or null if all phonemes
     * have been matched (word is complete).
     */
    public String expected() {
        if (index >= phonemes.size())
            return null;
        return phonemes.get(index);
    }

    /**
     * Advances the index if and only if the given phoneme matches
     * the currently expected phoneme. Returns true if advanced.
     */
    public boolean advanceIfMatch(String phoneme) {
        if (phoneme != null && phoneme.equals(expected())) {
            index++;
            return true;
        }
        return false;
    }

    /**
     * Returns true if all phonemes in the sequence have been matched.
     */
    public boolean isComplete() {
        return index >= phonemes.size();
    }

    /**
     * Returns the current index (number of phonemes matched so far).
     */
    public int currentIndex() {
        return index;
    }

    /**
     * Returns the total number of phonemes in the sequence.
     */
    public int totalPhonemes() {
        return phonemes.size();
    }

    @Override
    public String toString() {
        return "PhonemeState[" + index + "/" + phonemes.size()
                + ", expected=" + expected()
                + ", complete=" + isComplete() + "]";
    }
}
