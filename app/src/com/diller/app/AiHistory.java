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
            p.edit().putString("ai_history", out.toString()).apply();
        } catch (Exception ignored) { }
    }

    public static String render(Context c) {
        SharedPreferences p = c.getApplicationContext().getSharedPreferences("diller", Context.MODE_PRIVATE);
        try {
            JSONArray arr = new JSONArray(p.getString("ai_history", "[]"));
            if (arr.length() == 0) return "No AI activity yet.";
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
            return sb.toString();
        } catch (Exception e) {
            return "No AI activity yet.";
        }
    }
}
