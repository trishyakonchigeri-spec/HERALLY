package com.herally;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Safe Stop pipeline: situation -> Gemini plan -> SerpApi Maps -> Gemini explanation. */
public class HerAllyService {

    static final String DISCLAIMER =
            "HERALLY lists nearby places where you may be able to get help or wait in a public setting. "
          + "It does not judge whether an area is safe. In an emergency, call your local emergency number.";

    private final LlmClient gemini;
    private final SerpApiClient serp;

    public HerAllyService(LlmClient gemini, SerpApiClient serp) {
        this.gemini = gemini;
        this.serp = serp;
    }

    // ---------- 1. Understand the situation ----------
    private JsonObject defaultPlan() {
        JsonObject plan = new JsonObject();
        plan.addProperty("summary", "Nearby help locations");
        plan.addProperty("openNow", true);
        plan.addProperty("specific", false);
        plan.addProperty("source", "default");
        JsonArray cats = new JsonArray();
        cats.add("police station");
        cats.add("hospital");
        cats.add("24 hour pharmacy");
        plan.add("categories", cats);
        return plan;
    }

    // Used when the AI is unavailable (quota, network...): match words in the situation to place types.
    // Each rule: { regex, summary, specific?, query1|query2|query3 }
    private static final String[][] RULES = {
        {"women support|women help|one stop|support cent|helpline",
            "Women's support and help centres nearby", "true",
            "women support centre|women police station|one stop centre for women"},
        {"hospital|injur|hurt|bleed|accident|doctor|clinic|sick|ambulance|medical emergency|emergency room",
            "Hospitals and medical help nearby", "true",
            "hospital|emergency hospital|24 hour clinic"},
        {"pharmac|medicine|medical store|chemist|tablet",
            "Pharmacies nearby", "true",
            "24 hour pharmacy|medical store|pharmacy"},
        {"police|complaint|theft|stolen|robbed|robbery|\\bfir\\b",
            "Police help nearby", "true",
            "police station|police outpost"},
        {"taxi|\\bcab\\b|\\bauto\\b|rickshaw|\\bbus\\b|metro|train|transport",
            "Transport options nearby", "true",
            "taxi stand|auto rickshaw stand|metro station"},
        {"\\batm\\b|cash|money|bank",
            "Cash and banking nearby", "true",
            "ATM|bank"},
        {"hungry|food|\\beat\\b|restaurant|dinner|lunch",
            "Food places nearby", "true",
            "24 hour restaurant|restaurant"},
        {"hotel|lodge|\\bstay\\b|sleep",
            "Places to stay nearby", "true",
            "hotel|lodge"},
        {"follow|stalk|harass|unsafe|scared|afraid|threat|danger|fear|alone|lost|stranded|stuck|late|night|wait",
            "Staffed, public places nearby where you can get help or wait", "false",
            "police station|24 hour restaurant|petrol station"},
    };

    private JsonObject fallbackPlan(String situation) {
        String text = situation == null ? "" : situation.toLowerCase();
        for (String[] r : RULES) {
            if (java.util.regex.Pattern.compile(r[0]).matcher(text).find()) {
                JsonObject plan = new JsonObject();
                plan.addProperty("summary", r[1]);
                plan.addProperty("openNow", true);
                plan.addProperty("specific", Boolean.parseBoolean(r[2]));
                plan.addProperty("source", "keywords");
                JsonArray cats = new JsonArray();
                for (String q : r[3].split("\\|")) cats.add(q);
                plan.add("categories", cats);
                return plan;
            }
        }
        return defaultPlan();
    }

    private JsonObject makePlan(String situation) {
        if (situation == null || situation.isBlank()) return defaultPlan();
        try {
            String prompt = """
                    A person who may feel unsafe or stranded describes their situation.
                    Turn it into a search plan for Google Maps. Return ONLY a JSON object:
                      "summary": one short sentence restating what they need,
                      "openNow": boolean,
                      "specific": boolean. true if they ask for ONE particular kind of place or service
                                  (e.g. women support centre, hospital, police station, pharmacy, taxi stand).
                                  false if the need is vague (e.g. "I'm alone and need somewhere to wait"),
                      "categories": array of 1 to 3 short Google Maps search queries.
                    Rules:
                    - If "specific" is true, ALL queries must be different wordings of that same kind of place
                      (e.g. for women support: "women support centre", "women police station",
                      "one stop centre for women"). Do NOT add other kinds of places.
                    - If "specific" is false, choose a few varied helpful public or staffed places
                      (e.g. "police station", "hospital", "24 hour restaurant", "cafe", "petrol station").
                    Situation: %s
                    """.formatted(situation);
            JsonObject plan = JsonParser.parseString(gemini.generate(prompt, true)).getAsJsonObject();
            if (!plan.has("categories") || plan.getAsJsonArray("categories").size() == 0) return fallbackPlan(situation);
            plan.addProperty("source", "ai");
            return plan;
        } catch (Exception e) {
            System.err.println("[HerAlly] makePlan failed, matching keywords instead: " + e.getMessage());
            return fallbackPlan(situation);
        }
    }

    // ---------- 2 + 3. Search + explain ----------
    public JsonObject safeStop(double lat, double lng, String situation) throws Exception {
        JsonObject plan = makePlan(situation);
        boolean specific = plan.has("specific") && plan.get("specific").getAsBoolean();
        List<String> cats = new ArrayList<>();
        for (JsonElement e : plan.getAsJsonArray("categories")) {
            if (cats.size() < 3) cats.add(e.getAsString());
        }

        Map<String, JsonObject> found = new LinkedHashMap<>();
        for (String cat : cats) {
            JsonArray raw = serp.searchMaps(cat, lat, lng);
            int taken = 0;
            for (JsonElement el : raw) {
                if (taken >= 5) break;
                JsonObject p = el.getAsJsonObject();
                if (!p.has("gps_coordinates")) continue;
                JsonObject g = p.getAsJsonObject("gps_coordinates");
                double plat = g.get("latitude").getAsDouble();
                double plng = g.get("longitude").getAsDouble();
                String key = p.has("data_id") ? p.get("data_id").getAsString()
                        : str(p, "title") + "|" + str(p, "address");
                if (found.containsKey(key)) continue;

                JsonObject c = new JsonObject();
                for (String f : new String[]{"title", "rating", "reviews", "address", "type",
                        "phone", "open_state", "data_id", "thumbnail"}) {
                    copy(p, c, f);
                }
                c.addProperty("lat", plat);
                c.addProperty("lng", plng);
                c.addProperty("distanceKm", Math.round(haversineKm(lat, lng, plat, plng) * 100.0) / 100.0);
                c.addProperty("category", cat);
                found.put(key, c);
                taken++;
            }
        }

        List<JsonObject> list = new ArrayList<>(found.values());
        list.sort(Comparator.comparingDouble(o -> o.get("distanceKm").getAsDouble()));
       
        JsonArray candidates = new JsonArray();
        for (int i = 0; i < list.size(); i++) {
            JsonObject c = list.get(i);
            c.addProperty("id", i);
            candidates.add(c);
        }

        JsonObject out = new JsonObject();
        out.add("plan", plan);
        out.addProperty("disclaimer", DISCLAIMER);

        JsonArray results = new JsonArray();
        boolean rankerOk = false;
        if (candidates.size() > 0) {
            Map<Integer, String> whyById = new LinkedHashMap<>();
            String rule = specific
                    ? "The person asked for a SPECIFIC kind of place. Return ONLY places that genuinely are that "
                      + "kind (check the title and type). Do NOT add other kinds such as hospitals, pharmacies or cafes. "
                      + "If none qualify, return an empty array []."
                    : "Prefer places that are currently open, close, and have a phone number. "
                      + "Keep a mix of categories unless the person asked for one kind.";
            try {
                String prompt = """
                        A person said: "%s".
                        Plan: %s
                        Candidate nearby places from Google Maps (JSON, sorted by distance): %s
                        Choose up to 5 places, best first. %s
                        For each, write a 1-2 sentence "why it appears" using ONLY the data above
                        (distance, open status, phone availability, type). NEVER say a place or area is safe.
                        Return ONLY a JSON array: [{"id": <candidate id>, "why": "..."}]
                        """.formatted(situation == null ? "" : situation.replace("\"", "'"), plan, candidates, rule);
                JsonArray ranked = JsonParser.parseString(gemini.generate(prompt, true)).getAsJsonArray();
                rankerOk = true;
                for (JsonElement e : ranked) {
                    JsonObject r = e.getAsJsonObject();
                    int id = r.get("id").getAsInt();
                    if (id >= 0 && id < candidates.size() && !whyById.containsKey(id)) {
                        whyById.put(id, r.has("why") ? r.get("why").getAsString() : "");
                    }
                }
            } catch (Exception e) {
                System.err.println("[HerAlly] ranking failed, using nearest places: " + e.getMessage());
            }
            if (!rankerOk) {
                // No AI: take the nearest places, at most 2 per category first, then fill up to 5
                Map<String, Integer> perCat = new HashMap<>();
                for (int pass = 0; pass < 2 && whyById.size() < 5; pass++) {
                    for (int i = 0; i < candidates.size() && whyById.size() < 5; i++) {
                        if (whyById.containsKey(i)) continue;
                        JsonObject c = candidates.get(i).getAsJsonObject();
                        String cat = str(c, "category");
                        int n = perCat.getOrDefault(cat, 0);
                        if (pass == 0 && n >= 2) continue;
                        perCat.put(cat, n + 1);
                        whyById.put(i, describe(c));
                    }
                }
            }
            for (Map.Entry<Integer, String> en : whyById.entrySet()) {
                JsonObject place = candidates.get(en.getKey()).getAsJsonObject().deepCopy();
                place.addProperty("why", en.getValue());
                place.addProperty("directionsUrl", "https://www.google.com/maps/dir/?api=1&destination="
                        + place.get("lat").getAsDouble() + "," + place.get("lng").getAsDouble());
                results.add(place);
            }
        }
        if (results.size() == 0) {
            out.addProperty("message", "No matching places were found near this location for \""
                    + (plan.has("summary") ? plan.get("summary").getAsString() : "your request")
                    + "\". Try the red Find Safe Stop button for police and hospitals, or move the location.");
        
        }
        else {
            out.addProperty("message", "These are a few places nearby where you can get help.");
        }
        out.add("results", results);
        return out;
    }

    /** Plain description built only from Google Maps data (used when the AI is unavailable). */
    private static String describe(JsonObject c) {
        String type = str(c, "type");
        StringBuilder sb = new StringBuilder(type.isEmpty() ? "Place" : type);
        sb.append(", ").append(c.get("distanceKm").getAsDouble()).append(" km away.");
        String open = str(c, "open_state");
        if (!open.isEmpty()) sb.append(" ").append(open).append(".");
        if (!str(c, "phone").isEmpty()) sb.append(" Phone number available.");
        return sb.toString();
    }

    // ---------- 4. Review insights ----------
    public String reviewInsights(String dataId, String placeName) throws Exception {
        JsonArray raw = serp.reviews(dataId);
        JsonArray texts = new JsonArray();
        for (JsonElement e : raw) {
            if (texts.size() >= 15) break;
            JsonObject r = e.getAsJsonObject();
            String s = null;
            if (r.has("snippet") && !r.get("snippet").isJsonNull()) {
                s = r.get("snippet").getAsString();
            } else if (r.has("extracted_snippet") && r.get("extracted_snippet").isJsonObject()) {
                JsonObject x = r.getAsJsonObject("extracted_snippet");
                if (x.has("original")) s = x.get("original").getAsString();
            }
            if (s != null && !s.isBlank()) texts.add(s);
        }
        if (texts.size() == 0) return "No review text was available for this place.";

        String prompt = """
                Here are recent Google reviews for "%s" (JSON array of strings): %s
                Write "AI Review Insights" in 2-4 short sentences. Mention ONLY signals that reviewers
                actually wrote about, such as helpful staff, staffed reception or security, crowded/public
                setting, late-night or 24-hour availability, lighting, accessibility, women-friendly facilities.
                If reviews say nothing about these, say so. Start with "Based on recent reviews:".
                This is review-derived information, not a safety guarantee; do not say the place is safe.
                """.formatted(placeName.replace("\"", "'"), texts);
        return gemini.generate(prompt, false);
    }

    // ---------- 5. News context ----------
    public JsonObject newsContext(String area) throws Exception {
        JsonArray raw = serp.news(area + " police safety incident");
        JsonArray items = new JsonArray();
        for (JsonElement e : raw) {
            if (items.size() >= 6) break;
            JsonObject n = e.getAsJsonObject();
            if (n.has("title")) {
                addNews(items, n);
            } else if (n.has("stories") && n.get("stories").isJsonArray()) {
                for (JsonElement s : n.getAsJsonArray("stories")) {
                    if (items.size() < 6) addNews(items, s.getAsJsonObject());
                }
            }
        }
        JsonObject out = new JsonObject();
        out.add("items", items);
        if (items.size() == 0) {
            out.addProperty("summary", "No recent reports found for this area.");
            return out;
        }
        String prompt = """
                Recent news results for "%s" (JSON): %s
                In 2-3 neutral sentences, say what these headlines are about and how recent they are.
                Say if some look unrelated to the area. This is context only: do not give a danger score
                and do not say the area is safe or unsafe.
                """.formatted(area.replace("\"", "'"), items);
        out.addProperty("summary", gemini.generate(prompt, false));
        return out;
    }

    private static void addNews(JsonArray items, JsonObject n) {
        JsonObject o = new JsonObject();
        copy(n, o, "title");
        copy(n, o, "link");
        copy(n, o, "date");
        if (n.has("source")) {
            JsonElement s = n.get("source");
            if (s.isJsonObject() && s.getAsJsonObject().has("name")) {
                o.add("source", s.getAsJsonObject().get("name"));
            } else if (s.isJsonPrimitive()) {
                o.add("source", s);
            }
        }
        items.add(o);
    }

    // ---------- helpers ----------
    private static String str(JsonObject o, String k) {
        return (o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : "";
    }

    private static void copy(JsonObject from, JsonObject to, String key) {
        if (from.has(key) && !from.get(key).isJsonNull()) to.add(key, from.get(key));
    }

    static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
