package com.birdstools.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.view.Window;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends Activity {
    private static final String PREFS = "ai_bird_chat";
    private static final String KEY_ALIAS = "birds_tools_deepseek_key";
    private static final String API_URL = "https://api.deepseek.com/chat/completions";
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

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(18, 63, 55));
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
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey());
        prefs().edit()
                .putString("key", android.util.Base64.encodeToString(
                        cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)), android.util.Base64.NO_WRAP))
                .putString("iv", android.util.Base64.encodeToString(
                        cipher.getIV(), android.util.Base64.NO_WRAP))
                .apply();
    }

    private String apiKey() {
        String encrypted = prefs().getString("key", "");
        String iv = prefs().getString("iv", "");
        if (encrypted.isEmpty() || iv.isEmpty()) return "";
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), new GCMParameterSpec(
                    128, android.util.Base64.decode(iv, android.util.Base64.NO_WRAP)));
            return new String(cipher.doFinal(android.util.Base64.decode(
                    encrypted, android.util.Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            prefs().edit().remove("key").remove("iv").apply();
            return "";
        }
    }

    private JSONArray history() {
        try {
            return new JSONArray(prefs().getString("history", "[]"));
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private void saveExchange(String question, String answer, QueryTrace trace) throws Exception {
        JSONArray current = history();
        JSONObject exchange = new JSONObject().put("question", question).put("answer", answer);
        if (trace.queried) exchange.put("records_queried", true).put("query_summary", trace.summary);
        current.put(exchange);
        JSONArray trimmed = new JSONArray();
        int start = Math.max(0, current.length() - MAX_HISTORY);
        for (int i = start; i < current.length(); i++) trimmed.put(current.getJSONObject(i));
        prefs().edit().putString("history", trimmed.toString()).apply();
    }

    private QueryTrace requestDeepSeek(String question) throws Exception {
        if (apiKey().isEmpty()) throw new Exception("请先设置 DeepSeek API Key");
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Calendar.getInstance().getTime());
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content",
                "你是 AI 观鸟助手。今天是 " + today + "。涉及具体鸟种、数量、地点或日期时，必须调用 "
                        + "query_bird_records 查询观鸟记录中心的真实公开数据，不能凭常识猜测。回答时用中文明确说明查询日期、区域、"
                        + "地点匹配、记录数量及数据来源；没有结果时明确说明。用户说‘最近’或‘近期’但未指定范围时，查询截至今天的最近7天。"
                        + "其他鸟类知识问题可以直接回答，但不要虚构鸟名、数量或观察结论。请使用简洁清晰的 Markdown 排版："
                        + "重点用加粗，多个地点或鸟种优先用列表；这是手机界面，尽量不用表格，必须比较时表格最多3列且内容简短，不要堆叠过多标题。"));
        JSONArray old = history();
        for (int i = 0; i < old.length(); i++) {
            JSONObject item = old.getJSONObject(i);
            messages.put(new JSONObject().put("role", "user").put("content", item.optString("question")));
            messages.put(new JSONObject().put("role", "assistant").put("content", item.optString("answer")));
        }
        messages.put(new JSONObject().put("role", "user").put("content", question));

        boolean queried = false;
        String querySummary = "";
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
                return new QueryTrace(answer, queried, querySummary);
            }
            messages.put(assistant);
            for (int i = 0; i < toolCalls.length(); i++) {
                JSONObject call = toolCalls.getJSONObject(i);
                JSONObject function = call.optJSONObject("function");
                if (function == null || !"query_bird_records".equals(function.optString("name"))) continue;
                JSONObject arguments = new JSONObject(function.optString("arguments", "{}"));
                JSONObject toolResult = queryBirdRecords(arguments);
                queried = true;
                querySummary = buildQuerySummary(toolResult);
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

    private JSONObject callDeepSeek(JSONArray messages, boolean forceRecordsQuery) throws Exception {
        JSONObject properties = new JSONObject()
                .put("start_date", new JSONObject().put("type", "string").put("description", "开始日期，格式 YYYY-MM-DD"))
                .put("end_date", new JSONObject().put("type", "string").put("description", "结束日期，格式 YYYY-MM-DD"))
                .put("province", new JSONObject().put("type", "string").put("description", "省或直辖市，例如 北京市"))
                .put("city", new JSONObject().put("type", "string").put("description", "城市，例如 青岛市；直辖市可留空"))
                .put("location_keyword", new JSONObject().put("type", "string").put("description", "地点关键词，例如 天坛；没有地点限制时留空"));
        JSONObject parameters = new JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", new JSONArray().put("start_date").put("end_date").put("province"))
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
        String keyword = query.optString("location_keyword");
        if (!keyword.isEmpty()) region += " · " + keyword;
        return query.optString("start_date") + " 至 " + query.optString("end_date")
                + " · " + region + " · " + result.optInt("record_total") + " 条记录";
    }

    private static class QueryTrace {
        final String answer;
        final boolean queried;
        final String summary;
        QueryTrace(String answer, boolean queried, String summary) {
            this.answer = answer;
            this.queried = queried;
            this.summary = summary == null ? "" : summary;
        }
    }

    private JSONObject postDeepSeek(JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(API_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey());
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
                if (code == 402) throw new Exception("DeepSeek 账户余额不足");
                if (code == 429) throw new Exception("DeepSeek 请求过于频繁，请稍后再试");
                throw new Exception(message.isEmpty() ? "DeepSeek 服务异常（" + code + "）" : message);
            }
            return new JSONObject(response);
        } finally {
            connection.disconnect();
        }
    }

    private JSONObject queryBirdRecords(JSONObject arguments) throws Exception {
        Calendar calendar = Calendar.getInstance();
        String endDate = arguments.optString("end_date").trim();
        if (endDate.isEmpty()) endDate = formatDate(calendar);
        calendar.add(Calendar.DAY_OF_MONTH, -1);
        String startDate = arguments.optString("start_date").trim();
        if (startDate.isEmpty()) startDate = formatDate(calendar);
        String originalProvince = arguments.optString("province", "北京市").trim();
        String originalCity = arguments.optString("city").trim();
        String[] region = normalizeRegion(originalProvince, originalCity);
        String province = region[0];
        String city = region[1];
        String keyword = arguments.optString("location_keyword").trim();
        if (!startDate.matches("\\d{4}-\\d{2}-\\d{2}") || !endDate.matches("\\d{4}-\\d{2}-\\d{2}"))
            throw new Exception("查询日期格式不正确");
        if (province.isEmpty() && city.isEmpty()) throw new Exception("查询区域不能为空");

        List<JSONObject> details = fetchBirdreportDetails(province, city, startDate, endDate);
        if (!keyword.isEmpty()) {
            List<JSONObject> filtered = new ArrayList<>();
            for (JSONObject item : details) {
                if (item.optString("observation_location").toLowerCase(Locale.ROOT)
                        .contains(keyword.toLowerCase(Locale.ROOT))) filtered.add(item);
            }
            details = filtered;
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
            for (JSONObject item : entry.getValue()) {
                if (!item.optString("bird_name").isEmpty()) birds.add(item.optString("bird_name"));
                if (!item.optString("report_no").isEmpty()) reports.add(item.optString("report_no"));
            }
            locations.add(new JSONObject()
                    .put("location", entry.getKey())
                    .put("species_count", birds.size())
                    .put("record_count", entry.getValue().size())
                    .put("report_count", reports.size())
                    .put("bird_names", new JSONArray(birds)));
        }
        Collections.sort(locations, (left, right) -> {
            int species = Integer.compare(right.optInt("species_count"), left.optInt("species_count"));
            return species != 0 ? species : Integer.compare(right.optInt("record_count"), left.optInt("record_count"));
        });
        JSONArray top = new JSONArray();
        for (int i = 0; i < Math.min(20, locations.size()); i++) top.put(locations.get(i));
        return new JSONObject()
                .put("query", new JSONObject().put("province", province).put("city", city)
                        .put("location_keyword", keyword).put("start_date", startDate).put("end_date", endDate)
                        .put("region_corrected", !province.equals(originalProvince) || !city.equals(originalCity)))
                .put("record_total", details.size())
                .put("location_total", grouped.size())
                .put("locations", top)
                .put("source", "观鸟记录中心 https://www.birdreport.cn/home/search/page.html");
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
        return new JSONObject().put("report_no", report).put("observation_location", location.toString())
                .put("bird_name", bird).put("outside_type", outsideType);
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
            return !apiKey().isEmpty();
        }

        @JavascriptInterface
        public String saveKey(String raw) {
            String value = raw == null ? "" : raw.trim();
            if (value.length() < 8) return "请输入有效的 DeepSeek API Key";
            try {
                saveApiKey(value);
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
        public void clearHistory() {
            prefs().edit().remove("history").apply();
        }

        @JavascriptInterface
        public void resetKey() {
            prefs().edit().remove("key").remove("iv").apply();
        }

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
                    QueryTrace trace = requestDeepSeek(question);
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
