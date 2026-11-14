package io.github.stevejjobs8.whereareyou;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 얇은 껍데기: 앱 UI 전체는 웹(app.html)이 그린다.
 * 네이티브는 위치 전송 서비스 · 권한 요청 · 클립보드 · 공유 · 설정 열기만 JS 브리지로 제공한다.
 * 그래서 디자인/화면 수정은 APK 재빌드 없이 사이트 배포만으로 반영된다.
 */
public class MainActivity extends Activity {
    static final String BASE = "https://stevejjobs8.github.io/where-are-you/";
    private static final int RC_START = 7, RC_PERMS = 8;
    private SharedPreferences sp;
    private WebView web;
    private boolean startAfterPerm = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);         // 까만 기본 제목줄 제거
        sp = getSharedPreferences("share", MODE_PRIVATE);

        Window w = getWindow();
        w.setStatusBarColor(Color.parseColor("#E3EBF6"));
        w.setNavigationBarColor(Color.parseColor("#FFFFFF"));
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#E3EBF6"));
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        // 우리 사이트만 앱 안에서 열고, 바깥 링크는 외부 브라우저로 (인증 정보 보호)
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String u = r.getUrl().toString();
                if (u.startsWith(BASE)) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, r.getUrl())); } catch (Exception ignored) {}
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "AndroidBridge");
        setContentView(web);
        web.loadUrl(BASE + "app-design-preview.html");
    }

    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.WY&&WY.onResume&&WY.onResume()", null);
    }

    @Override public void onBackPressed() {
        if (web == null) { super.onBackPressed(); return; }
        web.evaluateJavascript("(window.WY&&WY.onBack&&WY.onBack())?1:0", val -> {
            if (!"1".equals(val)) moveTaskToBack(true);   // 앱을 끄지 않고 뒤로 보냄
        });
    }

    /* ---------------- JS → 네이티브 브리지 ---------------- */
    private class Bridge {
        /** 지도/앱이 쓸 내 인증 정보 */
        @JavascriptInterface public String auth() {
            try { return new JSONObject()
                    .put("view", sp.getString("view_code", ""))
                    .put("write", sp.getString("write_key", "")).toString(); }
            catch (Exception e) { return "{}"; }
        }
        /** 웹이 만든 공유 계정을 네이티브에 저장 (LocationService가 사용) */
        @JavascriptInterface public void setCreds(String view, String write) {
            sp.edit().putString("view_code", view).putString("write_key", write).apply();
        }
        @JavascriptInterface public boolean isSharing() { return sp.getBoolean("on", false); }
        @JavascriptInterface public String shareStatus() { return LocationService.lastStatus; }

        @JavascriptInterface public void startShare() { runOnUiThread(() -> beginShare()); }
        @JavascriptInterface public void stopShare() {
            runOnUiThread(() -> {
                stopService(new Intent(MainActivity.this, LocationService.class));
                sp.edit().putBoolean("on", false).apply();
                callJs("window.WY&&WY.onShare(false)");
            });
        }
        @JavascriptInterface public void requestPermissions() {
            runOnUiThread(() -> { startAfterPerm = false; askPermissions(RC_PERMS); });
        }
        @JavascriptInterface public String permissions() {
            try { return new JSONObject()
                    .put("location", granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION))
                    .put("notif", Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS))
                    .put("activity", Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACTIVITY_RECOGNITION)).toString(); }
            catch (Exception e) { return "{}"; }
        }
        @JavascriptInterface public void copy(String text) {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("code", text));
        }
        @JavascriptInterface public void shareText(String text) {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
                startActivity(Intent.createChooser(i, "보내기"));
            });
        }
        @JavascriptInterface public void openBattery() {
            runOnUiThread(() -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()))));
        }
        @JavascriptInterface public void openUrl(String url) {
            runOnUiThread(() -> { try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception ignored) {} });
        }
    }

    /* ---------------- 공유 시작: 권한 확인 → 서비스 ---------------- */
    private void beginShare() {
        List<String> need = neededPermissions();
        if (need.isEmpty()) { launchService(); return; }
        startAfterPerm = true;
        requestPermissions(need.toArray(new String[0]), RC_START);
    }

    private void launchService() {
        startForegroundService(new Intent(this, LocationService.class));
        sp.edit().putBoolean("on", true).apply();
        callJs("window.WY&&WY.onShare(true)");
    }

    private List<String> neededPermissions() {
        List<String> need = new ArrayList<>();
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION);
            need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS))
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACTIVITY_RECOGNITION))
            need.add(Manifest.permission.ACTIVITY_RECOGNITION);
        return need;
    }

    private void askPermissions(int rc) {
        List<String> need = neededPermissions();
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), rc);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        boolean loc = granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (rc == RC_START && startAfterPerm) {
            startAfterPerm = false;
            if (loc) launchService();
            else callJs("window.WY&&WY.onShareDenied&&WY.onShareDenied()");
        }
    }

    private boolean granted(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }
    private void callJs(String js) {
        if (web != null) web.evaluateJavascript(js, null);
    }
}
