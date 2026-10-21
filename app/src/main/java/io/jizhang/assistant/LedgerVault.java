package io.jizhang.assistant;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Authenticated atomic snapshots. Read failure never becomes an empty writable ledger. */
final class LedgerVault {
 private final AtomicFile file;private final SharedPreferences prefs;private static final String ALIAS="jizhang.ledger.aes.v1";
 LedgerVault(Context c){file=new AtomicFile(new File(c.getFilesDir(),"ledger_v1.enc"));prefs=c.getSharedPreferences("welcome",Context.MODE_PRIVATE);}
 private SecretKey key()throws Exception{KeyStore ks=KeyStore.getInstance("AndroidKeyStore");ks.load(null);if(ks.containsAlias(ALIAS))return (SecretKey)ks.getKey(ALIAS,null);KeyGenerator g=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");g.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());return g.generateKey();}
 @JavascriptInterface public synchronized String read(){try{if(!file.getBaseFile().exists()&&!new File(file.getBaseFile()+".bak").exists())return "";JSONObject e=new JSONObject(readRaw());Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(e.getString("iv"),Base64.NO_WRAP)));c.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));return new String(c.doFinal(Base64.decode(e.getString("data"),Base64.NO_WRAP)),StandardCharsets.UTF_8);}catch(Exception e){return "__LEDGER_READ_ERROR__";}}
 @JavascriptInterface public synchronized String readRaw(){try(InputStream in=file.openRead()){return ModelHttpClient.readLimited(in,26000000);}catch(Exception e){return "__LEDGER_RAW_READ_ERROR__";}}
 @JavascriptInterface public synchronized String write(String raw){if(raw==null||raw.length()>6000000)return "too-large";FileOutputStream out=null;try{JSONObject json=new JSONObject(raw);if(json.getInt("version")!=1)throw new Exception();Cipher c=Cipher.getInstance("AES/GCM/NoPadding");c.init(Cipher.ENCRYPT_MODE,key());c.updateAAD(ALIAS.getBytes(StandardCharsets.UTF_8));byte[] data=c.doFinal(raw.getBytes(StandardCharsets.UTF_8));JSONObject e=new JSONObject().put("iv",Base64.encodeToString(c.getIV(),Base64.NO_WRAP)).put("data",Base64.encodeToString(data,Base64.NO_WRAP));out=file.startWrite();out.write(e.toString().getBytes(StandardCharsets.UTF_8));file.finishWrite(out);return "ok";}catch(Exception e){if(out!=null)file.failWrite(out);return "error";}}
 @JavascriptInterface public boolean welcomed(){return prefs.getBoolean("welcomed",false);}
 @JavascriptInterface public void welcome(){prefs.edit().putBoolean("welcomed",true).apply();}
}
