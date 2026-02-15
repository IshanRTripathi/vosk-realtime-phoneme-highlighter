package com.phonemepoc;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses Vosk recognizer JSON output to extract recognized tokens.
 *
 * Vosk returns JSON in these formats:
 * Partial: {"partial" : "ka ma"}
 * Result: {"text" : "ka ma la"}
 *
 * This parser extracts the text content, splits into tokens,
 * and filters out [unk] and empty strings.
 */
public class PhonemeParser {

    private static final Gson GSON = new Gson();

    /**
     * Parses a Vosk partial result JSON and returns recognized tokens.
     * Returns an empty list if no meaningful tokens are found.
     */
    public static List<String> parsePartial(String json) {
        return parseField(json, "partial");
    }

    /**
     * Parses a Vosk final/complete result JSON and returns recognized tokens.
     * Returns an empty list if no meaningful tokens are found.
     */
    public static List<String> parseResult(String json) {
        return parseField(json, "text");
    }

    /**
     * Generic field extractor: reads a JSON field, splits by whitespace,
     * and filters out noise tokens.
     */
    private static List<String> parseField(String json, String field) {
        List<String> tokens = new ArrayList<>();

        if (json == null || json.isBlank()) {
            return tokens;
        }

        try {
            JsonObject obj = GSON.fromJson(json, JsonObject.class);
            if (obj == null || !obj.has(field)) {
                return tokens;
            }

            String text = obj.get(field).getAsString().trim();
            if (text.isEmpty()) {
                return tokens;
            }

            String[] parts = text.split("\\s+");
            for (String part : parts) {
                String cleaned = part.trim().toLowerCase();
                // Filter out Vosk's unknown token and empty strings
                if (!cleaned.isEmpty() && !cleaned.equals("[unk]")) {
                    tokens.add(cleaned);
                }
            }
        } catch (Exception e) {
            // Malformed JSON — ignore silently in POC
            System.err.println("[PhonemeParser] Failed to parse: " + json);
        }

        return tokens;
    }
}
