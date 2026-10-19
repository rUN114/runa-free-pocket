package cn.run.freepocket.tests;

import android.app.Activity;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.view.ViewGroup;
import android.os.SystemClock;
import androidx.webkit.WebViewCompat;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** Runs only in the diagnostic variant: an isolated, fully intercepted local page; never an official order. */
final class CheckpointBridgeFixture {
    static void run(ReleaseVerification runner, Activity activity, boolean allow) throws Exception {
        WebView[] fixture = new WebView[1];
        String domain=asset(runner,"domain.js");
        String automation=asset(runner,"automation.js");
        String script=domain+"\n"+automation+"\nrunPocketTask({nonce:'local-fixture',mode:'claim',category:'pc',item:{key:'local-fixture',url:'https://store.epicgames.com/p/local-fixture'}});";
        boolean[] committed={false};
        runner.runOnMainSync(() -> {
            WebView view=new WebView(activity);fixture[0]=view;
            view.getSettings().setJavaScriptEnabled(true);
            view.setWebViewClient(new WebViewClient() {
                @Override public WebResourceResponse shouldInterceptRequest(WebView webView, WebResourceRequest request) {
                    // Every request is intercepted in this isolated test view. No request reaches Epic.
                    String html="<!doctype html><body><h1>本机模拟结算，不会产生订单</h1><p>Total $0.00</p><button onclick=\"window.fixtureClicks=(window.fixtureClicks||0)+1\">Place Order</button></body>";
                    return new WebResourceResponse("text/html","UTF-8",new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
                }
            });
            WebViewCompat.addWebMessageListener(view,"PocketStore",Collections.singleton("https://store.epicgames.com"),(v,message,origin,main,reply)->{
                try {
                    JSONObject data=new JSONObject(message.getData());
                    if(!data.optString("type").equals("actionReady")||!data.optString("nonce").equals("local-fixture"))return;
                    if(!allow)return;
                    committed[0]=runner.getTargetContext().getSharedPreferences("local-bridge-fixture",0).edit().putBoolean("checkpoint",true).commit();
                    if(committed[0])reply.postMessage(new JSONObject().put("type","actionPermit").put("nonce","local-fixture").put("stage",data.getString("stage")).toString());
                }catch(Exception error){throw new IllegalStateException("Fixture bridge failed",error);}
            });
            WebViewCompat.addDocumentStartJavaScript(view,script,Collections.singleton("https://store.epicgames.com"));
            ((ViewGroup)activity.getWindow().getDecorView()).addView(view,new ViewGroup.LayoutParams(-1,-1));
            view.loadUrl("https://store.epicgames.com/checkout");
        });
        try {
            runner.waitFor(() -> "true".equals(runner.js(fixture[0],"!!document.querySelector('button')")),15000,"Local bridge page did not load");
            if(allow)runner.waitFor(() -> "1".equals(runner.js(fixture[0],"window.fixtureClicks||0")),15000,"Diagnostic bridge did not receive the native checkpoint permit");
            SystemClock.sleep(2000);
            int clicks=Integer.parseInt(runner.js(fixture[0],"window.fixtureClicks||0"));
            if(clicks!=(allow?1:0))throw new IllegalStateException("Local bridge fixture click count incorrect");
            runner.write("bridge-fixture-"+allow+".json",new JSONObject().put("fixtureOnly",true).put("allRequestsIntercepted",true)
                    .put("durableCheckpoint",committed[0]).put("clicks",clicks).put("officialOrders",0).toString());
        } finally { runner.runOnMainSync(() -> { ((ViewGroup)fixture[0].getParent()).removeView(fixture[0]);fixture[0].destroy();runner.getTargetContext().deleteSharedPreferences("local-bridge-fixture"); }); }
    }

    private static String asset(ReleaseVerification runner, String name) throws Exception {
        try (var stream=runner.getTargetContext().getAssets().open(name);var bytes=new ByteArrayOutputStream()) {
            byte[] buffer=new byte[8192];int count;
            while((count=stream.read(buffer))!=-1)bytes.write(buffer,0,count);
            return bytes.toString("UTF-8");
        }
    }

}
