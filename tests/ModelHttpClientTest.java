package io.jizhang.assistant;

import com.sun.net.httpserver.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.*;

public class ModelHttpClientTest {
    static int passed;
    static String body,auth;static final AtomicInteger requests=new AtomicInteger(),redirectHits=new AtomicInteger();
    static void ok(boolean value,String name){if(!value)throw new AssertionError(name);passed++;System.out.println("PASS "+name);}
    interface Attempt{void run() throws Exception;}
    static void fails(Attempt task,String name)throws Exception{try{task.run();throw new AssertionError(name);}catch(AssertionError e){throw e;}catch(Exception expected){passed++;System.out.println("PASS "+name);}}
    static JSONArray input()throws Exception{return new JSONArray().put(new JSONObject().put("role","user").put("content","你好，中文测试"));}
    static byte[] utf(String text){return text.getBytes(StandardCharsets.UTF_8);}
    static void reply(HttpExchange x,int code,String type,String text)throws IOException{
        body=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);auth=x.getRequestHeaders().getFirst("Authorization");requests.incrementAndGet();
        x.getResponseHeaders().set("Content-Type",type);byte[] bytes=utf(text);x.sendResponseHeaders(code,bytes.length);try(OutputStream o=x.getResponseBody()){o.write(bytes);}
    }
    static ModelHttpClient client(int port,String route){return new ModelHttpClient(){@Override HttpURLConnection open(URL ignored)throws IOException{return (HttpURLConnection)new URL("http://127.0.0.1:"+port+route).openConnection();}};}
    public static void main(String[] args)throws Exception{
        ModelConfig d=ModelConfig.defaults("deepseek");
        ok(d.endpoint.startsWith("https://api.deepseek.com/"),"DeepSeek official endpoint");
        ok(ModelConfig.defaults("kimi").endpoint.equals("https://api.moonshot.cn/v1/chat/completions"),"Kimi official endpoint");
        ok(ModelConfig.defaults("glm").endpoint.equals("https://open.bigmodel.cn/api/paas/v4/chat/completions"),"GLM official endpoint");
        fails(()->new ModelConfig("deepseek","http://example.com/chat/completions","model","test",8192,true),"Reject plaintext model endpoint");
        fails(()->new ModelConfig("deepseek","https://u:p@example.com/chat/completions","model","test",8192,true),"Reject endpoint credentials");
        fails(()->new ModelConfig("deepseek","https://example.com/chat/completions?token=secret","model","test",8192,true),"Reject endpoint query tokens");
        fails(()->new ModelConfig("other",d.endpoint,d.model,"test",8192,true),"Reject unknown provider");
        fails(()->new ModelConfig("deepseek",d.endpoint,d.model,"test\r\nInjected: header",8192,true),"Reject API key header injection");
        fails(()->new ModelConfig("deepseek",d.endpoint,d.model,"test",999999,true),"Bound output limit");
        ModelConfig config=new ModelConfig("deepseek",d.endpoint,d.model,"LOCAL_TEST_KEY_NOT_REAL",8192,true);
        JSONObject payload=ModelHttpClient.request(config,input());
        ok(payload.getBoolean("stream")&&payload.getString("model").equals(d.model),"Streaming request uses configured model");
        ok(payload.getJSONObject("thinking").getString("type").equals("enabled"),"DeepSeek requests provider reasoning");
        JSONObject kimi=ModelHttpClient.request(ModelConfig.defaults("kimi"),input());
        ok(kimi.getString("reasoning_effort").equals("low")&&kimi.getInt("max_completion_tokens")==8192&&!kimi.has("max_tokens"),"Kimi K3 uses documented reasoning and completion-length parameters");
        ok(ModelHttpClient.request(ModelConfig.defaults("glm"),input()).getJSONObject("thinking").getString("type").equals("enabled"),"GLM preset enables provider thinking");
        ok(!config.publicJson().has("key")&&config.publicJson().getBoolean("configured"),"Metadata never returns the API key");
        ModelConfig hidden=new ModelConfig("deepseek",d.endpoint,d.model,"LOCAL_TEST_KEY_NOT_REAL",8192,false);
        ok(!hidden.publicJson().getBoolean("showReasoning"),"Reasoning display switch is provider metadata");
        ok(ModelHttpClient.request(config,input()).toString().equals(ModelHttpClient.request(hidden,input()).toString()),"Display switch does not alter provider reasoning or billing parameters");
        JSONArray history=new JSONArray().put(new JSONObject().put("role","user").put("content","first")).put(new JSONObject().put("role","assistant").put("content","answer").put("reasoning_content","provider trace")).put(new JSONObject().put("role","user").put("content","next"));
        ok(ModelHttpClient.request(config,history).getJSONArray("messages").getJSONObject(1).getString("reasoning_content").equals("provider trace"),"Preserve returned reasoning for same-provider context");
        fails(()->ModelHttpClient.request(config,new JSONArray().put(new JSONObject().put("role","system").put("content","bad"))),"Reject injected system role");
        fails(()->ModelHttpClient.request(config,new JSONArray().put(input().getJSONObject(0)).put(input().getJSONObject(0))),"Reject non-alternating history");
        String stream=": heartbeat\n\ndata: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先分析中文问题。\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"最终回答\"}}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/ok",x->reply(x,200,"text/event-stream",stream));
        server.createContext("/json",x->reply(x,200,"application/json","{\"choices\":[{\"message\":{\"content\":\"完整正文\",\"reasoning_content\":\"推理文本\"},\"finish_reason\":\"length\"}]}"));
        server.createContext("/auth",x->reply(x,401,"application/json","{\"error\":\"LOCAL_TEST_KEY_NOT_REAL\"}"));
        server.createContext("/rate",x->reply(x,429,"application/json","{\"error\":\"rate limit\"}"));
        server.createContext("/redirect",x->{x.getResponseHeaders().set("Location","/steal");reply(x,302,"application/json","{}");});
        server.createContext("/steal",x->{redirectHits.incrementAndGet();reply(x,200,"application/json","{}");});
        server.createContext("/bad",x->reply(x,200,"text/event-stream","data: LOCAL_TEST_KEY_NOT_REAL malformed\n\n"));
        server.start();int port=server.getAddress().getPort();
        try{
            StringBuilder text=new StringBuilder(),thought=new StringBuilder();
            ModelHttpClient.Result result=client(port,"/ok").execute(config,input(),new ModelHttpClient.Control(),(a,b)->{text.append(a);thought.append(b);});
            ok(text.toString().equals("最终回答")&&thought.toString().equals("先分析中文问题。"),"SSE separates UTF-8 reasoning and answer");
            ok(result.finishReason.equals("stop"),"SSE terminal reason retained");
            ok(auth.equals("Bearer LOCAL_TEST_KEY_NOT_REAL")&&new JSONObject(body).getString("model").equals(d.model),"Authenticated HTTP payload reaches local mock only");
            text.setLength(0);thought.setLength(0);
            result=client(port,"/json").execute(config,input(),new ModelHttpClient.Control(),(a,b)->{text.append(a);thought.append(b);});
            ok(text.toString().equals("完整正文")&&thought.toString().equals("推理文本")&&result.finishReason.equals("length"),"Handle nonstream JSON compatibility and truncation");
            try{client(port,"/auth").execute(config,input(),new ModelHttpClient.Control(),(a,b)->{});throw new AssertionError();}catch(Exception e){ok(!e.getMessage().contains(config.key)&&e.getMessage().contains("API Key"),"Authentication errors do not echo server body or key");}
            int before=requests.get();fails(()->client(port,"/rate").execute(config,input(),new ModelHttpClient.Control(),(a,b)->{}),"Rate limit surfaces as an error");
            ok(requests.get()==before+1,"No automatic paid POST retries");
            fails(()->client(port,"/redirect").execute(config,input(),new ModelHttpClient.Control(),(a,b)->{}),"Reject credential-bearing redirect");
            ok(redirectHits.get()==0,"Redirect target never receives the request");
            try{client(port,"/bad").execute(config,input(),new ModelHttpClient.Control(),(a,b)->{});throw new AssertionError();}catch(Exception e){ok(!e.getMessage().contains(config.key),"Malformed provider JSON errors are sanitized");}
            ModelHttpClient.Control cancelled=new ModelHttpClient.Control();cancelled.cancelled=true;
            before=requests.get();fails(()->client(port,"/ok").execute(config,input(),cancelled,(a,b)->{}),"Cancelled request is stopped before transmission");
            ok(requests.get()==before,"Cancellation does not send a new POST");
            ModelHttpClient.Control during=new ModelHttpClient.Control();AtomicInteger chunks=new AtomicInteger();
            fails(()->client(port,"/ok").execute(config,input(),during,(a,b)->{chunks.incrementAndGet();during.cancelled=true;}),"Cancel an active stream after its first reasoning delta");
            ok(chunks.get()==1,"Cancelled stream does not deliver subsequent answer deltas");
            fails(()->ModelHttpClient.readStream(new ByteArrayInputStream(utf("data: [DONE]\n\n")),new ModelHttpClient.Control(),(a,b)->{}),"Reject an empty model completion");
            fails(()->ModelHttpClient.readStream(new ByteArrayInputStream(utf("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n")),new ModelHttpClient.Control(),(a,b)->{}),"Detect interrupted stream without terminal marker");
            System.out.println("TOTAL PASSED: "+passed);
        }finally{server.stop(0);}
    }
}
