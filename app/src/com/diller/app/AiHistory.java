package com.diller.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Local log of everything the AI did: questions asked, calls answered, translations. */
public class AiHistory {

    private static final int MAX = 80;

    public static void add(Context c, String type, String title, String body) {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences("diller", Context.MODE_PRIVATE);
        try {
            JSONArray old = new JSONArray(p.getString("ai_history", "[]"));
            JSONObject e = new JSONObject();
            e.put("t", System.currentTimeMillis());
            e.put("type", type);
            e.put("title", title);
            e.put("body", body == null ? "" : body);
            JSONArray out = new JSONArray();
            out.put(e);
            for (int i = 0; i < old.length() && i < MAX; i++) out.put(old.get(i));
            String raw = out.toString();
            p.edit().putString("ai_history", raw).apply();
            // Prime BOTH caches: the raw string so render() can skip the parse, and the
            // rendered text itself, built here rather than on the UI thread. Callers now
            // hand add() to a background thread and refresh the AI page when it returns,
            // and that refresh renders the history: leaving cachedRender null meant the
            // one parse this method had just done off the main thread was immediately
            // repeated on the main thread by render(), so nothing was gained.
            //
            // render() was reached through prefs, so it read the string it had just
            // written back out and parsed the WHOLE array again — up to 80 entries — to
            // format the same 20 lines, a second full JSON parse per recorded AI event
            // (every AI-answered call included). Render straight from the array built
            // above instead; same text, one parse.
            String rendered = renderArray(out);
            cachedRaw = raw;
            cachedRender = rendered;
        } catch (Exception ignored) { }
    }

    /** Rendered text of the last history we parsed, keyed on the stored JSON string.
     *  render() is called from the UI thread on every AI-tab refresh (tab switch,
     *  agent toggle, settings save); without this it re-read prefs, re-parsed the
     *  JSON array and rebuilt a SimpleDateFormat for the same unchanged text. */
    private static volatile String cachedRaw;
    private static volatile String cachedRender;

    /** The rendered history when it is already cached for the stored JSON, else null.
     *  render() re-reads the "ai_history" string and, on a cold cache, parses the whole
     *  array (up to 80 entries) and formats 20 dates. Callers that run on the UI thread —
     *  the AI tab's refresh, which fires from the tab switch itself — can ask this first
     *  and hand the cold case to a worker instead of parsing inside the page animation. */
    public static String cached(Context c) {
        String hit = cachedRender;
        if (hit == null) return null;
        try {
            String raw = c.getApplicationContext()
                    .getSharedPreferences("diller", Context.MODE_PRIVATE)
                    .getString("ai_history", "[]");
            return raw.equals(cachedRaw) ? hit : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String render(Context c) {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences("diller", Context.MODE_PRIVATE);
        String raw = p.getString("ai_history", "[]");
        String hit = cachedRender;
        if (hit != null && raw.equals(cachedRaw)) return hit;
        try {
            String text = renderArray(new JSONArray(raw));
            cachedRaw = raw;
            cachedRender = text;
            return text;
        } catch (Exception e) {
            return "No AI activity yet.";
        }
    }

    /** Renders the first 20 entries of an already-parsed array. Shared by render() (which
     *  parses the stored string) and add() (which already holds the array it just built),
     *  so recording an event no longer parses the history a second time. */
    private static String renderArray(JSONArray arr) throws Exception {
        if (arr.length() == 0) return "No AI activity yet.";
        SimpleDateFormat f = new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length() && i < 20; i++) {
            JSONObject e = arr.getJSONObject(i);
            sb.append("\u2022 ").append(f.format(new Date(e.optLong("t"))))
              .append(" \u2014 ").append(e.optString("title"));
            String b = e.optString("body", "");
            if (!b.isEmpty()) sb.append("\n    ").append(b);
            sb.append("\n");
        }
        return sb.toString();
    }
}
