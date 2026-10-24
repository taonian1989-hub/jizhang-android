package io.jizhang.assistant;

import com.sun.net.httpserver.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

public class SearchClientTest {
    static int passed;
    static String body, auth, requested;
    static final AtomicInteger requests = new AtomicInteger(), redirected = new AtomicInteger();
    static void ok(boolean v, String name) { if (!v) throw new AssertionError(name); passed++; System.out.println("PASS " + name); }
    interface Attempt { void run() throws Exception; }
    static void fails(Attempt f, String name) throws Exception {
        try { f.run(); throw new AssertionError(name); } catch (AssertionError e) { throw e; }
        catch (Exception expected) { passed++; System.out.println("PASS " + name); }
    }
    static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static void reply(HttpExchange exchange, int status, String text) throws IOException {
        body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        auth = exchange.getRequestHeaders().getFirst("Authorization"); requests.incrementAndGet();
        byte[] response = bytes(text); exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, response.length); try (OutputStream out = exchange.getResponseBody()) { out.write(response); }
    }
    static SearchClient client(int port, String route) {
        return new SearchClient() { @Override HttpURLConnection open(URL url) throws IOException {
            requested = url.toString(); return (HttpURLConnection) new URL("http://127.0.0.1:" + port + route).openConnection();
        }};
    }
    static ModelConfig config(String provider, String endpoint, String key) throws Exception {
        return new ModelConfig(provider, endpoint, "test-model", key, 8192, false);
    }
    public static void main(String[] args) throws Exception {
        String endpoint = ModelConfig.defaults("deepseek").endpoint;
        ModelConfig config = config("deepseek", endpoint, "LOCAL_SEARCH_TEST_KEY");
        ok(SearchClient.endpoint("deepseek").equals("https://api.deepseek.com/v1/tools/search"), "DeepSeek official Search API route");
        JSONObject request = SearchClient.request("deepseek", "记账 site:example.com");
        ok(request.getString("query").equals("记账 site:example.com") && request.getInt("count") == 8 && !request.getBoolean("need_content"), "documented DeepSeek query count content fields");
        ok(!request.has("search_query") && !request.has("search_engine"), "GLM request fields removed");
        ok(SearchClient.request("deepseek", "😀".repeat(128)).getString("query").codePointCount(0, 256) == 128, "Unicode search length counts code points");
        fails(() -> SearchClient.request("deepseek", "😀".repeat(129)), "reject overlong Unicode search");
        fails(() -> SearchClient.request("deepseek", "\u3000\u00a0\t"), "reject Unicode whitespace-only search");
        fails(() -> SearchClient.request("deepseek", ""), "reject empty search");
        fails(() -> SearchClient.endpoint("glm"), "no GLM fallback route");
        ok(SearchClient.officialCredentials(config) && SearchClient.officialCredentials(config("deepseek", "https://api.deepseek.com/v1/chat/completions", "LOCAL_TEST")), "both official chat URL forms permit own credentials");
        JSONObject sample = new JSONObject().put("results", new JSONArray().put(new JSONObject().put("url", "https://example.com/a").put("title", "来源").put("summary", "给模型的中文摘要").put("snippet", "短摘要").put("site_name", "Example").put("date", "2026-10-24T00:00:00Z")));
        JSONArray normalized = SearchClient.normalize("deepseek", sample);
        ok(normalized.getJSONObject(0).getString("snippet").equals("给模型的中文摘要"), "prefer long summary over display snippet");
        ok(normalized.getJSONObject(0).getString("site").equals("Example") && normalized.getJSONObject(0).getString("date").startsWith("2026-10-24"), "source site and date retained");
        JSONArray nullable = SearchClient.normalize("deepseek", new JSONObject().put("results", new JSONArray().put(new JSONObject().put("url", "https://example.com/null").put("site_name", JSONObject.NULL).put("date", JSONObject.NULL).put("summary", "").put("snippet", "短摘要"))));
        ok(nullable.getJSONObject(0).getString("site").equals("example.com") && nullable.getJSONObject(0).getString("date").isEmpty(), "nullable fields never show literal null");
        ok(nullable.getJSONObject(0).getString("snippet").equals("短摘要"), "short snippet fallback");
        JSONArray unsafe = new JSONArray().put(new JSONObject().put("url", "javascript:alert(1)")).put(new JSONObject().put("url", "https://u:p@example.com/a")).put(sample.getJSONArray("results").getJSONObject(0)).put(sample.getJSONArray("results").getJSONObject(0));
        ok(SearchClient.normalize("deepseek", new JSONObject().put("results", unsafe)).length() == 1, "unsafe URLs and duplicates filtered");
        JSONArray many = new JSONArray(); for (int i = 0; i < 20; i++) many.put(new JSONObject().put("url", "https://example.com/" + i).put("summary", "中".repeat(8000)));
        JSONArray bounded = SearchClient.normalize("deepseek", new JSONObject().put("results", many));
        ok(bounded.length() == 8 && bounded.getJSONObject(0).getString("snippet").length() == 6000, "bounded results and snippets");
        ok(SearchClient.normalize("deepseek", new JSONObject().put("results", new JSONArray())).length() == 0, "zero results stay explicitly empty");
        fails(() -> SearchClient.normalize("deepseek", new JSONObject().put("search_result", new JSONArray())), "reject old GLM response schema");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", x -> reply(x, 200, sample.toString()));
        server.createContext("/auth", x -> reply(x, 401, "LOCAL_SEARCH_TEST_KEY"));
        server.createContext("/rate", x -> reply(x, 429, "rate"));
        server.createContext("/bad", x -> reply(x, 200, "LOCAL_SEARCH_TEST_KEY invalid JSON"));
        server.createContext("/redirect", x -> { x.getResponseHeaders().set("Location", "/steal"); reply(x, 302, "{}"); });
        server.createContext("/steal", x -> { redirected.incrementAndGet(); reply(x, 200, "{}"); });
        server.start(); int port = server.getAddress().getPort();
        try {
            JSONArray result = client(port, "/ok").execute(config, "中文记账问题", new ModelHttpClient.Control());
            ok(result.length() == 1 && requested.equals("https://api.deepseek.com/v1/tools/search"), "HTTP transport dispatches DeepSeek official endpoint");
            ok(auth.equals("Bearer LOCAL_SEARCH_TEST_KEY"), "only local mock receives test bearer credential");
            JSONObject sent = new JSONObject(body);
            ok(sent.getString("query").equals("中文记账问题") && sent.getInt("count") == 8 && !sent.getBoolean("need_content"), "UTF8 request and documented payload reach transport");
            int before = requests.get();
            fails(() -> client(port, "/ok").execute(config("deepseek", endpoint, ""), "q", new ModelHttpClient.Control()), "missing DeepSeek key stops before network");
            fails(() -> client(port, "/ok").execute(config("deepseek", "https://proxy.example/chat/completions", "PROXY_TEST_KEY"), "q", new ModelHttpClient.Control()), "proxy credential never sent to DeepSeek");
            fails(() -> client(port, "/ok").execute(config("glm", ModelConfig.defaults("glm").endpoint, "GLM_TEST_KEY"), "q", new ModelHttpClient.Control()), "GLM credential never sent to DeepSeek");
            ok(requests.get() == before, "invalid credential origins produce no POST");
            try { client(port, "/auth").execute(config, "q", new ModelHttpClient.Control()); throw new AssertionError(); }
            catch (Exception e) { ok(e.getMessage().contains("DeepSeek") && !e.getMessage().contains(config.key), "auth errors identify DeepSeek without exposing body or key"); }
            before = requests.get(); fails(() -> client(port, "/rate").execute(config, "q", new ModelHttpClient.Control()), "quota error surfaced");
            ok(requests.get() == before + 1, "no automatic paid search retry");
            fails(() -> client(port, "/redirect").execute(config, "q", new ModelHttpClient.Control()), "credential redirect rejected");
            ok(redirected.get() == 0, "redirect destination receives no credentials");
            ModelHttpClient.Control cancelled = new ModelHttpClient.Control(); cancelled.cancelled = true; before = requests.get();
            fails(() -> client(port, "/ok").execute(config, "q", cancelled), "cancelled search stops before transmission");
            ok(requests.get() == before && cancelled.connection == null, "cancelled search releases connection");
            ModelHttpClient.Control failed = new ModelHttpClient.Control();
            try { client(port, "/bad").execute(config, "q", failed); throw new AssertionError(); }
            catch (Exception e) { ok(!e.getMessage().contains(config.key) && failed.connection == null, "malformed response is sanitized and connection released"); }
        } finally { server.stop(0); }
        System.out.println("TOTAL PASSED: " + passed);
    }
}
