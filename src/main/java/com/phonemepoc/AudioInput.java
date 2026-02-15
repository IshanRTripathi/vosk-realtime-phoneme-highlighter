package com.phonemepoc;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Files;

/**
 * Reads audio files and returns raw PCM bytes in the required format:
 * 16 kHz sample rate, mono channel, 16-bit PCM.
 *
 * Supported formats:
 * - WAV: read directly (must already be 16kHz mono)
 * - M4A/AAC/MP3/OGG/FLAC: converted to WAV via FFmpeg subprocess
 */
public class AudioInput {

    private static final float REQUIRED_SAMPLE_RATE = 16000f;
    private static final int REQUIRED_CHANNELS = 1;

    /** Extensions that require FFmpeg conversion */
    private static final String[] FFMPEG_EXTENSIONS = {
            ".m4a", ".aac", ".mp3", ".ogg", ".flac", ".mp4", ".webm", ".opus"
    };

    /**
     * Reads an audio file and returns raw PCM audio bytes.
     * Automatically detects format by extension and converts if needed.
     *
     * @param path path to the audio file (WAV, M4A, MP3, etc.)
     * @return raw PCM audio data as byte array (16kHz, mono, 16-bit)
     * @throws Exception if the file cannot be read or converted
     */
    public static byte[] readAudio(String path) throws Exception {
        File file = new File(path);
        if (!file.exists()) {
            throw new IllegalArgumentException("Audio file not found: " + path);
        }

        String lowerPath = path.toLowerCase();

        // Check if this format needs FFmpeg conversion
        if (needsConversion(lowerPath)) {
            System.out.println("[AudioInput] Non-WAV format detected, converting via FFmpeg...");
            file = convertToWav(file);
            System.out.println("[AudioInput] Conversion complete: " + file.getAbsolutePath());
        }

        return readWavFile(file);
    }

    /**
     * Checks if the file extension requires FFmpeg conversion.
     */
    private static boolean needsConversion(String lowerPath) {
        for (String ext : FFMPEG_EXTENSIONS) {
            if (lowerPath.endsWith(ext))
                return true;
        }
        return false;
    }

    /**
     * Converts any audio file to a 16kHz mono 16-bit WAV using FFmpeg.
     * The output is written to a temporary file that is deleted on JVM exit.
     */
    private static File convertToWav(File input) throws Exception {
        // Create a temp file for the converted WAV
        File tempWav = Files.createTempFile("phoneme_poc_", ".wav").toFile();
        tempWav.deleteOnExit();

        // FFmpeg command: convert to 16kHz, mono, 16-bit PCM WAV
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg",
                "-y", // overwrite output
                "-i", input.getAbsolutePath(),
                "-ar", "16000", // 16kHz sample rate
                "-ac", "1", // mono
                "-sample_fmt", "s16", // 16-bit signed
                "-f", "wav", // WAV format
                tempWav.getAbsolutePath());
        pb.redirectErrorStream(true);

        System.out.println("[AudioInput] Running: " + String.join(" ", pb.command()));

        Process process = pb.start();

        // Read FFmpeg output
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                System.out.println("[FFmpeg] " + line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "FFmpeg conversion failed with exit code " + exitCode
                            + ". Is FFmpeg installed and in your PATH?");
        }

        if (!tempWav.exists() || tempWav.length() == 0) {
            throw new RuntimeException("FFmpeg produced an empty or missing output file");
        }

        return tempWav;
    }

    /**
     * Reads a WAV file, validates its format, and returns raw PCM bytes.
     */
    private static byte[] readWavFile(File file) throws Exception {
        try (AudioInputStream ais = AudioSystem.getAudioInputStream(file)) {
            AudioFormat format = ais.getFormat();

            System.out.println("[AudioInput] Format: " + format);
            System.out.println("[AudioInput] Sample Rate: " + format.getSampleRate());
            System.out.println("[AudioInput] Channels: " + format.getChannels());
            System.out.println("[AudioInput] Sample Size: " + format.getSampleSizeInBits() + " bits");

            if (format.getSampleRate() != REQUIRED_SAMPLE_RATE) {
                throw new IllegalArgumentException(
                        "WAV must be 16kHz, got " + format.getSampleRate() + " Hz");
            }
            if (format.getChannels() != REQUIRED_CHANNELS) {
                throw new IllegalArgumentException(
                        "WAV must be mono, got " + format.getChannels() + " channels");
            }

            byte[] audioBytes = ais.readAllBytes();
            System.out.println("[AudioInput] Loaded " + audioBytes.length + " bytes ("
                    + String.format("%.2f", audioBytes.length / (2.0 * REQUIRED_SAMPLE_RATE))
                    + " seconds)");

            return audioBytes;
        }
    }
}
