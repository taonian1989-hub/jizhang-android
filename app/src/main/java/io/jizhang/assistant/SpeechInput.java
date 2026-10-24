package io.jizhang.assistant;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.WebView;
import java.util.ArrayList;
import org.json.JSONObject;

/** One user-initiated recognition at a time. Audio is never stored by this app. */
final class SpeechInput {
    static final int REQUEST_MICROPHONE = 301;
    private static final int SYSTEM_INPUT_FIRST = 4000, SYSTEM_INPUT_LIMIT = 54000;
    private final Activity activity;
    private final WebView web;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private String activeId;
    private boolean awaitingPermission, permissionInFlight, external, processing, closed;
    private Runnable deadline;
    private long attemptSerial;
    private int externalCode, nextExternalCode = SYSTEM_INPUT_FIRST;

    SpeechInput(Activity activity, WebView web) { this.activity = activity; this.web = web; }

    private boolean current(String id) { return !closed && id != null && id.equals(activeId); }
    private boolean currentAttempt(String id, long serial) { return current(id) && serial == attemptSerial; }
    private void emit(String id, String kind, String text, String code) {
        if (closed) return;
        try {
            JSONObject e = new JSONObject().put("id", id).put("kind", kind)
                .put("text", text).put("code", code);
            web.evaluateJavascript("window.onSpeechEvent && window.onSpeechEvent(" + e + ")", null);
        } catch (Exception ignored) { }
    }
    private Intent intent() {
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "说出要记的花费或想问的问题，识别后先检查文字");
    }
    private void armDeadline(String id, long millis) {
        if (deadline != null) main.removeCallbacks(deadline);
        deadline = () -> { if (current(id)) finish(id, "error", "语音识别超时，请分段重试", "timeout"); };
        main.postDelayed(deadline, millis);
    }
    void start(String id) {
        if (closed || id == null || !id.matches("[0-9]{1,12}")) return;
        if (activeId != null) { emit(id, "error", "另一段语音尚未结束，请先停止或取消", "busy"); return; }
        if (permissionInFlight) { emit(id, "error", "请先处理系统麦克风授权提示，再点语音输入", "permission"); return; }
        activeId = id;
        armDeadline(id, 60000);
        emit(id, "starting", "正在启动系统语音识别", "");
        boolean onDevice = false, available = false;
        try {
            onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(activity);
            available = SpeechRecognizer.isRecognitionAvailable(activity);
        } catch (Exception ignored) { }
        if (!onDevice && !available) { startExternal(id); return; }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            awaitingPermission = true;
            permissionInFlight = true;
            emit(id, "permission", "请在系统提示中允许使用麦克风", "");
            try { activity.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_MICROPHONE); }
            catch (Exception e) { permissionInFlight = false; finish(id, "error", "无法申请麦克风权限，请在应用设置中开启", "permission"); }
            return;
        }
        startRecognizer(id);
    }
    boolean permissionResult(int request, int[] grants) {
        if (request != REQUEST_MICROPHONE) return false;
        permissionInFlight = false;
        String id = activeId;
        if (id == null || !awaitingPermission || closed) return true;
        awaitingPermission = false;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) startRecognizer(id);
        else finish(id, "error", "麦克风权限未开启。可在系统应用设置中授权后重试", "permission");
        return true;
    }
    private void startRecognizer(final String id) { startRecognizer(id, true); }
    private void startRecognizer(final String id, boolean preferLocal) {
        if (!current(id)) return;
        awaitingPermission = false;
        final long attempt = ++attemptSerial;
        processing = false;
        armDeadline(id, 60000);
        boolean local = false;
        try {
            if (preferLocal && Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)) {
                try { recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(activity); local = true; }
                catch (RuntimeException ignored) { recognizer = null; }
            }
            if (recognizer == null) recognizer = SpeechRecognizer.createSpeechRecognizer(activity);
            final boolean localService = local;
            recognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { if (currentAttempt(id, attempt)) emit(id, "listening", "正在听，请说话…", ""); }
                @Override public void onBeginningOfSpeech() { if (currentAttempt(id, attempt)) emit(id, "listening", "正在听，请说话…", ""); }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { if (currentAttempt(id, attempt)) { processing = true; emit(id, "processing", "正在整理识别文字…", ""); armDeadline(id, 25000); } }
                @Override public void onError(int error) {
                    if (!currentAttempt(id, attempt)) return;
                    if (localService && (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)) {
                        releaseRecognizer(); startRecognizer(id, false); return;
                    }
                    finish(id, "error", errorMessage(error), error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? "permission" : "recognition");
                }
                @Override public void onResults(Bundle results) {
                    if (!currentAttempt(id, attempt)) return;
                    String text = best(results, SpeechRecognizer.RESULTS_RECOGNITION);
                    if (text.isEmpty()) finish(id, "error", "没有识别到文字，请再说一次", "empty");
                    else if (text.length() > 4000) finish(id, "error", "语音内容过长，请分段输入", "length");
                    else finish(id, "result", text, "");
                }
                @Override public void onPartialResults(Bundle partial) { if (currentAttempt(id, attempt)) { String text = best(partial, SpeechRecognizer.RESULTS_RECOGNITION); if (!text.isEmpty() && text.length() <= 4000) emit(id, "partial", text, ""); } }
                @Override public void onEvent(int eventType, Bundle params) { }
            });
            Intent request = intent();
            if (local) request.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
            emit(id, "starting", local ? "正在启动本地语音识别…" : "正在启动系统识别，服务可能需要联网…", "");
            recognizer.startListening(request);
        } catch (SecurityException e) { finish(id, "error", "麦克风权限不可用，请在应用设置中授权", "permission"); }
        catch (Exception e) { releaseRecognizer(); if (current(id)) startExternal(id); }
    }
    private void startExternal(String id) {
        if (!current(id)) return;
        try {
            Intent request = intent();
            if (request.resolveActivity(activity.getPackageManager()) == null) {
                finish(id, "error", "手机没有可用的系统语音识别服务。请启用系统语音服务，或使用输入法的语音键", "unavailable");
                return;
            }
            external = true;
            externalCode = nextExternalCode++;
            if (nextExternalCode >= SYSTEM_INPUT_LIMIT) nextExternalCode = SYSTEM_INPUT_FIRST;
            emit(id, "external", "请在系统语音窗口说话，完成后文字会回到输入框", "");
            activity.startActivityForResult(request, externalCode);
        } catch (SecurityException e) { finish(id, "error", "系统语音服务没有麦克风权限，请检查系统语音服务的设置", "service_permission"); }
        catch (Exception e) { finish(id, "error", "系统语音窗口无法打开，可改用输入法的语音键", "unavailable"); }
    }
    boolean activityResult(int request, int result, Intent data) {
        if (request < SYSTEM_INPUT_FIRST || request >= SYSTEM_INPUT_LIMIT) return false;
        String id = activeId;
        if (id == null || !external || request != externalCode || closed) return true;
        external = false;
        if (result != Activity.RESULT_OK) { finish(id, "cancelled", "已取消语音输入", ""); return true; }
        String text = data == null ? "" : best(data.getExtras(), RecognizerIntent.EXTRA_RESULTS);
        if (text.isEmpty()) finish(id, "error", "系统没有返回识别文字，请重试", "empty");
        else if (text.length() > 4000) finish(id, "error", "语音内容过长，请分段输入", "length");
        else finish(id, "result", text, "");
        return true;
    }
    void stop(String id) {
        if (!current(id) || processing) return;
        if (awaitingPermission || external || recognizer == null) { cancel(id); return; }
        try { processing = true; emit(id, "processing", "正在整理识别文字…", ""); armDeadline(id, 25000); recognizer.stopListening(); }
        catch (Exception e) { finish(id, "error", "语音识别未完成，请重试", "recognition"); }
    }
    void cancel(String id) { if (current(id)) finish(id, "cancelled", "已取消语音输入", ""); }
    void pause() { if (recognizer != null && activeId != null) cancel(activeId); }
    void settings() {
        try { activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.getPackageName()))); }
        catch (Exception ignored) { }
    }
    private void finish(String id, String kind, String text, String code) {
        if (!current(id)) return;
        activeId = null;
        awaitingPermission = false;
        processing = false;
        if (deadline != null) { main.removeCallbacks(deadline); deadline = null; }
        boolean wasExternal = external; external = false;
        releaseRecognizer();
        if (wasExternal) { try { activity.finishActivity(externalCode); } catch (Exception ignored) { } }
        emit(id, kind, text, code);
    }
    private void releaseRecognizer() {
        attemptSerial++;
        SpeechRecognizer old = recognizer; recognizer = null;
        if (old != null) { try { old.cancel(); } catch (Exception ignored) { } try { old.destroy(); } catch (Exception ignored) { } }
    }
    void close() {
        if (activeId != null) cancel(activeId);
        closed = true;
        main.removeCallbacksAndMessages(null);
        releaseRecognizer();
    }
    private static String best(Bundle data, String key) {
        if (data == null) return "";
        try {
            ArrayList<String> values = data.getStringArrayList(key);
            if (values != null) for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        } catch (RuntimeException ignored) { }
        return "";
    }
    private static String errorMessage(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "麦克风权限未开启，请在系统应用设置中授权";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
            case SpeechRecognizer.ERROR_NO_MATCH: return "没有听清，请靠近手机再说一次";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "系统语音服务连接失败，请检查网络或使用可用的本地语音服务";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "系统语音服务正忙，请结束其他录音或语音后重试";
            case SpeechRecognizer.ERROR_AUDIO: return "麦克风暂时不可用，请检查是否被其他应用占用";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE: return "系统语音服务尚不支持中文或未安装中文语音包，请在系统中设置后重试";
            default: return "系统语音识别未完成，请稍后重试或使用输入法语音键";
        }
    }
}
