package io.jizhang.assistant;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;

/** One explicit search request, normalized to bounded evidence records. */
class SearchClient {
    HttpURLConnection open(URL u)throws IOException{return (HttpURLConnection)u.openConnection();}
    static String endpoint(String provider)throws Exception {
        if(provider.equals("glm"))return "https://open.bigmodel.cn/api/paas/v4/web_search";
        throw new Exception("不支持的搜索服务");
    }
    static JSONObject request(String provider,String query)throws Exception {
        if(query.trim().isEmpty()||query.codePointCount(0,query.length())>128)throw new Exception("搜索词应在 1—128 字之间");
        if(provider.equals("glm"))return new JSONObject().put("search_query",query).put("search_engine","search_std").put("count",8).put("content_size","high");
        throw new Exception("不支持的搜索服务");
    }
    static String bounded(String s,int max){return s.length()>max?s.substring(0,max):s;}
    static JSONArray normalize(String provider,JSONObject root)throws Exception {
        String field=provider.equals("deepseek")?"results":provider.equals("kimi")?"search_results":"search_result";
        JSONArray items=root.optJSONArray(field);if(items==null)throw new Exception("搜索响应格式无法识别，请检查服务权限");
        JSONArray result=new JSONArray();HashSet<String> seen=new HashSet<>();
        for(int i=0;i<items.length()&&result.length()<8;i++){
            JSONObject a=items.getJSONObject(i);String url=a.optString(provider.equals("glm")?"link":"url","");
            URI u;try{u=new URI(url);}catch(Exception e){continue;}
            if(!("https".equals(u.getScheme())||"http".equals(u.getScheme()))||u.getHost()==null||u.getRawUserInfo()!=null||url.length()>4096||!seen.add(url))continue;
            String title=a.optString("title",u.getHost());String content=a.optString(provider.equals("glm")?"content":provider.equals("kimi")?"snippet":"summary","");
            if(content.isEmpty())content=a.optString("snippet","");
            result.put(new JSONObject().put("title",bounded(title,300)).put("url",url).put("site",bounded(a.optString(provider.equals("glm")?"media":"site_name",u.getHost()),100)).put("date",bounded(a.optString(provider.equals("glm")?"publish_date":"date",""),50)).put("snippet",bounded(content,6000)));
        }
        return result;
    }
    JSONArray execute(ModelConfig config,String query,ModelHttpClient.Control control)throws Exception {
        if(config.key.isEmpty())throw new Exception("请先配置搜索服务商的 API Key");
        // A credential configured for a proxy must never be sent to an unrelated official server.
        if(!config.endpoint.equals(ModelConfig.defaults(config.provider).endpoint))throw new Exception("搜索服务需使用官方接口配置，请选择另一已配置的官方搜索服务商");
        byte[] payload=request(config.provider,query).toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection c=open(new URL(endpoint(config.provider)));control.connection=c;
        try{
            if(control.cancelled)throw new InterruptedIOException("cancelled");
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(45000);c.setRequestMethod("POST");c.setDoOutput(true);c.setFixedLengthStreamingMode(payload.length);
            c.setRequestProperty("Authorization","Bearer "+config.key);c.setRequestProperty("Content-Type","application/json");
            try(OutputStream out=c.getOutputStream()){out.write(payload);}
            if(c.getResponseCode()!=200)throw new Exception("联网搜索失败："+ModelHttpClient.httpError(c.getResponseCode()));
            try(InputStream in=c.getInputStream()){return normalize(config.provider,new JSONObject(ModelHttpClient.readLimited(in,2*1024*1024)));}
        }catch(JSONException e){throw new Exception("搜索响应格式无法识别");}finally{control.connection=null;c.disconnect();}
    }
}
