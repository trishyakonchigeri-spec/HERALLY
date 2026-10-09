package com.herally;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public class Main {

    static final int PORT = 8081;

    public static void main(String[] args) throws Exception {
        String serpKey = System.getenv("SERPAPI_KEY");
        if (serpKey == null) {
            System.err.println("Set the SERPAPI_KEY environment variable.");
            return;
        }

        // LLM_PROVIDER=llama -> local llama.cpp, anything else -> Gemini
        String provider = System.getenv().getOrDefault("LLM_PROVIDER", "gemini");
        LlmClient llm;
        if ("llama".equalsIgnoreCase(provider)) {
            String url = System.getenv().getOrDefault("LLAMA_URL", "http://localhost:8080");
            llm = new LlamaCppClient(url);
            System.out.println("Using local llama.cpp at " + url);
        } else {
            String geminiKey = System.getenv("GEMINI_API_KEY");
            if (geminiKey == null) {
                System.err.println("Set GEMINI_API_KEY (or use LLM_PROVIDER=llama).");
                return;
            }
            llm = new GeminiClient(geminiKey);
            System.out.println("Using Gemini");
        }

        HerAllyService service = new HerAllyService(llm, new SerpApiClient(serpKey));

        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        // Static files: the page, the service worker (offline support) and the manifest
        server.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            switch (path) {
                case "/", "/index.html" -> serve(ex, "/static/index.html", "text/html; charset=utf-8");
                case "/sw.js" -> serve(ex, "/static/sw.js", "application/javascript; charset=utf-8");
                case "/manifest.json" -> serve(ex, "/static/manifest.json", "application/manifest+json");
                default -> {
                    ex.sendResponseHeaders(404, -1);
                    ex.close();
                }
            }
        });

        // POST /api/safestop {lat, lng, situation}
        server.createContext("/api/safestop", ex -> handle(ex, b -> service.safeStop(
                b.get("lat").getAsDouble(),
                b.get("lng").getAsDouble(),
                b.has("situation") ? b.get("situation").getAsString() : "")));

        // POST /api/reviews {dataId, name}
        server.createContext("/api/reviews", ex -> handle(ex, b -> {
            JsonObject o = new JsonObject();
            o.addProperty("insight", service.reviewInsights(
                    b.get("dataId").getAsString(), b.get("name").getAsString()));
            return o;
        }));

        // POST /api/news {area}
        server.createContext("/api/news", ex -> handle(ex, b ->
                service.newsContext(b.get("area").getAsString())));

        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("HERALLY running at http://localhost:" + PORT);
    }

    private static void serve(HttpExchange ex, String resource, String type) throws IOException {
        try (InputStream in = Main.class.getResourceAsStream(resource)) {
            if (in == null) {
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
            byte[] data = in.readAllBytes();
            ex.getResponseHeaders().set("Content-Type", type);
            ex.getResponseHeaders().set("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(data); }
        }
    }

    interface Handler { JsonObject run(JsonObject body) throws Exception; }

    private static void handle(HttpExchange ex, Handler h) {
        int status = 200;
        JsonObject result;
        try {
            if (!"POST".equals(ex.getRequestMethod())) throw new IllegalArgumentException("Use POST");
            String raw = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            result = h.run(JsonParser.parseString(raw).getAsJsonObject());
        } catch (Exception e) {
            e.printStackTrace();
            status = 500;
            result = new JsonObject();
            result.addProperty("error", String.valueOf(e.getMessage()));
        }
        try {
            byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(bytes); }
        } catch (Exception ignored) { }
    }
}
