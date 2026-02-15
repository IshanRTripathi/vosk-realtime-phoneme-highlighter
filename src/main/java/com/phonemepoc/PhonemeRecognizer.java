package com.phonemepoc;

import org.vosk.LogLevel;
import org.vosk.LibVosk;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;
import java.util.List;

/**
 * Wraps the Vosk speech recognizer with grammar-constrained recognition.
 * The grammar constrains the decoder to only recognize the specified
 * phoneme/syllable tokens, ensuring phoneme-level output rather than
 * full word transcription.
 */
public class PhonemeRecognizer implements AutoCloseable {

    private final Model model;
    private final Recognizer recognizer;

    /**
     * Creates a grammar-constrained recognizer.
     *
     * @param modelPath     path to the Vosk model directory
     * @param grammarTokens list of phoneme/syllable tokens to recognize
     *                      (e.g., ["ka", "ma", "la"])
     */
    public PhonemeRecognizer(String modelPath, List<String> grammarTokens) throws IOException {
        // Reduce Vosk's verbose internal logging
        LibVosk.setLogLevel(LogLevel.WARNINGS);

        System.out.println("[PhonemeRecognizer] Loading model from: " + modelPath);
        this.model = new Model(modelPath);

        // Build grammar JSON: ["ka", "ma", "la", "[unk]"]
        // [unk] captures any audio that doesn't match the grammar tokens
        StringBuilder grammar = new StringBuilder("[");
        for (int i = 0; i < grammarTokens.size(); i++) {
            grammar.append("\"").append(grammarTokens.get(i)).append("\", ");
        }
        grammar.append("\"[unk]\"]");

        String grammarJson = grammar.toString();
        System.out.println("[PhonemeRecognizer] Grammar: " + grammarJson);

        this.recognizer = new Recognizer(model, 16000, grammarJson);
        this.recognizer.setWords(true);
        this.recognizer.setPartialWords(true);

        System.out.println("[PhonemeRecognizer] Ready.");
    }

    /**
     * Feeds an audio chunk to the recognizer.
     *
     * @param audioChunk raw PCM audio bytes
     * @param length     number of bytes to process
     * @return true if a result boundary was hit (call getResult()),
     *         false if only partial result is available
     */
    public boolean acceptAudio(byte[] audioChunk, int length) {
        return recognizer.acceptWaveForm(audioChunk, length);
    }

    /**
     * Returns the current partial recognition result as JSON.
     */
    public String getPartialResult() {
        return recognizer.getPartialResult();
    }

    /**
     * Returns the completed recognition result as JSON.
     * Call this after acceptAudio() returns true.
     */
    public String getResult() {
        return recognizer.getResult();
    }

    /**
     * Returns the final result after all audio has been processed.
     */
    public String getFinalResult() {
        return recognizer.getFinalResult();
    }

    @Override
    public void close() {
        recognizer.close();
        model.close();
    }
}
