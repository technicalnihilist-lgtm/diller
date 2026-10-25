package com.diller.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Talks to an AI provider. Works with Sarvam AI (chat + translate) or any
 * OpenAI-compatible chat endpoint. Requires an API key the user supplies.
 */
public class AiClient {

    public interface Callback { void onResult(String text); }

    private final AiConfig cfg;
    private final Handler main = new Handler(Looper.getMainLooper());

    public AiClient(Context c) { cfg = new AiConfig(c); }

    public void chat(final String prompt, final Callback cb) {
        new Thread(new Runnable() {
            public void run() {
                String out;
                try { out = doChat(prompt); }
                catch (Exception e) { out = "AI error: " + e.getMessage(); }
                final String r = out;
                main.post(new Runnable() { public void run() { cb.onResult(r); } });
            }
        }).start();
    }

    public void translate(final String text, final String targetCode, final Callback cb) {
        new Thread(new Runnable() {
            public void run() {
                String out;
                try { out = doTranslate(text, targetCode); }
                catch (Exception e) { out = "AI error: " + e.getMessage(); }
                final String r = out;
                main.post(new Runnable() { public void run() { cb.onResult(r); } });
            }
        }).start();
    }

    private boolean sarvam() { return "sarvam".equalsIgnoreCase(cfg.provider()); }

    private String doChat(String prompt) throws Exception {
        String base = trim(cfg.baseUrl());
        JSONObject body = new JSONObject();
        body.put("model", cfg.model());
        JSONArray msgs = new JSONArray();
        JSONObject m = new JSONObject();
        m.put("role", "user");
        m.put("content", prompt);
        msgs.put(m);
        body.put("messages", msgs);

        JSONObject res = postJson(base + "/v1/chat/completions", body);
        JSONArray choices = res.optJSONArray("choices");
        if (choices != null && choices.length() > 0) {
            JSONObject c0 = choices.getJSONObject(0);
            JSONObject msg = c0.optJSONObject("message");
            if (msg != null) return msg.optString("content", "");
            String t = c0.optString("text", "");
            if (!t.isEmpty()) return t;
        }
        return res.toString();
    }

    private String doTranslate(String text, String target) throws Exception {
        if (sarvam()) {
            String base = trim(cfg.baseUrl());
            JSONObject body = new JSONObject();
            body.put("input", text);
            body.put("source_language_code", "auto");
            body.put("target_language_code", target);
            body.put("model", "mayura:v1");
            JSONObject res = postJson(base + "/translate", body);
            String t = res.optString("translated_text", "");
            if (!t.isEmpty()) return t;
            return res.toString();
        }
        return doChat("Translate the following into " + target
                + ". Reply with the translation only, no explanation:\n\n" + text);
    }

    private JSONObject postJson(String urlStr, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlStr).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(20000);
        c.setReadTimeout(40000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        if (sarvam()) c.setRequestProperty("api-subscription-key", cfg.key());
        else c.setRequestProperty("Authorization", "Bearer " + cfg.key());

        byte[] out = body.toString().getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(out.length);
        OutputStream os = c.getOutputStream();
        os.write(out);
        os.flush();
        os.close();

        int code = c.getResponseCode();
        InputStream is = (code >= 400) ? c.getErrorStream() : c.getInputStream();
        String txt = read(is);
        if (code >= 400) throw new Exception("HTTP " + code + " — " + txt);
        return new JSONObject(txt);
    }

    private static String read(InputStream is) throws IOException {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        return sb.toString();
    }

    private static String trim(String s) {
        if (s == null || s.isEmpty()) return "https://api.sarvam.ai";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
