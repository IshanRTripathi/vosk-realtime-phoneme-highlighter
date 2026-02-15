package com.phonemepoc;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.util.*;

/**
 * Loads the CMU Pronouncing Dictionary and provides word-to-phoneme lookups.
 *
 * The CMU Dict maps English words to ARPAbet phoneme sequences, e.g.:
 * KAMALA → K AH0 M AA1 L AH0
 * HELLO → HH AH0 L OW1
 *
 * This mapper strips stress markers (0,1,2) to produce clean phoneme sequences.
 */
public class CmuDictPhonemeMapper {

    private final Map<String, List<String>> dictionary = new HashMap<>();

    /**
     * Loads the CMU dict from a resource file or bundled dictionary.
     */
    public CmuDictPhonemeMapper() throws Exception {
        // Try loading from Sphinx4's bundled dictionary first
        InputStream is = getClass().getResourceAsStream(
                "/edu/cmu/sphinx/models/en-us/cmudict-en-us.dict");

        if (is == null) {
            // Fallback: try local resource
            is = getClass().getResourceAsStream("/cmudict.dict");
        }

        if (is == null) {
            System.out.println("[CmuDict] No dictionary file found, using hardcoded entries only.");
            loadHardcodedEntries();
            return;
        }

        System.out.println("[CmuDict] Loading dictionary...");
        int count = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith(";;;"))
                    continue;

                // Format: "WORD PH1 PH2 PH3" or "WORD(2) PH1 PH2 PH3"
                String[] parts = line.split("\\s+", 2);
                if (parts.length < 2)
                    continue;

                String word = parts[0].toLowerCase();
                // Remove variant markers like (2), (3)
                if (word.contains("(")) {
                    word = word.substring(0, word.indexOf('('));
                }

                // Skip if we already have this word (keep first pronunciation)
                if (dictionary.containsKey(word))
                    continue;

                String[] phonemes = parts[1].trim().split("\\s+");
                List<String> phonemeList = new ArrayList<>();
                for (String ph : phonemes) {
                    // Strip stress markers (0, 1, 2) from vowels
                    String cleaned = ph.replaceAll("[012]$", "");
                    phonemeList.add(cleaned);
                }

                dictionary.put(word, phonemeList);
                count++;
            }
        }

        // Add custom entries
        loadHardcodedEntries();

        System.out.println("[CmuDict] Loaded " + count + " words (+ hardcoded entries).");
    }

    /**
     * Adds hardcoded phoneme mappings for words that may not be
     * in the standard CMU dict (proper nouns, non-English words).
     */
    private void loadHardcodedEntries() {
        // Indian names commonly used in this POC
        putIfAbsent("kamala", List.of("K", "AH", "M", "AA", "L", "AH"));
        putIfAbsent("ishan", List.of("IH", "SH", "AA", "N"));

        // Common test words
        putIfAbsent("hello", List.of("HH", "AH", "L", "OW"));
        putIfAbsent("cat", List.of("K", "AE", "T"));
        putIfAbsent("dog", List.of("D", "AO", "G"));
    }

    private void putIfAbsent(String word, List<String> phonemes) {
        dictionary.putIfAbsent(word.toLowerCase(), phonemes);
    }

    /**
     * Returns the ARPAbet phoneme sequence for a word, or null if not found.
     */
    public List<String> getPhonemes(String word) {
        return dictionary.get(word.toLowerCase());
    }

    /**
     * Returns a formatted string of the phoneme breakdown for a word.
     */
    public String getPhonemeString(String word) {
        List<String> phonemes = getPhonemes(word);
        if (phonemes == null)
            return "(not in dictionary)";
        return String.join(" ", phonemes);
    }

    /**
     * Returns the number of entries in the dictionary.
     */
    public int size() {
        return dictionary.size();
    }
}
