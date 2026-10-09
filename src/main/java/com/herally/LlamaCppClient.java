package com.herally;

import com.google.gson.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;

/**
 * Talks to a local llama.cpp server (llama-server) through its OpenAI-compatible
 * /v1/chat/completions endpoint. No API key needed.
 */
public class LlamaCppClient implements LlmClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final String baseUrl;

    public LlamaCppClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public String generate(String prompt, boolean json) throws Exception {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", json
                ? prompt + "\n\nReply with ONLY valid JSON. No explanation, no markdown fences."
                : prompt);
        JsonArray messages = new JsonArray();
        messages.add(msg);

        JsonObject body = new JsonObject();
        body.add("messages", messages);
        body.addProperty("temperature", 0.2);
        body.addProperty("max_tokens", 1024);
        body.addProperty("stream", false);
        // Note: we do NOT use response_format=json_object, because the ranker step
        // needs a top-level JSON array and json_object forces a top-level object.

        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(180))          // local models can be slow
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body.toString()))
                .build();

        HttpResponse<String> res;
        try {
            res = http.send(req, BodyHandlers.ofString());
        } catch (java.net.ConnectException e) {
            throw new RuntimeException("Cannot reach llama-server at " + baseUrl
                    + ". Is it running?", e);
        }
        if (res.statusCode() != 200) {
            throw new RuntimeException("llama.cpp error " + res.statusCode() + ": " + res.body());
        }

        JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.size() == 0) {
            throw new RuntimeException("llama.cpp returned no choices");
        }
        String text = choices.get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString();

        // Some models (e.g. Qwen3, DeepSeek-R1) emit <think>...</think> first
        text = text.replaceAll("(?s)<think>.*?</think>", "").trim();
        return json ? extractJson(text) : text;
    }

    /** Pull the JSON object/array out of the reply, ignoring fences or chatter around it. */
    private static String extractJson(String text) {
        int o = text.indexOf('{'), a = text.indexOf('[');
        int start = (o == -1) ? a : (a == -1 ? o : Math.min(o, a));
        if (start == -1) return text;
        char close = text.charAt(start) == '{' ? '}' : ']';
        int end = text.lastIndexOf(close);
        return end > start ? text.substring(start, end + 1) : text;
    }
}
