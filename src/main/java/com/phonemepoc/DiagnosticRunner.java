package com.phonemepoc;

import org.vosk.LogLevel;
import org.vosk.LibVosk;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.util.Arrays;

/**
 * Diagnostic tool: runs Vosk WITHOUT grammar constraints to see
 * exactly what it recognizes from the audio. This helps determine
 * what grammar tokens to use.
 *
 * Usage: mvn exec:java -Dexec.mainClass="com.phonemepoc.DiagnosticRunner"
 */
public class DiagnosticRunner {

    private static final String MODEL_PATH = "model/vosk-model-small-en-us-0.15";
    private static final String AUDIO_PATH = "audio/ishan.m4a";
    private static final int CHUNK_SIZE = 4096;

    public static void main(String[] args) throws Exception {
        String modelPath = args.length > 0 ? args[0] : MODEL_PATH;
        String audioPath = args.length > 1 ? args[1] : AUDIO_PATH;

        System.out.println("══════════════════════════════════════════════════");
        System.out.println("  DIAGNOSTIC: What does Vosk hear? (no grammar)");
        System.out.println("══════════════════════════════════════════════════");
        System.out.println("Model: " + modelPath);
        System.out.println("Audio: " + audioPath);
        System.out.println();

        byte[] audio = AudioInput.readAudio(audioPath);
        System.out.println();

        LibVosk.setLogLevel(LogLevel.WARNINGS);

        try (Model model = new Model(modelPath);
                Recognizer recognizer = new Recognizer(model, 16000)) {

            recognizer.setWords(true);
            recognizer.setPartialWords(true);

            System.out.println("── Streaming audio (no grammar) ───────────────");
            for (int i = 0; i < audio.length; i += CHUNK_SIZE) {
                int len = Math.min(CHUNK_SIZE, audio.length - i);
                byte[] chunk = Arrays.copyOfRange(audio, i, i + len);

                if (recognizer.acceptWaveForm(chunk, len)) {
                    String result = recognizer.getResult();
                    System.out.println("[RESULT]  " + result.trim());
                } else {
                    String partial = recognizer.getPartialResult();
                    if (!partial.contains("\"\"")) { // skip empty partials
                        System.out.println("[PARTIAL] " + partial.trim());
                    }
                }
            }

            String finalResult = recognizer.getFinalResult();
            System.out.println("[FINAL]   " + finalResult.trim());
        }

        System.out.println();
        System.out.println("══════════════════════════════════════════════════");
        System.out.println("  Use the words above to design your grammar!");
        System.out.println("══════════════════════════════════════════════════");
    }
}
