package com.phonemepoc;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

/**
 * WebSocket server for real-time audio streaming.
 * Supports dynamic model switching for different languages/accents.
 */
public class RealtimePhonemeServer extends WebSocketServer {

    private static final int PORT = 8887;
    private static final Gson GSON = new Gson();

    // Model paths relative to project root
    private static final Map<String, String> MODEL_PATHS = Map.of(
            "en-us-small", "model/vosk-model-small-en-us-0.15",
            "en-us-large", "model/vosk-model-en-us-0.22",
            "en-in", "model/vosk-model-en-in-0.5");

    // Per-client configuration state
    private static class ClientConfig {
        String modelKey = "en-us-small";
        String mode = "free"; // "free", "grammar", "auto"
        String grammarPhrase = "";
        List<Syllable> targetSyllables = new ArrayList<>(); // Computed for "auto" mode
    }

    private static class Syllable {
        String token; // The word used for grammar (e.g. "he")
        String display; // The visual label (e.g. "HH-EH")

        Syllable(String token, String display) {
            this.token = token;
            this.display = display;
        }
    }

    private final Map<WebSocket, ClientConfig> clientConfigs = new ConcurrentHashMap<>();
    private final Map<String, List<Syllable>> syllableMap = new ConcurrentHashMap<>();

    // Cache loaded models to avoid reloading (heavy operation)
    private final Map<String, Model> loadedModels = new ConcurrentHashMap<>();
    private final CmuDictPhonemeMapper cmuDict;
    private final Map<WebSocket, Recognizer> recognizers = new ConcurrentHashMap<>();

    public RealtimePhonemeServer() throws Exception {
        super(new InetSocketAddress(PORT));
        System.out.println("Loading CMU Dictionary...");
        this.cmuDict = new CmuDictPhonemeMapper();

        loadSyllableMap();

        // Preload default model (Small US)
        System.out.println("Preloading default model (en-us-small)...");
        loadModel("en-us-small");
    }

    private void loadSyllableMap() {
        try (InputStream is = getClass().getResourceAsStream("/syllables.json")) {
            if (is != null) {
                String jsonTxt = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                JsonObject json = GSON.fromJson(jsonTxt, JsonObject.class);
                for (String word : json.keySet()) {
                    List<Syllable> syls = new ArrayList<>();
                    // The value is now an array of objects: [{"token":"he", "display":"HH-EH"}]
                    // Check if it's array of strings (legacy) or objects
                    JsonArray arr = json.getAsJsonArray(word);
                    if (arr.size() > 0 && arr.get(0).isJsonObject()) {
                        for (JsonElement e : arr) {
                            JsonObject obj = e.getAsJsonObject();
                            syls.add(new Syllable(
                                    obj.get("token").getAsString(),
                                    obj.has("display") ? obj.get("display").getAsString()
                                            : obj.get("token").getAsString()));
                        }
                    } else {
                        // Legacy string array fallback (if any)
                        for (JsonElement e : arr) {
                            String s = e.getAsString();
                            syls.add(new Syllable(s, s));
                        }
                    }
                    syllableMap.put(word.toLowerCase(), syls);
                }
                System.out.println("Loaded " + syllableMap.size() + " words into Syllable Map.");
            } else {
                System.err.println("syllables.json not found in resources!");
            }
        } catch (Exception e) {
            System.err.println("Error loading syllable map: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private Model loadModel(String modelKey) {
        return loadedModels.computeIfAbsent(modelKey, k -> {
            String path = MODEL_PATHS.get(k);
            if (path == null)
                return null;
            try {
                System.out.println("Loading model from disk: " + k + " (" + path + ")...");
                return new Model(path);
            } catch (Exception e) {
                System.err.println("Failed to load model " + k + ": " + e.getMessage());
                return null;
            }
        });
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        System.out.println("New connection: " + conn.getRemoteSocketAddress());
        // Initialize default config
        clientConfigs.put(conn, new ClientConfig());
        // Apply default (Small US, Free Mode)
        applyConfig(conn);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("Closed connection: " + conn.getRemoteSocketAddress());
        clientConfigs.remove(conn);
        Recognizer r = recognizers.remove(conn);
        if (r != null)
            r.close();
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            JsonObject json = GSON.fromJson(message, JsonObject.class);
            if (json.has("type") && "config".equals(json.get("type").getAsString())) {
                ClientConfig config = clientConfigs.get(conn);
                if (config == null)
                    return;

                boolean changed = false;

                if (json.has("model")) {
                    config.modelKey = json.get("model").getAsString();
                    changed = true;
                }
                if (json.has("mode")) {
                    config.mode = json.get("mode").getAsString();
                    changed = true;
                }
                if (json.has("grammar")) { // Raw input text
                    config.grammarPhrase = json.get("grammar").getAsString();
                    changed = true;
                }

                if (changed) {
                    applyConfig(conn);
                }
            }
        } catch (Exception e) {
            System.err
                    .println("Error processing message from " + conn.getRemoteSocketAddress() + ": " + e.getMessage());
        }
    }

    private void applyConfig(WebSocket conn) {
        ClientConfig config = clientConfigs.get(conn);
        if (config == null)
            return;

        sendMessage(conn, "status", "Applying config: " + config.mode + " (" + config.modelKey + ")...");

        Model model = loadModel(config.modelKey);
        if (model != null) {
            try {
                Recognizer old = recognizers.remove(conn);
                if (old != null)
                    old.close();

                Recognizer newRec;

                if ("auto".equals(config.mode) && config.grammarPhrase != null && !config.grammarPhrase.isEmpty()) {
                    // Auto-generate grammar from text using Syllable Map
                    List<Syllable> syllables = new ArrayList<>();
                    String[] words = config.grammarPhrase.toLowerCase().replaceAll("[^a-z ]", "").split("\\s+");

                    for (String word : words) {
                        List<Syllable> map = syllableMap.get(word);
                        if (map != null) {
                            syllables.addAll(map);
                        } else {
                            // Fallback
                            syllables.add(new Syllable(word, word));
                        }
                    }

                    config.targetSyllables = syllables;
                    // Grammar string uses TOKENS
                    List<String> tokens = new ArrayList<>();
                    for (Syllable s : syllables)
                        tokens.add(s.token);

                    String grammarStr = String.join(" ", tokens);
                    String grammarJson = "[\"" + grammarStr + "\"]"; // Single phrase sequence

                    System.out.println("Auto-Grammar: " + grammarJson);
                    newRec = new Recognizer(model, 16000, grammarJson);

                    // Notify Frontend of the computed syllables (token + display)
                    JsonArray arr = new JsonArray();
                    for (Syllable s : syllables) {
                        JsonObject o = new JsonObject();
                        o.addProperty("token", s.token);
                        o.addProperty("display", s.display);
                        arr.add(o);
                    }

                    JsonObject update = new JsonObject();
                    update.addProperty("type", "config_update");
                    update.add("syllables", arr);
                    conn.send(GSON.toJson(update));

                } else if ("grammar".equals(config.mode) && config.grammarPhrase != null
                        && !config.grammarPhrase.isEmpty()) {
                    String grammarJson = "[\"" + config.grammarPhrase + "\"]";
                    System.out.println("Manual Grammar: " + grammarJson);
                    newRec = new Recognizer(model, 16000, grammarJson);
                    config.targetSyllables.clear(); // Manual mode doesn't auto-syllabify for highlighting same way
                } else {
                    // Free mode
                    newRec = new Recognizer(model, 16000);
                    config.targetSyllables.clear();
                }

                recognizers.put(conn, newRec);
                sendMessage(conn, "status", "Config applied: " + config.mode);
                System.out.println("Client " + conn.getRemoteSocketAddress() + " updated to " + config.mode);
            } catch (Exception e) {
                sendMessage(conn, "error", "Failed to init recognizer: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            sendMessage(conn, "error", "Model not found: " + config.modelKey);
        }
    }

    @Override
    public void onMessage(WebSocket conn, ByteBuffer blob) {
        Recognizer recognizer = recognizers.get(conn);
        if (recognizer == null)
            return;

        byte[] data = new byte[blob.remaining()];
        blob.get(data);

        boolean isFinal = recognizer.acceptWaveForm(data, data.length);
        String resultJson = isFinal ? recognizer.getResult() : recognizer.getPartialResult();

        processVoskResult(conn, resultJson, isFinal);
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("WebSocket Error: " + ex.getMessage());
        if (conn != null) {
            Recognizer r = recognizers.remove(conn);
            if (r != null)
                r.close();
        }
    }

    @Override
    public void onStart() {
        System.out.println("RealtimePhonemeServer started on port " + PORT);
    }

    private void processVoskResult(WebSocket conn, String json, boolean isFinal) {
        JsonObject resultObj = GSON.fromJson(json, JsonObject.class);
        ClientConfig config = clientConfigs.get(conn);
        boolean isGrammarOrAuto = config != null && ("grammar".equals(config.mode) || "auto".equals(config.mode));

        if (resultObj.has("partial")) {
            String partialText = resultObj.get("partial").getAsString();
            if (!partialText.isEmpty()) {
                sendMessage(conn, "partial", partialText); // Send matched tokens so far
            }
        } else if (resultObj.has("text")) {
            String text = resultObj.get("text").getAsString();
            if (!text.isEmpty()) {
                if (isGrammarOrAuto) {
                    sendMessage(conn, "result", text, text);
                } else {
                    // Free mode: Map words to phonemes
                    String[] words = text.split("\\s+");
                    StringBuilder phonemeSeq = new StringBuilder();

                    for (String word : words) {
                        List<String> phonemes = cmuDict.getPhonemes(word);
                        if (phonemes != null) {
                            phonemeSeq.append(String.join(" ", phonemes)).append(" | ");
                        } else {
                            phonemeSeq.append("[").append(word).append("?] | ");
                        }
                    }
                    sendMessage(conn, "result", text, phonemeSeq.toString());
                }
            }
        }
    }

    private void sendMessage(WebSocket conn, String type, String text) {
        sendMessage(conn, type, text, null);
    }

    private void sendMessage(WebSocket conn, String type, String text, String phonemes) {
        if (conn != null && conn.isOpen()) {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", type);
            msg.addProperty("text", text);
            if (phonemes != null) {
                msg.addProperty("phonemes", phonemes);
            }
            conn.send(GSON.toJson(msg));
        }
    }

    public static void main(String[] args) throws Exception {
        RealtimePhonemeServer server = new RealtimePhonemeServer();
        server.start();
        System.out.println("Server running. Press Enter to stop.");
        new java.util.Scanner(System.in).nextLine();
        server.stop();
    }
}
