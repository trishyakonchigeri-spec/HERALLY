package com.herally;

import com.google.gson.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wrapper around the Gemini generateContent REST endpoint.
 * - Tries several models in order (free-tier quota is counted PER MODEL).
 * - Does not waste time retrying a daily-quota error; moves to the next model.
 * - Caches successful answers so repeating the same search costs no quota.
 * - Logs every failure to the console (Eclipse Console tab) as "[Gemini] ...".
 *
 * Override the model list with the env var GEMINI_MODELS="modelA,modelB".
 */
public class GeminiClient implements LlmClient {

    private static final String[] MODELS = System.getenv()
            .getOrDefault("GEMINI_MODELS", "gemini-3.7-flash,gemini-3.6-flash")
            .split("\\s*,\\s*");
    private static final int MAX_ATTEMPTS = 3;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();
    private final String apiKey;

    public GeminiClient(String apiKey) {
        this.apiKey = apiKey;
    }

    /** @param json if true, asks Gemini to reply with raw JSON only. */
    @Override
    public String generate(String prompt, boolean json) throws Exception {
        String cacheKey = json + "|" + prompt;
        String cached = cache.get(cacheKey);
        if (cached != null) {
            System.out.println("[Gemini] cache hit (no quota used)");
            return cached;
        }

        JsonObject part = new JsonObject();
        part.addProperty("text", prompt);
        JsonArray parts = new JsonArray();
        parts.add(part);
        JsonObject content = new JsonObject();
        content.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(content);

        JsonObject body = new JsonObject();
        body.add("contents", contents);
        if (json) {
            JsonObject cfg = new JsonObject();
            cfg.addProperty("responseMimeType", "application/json");
            body.add("generationConfig", cfg);
        }
        String bodyStr = body.toString();

        String lastError = "Gemini request failed";
        boolean quotaHit = false;

        for (String model : MODELS) {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent";
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/json")
                        .header("x-goog-api-key", apiKey)
                        .POST(BodyPublishers.ofString(bodyStr))
                        .build();

                HttpResponse<String> res;
                try {
                    res = http.send(req, BodyHandlers.ofString());
                } catch (IOException e) {                       // network down / timeout
                    lastError = "Network error calling " + model + ": " + e.getMessage();
                    System.err.println("[Gemini] " + lastError);
                    break;                                      // try next model
                }

                int code = res.statusCode();
                if (code == 200) {
                    String text = extractText(res.body());
                    if (cache.size() > 200) cache.clear();
                    cache.put(cacheKey, text);
                    System.out.println("[Gemini] OK using " + model);
                    return text;
                }

                lastError = "Gemini error " + code + " (" + model + "): " + res.body();
                System.err.println("[Gemini] " + model + " -> HTTP " + code);
                System.err.println(res.body());

                boolean dailyQuota = code == 429 && res.body().contains("PerDay");
                if (code == 429) quotaHit = true;

                // Daily quota will not clear in a few seconds: skip retries, try next model.
                // 404 = model name does not exist: also skip straight to the next model.
                boolean retryable = (code == 429 && !dailyQuota)
                        || code == 500 || code == 503 || code == 504;
                if (retryable && attempt < MAX_ATTEMPTS) {
                    Thread.sleep(1500L * attempt);
                    continue;
                }
                break;
            }
        }

        if (quotaHit) {
            throw new RuntimeException("Gemini free-tier limit reached for today. "
                    + "Try again later, or run with LLM_PROVIDER=llama to use a local model.");
        }
        throw new RuntimeException(lastError);
    }

    private static String extractText(String responseBody) {
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray candidates = root.getAsJsonArray("candidates");
        if (candidates == null || candidates.size() == 0
                || !candidates.get(0).getAsJsonObject().has("content")) {
            throw new RuntimeException("Gemini returned no answer (it may have been blocked by a safety filter).");
        }
        JsonArray outParts = candidates.get(0).getAsJsonObject()
                .getAsJsonObject("content").getAsJsonArray("parts");

        StringBuilder sb = new StringBuilder();
        for (JsonElement pe : outParts) {
            JsonObject po = pe.getAsJsonObject();
            boolean thought = po.has("thought") && po.get("thought").getAsBoolean();
            if (!thought && po.has("text")) sb.append(po.get("text").getAsString());
        }
        String text = sb.toString().trim();

        // Strip ```json fences if the model adds them anyway
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?", "").replaceFirst("```$", "").trim();
        }
        return text;
    }
}
