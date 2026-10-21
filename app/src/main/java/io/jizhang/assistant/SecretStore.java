package io.jizhang.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.security.KeyStore;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Secrets never leave the native store through a read method. */
final class SecretStore {
    private static final String ALIAS="jizhang.models.aes.v1";
    private final SharedPreferences prefs;
    SecretStore(Context context){prefs=context.getSharedPreferences("models_secure_v1",Context.MODE_PRIVATE);}
    private SecretKey secretKey() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);
        if(ks.containsAlias(ALIAS))return (SecretKey)ks.getKey(ALIAS,null);
        KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return gen.generateKey();
    }
    synchronized ModelConfig load(String provider) throws Exception {
        if(!ModelConfig.validProvider(provider))throw new Exception("不支持的模型服务商");
        String stored=prefs.getString(provider,null);if(stored==null)return ModelConfig.defaults(provider);
        try {
            JSONObject envelope=new JSONObject(stored);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,secretKey(),new GCMParameterSpec(128,Base64.decode(envelope.getString("iv"),Base64.NO_WRAP)));
            cipher.updateAAD(provider.getBytes(StandardCharsets.UTF_8));
            JSONObject data=new JSONObject(new String(cipher.doFinal(Base64.decode(envelope.getString("ciphertext"),Base64.NO_WRAP)),StandardCharsets.UTF_8));
            return ModelConfig.fromJson(provider,data,data.getString("key"));
        }catch(Exception e){throw new Exception("本机模型配置无法解密，请移除后重新配置");}
    }
    synchronized void save(ModelConfig config) throws Exception {
        JSONObject data=config.publicJson().put("key",config.key);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,secretKey());cipher.updateAAD(config.provider.getBytes(StandardCharsets.UTF_8));
        byte[] bytes=cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject envelope=new JSONObject().put("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)).put("ciphertext",Base64.encodeToString(bytes,Base64.NO_WRAP));
        if(!prefs.edit().putString(config.provider,envelope.toString()).commit())throw new Exception("配置未能保存，请重试");
    }
    synchronized void remove(String provider) throws Exception {
        if(!ModelConfig.validProvider(provider))throw new Exception("不支持的模型服务商");
        if(!prefs.edit().remove(provider).commit())throw new Exception("配置未能移除");
    }
}
