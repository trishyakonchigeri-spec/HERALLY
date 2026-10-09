package com.herally;

import com.google.gson.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** SerpApi wrapper: Google Maps (nearby), Google Maps Reviews, Google News. */
public class SerpApiClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15)).build();
    private final String apiKey;

    public SerpApiClient(String apiKey) {
        this.apiKey = apiKey;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private JsonObject get(String params) throws Exception {
        String url = "https://serpapi.com/search.json?" + params + "&api_key=" + enc(apiKey);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60)).GET().build();
        HttpResponse<String> res = http.send(req, BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new RuntimeException("SerpApi error " + res.statusCode() + ": " + res.body());
        }
        return JsonParser.parseString(res.body()).getAsJsonObject();
    }

    private static JsonArray arr(JsonObject root, String key) {
        return (root.has(key) && root.get(key).isJsonArray()) ? root.getAsJsonArray(key) : new JsonArray();
    }

    /** Places near a coordinate. */
    public JsonArray searchMaps(String query, double lat, double lng) throws Exception {
        String ll = "@" + lat + "," + lng + ",15z";
        JsonObject root = get("engine=google_maps&type=search&hl=en&q=" + enc(query) + "&ll=" + enc(ll));
        return arr(root, "local_results");
    }

    /** Reviews for a place, identified by its Google data_id. */
    public JsonArray reviews(String dataId) throws Exception {
        JsonObject root = get("engine=google_maps_reviews&hl=en&sort_by=newestFirst&data_id=" + enc(dataId));
        return arr(root, "reviews");
    }

    /** Google News results for a query. */
    public JsonArray news(String query) throws Exception {
        JsonObject root = get("engine=google_news&hl=en&gl=in&q=" + enc(query));
        return arr(root, "news_results");
    }
}
