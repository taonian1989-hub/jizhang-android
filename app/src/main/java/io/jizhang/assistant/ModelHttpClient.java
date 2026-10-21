package io.jizhang.assistant;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/** Common, bounded Chat Completions transport. No automatic POST retries or credential redirects. */
class ModelHttpClient {
    interface Listener {void delta(String text,String reasoning) throws Exception;}
    static final class Control {
        volatile boolean cancelled;
        volatile HttpURLConnection connection;
        void cancel(){cancelled=true;HttpURLConnection c=connection;if(c!=null)c.disconnect();}
    }
    static final class Result {final String finishReason;Result(String reason){finishReason=reason;}}
    HttpURLConnection open(URL url) throws IOException {return (HttpURLConnection)url.openConnection();}
    static JSONObject request(ModelConfig config,JSONArray input) throws Exception {
        return request(config,input,"");
    }
    static JSONObject request(ModelConfig config,JSONArray input,String system) throws Exception {
        if(input.length()<1||input.length()>81)throw new Exception("会话较长，请新建对话后继续");
        JSONArray messages=new JSONArray();int length=system.length();
        if(!system.isEmpty())messages.put(new JSONObject().put("role","system").put("content",system));
        for(int i=0;i<input.length();i++){
            JSONObject raw=input.getJSONObject(i);String role=raw.getString("role"),content=raw.getString("content");
            if(!role.equals(i%2==0?"user":"assistant"))throw new Exception("对话顺序不正确，请新建对话");
            if(content.isEmpty()||content.length()>65536)throw new Exception("单条内容过长或为空");
            JSONObject item=new JSONObject().put("role",role).put("content",content);
            if(role.equals("assistant")&&raw.optString("reasoning_content","").length()>0){String reasoning=raw.getString("reasoning_content");item.put("reasoning_content",reasoning);length+=reasoning.length();}
            length+=content.length();messages.put(item);
        }
        if(input.length()%2!=1||length>180000)throw new Exception("会话较长，请新建对话后继续");
        JSONObject body=new JSONObject().put("model",config.model).put("messages",messages).put("stream",true).put("max_tokens",config.maxTokens);
        if(config.provider.equals("deepseek")&&config.model.startsWith("deepseek-"))body.put("thinking",new JSONObject().put("type","enabled")).put("reasoning_effort","low");
        if(config.provider.equals("kimi")&&config.model.startsWith("kimi-k3")){body.remove("max_tokens");body.put("max_completion_tokens",config.maxTokens).put("reasoning_effort","low");}
        if(config.provider.equals("glm")&&(config.model.startsWith("glm-5.3")||config.model.startsWith("glm-5.2")))body.put("thinking",new JSONObject().put("type","enabled")).put("reasoning_effort","low");
        return body;
    }
    Result execute(ModelConfig config,JSONArray messages,Control control,Listener listener) throws Exception {
        return execute(config,messages,"",control,listener);
    }
    Result execute(ModelConfig config,JSONArray messages,String system,Control control,Listener listener) throws Exception {
        if(config.key.isEmpty())throw new Exception("请先在模型配置中填写 API Key");
        byte[] payload=request(config,messages,system).toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn=open(new URL(config.endpoint));control.connection=conn;
        try {
            if(control.cancelled)throw new InterruptedIOException("cancelled");
            conn.setConnectTimeout(15000);conn.setReadTimeout(90000);conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod("POST");conn.setDoOutput(true);conn.setFixedLengthStreamingMode(payload.length);
            conn.setRequestProperty("Authorization","Bearer "+config.key);
            conn.setRequestProperty("Content-Type","application/json; charset=utf-8");conn.setRequestProperty("Accept","text/event-stream, application/json");conn.setRequestProperty("Accept-Encoding","identity");
            try(OutputStream out=conn.getOutputStream()){out.write(payload);}
            int status=conn.getResponseCode();if(status!=200)throw new Exception(httpError(status));
            String contentType=conn.getContentType();
            try(InputStream in=conn.getInputStream()){
                if(contentType!=null&&contentType.toLowerCase().contains("text/event-stream"))return readStream(in,control,listener);
                String raw=readLimited(in,2*1024*1024);JSONObject root=new JSONObject(raw);
                JSONObject choice=root.getJSONArray("choices").getJSONObject(0),message=choice.getJSONObject("message");
                String text=string(message,"content"),reasoning=string(message,"reasoning_content");
                if(text.length()+reasoning.length()>180000)throw new Exception("模型输出过长，请调低输出上限");
                if(text.isEmpty()&&reasoning.isEmpty())throw new Exception("模型没有返回可展示内容");
                if(!control.cancelled)listener.delta(text,reasoning);
                return new Result(choice.optString("finish_reason","stop"));
            }
        }catch(org.json.JSONException e){throw new Exception("模型返回了无法识别的内容，请检查 API 地址或稍后重试");}
        finally{control.connection=null;conn.disconnect();}
    }
    static String string(JSONObject json,String key){Object value=json.opt(key);return value instanceof String?(String)value:"";}
    static String httpError(int code){
        if(code==401||code==403)return "API Key 无效，或无权使用此模型（HTTP "+code+"）";
        if(code==402)return "模型账户余额不足，请到服务商平台查看";
        if(code==429)return "模型服务限流或额度不足，请稍后手动重试";
        if(code==404)return "模型或 API 地址不存在，请检查配置";
        if(code>=300&&code<400)return "接口发生重定向，已停止发送密钥，请检查 API 地址";
        if(code==400)return "模型参数不兼容，请检查模型 ID 和输出上限";
        return "模型服务暂不可用（HTTP "+code+"），请稍后手动重试";
    }
    static Result readStream(InputStream input,Control control,Listener listener) throws Exception {
        InputStream bounded=new FilterInputStream(input){
            int size;
            @Override public int read() throws IOException {int n=super.read();if(n!=-1&&++size>4*1024*1024)throw new IOException("Response too large");return n;}
            @Override public int read(byte[] b,int off,int len) throws IOException {int n=in.read(b,off,len);if(n>0&&(size+=n)>4*1024*1024)throw new IOException("Response too large");return n;}
        };
        BufferedReader reader=new BufferedReader(new InputStreamReader(bounded,StandardCharsets.UTF_8));
        StringBuilder event=new StringBuilder();String line,finish="";int received=0,output=0;boolean sawContent=false;
        while((line=reader.readLine())!=null){
            if(control.cancelled)throw new InterruptedIOException("cancelled");
            received+=line.length();if(line.length()>262144||received>4*1024*1024)throw new Exception("模型响应过大");
            if(line.startsWith("\ufeff"))line=line.substring(1);
            if(line.isEmpty()){
                if(event.length()==0)continue;String data=event.toString();event.setLength(0);
                if(data.equals("[DONE]")){if(!sawContent)throw new Exception("模型没有返回可展示内容");return new Result(finish.isEmpty()?"stop":finish);}
                JSONObject root=new JSONObject(data);
                if(root.has("error"))throw new Exception("模型流中返回错误，请检查配置后手动重试");
                JSONArray choices=root.optJSONArray("choices");if(choices==null||choices.length()==0)continue;
                JSONObject choice=choices.getJSONObject(0),delta=choice.optJSONObject("delta");
                if(delta!=null){String text=string(delta,"content"),reasoning=string(delta,"reasoning_content");output+=text.length()+reasoning.length();
                    if(output>180000)throw new Exception("模型输出过长，请调低输出上限");
                    if(!text.isEmpty()||!reasoning.isEmpty()){sawContent=true;listener.delta(text,reasoning);}}
                String reason=string(choice,"finish_reason");if(!reason.isEmpty())finish=reason;
            }else if(line.startsWith("data:")){if(event.length()>0)event.append('\n');event.append(line.substring(5).trim());}
        }
        if(sawContent&&!finish.isEmpty())return new Result(finish);
        throw new Exception("连接提前中断，已保留收到的内容，可手动重试");
    }
    static String readLimited(InputStream in,int max) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=in.read(buffer))!=-1){if(bytes.size()+n>max)throw new Exception("模型响应过大");bytes.write(buffer,0,n);}
        return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
    }
}
