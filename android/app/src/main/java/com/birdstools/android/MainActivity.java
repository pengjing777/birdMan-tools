package com.birdstools.android;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
    private static final int PICK_IMAGES = 2001;
    private static final int EXPORT_RECORDS = 2002;
    private static final String PREFS = "birds_tools_local";
    private final List<Animal> animals = new ArrayList<>();
    private LinearLayout content;
    private TextView title;
    private WebView webView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ensureAnimalsLoaded();
        webView = new WebView(this);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webSettings.setAllowFileAccess(true);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new NativeBridge(), "Native");
        setContentView(webView);
        webView.loadUrl("file:///android_asset/web/index.html");
    }

    private void ensureAnimalsLoaded() {
        if (!animals.isEmpty()) return;
        try {
            InputStream input = getAssets().open("国家重点保护野生动物名录.xlsx");
            ZipInputStream zip = new ZipInputStream(input);
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.getName().equals("xl/worksheets/sheet1.xml")) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = zip.read(buffer)) >= 0) out.write(buffer, 0, count);
                parseAnimals(out.toString("UTF-8"));
                break;
            }
            zip.close();
        } catch (Exception ignored) { }
    }

    private class NativeBridge {
        @JavascriptInterface public String catalog(String query) {
            JSONArray result = new JSONArray();
            String keyword = query == null ? "" : query.trim().toLowerCase();
            try {
                for (Animal animal : animals) {
                    String all = (animal.name + animal.latin + animal.family + animal.level).toLowerCase();
                    if (!keyword.isEmpty() && !all.contains(keyword)) continue;
                    JSONObject item = new JSONObject();
                    item.put("name", animal.name); item.put("latin", animal.latin); item.put("family", animal.family); item.put("level", animal.level);
                    result.put(item); if (result.length() >= 100) break;
                }
            } catch (Exception ignored) { }
            return result.toString();
        }

        @JavascriptInterface public String records() {
            return getSharedPreferences(PREFS, MODE_PRIVATE).getString("records", "[]");
        }

        @JavascriptInterface public String saveRecord(String payload) {
            try {
                JSONObject item = new JSONObject(payload);
                if (item.optString("bird").trim().isEmpty()) return new JSONObject().put("error", "请输入鸟名").toString();
                JSONArray records = new JSONArray(getSharedPreferences(PREFS, MODE_PRIVATE).getString("records", "[]"));
                records.put(item); getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("records", records.toString()).apply();
                return "{\"ok\":true}";
            } catch (Exception error) { return "{\"error\":\"保存失败\"}"; }
        }

        @JavascriptInterface public void deleteRecord(int index) {
            try {
                JSONArray records = new JSONArray(getSharedPreferences(PREFS, MODE_PRIVATE).getString("records", "[]"));
                if (index >= 0 && index < records.length()) records.remove(index);
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("records", records.toString()).apply();
            } catch (Exception ignored) { }
        }

        @JavascriptInterface public void toggleBookmark(String type, String value) {
            try {
                SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                JSONArray source = new JSONArray(prefs.getString("bookmarks", "[]"));
                String key = type + ":" + value; JSONArray target = new JSONArray(); boolean removed = false;
                for (int i = 0; i < source.length(); i++) {
                    String current = source.optString(i); if (key.equals(current)) removed = true; else target.put(current);
                }
                if (!removed) target.put(key); prefs.edit().putString("bookmarks", target.toString()).apply();
            } catch (Exception ignored) { }
        }

        @JavascriptInterface public String bookmarks() {
            JSONArray result = new JSONArray();
            try {
                JSONArray saved = new JSONArray(getSharedPreferences(PREFS, MODE_PRIVATE).getString("bookmarks", "[]"));
                JSONArray records = new JSONArray(getSharedPreferences(PREFS, MODE_PRIVATE).getString("records", "[]"));
                for (int i = 0; i < saved.length(); i++) {
                    String key = saved.optString(i); JSONObject item = new JSONObject();
                    if (key.startsWith("species:")) { item.put("type", "保护鸟种"); item.put("title", key.substring(8)); }
                    else if (key.startsWith("record:")) {
                        int index = Integer.parseInt(key.substring(7)); JSONObject record = records.optJSONObject(index);
                        item.put("type", "观鸟记录"); item.put("title", record == null ? "已删除的记录" : record.optString("bird") + " · " + record.optString("location"));
                    }
                    result.put(item);
                }
            } catch (Exception ignored) { }
            return result.toString();
        }

        @JavascriptInterface public String config() {
            try {
                SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE); JSONObject data = new JSONObject();
                data.put("nickname", prefs.getString("nickname", "")); data.put("defaultProvince", prefs.getString("defaultProvince", "重庆市")); return data.toString();
            } catch (Exception ignored) { return "{}"; }
        }

        @JavascriptInterface public void saveConfig(String payload) {
            try {
                JSONObject data = new JSONObject(payload); getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("nickname", data.optString("nickname")).putString("defaultProvince", data.optString("defaultProvince", "重庆市")).apply();
            } catch (Exception ignored) { }
        }

        @JavascriptInterface public void exportRecords() {
            runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.setType("text/csv");
                intent.putExtra(Intent.EXTRA_TITLE, "鸟友工具箱-鸟类记录.csv"); startActivityForResult(intent, EXPORT_RECORDS);
            });
        }

        @JavascriptInterface public void searchBirds(String payload) {
            new Thread(() -> {
                JSONObject response = new JSONObject();
                try {
                    JSONObject data = new JSONObject(payload);
                    JSONArray rows = queryBirdReport(data.optString("birdName"), data.optString("province"), data.optString("city"), data.optString("district"), data.optString("start"), data.optString("end"));
                    response.put("records", rows);
                } catch (Exception error) {
                    try { response.put("error", error.getMessage() == null ? "联网查询失败" : error.getMessage()); } catch (Exception ignored) { }
                }
                String encoded = JSONObject.quote(response.toString());
                runOnUiThread(() -> webView.evaluateJavascript("window.onNativeSearchResult(" + encoded + ")", null));
            }).start();
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView label(String text, int size) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(0xff1e293b);
        view.setPadding(dp(16), dp(10), dp(16), dp(10));
        return view;
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff0f2f5);

        title = label("鸟友工具箱", 20);
        title.setTextColor(0xffffffff);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setBackgroundColor(0xff1e293b);
        root.addView(title, new LinearLayout.LayoutParams(-1, dp(58)));

        LinearLayout nav = new LinearLayout(this);
        nav.setPadding(dp(8), dp(6), dp(8), dp(6));
        nav.setBackgroundColor(0xffffffff);
        addNavButton(nav, "保护名录", v -> showCatalog());
        addNavButton(nav, "我的记录", v -> showRecords());
        addNavButton(nav, "联网观鸟", v -> showBirdSearch());
        addNavButton(nav, "配置", v -> showConfig());
        root.addView(nav, new LinearLayout.LayoutParams(-1, dp(54)));

        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(12), dp(12), dp(24));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void addNavButton(LinearLayout parent, String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(13);
        button.setOnClickListener(listener);
        parent.addView(button, new LinearLayout.LayoutParams(0, -1, 1));
    }

    private void showCatalog() {
        title.setText("保护动物名录（离线）");
        content.removeAllViews();
        EditText search = new EditText(this);
        search.setHint("搜索中文名、拉丁学名或科目");
        search.setSingleLine(true);
        content.addView(search, new LinearLayout.LayoutParams(-1, dp(52)));
        LinearLayout results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        content.addView(results, new LinearLayout.LayoutParams(-1, -2));
        loadAnimals(() -> renderAnimals(results, search.getText().toString()));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { renderAnimals(results, s.toString()); }
            public void afterTextChanged(Editable e) { }
        });
    }

    private void loadAnimals(Runnable done) {
        if (!animals.isEmpty()) { done.run(); return; }
        new Thread(() -> {
            try {
                InputStream input = getAssets().open("国家重点保护野生动物名录.xlsx");
                ZipInputStream zip = new ZipInputStream(input);
                ZipEntry entry;
                String xml = null;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.getName().equals("xl/worksheets/sheet1.xml")) {
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = zip.read(buffer)) >= 0) out.write(buffer, 0, count);
                        xml = out.toString("UTF-8");
                        break;
                    }
                }
                if (xml != null) parseAnimals(xml);
                runOnUiThread(done);
            } catch (Exception error) {
                runOnUiThread(() -> Toast.makeText(this, "名录读取失败：" + error.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void parseAnimals(String xml) {
        Pattern rowPattern = Pattern.compile("<x:row[^>]*>(.*?)</x:row>", Pattern.DOTALL);
        Pattern cellPattern = Pattern.compile("<x:c[^>]*r=\\\"([A-Z]+)\\d+\\\"[^>]*>(.*?)</x:c>", Pattern.DOTALL);
        Matcher rows = rowPattern.matcher(xml);
        while (rows.find()) {
            String row = rows.group(1);
            Matcher cells = cellPattern.matcher(row);
            String name = "", latin = "", family = "", level = "";
            while (cells.find()) {
                String column = cells.group(1);
                Matcher value = Pattern.compile("<x:v>(.*?)</x:v>", Pattern.DOTALL).matcher(cells.group(2));
                String text = value.find() ? Html.fromHtml(value.group(1)).toString() : "";
                if ("B".equals(column)) name = text;
                else if ("C".equals(column)) latin = text;
                else if ("D".equals(column)) family = text;
                else if ("E".equals(column)) level = text;
            }
            if (!name.isEmpty() && !"中文名".equals(name)) animals.add(new Animal(name, latin, family, level));
        }
    }

    private void renderAnimals(LinearLayout results, String query) {
        if (animals.isEmpty()) return;
        results.removeAllViews();
        String keyword = query == null ? "" : query.trim().toLowerCase();
        int count = 0;
        for (Animal animal : animals) {
            String all = (animal.name + animal.latin + animal.family + animal.level).toLowerCase();
            if (!keyword.isEmpty() && !all.contains(keyword)) continue;
            TextView row = label(animal.name + "  " + animal.level + "\n" + animal.family + "\n" + animal.latin, 14);
            row.setBackgroundColor(count % 2 == 0 ? 0xffffffff : 0xfff8fafc);
            results.addView(row, new LinearLayout.LayoutParams(-1, -2));
            if (++count >= 100) break;
        }
        if (count == 0) results.addView(label("没有找到匹配记录", 15));
    }

    private void showRecords() {
        title.setText("我的鸟类记录（本地）");
        content.removeAllViews();
        EditText bird = new EditText(this); bird.setHint("鸟名");
        EditText location = new EditText(this); location.setHint("观察地点");
        EditText date = new EditText(this); date.setHint("日期，例如 2026-08-03");
        EditText notes = new EditText(this); notes.setHint("备注（可选）");
        Button save = new Button(this); save.setText("保存记录");
        Button export = new Button(this); export.setText("导出全部记录");
        content.addView(bird); content.addView(location); content.addView(date); content.addView(notes); content.addView(save); content.addView(export);
        LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); content.addView(list);
        renderRecords(list);
        save.setOnClickListener(v -> {
            if (bird.getText().toString().trim().isEmpty()) return;
            try {
                SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
                JSONArray records = new JSONArray(prefs.getString("records", "[]"));
                JSONObject item = new JSONObject(); item.put("bird", bird.getText().toString().trim());
                item.put("location", location.getText().toString().trim()); item.put("date", date.getText().toString().trim()); item.put("notes", notes.getText().toString().trim());
                records.put(item); prefs.edit().putString("records", records.toString()).apply();
                bird.setText(""); location.setText(""); date.setText(""); notes.setText(""); renderRecords(list);
            } catch (Exception ignored) { }
        });
        export.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.setType("text/csv"); intent.putExtra(Intent.EXTRA_TITLE, "鸟友工具箱-鸟类记录.csv");
            startActivityForResult(intent, EXPORT_RECORDS);
        });
    }

    private void renderRecords(LinearLayout list) {
        list.removeAllViews();
        try {
            JSONArray records = new JSONArray(getSharedPreferences(PREFS, MODE_PRIVATE).getString("records", "[]"));
            for (int i = records.length() - 1; i >= 0; i--) {
                JSONObject item = records.getJSONObject(i);
                list.addView(label(item.optString("bird") + "\n" + item.optString("location") + "  " + item.optString("date") + "\n" + item.optString("notes"), 15));
            }
        } catch (Exception ignored) { }
    }

    private void showPhotos() {
        title.setText("照片整理（手机本地）");
        content.removeAllViews();
        content.addView(label("选择手机中的照片后，可在系统文件管理器中整理到目标文件夹。完整的按日期自动分类功能正在接入。", 16));
        Button pick = new Button(this); pick.setText("选择照片"); content.addView(pick);
        pick.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("image/*"); intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true); intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, PICK_IMAGES);
        });
    }

    private void showConfig() {
        title.setText("本地配置");
        content.removeAllViews();
        content.addView(label("当前为独立安卓版，记录、收藏和配置均保存在手机本地，不使用 MySQL/Oracle 数据库。", 16));
        EditText nickname = new EditText(this);
        nickname.setHint("你的昵称（可选）");
        nickname.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString("nickname", ""));
        Button save = new Button(this); save.setText("保存配置"); content.addView(nickname); content.addView(save);
        save.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("nickname", nickname.getText().toString().trim()).apply();
            Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        });
    }

    private void showBirdSearch() {
        title.setText("联网观鸟查询");
        content.removeAllViews();
        EditText bird = new EditText(this); bird.setHint("鸟名，例如：白鹭");
        EditText province = new EditText(this); province.setHint("省份"); province.setText("北京市");
        EditText city = new EditText(this); city.setHint("城市（可选）");
        EditText district = new EditText(this); district.setHint("区县（可选）");
        EditText start = new EditText(this); start.setHint("开始日期 2026-08-01");
        EditText end = new EditText(this); end.setHint("结束日期 2026-08-03");
        Button search = new Button(this); search.setText("查询公开观鸟记录");
        TextView message = label("数据来自 birdreport.cn，查询需要手机联网。", 13);
        LinearLayout results = new LinearLayout(this); results.setOrientation(LinearLayout.VERTICAL);
        content.addView(bird); content.addView(province); content.addView(city); content.addView(district);
        content.addView(start); content.addView(end); content.addView(search); content.addView(message); content.addView(results);
        search.setOnClickListener(v -> {
            String name = bird.getText().toString().trim();
            if (name.isEmpty()) { message.setText("请输入鸟名"); return; }
            search.setEnabled(false); message.setText("正在查询，请稍候……"); results.removeAllViews();
            new Thread(() -> {
                try {
                    JSONArray found = queryBirdReport(name, province.getText().toString().trim(), city.getText().toString().trim(), district.getText().toString().trim(), start.getText().toString().trim(), end.getText().toString().trim());
                    runOnUiThread(() -> {
                        search.setEnabled(true); message.setText("查询完成，共 " + found.length() + " 条记录");
                        for (int i = 0; i < found.length(); i++) {
                            JSONObject item = found.optJSONObject(i);
                            if (item == null) continue;
                            String text = first(item, "report_no", "reportNo", "record_no", "reportno") + "\n" +
                                    first(item, "observation_location", "pointname", "point_name", "location") + "\n" +
                                    "鸟名：" + first(item, "taxon_name", "taxonname") + "  数量：" + first(item, "bird_count", "count", "number") + "\n" +
                                    first(item, "observation_time", "record_time", "time", "date");
                            results.addView(label(text, 14));
                        }
                        if (found.length() == 0) results.addView(label("没有找到匹配记录", 15));
                    });
                } catch (Exception error) {
                    runOnUiThread(() -> { search.setEnabled(true); message.setText("查询失败：" + error.getMessage()); });
                }
            }).start();
        });
    }

    private JSONArray queryBirdReport(String birdName, String province, String city, String district, String start, String end) throws Exception {
        if (province.isEmpty()) throw new IllegalArgumentException("省份不能为空");
        if (start.isEmpty() || end.isEmpty()) throw new IllegalArgumentException("请填写开始和结束日期");
        JSONArray found = new JSONArray();
        for (int outside = 0; outside <= 1; outside++) {
            Map<String, String> params = new TreeMap<>();
            params.put("startTime", start); params.put("endTime", end); params.put("province", province);
            params.put("state", "2"); params.put("version", "CH4"); params.put("mode", "0");
            params.put("outside_type", String.valueOf(outside)); params.put("page", "1"); params.put("limit", "1500");
            if (!city.isEmpty()) params.put("city", city);
            if (!district.isEmpty()) params.put("district", district);
            JSONObject response = postBirdReport("front/record/search/page", params);
            int code = response.optInt("code", -1);
            if (code != 0) {
                if (code == 405 || code == 505) throw new IllegalStateException("观鸟数据中心要求验证码，请稍后重试");
                throw new IllegalStateException(response.optString("msg", "数据中心返回错误 code=" + code));
            }
            JSONArray rows = decryptRows(response.optString("data"));
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                if (row == null) continue;
                String taxon = first(row, "taxon_name", "taxonname");
                if (taxon.contains(birdName)) found.put(row);
            }
        }
        return found;
    }

    private JSONObject postBirdReport(String path, Map<String, String> params) throws Exception {
        String plain = canonicalJson(params);
        String requestId = java.util.UUID.randomUUID().toString().replace("-", "");
        String timestamp = String.valueOf(System.currentTimeMillis());
        String sign = md5(plain + requestId + timestamp);
        PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.decode(BIRD_PUBLIC_KEY, Base64.DEFAULT)));
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding"); rsa.init(Cipher.ENCRYPT_MODE, key);
        byte[] plainBytes = plain.getBytes(StandardCharsets.UTF_8); ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
        // RSA doFinal() cannot be mixed with update() on all Android providers; encrypt each block explicitly.
        encrypted.reset();
        for (int offset = 0; offset < plainBytes.length; offset += 117) {
            byte[] block = new byte[Math.min(117, plainBytes.length - offset)];
            System.arraycopy(plainBytes, offset, block, 0, block.length); encrypted.write(rsa.doFinal(block));
        }
        HttpURLConnection connection = (HttpURLConnection) new URL("https://api.birdreport.cn/" + path).openConnection();
        connection.setRequestMethod("POST"); connection.setConnectTimeout(20000); connection.setReadTimeout(30000); connection.setDoOutput(true);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0"); connection.setRequestProperty("Referer", "https://www.birdreport.cn/home/search/page.html");
        connection.setRequestProperty("Origin", "https://www.birdreport.cn"); connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        connection.setRequestProperty("timestamp", timestamp);
        connection.setRequestProperty("requestId", requestId);
        connection.setRequestProperty("sign", sign);
        byte[] body = Base64.encode(encrypted.toByteArray(), Base64.NO_WRAP);
        OutputStream output = connection.getOutputStream(); output.write(body); output.close();
        InputStream stream = connection.getResponseCode() >= 400 ? connection.getErrorStream() : connection.getInputStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)); StringBuilder text = new StringBuilder(); String line;
        while ((line = reader.readLine()) != null) text.append(line); reader.close(); connection.disconnect();
        return new JSONObject(text.toString());
    }

    private JSONArray decryptRows(String encoded) throws Exception {
        byte[] encrypted = Base64.decode(encoded, Base64.DEFAULT);
        Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec("C8EB5514AF5ADDB94B2207B08C66601C".getBytes(StandardCharsets.UTF_8), "AES"), new IvParameterSpec("55DD79C6F04E1A67".getBytes(StandardCharsets.UTF_8)));
        return new JSONArray(new String(aes.doFinal(encrypted), StandardCharsets.UTF_8));
    }

    private String canonicalJson(Map<String, String> params) throws Exception {
        StringBuilder json = new StringBuilder("{"); boolean first = true;
        for (String key : params.keySet()) {
            if (!first) json.append(','); first = false;
            String encodedValue = URLEncoder.encode(params.get(key), "UTF-8").replace("%7E", "~");
            json.append(JSONObject.quote(key)).append(':').append(JSONObject.quote(encodedValue));
        }
        return json.append('}').toString();
    }

    private String md5(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)); StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format("%02x", b & 0xff)); return out.toString();
    }

    private static String first(JSONObject item, String... keys) {
        for (String key : keys) if (item.has(key) && !item.isNull(key)) return item.optString(key, "-");
        return "-";
    }

    private static final String BIRD_PUBLIC_KEY =
            "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQCvxXa98E1uWXnBzXkS2yHUfnBM6n3PCwLdfIox03T91joBvjtoDqiQ5x3t" +
            "TOfpHs3LtiqMMEafls6b0YWtgB1dse1W5m+FpeusVkCOkQxB4SZDH6tuerIknnmB/Hsq5wgEkIvO5Pff9biig6AyoAkdWp" +
            "Sek/1/B7zYIepYY0lxKQIDAQAB";

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_IMAGES && resultCode == RESULT_OK && data != null) {
            int count = data.getClipData() == null ? 1 : data.getClipData().getItemCount();
            Toast.makeText(this, "已选择 " + count + " 张照片", Toast.LENGTH_SHORT).show();
        }
        if (requestCode == EXPORT_RECORDS && resultCode == RESULT_OK && data != null && data.getData() != null) {
            try {
                JSONArray records = new JSONArray(getSharedPreferences(PREFS, MODE_PRIVATE).getString("records", "[]"));
                StringBuilder csv = new StringBuilder("鸟名,地点,日期,备注\n");
                for (int i = 0; i < records.length(); i++) {
                    JSONObject item = records.getJSONObject(i);
                    csv.append(csvCell(item.optString("bird"))).append(',').append(csvCell(item.optString("location"))).append(',')
                            .append(csvCell(item.optString("date"))).append(',').append(csvCell(item.optString("notes"))).append('\n');
                }
                OutputStream output = getContentResolver().openOutputStream(data.getData());
                output.write(csv.toString().getBytes(StandardCharsets.UTF_8)); output.close();
                Toast.makeText(this, "记录已导出", Toast.LENGTH_SHORT).show();
            } catch (Exception error) { Toast.makeText(this, "导出失败：" + error.getMessage(), Toast.LENGTH_LONG).show(); }
        }
    }

    private String csvCell(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }

    private static class Animal {
        final String name, latin, family, level;
        Animal(String name, String latin, String family, String level) { this.name = name; this.latin = latin; this.family = family; this.level = level; }
    }
}
