package io.jizhang.assistant;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** BYOK interface exposed only to bundled app assets, never to publisher pages. */
public final class ModelBridge {
    private final Activity activity;
    private final WebView web;
    private final SecretStore store;
    private final ExecutorService workers=Executors.newFixedThreadPool(2);
    private final ScheduledExecutorService deadlines=Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<String,Job> jobs=new ConcurrentHashMap<>();
    private volatile boolean closed;
    ModelBridge(Activity activity,WebView web){this.activity=activity;this.web=web;store=new SecretStore(activity);}
    private boolean id(String id){return id!=null&&id.matches("[0-9]{1,12}");}
    private void emit(JSONObject data){if(closed)return;final String json=data.toString();activity.runOnUiThread(()->{if(!closed)web.evaluateJavascript("window.onModelEvent && window.onModelEvent("+json+")",null);});}
    private JSONObject event(String kind,String id) throws Exception{return new JSONObject().put("kind",kind).put("id",id);}
    private void error(String id,String message){try{emit(event("error",id).put("message",message));}catch(Exception ignored){}}
    private JSONArray metadata() throws Exception {
        JSONArray configs=new JSONArray();
        for(String provider:ModelConfig.PROVIDERS){
            try{configs.put(store.load(provider).publicJson());}
            catch(Exception e){configs.put(ModelConfig.defaults(provider).publicJson().put("unreadable",true));}
        }
        return configs;
    }
    @JavascriptInterface public void configs(String id){if(!id(id)||closed)return;workers.execute(()->{try{emit(event("configs",id).put("configs",metadata()));}catch(Exception e){error(id,"无法读取本机模型配置");}});}
    @JavascriptInterface public void save(String id,String provider,String raw){
        if(!id(id)||closed)return;
        workers.execute(()->{try{
            if(raw==null||raw.length()>10000)throw new Exception("配置内容过长");
            JSONObject json=new JSONObject(raw);ModelConfig old;
            try{old=store.load(provider);}catch(Exception e){old=ModelConfig.defaults(provider);}
            String key=json.optString("key","").trim();
            if(key.isEmpty()){
                if(!old.endpoint.equals(json.optString("endpoint","").trim()))throw new Exception("API 地址变更后，请重新填写对应服务的 Key");
                key=old.key;
            }
            if(key.isEmpty())throw new Exception("请填写此服务商的 API Key");
            ModelConfig config=ModelConfig.fromJson(provider,json,key);store.save(config);
            emit(event("saved",id).put("provider",provider).put("config",config.publicJson()));
        }catch(Exception e){error(id,e instanceof org.json.JSONException?"配置格式无法识别":e.getMessage()==null?"配置保存失败":e.getMessage());}});
    }
    @JavascriptInterface public void remove(String id,String provider){if(!id(id)||closed)return;workers.execute(()->{try{store.remove(provider);emit(event("removed",id).put("provider",provider).put("config",ModelConfig.defaults(provider).publicJson()));}catch(Exception e){error(id,"配置未能移除");}});}
    @JavascriptInterface public void chat(String id,String provider,String raw){
        if(!id(id)||closed)return;
        final Job job;
        synchronized(jobs){if(!jobs.isEmpty()){error(id,"另一段回答尚未结束，请先停止生成");return;}job=new Job(id,provider);jobs.put(id,job);}
        job.future=workers.submit(()->{
            ScheduledFuture<?> timeout=deadlines.schedule(()->{job.control.cancel();job.finish("error","生成超时，已保留收到的内容，请手动重试","");},180,TimeUnit.SECONDS);
            try{
                if(raw==null||raw.length()>400000)throw new Exception("会话过长，请新建对话");
                ModelConfig config=store.load(provider);
                if(config.key.isEmpty())throw new Exception("请先在设置中配置所选模型的 API Key");
                JSONObject task=new JSONObject(raw);JSONArray sources=new JSONArray();
                boolean online=task.optBoolean("online",false);
                if(online){
                    emit(event("phase",id).put("message","正在使用 DeepSeek 联网搜索"));
                    String engine="deepseek";
                    sources=new SearchClient().execute(store.load(engine),task.getString("query"),job.control);
                    if(job.control.cancelled)throw new java.io.InterruptedIOException("cancelled");
                    emit(event("sources",id).put("sources",sources).put("engine",engine));
                    if(sources.length()==0)throw new Exception("本次搜索没有可用结果，请修改搜索词，或关闭联网后基于已有知识分析");
                }
                emit(event("phase",id).put("message","正在整理你的问题"));
                ModelHttpClient.Result result=new ModelHttpClient().execute(config,task.getJSONArray("messages"),LifePrompt.build(task,online,sources),job.control,(text,reasoning)->job.chunk(text,reasoning));
                if(job.control.cancelled)job.finish("cancelled","","");else job.finish("done","",result.finishReason);
            }catch(Exception e){
                if(job.control.cancelled)job.finish("cancelled","","");
                else {String message=e instanceof java.io.IOException?"连接失败或超时，已保留收到的内容，请手动重试":e.getMessage();job.finish("error",message==null?"模型请求失败":message,"");}
            }finally{timeout.cancel(false);jobs.remove(id,job);}
        });
    }
    @JavascriptInterface public void cancel(String id){Job job=jobs.get(id);if(job!=null){job.control.cancelled=true;job.finish("cancelled","","");if(job.future!=null)job.future.cancel(true);jobs.remove(id,job);workers.execute(()->job.control.cancel());}}
    @JavascriptInterface public void copyText(String text){if(text==null||text.length()>500000)return;activity.runOnUiThread(()->{ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("记账回答",text));});}
    void close(){
        closed=true;final Job[] pending=jobs.values().toArray(new Job[0]);
        for(Job job:pending)job.control.cancelled=true;
        workers.shutdownNow();deadlines.shutdownNow();
        Thread closer=new Thread(()->{for(Job job:pending)job.control.cancel();jobs.clear();},"jizhang-model-close");
        closer.setDaemon(true);closer.start();
    }
    private final class Job {
        final String id,provider;
        final ModelHttpClient.Control control=new ModelHttpClient.Control();
        final AtomicBoolean terminal=new AtomicBoolean();
        final StringBuilder pendingText=new StringBuilder(),pendingReasoning=new StringBuilder();
        long lastFlush;
        volatile Future<?> future;
        Job(String id,String provider){this.id=id;this.provider=provider;}
        synchronized void chunk(String text,String reasoning) throws Exception {
            if(terminal.get())return;pendingText.append(text);pendingReasoning.append(reasoning);
            if(System.currentTimeMillis()-lastFlush>=65||pendingText.length()+pendingReasoning.length()>2048)flush();
        }
        synchronized void flush() throws Exception {
            if(pendingText.length()+pendingReasoning.length()==0)return;
            emit(event("delta",id).put("provider",provider).put("text",pendingText.toString()).put("reasoning",pendingReasoning.toString()));
            pendingText.setLength(0);pendingReasoning.setLength(0);lastFlush=System.currentTimeMillis();
        }
        synchronized void finish(String kind,String message,String finish){
            if(!terminal.compareAndSet(false,true))return;
            try{flush();emit(event(kind,id).put("provider",provider).put("message",message).put("finishReason",finish));}catch(Exception ignored){}
            jobs.remove(id,this);
        }
    }
}
