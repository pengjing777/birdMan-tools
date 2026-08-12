package com.birdstools.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.view.Window;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends Activity {
    private static final int IMAGE_PICK_REQUEST = 4201;
    private static final String PREFS = "ai_bird_chat";
    private static final String KEY_ALIAS = "birds_tools_deepseek_key";
    private static final String API_URL = "https://api.deepseek.com/chat/completions";
    private static final String ZHIPU_API_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions";
    private static final String BIRDREPORT_API = "https://api.birdreport.cn/";
    private static final String BIRDREPORT_PUBLIC_KEY =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCvxXa98E1uWXnBzXkS2yHUfnBM6n3PCwLdfIox03T91joBvjtoDqiQ5x3t"
            + "TOfpHs3LtiqMMEafls6b0YWtgB1dse1W5m+FpeusVkCOkQxB4SZDH6tuerIknnmB/Hsq5wgEkIvO5Pff9biig6AyoAkdWp"
            + "Sek/1/B7zYIepYY0lxKQIDAQAB";
    private static final byte[] BIRDREPORT_AES_KEY =
            "C8EB5514AF5ADDB94B2207B08C66601C".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BIRDREPORT_AES_IV =
            "55DD79C6F04E1A67".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_HISTORY = 10;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Map<String, String> birdreportCookies = new LinkedHashMap<>();
    private WebView webView;
    private String pendingImageData = "";

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        // Keep the system bar in the same light kingfisher palette as the web UI.
        window.setStatusBarColor(Color.rgb(126, 190, 192));
        window.getDecorView().setSystemUiVisibility(
                window.getDecorView().getSystemUiVisibility()
                        | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        window.setNavigationBarColor(Color.rgb(243, 247, 245));

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        settings.setDatabaseEnabled(false);
        settings.setGeolocationEnabled(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.setWebViewClient(new WebViewClient());
        webView.setBackgroundColor(Color.rgb(243, 247, 245));
        webView.addJavascriptInterface(new NativeBridge(), "Native");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/web/index.html");
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }

    private void pickImage() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        startActivityForResult(intent, IMAGE_PICK_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != IMAGE_PICK_REQUEST) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            pendingImageData = "";
            try { callback("onImageSelected", new JSONObject().put("ok", false).put("error", "已取消选择图片")); } catch (Exception ignored) { }
            return;
        }
        try {
            Uri uri = data.getData();
            InputStream input = getContentResolver().openInputStream(uri);
            Bitmap bitmap = BitmapFactory.decodeStream(input);
            if (input != null) input.close();
            if (bitmap == null) throw new Exception("无法读取图片");
            int max = 1280;
            float scale = Math.min(1f, max / (float) Math.max(bitmap.getWidth(), bitmap.getHeight()));
            if (scale < 1f) bitmap = Bitmap.createScaledBitmap(bitmap, Math.round(bitmap.getWidth() * scale), Math.round(bitmap.getHeight() * scale), true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out);
            pendingImageData = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            callback("onImageSelected", new JSONObject().put("ok", true));
        } catch (Exception error) {
            try { callback("onImageSelected", new JSONObject().put("ok", false).put("error", error.getMessage())); } catch (Exception ignored) { }
        }
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private SecretKey getSecretKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(KEY_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            generator.generateKey();
        }
        KeyStore.SecretKeyEntry entry = (KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null);
        return entry.getSecretKey();
    }

    private void saveApiKey(String value) throws Exception {
        saveApiKey("deepseek", value);
    }

    private void saveApiKey(String provider, String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey());
        String prefix = "zhipu".equals(provider) ? "zhipu_" : "";
        prefs().edit()
                .putString(prefix + "key", android.util.Base64.encodeToString(
                        cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), android.util.Base64.NO_WRAP))
                .putString(prefix + "iv", android.util.Base64.encodeToString(
                        cipher.getIV(), android.util.Base64.NO_WRAP))
                .apply();
    }

    private String apiKey() {
        return apiKeyFor("deepseek");
    }

    private String apiKeyFor(String provider) {
        String prefix = "zhipu".equals(provider) ? "zhipu_" : "";
        String encrypted = prefs().getString(prefix + "key", "");
        String iv = prefs().getString(prefix + "iv", "");
        if (encrypted.isEmpty() || iv.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), new GCMParameterSpec(
                    128, android.util.Base64.decode(iv, android.util.Base64.NO_WRAP)));
            return new String(cipher.doFinal(android.util.Base64.decode(
                    encrypted, android.util.Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            prefs().edit().remove(prefix + "key").remove(prefix + "iv").apply();
            return "";
        }
    }

    private String modelProvider() { return prefs().getString("model_provider", "deepseek"); }
    private boolean activeModelHasKey() { return !apiKeyFor(modelProvider()).isEmpty(); }

    private JSONArray history() {
        try {
            return new JSONArray(prefs().getString("history", "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private JSONObject birdPreferences() {
        String province = prefs().getString("preference_province", "").trim();
        String city = prefs().getString("preference_city", "").trim();
        String district = prefs().getString("preference_district", "").trim();
        try {
            return new JSONObject()
                    .put("province", province)
                    .put("city", city)
                    .put("district", district)
                    .put("blacklist_users", birdBlacklist());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private JSONArray birdBlacklist() {
        try { return new JSONArray(prefs().getString("preference_blacklist_users", "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private String saveBirdPreferences(String rawProvince, String rawCity, String rawDistrict, String rawBlacklist) {
        String province = rawProvince == null ? "" : rawProvince.trim();
        String city = rawCity == null ? "" : rawCity.trim();
        String district = rawDistrict == null ? "" : rawDistrict.trim();
        if (province.isEmpty() && (!city.isEmpty() || !district.isEmpty())) return "填写城市或区县时，请同时填写省或直辖市";
        JSONArray blacklist = new JSONArray();
        Set<String> seen = new LinkedHashSet<>();
        for (String value : (rawBlacklist == null ? "" : rawBlacklist).split("[,，\\n]")) {
            String name = value.trim();
            String key = name.toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && seen.add(key)) blacklist.put(name);
        }
        prefs().edit()
                .putString("preference_province", province)
                .putString("preference_city", city)
                .putString("preference_district", district)
                .putString("preference_blacklist_users", blacklist.toString())
                .apply();
        return "";
    }

    private void saveExchange(String question, String answer, QueryTrace trace) throws Exception {
        JSONArray current = history();
        JSONObject exchange = new JSONObject().put("question", question).put("answer", answer);
        if (trace.queried) {
            exchange.put("records_queried", true).put("query_summary", trace.summary);
            if (trace.locations != null && trace.locations.length() > 0)
                exchange.put("location_details", trace.locations);
        }
        current.put(exchange);
        JSONArray trimmed = new JSONArray();
        int start = Math.max(0, current.length() - MAX_HISTORY);
        for (int i = start; i < current.length(); i++) trimmed.put(current.getJSONObject(i));
        prefs().edit().putString("history", trimmed.toString()).apply();
    }

    private QueryTrace requestDeepSeek(String question) throws Exception { return requestDeepSeek(question, ""); }

    private QueryTrace requestDeepSeek(String question, String imageData) throws Exception {
        if ("zhipu".equals(modelProvider())) return requestZhipu(question, imageData);
        if (apiKey().isEmpty()) throw new Exception("请先设置 DeepSeek API Key");
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().getTime());
        JSONObject preferences = birdPreferences();
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content",
                "你是鸟友工具箱的专业观鸟助手。今天是 " + today + "。未指定日期时固定查询最近7天。\n"
                        + "【何时查数据】凡是询问近期或指定日期的鸟种、数量、地点、鸟况、鸟讯、哪里值得看或记录排行，必须先调用 "
                        + "query_bird_records；不得凭常识补充本次数据里没有的鸟名或数量。纯鸟类知识、辨识方法、行为习性可以直接回答。\n"
                        + "【地点优先级】本次问题明确说了省、市、区县、公园或地点时，location_source=user，只使用本次地点，绝不混入偏好；程序会从原始问题校正广州、深圳、杭州等城市所属省份。"
                        + "只有完全没说地点时 location_source=preference，由程序使用偏好省市区；偏好为空时要求用户补充地点。\n"
                        + "【日期优先级】本次问题明确说了今天、昨天、这两天、最近N天、本周等范围时必须严格按该范围查询；"
                        + "只有用户完全没说时间时，才使用默认最近7天。\n"
                        + "【回答原则】先给简短结论，再列最有用的地点或鸟种；明确实际日期、区域、地点数、记录数和数据来源。"
                        + "有记录不等于现在一定能看到，推荐时说明基于近期公开记录。零结果时建议扩大日期或区域，绝不猜测。"
                        + "使用简洁 Markdown，重点加粗并优先用短列表；涉及多个地点时必须给出一个最多3列的 Markdown 表格，列为‘地点、鸟种、保护级别’，不要展示记录数列，鸟种使用查询结果中的名称。"));
        JSONArray old = history();
        for (int i = 0; i < old.length(); i++) {
            JSONObject item = old.getJSONObject(i);
            messages.put(new JSONObject().put("role", "user").put("content", item.optString("question")));
            messages.put(new JSONObject().put("role", "assistant").put("content", item.optString("answer")));
        }
        JSONObject userMessage = new JSONObject().put("role", "user");
        if (imageData != null && imageData.startsWith("data:image/")) {
            JSONArray content = new JSONArray();
            content.put(new JSONObject().put("type", "text").put("text", question));
            content.put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", imageData)));
            userMessage.put("content", content);
        } else userMessage.put("content", question);
        messages.put(userMessage);

        boolean queried = false;
        String querySummary = "";
        JSONArray locationDetails = new JSONArray();
        boolean recordsRequired = requiresBirdRecords(question);
        for (int round = 0; round < 3; round++) {
            JSONObject response = callDeepSeek(messages, recordsRequired && !queried);
            JSONObject assistant = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
            JSONArray toolCalls = assistant.optJSONArray("tool_calls");
            if (toolCalls == null || toolCalls.length() == 0) {
                if (recordsRequired && !queried)
                    throw new Exception("此问题需要查询观鸟记录中心，但本次未能执行查询，请重试");
                String answer = assistant.optString("content").trim();
                if (answer.isEmpty()) throw new Exception("DeepSeek 没有返回回答");
                return new QueryTrace(answer, queried, querySummary, locationDetails);
            }
            messages.put(assistant);
            for (int i = 0; i < toolCalls.length(); i++) {
                JSONObject call = toolCalls.getJSONObject(i);
                JSONObject function = call.optJSONObject("function");
                if (function == null || !"query_bird_records".equals(function.optString("name"))) continue;
                JSONObject arguments = new JSONObject(function.optString("arguments", "{}"));
                JSONObject toolResult = queryBirdRecords(arguments, question);
                queried = true;
                querySummary = buildQuerySummary(toolResult);
                JSONArray queriedLocations = toolResult.optJSONArray("locations");
                if (queriedLocations != null) locationDetails = queriedLocations;
                String toolContent = toolResult.toString();
                if (toolContent.length() > 30000) toolContent = toolContent.substring(0, 30000);
                messages.put(new JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", call.optString("id"))
                        .put("content", toolContent));
            }
        }
        throw new Exception("DeepSeek 多次查询后仍未完成回答");
    }

    private QueryTrace requestZhipu(String question, String imageData) throws Exception {
        if (apiKeyFor("zhipu").isEmpty()) throw new Exception("请先设置智谱 API Key");
        JSONArray content = new JSONArray();
        if (imageData != null && imageData.startsWith("data:image/"))
            content.put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", imageData)));
        content.put(new JSONObject().put("type", "text").put("text", question.isEmpty() ? "请识别这张鸟类照片，并说明判断依据。" : question));
        JSONArray messages = new JSONArray().put(new JSONObject().put("role", "user").put("content", content));
        JSONObject body = new JSONObject().put("model", "glm-5v-turbo").put("messages", messages)
                .put("thinking", new JSONObject().put("type", "enabled"));
        JSONObject response = postModel(body, ZHIPU_API_URL, "zhipu");
        JSONObject message = response.getJSONArray("choices").getJSONObject(0).getJSONObject("message");
        String answer = message.optString("content").trim();
        if (answer.isEmpty()) throw new Exception("智谱没有返回回答");
        return new QueryTrace(answer, false, "", new JSONArray());
    }

    private JSONObject callDeepSeek(JSONArray messages, boolean forceRecordsQuery) throws Exception {
        JSONObject properties = new JSONObject()
                .put("location_source", new JSONObject().put("type", "string").put("enum", new JSONArray().put("user").put("preference")).put("description", "本次问题说了地点用 user，完全没说地点才用 preference"))
                .put("date_source", new JSONObject().put("type", "string").put("enum", new JSONArray().put("user").put("recent_7_days")).put("description", "本次问题给了明确时间用 user，否则用 recent_7_days"))
                .put("start_date", new JSONObject().put("type", "string").put("description", "date_source=user 时填写 YYYY-MM-DD，否则留空"))
                .put("end_date", new JSONObject().put("type", "string").put("description", "date_source=user 时填写 YYYY-MM-DD，否则留空"))
                .put("province", new JSONObject().put("type", "string").put("description", "location_source=user 时填写省或直辖市，否则留空"))
                .put("city", new JSONObject().put("type", "string").put("description", "location_source=user 时填写城市，否则留空"))
                .put("district", new JSONObject().put("type", "string").put("description", "用户明确说了区县时填写，否则留空"))
                .put("location_keyword", new JSONObject().put("type", "string").put("description", "地点关键词，例如 天坛；没有地点限制时留空"));
        JSONObject parameters = new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", new JSONArray().put("location_source").put("date_source").put("start_date").put("end_date")
                        .put("province").put("city").put("district").put("location_keyword"))
                .put("additionalProperties", false);
        JSONObject tool = new JSONObject().put("type", "function").put("function", new JSONObject()
                .put("name", "query_bird_records")
                .put("description", "查询观鸟记录中心在指定日期、区域或地点关键词下的真实公开鸟种记录")
                .put("parameters", parameters));
        JSONObject body = new JSONObject()
                .put("model", "deepseek-chat")
                .put("messages", messages)
                .put("tools", new JSONArray().put(tool))
                .put("tool_choice", forceRecordsQuery
                        ? new JSONObject().put("type", "function").put("function",
                                new JSONObject().put("name", "query_bird_records"))
                        : "auto")
                .put("stream", false)
                .put("temperature", 0.2);
        return postDeepSeek(body);
    }

    private static boolean requiresBirdRecords(String question) {
        String value = question == null ? "" : question.replaceAll("\\s+", "");
        boolean timeSensitive = value.matches(".*(最近|近期|今天|昨日|昨天|前天|这两天|这几天|近[一二三四五六七八九十0-9]+天|本周|这周|本月|这个月|今年).*?");
        boolean birdContext = value.matches(".*(鸟|观测|观察|记录|鸟讯|鸟况|值得看|能看到|去哪看|去哪里看).*?");
        boolean liveDataQuestion = value.matches(".*(有什么鸟|有哪些鸟|哪些鸟|哪里有鸟|哪里能看|去哪看鸟|去哪里看鸟|能看到什么|值得看什么|鸟讯|鸟况|观鸟记录|观察记录|观测记录|记录最多|鸟种最多|近期记录|最新记录).*?");
        return liveDataQuestion || (timeSensitive && birdContext);
    }

    private static String buildQuerySummary(JSONObject result) {
        JSONObject query = result.optJSONObject("query");
        if (query == null) return "已查询观鸟记录中心";
        String region = query.optString("province") + query.optString("city");
        String district = query.optString("district");
        if (!district.isEmpty()) region += " · " + district;
        String keyword = query.optString("location_keyword");
        if (!keyword.isEmpty()) region += " · " + keyword;
        return query.optString("start_date") + " 至 " + query.optString("end_date")
                + " · " + region + " · " + result.optInt("record_total") + " 条记录";
    }

    private static String[] extractExplicitLocation(String question) {
        String text = question == null ? "" : question.replaceAll("\\s+", "");
        String[][] aliases = {
                {"越秀公园", "广东省", "广州市"}, {"海珠湿地", "广东省", "广州市"}, {"白云山", "广东省", "广州市"},
                {"广州市", "广东省", "广州市"}, {"广州", "广东省", "广州市"},
                {"深圳市", "广东省", "深圳市"}, {"深圳", "广东省", "深圳市"},
                {"杭州市", "浙江省", "杭州市"}, {"杭州", "浙江省", "杭州市"},
                {"青岛市", "山东省", "青岛市"}, {"青岛", "山东省", "青岛市"},
                {"成都市", "四川省", "成都市"}, {"成都", "四川省", "成都市"},
                {"武汉市", "湖北省", "武汉市"}, {"武汉", "湖北省", "武汉市"},
                {"北京市", "北京市", ""}, {"北京", "北京市", ""},
                {"上海市", "上海市", ""}, {"上海", "上海市", ""},
                {"重庆市", "重庆市", ""}, {"重庆", "重庆市", ""},
                {"天津市", "天津市", ""}, {"天津", "天津市", ""}
        };
        for (String[] item : aliases) if (text.contains(item[0])) return new String[]{item[1], item[2]};
        String[][] provinces = {{"广东", "广东省"}, {"浙江", "浙江省"}, {"山东", "山东省"},
                {"江苏", "江苏省"}, {"四川", "四川省"}, {"湖北", "湖北省"}, {"福建", "福建省"},
                {"陕西", "陕西省"}, {"河北", "河北省"}, {"河南", "河南省"}, {"云南", "云南省"}, {"海南", "海南省"}};
        for (String[] item : provinces) if (text.contains(item[0])) return new String[]{item[1], ""};
        return new String[]{"", ""};
    }

    private static String extractExplicitLandmark(String question) {
        String text = question == null ? "" : question.replaceAll("\\s+", "");
        for (String landmark : new String[]{"越秀公园", "海珠湿地", "白云山"}) {
            if (text.contains(landmark)) return landmark;
        }
        return "";
    }

    private static class QueryTrace {
        final String answer;
        final boolean queried;
        final String summary;
        final JSONArray locations;
        QueryTrace(String answer, boolean queried, String summary, JSONArray locations) {
            this.answer = answer;
            this.queried = queried;
            this.summary = summary == null ? "" : summary;
            this.locations = locations;
        }
    }

    private JSONObject postDeepSeek(JSONObject body) throws Exception {
        return postModel(body, API_URL, "deepseek");
    }

    private JSONObject postModel(JSONObject body, String endpoint, String provider) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("Authorization", "Bearer " + apiKeyFor(provider));
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        connection.setDoOutput(true);
        try {
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payload.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(payload); }
            int code = connection.getResponseCode();
            String response = readAll(code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            if (code < 200 || code >= 300) {
                String message = "";
                try {
                    JSONObject error = new JSONObject(response).optJSONObject("error");
                    if (error != null) message = error.optString("message");
                } catch (Exception ignored) { }
                if (code == 401 || code == 403) throw new Exception("API Key 无效，请重新设置");
                if (code == 402) throw new Exception(provider.equals("zhipu") ? "智谱账户余额不足" : "DeepSeek 账户余额不足");
                if (code == 429) throw new Exception("请求过于频繁，请稍后再试");
                throw new Exception(message.isEmpty() ? "模型服务异常（" + code + "）" : message);
            }
            return new JSONObject(response);
        } finally {
            connection.disconnect();
        }
    }

    private JSONObject queryBirdRecords(JSONObject arguments, String originalQuestion) throws Exception {
        JSONObject preferences = birdPreferences();
        String[] explicitLocation = extractExplicitLocation(originalQuestion);
        boolean explicitQuestionLocation = !explicitLocation[0].isEmpty();
        boolean usePreferenceLocation = !explicitQuestionLocation && ("preference".equalsIgnoreCase(arguments.optString("location_source"))
                || (arguments.optString("location_source").trim().isEmpty()
                && arguments.optString("province").trim().isEmpty()
                && arguments.optString("city").trim().isEmpty()
                && arguments.optString("district").trim().isEmpty()
                && arguments.optString("location_keyword").trim().isEmpty()));
        String originalProvince = explicitQuestionLocation ? explicitLocation[0] : usePreferenceLocation
                ? preferences.optString("province").trim() : arguments.optString("province").trim();
        String originalCity = explicitQuestionLocation ? explicitLocation[1] : usePreferenceLocation
                ? preferences.optString("city").trim() : arguments.optString("city").trim();
        String district = explicitQuestionLocation ? "" : usePreferenceLocation
                ? preferences.optString("district").trim() : arguments.optString("district").trim();
        String keyword = extractExplicitLandmark(originalQuestion);
        if (keyword.isEmpty()) keyword = arguments.optString("location_keyword").trim();
        if (originalProvince.isEmpty()) throw new Exception("未指定省份，且观鸟偏好中没有默认省份；请先设置偏好或在问题中说明地点");
        Calendar calendar = Calendar.getInstance();
        boolean usePreferenceDate = "preference".equalsIgnoreCase(arguments.optString("date_source"))
                || "recent_7_days".equalsIgnoreCase(arguments.optString("date_source"))
                || (arguments.optString("date_source").trim().isEmpty()
                && (arguments.optString("start_date").trim().isEmpty() || arguments.optString("end_date").trim().isEmpty()));
        String endDate;
        String startDate;
        String[] questionDates = extractExplicitDateRange(originalQuestion);
        if (questionDates != null) {
            // 用户原话优先，避免模型把“今天”误填成 recent_7_days。
            startDate = questionDates[0];
            endDate = questionDates[1];
            usePreferenceDate = false;
        } else if (usePreferenceDate) {
            endDate = formatDate(calendar);
            calendar.add(Calendar.DAY_OF_MONTH, -6);
            startDate = formatDate(calendar);
        } else {
            endDate = arguments.optString("end_date").trim();
            startDate = arguments.optString("start_date").trim();
        }
        String[] region = normalizeRegion(originalProvince, originalCity);
        String province = region[0];
        String city = region[1];
        if (!startDate.matches("\\d{4}-\\d{2}-\\d{2}") || !endDate.matches("\\d{4}-\\d{2}-\\d{2}"))
            throw new Exception("查询日期格式不正确");
        if (province.isEmpty() && city.isEmpty()) throw new Exception("查询区域不能为空");

        List<JSONObject> details = fetchBirdreportDetails(province, city, startDate, endDate);
        JSONArray blacklist = birdBlacklist();
        if (blacklist.length() > 0) {
            Set<String> blocked = new LinkedHashSet<>();
            for (int i = 0; i < blacklist.length(); i++) blocked.add(blacklist.optString(i).trim().toLowerCase(Locale.ROOT));
            List<JSONObject> visible = new ArrayList<>();
            for (JSONObject item : details) {
                if (!blocked.contains(item.optString("record_user").trim().toLowerCase(Locale.ROOT))) visible.add(item);
            }
            details = visible;
        }
        for (String filter : new String[]{district, keyword}) {
            if (!filter.isEmpty()) {
                List<JSONObject> filtered = new ArrayList<>();
                for (JSONObject item : details) {
                    if (item.optString("observation_location").toLowerCase(Locale.ROOT)
                            .contains(filter.toLowerCase(Locale.ROOT))) filtered.add(item);
                }
                details = filtered;
            }
        }
        Map<String, List<JSONObject>> grouped = new LinkedHashMap<>();
        for (JSONObject item : details) {
            String location = item.optString("observation_location");
            if (!location.isEmpty()) {
                if (!grouped.containsKey(location)) grouped.put(location, new ArrayList<>());
                grouped.get(location).add(item);
            }
        }
        List<JSONObject> locations = new ArrayList<>();
        for (Map.Entry<String, List<JSONObject>> entry : grouped.entrySet()) {
            Set<String> birds = new LinkedHashSet<>();
            Set<String> reports = new LinkedHashSet<>();
            Map<String, String> birdLevels = new LinkedHashMap<>();
            for (JSONObject item : entry.getValue()) {
                if (!item.optString("bird_name").isEmpty()) {
                    String birdName = item.optString("bird_name");
                    birds.add(birdName);
                    birdLevels.put(birdName, item.optString("protection_level"));
                }
                if (!item.optString("report_no").isEmpty()) reports.add(item.optString("report_no"));
            }
            List<String> sortedBirds = new ArrayList<>(birds);
            Collections.sort(sortedBirds, (left, right) -> {
                int level = Integer.compare(protectionRank(birdLevels.get(right)), protectionRank(birdLevels.get(left)));
                return level != 0 ? level : left.compareTo(right);
            });
            JSONArray birdDetails = new JSONArray();
            int protectedCount = 0;
            for (String birdName : sortedBirds) {
                String level = birdLevels.get(birdName);
                if (protectionRank(level) > 0) protectedCount++;
                String lowerName = birdName.toLowerCase(Locale.ROOT);
                boolean uncertain = birdName.contains("?") || birdName.contains("？") || birdName.contains("疑似") || birdName.contains("待定")
                        || lowerName.contains("sp.") || lowerName.contains(" cf");
                birdDetails.put(new JSONObject().put("name", birdName).put("protection_level", level).put("uncertain", uncertain));
            }
            locations.add(new JSONObject()
                    .put("location", entry.getKey())
                    .put("species_count", birds.size())
                    .put("protected_count", protectedCount)
                    .put("record_count", entry.getValue().size())
                    .put("report_count", reports.size())
                    .put("bird_names", new JSONArray(sortedBirds))
                    .put("bird_details", birdDetails));
        }
        Collections.sort(locations, (left, right) -> {
            int protectedCount = Integer.compare(right.optInt("protected_count"), left.optInt("protected_count"));
            if (protectedCount != 0) return protectedCount;
            int species = Integer.compare(right.optInt("species_count"), left.optInt("species_count"));
            return species != 0 ? species : Integer.compare(right.optInt("record_count"), left.optInt("record_count"));
        });
        JSONArray top = new JSONArray();
        for (int i = 0; i < Math.min(20, locations.size()); i++) top.put(locations.get(i));
        return new JSONObject()
                .put("query", new JSONObject().put("province", province).put("city", city)
                        .put("district", district).put("location_keyword", keyword)
                        .put("start_date", startDate).put("end_date", endDate)
                        .put("location_source", usePreferenceLocation ? "preference" : "user")
                        .put("date_source", usePreferenceDate ? "recent_7_days" : "user")
                        .put("fixed_recent_days", usePreferenceDate ? 7 : JSONObject.NULL)
                        .put("region_corrected", !province.equals(originalProvince) || !city.equals(originalCity)))
                .put("record_total", details.size())
                .put("location_total", grouped.size())
                .put("locations", top)
                .put("source", "观鸟记录中心 https://www.birdreport.cn/home/search/page.html");
    }

    private static String[] extractExplicitDateRange(String question) {
        String text = question == null ? "" : question.replaceAll("\\s+", "");
        Calendar today = Calendar.getInstance();
        if (text.contains("今天")) {
            String day = formatDate(today);
            return new String[]{day, day};
        }
        if (text.contains("昨天") || text.contains("昨日")) {
            today.add(Calendar.DAY_OF_MONTH, -1);
            String day = formatDate(today);
            return new String[]{day, day};
        }
        if (text.contains("前天")) {
            today.add(Calendar.DAY_OF_MONTH, -2);
            String day = formatDate(today);
            return new String[]{day, day};
        }
        Matcher matcher = Pattern.compile("(?:最近|近|过去|这)([一二两三四五六七八九十0-9]+)天").matcher(text);
        if (matcher.find()) {
            int days = parseChineseNumber(matcher.group(1));
            if (days > 0) {
                String end = formatDate(today);
                today.add(Calendar.DAY_OF_MONTH, -(days - 1));
                return new String[]{formatDate(today), end};
            }
        }
        return null;
    }

    private static int parseChineseNumber(String value) {
        if (value == null || value.isEmpty()) return 0;
        try { return Integer.parseInt(value); } catch (NumberFormatException ignored) { }
        if ("一".equals(value)) return 1;
        if ("二".equals(value)) return 2;
        if ("两".equals(value)) return 2;
        if ("三".equals(value)) return 3;
        if ("四".equals(value)) return 4;
        if ("五".equals(value)) return 5;
        if ("六".equals(value)) return 6;
        if ("七".equals(value)) return 7;
        if ("八".equals(value)) return 8;
        if ("九".equals(value)) return 9;
        if ("十".equals(value)) return 10;
        if (value.length() == 2 && value.charAt(0) == '十') return 10 + parseChineseNumber(value.substring(1));
        if (value.length() == 2 && value.charAt(1) == '十') return parseChineseNumber(value.substring(0, 1)) * 10;
        return 0;
    }

    private List<JSONObject> fetchBirdreportDetails(String province, String city, String startDate, String endDate) throws Exception {
        List<JSONObject> details = new ArrayList<>();
        for (int outsideType = 0; outsideType <= 1; outsideType++) {
            int page = 1;
            int limit = 1500;
            while (true) {
                Map<String, String> params = new LinkedHashMap<>();
                params.put("startTime", startDate);
                params.put("endTime", endDate);
                params.put("province", province);
                params.put("state", "2");
                params.put("version", "CH4");
                params.put("mode", "0");
                params.put("outside_type", String.valueOf(outsideType));
                params.put("page", String.valueOf(page));
                params.put("limit", String.valueOf(limit));
                if (!city.isEmpty()) params.put("city", city);
                JSONObject response = birdreportPost("front/record/search/page", params);
                String code = String.valueOf(response.opt("code"));
                if (!"0".equals(code)) {
                    if ("405".equals(code) || "505".equals(code)) throw new CaptchaRequiredException();
                    throw new Exception("观鸟记录中心查询失败：" + response.optString("msg", "code=" + code));
                }
                int count = response.optInt("count");
                if (count <= 0) break;
                JSONArray records = decodeBirdreportData(response.optString("data"));
                for (int i = 0; i < records.length(); i++) {
                    JSONObject detail = normalizeBirdDetail(records.getJSONObject(i), province + city, outsideType);
                    if (detail != null) details.add(detail);
                }
                if (page * limit >= count || records.length() == 0) break;
                page++;
            }
        }
        return details;
    }

    private JSONObject normalizeBirdDetail(JSONObject record, String fallback, int outsideType) throws Exception {
        if (!"2".equals(String.valueOf(record.opt("state")))) return null;
        String point = firstNonEmpty(record.optString("point_name"), record.optString("pointname"));
        if (point.matches("\\d+")) return null;
        String report = record.optString("serial_id").trim();
        String bird = firstNonEmpty(record.optString("taxon_name"), record.optString("taxonname")).trim();
        if (report.isEmpty() || bird.isEmpty()) return null;
        String province = record.optString("province_name").trim();
        String city = record.optString("city_name").trim();
        String district = record.optString("district_name").trim();
        StringBuilder location = new StringBuilder();
        if (!province.isEmpty()) location.append(province);
        if (!city.isEmpty() && !city.equals(province)) location.append(city);
        if (!district.isEmpty()) location.append(district);
        location.append(point.isEmpty() ? fallback : point);
        String protection = normalizeProtectionLevel(firstNonEmpty(record.optString("protection_level"),
                firstNonEmpty(record.optString("protect_level"), record.optString("protectionLevel"))));
        if (protection.isEmpty()) protection = catalogProtectionLevel(bird);
        if (protection.isEmpty()) protection = catalogProtectionLevelComplete(bird);
        return new JSONObject().put("report_no", report).put("observation_location", location.toString())
                .put("record_user", record.optString("username").trim())
                .put("bird_name", bird).put("protection_level", protection).put("outside_type", outsideType);
    }

    private static String normalizeProtectionLevel(String value) {
        String text = value == null ? "" : value.trim();
        if (text.contains("Ⅰ") || text.matches(".*(?:一|1).*(?:级|类)?.*")) return "Ⅰ级";
        if (text.contains("Ⅱ") || text.matches(".*(?:二|2).*(?:级|类)?.*")) return "Ⅱ级";
        return "";
    }

    private static String catalogProtectionLevel(String bird) {
        if (bird != null && (bird.contains("中华凤头燕鸥") || bird.contains("黑嘴端凤头燕鸥"))) return "Ⅰ级";
        if ("环颈山鹧鸪".equals(bird)) return "Ⅱ级";
        if ("四川山鹧鸪".equals(bird)) return "Ⅰ级";
        if ("红喉山鹧鸪".equals(bird)) return "Ⅱ级";
        if ("白眉山鹧鸪".equals(bird)) return "Ⅱ级";
        if ("白颊山鹧鸪".equals(bird)) return "Ⅱ级";
        if ("褐胸山鹧鸪".equals(bird)) return "Ⅱ级";
        if ("红胸山鹧鸪".equals(bird)) return "Ⅱ级";
        if ("台湾山鹧鸪".equals(bird)) return "Ⅱ级";
        if ("海南山鹧鸪".equals(bird)) return "Ⅰ级";
        if ("绿脚树鹧鸪".equals(bird)) return "Ⅱ级";
        if ("花尾榛鸡".equals(bird)) return "Ⅱ级";
        if ("斑尾榛鸡".equals(bird)) return "Ⅰ级";
        if ("镰翅鸡".equals(bird)) return "Ⅱ级";
        if ("松鸡".equals(bird)) return "Ⅱ级";
        if ("黑嘴松鸡 (原名“细嘴松鸡”)".equals(bird)) return "Ⅰ级";
        if ("黑琴鸡".equals(bird)) return "Ⅰ级";
        if ("岩雷鸟".equals(bird)) return "Ⅱ级";
        if ("柳雷鸟".equals(bird)) return "Ⅱ级";
        if ("红喉雉鹑".equals(bird)) return "Ⅰ级";
        if ("黄喉雉鹑".equals(bird)) return "Ⅰ级";
        if ("暗腹雪鸡".equals(bird)) return "Ⅱ级";
        if ("藏雪鸡".equals(bird)) return "Ⅱ级";
        if ("阿尔泰雪鸡".equals(bird)) return "Ⅱ级";
        if ("大石鸡".equals(bird)) return "Ⅱ级";
        if ("血雉".equals(bird)) return "Ⅱ级";
        if ("黑头角雉".equals(bird)) return "Ⅰ级";
        if ("红胸角雉".equals(bird)) return "Ⅰ级";
        if ("灰腹角雉".equals(bird)) return "Ⅰ级";
        if ("红腹角雉".equals(bird)) return "Ⅱ级";
        if ("黄腹角雉".equals(bird)) return "Ⅰ级";
        if ("勺鸡".equals(bird)) return "Ⅱ级";
        if ("棕尾虹雉".equals(bird)) return "Ⅰ级";
        if ("白尾梢虹雉".equals(bird)) return "Ⅰ级";
        if ("绿尾虹雉".equals(bird)) return "Ⅰ级";
        if ("红原鸡 (原名“原鸡”)".equals(bird)) return "Ⅱ级";
        if ("黑鹇".equals(bird)) return "Ⅱ级";
        if ("白鹇".equals(bird)) return "Ⅱ级";
        if ("蓝腹鹇 (原名“蓝鹇”)".equals(bird)) return "Ⅰ级";
        if ("白马鸡".equals(bird)) return "Ⅱ级";
        if ("藏马鸡".equals(bird)) return "Ⅱ级";
        if ("褐马鸡".equals(bird)) return "Ⅰ级";
        if ("蓝马鸡".equals(bird)) return "Ⅱ级";
        if ("白颈长尾雉".equals(bird)) return "Ⅰ级";
        if ("黑颈长尾雉".equals(bird)) return "Ⅰ级";
        if ("黑长尾雉".equals(bird)) return "Ⅰ级";
        if ("白冠长尾雉".equals(bird)) return "Ⅰ级";
        if ("红腹锦鸡".equals(bird)) return "Ⅱ级";
        if ("白腹锦鸡".equals(bird)) return "Ⅱ级";
        if ("灰孔雀雉".equals(bird)) return "Ⅰ级";
        if ("海南孔雀雉".equals(bird)) return "Ⅰ级";
        if ("绿孔雀".equals(bird)) return "Ⅰ级";
        if ("栗树鸭".equals(bird)) return "Ⅱ级";
        if ("鸿雁".equals(bird)) return "Ⅱ级";
        if ("白额雁".equals(bird)) return "Ⅱ级";
        if ("小白额雁".equals(bird)) return "Ⅱ级";
        if ("红胸黑雁".equals(bird)) return "Ⅱ级";
        if ("疣鼻天鹅".equals(bird)) return "Ⅱ级";
        if ("小天鹅".equals(bird)) return "Ⅱ级";
        if ("大天鹅".equals(bird)) return "Ⅱ级";
        if ("鸳鸯".equals(bird)) return "Ⅱ级";
        if ("棉凫".equals(bird)) return "Ⅱ级";
        if ("花脸鸭".equals(bird)) return "Ⅱ级";
        if ("云石斑鸭".equals(bird)) return "Ⅱ级";
        if ("青头潜鸭".equals(bird)) return "Ⅰ级";
        if ("斑头秋沙鸭".equals(bird)) return "Ⅱ级";
        if ("中华秋沙鸭".equals(bird)) return "Ⅰ级";
        if ("白头硬尾鸭".equals(bird)) return "Ⅰ级";
        if ("白翅栖鸭".equals(bird)) return "Ⅱ级";
        if ("中亚鸽".equals(bird)) return "Ⅱ级";
        if ("斑尾林鸽".equals(bird)) return "Ⅱ级";
        if ("紫林鸽".equals(bird)) return "Ⅱ级";
        if ("斑尾鹃鸠".equals(bird)) return "Ⅱ级";
        if ("菲律宾鹃鸠".equals(bird)) return "Ⅱ级";
        if ("小鹃鸠 (原名“棕头鹃鸠”)".equals(bird)) return "Ⅰ级";
        if ("橙胸绿鸠".equals(bird)) return "Ⅱ级";
        if ("灰头绿鸠".equals(bird)) return "Ⅱ级";
        if ("厚嘴绿鸠".equals(bird)) return "Ⅱ级";
        if ("黄脚绿鸠".equals(bird)) return "Ⅱ级";
        if ("针尾绿鸠".equals(bird)) return "Ⅱ级";
        if ("楔尾绿鸠".equals(bird)) return "Ⅱ级";
        if ("红翅绿鸠".equals(bird)) return "Ⅱ级";
        if ("红顶绿鸠".equals(bird)) return "Ⅱ级";
        if ("黑颏果鸠".equals(bird)) return "Ⅱ级";
        if ("绿皇鸠".equals(bird)) return "Ⅱ级";
        if ("山皇鸠".equals(bird)) return "Ⅱ级";
        if ("褐翅鸦鹃".equals(bird)) return "Ⅱ级";
        if ("小鸦鹃".equals(bird)) return "Ⅱ级";
        if ("大鸨".equals(bird)) return "Ⅰ级";
        if ("波斑鸨".equals(bird)) return "Ⅰ级";
        if ("小鸨".equals(bird)) return "Ⅰ级";
        if ("花田鸡".equals(bird)) return "Ⅱ级";
        if ("长脚秧鸡".equals(bird)) return "Ⅱ级";
        if ("棕背田鸡".equals(bird)) return "Ⅱ级";
        if ("姬田鸡".equals(bird)) return "Ⅱ级";
        if ("斑胁田鸡".equals(bird)) return "Ⅱ级";
        if ("紫水鸡".equals(bird)) return "Ⅱ级";
        if ("白鹤".equals(bird)) return "Ⅰ级";
        if ("沙丘鹤".equals(bird)) return "Ⅱ级";
        if ("白枕鹤".equals(bird)) return "Ⅰ级";
        if ("赤颈鹤".equals(bird)) return "Ⅰ级";
        if ("蓑羽鹤".equals(bird)) return "Ⅱ级";
        if ("丹顶鹤".equals(bird)) return "Ⅰ级";
        if ("灰鹤".equals(bird)) return "Ⅱ级";
        if ("白头鹤".equals(bird)) return "Ⅰ级";
        if ("黑颈鹤".equals(bird)) return "Ⅰ级";
        if ("黑脚信天翁".equals(bird)) return "Ⅰ级";
        if ("短尾信天翁".equals(bird)) return "Ⅰ级";
        if ("彩鹳".equals(bird)) return "Ⅰ级";
        if ("黑鹳".equals(bird)) return "Ⅰ级";
        if ("白鹳".equals(bird)) return "Ⅰ级";
        if ("东方白鹳".equals(bird)) return "Ⅰ级";
        if ("秃鹳".equals(bird)) return "Ⅱ级";
        if ("白腹军舰鸟".equals(bird)) return "Ⅰ级";
        if ("黑腹军舰鸟".equals(bird)) return "Ⅱ级";
        if ("白斑军舰鸟".equals(bird)) return "Ⅱ级";
        if ("蓝脸鲣鸟".equals(bird)) return "Ⅱ级";
        if ("红脚鲣鸟".equals(bird)) return "Ⅱ级";
        if ("褐鲣鸟".equals(bird)) return "Ⅱ级";
        if ("黑颈鸬鹚".equals(bird)) return "Ⅱ级";
        if ("海鸬鹚".equals(bird)) return "Ⅱ级";
        if ("黑头白鹮 (原名“白鹮”)".equals(bird)) return "Ⅰ级";
        if ("白肩黑鹮 (原名“黑鹮”)".equals(bird)) return "Ⅰ级";
        if ("朱鹮".equals(bird)) return "Ⅰ级";
        if ("彩鹮".equals(bird)) return "Ⅰ级";
        if ("白琵鹭".equals(bird)) return "Ⅱ级";
        if ("黑脸琵鹭".equals(bird)) return "Ⅰ级";
        if ("小苇鳽".equals(bird)) return "Ⅱ级";
        if ("海南鳽 (原名“海南虎斑鳽”)".equals(bird)) return "Ⅰ级";
        if ("栗头鳽".equals(bird)) return "Ⅱ级";
        if ("黑冠鳽".equals(bird)) return "Ⅱ级";
        if ("白腹鹭".equals(bird)) return "Ⅰ级";
        if ("岩鹭".equals(bird)) return "Ⅱ级";
        if ("黄嘴白鹭".equals(bird)) return "Ⅰ级";
        if ("白鹈鹕".equals(bird)) return "Ⅰ级";
        if ("斑嘴鹈鹕".equals(bird)) return "Ⅰ级";
        if ("卷羽鹈鹕".equals(bird)) return "Ⅰ级";
        if ("黄嘴角鸮".equals(bird)) return "Ⅱ级";
        if ("领角鸮".equals(bird)) return "Ⅱ级";
        if ("北领角鸮".equals(bird)) return "Ⅱ级";
        if ("纵纹角鸮".equals(bird)) return "Ⅱ级";
        if ("西红角鸮".equals(bird)) return "Ⅱ级";
        if ("红角鸮".equals(bird)) return "Ⅱ级";
        if ("优雅角鸮".equals(bird)) return "Ⅱ级";
        if ("雪鸮".equals(bird)) return "Ⅱ级";
        if ("雕鸮".equals(bird)) return "Ⅱ级";
        if ("林雕鸮".equals(bird)) return "Ⅱ级";
        if ("毛腿雕鸮".equals(bird)) return "Ⅰ级";
        if ("褐渔鸮".equals(bird)) return "Ⅱ级";
        if ("黄腿渔鸮".equals(bird)) return "Ⅱ级";
        if ("褐林鸮".equals(bird)) return "Ⅱ级";
        if ("灰林鸮".equals(bird)) return "Ⅱ级";
        if ("长尾林鸮".equals(bird)) return "Ⅱ级";
        if ("四川林鸮".equals(bird)) return "Ⅰ级";
        if ("乌林鸮".equals(bird)) return "Ⅱ级";
        if ("猛鸮".equals(bird)) return "Ⅱ级";
        if ("花头鸺鹠".equals(bird)) return "Ⅱ级";
        if ("领鸺鹠".equals(bird)) return "Ⅱ级";
        if ("斑头鸺鹠".equals(bird)) return "Ⅱ级";
        if ("纵纹腹小鸮".equals(bird)) return "Ⅱ级";
        if ("横斑腹小鸮".equals(bird)) return "Ⅱ级";
        if ("鬼鸮".equals(bird)) return "Ⅱ级";
        if ("鹰鸮".equals(bird)) return "Ⅱ级";
        if ("日本鹰鸮".equals(bird)) return "Ⅱ级";
        if ("长耳鸮".equals(bird)) return "Ⅱ级";
        if ("短耳鸮".equals(bird)) return "Ⅱ级";
        if ("仓鸮".equals(bird)) return "Ⅱ级";
        if ("草鸮".equals(bird)) return "Ⅱ级";
        if ("栗鸮".equals(bird)) return "Ⅱ级";
        if ("赤须蜂虎".equals(bird)) return "Ⅱ级";
        if ("蓝须蜂虎".equals(bird)) return "Ⅱ级";
        if ("绿喉蜂虎".equals(bird)) return "Ⅱ级";
        if ("蓝颊蜂虎".equals(bird)) return "Ⅱ级";
        if ("栗喉蜂虎".equals(bird)) return "Ⅱ级";
        if ("彩虹蜂虎".equals(bird)) return "Ⅱ级";
        if ("蓝喉蜂虎".equals(bird)) return "Ⅱ级";
        if ("栗头蜂虎 (原名“黑胸蜂虎”)".equals(bird)) return "Ⅱ级";
        if ("鹳嘴翡翠 (原名“鹳嘴翠鸟”)".equals(bird)) return "Ⅱ级";
        if ("白胸翡翠".equals(bird)) return "Ⅱ级";
        if ("蓝耳翠鸟".equals(bird)) return "Ⅱ级";
        if ("斑头大翠鸟".equals(bird)) return "Ⅱ级";
        if ("白翅啄木鸟".equals(bird)) return "Ⅱ级";
        if ("三趾啄木鸟".equals(bird)) return "Ⅱ级";
        if ("白腹黑啄木鸟".equals(bird)) return "Ⅱ级";
        if ("黑啄木鸟".equals(bird)) return "Ⅱ级";
        if ("大黄冠啄木鸟".equals(bird)) return "Ⅱ级";
        if ("黄冠啄木鸟".equals(bird)) return "Ⅱ级";
        if ("红颈绿啄木鸟".equals(bird)) return "Ⅱ级";
        if ("大灰啄木鸟".equals(bird)) return "Ⅱ级";
        if ("红腿小隼".equals(bird)) return "Ⅱ级";
        if ("白腿小隼".equals(bird)) return "Ⅱ级";
        if ("黄爪隼".equals(bird)) return "Ⅱ级";
        if ("红隼".equals(bird)) return "Ⅱ级";
        if ("西红脚隼".equals(bird)) return "Ⅱ级";
        if ("红脚隼".equals(bird)) return "Ⅱ级";
        if ("灰背隼".equals(bird)) return "Ⅱ级";
        if ("燕隼".equals(bird)) return "Ⅱ级";
        if ("猛隼".equals(bird)) return "Ⅱ级";
        if ("猎隼".equals(bird)) return "Ⅰ级";
        if ("矛隼".equals(bird)) return "Ⅰ级";
        if ("游隼".equals(bird)) return "Ⅱ级";
        if ("双辫八色鸫".equals(bird)) return "Ⅱ级";
        if ("蓝枕八色鸫".equals(bird)) return "Ⅱ级";
        if ("蓝背八色鸫".equals(bird)) return "Ⅱ级";
        if ("栗头八色鸫".equals(bird)) return "Ⅱ级";
        if ("蓝八色鸫".equals(bird)) return "Ⅱ级";
        if ("绿胸八色鸫".equals(bird)) return "Ⅱ级";
        if ("仙八色鸫".equals(bird)) return "Ⅱ级";
        if ("蓝翅八色鸫".equals(bird)) return "Ⅱ级";
        if ("长尾阔嘴鸟".equals(bird)) return "Ⅱ级";
        if ("银胸丝冠鸟".equals(bird)) return "Ⅱ级";
        if ("鹊鹂".equals(bird)) return "Ⅱ级";
        if ("小盘尾".equals(bird)) return "Ⅱ级";
        if ("大盘尾".equals(bird)) return "Ⅱ级";
        if ("黑头噪鸦".equals(bird)) return "Ⅰ级";
        if ("蓝绿鹊".equals(bird)) return "Ⅱ级";
        if ("黄胸绿鹊".equals(bird)) return "Ⅱ级";
        if ("黑尾地鸦".equals(bird)) return "Ⅱ级";
        if ("白尾地鸦".equals(bird)) return "Ⅱ级";
        if ("白眉山雀".equals(bird)) return "Ⅱ级";
        if ("红腹山雀".equals(bird)) return "Ⅱ级";
        if ("歌百灵".equals(bird)) return "Ⅱ级";
        if ("蒙古百灵".equals(bird)) return "Ⅱ级";
        if ("云雀".equals(bird)) return "Ⅱ级";
        if ("细纹苇莺".equals(bird)) return "Ⅱ级";
        if ("台湾鹎".equals(bird)) return "Ⅱ级";
        if ("金胸雀鹛".equals(bird)) return "Ⅱ级";
        if ("宝兴鹛雀".equals(bird)) return "Ⅱ级";
        if ("中华雀鹛".equals(bird)) return "Ⅱ级";
        if ("三趾鸦雀".equals(bird)) return "Ⅱ级";
        if ("白眶鸦雀".equals(bird)) return "Ⅱ级";
        if ("暗色鸦雀".equals(bird)) return "Ⅱ级";
        if ("灰冠鸦雀".equals(bird)) return "Ⅰ级";
        if ("短尾鸦雀".equals(bird)) return "Ⅱ级";
        if ("震旦鸦雀".equals(bird)) return "Ⅱ级";
        if ("红胁绣眼鸟".equals(bird)) return "Ⅱ级";
        if ("淡喉鹩鹛".equals(bird)) return "Ⅱ级";
        if ("弄岗穗鹛".equals(bird)) return "Ⅱ级";
        if ("金额雀鹛".equals(bird)) return "Ⅰ级";
        if ("大草鹛".equals(bird)) return "Ⅱ级";
        if ("棕草鹛".equals(bird)) return "Ⅱ级";
        if ("画眉".equals(bird)) return "Ⅱ级";
        if ("海南画眉".equals(bird)) return "Ⅱ级";
        if ("台湾画眉".equals(bird)) return "Ⅱ级";
        if ("褐胸噪鹛".equals(bird)) return "Ⅱ级";
        if ("黑额山噪鹛".equals(bird)) return "Ⅰ级";
        if ("斑背噪鹛".equals(bird)) return "Ⅱ级";
        if ("白点噪鹛".equals(bird)) return "Ⅰ级";
        if ("大噪鹛".equals(bird)) return "Ⅱ级";
        if ("眼纹噪鹛".equals(bird)) return "Ⅱ级";
        if ("黑喉噪鹛".equals(bird)) return "Ⅱ级";
        if ("蓝冠噪鹛".equals(bird)) return "Ⅰ级";
        if ("棕噪鹛".equals(bird)) return "Ⅱ级";
        if ("橙翅噪鹛".equals(bird)) return "Ⅱ级";
        if ("红翅噪鹛".equals(bird)) return "Ⅱ级";
        if ("红尾噪鹛".equals(bird)) return "Ⅱ级";
        if ("黑冠薮鹛".equals(bird)) return "Ⅰ级";
        if ("灰胸薮鹛".equals(bird)) return "Ⅰ级";
        if ("银耳相思鸟".equals(bird)) return "Ⅱ级";
        if ("红嘴相思鸟".equals(bird)) return "Ⅱ级";
        if ("四川旋木雀".equals(bird)) return "Ⅱ级";
        if ("滇䴓".equals(bird)) return "Ⅱ级";
        if ("巨䴓".equals(bird)) return "Ⅱ级";
        if ("丽䴓".equals(bird)) return "Ⅱ级";
        if ("鹩哥".equals(bird)) return "Ⅱ级";
        if ("褐头鸫".equals(bird)) return "Ⅱ级";
        if ("紫宽嘴鸫".equals(bird)) return "Ⅱ级";
        if ("绿宽嘴鸫".equals(bird)) return "Ⅱ级";
        if ("棕头歌鸲".equals(bird)) return "Ⅰ级";
        if ("红喉歌鸲".equals(bird)) return "Ⅱ级";
        if ("黑喉歌鸲".equals(bird)) return "Ⅱ级";
        if ("金胸歌鸲".equals(bird)) return "Ⅱ级";
        if ("蓝喉歌鸲".equals(bird)) return "Ⅱ级";
        if ("新疆歌鸲".equals(bird)) return "Ⅱ级";
        if ("棕腹林鸲".equals(bird)) return "Ⅱ级";
        if ("贺兰山红尾鸲".equals(bird)) return "Ⅱ级";
        if ("白喉石鵖".equals(bird)) return "Ⅱ级";
        if ("白喉林鹟".equals(bird)) return "Ⅱ级";
        if ("棕腹大仙鹟".equals(bird)) return "Ⅱ级";
        if ("大仙鹟".equals(bird)) return "Ⅱ级";
        if ("贺兰山岩鹨".equals(bird)) return "Ⅱ级";
        if ("朱鹀".equals(bird)) return "Ⅱ级";
        if ("褐头朱雀".equals(bird)) return "Ⅱ级";
        if ("藏雀".equals(bird)) return "Ⅱ级";
        if ("北朱雀".equals(bird)) return "Ⅱ级";
        if ("红交嘴雀".equals(bird)) return "Ⅱ级";
        if ("蓝鹀".equals(bird)) return "Ⅱ级";
        if ("栗斑腹鹀".equals(bird)) return "Ⅰ级";
        if ("黄胸鹀".equals(bird)) return "Ⅰ级";
        if ("藏鹀".equals(bird)) return "Ⅱ级";
        return "";
    }

    private static int protectionRank(String level) {
        if ("Ⅰ级".equals(level)) return 2;
        if ("Ⅱ级".equals(level)) return 1;
        return 0;
    }

    private static String catalogProtectionLevelComplete(String bird) {
        if ("蜂猴".equals(bird) || bird.startsWith("蜂猴 (")) return "Ⅰ级";
        if ("倭蜂猴".equals(bird) || bird.startsWith("倭蜂猴 (")) return "Ⅰ级";
        if ("短尾猴".equals(bird) || bird.startsWith("短尾猴 (")) return "Ⅱ级";
        if ("熊猴".equals(bird) || bird.startsWith("熊猴 (")) return "Ⅱ级";
        if ("台湾猴".equals(bird) || bird.startsWith("台湾猴 (")) return "Ⅰ级";
        if ("北豚尾猴 (原名“豚尾猴”)".equals(bird) || bird.startsWith("北豚尾猴 (原名“豚尾猴”) (")) return "Ⅰ级";
        if ("白颊猕猴".equals(bird) || bird.startsWith("白颊猕猴 (")) return "Ⅱ级";
        if ("猕猴".equals(bird) || bird.startsWith("猕猴 (")) return "Ⅱ级";
        if ("藏南猕猴".equals(bird) || bird.startsWith("藏南猕猴 (")) return "Ⅱ级";
        if ("藏酋猴".equals(bird) || bird.startsWith("藏酋猴 (")) return "Ⅱ级";
        if ("喜山长尾叶猴".equals(bird) || bird.startsWith("喜山长尾叶猴 (")) return "Ⅰ级";
        if ("印支灰叶猴".equals(bird) || bird.startsWith("印支灰叶猴 (")) return "Ⅰ级";
        if ("黑叶猴".equals(bird) || bird.startsWith("黑叶猴 (")) return "Ⅰ级";
        if ("菲氏叶猴".equals(bird) || bird.startsWith("菲氏叶猴 (")) return "Ⅰ级";
        if ("戴帽叶猴".equals(bird) || bird.startsWith("戴帽叶猴 (")) return "Ⅰ级";
        if ("白头叶猴".equals(bird) || bird.startsWith("白头叶猴 (")) return "Ⅰ级";
        if ("肖氏乌叶猴".equals(bird) || bird.startsWith("肖氏乌叶猴 (")) return "Ⅰ级";
        if ("滇金丝猴".equals(bird) || bird.startsWith("滇金丝猴 (")) return "Ⅰ级";
        if ("黔金丝猴".equals(bird) || bird.startsWith("黔金丝猴 (")) return "Ⅰ级";
        if ("川金丝猴".equals(bird) || bird.startsWith("川金丝猴 (")) return "Ⅰ级";
        if ("怒江金丝猴".equals(bird) || bird.startsWith("怒江金丝猴 (")) return "Ⅰ级";
        if ("西白眉长臂猿".equals(bird) || bird.startsWith("西白眉长臂猿 (")) return "Ⅰ级";
        if ("东白眉长臂猿".equals(bird) || bird.startsWith("东白眉长臂猿 (")) return "Ⅰ级";
        if ("高黎贡白眉长臂猿".equals(bird) || bird.startsWith("高黎贡白眉长臂猿 (")) return "Ⅰ级";
        if ("白掌长臂猿".equals(bird) || bird.startsWith("白掌长臂猿 (")) return "Ⅰ级";
        if ("西黑冠长臂猿".equals(bird) || bird.startsWith("西黑冠长臂猿 (")) return "Ⅰ级";
        if ("东黑冠长臂猿".equals(bird) || bird.startsWith("东黑冠长臂猿 (")) return "Ⅰ级";
        if ("海南长臂猿".equals(bird) || bird.startsWith("海南长臂猿 (")) return "Ⅰ级";
        if ("北白颊长臂猿".equals(bird) || bird.startsWith("北白颊长臂猿 (")) return "Ⅰ级";
        if ("印度穿山甲".equals(bird) || bird.startsWith("印度穿山甲 (")) return "Ⅰ级";
        if ("马来穿山甲".equals(bird) || bird.startsWith("马来穿山甲 (")) return "Ⅰ级";
        if ("穿山甲".equals(bird) || bird.startsWith("穿山甲 (")) return "Ⅰ级";
        if ("狼".equals(bird) || bird.startsWith("狼 (")) return "Ⅱ级";
        if ("亚洲胡狼".equals(bird) || bird.startsWith("亚洲胡狼 (")) return "Ⅱ级";
        if ("豺".equals(bird) || bird.startsWith("豺 (")) return "Ⅰ级";
        if ("貉 (仅限野外种群)".equals(bird) || bird.startsWith("貉 (仅限野外种群) (")) return "Ⅱ级";
        if ("沙狐".equals(bird) || bird.startsWith("沙狐 (")) return "Ⅱ级";
        if ("藏狐".equals(bird) || bird.startsWith("藏狐 (")) return "Ⅱ级";
        if ("赤狐".equals(bird) || bird.startsWith("赤狐 (")) return "Ⅱ级";
        if ("懒熊".equals(bird) || bird.startsWith("懒熊 (")) return "Ⅱ级";
        if ("马来熊".equals(bird) || bird.startsWith("马来熊 (")) return "Ⅰ级";
        if ("棕熊".equals(bird) || bird.startsWith("棕熊 (")) return "Ⅱ级";
        if ("黑熊".equals(bird) || bird.startsWith("黑熊 (")) return "Ⅱ级";
        if ("大熊猫".equals(bird) || bird.startsWith("大熊猫 (")) return "Ⅰ级";
        if ("小熊猫".equals(bird) || bird.startsWith("小熊猫 (")) return "Ⅱ级";
        if ("黄喉貂".equals(bird) || bird.startsWith("黄喉貂 (")) return "Ⅱ级";
        if ("石貂".equals(bird) || bird.startsWith("石貂 (")) return "Ⅱ级";
        if ("紫貂".equals(bird) || bird.startsWith("紫貂 (")) return "Ⅰ级";
        if ("貂熊".equals(bird) || bird.startsWith("貂熊 (")) return "Ⅰ级";
        if ("*小爪水獭".equals(bird) || bird.startsWith("*小爪水獭 (")) return "Ⅱ级";
        if ("*水獭".equals(bird) || bird.startsWith("*水獭 (")) return "Ⅱ级";
        if ("*江獭".equals(bird) || bird.startsWith("*江獭 (")) return "Ⅱ级";
        if ("大斑灵猫".equals(bird) || bird.startsWith("大斑灵猫 (")) return "Ⅰ级";
        if ("大灵猫".equals(bird) || bird.startsWith("大灵猫 (")) return "Ⅰ级";
        if ("小灵猫".equals(bird) || bird.startsWith("小灵猫 (")) return "Ⅰ级";
        if ("椰子猫".equals(bird) || bird.startsWith("椰子猫 (")) return "Ⅱ级";
        if ("熊狸".equals(bird) || bird.startsWith("熊狸 (")) return "Ⅰ级";
        if ("小齿狸 (又名“小齿椰子猫”)".equals(bird) || bird.startsWith("小齿狸 (又名“小齿椰子猫”) (")) return "Ⅰ级";
        if ("缟灵猫".equals(bird) || bird.startsWith("缟灵猫 (")) return "Ⅰ级";
        if ("斑林狸".equals(bird) || bird.startsWith("斑林狸 (")) return "Ⅱ级";
        if ("荒漠猫".equals(bird) || bird.startsWith("荒漠猫 (")) return "Ⅰ级";
        if ("丛林猫".equals(bird) || bird.startsWith("丛林猫 (")) return "Ⅰ级";
        if ("草原斑猫".equals(bird) || bird.startsWith("草原斑猫 (")) return "Ⅱ级";
        if ("渔猫".equals(bird) || bird.startsWith("渔猫 (")) return "Ⅱ级";
        if ("兔狲".equals(bird) || bird.startsWith("兔狲 (")) return "Ⅱ级";
        if ("猞猁".equals(bird) || bird.startsWith("猞猁 (")) return "Ⅱ级";
        if ("云猫".equals(bird) || bird.startsWith("云猫 (")) return "Ⅱ级";
        if ("金猫".equals(bird) || bird.startsWith("金猫 (")) return "Ⅰ级";
        if ("豹猫".equals(bird) || bird.startsWith("豹猫 (")) return "Ⅱ级";
        if ("云豹".equals(bird) || bird.startsWith("云豹 (")) return "Ⅰ级";
        if ("豹".equals(bird) || bird.startsWith("豹 (")) return "Ⅰ级";
        if ("虎".equals(bird) || bird.startsWith("虎 (")) return "Ⅰ级";
        if ("雪豹".equals(bird) || bird.startsWith("雪豹 (")) return "Ⅰ级";
        if ("*北海狗".equals(bird) || bird.startsWith("*北海狗 (")) return "Ⅱ级";
        if ("*北海狮".equals(bird) || bird.startsWith("*北海狮 (")) return "Ⅱ级";
        if ("*西太平洋斑海豹 (原名“斑海豹”)".equals(bird) || bird.startsWith("*西太平洋斑海豹 (原名“斑海豹”) (")) return "Ⅰ级";
        if ("*髯海豹".equals(bird) || bird.startsWith("*髯海豹 (")) return "Ⅱ级";
        if ("*环海豹".equals(bird) || bird.startsWith("*环海豹 (")) return "Ⅱ级";
        if ("亚洲象".equals(bird) || bird.startsWith("亚洲象 (")) return "Ⅰ级";
        if ("普氏野马 (原名“野马”)".equals(bird) || bird.startsWith("普氏野马 (原名“野马”) (")) return "Ⅰ级";
        if ("蒙古野驴".equals(bird) || bird.startsWith("蒙古野驴 (")) return "Ⅰ级";
        if ("藏野驴 (原名“西藏野驴”)".equals(bird) || bird.startsWith("藏野驴 (原名“西藏野驴”) (")) return "Ⅰ级";
        if ("野骆驼".equals(bird) || bird.startsWith("野骆驼 (")) return "Ⅰ级";
        if ("威氏鼷鹿 (原名“鼷鹿”)".equals(bird) || bird.startsWith("威氏鼷鹿 (原名“鼷鹿”) (")) return "Ⅰ级";
        if ("安徽麝".equals(bird) || bird.startsWith("安徽麝 (")) return "Ⅰ级";
        if ("林麝".equals(bird) || bird.startsWith("林麝 (")) return "Ⅰ级";
        if ("马麝".equals(bird) || bird.startsWith("马麝 (")) return "Ⅰ级";
        if ("黑麝".equals(bird) || bird.startsWith("黑麝 (")) return "Ⅰ级";
        if ("喜马拉雅麝".equals(bird) || bird.startsWith("喜马拉雅麝 (")) return "Ⅰ级";
        if ("原麝".equals(bird) || bird.startsWith("原麝 (")) return "Ⅰ级";
        if ("獐 (原名“河麂”)".equals(bird) || bird.startsWith("獐 (原名“河麂”) (")) return "Ⅱ级";
        if ("黑麂".equals(bird) || bird.startsWith("黑麂 (")) return "Ⅰ级";
        if ("贡山麂".equals(bird) || bird.startsWith("贡山麂 (")) return "Ⅱ级";
        if ("海南麂".equals(bird) || bird.startsWith("海南麂 (")) return "Ⅱ级";
        if ("豚鹿".equals(bird) || bird.startsWith("豚鹿 (")) return "Ⅰ级";
        if ("水鹿".equals(bird) || bird.startsWith("水鹿 (")) return "Ⅱ级";
        if ("梅花鹿 (仅限野外种群)".equals(bird) || bird.startsWith("梅花鹿 (仅限野外种群) (")) return "Ⅰ级";
        if ("马鹿 (仅限野外种群)".equals(bird) || bird.startsWith("马鹿 (仅限野外种群) (")) return "Ⅱ级";
        if ("西藏马鹿（包括白臀鹿）".equals(bird) || bird.startsWith("西藏马鹿（包括白臀鹿） (")) return "Ⅰ级";
        if ("塔里木马鹿 (仅限野外种群)".equals(bird) || bird.startsWith("塔里木马鹿 (仅限野外种群) (")) return "Ⅰ级";
        if ("坡鹿".equals(bird) || bird.startsWith("坡鹿 (")) return "Ⅰ级";
        if ("白唇鹿".equals(bird) || bird.startsWith("白唇鹿 (")) return "Ⅰ级";
        if ("麋鹿".equals(bird) || bird.startsWith("麋鹿 (")) return "Ⅰ级";
        if ("毛冠鹿".equals(bird) || bird.startsWith("毛冠鹿 (")) return "Ⅱ级";
        if ("驼鹿".equals(bird) || bird.startsWith("驼鹿 (")) return "Ⅰ级";
        if ("野牛".equals(bird) || bird.startsWith("野牛 (")) return "Ⅰ级";
        if ("爪哇野牛".equals(bird) || bird.startsWith("爪哇野牛 (")) return "Ⅰ级";
        if ("野牦牛".equals(bird) || bird.startsWith("野牦牛 (")) return "Ⅰ级";
        if ("蒙原羚 (原名“黄羊”)".equals(bird) || bird.startsWith("蒙原羚 (原名“黄羊”) (")) return "Ⅰ级";
        if ("藏原羚".equals(bird) || bird.startsWith("藏原羚 (")) return "Ⅱ级";
        if ("普氏原羚".equals(bird) || bird.startsWith("普氏原羚 (")) return "Ⅰ级";
        if ("鹅喉羚".equals(bird) || bird.startsWith("鹅喉羚 (")) return "Ⅱ级";
        if ("藏羚".equals(bird) || bird.startsWith("藏羚 (")) return "Ⅰ级";
        if ("高鼻羚羊".equals(bird) || bird.startsWith("高鼻羚羊 (")) return "Ⅰ级";
        if ("秦岭羚牛".equals(bird) || bird.startsWith("秦岭羚牛 (")) return "Ⅰ级";
        if ("四川羚牛".equals(bird) || bird.startsWith("四川羚牛 (")) return "Ⅰ级";
        if ("不丹羚牛".equals(bird) || bird.startsWith("不丹羚牛 (")) return "Ⅰ级";
        if ("贡山羚牛 (原名“扭角羚”)".equals(bird) || bird.startsWith("贡山羚牛 (原名“扭角羚”) (")) return "Ⅰ级";
        if ("赤斑羚".equals(bird) || bird.startsWith("赤斑羚 (")) return "Ⅰ级";
        if ("长尾斑羚".equals(bird) || bird.startsWith("长尾斑羚 (")) return "Ⅱ级";
        if ("缅甸斑羚".equals(bird) || bird.startsWith("缅甸斑羚 (")) return "Ⅱ级";
        if ("喜马拉雅斑羚".equals(bird) || bird.startsWith("喜马拉雅斑羚 (")) return "Ⅰ级";
        if ("中华斑羚".equals(bird) || bird.startsWith("中华斑羚 (")) return "Ⅱ级";
        if ("塔尔羊".equals(bird) || bird.startsWith("塔尔羊 (")) return "Ⅰ级";
        if ("北山羊".equals(bird) || bird.startsWith("北山羊 (")) return "Ⅱ级";
        if ("岩羊".equals(bird) || bird.startsWith("岩羊 (")) return "Ⅱ级";
        if ("阿尔泰盘羊".equals(bird) || bird.startsWith("阿尔泰盘羊 (")) return "Ⅱ级";
        if ("哈萨克盘羊".equals(bird) || bird.startsWith("哈萨克盘羊 (")) return "Ⅱ级";
        if ("戈壁盘羊".equals(bird) || bird.startsWith("戈壁盘羊 (")) return "Ⅱ级";
        if ("西藏盘羊".equals(bird) || bird.startsWith("西藏盘羊 (")) return "Ⅰ级";
        if ("天山盘羊".equals(bird) || bird.startsWith("天山盘羊 (")) return "Ⅱ级";
        if ("帕米尔盘羊".equals(bird) || bird.startsWith("帕米尔盘羊 (")) return "Ⅱ级";
        if ("中华鬣羚".equals(bird) || bird.startsWith("中华鬣羚 (")) return "Ⅱ级";
        if ("红鬣羚".equals(bird) || bird.startsWith("红鬣羚 (")) return "Ⅱ级";
        if ("台湾鬣羚".equals(bird) || bird.startsWith("台湾鬣羚 (")) return "Ⅰ级";
        if ("喜马拉雅鬣羚".equals(bird) || bird.startsWith("喜马拉雅鬣羚 (")) return "Ⅰ级";
        if ("河狸".equals(bird) || bird.startsWith("河狸 (")) return "Ⅰ级";
        if ("巨松鼠".equals(bird) || bird.startsWith("巨松鼠 (")) return "Ⅱ级";
        if ("贺兰山鼠兔".equals(bird) || bird.startsWith("贺兰山鼠兔 (")) return "Ⅱ级";
        if ("伊犁鼠兔".equals(bird) || bird.startsWith("伊犁鼠兔 (")) return "Ⅱ级";
        if ("粗毛兔".equals(bird) || bird.startsWith("粗毛兔 (")) return "Ⅱ级";
        if ("海南兔".equals(bird) || bird.startsWith("海南兔 (")) return "Ⅱ级";
        if ("雪兔".equals(bird) || bird.startsWith("雪兔 (")) return "Ⅱ级";
        if ("塔里木兔".equals(bird) || bird.startsWith("塔里木兔 (")) return "Ⅱ级";
        if ("*儒艮".equals(bird) || bird.startsWith("*儒艮 (")) return "Ⅰ级";
        if ("*北太平洋露脊鲸".equals(bird) || bird.startsWith("*北太平洋露脊鲸 (")) return "Ⅰ级";
        if ("*灰鲸".equals(bird) || bird.startsWith("*灰鲸 (")) return "Ⅰ级";
        if ("*蓝鲸".equals(bird) || bird.startsWith("*蓝鲸 (")) return "Ⅰ级";
        if ("*小须鲸".equals(bird) || bird.startsWith("*小须鲸 (")) return "Ⅰ级";
        if ("*塞鲸".equals(bird) || bird.startsWith("*塞鲸 (")) return "Ⅰ级";
        if ("*布氏鲸".equals(bird) || bird.startsWith("*布氏鲸 (")) return "Ⅰ级";
        if ("*大村鲸".equals(bird) || bird.startsWith("*大村鲸 (")) return "Ⅰ级";
        if ("*长须鲸".equals(bird) || bird.startsWith("*长须鲸 (")) return "Ⅰ级";
        if ("*大翅鲸".equals(bird) || bird.startsWith("*大翅鲸 (")) return "Ⅰ级";
        if ("*白鱀豚".equals(bird) || bird.startsWith("*白鱀豚 (")) return "Ⅰ级";
        if ("*恒河豚".equals(bird) || bird.startsWith("*恒河豚 (")) return "Ⅰ级";
        if ("*中华白海豚".equals(bird) || bird.startsWith("*中华白海豚 (")) return "Ⅰ级";
        if ("*糙齿海豚".equals(bird) || bird.startsWith("*糙齿海豚 (")) return "Ⅱ级";
        if ("*热带点斑原海豚".equals(bird) || bird.startsWith("*热带点斑原海豚 (")) return "Ⅱ级";
        if ("*条纹原海豚".equals(bird) || bird.startsWith("*条纹原海豚 (")) return "Ⅱ级";
        if ("*飞旋原海豚".equals(bird) || bird.startsWith("*飞旋原海豚 (")) return "Ⅱ级";
        if ("*长喙真海豚".equals(bird) || bird.startsWith("*长喙真海豚 (")) return "Ⅱ级";
        if ("*真海豚".equals(bird) || bird.startsWith("*真海豚 (")) return "Ⅱ级";
        if ("*印太瓶鼻海豚".equals(bird) || bird.startsWith("*印太瓶鼻海豚 (")) return "Ⅱ级";
        if ("*瓶鼻海豚".equals(bird) || bird.startsWith("*瓶鼻海豚 (")) return "Ⅱ级";
        if ("*弗氏海豚".equals(bird) || bird.startsWith("*弗氏海豚 (")) return "Ⅱ级";
        if ("*里氏海豚".equals(bird) || bird.startsWith("*里氏海豚 (")) return "Ⅱ级";
        if ("*太平洋斑纹海豚".equals(bird) || bird.startsWith("*太平洋斑纹海豚 (")) return "Ⅱ级";
        if ("*瓜头鲸".equals(bird) || bird.startsWith("*瓜头鲸 (")) return "Ⅱ级";
        if ("*虎鲸".equals(bird) || bird.startsWith("*虎鲸 (")) return "Ⅱ级";
        if ("*伪虎鲸".equals(bird) || bird.startsWith("*伪虎鲸 (")) return "Ⅱ级";
        if ("*小虎鲸".equals(bird) || bird.startsWith("*小虎鲸 (")) return "Ⅱ级";
        if ("*短肢领航鲸".equals(bird) || bird.startsWith("*短肢领航鲸 (")) return "Ⅱ级";
        if ("*长江江豚".equals(bird) || bird.startsWith("*长江江豚 (")) return "Ⅰ级";
        if ("*东亚江豚".equals(bird) || bird.startsWith("*东亚江豚 (")) return "Ⅱ级";
        if ("*印太江豚".equals(bird) || bird.startsWith("*印太江豚 (")) return "Ⅱ级";
        if ("*抹香鲸".equals(bird) || bird.startsWith("*抹香鲸 (")) return "Ⅰ级";
        if ("*小抹香鲸".equals(bird) || bird.startsWith("*小抹香鲸 (")) return "Ⅱ级";
        if ("*侏抹香鲸".equals(bird) || bird.startsWith("*侏抹香鲸 (")) return "Ⅱ级";
        if ("*鹅喙鲸".equals(bird) || bird.startsWith("*鹅喙鲸 (")) return "Ⅱ级";
        if ("*柏氏中喙鲸".equals(bird) || bird.startsWith("*柏氏中喙鲸 (")) return "Ⅱ级";
        if ("*银杏齿中喙鲸".equals(bird) || bird.startsWith("*银杏齿中喙鲸 (")) return "Ⅱ级";
        if ("*小中喙鲸".equals(bird) || bird.startsWith("*小中喙鲸 (")) return "Ⅱ级";
        if ("*贝氏喙鲸".equals(bird) || bird.startsWith("*贝氏喙鲸 (")) return "Ⅱ级";
        if ("*朗氏喙鲸".equals(bird) || bird.startsWith("*朗氏喙鲸 (")) return "Ⅱ级";
        if ("环颈山鹧鸪".equals(bird) || bird.startsWith("环颈山鹧鸪 (")) return "Ⅱ级";
        if ("四川山鹧鸪".equals(bird) || bird.startsWith("四川山鹧鸪 (")) return "Ⅰ级";
        if ("红喉山鹧鸪".equals(bird) || bird.startsWith("红喉山鹧鸪 (")) return "Ⅱ级";
        if ("白眉山鹧鸪".equals(bird) || bird.startsWith("白眉山鹧鸪 (")) return "Ⅱ级";
        if ("白颊山鹧鸪".equals(bird) || bird.startsWith("白颊山鹧鸪 (")) return "Ⅱ级";
        if ("褐胸山鹧鸪".equals(bird) || bird.startsWith("褐胸山鹧鸪 (")) return "Ⅱ级";
        if ("红胸山鹧鸪".equals(bird) || bird.startsWith("红胸山鹧鸪 (")) return "Ⅱ级";
        if ("台湾山鹧鸪".equals(bird) || bird.startsWith("台湾山鹧鸪 (")) return "Ⅱ级";
        if ("海南山鹧鸪".equals(bird) || bird.startsWith("海南山鹧鸪 (")) return "Ⅰ级";
        if ("绿脚树鹧鸪".equals(bird) || bird.startsWith("绿脚树鹧鸪 (")) return "Ⅱ级";
        if ("花尾榛鸡".equals(bird) || bird.startsWith("花尾榛鸡 (")) return "Ⅱ级";
        if ("斑尾榛鸡".equals(bird) || bird.startsWith("斑尾榛鸡 (")) return "Ⅰ级";
        if ("镰翅鸡".equals(bird) || bird.startsWith("镰翅鸡 (")) return "Ⅱ级";
        if ("松鸡".equals(bird) || bird.startsWith("松鸡 (")) return "Ⅱ级";
        if ("黑嘴松鸡 (原名“细嘴松鸡”)".equals(bird) || bird.startsWith("黑嘴松鸡 (原名“细嘴松鸡”) (")) return "Ⅰ级";
        if ("黑琴鸡".equals(bird) || bird.startsWith("黑琴鸡 (")) return "Ⅰ级";
        if ("岩雷鸟".equals(bird) || bird.startsWith("岩雷鸟 (")) return "Ⅱ级";
        if ("柳雷鸟".equals(bird) || bird.startsWith("柳雷鸟 (")) return "Ⅱ级";
        if ("红喉雉鹑".equals(bird) || bird.startsWith("红喉雉鹑 (")) return "Ⅰ级";
        if ("黄喉雉鹑".equals(bird) || bird.startsWith("黄喉雉鹑 (")) return "Ⅰ级";
        if ("暗腹雪鸡".equals(bird) || bird.startsWith("暗腹雪鸡 (")) return "Ⅱ级";
        if ("藏雪鸡".equals(bird) || bird.startsWith("藏雪鸡 (")) return "Ⅱ级";
        if ("阿尔泰雪鸡".equals(bird) || bird.startsWith("阿尔泰雪鸡 (")) return "Ⅱ级";
        if ("大石鸡".equals(bird) || bird.startsWith("大石鸡 (")) return "Ⅱ级";
        if ("血雉".equals(bird) || bird.startsWith("血雉 (")) return "Ⅱ级";
        if ("黑头角雉".equals(bird) || bird.startsWith("黑头角雉 (")) return "Ⅰ级";
        if ("红胸角雉".equals(bird) || bird.startsWith("红胸角雉 (")) return "Ⅰ级";
        if ("灰腹角雉".equals(bird) || bird.startsWith("灰腹角雉 (")) return "Ⅰ级";
        if ("红腹角雉".equals(bird) || bird.startsWith("红腹角雉 (")) return "Ⅱ级";
        if ("黄腹角雉".equals(bird) || bird.startsWith("黄腹角雉 (")) return "Ⅰ级";
        if ("勺鸡".equals(bird) || bird.startsWith("勺鸡 (")) return "Ⅱ级";
        if ("棕尾虹雉".equals(bird) || bird.startsWith("棕尾虹雉 (")) return "Ⅰ级";
        if ("白尾梢虹雉".equals(bird) || bird.startsWith("白尾梢虹雉 (")) return "Ⅰ级";
        if ("绿尾虹雉".equals(bird) || bird.startsWith("绿尾虹雉 (")) return "Ⅰ级";
        if ("红原鸡 (原名“原鸡”)".equals(bird) || bird.startsWith("红原鸡 (原名“原鸡”) (")) return "Ⅱ级";
        if ("黑鹇".equals(bird) || bird.startsWith("黑鹇 (")) return "Ⅱ级";
        if ("白鹇".equals(bird) || bird.startsWith("白鹇 (")) return "Ⅱ级";
        if ("蓝腹鹇 (原名“蓝鹇”)".equals(bird) || bird.startsWith("蓝腹鹇 (原名“蓝鹇”) (")) return "Ⅰ级";
        if ("白马鸡".equals(bird) || bird.startsWith("白马鸡 (")) return "Ⅱ级";
        if ("藏马鸡".equals(bird) || bird.startsWith("藏马鸡 (")) return "Ⅱ级";
        if ("褐马鸡".equals(bird) || bird.startsWith("褐马鸡 (")) return "Ⅰ级";
        if ("蓝马鸡".equals(bird) || bird.startsWith("蓝马鸡 (")) return "Ⅱ级";
        if ("白颈长尾雉".equals(bird) || bird.startsWith("白颈长尾雉 (")) return "Ⅰ级";
        if ("黑颈长尾雉".equals(bird) || bird.startsWith("黑颈长尾雉 (")) return "Ⅰ级";
        if ("黑长尾雉".equals(bird) || bird.startsWith("黑长尾雉 (")) return "Ⅰ级";
        if ("白冠长尾雉".equals(bird) || bird.startsWith("白冠长尾雉 (")) return "Ⅰ级";
        if ("红腹锦鸡".equals(bird) || bird.startsWith("红腹锦鸡 (")) return "Ⅱ级";
        if ("白腹锦鸡".equals(bird) || bird.startsWith("白腹锦鸡 (")) return "Ⅱ级";
        if ("灰孔雀雉".equals(bird) || bird.startsWith("灰孔雀雉 (")) return "Ⅰ级";
        if ("海南孔雀雉".equals(bird) || bird.startsWith("海南孔雀雉 (")) return "Ⅰ级";
        if ("绿孔雀".equals(bird) || bird.startsWith("绿孔雀 (")) return "Ⅰ级";
        if ("栗树鸭".equals(bird) || bird.startsWith("栗树鸭 (")) return "Ⅱ级";
        if ("鸿雁".equals(bird) || bird.startsWith("鸿雁 (")) return "Ⅱ级";
        if ("白额雁".equals(bird) || bird.startsWith("白额雁 (")) return "Ⅱ级";
        if ("小白额雁".equals(bird) || bird.startsWith("小白额雁 (")) return "Ⅱ级";
        if ("红胸黑雁".equals(bird) || bird.startsWith("红胸黑雁 (")) return "Ⅱ级";
        if ("疣鼻天鹅".equals(bird) || bird.startsWith("疣鼻天鹅 (")) return "Ⅱ级";
        if ("小天鹅".equals(bird) || bird.startsWith("小天鹅 (")) return "Ⅱ级";
        if ("大天鹅".equals(bird) || bird.startsWith("大天鹅 (")) return "Ⅱ级";
        if ("鸳鸯".equals(bird) || bird.startsWith("鸳鸯 (")) return "Ⅱ级";
        if ("棉凫".equals(bird) || bird.startsWith("棉凫 (")) return "Ⅱ级";
        if ("花脸鸭".equals(bird) || bird.startsWith("花脸鸭 (")) return "Ⅱ级";
        if ("云石斑鸭".equals(bird) || bird.startsWith("云石斑鸭 (")) return "Ⅱ级";
        if ("青头潜鸭".equals(bird) || bird.startsWith("青头潜鸭 (")) return "Ⅰ级";
        if ("斑头秋沙鸭".equals(bird) || bird.startsWith("斑头秋沙鸭 (")) return "Ⅱ级";
        if ("中华秋沙鸭".equals(bird) || bird.startsWith("中华秋沙鸭 (")) return "Ⅰ级";
        if ("白头硬尾鸭".equals(bird) || bird.startsWith("白头硬尾鸭 (")) return "Ⅰ级";
        if ("白翅栖鸭".equals(bird) || bird.startsWith("白翅栖鸭 (")) return "Ⅱ级";
        if ("赤颈䴙䴘".equals(bird) || bird.startsWith("赤颈䴙䴘 (")) return "Ⅱ级";
        if ("角䴙䴘".equals(bird) || bird.startsWith("角䴙䴘 (")) return "Ⅱ级";
        if ("黑颈䴙䴘".equals(bird) || bird.startsWith("黑颈䴙䴘 (")) return "Ⅱ级";
        if ("中亚鸽".equals(bird) || bird.startsWith("中亚鸽 (")) return "Ⅱ级";
        if ("斑尾林鸽".equals(bird) || bird.startsWith("斑尾林鸽 (")) return "Ⅱ级";
        if ("紫林鸽".equals(bird) || bird.startsWith("紫林鸽 (")) return "Ⅱ级";
        if ("斑尾鹃鸠".equals(bird) || bird.startsWith("斑尾鹃鸠 (")) return "Ⅱ级";
        if ("菲律宾鹃鸠".equals(bird) || bird.startsWith("菲律宾鹃鸠 (")) return "Ⅱ级";
        if ("小鹃鸠 (原名“棕头鹃鸠”)".equals(bird) || bird.startsWith("小鹃鸠 (原名“棕头鹃鸠”) (")) return "Ⅰ级";
        if ("橙胸绿鸠".equals(bird) || bird.startsWith("橙胸绿鸠 (")) return "Ⅱ级";
        if ("灰头绿鸠".equals(bird) || bird.startsWith("灰头绿鸠 (")) return "Ⅱ级";
        if ("厚嘴绿鸠".equals(bird) || bird.startsWith("厚嘴绿鸠 (")) return "Ⅱ级";
        if ("黄脚绿鸠".equals(bird) || bird.startsWith("黄脚绿鸠 (")) return "Ⅱ级";
        if ("针尾绿鸠".equals(bird) || bird.startsWith("针尾绿鸠 (")) return "Ⅱ级";
        if ("楔尾绿鸠".equals(bird) || bird.startsWith("楔尾绿鸠 (")) return "Ⅱ级";
        if ("红翅绿鸠".equals(bird) || bird.startsWith("红翅绿鸠 (")) return "Ⅱ级";
        if ("红顶绿鸠".equals(bird) || bird.startsWith("红顶绿鸠 (")) return "Ⅱ级";
        if ("黑颏果鸠".equals(bird) || bird.startsWith("黑颏果鸠 (")) return "Ⅱ级";
        if ("绿皇鸠".equals(bird) || bird.startsWith("绿皇鸠 (")) return "Ⅱ级";
        if ("山皇鸠".equals(bird) || bird.startsWith("山皇鸠 (")) return "Ⅱ级";
        if ("黑腹沙鸡".equals(bird) || bird.startsWith("黑腹沙鸡 (")) return "Ⅱ级";
        if ("黑顶蛙口夜鹰".equals(bird) || bird.startsWith("黑顶蛙口夜鹰 (")) return "Ⅱ级";
        if ("凤头雨燕".equals(bird) || bird.startsWith("凤头雨燕 (")) return "Ⅱ级";
        if ("爪哇金丝燕".equals(bird) || bird.startsWith("爪哇金丝燕 (")) return "Ⅱ级";
        if ("灰喉针尾雨燕".equals(bird) || bird.startsWith("灰喉针尾雨燕 (")) return "Ⅱ级";
        if ("褐翅鸦鹃".equals(bird) || bird.startsWith("褐翅鸦鹃 (")) return "Ⅱ级";
        if ("小鸦鹃".equals(bird) || bird.startsWith("小鸦鹃 (")) return "Ⅱ级";
        if ("大鸨".equals(bird) || bird.startsWith("大鸨 (")) return "Ⅰ级";
        if ("波斑鸨".equals(bird) || bird.startsWith("波斑鸨 (")) return "Ⅰ级";
        if ("小鸨".equals(bird) || bird.startsWith("小鸨 (")) return "Ⅰ级";
        if ("花田鸡".equals(bird) || bird.startsWith("花田鸡 (")) return "Ⅱ级";
        if ("长脚秧鸡".equals(bird) || bird.startsWith("长脚秧鸡 (")) return "Ⅱ级";
        if ("棕背田鸡".equals(bird) || bird.startsWith("棕背田鸡 (")) return "Ⅱ级";
        if ("姬田鸡".equals(bird) || bird.startsWith("姬田鸡 (")) return "Ⅱ级";
        if ("斑胁田鸡".equals(bird) || bird.startsWith("斑胁田鸡 (")) return "Ⅱ级";
        if ("紫水鸡".equals(bird) || bird.startsWith("紫水鸡 (")) return "Ⅱ级";
        if ("白鹤".equals(bird) || bird.startsWith("白鹤 (")) return "Ⅰ级";
        if ("沙丘鹤".equals(bird) || bird.startsWith("沙丘鹤 (")) return "Ⅱ级";
        if ("白枕鹤".equals(bird) || bird.startsWith("白枕鹤 (")) return "Ⅰ级";
        if ("赤颈鹤".equals(bird) || bird.startsWith("赤颈鹤 (")) return "Ⅰ级";
        if ("蓑羽鹤".equals(bird) || bird.startsWith("蓑羽鹤 (")) return "Ⅱ级";
        if ("丹顶鹤".equals(bird) || bird.startsWith("丹顶鹤 (")) return "Ⅰ级";
        if ("灰鹤".equals(bird) || bird.startsWith("灰鹤 (")) return "Ⅱ级";
        if ("白头鹤".equals(bird) || bird.startsWith("白头鹤 (")) return "Ⅰ级";
        if ("黑颈鹤".equals(bird) || bird.startsWith("黑颈鹤 (")) return "Ⅰ级";
        if ("大石鸻".equals(bird) || bird.startsWith("大石鸻 (")) return "Ⅱ级";
        if ("鹮嘴鹬".equals(bird) || bird.startsWith("鹮嘴鹬 (")) return "Ⅱ级";
        if ("黄颊麦鸡".equals(bird) || bird.startsWith("黄颊麦鸡 (")) return "Ⅱ级";
        if ("水雉".equals(bird) || bird.startsWith("水雉 (")) return "Ⅱ级";
        if ("铜翅水雉".equals(bird) || bird.startsWith("铜翅水雉 (")) return "Ⅱ级";
        if ("林沙锥".equals(bird) || bird.startsWith("林沙锥 (")) return "Ⅱ级";
        if ("半蹼鹬".equals(bird) || bird.startsWith("半蹼鹬 (")) return "Ⅱ级";
        if ("小杓鹬".equals(bird) || bird.startsWith("小杓鹬 (")) return "Ⅱ级";
        if ("白腰杓鹬".equals(bird) || bird.startsWith("白腰杓鹬 (")) return "Ⅱ级";
        if ("大杓鹬".equals(bird) || bird.startsWith("大杓鹬 (")) return "Ⅱ级";
        if ("小青脚鹬".equals(bird) || bird.startsWith("小青脚鹬 (")) return "Ⅰ级";
        if ("翻石鹬".equals(bird) || bird.startsWith("翻石鹬 (")) return "Ⅱ级";
        if ("大滨鹬".equals(bird) || bird.startsWith("大滨鹬 (")) return "Ⅱ级";
        if ("勺嘴鹬".equals(bird) || bird.startsWith("勺嘴鹬 (")) return "Ⅰ级";
        if ("阔嘴鹬".equals(bird) || bird.startsWith("阔嘴鹬 (")) return "Ⅱ级";
        if ("灰燕鸻".equals(bird) || bird.startsWith("灰燕鸻 (")) return "Ⅱ级";
        if ("黑嘴鸥".equals(bird) || bird.startsWith("黑嘴鸥 (")) return "Ⅰ级";
        if ("小鸥".equals(bird) || bird.startsWith("小鸥 (")) return "Ⅱ级";
        if ("遗鸥".equals(bird) || bird.startsWith("遗鸥 (")) return "Ⅰ级";
        if ("大凤头燕鸥".equals(bird) || bird.startsWith("大凤头燕鸥 (")) return "Ⅱ级";
        if ("中华凤头燕鸥 (原名“黑嘴端凤头燕鸥”)".equals(bird) || bird.startsWith("中华凤头燕鸥 (原名“黑嘴端凤头燕鸥”) (")) return "Ⅰ级";
        if ("河燕鸥 (原名“黄嘴河燕鸥”)".equals(bird) || bird.startsWith("河燕鸥 (原名“黄嘴河燕鸥”) (")) return "Ⅰ级";
        if ("黑腹燕鸥".equals(bird) || bird.startsWith("黑腹燕鸥 (")) return "Ⅱ级";
        if ("黑浮鸥".equals(bird) || bird.startsWith("黑浮鸥 (")) return "Ⅱ级";
        if ("冠海雀".equals(bird) || bird.startsWith("冠海雀 (")) return "Ⅱ级";
        if ("黑脚信天翁".equals(bird) || bird.startsWith("黑脚信天翁 (")) return "Ⅰ级";
        if ("短尾信天翁".equals(bird) || bird.startsWith("短尾信天翁 (")) return "Ⅰ级";
        if ("彩鹳".equals(bird) || bird.startsWith("彩鹳 (")) return "Ⅰ级";
        if ("黑鹳".equals(bird) || bird.startsWith("黑鹳 (")) return "Ⅰ级";
        if ("白鹳".equals(bird) || bird.startsWith("白鹳 (")) return "Ⅰ级";
        if ("东方白鹳".equals(bird) || bird.startsWith("东方白鹳 (")) return "Ⅰ级";
        if ("秃鹳".equals(bird) || bird.startsWith("秃鹳 (")) return "Ⅱ级";
        if ("白腹军舰鸟".equals(bird) || bird.startsWith("白腹军舰鸟 (")) return "Ⅰ级";
        if ("黑腹军舰鸟".equals(bird) || bird.startsWith("黑腹军舰鸟 (")) return "Ⅱ级";
        if ("白斑军舰鸟".equals(bird) || bird.startsWith("白斑军舰鸟 (")) return "Ⅱ级";
        if ("蓝脸鲣鸟".equals(bird) || bird.startsWith("蓝脸鲣鸟 (")) return "Ⅱ级";
        if ("红脚鲣鸟".equals(bird) || bird.startsWith("红脚鲣鸟 (")) return "Ⅱ级";
        if ("褐鲣鸟".equals(bird) || bird.startsWith("褐鲣鸟 (")) return "Ⅱ级";
        if ("黑颈鸬鹚".equals(bird) || bird.startsWith("黑颈鸬鹚 (")) return "Ⅱ级";
        if ("海鸬鹚".equals(bird) || bird.startsWith("海鸬鹚 (")) return "Ⅱ级";
        if ("黑头白鹮 (原名“白鹮”)".equals(bird) || bird.startsWith("黑头白鹮 (原名“白鹮”) (")) return "Ⅰ级";
        if ("白肩黑鹮 (原名“黑鹮”)".equals(bird) || bird.startsWith("白肩黑鹮 (原名“黑鹮”) (")) return "Ⅰ级";
        if ("朱鹮".equals(bird) || bird.startsWith("朱鹮 (")) return "Ⅰ级";
        if ("彩鹮".equals(bird) || bird.startsWith("彩鹮 (")) return "Ⅰ级";
        if ("白琵鹭".equals(bird) || bird.startsWith("白琵鹭 (")) return "Ⅱ级";
        if ("黑脸琵鹭".equals(bird) || bird.startsWith("黑脸琵鹭 (")) return "Ⅰ级";
        if ("小苇鳽".equals(bird) || bird.startsWith("小苇鳽 (")) return "Ⅱ级";
        if ("海南鳽 (原名“海南虎斑鳽”)".equals(bird) || bird.startsWith("海南鳽 (原名“海南虎斑鳽”) (")) return "Ⅰ级";
        if ("栗头鳽".equals(bird) || bird.startsWith("栗头鳽 (")) return "Ⅱ级";
        if ("黑冠鳽".equals(bird) || bird.startsWith("黑冠鳽 (")) return "Ⅱ级";
        if ("白腹鹭".equals(bird) || bird.startsWith("白腹鹭 (")) return "Ⅰ级";
        if ("岩鹭".equals(bird) || bird.startsWith("岩鹭 (")) return "Ⅱ级";
        if ("黄嘴白鹭".equals(bird) || bird.startsWith("黄嘴白鹭 (")) return "Ⅰ级";
        if ("白鹈鹕".equals(bird) || bird.startsWith("白鹈鹕 (")) return "Ⅰ级";
        if ("斑嘴鹈鹕".equals(bird) || bird.startsWith("斑嘴鹈鹕 (")) return "Ⅰ级";
        if ("卷羽鹈鹕".equals(bird) || bird.startsWith("卷羽鹈鹕 (")) return "Ⅰ级";
        if ("鹗".equals(bird) || bird.startsWith("鹗 (")) return "Ⅱ级";
        if ("黑翅鸢".equals(bird) || bird.startsWith("黑翅鸢 (")) return "Ⅱ级";
        if ("胡兀鹫".equals(bird) || bird.startsWith("胡兀鹫 (")) return "Ⅰ级";
        if ("白兀鹫".equals(bird) || bird.startsWith("白兀鹫 (")) return "Ⅱ级";
        if ("鹃头蜂鹰".equals(bird) || bird.startsWith("鹃头蜂鹰 (")) return "Ⅱ级";
        if ("凤头蜂鹰".equals(bird) || bird.startsWith("凤头蜂鹰 (")) return "Ⅱ级";
        if ("褐冠鹃隼".equals(bird) || bird.startsWith("褐冠鹃隼 (")) return "Ⅱ级";
        if ("黑冠鹃隼".equals(bird) || bird.startsWith("黑冠鹃隼 (")) return "Ⅱ级";
        if ("兀鹫".equals(bird) || bird.startsWith("兀鹫 (")) return "Ⅱ级";
        if ("长嘴兀鹫".equals(bird) || bird.startsWith("长嘴兀鹫 (")) return "Ⅱ级";
        if ("白背兀鹫 (原名“拟兀鹫”)".equals(bird) || bird.startsWith("白背兀鹫 (原名“拟兀鹫”) (")) return "Ⅰ级";
        if ("高山兀鹫".equals(bird) || bird.startsWith("高山兀鹫 (")) return "Ⅱ级";
        if ("黑兀鹫".equals(bird) || bird.startsWith("黑兀鹫 (")) return "Ⅰ级";
        if ("秃鹫".equals(bird) || bird.startsWith("秃鹫 (")) return "Ⅰ级";
        if ("蛇雕".equals(bird) || bird.startsWith("蛇雕 (")) return "Ⅱ级";
        if ("短趾雕".equals(bird) || bird.startsWith("短趾雕 (")) return "Ⅱ级";
        if ("凤头鹰雕".equals(bird) || bird.startsWith("凤头鹰雕 (")) return "Ⅱ级";
        if ("鹰雕".equals(bird) || bird.startsWith("鹰雕 (")) return "Ⅱ级";
        if ("棕腹隼雕".equals(bird) || bird.startsWith("棕腹隼雕 (")) return "Ⅱ级";
        if ("林雕".equals(bird) || bird.startsWith("林雕 (")) return "Ⅱ级";
        if ("乌雕".equals(bird) || bird.startsWith("乌雕 (")) return "Ⅰ级";
        if ("靴隼雕".equals(bird) || bird.startsWith("靴隼雕 (")) return "Ⅱ级";
        if ("草原雕".equals(bird) || bird.startsWith("草原雕 (")) return "Ⅰ级";
        if ("白肩雕".equals(bird) || bird.startsWith("白肩雕 (")) return "Ⅰ级";
        if ("金雕".equals(bird) || bird.startsWith("金雕 (")) return "Ⅰ级";
        if ("白腹隼雕".equals(bird) || bird.startsWith("白腹隼雕 (")) return "Ⅱ级";
        if ("凤头鹰".equals(bird) || bird.startsWith("凤头鹰 (")) return "Ⅱ级";
        if ("褐耳鹰".equals(bird) || bird.startsWith("褐耳鹰 (")) return "Ⅱ级";
        if ("赤腹鹰".equals(bird) || bird.startsWith("赤腹鹰 (")) return "Ⅱ级";
        if ("日本松雀鹰".equals(bird) || bird.startsWith("日本松雀鹰 (")) return "Ⅱ级";
        if ("松雀鹰".equals(bird) || bird.startsWith("松雀鹰 (")) return "Ⅱ级";
        if ("雀鹰".equals(bird) || bird.startsWith("雀鹰 (")) return "Ⅱ级";
        if ("苍鹰".equals(bird) || bird.startsWith("苍鹰 (")) return "Ⅱ级";
        if ("白头鹞".equals(bird) || bird.startsWith("白头鹞 (")) return "Ⅱ级";
        if ("白腹鹞".equals(bird) || bird.startsWith("白腹鹞 (")) return "Ⅱ级";
        if ("白尾鹞".equals(bird) || bird.startsWith("白尾鹞 (")) return "Ⅱ级";
        if ("草原鹞".equals(bird) || bird.startsWith("草原鹞 (")) return "Ⅱ级";
        if ("鹊鹞".equals(bird) || bird.startsWith("鹊鹞 (")) return "Ⅱ级";
        if ("乌灰鹞".equals(bird) || bird.startsWith("乌灰鹞 (")) return "Ⅱ级";
        if ("黑鸢".equals(bird) || bird.startsWith("黑鸢 (")) return "Ⅱ级";
        if ("栗鸢".equals(bird) || bird.startsWith("栗鸢 (")) return "Ⅱ级";
        if ("白腹海雕".equals(bird) || bird.startsWith("白腹海雕 (")) return "Ⅰ级";
        if ("玉带海雕".equals(bird) || bird.startsWith("玉带海雕 (")) return "Ⅰ级";
        if ("白尾海雕".equals(bird) || bird.startsWith("白尾海雕 (")) return "Ⅰ级";
        if ("虎头海雕".equals(bird) || bird.startsWith("虎头海雕 (")) return "Ⅰ级";
        if ("渔雕".equals(bird) || bird.startsWith("渔雕 (")) return "Ⅱ级";
        if ("白眼鵟鹰".equals(bird) || bird.startsWith("白眼鵟鹰 (")) return "Ⅱ级";
        if ("棕翅鵟鹰".equals(bird) || bird.startsWith("棕翅鵟鹰 (")) return "Ⅱ级";
        if ("灰脸鵟鹰".equals(bird) || bird.startsWith("灰脸鵟鹰 (")) return "Ⅱ级";
        if ("毛脚鵟".equals(bird) || bird.startsWith("毛脚鵟 (")) return "Ⅱ级";
        if ("大鵟".equals(bird) || bird.startsWith("大鵟 (")) return "Ⅱ级";
        if ("普通鵟".equals(bird) || bird.startsWith("普通鵟 (")) return "Ⅱ级";
        if ("喜山鵟".equals(bird) || bird.startsWith("喜山鵟 (")) return "Ⅱ级";
        if ("欧亚鵟".equals(bird) || bird.startsWith("欧亚鵟 (")) return "Ⅱ级";
        if ("棕尾鵟".equals(bird) || bird.startsWith("棕尾鵟 (")) return "Ⅱ级";
        if ("黄嘴角鸮".equals(bird) || bird.startsWith("黄嘴角鸮 (")) return "Ⅱ级";
        if ("领角鸮".equals(bird) || bird.startsWith("领角鸮 (")) return "Ⅱ级";
        if ("北领角鸮".equals(bird) || bird.startsWith("北领角鸮 (")) return "Ⅱ级";
        if ("纵纹角鸮".equals(bird) || bird.startsWith("纵纹角鸮 (")) return "Ⅱ级";
        if ("西红角鸮".equals(bird) || bird.startsWith("西红角鸮 (")) return "Ⅱ级";
        if ("红角鸮".equals(bird) || bird.startsWith("红角鸮 (")) return "Ⅱ级";
        if ("优雅角鸮".equals(bird) || bird.startsWith("优雅角鸮 (")) return "Ⅱ级";
        if ("雪鸮".equals(bird) || bird.startsWith("雪鸮 (")) return "Ⅱ级";
        if ("雕鸮".equals(bird) || bird.startsWith("雕鸮 (")) return "Ⅱ级";
        if ("林雕鸮".equals(bird) || bird.startsWith("林雕鸮 (")) return "Ⅱ级";
        if ("毛腿雕鸮".equals(bird) || bird.startsWith("毛腿雕鸮 (")) return "Ⅰ级";
        if ("褐渔鸮".equals(bird) || bird.startsWith("褐渔鸮 (")) return "Ⅱ级";
        if ("黄腿渔鸮".equals(bird) || bird.startsWith("黄腿渔鸮 (")) return "Ⅱ级";
        if ("褐林鸮".equals(bird) || bird.startsWith("褐林鸮 (")) return "Ⅱ级";
        if ("灰林鸮".equals(bird) || bird.startsWith("灰林鸮 (")) return "Ⅱ级";
        if ("长尾林鸮".equals(bird) || bird.startsWith("长尾林鸮 (")) return "Ⅱ级";
        if ("四川林鸮".equals(bird) || bird.startsWith("四川林鸮 (")) return "Ⅰ级";
        if ("乌林鸮".equals(bird) || bird.startsWith("乌林鸮 (")) return "Ⅱ级";
        if ("猛鸮".equals(bird) || bird.startsWith("猛鸮 (")) return "Ⅱ级";
        if ("花头鸺鹠".equals(bird) || bird.startsWith("花头鸺鹠 (")) return "Ⅱ级";
        if ("领鸺鹠".equals(bird) || bird.startsWith("领鸺鹠 (")) return "Ⅱ级";
        if ("斑头鸺鹠".equals(bird) || bird.startsWith("斑头鸺鹠 (")) return "Ⅱ级";
        if ("纵纹腹小鸮".equals(bird) || bird.startsWith("纵纹腹小鸮 (")) return "Ⅱ级";
        if ("横斑腹小鸮".equals(bird) || bird.startsWith("横斑腹小鸮 (")) return "Ⅱ级";
        if ("鬼鸮".equals(bird) || bird.startsWith("鬼鸮 (")) return "Ⅱ级";
        if ("鹰鸮".equals(bird) || bird.startsWith("鹰鸮 (")) return "Ⅱ级";
        if ("日本鹰鸮".equals(bird) || bird.startsWith("日本鹰鸮 (")) return "Ⅱ级";
        if ("长耳鸮".equals(bird) || bird.startsWith("长耳鸮 (")) return "Ⅱ级";
        if ("短耳鸮".equals(bird) || bird.startsWith("短耳鸮 (")) return "Ⅱ级";
        if ("仓鸮".equals(bird) || bird.startsWith("仓鸮 (")) return "Ⅱ级";
        if ("草鸮".equals(bird) || bird.startsWith("草鸮 (")) return "Ⅱ级";
        if ("栗鸮".equals(bird) || bird.startsWith("栗鸮 (")) return "Ⅱ级";
        if ("橙胸咬鹃".equals(bird) || bird.startsWith("橙胸咬鹃 (")) return "Ⅱ级";
        if ("红头咬鹃".equals(bird) || bird.startsWith("红头咬鹃 (")) return "Ⅱ级";
        if ("红腹咬鹃".equals(bird) || bird.startsWith("红腹咬鹃 (")) return "Ⅱ级";
        if ("白喉犀鸟".equals(bird) || bird.startsWith("白喉犀鸟 (")) return "Ⅰ级";
        if ("冠斑犀鸟".equals(bird) || bird.startsWith("冠斑犀鸟 (")) return "Ⅰ级";
        if ("双角犀鸟".equals(bird) || bird.startsWith("双角犀鸟 (")) return "Ⅰ级";
        if ("棕颈犀鸟".equals(bird) || bird.startsWith("棕颈犀鸟 (")) return "Ⅰ级";
        if ("花冠皱盔犀鸟".equals(bird) || bird.startsWith("花冠皱盔犀鸟 (")) return "Ⅰ级";
        if ("赤须蜂虎".equals(bird) || bird.startsWith("赤须蜂虎 (")) return "Ⅱ级";
        if ("蓝须蜂虎".equals(bird) || bird.startsWith("蓝须蜂虎 (")) return "Ⅱ级";
        if ("绿喉蜂虎".equals(bird) || bird.startsWith("绿喉蜂虎 (")) return "Ⅱ级";
        if ("蓝颊蜂虎".equals(bird) || bird.startsWith("蓝颊蜂虎 (")) return "Ⅱ级";
        if ("栗喉蜂虎".equals(bird) || bird.startsWith("栗喉蜂虎 (")) return "Ⅱ级";
        if ("彩虹蜂虎".equals(bird) || bird.startsWith("彩虹蜂虎 (")) return "Ⅱ级";
        if ("蓝喉蜂虎".equals(bird) || bird.startsWith("蓝喉蜂虎 (")) return "Ⅱ级";
        if ("栗头蜂虎 (原名“黑胸蜂虎”)".equals(bird) || bird.startsWith("栗头蜂虎 (原名“黑胸蜂虎”) (")) return "Ⅱ级";
        if ("鹳嘴翡翠 (原名“鹳嘴翠鸟”)".equals(bird) || bird.startsWith("鹳嘴翡翠 (原名“鹳嘴翠鸟”) (")) return "Ⅱ级";
        if ("白胸翡翠".equals(bird) || bird.startsWith("白胸翡翠 (")) return "Ⅱ级";
        if ("蓝耳翠鸟".equals(bird) || bird.startsWith("蓝耳翠鸟 (")) return "Ⅱ级";
        if ("斑头大翠鸟".equals(bird) || bird.startsWith("斑头大翠鸟 (")) return "Ⅱ级";
        if ("白翅啄木鸟".equals(bird) || bird.startsWith("白翅啄木鸟 (")) return "Ⅱ级";
        if ("三趾啄木鸟".equals(bird) || bird.startsWith("三趾啄木鸟 (")) return "Ⅱ级";
        if ("白腹黑啄木鸟".equals(bird) || bird.startsWith("白腹黑啄木鸟 (")) return "Ⅱ级";
        if ("黑啄木鸟".equals(bird) || bird.startsWith("黑啄木鸟 (")) return "Ⅱ级";
        if ("大黄冠啄木鸟".equals(bird) || bird.startsWith("大黄冠啄木鸟 (")) return "Ⅱ级";
        if ("黄冠啄木鸟".equals(bird) || bird.startsWith("黄冠啄木鸟 (")) return "Ⅱ级";
        if ("红颈绿啄木鸟".equals(bird) || bird.startsWith("红颈绿啄木鸟 (")) return "Ⅱ级";
        if ("大灰啄木鸟".equals(bird) || bird.startsWith("大灰啄木鸟 (")) return "Ⅱ级";
        if ("红腿小隼".equals(bird) || bird.startsWith("红腿小隼 (")) return "Ⅱ级";
        if ("白腿小隼".equals(bird) || bird.startsWith("白腿小隼 (")) return "Ⅱ级";
        if ("黄爪隼".equals(bird) || bird.startsWith("黄爪隼 (")) return "Ⅱ级";
        if ("红隼".equals(bird) || bird.startsWith("红隼 (")) return "Ⅱ级";
        if ("西红脚隼".equals(bird) || bird.startsWith("西红脚隼 (")) return "Ⅱ级";
        if ("红脚隼".equals(bird) || bird.startsWith("红脚隼 (")) return "Ⅱ级";
        if ("灰背隼".equals(bird) || bird.startsWith("灰背隼 (")) return "Ⅱ级";
        if ("燕隼".equals(bird) || bird.startsWith("燕隼 (")) return "Ⅱ级";
        if ("猛隼".equals(bird) || bird.startsWith("猛隼 (")) return "Ⅱ级";
        if ("猎隼".equals(bird) || bird.startsWith("猎隼 (")) return "Ⅰ级";
        if ("矛隼".equals(bird) || bird.startsWith("矛隼 (")) return "Ⅰ级";
        if ("游隼".equals(bird) || bird.startsWith("游隼 (")) return "Ⅱ级";
        if ("短尾鹦鹉".equals(bird) || bird.startsWith("短尾鹦鹉 (")) return "Ⅱ级";
        if ("蓝腰鹦鹉".equals(bird) || bird.startsWith("蓝腰鹦鹉 (")) return "Ⅱ级";
        if ("亚历山大鹦鹉".equals(bird) || bird.startsWith("亚历山大鹦鹉 (")) return "Ⅱ级";
        if ("红领绿鹦鹉".equals(bird) || bird.startsWith("红领绿鹦鹉 (")) return "Ⅱ级";
        if ("青头鹦鹉".equals(bird) || bird.startsWith("青头鹦鹉 (")) return "Ⅱ级";
        if ("灰头鹦鹉".equals(bird) || bird.startsWith("灰头鹦鹉 (")) return "Ⅱ级";
        if ("花头鹦鹉".equals(bird) || bird.startsWith("花头鹦鹉 (")) return "Ⅱ级";
        if ("大紫胸鹦鹉".equals(bird) || bird.startsWith("大紫胸鹦鹉 (")) return "Ⅱ级";
        if ("绯胸鹦鹉".equals(bird) || bird.startsWith("绯胸鹦鹉 (")) return "Ⅱ级";
        if ("双辫八色鸫".equals(bird) || bird.startsWith("双辫八色鸫 (")) return "Ⅱ级";
        if ("蓝枕八色鸫".equals(bird) || bird.startsWith("蓝枕八色鸫 (")) return "Ⅱ级";
        if ("蓝背八色鸫".equals(bird) || bird.startsWith("蓝背八色鸫 (")) return "Ⅱ级";
        if ("栗头八色鸫".equals(bird) || bird.startsWith("栗头八色鸫 (")) return "Ⅱ级";
        if ("蓝八色鸫".equals(bird) || bird.startsWith("蓝八色鸫 (")) return "Ⅱ级";
        if ("绿胸八色鸫".equals(bird) || bird.startsWith("绿胸八色鸫 (")) return "Ⅱ级";
        if ("仙八色鸫".equals(bird) || bird.startsWith("仙八色鸫 (")) return "Ⅱ级";
        if ("蓝翅八色鸫".equals(bird) || bird.startsWith("蓝翅八色鸫 (")) return "Ⅱ级";
        if ("长尾阔嘴鸟".equals(bird) || bird.startsWith("长尾阔嘴鸟 (")) return "Ⅱ级";
        if ("银胸丝冠鸟".equals(bird) || bird.startsWith("银胸丝冠鸟 (")) return "Ⅱ级";
        if ("鹊鹂".equals(bird) || bird.startsWith("鹊鹂 (")) return "Ⅱ级";
        if ("小盘尾".equals(bird) || bird.startsWith("小盘尾 (")) return "Ⅱ级";
        if ("大盘尾".equals(bird) || bird.startsWith("大盘尾 (")) return "Ⅱ级";
        if ("黑头噪鸦".equals(bird) || bird.startsWith("黑头噪鸦 (")) return "Ⅰ级";
        if ("蓝绿鹊".equals(bird) || bird.startsWith("蓝绿鹊 (")) return "Ⅱ级";
        if ("黄胸绿鹊".equals(bird) || bird.startsWith("黄胸绿鹊 (")) return "Ⅱ级";
        if ("黑尾地鸦".equals(bird) || bird.startsWith("黑尾地鸦 (")) return "Ⅱ级";
        if ("白尾地鸦".equals(bird) || bird.startsWith("白尾地鸦 (")) return "Ⅱ级";
        if ("白眉山雀".equals(bird) || bird.startsWith("白眉山雀 (")) return "Ⅱ级";
        if ("红腹山雀".equals(bird) || bird.startsWith("红腹山雀 (")) return "Ⅱ级";
        if ("歌百灵".equals(bird) || bird.startsWith("歌百灵 (")) return "Ⅱ级";
        if ("蒙古百灵".equals(bird) || bird.startsWith("蒙古百灵 (")) return "Ⅱ级";
        if ("云雀".equals(bird) || bird.startsWith("云雀 (")) return "Ⅱ级";
        if ("细纹苇莺".equals(bird) || bird.startsWith("细纹苇莺 (")) return "Ⅱ级";
        if ("台湾鹎".equals(bird) || bird.startsWith("台湾鹎 (")) return "Ⅱ级";
        if ("金胸雀鹛".equals(bird) || bird.startsWith("金胸雀鹛 (")) return "Ⅱ级";
        if ("宝兴鹛雀".equals(bird) || bird.startsWith("宝兴鹛雀 (")) return "Ⅱ级";
        if ("中华雀鹛".equals(bird) || bird.startsWith("中华雀鹛 (")) return "Ⅱ级";
        if ("三趾鸦雀".equals(bird) || bird.startsWith("三趾鸦雀 (")) return "Ⅱ级";
        if ("白眶鸦雀".equals(bird) || bird.startsWith("白眶鸦雀 (")) return "Ⅱ级";
        if ("暗色鸦雀".equals(bird) || bird.startsWith("暗色鸦雀 (")) return "Ⅱ级";
        if ("灰冠鸦雀".equals(bird) || bird.startsWith("灰冠鸦雀 (")) return "Ⅰ级";
        if ("短尾鸦雀".equals(bird) || bird.startsWith("短尾鸦雀 (")) return "Ⅱ级";
        if ("震旦鸦雀".equals(bird) || bird.startsWith("震旦鸦雀 (")) return "Ⅱ级";
        if ("红胁绣眼鸟".equals(bird) || bird.startsWith("红胁绣眼鸟 (")) return "Ⅱ级";
        if ("淡喉鹩鹛".equals(bird) || bird.startsWith("淡喉鹩鹛 (")) return "Ⅱ级";
        if ("弄岗穗鹛".equals(bird) || bird.startsWith("弄岗穗鹛 (")) return "Ⅱ级";
        if ("金额雀鹛".equals(bird) || bird.startsWith("金额雀鹛 (")) return "Ⅰ级";
        if ("大草鹛".equals(bird) || bird.startsWith("大草鹛 (")) return "Ⅱ级";
        if ("棕草鹛".equals(bird) || bird.startsWith("棕草鹛 (")) return "Ⅱ级";
        if ("画眉".equals(bird) || bird.startsWith("画眉 (")) return "Ⅱ级";
        if ("海南画眉".equals(bird) || bird.startsWith("海南画眉 (")) return "Ⅱ级";
        if ("台湾画眉".equals(bird) || bird.startsWith("台湾画眉 (")) return "Ⅱ级";
        if ("褐胸噪鹛".equals(bird) || bird.startsWith("褐胸噪鹛 (")) return "Ⅱ级";
        if ("黑额山噪鹛".equals(bird) || bird.startsWith("黑额山噪鹛 (")) return "Ⅰ级";
        if ("斑背噪鹛".equals(bird) || bird.startsWith("斑背噪鹛 (")) return "Ⅱ级";
        if ("白点噪鹛".equals(bird) || bird.startsWith("白点噪鹛 (")) return "Ⅰ级";
        if ("大噪鹛".equals(bird) || bird.startsWith("大噪鹛 (")) return "Ⅱ级";
        if ("眼纹噪鹛".equals(bird) || bird.startsWith("眼纹噪鹛 (")) return "Ⅱ级";
        if ("黑喉噪鹛".equals(bird) || bird.startsWith("黑喉噪鹛 (")) return "Ⅱ级";
        if ("蓝冠噪鹛".equals(bird) || bird.startsWith("蓝冠噪鹛 (")) return "Ⅰ级";
        if ("棕噪鹛".equals(bird) || bird.startsWith("棕噪鹛 (")) return "Ⅱ级";
        if ("橙翅噪鹛".equals(bird) || bird.startsWith("橙翅噪鹛 (")) return "Ⅱ级";
        if ("红翅噪鹛".equals(bird) || bird.startsWith("红翅噪鹛 (")) return "Ⅱ级";
        if ("红尾噪鹛".equals(bird) || bird.startsWith("红尾噪鹛 (")) return "Ⅱ级";
        if ("黑冠薮鹛".equals(bird) || bird.startsWith("黑冠薮鹛 (")) return "Ⅰ级";
        if ("灰胸薮鹛".equals(bird) || bird.startsWith("灰胸薮鹛 (")) return "Ⅰ级";
        if ("银耳相思鸟".equals(bird) || bird.startsWith("银耳相思鸟 (")) return "Ⅱ级";
        if ("红嘴相思鸟".equals(bird) || bird.startsWith("红嘴相思鸟 (")) return "Ⅱ级";
        if ("四川旋木雀".equals(bird) || bird.startsWith("四川旋木雀 (")) return "Ⅱ级";
        if ("滇䴓".equals(bird) || bird.startsWith("滇䴓 (")) return "Ⅱ级";
        if ("巨䴓".equals(bird) || bird.startsWith("巨䴓 (")) return "Ⅱ级";
        if ("丽䴓".equals(bird) || bird.startsWith("丽䴓 (")) return "Ⅱ级";
        if ("鹩哥".equals(bird) || bird.startsWith("鹩哥 (")) return "Ⅱ级";
        if ("褐头鸫".equals(bird) || bird.startsWith("褐头鸫 (")) return "Ⅱ级";
        if ("紫宽嘴鸫".equals(bird) || bird.startsWith("紫宽嘴鸫 (")) return "Ⅱ级";
        if ("绿宽嘴鸫".equals(bird) || bird.startsWith("绿宽嘴鸫 (")) return "Ⅱ级";
        if ("棕头歌鸲".equals(bird) || bird.startsWith("棕头歌鸲 (")) return "Ⅰ级";
        if ("红喉歌鸲".equals(bird) || bird.startsWith("红喉歌鸲 (")) return "Ⅱ级";
        if ("黑喉歌鸲".equals(bird) || bird.startsWith("黑喉歌鸲 (")) return "Ⅱ级";
        if ("金胸歌鸲".equals(bird) || bird.startsWith("金胸歌鸲 (")) return "Ⅱ级";
        if ("蓝喉歌鸲".equals(bird) || bird.startsWith("蓝喉歌鸲 (")) return "Ⅱ级";
        if ("新疆歌鸲".equals(bird) || bird.startsWith("新疆歌鸲 (")) return "Ⅱ级";
        if ("棕腹林鸲".equals(bird) || bird.startsWith("棕腹林鸲 (")) return "Ⅱ级";
        if ("贺兰山红尾鸲".equals(bird) || bird.startsWith("贺兰山红尾鸲 (")) return "Ⅱ级";
        if ("白喉石鵖".equals(bird) || bird.startsWith("白喉石鵖 (")) return "Ⅱ级";
        if ("白喉林鹟".equals(bird) || bird.startsWith("白喉林鹟 (")) return "Ⅱ级";
        if ("棕腹大仙鹟".equals(bird) || bird.startsWith("棕腹大仙鹟 (")) return "Ⅱ级";
        if ("大仙鹟".equals(bird) || bird.startsWith("大仙鹟 (")) return "Ⅱ级";
        if ("贺兰山岩鹨".equals(bird) || bird.startsWith("贺兰山岩鹨 (")) return "Ⅱ级";
        if ("朱鹀".equals(bird) || bird.startsWith("朱鹀 (")) return "Ⅱ级";
        if ("褐头朱雀".equals(bird) || bird.startsWith("褐头朱雀 (")) return "Ⅱ级";
        if ("藏雀".equals(bird) || bird.startsWith("藏雀 (")) return "Ⅱ级";
        if ("北朱雀".equals(bird) || bird.startsWith("北朱雀 (")) return "Ⅱ级";
        if ("红交嘴雀".equals(bird) || bird.startsWith("红交嘴雀 (")) return "Ⅱ级";
        if ("蓝鹀".equals(bird) || bird.startsWith("蓝鹀 (")) return "Ⅱ级";
        if ("栗斑腹鹀".equals(bird) || bird.startsWith("栗斑腹鹀 (")) return "Ⅰ级";
        if ("黄胸鹀".equals(bird) || bird.startsWith("黄胸鹀 (")) return "Ⅰ级";
        if ("藏鹀".equals(bird) || bird.startsWith("藏鹀 (")) return "Ⅱ级";
        if ("*平胸龟 (仅限野外种群)".equals(bird) || bird.startsWith("*平胸龟 (仅限野外种群) (")) return "Ⅱ级";
        if ("缅甸陆龟".equals(bird) || bird.startsWith("缅甸陆龟 (")) return "Ⅰ级";
        if ("凹甲陆龟".equals(bird) || bird.startsWith("凹甲陆龟 (")) return "Ⅰ级";
        if ("四爪陆龟".equals(bird) || bird.startsWith("四爪陆龟 (")) return "Ⅰ级";
        if ("*欧氏摄龟".equals(bird) || bird.startsWith("*欧氏摄龟 (")) return "Ⅱ级";
        if ("*黑颈乌龟 (仅限野外种群)".equals(bird) || bird.startsWith("*黑颈乌龟 (仅限野外种群) (")) return "Ⅱ级";
        if ("*乌龟 (仅限野外种群)".equals(bird) || bird.startsWith("*乌龟 (仅限野外种群) (")) return "Ⅱ级";
        if ("*花龟 (仅限野外种群)".equals(bird) || bird.startsWith("*花龟 (仅限野外种群) (")) return "Ⅱ级";
        if ("*黄喉拟水龟 (仅限野外种群)".equals(bird) || bird.startsWith("*黄喉拟水龟 (仅限野外种群) (")) return "Ⅱ级";
        if ("*闭壳龟属所有种 (仅限野外种群)".equals(bird) || bird.startsWith("*闭壳龟属所有种 (仅限野外种群) (")) return "Ⅱ级";
        if ("*地龟".equals(bird) || bird.startsWith("*地龟 (")) return "Ⅱ级";
        if ("*眼斑水龟 (仅限野外种群)".equals(bird) || bird.startsWith("*眼斑水龟 (仅限野外种群) (")) return "Ⅱ级";
        if ("*四眼斑水龟 (仅限野外种群)".equals(bird) || bird.startsWith("*四眼斑水龟 (仅限野外种群) (")) return "Ⅱ级";
        if ("*红海龟 (原名“蠵龟”)".equals(bird) || bird.startsWith("*红海龟 (原名“蠵龟”) (")) return "Ⅰ级";
        if ("*绿海龟".equals(bird) || bird.startsWith("*绿海龟 (")) return "Ⅰ级";
        if ("*玳瑁".equals(bird) || bird.startsWith("*玳瑁 (")) return "Ⅰ级";
        if ("*太平洋丽龟".equals(bird) || bird.startsWith("*太平洋丽龟 (")) return "Ⅰ级";
        if ("*棱皮龟".equals(bird) || bird.startsWith("*棱皮龟 (")) return "Ⅰ级";
        if ("*鼋".equals(bird) || bird.startsWith("*鼋 (")) return "Ⅰ级";
        if ("*山瑞鳖 (仅限野外种群)".equals(bird) || bird.startsWith("*山瑞鳖 (仅限野外种群) (")) return "Ⅱ级";
        if ("*斑鳖".equals(bird) || bird.startsWith("*斑鳖 (")) return "Ⅰ级";
        if ("大壁虎".equals(bird) || bird.startsWith("大壁虎 (")) return "Ⅱ级";
        if ("黑疣大壁虎".equals(bird) || bird.startsWith("黑疣大壁虎 (")) return "Ⅱ级";
        if ("伊犁沙虎".equals(bird) || bird.startsWith("伊犁沙虎 (")) return "Ⅱ级";
        if ("吐鲁番沙虎".equals(bird) || bird.startsWith("吐鲁番沙虎 (")) return "Ⅱ级";
        if ("英德睑虎".equals(bird) || bird.startsWith("英德睑虎 (")) return "Ⅱ级";
        if ("越南睑虎".equals(bird) || bird.startsWith("越南睑虎 (")) return "Ⅱ级";
        if ("霸王岭睑虎".equals(bird) || bird.startsWith("霸王岭睑虎 (")) return "Ⅱ级";
        if ("海南睑虎".equals(bird) || bird.startsWith("海南睑虎 (")) return "Ⅱ级";
        if ("嘉道理睑虎".equals(bird) || bird.startsWith("嘉道理睑虎 (")) return "Ⅱ级";
        if ("广西睑虎".equals(bird) || bird.startsWith("广西睑虎 (")) return "Ⅱ级";
        if ("荔波睑虎".equals(bird) || bird.startsWith("荔波睑虎 (")) return "Ⅱ级";
        if ("凭祥睑虎".equals(bird) || bird.startsWith("凭祥睑虎 (")) return "Ⅱ级";
        if ("蒲氏睑虎".equals(bird) || bird.startsWith("蒲氏睑虎 (")) return "Ⅱ级";
        if ("周氏睑虎".equals(bird) || bird.startsWith("周氏睑虎 (")) return "Ⅱ级";
        if ("巴塘龙蜥".equals(bird) || bird.startsWith("巴塘龙蜥 (")) return "Ⅱ级";
        if ("短尾龙蜥".equals(bird) || bird.startsWith("短尾龙蜥 (")) return "Ⅱ级";
        if ("侏龙蜥".equals(bird) || bird.startsWith("侏龙蜥 (")) return "Ⅱ级";
        if ("滑腹龙蜥".equals(bird) || bird.startsWith("滑腹龙蜥 (")) return "Ⅱ级";
        if ("宜兰龙蜥".equals(bird) || bird.startsWith("宜兰龙蜥 (")) return "Ⅱ级";
        if ("溪头龙蜥".equals(bird) || bird.startsWith("溪头龙蜥 (")) return "Ⅱ级";
        if ("帆背龙蜥".equals(bird) || bird.startsWith("帆背龙蜥 (")) return "Ⅱ级";
        if ("蜡皮蜥".equals(bird) || bird.startsWith("蜡皮蜥 (")) return "Ⅱ级";
        if ("贵南沙蜥".equals(bird) || bird.startsWith("贵南沙蜥 (")) return "Ⅱ级";
        if ("大耳沙蜥".equals(bird) || bird.startsWith("大耳沙蜥 (")) return "Ⅰ级";
        if ("长鬣蜥".equals(bird) || bird.startsWith("长鬣蜥 (")) return "Ⅱ级";
        if ("细脆蛇蜥".equals(bird) || bird.startsWith("细脆蛇蜥 (")) return "Ⅱ级";
        if ("海南脆蛇蜥".equals(bird) || bird.startsWith("海南脆蛇蜥 (")) return "Ⅱ级";
        if ("脆蛇蜥".equals(bird) || bird.startsWith("脆蛇蜥 (")) return "Ⅱ级";
        if ("鳄蜥".equals(bird) || bird.startsWith("鳄蜥 (")) return "Ⅰ级";
        if ("孟加拉巨蜥".equals(bird) || bird.startsWith("孟加拉巨蜥 (")) return "Ⅰ级";
        if ("圆鼻巨蜥 (原名“巨蜥”)".equals(bird) || bird.startsWith("圆鼻巨蜥 (原名“巨蜥”) (")) return "Ⅰ级";
        if ("桓仁滑蜥".equals(bird) || bird.startsWith("桓仁滑蜥 (")) return "Ⅱ级";
        if ("香港双足蜥".equals(bird) || bird.startsWith("香港双足蜥 (")) return "Ⅱ级";
        if ("香港盲蛇".equals(bird) || bird.startsWith("香港盲蛇 (")) return "Ⅱ级";
        if ("红尾筒蛇".equals(bird) || bird.startsWith("红尾筒蛇 (")) return "Ⅱ级";
        if ("闪鳞蛇".equals(bird) || bird.startsWith("闪鳞蛇 (")) return "Ⅱ级";
        if ("红沙蟒".equals(bird) || bird.startsWith("红沙蟒 (")) return "Ⅱ级";
        if ("东方沙蟒".equals(bird) || bird.startsWith("东方沙蟒 (")) return "Ⅱ级";
        if ("蟒蛇 (原名“蟒”)".equals(bird) || bird.startsWith("蟒蛇 (原名“蟒”) (")) return "Ⅱ级";
        if ("井冈山脊蛇".equals(bird) || bird.startsWith("井冈山脊蛇 (")) return "Ⅱ级";
        if ("三索蛇".equals(bird) || bird.startsWith("三索蛇 (")) return "Ⅱ级";
        if ("团花锦蛇".equals(bird) || bird.startsWith("团花锦蛇 (")) return "Ⅱ级";
        if ("横斑锦蛇".equals(bird) || bird.startsWith("横斑锦蛇 (")) return "Ⅱ级";
        if ("尖喙蛇".equals(bird) || bird.startsWith("尖喙蛇 (")) return "Ⅱ级";
        if ("西藏温泉蛇".equals(bird) || bird.startsWith("西藏温泉蛇 (")) return "Ⅰ级";
        if ("香格里拉温泉蛇".equals(bird) || bird.startsWith("香格里拉温泉蛇 (")) return "Ⅰ级";
        if ("四川温泉蛇".equals(bird) || bird.startsWith("四川温泉蛇 (")) return "Ⅰ级";
        if ("黑网乌梢蛇".equals(bird) || bird.startsWith("黑网乌梢蛇 (")) return "Ⅱ级";
        if ("*瘰鳞蛇".equals(bird) || bird.startsWith("*瘰鳞蛇 (")) return "Ⅱ级";
        if ("眼镜王蛇".equals(bird) || bird.startsWith("眼镜王蛇 (")) return "Ⅱ级";
        if ("*蓝灰扁尾海蛇".equals(bird) || bird.startsWith("*蓝灰扁尾海蛇 (")) return "Ⅱ级";
        if ("*扁尾海蛇".equals(bird) || bird.startsWith("*扁尾海蛇 (")) return "Ⅱ级";
        if ("*半环扁尾海蛇".equals(bird) || bird.startsWith("*半环扁尾海蛇 (")) return "Ⅱ级";
        if ("*龟头海蛇".equals(bird) || bird.startsWith("*龟头海蛇 (")) return "Ⅱ级";
        if ("*青环海蛇".equals(bird) || bird.startsWith("*青环海蛇 (")) return "Ⅱ级";
        if ("*环纹海蛇".equals(bird) || bird.startsWith("*环纹海蛇 (")) return "Ⅱ级";
        if ("*黑头海蛇".equals(bird) || bird.startsWith("*黑头海蛇 (")) return "Ⅱ级";
        if ("*淡灰海蛇".equals(bird) || bird.startsWith("*淡灰海蛇 (")) return "Ⅱ级";
        if ("*棘眦海蛇".equals(bird) || bird.startsWith("*棘眦海蛇 (")) return "Ⅱ级";
        if ("*棘鳞海蛇".equals(bird) || bird.startsWith("*棘鳞海蛇 (")) return "Ⅱ级";
        if ("*青灰海蛇".equals(bird) || bird.startsWith("*青灰海蛇 (")) return "Ⅱ级";
        if ("*平颏海蛇".equals(bird) || bird.startsWith("*平颏海蛇 (")) return "Ⅱ级";
        if ("*小头海蛇".equals(bird) || bird.startsWith("*小头海蛇 (")) return "Ⅱ级";
        if ("*长吻海蛇".equals(bird) || bird.startsWith("*长吻海蛇 (")) return "Ⅱ级";
        if ("*截吻海蛇".equals(bird) || bird.startsWith("*截吻海蛇 (")) return "Ⅱ级";
        if ("*海蝰".equals(bird) || bird.startsWith("*海蝰 (")) return "Ⅱ级";
        if ("泰国圆斑蝰".equals(bird) || bird.startsWith("泰国圆斑蝰 (")) return "Ⅱ级";
        if ("蛇岛蝮".equals(bird) || bird.startsWith("蛇岛蝮 (")) return "Ⅱ级";
        if ("角原矛头蝮".equals(bird) || bird.startsWith("角原矛头蝮 (")) return "Ⅱ级";
        if ("莽山烙铁头蛇".equals(bird) || bird.startsWith("莽山烙铁头蛇 (")) return "Ⅰ级";
        if ("极北蝰".equals(bird) || bird.startsWith("极北蝰 (")) return "Ⅱ级";
        if ("东方蝰".equals(bird) || bird.startsWith("东方蝰 (")) return "Ⅱ级";
        if ("*扬子鳄".equals(bird) || bird.startsWith("*扬子鳄 (")) return "Ⅰ级";
        if ("版纳鱼螈".equals(bird) || bird.startsWith("版纳鱼螈 (")) return "Ⅱ级";
        if ("*安吉小鲵".equals(bird) || bird.startsWith("*安吉小鲵 (")) return "Ⅰ级";
        if ("*中国小鲵".equals(bird) || bird.startsWith("*中国小鲵 (")) return "Ⅰ级";
        if ("*挂榜山小鲵".equals(bird) || bird.startsWith("*挂榜山小鲵 (")) return "Ⅰ级";
        if ("*猫儿山小鲵".equals(bird) || bird.startsWith("*猫儿山小鲵 (")) return "Ⅰ级";
        if ("*普雄原鲵".equals(bird) || bird.startsWith("*普雄原鲵 (")) return "Ⅰ级";
        if ("*辽宁爪鲵 (原名“爪鲵\")".equals(bird) || bird.startsWith("*辽宁爪鲵 (原名“爪鲵\") (")) return "Ⅰ级";
        if ("*吉林爪鲵".equals(bird) || bird.startsWith("*吉林爪鲵 (")) return "Ⅱ级";
        if ("*新疆北鲵".equals(bird) || bird.startsWith("*新疆北鲵 (")) return "Ⅱ级";
        if ("*极北鲵".equals(bird) || bird.startsWith("*极北鲵 (")) return "Ⅱ级";
        if ("*巫山巴鲵 (原名“巴鲵”)".equals(bird) || bird.startsWith("*巫山巴鲵 (原名“巴鲵”) (")) return "Ⅱ级";
        if ("*秦巴巴鲵 (原名“秦巴北鲵”)".equals(bird) || bird.startsWith("*秦巴巴鲵 (原名“秦巴北鲵”) (")) return "Ⅱ级";
        if ("*黄斑拟小鲵".equals(bird) || bird.startsWith("*黄斑拟小鲵 (")) return "Ⅱ级";
        if ("*贵州拟小鲵".equals(bird) || bird.startsWith("*贵州拟小鲵 (")) return "Ⅱ级";
        if ("*金佛拟小鲵".equals(bird) || bird.startsWith("*金佛拟小鲵 (")) return "Ⅱ级";
        if ("*宽阔水拟小鲵".equals(bird) || bird.startsWith("*宽阔水拟小鲵 (")) return "Ⅱ级";
        if ("*水城拟小鲵".equals(bird) || bird.startsWith("*水城拟小鲵 (")) return "Ⅱ级";
        if ("*弱唇褶山溪鲵".equals(bird) || bird.startsWith("*弱唇褶山溪鲵 (")) return "Ⅱ级";
        if ("*无斑山溪鲵".equals(bird) || bird.startsWith("*无斑山溪鲵 (")) return "Ⅱ级";
        if ("*龙洞山溪鲵".equals(bird) || bird.startsWith("*龙洞山溪鲵 (")) return "Ⅱ级";
        if ("*山溪鲵".equals(bird) || bird.startsWith("*山溪鲵 (")) return "Ⅱ级";
        if ("*西藏山溪鲵 (原名“北方山溪鲵”)".equals(bird) || bird.startsWith("*西藏山溪鲵 (原名“北方山溪鲵”) (")) return "Ⅱ级";
        if ("*盐源山溪鲵".equals(bird) || bird.startsWith("*盐源山溪鲵 (")) return "Ⅱ级";
        if ("*阿里山小鲵".equals(bird) || bird.startsWith("*阿里山小鲵 (")) return "Ⅱ级";
        if ("*台湾小鲵".equals(bird) || bird.startsWith("*台湾小鲵 (")) return "Ⅱ级";
        if ("*观雾小鲵".equals(bird) || bird.startsWith("*观雾小鲵 (")) return "Ⅱ级";
        if ("*南湖小鲵".equals(bird) || bird.startsWith("*南湖小鲵 (")) return "Ⅱ级";
        if ("*东北小鲵".equals(bird) || bird.startsWith("*东北小鲵 (")) return "Ⅱ级";
        if ("*楚南小鲵 (原名“能高山小鲵”)".equals(bird) || bird.startsWith("*楚南小鲵 (原名“能高山小鲵”) (")) return "Ⅱ级";
        if ("*义乌小鲵".equals(bird) || bird.startsWith("*义乌小鲵 (")) return "Ⅱ级";
        if ("*大鲵 (仅限野外种群)".equals(bird) || bird.startsWith("*大鲵 (仅限野外种群) (")) return "Ⅱ级";
        if ("*潮汕蝾螈".equals(bird) || bird.startsWith("*潮汕蝾螈 (")) return "Ⅱ级";
        if ("*大凉螈 (原名“大凉疣螈”)".equals(bird) || bird.startsWith("*大凉螈 (原名“大凉疣螈”) (")) return "Ⅱ级";
        if ("*贵州疣螈".equals(bird) || bird.startsWith("*贵州疣螈 (")) return "Ⅱ级";
        if ("*川南疣螈".equals(bird) || bird.startsWith("*川南疣螈 (")) return "Ⅱ级";
        if ("*丽色疣螈".equals(bird) || bird.startsWith("*丽色疣螈 (")) return "Ⅱ级";
        if ("*红瘰疣螈".equals(bird) || bird.startsWith("*红瘰疣螈 (")) return "Ⅱ级";
        if ("*棕黑疣螈 (原名“棕黑蛎螈”)".equals(bird) || bird.startsWith("*棕黑疣螈 (原名“棕黑蛎螈”) (")) return "Ⅱ级";
        if ("*滇南疣螈".equals(bird) || bird.startsWith("*滇南疣螈 (")) return "Ⅱ级";
        if ("*安徽瑶螈".equals(bird) || bird.startsWith("*安徽瑶螈 (")) return "Ⅱ级";
        if ("*细痣瑶螈 (原名“细痣疣螈”)".equals(bird) || bird.startsWith("*细痣瑶螈 (原名“细痣疣螈”) (")) return "Ⅱ级";
        if ("*宽脊瑶螈".equals(bird) || bird.startsWith("*宽脊瑶螈 (")) return "Ⅱ级";
        if ("*大别瑶螈".equals(bird) || bird.startsWith("*大别瑶螈 (")) return "Ⅱ级";
        if ("*浏阳瑶螈".equals(bird) || bird.startsWith("*浏阳瑶螈 (")) return "Ⅱ级";
        if ("*莽山瑶螈".equals(bird) || bird.startsWith("*莽山瑶螈 (")) return "Ⅱ级";
        if ("*文县瑶螈".equals(bird) || bird.startsWith("*文县瑶螈 (")) return "Ⅱ级";
        if ("*蔡氏瑶螈 ( )".equals(bird) || bird.startsWith("*蔡氏瑶螈 ( ) (")) return "Ⅱ级";
        if ("*镇海棘螈 (原名“镇海疣螈”)".equals(bird) || bird.startsWith("*镇海棘螈 (原名“镇海疣螈”) (")) return "Ⅰ级";
        if ("*琉球棘螈".equals(bird) || bird.startsWith("*琉球棘螈 (")) return "Ⅱ级";
        if ("*高山棘螈".equals(bird) || bird.startsWith("*高山棘螈 (")) return "Ⅱ级";
        if ("*橙脊瘰螈".equals(bird) || bird.startsWith("*橙脊瘰螈 (")) return "Ⅱ级";
        if ("*尾斑瘰螈".equals(bird) || bird.startsWith("*尾斑瘰螈 (")) return "Ⅱ级";
        if ("*中国瘰螈".equals(bird) || bird.startsWith("*中国瘰螈 (")) return "Ⅱ级";
        if ("*越南瘰螈".equals(bird) || bird.startsWith("*越南瘰螈 (")) return "Ⅱ级";
        if ("*富钟瘰螈".equals(bird) || bird.startsWith("*富钟瘰螈 (")) return "Ⅱ级";
        if ("*广西瘰螈".equals(bird) || bird.startsWith("*广西瘰螈 (")) return "Ⅱ级";
        if ("*香港瘰螈".equals(bird) || bird.startsWith("*香港瘰螈 (")) return "Ⅱ级";
        if ("*无斑瘰螈".equals(bird) || bird.startsWith("*无斑瘰螈 (")) return "Ⅱ级";
        if ("*龙里瘰螈".equals(bird) || bird.startsWith("*龙里瘰螈 (")) return "Ⅱ级";
        if ("*茂兰瘰螈".equals(bird) || bird.startsWith("*茂兰瘰螈 (")) return "Ⅱ级";
        if ("*七溪岭瘰螈".equals(bird) || bird.startsWith("*七溪岭瘰螈 (")) return "Ⅱ级";
        if ("*武陵瘰螈".equals(bird) || bird.startsWith("*武陵瘰螈 (")) return "Ⅱ级";
        if ("*云雾瘰螈".equals(bird) || bird.startsWith("*云雾瘰螈 (")) return "Ⅱ级";
        if ("*织金瘰螈".equals(bird) || bird.startsWith("*织金瘰螈 (")) return "Ⅱ级";
        if ("抱龙角蟾".equals(bird) || bird.startsWith("抱龙角蟾 (")) return "Ⅱ级";
        if ("凉北齿蟾".equals(bird) || bird.startsWith("凉北齿蟾 (")) return "Ⅱ级";
        if ("金顶齿突蟾".equals(bird) || bird.startsWith("金顶齿突蟾 (")) return "Ⅱ级";
        if ("九龙齿突蟾".equals(bird) || bird.startsWith("九龙齿突蟾 (")) return "Ⅱ级";
        if ("木里齿突蟾".equals(bird) || bird.startsWith("木里齿突蟾 (")) return "Ⅱ级";
        if ("宁陕齿突蟾".equals(bird) || bird.startsWith("宁陕齿突蟾 (")) return "Ⅱ级";
        if ("平武齿突蟾".equals(bird) || bird.startsWith("平武齿突蟾 (")) return "Ⅱ级";
        if ("哀牢髭蟾".equals(bird) || bird.startsWith("哀牢髭蟾 (")) return "Ⅱ级";
        if ("峨眉髭蟾".equals(bird) || bird.startsWith("峨眉髭蟾 (")) return "Ⅱ级";
        if ("雷山髭蟾".equals(bird) || bird.startsWith("雷山髭蟾 (")) return "Ⅱ级";
        if ("原髭蟾".equals(bird) || bird.startsWith("原髭蟾 (")) return "Ⅱ级";
        if ("南澳岛角蟾".equals(bird) || bird.startsWith("南澳岛角蟾 (")) return "Ⅱ级";
        if ("水城角蟾".equals(bird) || bird.startsWith("水城角蟾 (")) return "Ⅱ级";
        if ("史氏蟾蜍".equals(bird) || bird.startsWith("史氏蟾蜍 (")) return "Ⅱ级";
        if ("鳞皮小蟾".equals(bird) || bird.startsWith("鳞皮小蟾 (")) return "Ⅱ级";
        if ("乐东蟾蜍".equals(bird) || bird.startsWith("乐东蟾蜍 (")) return "Ⅱ级";
        if ("无棘溪蟾".equals(bird) || bird.startsWith("无棘溪蟾 (")) return "Ⅱ级";
        if ("*虎纹蛙 (仅限野外种群 )".equals(bird) || bird.startsWith("*虎纹蛙 (仅限野外种群 ) (")) return "Ⅱ级";
        if ("*脆皮大头蛙".equals(bird) || bird.startsWith("*脆皮大头蛙 (")) return "Ⅱ级";
        if ("*叶氏肛刺蛙".equals(bird) || bird.startsWith("*叶氏肛刺蛙 (")) return "Ⅱ级";
        if ("*海南湍蛙".equals(bird) || bird.startsWith("*海南湍蛙 (")) return "Ⅱ级";
        if ("*香港湍蛙".equals(bird) || bird.startsWith("*香港湍蛙 (")) return "Ⅱ级";
        if ("*小腺蛙".equals(bird) || bird.startsWith("*小腺蛙 (")) return "Ⅱ级";
        if ("*务川臭蛙".equals(bird) || bird.startsWith("*务川臭蛙 (")) return "Ⅱ级";
        if ("巫溪树蛙".equals(bird) || bird.startsWith("巫溪树蛙 (")) return "Ⅱ级";
        if ("老山树蛙".equals(bird) || bird.startsWith("老山树蛙 (")) return "Ⅱ级";
        if ("罗默刘树蛙".equals(bird) || bird.startsWith("罗默刘树蛙 (")) return "Ⅱ级";
        if ("洪佛树蛙".equals(bird) || bird.startsWith("洪佛树蛙 (")) return "Ⅱ级";
        if ("*厦门文昌鱼 (仅限野外种群。原名“文昌鱼”。)".equals(bird) || bird.startsWith("*厦门文昌鱼 (仅限野外种群。原名“文昌鱼”。) (")) return "Ⅱ级";
        if ("*青岛文昌鱼 (仅限野外种群)".equals(bird) || bird.startsWith("*青岛文昌鱼 (仅限野外种群) (")) return "Ⅱ级";
        if ("*日本七鰓鰻".equals(bird) || bird.startsWith("*日本七鰓鰻 (")) return "Ⅱ级";
        if ("*东北七鳃鳗".equals(bird) || bird.startsWith("*东北七鳃鳗 (")) return "Ⅱ级";
        if ("*雷氏七鰓鰻".equals(bird) || bird.startsWith("*雷氏七鰓鰻 (")) return "Ⅱ级";
        if ("*姥鲨".equals(bird) || bird.startsWith("*姥鲨 (")) return "Ⅱ级";
        if ("*噬人鲨".equals(bird) || bird.startsWith("*噬人鲨 (")) return "Ⅱ级";
        if ("*鲸鲨".equals(bird) || bird.startsWith("*鲸鲨 (")) return "Ⅱ级";
        if ("*黄魟 (仅限陆封种群)".equals(bird) || bird.startsWith("*黄魟 (仅限陆封种群) (")) return "Ⅱ级";
        if ("*中华鲟".equals(bird) || bird.startsWith("*中华鲟 (")) return "Ⅰ级";
        if ("*长江鲟 (原名“达氏鲟”)".equals(bird) || bird.startsWith("*长江鲟 (原名“达氏鲟”) (")) return "Ⅰ级";
        if ("*鳇 (仅限野外种群)".equals(bird) || bird.startsWith("*鳇 (仅限野外种群) (")) return "Ⅰ级";
        if ("*西伯利亚鲟 (仅限野外种群)".equals(bird) || bird.startsWith("*西伯利亚鲟 (仅限野外种群) (")) return "Ⅱ级";
        if ("*裸腹鲟 (仅限野外种群)".equals(bird) || bird.startsWith("*裸腹鲟 (仅限野外种群) (")) return "Ⅱ级";
        if ("*小体鲟 (仅限野外种群)".equals(bird) || bird.startsWith("*小体鲟 (仅限野外种群) (")) return "Ⅱ级";
        if ("*施氏鲟 (仅限野外种群)".equals(bird) || bird.startsWith("*施氏鲟 (仅限野外种群) (")) return "Ⅱ级";
        if ("*白鲟".equals(bird) || bird.startsWith("*白鲟 (")) return "Ⅰ级";
        if ("*花鳗鲡".equals(bird) || bird.startsWith("*花鳗鲡 (")) return "Ⅱ级";
        if ("*鲥".equals(bird) || bird.startsWith("*鲥 (")) return "Ⅰ级";
        if ("*双孔鱼 (仅限野外种群)".equals(bird) || bird.startsWith("*双孔鱼 (仅限野外种群) (")) return "Ⅱ级";
        if ("*平鳍裸吻鱼".equals(bird) || bird.startsWith("*平鳍裸吻鱼 (")) return "Ⅱ级";
        if ("*胭脂鱼 (仅限野外种群)".equals(bird) || bird.startsWith("*胭脂鱼 (仅限野外种群) (")) return "Ⅱ级";
        if ("*唐鱼 (仅限野外种群)".equals(bird) || bird.startsWith("*唐鱼 (仅限野外种群) (")) return "Ⅱ级";
        if ("*稀有鮈鲫 (仅限野外种群)".equals(bird) || bird.startsWith("*稀有鮈鲫 (仅限野外种群) (")) return "Ⅱ级";
        if ("*鯮".equals(bird) || bird.startsWith("*鯮 (")) return "Ⅱ级";
        if ("*多鳞白鱼".equals(bird) || bird.startsWith("*多鳞白鱼 (")) return "Ⅱ级";
        if ("*山白鱼".equals(bird) || bird.startsWith("*山白鱼 (")) return "Ⅱ级";
        if ("*北方铜鱼".equals(bird) || bird.startsWith("*北方铜鱼 (")) return "Ⅰ级";
        if ("*圆口铜鱼 (仅限野外种群)".equals(bird) || bird.startsWith("*圆口铜鱼 (仅限野外种群) (")) return "Ⅱ级";
        if ("*大鼻吻鮈".equals(bird) || bird.startsWith("*大鼻吻鮈 (")) return "Ⅱ级";
        if ("*长鳍吻鮈".equals(bird) || bird.startsWith("*长鳍吻鮈 (")) return "Ⅱ级";
        if ("*平鳍鳅鮀".equals(bird) || bird.startsWith("*平鳍鳅鮀 (")) return "Ⅱ级";
        if ("*单纹似鱤".equals(bird) || bird.startsWith("*单纹似鱤 (")) return "Ⅱ级";
        if ("*金线鲃属所有种".equals(bird) || bird.startsWith("*金线鲃属所有种 (")) return "Ⅱ级";
        if ("*四川白甲鱼".equals(bird) || bird.startsWith("*四川白甲鱼 (")) return "Ⅱ级";
        if ("*多鳞白甲鱼 (仅限野外种群)".equals(bird) || bird.startsWith("*多鳞白甲鱼 (仅限野外种群) (")) return "Ⅱ级";
        if ("*金沙鲈鲤 (仅限野外种群)".equals(bird) || bird.startsWith("*金沙鲈鲤 (仅限野外种群) (")) return "Ⅱ级";
        if ("*花鲈鲤 (仅限野外种群)".equals(bird) || bird.startsWith("*花鲈鲤 (仅限野外种群) (")) return "Ⅱ级";
        if ("*后背鲈鲤 (仅限野外种群)".equals(bird) || bird.startsWith("*后背鲈鲤 (仅限野外种群) (")) return "Ⅱ级";
        if ("*张氏鲈鲤 (仅限野外种群)".equals(bird) || bird.startsWith("*张氏鲈鲤 (仅限野外种群) (")) return "Ⅱ级";
        if ("*裸腹盲鲃".equals(bird) || bird.startsWith("*裸腹盲鲃 (")) return "Ⅱ级";
        if ("*角鱼".equals(bird) || bird.startsWith("*角鱼 (")) return "Ⅱ级";
        if ("*骨唇黄河鱼".equals(bird) || bird.startsWith("*骨唇黄河鱼 (")) return "Ⅱ级";
        if ("*极边扁咽齿鱼 (仅限野外种群)".equals(bird) || bird.startsWith("*极边扁咽齿鱼 (仅限野外种群) (")) return "Ⅱ级";
        if ("*细鳞裂腹鱼 (仅限野外种群)".equals(bird) || bird.startsWith("*细鳞裂腹鱼 (仅限野外种群) (")) return "Ⅱ级";
        if ("*巨须裂腹鱼".equals(bird) || bird.startsWith("*巨须裂腹鱼 (")) return "Ⅱ级";
        if ("*重口裂腹鱼 (仅限野外种群)".equals(bird) || bird.startsWith("*重口裂腹鱼 (仅限野外种群) (")) return "Ⅱ级";
        if ("*拉萨裂腹鱼 (仅限野外种群)".equals(bird) || bird.startsWith("*拉萨裂腹鱼 (仅限野外种群) (")) return "Ⅱ级";
        if ("*塔里木裂腹鱼 (仅限野外种群)".equals(bird) || bird.startsWith("*塔里木裂腹鱼 (仅限野外种群) (")) return "Ⅱ级";
        if ("*大理裂腹鱼 (仅限野外种群)".equals(bird) || bird.startsWith("*大理裂腹鱼 (仅限野外种群) (")) return "Ⅱ级";
        if ("*扁吻鱼 (原名“新疆大头鱼”)".equals(bird) || bird.startsWith("*扁吻鱼 (原名“新疆大头鱼”) (")) return "Ⅰ级";
        if ("*厚唇裸重唇鱼 (仅限野外种群)".equals(bird) || bird.startsWith("*厚唇裸重唇鱼 (仅限野外种群) (")) return "Ⅱ级";
        if ("*斑重唇鱼".equals(bird) || bird.startsWith("*斑重唇鱼 (")) return "Ⅱ级";
        if ("*尖裸鲤 (仅限野外种群)".equals(bird) || bird.startsWith("*尖裸鲤 (仅限野外种群) (")) return "Ⅱ级";
        if ("*大头鲤 (仅限野外种群)".equals(bird) || bird.startsWith("*大头鲤 (仅限野外种群) (")) return "Ⅱ级";
        if ("*小鲤".equals(bird) || bird.startsWith("*小鲤 (")) return "Ⅱ级";
        if ("*抚仙鲤".equals(bird) || bird.startsWith("*抚仙鲤 (")) return "Ⅱ级";
        if ("*岩原鲤 (仅限野外种群)".equals(bird) || bird.startsWith("*岩原鲤 (仅限野外种群) (")) return "Ⅱ级";
        if ("*乌原鲤".equals(bird) || bird.startsWith("*乌原鲤 (")) return "Ⅱ级";
        if ("*大鳞鲢".equals(bird) || bird.startsWith("*大鳞鲢 (")) return "Ⅱ级";
        if ("*红唇薄鳅 (仅限野外种群)".equals(bird) || bird.startsWith("*红唇薄鳅 (仅限野外种群) (")) return "Ⅱ级";
        if ("*黄线薄鳅".equals(bird) || bird.startsWith("*黄线薄鳅 (")) return "Ⅱ级";
        if ("*长薄鳅 (仅限野外种群)".equals(bird) || bird.startsWith("*长薄鳅 (仅限野外种群) (")) return "Ⅱ级";
        if ("*无眼岭鳅".equals(bird) || bird.startsWith("*无眼岭鳅 (")) return "Ⅱ级";
        if ("*拟鲇高原鳅 (仅限野外种群)".equals(bird) || bird.startsWith("*拟鲇高原鳅 (仅限野外种群) (")) return "Ⅱ级";
        if ("*湘西盲高原鳅".equals(bird) || bird.startsWith("*湘西盲高原鳅 (")) return "Ⅱ级";
        if ("*小头高原鳅".equals(bird) || bird.startsWith("*小头高原鳅 (")) return "Ⅱ级";
        if ("*厚唇原吸鳅".equals(bird) || bird.startsWith("*厚唇原吸鳅 (")) return "Ⅱ级";
        if ("*斑鳠 (仅限野外种群)".equals(bird) || bird.startsWith("*斑鳠 (仅限野外种群) (")) return "Ⅱ级";
        if ("*昆明鲇".equals(bird) || bird.startsWith("*昆明鲇 (")) return "Ⅱ级";
        if ("*长丝𩷶".equals(bird) || bird.startsWith("*长丝𩷶 (")) return "Ⅰ级";
        if ("*金氏䱀".equals(bird) || bird.startsWith("*金氏䱀 (")) return "Ⅱ级";
        if ("*长丝黑鮡".equals(bird) || bird.startsWith("*长丝黑鮡 (")) return "Ⅱ级";
        if ("*青石爬鮡".equals(bird) || bird.startsWith("*青石爬鮡 (")) return "Ⅱ级";
        if ("*黑斑原鮡".equals(bird) || bird.startsWith("*黑斑原鮡 (")) return "Ⅱ级";
        if ("*魾".equals(bird) || bird.startsWith("*魾 (")) return "Ⅱ级";
        if ("*红魾".equals(bird) || bird.startsWith("*红魾 (")) return "Ⅱ级";
        if ("*巨魾".equals(bird) || bird.startsWith("*巨魾 (")) return "Ⅱ级";
        if ("*细鳞鲑属所有种 (仅限野外种群)".equals(bird) || bird.startsWith("*细鳞鲑属所有种 (仅限野外种群) (")) return "Ⅱ级";
        if ("*川陕哲罗鲑".equals(bird) || bird.startsWith("*川陕哲罗鲑 (")) return "Ⅰ级";
        if ("*哲罗鲑 (仅限野外种群)".equals(bird) || bird.startsWith("*哲罗鲑 (仅限野外种群) (")) return "Ⅱ级";
        if ("*石川氏哲罗鲑".equals(bird) || bird.startsWith("*石川氏哲罗鲑 (")) return "Ⅱ级";
        if ("*花羔红点鲑 (仅限野外种群)".equals(bird) || bird.startsWith("*花羔红点鲑 (仅限野外种群) (")) return "Ⅱ级";
        if ("*马苏大马哈鱼".equals(bird) || bird.startsWith("*马苏大马哈鱼 (")) return "Ⅱ级";
        if ("*北鲑".equals(bird) || bird.startsWith("*北鲑 (")) return "Ⅱ级";
        if ("*北极茴鱼 (仅限野外种群)".equals(bird) || bird.startsWith("*北极茴鱼 (仅限野外种群) (")) return "Ⅱ级";
        if ("*下游黑龙江茴鱼 (仅限野外种群)".equals(bird) || bird.startsWith("*下游黑龙江茴鱼 (仅限野外种群) (")) return "Ⅱ级";
        if ("*鸭绿江茴鱼 (仅限野外种群)".equals(bird) || bird.startsWith("*鸭绿江茴鱼 (仅限野外种群) (")) return "Ⅱ级";
        if ("*海马属所有种 (仅限野外种群)".equals(bird) || bird.startsWith("*海马属所有种 (仅限野外种群) (")) return "Ⅱ级";
        if ("*黄唇鱼".equals(bird) || bird.startsWith("*黄唇鱼 (")) return "Ⅰ级";
        if ("*波纹唇鱼 (仅限野外种群)".equals(bird) || bird.startsWith("*波纹唇鱼 (仅限野外种群) (")) return "Ⅱ级";
        if ("*松江鲈 (仅限野外种群。原名“松江鲈鱼”)".equals(bird) || bird.startsWith("*松江鲈 (仅限野外种群。原名“松江鲈鱼”) (")) return "Ⅱ级";
        if ("*多鳃孔舌形虫".equals(bird) || bird.startsWith("*多鳃孔舌形虫 (")) return "Ⅰ级";
        if ("*三崎柱头虫".equals(bird) || bird.startsWith("*三崎柱头虫 (")) return "Ⅱ级";
        if ("*短殖舌形虫".equals(bird) || bird.startsWith("*短殖舌形虫 (")) return "Ⅱ级";
        if ("*肉质柱头虫".equals(bird) || bird.startsWith("*肉质柱头虫 (")) return "Ⅱ级";
        if ("*黄殖翼柱头虫".equals(bird) || bird.startsWith("*黄殖翼柱头虫 (")) return "Ⅱ级";
        if ("*青岛橡头虫".equals(bird) || bird.startsWith("*青岛橡头虫 (")) return "Ⅱ级";
        if ("*黄岛长吻虫".equals(bird) || bird.startsWith("*黄岛长吻虫 (")) return "Ⅰ级";
        if ("伟铗𧈢".equals(bird) || bird.startsWith("伟铗𧈢 (")) return "Ⅱ级";
        if ("丽叶䗛".equals(bird) || bird.startsWith("丽叶䗛 (")) return "Ⅱ级";
        if ("中华叶䗛".equals(bird) || bird.startsWith("中华叶䗛 (")) return "Ⅱ级";
        if ("泛叶䗛".equals(bird) || bird.startsWith("泛叶䗛 (")) return "Ⅱ级";
        if ("翔叶䗛".equals(bird) || bird.startsWith("翔叶䗛 (")) return "Ⅱ级";
        if ("东方叶䗛".equals(bird) || bird.startsWith("东方叶䗛 (")) return "Ⅱ级";
        if ("独龙叶䗛".equals(bird) || bird.startsWith("独龙叶䗛 (")) return "Ⅱ级";
        if ("同叶䗛".equals(bird) || bird.startsWith("同叶䗛 (")) return "Ⅱ级";
        if ("滇叶䗛".equals(bird) || bird.startsWith("滇叶䗛 (")) return "Ⅱ级";
        if ("藏叶䗛".equals(bird) || bird.startsWith("藏叶䗛 (")) return "Ⅱ级";
        if ("珍叶䗛".equals(bird) || bird.startsWith("珍叶䗛 (")) return "Ⅱ级";
        if ("扭尾曦春蜓 (原名“尖板曦箭蜓”)".equals(bird) || bird.startsWith("扭尾曦春蜓 (原名“尖板曦箭蜓”) (")) return "Ⅱ级";
        if ("棘角蛇纹春蜓 (原名“宽纹北箭蜓”)".equals(bird) || bird.startsWith("棘角蛇纹春蜓 (原名“宽纹北箭蜓”) (")) return "Ⅱ级";
        if ("中华缺翅虫".equals(bird) || bird.startsWith("中华缺翅虫 (")) return "Ⅱ级";
        if ("墨脱缺翅虫".equals(bird) || bird.startsWith("墨脱缺翅虫 (")) return "Ⅱ级";
        if ("中华蛩蠊".equals(bird) || bird.startsWith("中华蛩蠊 (")) return "Ⅰ级";
        if ("陈氏西蛩蠊".equals(bird) || bird.startsWith("陈氏西蛩蠊 (")) return "Ⅰ级";
        if ("中华旌蛉".equals(bird) || bird.startsWith("中华旌蛉 (")) return "Ⅱ级";
        if ("拉步甲".equals(bird) || bird.startsWith("拉步甲 (")) return "Ⅱ级";
        if ("细胸大步甲".equals(bird) || bird.startsWith("细胸大步甲 (")) return "Ⅱ级";
        if ("巫山大步甲".equals(bird) || bird.startsWith("巫山大步甲 (")) return "Ⅱ级";
        if ("库班大步甲".equals(bird) || bird.startsWith("库班大步甲 (")) return "Ⅱ级";
        if ("桂北大步甲".equals(bird) || bird.startsWith("桂北大步甲 (")) return "Ⅱ级";
        if ("贞大步甲".equals(bird) || bird.startsWith("贞大步甲 (")) return "Ⅱ级";
        if ("蓝鞘大步甲".equals(bird) || bird.startsWith("蓝鞘大步甲 (")) return "Ⅱ级";
        if ("滇川大步甲".equals(bird) || bird.startsWith("滇川大步甲 (")) return "Ⅱ级";
        if ("硕步甲".equals(bird) || bird.startsWith("硕步甲 (")) return "Ⅱ级";
        if ("中华两栖甲".equals(bird) || bird.startsWith("中华两栖甲 (")) return "Ⅱ级";
        if ("中华长阎甲".equals(bird) || bird.startsWith("中华长阎甲 (")) return "Ⅱ级";
        if ("大卫长阎甲".equals(bird) || bird.startsWith("大卫长阎甲 (")) return "Ⅱ级";
        if ("玛氏长阎甲".equals(bird) || bird.startsWith("玛氏长阎甲 (")) return "Ⅱ级";
        if ("戴氏棕臂金龟 (原名“戴褐臂金龟”)".equals(bird) || bird.startsWith("戴氏棕臂金龟 (原名“戴褐臂金龟”) (")) return "Ⅱ级";
        if ("玛氏棕臂金龟".equals(bird) || bird.startsWith("玛氏棕臂金龟 (")) return "Ⅱ级";
        if ("越南臂金龟".equals(bird) || bird.startsWith("越南臂金龟 (")) return "Ⅱ级";
        if ("福氏彩臂金龟".equals(bird) || bird.startsWith("福氏彩臂金龟 (")) return "Ⅱ级";
        if ("格彩臂金龟".equals(bird) || bird.startsWith("格彩臂金龟 (")) return "Ⅱ级";
        if ("台湾长臂金龟".equals(bird) || bird.startsWith("台湾长臂金龟 (")) return "Ⅱ级";
        if ("阳彩臂金龟".equals(bird) || bird.startsWith("阳彩臂金龟 (")) return "Ⅱ级";
        if ("印度长臂金龟".equals(bird) || bird.startsWith("印度长臂金龟 (")) return "Ⅱ级";
        if ("昭沼氏长臂金龟".equals(bird) || bird.startsWith("昭沼氏长臂金龟 (")) return "Ⅱ级";
        if ("艾氏泽蜣螂".equals(bird) || bird.startsWith("艾氏泽蜣螂 (")) return "Ⅱ级";
        if ("拜氏蜣螂".equals(bird) || bird.startsWith("拜氏蜣螂 (")) return "Ⅱ级";
        if ("悍马巨蜣螂".equals(bird) || bird.startsWith("悍马巨蜣螂 (")) return "Ⅱ级";
        if ("上帝巨蜣螂".equals(bird) || bird.startsWith("上帝巨蜣螂 (")) return "Ⅱ级";
        if ("迈达斯巨蜣螂".equals(bird) || bird.startsWith("迈达斯巨蜣螂 (")) return "Ⅱ级";
        if ("戴叉犀金龟 (原名“叉犀金龟”)".equals(bird) || bird.startsWith("戴叉犀金龟 (原名“叉犀金龟”) (")) return "Ⅱ级";
        if ("粗尤犀金龟".equals(bird) || bird.startsWith("粗尤犀金龟 (")) return "Ⅱ级";
        if ("细角尤犀金龟".equals(bird) || bird.startsWith("细角尤犀金龟 (")) return "Ⅱ级";
        if ("胫晓扁犀金龟".equals(bird) || bird.startsWith("胫晓扁犀金龟 (")) return "Ⅱ级";
        if ("安达刀锹甲".equals(bird) || bird.startsWith("安达刀锹甲 (")) return "Ⅱ级";
        if ("巨叉深山锹甲".equals(bird) || bird.startsWith("巨叉深山锹甲 (")) return "Ⅱ级";
        if ("喙凤蝶".equals(bird) || bird.startsWith("喙凤蝶 (")) return "Ⅱ级";
        if ("金斑喙凤蝶".equals(bird) || bird.startsWith("金斑喙凤蝶 (")) return "Ⅰ级";
        if ("裳凤蝶".equals(bird) || bird.startsWith("裳凤蝶 (")) return "Ⅱ级";
        if ("金裳凤蝶".equals(bird) || bird.startsWith("金裳凤蝶 (")) return "Ⅱ级";
        if ("荧光裳凤蝶".equals(bird) || bird.startsWith("荧光裳凤蝶 (")) return "Ⅱ级";
        if ("鸟翼裳凤蝶".equals(bird) || bird.startsWith("鸟翼裳凤蝶 (")) return "Ⅱ级";
        if ("珂裳凤蝶".equals(bird) || bird.startsWith("珂裳凤蝶 (")) return "Ⅱ级";
        if ("楔纹裳凤蝶".equals(bird) || bird.startsWith("楔纹裳凤蝶 (")) return "Ⅱ级";
        if ("小斑裳凤蝶".equals(bird) || bird.startsWith("小斑裳凤蝶 (")) return "Ⅱ级";
        if ("多尾凤蝶".equals(bird) || bird.startsWith("多尾凤蝶 (")) return "Ⅱ级";
        if ("不丹尾凤蝶".equals(bird) || bird.startsWith("不丹尾凤蝶 (")) return "Ⅱ级";
        if ("双尾凤蝶".equals(bird) || bird.startsWith("双尾凤蝶 (")) return "Ⅱ级";
        if ("玄裳尾凤蝶".equals(bird) || bird.startsWith("玄裳尾凤蝶 (")) return "Ⅱ级";
        if ("三尾凤蝶".equals(bird) || bird.startsWith("三尾凤蝶 (")) return "Ⅱ级";
        if ("玉龙尾凤蝶".equals(bird) || bird.startsWith("玉龙尾凤蝶 (")) return "Ⅱ级";
        if ("丽斑尾凤蝶".equals(bird) || bird.startsWith("丽斑尾凤蝶 (")) return "Ⅱ级";
        if ("锤尾凤蝶".equals(bird) || bird.startsWith("锤尾凤蝶 (")) return "Ⅱ级";
        if ("中华虎凤蝶".equals(bird) || bird.startsWith("中华虎凤蝶 (")) return "Ⅱ级";
        if ("最美紫蛱蝶".equals(bird) || bird.startsWith("最美紫蛱蝶 (")) return "Ⅱ级";
        if ("黑紫蛱蝶".equals(bird) || bird.startsWith("黑紫蛱蝶 (")) return "Ⅱ级";
        if ("阿波罗绢蝶".equals(bird) || bird.startsWith("阿波罗绢蝶 (")) return "Ⅱ级";
        if ("君主绢蝶".equals(bird) || bird.startsWith("君主绢蝶 (")) return "Ⅱ级";
        if ("大斑霾灰蝶".equals(bird) || bird.startsWith("大斑霾灰蝶 (")) return "Ⅱ级";
        if ("秀山白灰蝶".equals(bird) || bird.startsWith("秀山白灰蝶 (")) return "Ⅱ级";
        if ("海南塞勒蛛".equals(bird) || bird.startsWith("海南塞勒蛛 (")) return "Ⅱ级";
        if ("*中国鲎".equals(bird) || bird.startsWith("*中国鲎 (")) return "Ⅱ级";
        if ("*圆尾蝎鲎".equals(bird) || bird.startsWith("*圆尾蝎鲎 (")) return "Ⅱ级";
        if ("*锦绣龙虾 (仅限野外种群)".equals(bird) || bird.startsWith("*锦绣龙虾 (仅限野外种群) (")) return "Ⅱ级";
        if ("*大珠母贝 (仅限野外种群)".equals(bird) || bird.startsWith("*大珠母贝 (仅限野外种群) (")) return "Ⅱ级";
        if ("*大砗磲 (原名“库氏砗磲”)".equals(bird) || bird.startsWith("*大砗磲 (原名“库氏砗磲”) (")) return "Ⅰ级";
        if ("*无鳞砗磲 (仅限野外种群)".equals(bird) || bird.startsWith("*无鳞砗磲 (仅限野外种群) (")) return "Ⅱ级";
        if ("*鳞砗磲 (仅限野外种群)".equals(bird) || bird.startsWith("*鳞砗磲 (仅限野外种群) (")) return "Ⅱ级";
        if ("*长砗磲 (仅限野外种群)".equals(bird) || bird.startsWith("*长砗磲 (仅限野外种群) (")) return "Ⅱ级";
        if ("*番红砗磲 (仅限野外种群)".equals(bird) || bird.startsWith("*番红砗磲 (仅限野外种群) (")) return "Ⅱ级";
        if ("*砗蚝 (仅限野外种群)".equals(bird) || bird.startsWith("*砗蚝 (仅限野外种群) (")) return "Ⅱ级";
        if ("*珠母珍珠蚌 (仅限野外种群)".equals(bird) || bird.startsWith("*珠母珍珠蚌 (仅限野外种群) (")) return "Ⅱ级";
        if ("*佛耳丽蚌".equals(bird) || bird.startsWith("*佛耳丽蚌 (")) return "Ⅱ级";
        if ("*绢丝丽蚌".equals(bird) || bird.startsWith("*绢丝丽蚌 (")) return "Ⅱ级";
        if ("*背瘤丽蚌".equals(bird) || bird.startsWith("*背瘤丽蚌 (")) return "Ⅱ级";
        if ("*多瘤丽蚌".equals(bird) || bird.startsWith("*多瘤丽蚌 (")) return "Ⅱ级";
        if ("*刻裂丽蚌".equals(bird) || bird.startsWith("*刻裂丽蚌 (")) return "Ⅱ级";
        if ("*中国淡水蛏".equals(bird) || bird.startsWith("*中国淡水蛏 (")) return "Ⅱ级";
        if ("*龙骨蛏蚌".equals(bird) || bird.startsWith("*龙骨蛏蚌 (")) return "Ⅱ级";
        if ("*鹦鹉螺".equals(bird) || bird.startsWith("*鹦鹉螺 (")) return "Ⅰ级";
        if ("*螺蛳".equals(bird) || bird.startsWith("*螺蛳 (")) return "Ⅱ级";
        if ("*夜光蝾螺".equals(bird) || bird.startsWith("*夜光蝾螺 (")) return "Ⅱ级";
        if ("*虎斑宝贝".equals(bird) || bird.startsWith("*虎斑宝贝 (")) return "Ⅱ级";
        if ("*唐冠螺 (原名“冠螺”)".equals(bird) || bird.startsWith("*唐冠螺 (原名“冠螺”) (")) return "Ⅱ级";
        if ("*法螺".equals(bird) || bird.startsWith("*法螺 (")) return "Ⅱ级";
        if ("*角珊瑚目所有种".equals(bird) || bird.startsWith("*角珊瑚目所有种 (")) return "Ⅱ级";
        if ("*石珊瑚目所有种".equals(bird) || bird.startsWith("*石珊瑚目所有种 (")) return "Ⅱ级";
        if ("*苍珊瑚科所有种".equals(bird) || bird.startsWith("*苍珊瑚科所有种 (")) return "Ⅱ级";
        if ("*笙珊瑚".equals(bird) || bird.startsWith("*笙珊瑚 (")) return "Ⅱ级";
        if ("*红珊瑚科所有种".equals(bird) || bird.startsWith("*红珊瑚科所有种 (")) return "Ⅰ级";
        if ("*粗糙竹节柳珊瑚".equals(bird) || bird.startsWith("*粗糙竹节柳珊瑚 (")) return "Ⅱ级";
        if ("*细枝竹节柳珊瑚".equals(bird) || bird.startsWith("*细枝竹节柳珊瑚 (")) return "Ⅱ级";
        if ("*网枝竹节柳珊瑚".equals(bird) || bird.startsWith("*网枝竹节柳珊瑚 (")) return "Ⅱ级";
        if ("*分叉多孔螅".equals(bird) || bird.startsWith("*分叉多孔螅 (")) return "Ⅱ级";
        if ("*节块多孔螅".equals(bird) || bird.startsWith("*节块多孔螅 (")) return "Ⅱ级";
        if ("*窝形多孔螅".equals(bird) || bird.startsWith("*窝形多孔螅 (")) return "Ⅱ级";
        if ("*错综多孔螅".equals(bird) || bird.startsWith("*错综多孔螅 (")) return "Ⅱ级";
        if ("*阔叶多孔螅".equals(bird) || bird.startsWith("*阔叶多孔螅 (")) return "Ⅱ级";
        if ("*扁叶多孔螅".equals(bird) || bird.startsWith("*扁叶多孔螅 (")) return "Ⅱ级";
        if ("*娇嫩多孔螅".equals(bird) || bird.startsWith("*娇嫩多孔螅 (")) return "Ⅱ级";
        if ("*无序双孔螅".equals(bird) || bird.startsWith("*无序双孔螅 (")) return "Ⅱ级";
        if ("*紫色双孔螅".equals(bird) || bird.startsWith("*紫色双孔螅 (")) return "Ⅱ级";
        if ("*佳丽刺柱螅".equals(bird) || bird.startsWith("*佳丽刺柱螅 (")) return "Ⅱ级";
        if ("*扇形柱星螅".equals(bird) || bird.startsWith("*扇形柱星螅 (")) return "Ⅱ级";
        if ("*细巧柱星螅".equals(bird) || bird.startsWith("*细巧柱星螅 (")) return "Ⅱ级";
        if ("*佳丽柱星螅".equals(bird) || bird.startsWith("*佳丽柱星螅 (")) return "Ⅱ级";
        if ("*艳红柱星螅".equals(bird) || bird.startsWith("*艳红柱星螅 (")) return "Ⅱ级";
        if ("*粗糙柱星螅".equals(bird) || bird.startsWith("*粗糙柱星螅 (")) return "Ⅱ级";
        return "";
    }

    private JSONObject birdreportPost(String path, Map<String, String> params) throws Exception {
        String plain = encodedSortedJson(params);
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(android.util.Base64.decode(BIRDREPORT_PUBLIC_KEY, android.util.Base64.DEFAULT)));
        ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
        byte[] plainBytes = plain.getBytes(StandardCharsets.UTF_8);
        for (int offset = 0; offset < plainBytes.length; offset += 117) {
            int length = Math.min(117, plainBytes.length - offset);
            Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            rsa.init(Cipher.ENCRYPT_MODE, publicKey);
            encrypted.write(rsa.doFinal(plainBytes, offset, length));
        }
        String timestamp = String.valueOf(System.currentTimeMillis());
        String requestId = UUID.randomUUID().toString().replace("-", "");
        byte[] requestBody = android.util.Base64.encode(encrypted.toByteArray(), android.util.Base64.NO_WRAP);
        HttpURLConnection connection = openBirdreport(BIRDREPORT_API + path, "POST",
                "application/x-www-form-urlencoded; charset=UTF-8", null,
                "https://www.birdreport.cn/home/search/page.html");
        connection.setRequestProperty("timestamp", timestamp);
        connection.setRequestProperty("requestId", requestId);
        connection.setRequestProperty("sign", md5(plain + requestId + timestamp));
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(requestBody.length);
        try (OutputStream output = connection.getOutputStream()) { output.write(requestBody); }
        return readBirdreportJson(connection);
    }

    private String encodedSortedJson(Map<String, String> params) throws Exception {
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        JSONObject sorted = new JSONObject();
        for (String key : keys) sorted.put(key, URLEncoder.encode(params.get(key), "UTF-8"));
        return sorted.toString();
    }

    private JSONArray decodeBirdreportData(String value) throws Exception {
        Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(BIRDREPORT_AES_KEY, "AES"), new IvParameterSpec(BIRDREPORT_AES_IV));
        String json = new String(aes.doFinal(android.util.Base64.decode(value, android.util.Base64.DEFAULT)), StandardCharsets.UTF_8);
        return new JSONArray(json);
    }

    private HttpURLConnection openBirdreport(String url, String method, String contentType, byte[] body, String referer) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AI-Birding/3.1");
        connection.setRequestProperty("Referer", referer);
        connection.setRequestProperty("Origin", "https://www.birdreport.cn");
        if (contentType != null) connection.setRequestProperty("Content-Type", contentType);
        String cookie = cookieHeader();
        if (!cookie.isEmpty()) connection.setRequestProperty("Cookie", cookie);
        if (body != null) {
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
        }
        return connection;
    }

    private JSONObject readBirdreportJson(HttpURLConnection connection) throws Exception {
        try {
            int code = connection.getResponseCode();
            storeCookies(connection);
            String body = readAll(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
            if (code < 200 || code >= 300) throw new Exception("观鸟记录中心返回 HTTP " + code);
            return new JSONObject(body);
        } finally { connection.disconnect(); }
    }

    private synchronized String cookieHeader() {
        StringBuilder value = new StringBuilder();
        for (Map.Entry<String, String> item : birdreportCookies.entrySet()) {
            if (value.length() > 0) value.append("; ");
            value.append(item.getKey()).append('=').append(item.getValue());
        }
        return value.toString();
    }

    private synchronized void storeCookies(HttpURLConnection connection) {
        for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
            if (header.getKey() == null || !"Set-Cookie".equalsIgnoreCase(header.getKey())) continue;
            for (String raw : header.getValue()) {
                String pair = raw.split(";", 2)[0];
                int separator = pair.indexOf('=');
                if (separator > 0) birdreportCookies.put(pair.substring(0, separator), pair.substring(separator + 1));
            }
        }
    }

    private static String md5(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte item : digest) hex.append(String.format(Locale.US, "%02x", item & 0xff));
        return hex.toString();
    }

    private static String formatDate(Calendar calendar) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(calendar.getTime());
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && !first.trim().isEmpty() ? first : (second == null ? "" : second);
    }

    private static String[] normalizeRegion(String rawProvince, String rawCity) {
        String province = rawProvince == null ? "" : rawProvince.trim();
        String city = rawCity == null ? "" : rawCity.trim();
        Map<String, String> aliases = new LinkedHashMap<>();
        aliases.put("北京", "北京市"); aliases.put("上海", "上海市");
        aliases.put("天津", "天津市"); aliases.put("重庆", "重庆市");
        aliases.put("内蒙古", "内蒙古自治区"); aliases.put("广西", "广西壮族自治区");
        aliases.put("西藏", "西藏自治区"); aliases.put("宁夏", "宁夏回族自治区");
        aliases.put("新疆", "新疆维吾尔自治区"); aliases.put("香港", "香港特别行政区");
        aliases.put("澳门", "澳门特别行政区");
        String[] provinces = {"河北", "山西", "辽宁", "吉林", "黑龙江", "江苏", "浙江", "安徽", "福建",
                "江西", "山东", "河南", "湖北", "湖南", "广东", "海南", "四川", "贵州", "云南", "陕西",
                "甘肃", "青海", "台湾"};
        for (String item : provinces) aliases.put(item, item + "省");
        if (aliases.containsKey(province)) province = aliases.get(province);

        boolean provincialLevel = province.endsWith("省") || province.endsWith("自治区")
                || province.endsWith("特别行政区") || "北京市".equals(province) || "上海市".equals(province)
                || "天津市".equals(province) || "重庆市".equals(province);
        if (!province.isEmpty() && !provincialLevel) {
            if (city.isEmpty()) city = province;
            province = "";
        }
        return new String[]{province, city};
    }

    private static class CaptchaRequiredException extends Exception { }

    private JSONObject loadBirdreportCaptcha() throws Exception {
        HttpURLConnection connection = openBirdreport(
                BIRDREPORT_API + "front/code/visited/generate?timestamp=" + System.currentTimeMillis(),
                "GET", null, null, "https://www.birdreport.cn/home/code/verify.html");
        try {
            int code = connection.getResponseCode();
            storeCookies(connection);
            if (code < 200 || code >= 300) throw new Exception("验证码获取失败（" + code + "）");
            String type = connection.getContentType();
            if (type == null || !type.startsWith("image/")) type = "image/jpeg";
            byte[] image = readBytes(connection.getInputStream());
            return new JSONObject().put("ok", true).put("image",
                    "data:" + type.split(";", 2)[0] + ";base64," +
                            android.util.Base64.encodeToString(image, android.util.Base64.NO_WRAP));
        } finally { connection.disconnect(); }
    }

    private JSONObject verifyBirdreportCaptcha(String code) throws Exception {
        byte[] body = new JSONObject().put("code", code).toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = openBirdreport(BIRDREPORT_API + "front/code/visited/verify", "POST",
                "application/json; charset=UTF-8", body, "https://www.birdreport.cn/home/code/verify.html");
        JSONObject response = readBirdreportJson(connection);
        if (!response.optBoolean("success"))
            throw new Exception(response.optString("msg", "验证码不正确，请重新输入"));
        return new JSONObject().put("ok", true).put("message", response.optString("msg", "验证通过"));
    }

    private static byte[] readBytes(InputStream stream) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int length;
        try (InputStream input = stream) {
            while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
        }
        return output.toByteArray();
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private void callback(String function, JSONObject payload) {
        String script = "window." + function + "(" + payload.toString() + ")";
        runOnUiThread(() -> webView.evaluateJavascript(script, null));
    }

    public class NativeBridge {
        @JavascriptInterface
        public boolean hasKey() {
            return activeModelHasKey();
        }

        @JavascriptInterface
        public String saveKey(String raw) {
            String value = raw == null ? "" : raw.trim();
            if (value.length() < 8) return "请输入有效的 DeepSeek API Key";
            try {
                saveApiKey(modelProvider(), value);
                return "";
            } catch (Exception ignored) {
                return "Key 保存失败，请重试";
            }
        }

        @JavascriptInterface
        public String historyJson() {
            return history().toString();
        }

        @JavascriptInterface
        public String preferenceJson() {
            return birdPreferences().toString();
        }

        @JavascriptInterface
        public String savePreferences(String province, String city, String district, String blacklist) {
            return saveBirdPreferences(province, city, district, blacklist);
        }

        @JavascriptInterface
        public boolean clearHistory() {
            return prefs().edit().remove("history").commit();
        }

        @JavascriptInterface
        public void resetKey() {
            String prefix = "zhipu".equals(modelProvider()) ? "zhipu_" : "";
            prefs().edit().remove(prefix + "key").remove(prefix + "iv").apply();
        }

        @JavascriptInterface
        public String modelProvider() { return MainActivity.this.modelProvider(); }

        @JavascriptInterface
        public String switchModel(String provider) {
            String value = "zhipu".equals(provider) ? "zhipu" : "deepseek";
            prefs().edit().putString("model_provider", value).apply();
            return value;
        }

        @JavascriptInterface
        public void pickImage() { MainActivity.this.pickImage(); }

        @JavascriptInterface
        public void clearImage() { pendingImageData = ""; }

        @JavascriptInterface
        public void loadCaptcha() {
            executor.execute(() -> {
                JSONObject result;
                try { result = loadBirdreportCaptcha(); }
                catch (Exception error) {
                    result = new JSONObject();
                    try { result.put("ok", false).put("error", error.getMessage()); } catch (Exception ignored) { }
                }
                callback("onCaptchaLoaded", result);
            });
        }

        @JavascriptInterface
        public void verifyCaptcha(String raw) {
            final String code = raw == null ? "" : raw.trim();
            executor.execute(() -> {
                JSONObject result = new JSONObject();
                try {
                    if (!code.matches("[A-Za-z0-9]{4,6}")) throw new Exception("请输入正确的验证码");
                    result = verifyBirdreportCaptcha(code);
                } catch (Exception error) {
                    try { result.put("ok", false).put("error", error.getMessage()); } catch (Exception ignored) { }
                }
                callback("onCaptchaVerified", result);
            });
        }

        @JavascriptInterface
        public void ask(String raw) {
            final String question = raw == null ? "" : raw.trim();
            executor.execute(() -> {
                JSONObject result = new JSONObject();
                try {
                    if (question.isEmpty()) throw new Exception("请输入你想了解的鸟类问题");
                    String image = pendingImageData;
                    pendingImageData = "";
                    QueryTrace trace = requestDeepSeek(question, image);
                    saveExchange(question, trace.answer, trace);
                    result.put("ok", true).put("answer", trace.answer)
                            .put("recordsQueried", trace.queried)
                            .put("querySummary", trace.summary);
                } catch (CaptchaRequiredException error) {
                    try {
                        result.put("ok", false).put("captchaRequired", true)
                                .put("error", "观鸟记录中心需要完成访问验证");
                    } catch (Exception ignored) { }
                } catch (Exception error) {
                    String message = error.getMessage();
                    if (message == null || message.trim().isEmpty()) message = "网络异常，请稍后重试";
                    try { result.put("ok", false).put("error", message); } catch (Exception ignored) { }
                }
                callback("onAiResult", result);
            });
        }
    }
}
