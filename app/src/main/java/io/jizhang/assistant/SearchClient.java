package io.jizhang.assistant;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

/** DeepSeek's official, independent Search API. No implicit provider fallback. */
class SearchClient {
    HttpURLConnection open(URL url) throws IOException { return (HttpURLConnection) url.openConnection(); }
    static String endpoint(String provider) throws Exception {
        if ("deepseek".equals(provider)) return "https://api.deepseek.com/v1/tools/search";
        throw new Exception("联网搜索使用 DeepSeek，请配置 DeepSeek 官方 API Key");
    }
    static JSONObject request(String provider, String query) throws Exception {
        endpoint(provider);
        if (query == null || query.codePointCount(0, query.length()) > 128
            || query.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c)))
            throw new Exception("搜索词应在 1—128 字之间");
        return new JSONObject().put("query", query).put("count", 8).put("need_content", false);
    }
    static boolean officialCredentials(ModelConfig config) {
        return "deepseek".equals(config.provider) && (config.endpoint.equals("https://api.deepseek.com/chat/completions")
            || config.endpoint.equals("https://api.deepseek.com/v1/chat/completions"));
    }
    private static String string(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof String ? (String) value : "";
    }
    static String bounded(String text, int limit) { return text.length() > limit ? text.substring(0, limit) : text; }
    static JSONArray normalize(String provider, JSONObject root) throws Exception {
        endpoint(provider);
        JSONArray items = root.optJSONArray("results");
        if (items == null) throw new Exception("DeepSeek 搜索响应格式无法识别，请检查服务权限");
        JSONArray result = new JSONArray();
        HashSet<String> seen = new HashSet<>();
        for (int i = 0; i < items.length() && result.length() < 8; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            String url = string(item, "url");
            URI uri;
            try { uri = new URI(url); } catch (Exception ignored) { continue; }
            if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getRawUserInfo() != null || url.length() > 4096 || !seen.add(url)) continue;
            String title = string(item, "title"), site = string(item, "site_name"), summary = string(item, "summary");
            if (title.isEmpty()) title = uri.getHost();
            if (site.isEmpty()) site = uri.getHost();
            if (summary.isEmpty()) summary = string(item, "snippet");
            result.put(new JSONObject().put("title", bounded(title, 300)).put("url", url)
                .put("site", bounded(site, 100)).put("date", bounded(string(item, "date"), 50))
                .put("snippet", bounded(summary, 6000)));
        }
        return result;
    }
    JSONArray execute(ModelConfig config, String query, ModelHttpClient.Control control) throws Exception {
        if (config.key.isEmpty()) throw new Exception("请先在模型连接中配置 DeepSeek 官方 API Key，再使用联网搜索");
        if (!officialCredentials(config)) throw new Exception("联网搜索需要 DeepSeek 官方接口凭据；自定义代理 Key 不会发送到官方搜索");
        byte[] payload = request(config.provider, query).toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = open(new URL(endpoint(config.provider)));
        control.connection = connection;
        try {
            if (control.cancelled) throw new InterruptedIOException("cancelled");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(15000); connection.setReadTimeout(45000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setFixedLengthStreamingMode(payload.length);
            connection.setRequestProperty("Authorization", "Bearer " + config.key);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream out = connection.getOutputStream()) { out.write(payload); }
            int status = connection.getResponseCode();
            if (status != 200) throw new Exception("DeepSeek 联网搜索失败：" + ModelHttpClient.httpError(status));
            try (InputStream in = connection.getInputStream()) {
                return normalize(config.provider, new JSONObject(ModelHttpClient.readLimited(in, 2 * 1024 * 1024)));
            }
        } catch (JSONException e) { throw new Exception("DeepSeek 搜索响应格式无法识别"); }
        catch (IOException e) {
            if (control.cancelled) throw e;
            throw new Exception("DeepSeek 联网搜索连接失败或超时，请检查网络后手动重试");
        } finally { control.connection = null; connection.disconnect(); }
    }
}
