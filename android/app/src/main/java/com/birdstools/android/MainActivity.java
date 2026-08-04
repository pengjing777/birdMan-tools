package com.birdstools.android;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public class MainActivity extends Activity {
    private static final String PREFS = "ai_bird_chat";
    private static final String KEY_ALIAS = "birds_tools_deepseek_key";
    private static final String API_URL = "https://api.deepseek.com/chat/completions";
    private WebView webView;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new NativeBridge(), "Native");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/web/index.html");
    }

    private SharedPreferences prefs() { return getSharedPreferences(PREFS, MODE_PRIVATE); }

    private SecretKey getSecretKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance("AES", "AndroidKeyStore");
            generator.init(256);
            generator.generateKey();
        }
        return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
    }

    private void saveApiKey(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey());
        prefs().edit().putString("key", Base64.encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).apply();
    }

    private String apiKey() {
        try {
            String encrypted = prefs().getString("key", "");
            if (encrypted.isEmpty()) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), new GCMParameterSpec(128, Base64.decode(prefs().getString("iv", ""), Base64.DEFAULT)));
            return new String(cipher.doFinal(Base64.decode(encrypted, Base64.DEFAULT)), StandardCharsets.UTF_8);
        } catch (Exception ignored) { return ""; }
    }

    private class NativeBridge {
        @JavascriptInterface public boolean hasKey() { return !apiKey().isEmpty(); }

        @JavascriptInterface public String saveKey(String value) {
            try {
                String key = value == null ? "" : value.trim();
                if (key.isEmpty()) return "请输入 DeepSeek API Key";
                saveApiKey(key);
                return "";
            } catch (Exception e) { return "Key 保存失败，请重试"; }
        }

        @JavascriptInterface public String history() { return prefs().getString("history", "[]"); }

        @JavascriptInterface public void clearHistory() { prefs().edit().remove("history").apply(); }

        @JavascriptInterface public void ask(String question) {
            final String prompt = question == null ? "" : question.trim();
            new Thread(() -> {
                String answer;
                try {
                    if (apiKey().isEmpty()) throw new Exception("请先配置 DeepSeek API Key");
                    if (prompt.isEmpty()) throw new Exception("请输入你想了解的鸟类问题");
                    answer = requestDeepSeek(prompt);
                    addHistory(prompt, answer);
                } catch (Exception error) {
                    answer = "请求失败：" + (error.getMessage() == null ? "网络异常，请稍后重试" : error.getMessage());
                }
                String result = JSONObject.quote(answer);
                runOnUiThread(() -> webView.evaluateJavascript("window.onAiAnswer(" + result + ")", null));
            }).start();
        }
    }

    private String requestDeepSeek(String question) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000); connection.setReadTimeout(30000);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey());
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setDoOutput(true);

        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", "你是一位专业、友善的观鸟顾问。请用简洁易懂的中文回答鸟类识别、习性、栖息地、观鸟技巧和保护相关问题；不确定时要明确说明，不要编造信息。"));
        JSONArray old = new JSONArray(prefs().getString("history", "[]"));
        for (int i = 0; i < old.length(); i++) {
            JSONObject item = old.getJSONObject(i);
            messages.put(new JSONObject().put("role", "user").put("content", item.optString("question")));
            messages.put(new JSONObject().put("role", "assistant").put("content", item.optString("answer")));
        }
        messages.put(new JSONObject().put("role", "user").put("content", question));
        JSONObject body = new JSONObject().put("model", "deepseek-chat").put("messages", messages).put("temperature", 0.7);
        try (OutputStream output = connection.getOutputStream()) { output.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int code = connection.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(code >= 400 ? connection.getErrorStream() : connection.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder raw = new StringBuilder(); String line;
        while ((line = reader.readLine()) != null) raw.append(line);
        if (code >= 400) throw new Exception("DeepSeek 返回错误（" + code + "）");
        JSONObject response = new JSONObject(raw.toString());
        return response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
    }

    private synchronized void addHistory(String question, String answer) throws Exception {
        JSONArray source = new JSONArray(prefs().getString("history", "[]"));
        JSONArray target = new JSONArray();
        for (int i = Math.max(0, source.length() - 9); i < source.length(); i++) target.put(source.get(i));
        target.put(new JSONObject().put("question", question).put("answer", answer));
        prefs().edit().putString("history", target.toString()).apply();
    }
}
