package io.jizhang.assistant;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.*;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONObject;

/** Only immutable packaged assets receive native capabilities. */
public class MainActivity extends Activity {
 private WebView web; private FrameLayout root; private ModelBridge bridge; private String pendingExport;
 private static final String HOST="app.jizhang.local";
 private static final Set<String> ASSETS=new HashSet<>(Arrays.asList("index.html","app.css","app.js","ledger.js"));
 @Override public void onCreate(Bundle state){super.onCreate(state);
  getWindow().setStatusBarColor(Color.TRANSPARENT);getWindow().setNavigationBarColor(Color.TRANSPARENT);
  root=new FrameLayout(this);root.setBackgroundColor(Color.rgb(245,246,243));
  root.setOnApplyWindowInsetsListener((v,i)->{v.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i.consumeSystemWindowInsets();});
  web=new WebView(this);web.setBackgroundColor(Color.rgb(245,246,243));
  WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setAllowFileAccess(false);s.setAllowContentAccess(false);s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);s.setJavaScriptCanOpenWindowsAutomatically(false);s.setSafeBrowsingEnabled(true);
  web.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
  bridge=new ModelBridge(this,web);web.addJavascriptInterface(bridge,"ModelBridge");web.addJavascriptInterface(new Device(),"Device");web.addJavascriptInterface(new LedgerVault(this),"Vault");
  web.setWebViewClient(new WebViewClient(){
   @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest req){Uri u=req.getUrl();String p=u.getPath(),name=p==null||p.equals("/")?"index.html":p.substring(1);if(!"https".equals(u.getScheme())||!HOST.equals(u.getHost())||!ASSETS.contains(name))return blocked();try{return new WebResourceResponse(name.endsWith(".js")?"application/javascript":name.endsWith(".css")?"text/css":"text/html","UTF-8",getAssets().open(name));}catch(Exception e){return blocked();}}
   @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest req){external(req.getUrl().toString());return true;}
  });
  root.addView(web,new FrameLayout.LayoutParams(-1,-1));setContentView(root);root.requestApplyInsets();web.loadUrl("https://"+HOST+"/index.html");
 }
 private static WebResourceResponse blocked(){return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",null,new ByteArrayInputStream(new byte[0]));}
 private void external(String raw){try{URI u=new URI(raw);if(("https".equals(u.getScheme())||"http".equals(u.getScheme()))&&u.getHost()!=null&&u.getRawUserInfo()==null)startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(raw)));}catch(Exception e){message("无法打开链接");}}
 private void message(String text){runOnUiThread(()->Toast.makeText(this,text,Toast.LENGTH_LONG).show());}
 public final class Device {
  @JavascriptInterface public void theme(String value){final boolean dark="dark".equals(value);runOnUiThread(()->{int c=Color.parseColor(dark?"#131e1a":"#f5f6f3");root.setBackgroundColor(c);web.setBackgroundColor(c);getWindow().getDecorView().setSystemUiVisibility(dark?0:android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);});}
  @JavascriptInterface public void open(String url){runOnUiThread(()->external(url));}
  @JavascriptInterface public void exportFile(String text,String name,String mime){if(text==null||text.length()>26000000||name==null||name.length()>200||name.contains("/")||name.contains("\\"))return;final String type="application/json".equals(mime)?mime:"text/csv".equals(mime)?mime:"text/plain";runOnUiThread(()->{if(pendingExport!=null){message("请先完成当前导出");return;}pendingExport=text;Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(type).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,name);try{startActivityForResult(i,101);}catch(Exception e){pendingExport=null;message("设备没有文件保存入口");}});}
  @JavascriptInterface public void importFile(){runOnUiThread(()->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","text/csv","text/comma-separated-values","text/plain","application/octet-stream"});try{startActivityForResult(i,102);}catch(Exception e){message("设备没有文件选择入口");}});}
 }
 @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==101){final String text=pendingExport;pendingExport=null;if(result==RESULT_OK&&data!=null&&data.getData()!=null&&text!=null){final Uri uri=data.getData();new Thread(()->{try(OutputStream out=getContentResolver().openOutputStream(uri)){if(out==null)throw new IOException();out.write(text.getBytes(StandardCharsets.UTF_8));message("文件已保存，请妥善保管");}catch(Exception e){message("文件保存失败，请重试");}},"ledger-export").start();}}
  if(request==102&&result==RESULT_OK&&data!=null&&data.getData()!=null){final Uri uri=data.getData();new Thread(()->{try(InputStream in=getContentResolver().openInputStream(uri)){if(in==null)throw new IOException();String text=ModelHttpClient.readLimited(in,32000000);runOnUiThread(()->web.evaluateJavascript("window.onImportFile && window.onImportFile("+JSONObject.quote(text)+")",null));}catch(Exception e){message("读取失败或文件超过32MB，请选择有效备份");}},"ledger-import").start();}
 }
 @Override public void onBackPressed(){web.evaluateJavascript("window.handleBack && window.handleBack()",v->{if(!"true".equals(v))MainActivity.super.onBackPressed();});}
 @Override protected void onDestroy(){bridge.close();web.removeJavascriptInterface("ModelBridge");web.removeJavascriptInterface("Device");web.removeJavascriptInterface("Vault");web.destroy();super.onDestroy();}
}
