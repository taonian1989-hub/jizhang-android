package io.jizhang.assistant;

import org.json.JSONObject;
import java.net.URI;

final class ModelConfig {
    static final String[] PROVIDERS={"deepseek","kimi","glm","custom"};
    final String provider,endpoint,model,key;
    final int maxTokens;
    final boolean showReasoning;
    ModelConfig(String provider,String endpoint,String model,String key,int maxTokens,boolean showReasoning) throws Exception {
        if(!validProvider(provider))throw new Exception("不支持的模型服务商");
        URI u=new URI(endpoint);
        if(!"https".equalsIgnoreCase(u.getScheme())||u.getHost()==null||u.getRawUserInfo()!=null||u.getRawQuery()!=null||u.getRawFragment()!=null||endpoint.length()>2048||!u.getPath().endsWith("/chat/completions"))throw new Exception("请输入 HTTPS 的完整对话接口地址，以 /chat/completions 结尾");
        if(!model.matches("[A-Za-z0-9][A-Za-z0-9_./:@-]{0,127}"))throw new Exception("模型 ID 格式不正确");
        if(key.length()>4096||key.matches("(?s).*[\\r\\n\\x00-\\x20\\x7f].*"))throw new Exception("API Key 不能包含空白或控制字符");
        if(maxTokens<1024||maxTokens>32768)throw new Exception("输出上限应在 1024 至 32768 之间");
        this.provider=provider;this.endpoint=u.normalize().toASCIIString();this.model=model;this.key=key;this.maxTokens=maxTokens;this.showReasoning=showReasoning;
    }
    static boolean validProvider(String provider){for(String p:PROVIDERS)if(p.equals(provider))return true;return false;}
    static ModelConfig defaults(String p) throws Exception {
        if("deepseek".equals(p))return new ModelConfig(p,"https://api.deepseek.com/chat/completions","deepseek-flash","",8192,false);
        if("kimi".equals(p))return new ModelConfig(p,"https://api.moonshot.cn/v1/chat/completions","kimi-k3","",8192,false);
        if("glm".equals(p))return new ModelConfig(p,"https://open.bigmodel.cn/api/paas/v4/chat/completions","glm-5.3","",8192,false);
        if("custom".equals(p))return new ModelConfig(p,"https://your-provider.example/v1/chat/completions","your-model","",8192,false);
        throw new Exception("不支持的模型服务商");
    }
    static ModelConfig fromJson(String provider,JSONObject json,String key) throws Exception {
        return new ModelConfig(provider,json.getString("endpoint").trim(),json.getString("model").trim(),key,json.optInt("maxTokens",8192),json.optBoolean("showReasoning",true));
    }
    JSONObject publicJson() throws Exception {return new JSONObject().put("provider",provider).put("endpoint",endpoint).put("model",model).put("maxTokens",maxTokens).put("showReasoning",showReasoning).put("configured",!key.isEmpty());}
}
