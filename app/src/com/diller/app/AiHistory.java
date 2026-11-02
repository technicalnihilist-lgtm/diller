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
            // Prime the render cache so the next render() skips the parse entirely.
            cachedRaw = raw;
            cachedRender = null;
        } catch (Exception ignored) { }
    }

    /** Rendered text of the last history we parsed, keyed on the stored JSON string.
     *  render() is called from the UI thread on every AI-tab refresh (tab switch,
     *  agent toggle, settings save); without this it re-read prefs, re-parsed the
     *  JSON array and rebuilt a SimpleDateFormat for the same unchanged text. */
    private static volatile String cachedRaw;
    private static volatile String cachedRender;

    public static String render(Context c) {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences("diller", Context.MODE_PRIVATE);
        String raw = p.getString("ai_history", "[]");
        String hit = cachedRender;
        if (hit != null && raw.equals(cachedRaw)) return hit;
        try {
            JSONArray arr = new JSONArray(raw);
            if (arr.length() == 0) {
                cachedRaw = raw;
                cachedRender = "No AI activity yet.";
                return cachedRender;
            }
            SimpleDateFormat f = new SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length() && i < 20; i++) {
                JSONObject e = arr.getJSONObject(i);
                sb.append("• ").append(f.format(new Date(e.optLong("t"))))
                  .append(" — ").append(e.optString("title"));
                String b = e.optString("body", "");
                if (!b.isEmpty()) sb.append("\n    ").append(b);
                sb.append("\n");
            }
            String text = sb.toString();
            cachedRaw = raw;
            cachedRender = text;
            return text;
        } catch (Exception e) {
            return "No AI activity yet.";
        }
    }
}
