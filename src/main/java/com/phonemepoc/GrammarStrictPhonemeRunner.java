package com.phonemepoc;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.vosk.LibVosk;
import org.vosk.LogLevel;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.FileInputStream;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;

public class GrammarStrictPhonemeRunner {

    private static final String MODEL_PATH = "model/vosk-model-small-en-us-0.15";
    private static final String AUDIO_FILE = "audio/test.m4a";
    // Using test.m4a ("Kamala") for "ka ma la" test

    // Strict phoneme sequence grammar
    private static final String GRAMMAR = "[\"K AE M AH L AH\"]";

    public static void main(String[] args) throws Exception {
        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║  Phase 4: Strict Grammar Verification             ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");

        LibVosk.setLogLevel(LogLevel.INFO); // Enable logs to see vocab warnings

        System.out.println("Loading Model: " + MODEL_PATH);
        try (Model model = new Model(MODEL_PATH)) {

            System.out.println("Initializing Recognizer with Grammar: " + GRAMMAR);
            // This is the CRITICAL STEP: Constructing recognizer with grammar
            try (Recognizer recognizer = new Recognizer(model, 16000, GRAMMAR)) {

                System.out.println("Processing Audio: " + AUDIO_FILE);

                // Read entire audio file into memory (converts if needed)
                byte[] audioBytes = AudioInput.readAudio(AUDIO_FILE);

                try (ByteArrayInputStream bais = new ByteArrayInputStream(audioBytes)) {

                    byte[] b = new byte[4096];
                    int bytesRead;

                    while ((bytesRead = bais.read(b)) >= 0) {
                        if (recognizer.acceptWaveForm(b, bytesRead)) {
                            // Final result reached (silence or end of phrase)
                            // But with continuous streaming, we care about partials too
                            // System.out.println("Final: " + recognizer.getResult());
                        } else {
                            // Partial result available
                            String partialJson = recognizer.getPartialResult();
                            printPartial(partialJson);
                        }
                    }

                    // Final result after EOF
                    String finalJson = recognizer.getFinalResult();
                    System.out.println("\nFinal Result: " + finalJson);
                }
            } catch (Exception e) {
                System.err.println("Recognizer logic error: " + e.getMessage());
                e.printStackTrace();
            }
        } catch (IOException e) {
            System.err.println("Model load error: " + e.getMessage());
        }
    }

    private static void printPartial(String json) {
        // Parse simple JSON: {"partial" : "text"}
        int start = json.indexOf(": \"");
        if (start > 0) {
            int end = json.lastIndexOf("\"");
            if (end > start + 3) {
                String text = json.substring(start + 3, end);
                if (!text.isEmpty()) {
                    System.out.print("\rPartial: [" + text + "]   ");
                }
            }
        }
    }
}
