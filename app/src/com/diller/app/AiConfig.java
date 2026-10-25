package com.diller.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores the optional AI provider settings (API key, provider, base URL, model). */
public class AiConfig {
    private final SharedPreferences p;

    public AiConfig(Context c) {
        p = c.getApplicationContext().getSharedPreferences("diller", Context.MODE_PRIVATE);
    }

    public String key() { return p.getString("ai_key", ""); }
    public void setKey(String v) { p.edit().putString("ai_key", v).apply(); }

    public String provider() { return p.getString("ai_provider", "sarvam"); }
    public void setProvider(String v) { p.edit().putString("ai_provider", v).apply(); }

    public String baseUrl() { return p.getString("ai_base", "https://api.sarvam.ai"); }
    public void setBaseUrl(String v) { p.edit().putString("ai_base", v).apply(); }

    public String model() { return p.getString("ai_model", "sarvam-105b-conversations"); }
    public void setModel(String v) { p.edit().putString("ai_model", v).apply(); }

    public boolean hasKey() {
        String k = key();
        return k != null && !k.trim().isEmpty();
    }
}
