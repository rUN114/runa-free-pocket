package cn.run.freepocket.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.webkit.CookieManager;
import android.webkit.WebView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Owner-signed tests. Live modes observe the non-debuggable release; fixture mode is diagnostic only. */
public final class ReleaseVerification extends Instrumentation {
    private Bundle arguments;
    private Activity activity;
    private WebView catalogue;
    private File output;
    private String selectedAccountUrl = "";

    @Override public void onCreate(Bundle args) {
        arguments = args;
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            output = new File(getTargetContext().getExternalFilesDir(null), "verification");
            if (!output.isDirectory() && !output.mkdirs()) throw new IllegalStateException("Cannot create test output");
            String mode = arguments.getString("mode", "inspect");
            if (!java.util.Arrays.asList("inspect", "batch", "resume", "interrupt", "fixture").contains(mode)) throw new IllegalStateException("Unknown verification mode");
            if (mode.equals("fixture") && (getTargetContext().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0)
                throw new IllegalStateException("Fixture mode requires the separate diagnostic build");
            if (!mode.equals("fixture") && (getTargetContext().getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                throw new IllegalStateException("Target is debuggable; this test requires the actual release");
            restoreAuthorizedCookies();
            activity = startActivitySync(new Intent(Intent.ACTION_MAIN).setComponent(
                    new ComponentName("cn.run.freepocket", "cn.run.freepocket.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if (arguments.getString("portrait", "false").equals("true"))
                runOnMainSync(() -> activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
            waitFor(() -> {
                runOnMainSync(() -> catalogue = findCatalogue(activity.getWindow().getDecorView()));
                return catalogue != null && "true".equals(js(catalogue, "typeof window.receiveState==='function'&&!state.busy"));
            }, 100000, "Initial catalogue did not become ready");
            if (mode.equals("inspect")) {
                result.putString("verification", "passed; actual release startup; no claiming requested");
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (mode.equals("fixture")) {
                CheckpointBridgeFixture.run(this, activity, true);
                CheckpointBridgeFixture.run(this, activity, false);
                result.putString("verification", "passed; local intercepted bridge fixture only; no official orders");
                finish(Activity.RESULT_OK, result);
                return;
            }
            if (mode.equals("interrupt")) {
                js(catalogue, "document.querySelector('#claim-all').click(), true");
                waitFor(() -> "true".equals(js(catalogue, "state.busy&&state.history.length>0")), 5000, "Batch checkpoint missing");
                write("interrupted.json", js(catalogue, "JSON.stringify({id:state.history[0].id,state:state.history[0].state})"));
                Bundle checkpoint = new Bundle(); checkpoint.putString("checkpoint", "persisted; intentional process kill follows");
                sendStatus(1, checkpoint);
                android.os.Process.killProcess(android.os.Process.myPid());
                return;
            }
            if (mode.equals("resume")) {
                if (!"true".equals(js(catalogue, "state.history[0]?.state==='paused'"))) throw new IllegalStateException("Interrupted release task was not restored as paused");
                js(catalogue, "document.querySelector('#resume-batch').click(), true");
            } else {
                js(catalogue, "document.querySelector('#claim-all').click(), true");
            }
            waitFor(() -> {
                selectAuthorizedAccountIfShown();
                return "true".equals(js(catalogue, "!state.busy&&state.history?.[0]?.state==='done'"));
            }, 360000, "Release batch did not finish; may require human verification");
            JSONObject snapshot = new JSONObject(js(catalogue, "JSON.stringify({history:state.history,items:state.items,epic:state.epicAccount,fab:state.fabAccount})"));
            JSONObject latest = snapshot.getJSONArray("history").getJSONObject(0);
            if (latest.getJSONObject("errors").length() != 0) throw new IllegalStateException("Release batch contains category errors; inspect private report");
            JSONArray entries = latest.getJSONArray("entries");
            if (entries.length() == 0) throw new IllegalStateException("Release produced no inventory results");
            for (int i = 0; i < entries.length(); i++) {
                String status = entries.getJSONObject(i).getString("status");
                if (!status.equals("owned") && !status.equals("skipped") && !status.equals("claimed")) throw new IllegalStateException("Unfinished release item: " + status);
            }
            write(mode + "-real-account.json", snapshot.toString(2));
            for (String category : new String[]{"pc", "android", "fab"}) {
                js(catalogue, "document.querySelector('[data-tab=" + category + "]').click(), true");
                waitFor(() -> "true".equals(js(catalogue, "!state.busy&&[...document.querySelectorAll('.cover-button img')].length>0&&[...document.querySelectorAll('.cover-button img')].every(i=>i.complete&&i.naturalWidth>0)")), 90000, "Official images failed to decode");
                write(mode + "-" + category + "-layout.json", js(catalogue, "JSON.stringify({width:innerWidth,height:innerHeight,scrollWidth:document.documentElement.scrollWidth,images:[...document.querySelectorAll('.cover-button img')].map(i=>({decoded:i.complete&&i.naturalWidth>0,width:i.naturalWidth,height:i.naturalHeight}))})"));
                screenshot(mode + "-" + category + ".png");
            }
            js(catalogue, "document.querySelector('#show-history').click(), true");
            screenshot(mode + "-history.png");
            result.putString("verification", "passed; actual non-debuggable release and official inventory; private evidence saved");
            finish(Activity.RESULT_OK, result);
        } catch (Exception error) {
            try { if (catalogue != null) write("failure-state.json", js(catalogue, "JSON.stringify({history:state.history,message:state.message,busy:state.busy})")); }
            catch (Exception ignored) { /* The primary failure remains the instrumentation result. */ }
            result.putString("verification", error.getClass().getSimpleName() + ": " + error.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private WebView findCatalogue(View view) {
        if (view instanceof WebView && ((WebView) view).getUrl() != null && ((WebView) view).getUrl().startsWith("https://appassets.androidplatform.net/")) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) { WebView result = findCatalogue(group.getChildAt(i)); if (result != null) return result; }
        }
        return null;
    }

    String js(WebView webView, String expression) throws Exception {
        String[] raw = new String[1]; CountDownLatch latch = new CountDownLatch(1);
        runOnMainSync(() -> webView.evaluateJavascript("(()=>{" + "return (" + expression + ");})()", value -> { raw[0]=value; latch.countDown(); }));
        if (!latch.await(8, TimeUnit.SECONDS)) throw new IllegalStateException("WebView evaluation timed out");
        Object decoded = new JSONTokener(raw[0]).nextValue();
        return decoded == JSONObject.NULL ? "null" : decoded.toString();
    }

    private void selectAuthorizedAccountIfShown() throws Exception {
        File accountFile = new File(getTargetContext().getExternalFilesDir(null), "account.json");
        if (!accountFile.isFile()) return;
        String email = new JSONObject(new String(Files.readAllBytes(accountFile.toPath()), StandardCharsets.UTF_8)).getString("email");
        WebView[] choice=new WebView[1];
        runOnMainSync(() -> choice[0]=findAccountChooser(activity.getWindow().getDecorView()));
        if(choice[0]==null)return;
        String[] currentUrl=new String[1];
        runOnMainSync(() -> currentUrl[0]=choice[0].getUrl());
        if(currentUrl[0].equals(selectedAccountUrl))return;
        String position=js(choice[0],"(()=>{const email="+JSONObject.quote(email)+";const choices=[...document.querySelectorAll('p,span,div,button,a')].filter(el=>el.children.length===0&&el.textContent.trim()===email&&el.getBoundingClientRect().width>0&&el.getBoundingClientRect().height>0);if(choices.length!==1)return null;const r=choices[0].getBoundingClientRect();return JSON.stringify({x:r.left+r.width/2,y:r.top+r.height/2,width:innerWidth});})()");
        if(position.equals("null"))return;
        JSONObject point=new JSONObject(position);int[] location=new int[2];int[] width=new int[1];
        runOnMainSync(() -> {choice[0].getLocationOnScreen(location);width[0]=choice[0].getWidth();});
        float scale=(float)(width[0]/point.getDouble("width"));
        float x=location[0]+(float)point.getDouble("x")*scale,y=location[1]+(float)point.getDouble("y")*scale;
        long time=SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,x,y,0);
        MotionEvent up=MotionEvent.obtain(time,time+100,MotionEvent.ACTION_UP,x,y,0);
        try{sendPointerSync(down);sendPointerSync(up);}finally{down.recycle();up.recycle();}
        selectedAccountUrl=currentUrl[0];
    }

    private WebView findAccountChooser(View view) {
        if (view instanceof WebView) {
            WebView webView=(WebView)view;
            String url=webView.getUrl();
            if (url != null && url.startsWith("https://www.epicgames.com/id/login/switch-account"))return webView;
        }
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) {
            WebView found=findAccountChooser(((ViewGroup)view).getChildAt(i));if(found!=null)return found;
        }
        return null;
    }

    private void restoreAuthorizedCookies() throws Exception {
        File file = new File(getTargetContext().getExternalFilesDir(null), "cookies.json");
        if (!file.isFile()) return;
        JSONArray cookies = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        CountDownLatch latch = new CountDownLatch(1);
        runOnMainSync(() -> {
            try {
                CookieManager manager=CookieManager.getInstance();manager.setAcceptCookie(true);
                for(int i=0;i<cookies.length();i++) {
                    JSONObject cookie=cookies.getJSONObject(i);String domain=cookie.getString("domain");
                    if (!domain.equals("epicgames.com") && !domain.endsWith(".epicgames.com") && !domain.equals("fab.com") && !domain.endsWith(".fab.com")) throw new IllegalStateException("Unexpected cookie domain");
                    String header=cookie.getString("name")+"="+cookie.getString("value")+"; Domain="+domain+"; Path="+cookie.getString("path");
                    if(cookie.optBoolean("secure"))header+="; Secure";
                    if(cookie.optBoolean("httpOnly"))header+="; HttpOnly";
                    if(cookie.has("sameSite"))header+="; SameSite="+cookie.getString("sameSite");
                    manager.setCookie("https://"+domain.replaceFirst("^\\.","")+cookie.getString("path"),header);
                }
                manager.flush();
            } catch(Exception error) { throw new IllegalStateException("Cannot restore authorized local session",error); }
            finally { latch.countDown(); }
        });
        if(!latch.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Cookie restore timed out");
        Files.delete(file.toPath());
    }

    interface Condition { boolean ready() throws Exception; }
    void waitFor(Condition condition, long timeout, String failure) throws Exception {
        long end=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<end) { if(condition.ready())return;SystemClock.sleep(800); }
        throw new IllegalStateException(failure);
    }
    void write(String name, String text) throws Exception { Files.write(new File(output,name).toPath(),text.getBytes(StandardCharsets.UTF_8)); }
    private void screenshot(String name) throws Exception {
        CountDownLatch painted=new CountDownLatch(1);
        runOnMainSync(() -> catalogue.postVisualStateCallback(0,new WebView.VisualStateCallback() {
            @Override public void onComplete(long requestId) { painted.countDown(); }
        }));
        if(!painted.await(10,TimeUnit.SECONDS))throw new IllegalStateException("Release visual frame timed out");
        SystemClock.sleep(200);
        Bitmap bitmap=getUiAutomation().takeScreenshot();
        if(bitmap==null)throw new IllegalStateException("Cannot capture release screenshot");
        try(FileOutputStream stream=new FileOutputStream(new File(output,name))) { bitmap.compress(Bitmap.CompressFormat.PNG,100,stream); }
        bitmap.recycle();
    }
}
