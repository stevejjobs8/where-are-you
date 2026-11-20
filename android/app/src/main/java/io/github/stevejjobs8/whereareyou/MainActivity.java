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
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.window.OnBackInvokedDispatcher;

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
    private static final int TOP_BAR = Color.parseColor("#E3EBF6"), BOTTOM_BAR = Color.parseColor("#FFFFFF");
    private SharedPreferences sp;
    private WebView web;
    private boolean startAfterPerm = false;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        requestWindowFeature(Window.FEATURE_NO_TITLE);         // 까만 기본 제목줄 제거
        sp = getSharedPreferences("share", MODE_PRIVATE);

        Window w = getWindow();
        if (Build.VERSION.SDK_INT < 35) {
            // Android 14 이하: 시스템이 상태 바·내비게이션 바 색을 칠해 준다.
            w.setStatusBarColor(TOP_BAR);
            w.setNavigationBarColor(BOTTOM_BAR);
        }
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                int dark = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(dark, dark);   // 밝은 바 위에 어두운 아이콘
            }
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }

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
        setContentView(buildRoot());
        web.loadUrl(BASE + "app.html");

        if (Build.VERSION.SDK_INT >= 33) {
            // Android 16(API 36) 목표부터는 onBackPressed가 불리지 않는다. 새 방식으로 뒤로 가기를 받는다.
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        }
    }

    /**
     * Android 15+(API 35)는 앱 화면을 상태 바·내비게이션 바 밑까지 강제로 그린다(edge-to-edge).
     * 웹 화면이 바에 가리지 않도록, 바 높이만큼 색 띠를 위아래에 두고 WebView는 그 사이에 둔다.
     * 키보드가 올라오면 아래 띠가 키보드 높이만큼 늘어나 입력칸이 가리지 않는다.
     * Android 14 이하에서는 시스템이 이미 바 영역을 비워 두므로 띠 높이는 0이 된다.
     */
    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(TOP_BAR);
        View top = new View(this), bottom = new View(this);
        top.setBackgroundColor(TOP_BAR);
        bottom.setBackgroundColor(BOTTOM_BAR);
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((v, in) -> {
                android.graphics.Insets bars = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = in.getInsets(WindowInsets.Type.ime());
                v.setPadding(bars.left, 0, bars.right, 0);
                top.getLayoutParams().height = bars.top;
                bottom.getLayoutParams().height = Math.max(bars.bottom, ime.bottom);
                top.requestLayout();
                bottom.requestLayout();
                return WindowInsets.CONSUMED;
            });
        }
        return root;
    }

    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.evaluateJavascript("window.WY&&WY.onResume&&WY.onResume()", null);
    }

    /** Android 12 이하용. 13 이상은 onCreate에서 등록한 콜백이 handleBack을 부른다. */
    @Override public void onBackPressed() { handleBack(); }

    /** 웹이 열린 시트·화면을 먼저 닫게 하고, 닫을 게 없으면 앱을 끄지 않고 뒤로 보낸다. */
    private void handleBack() {
        if (web == null) { moveTaskToBack(true); return; }
        web.evaluateJavascript("(window.WY&&WY.onBack&&WY.onBack())?1:0", val -> {
            if (!"1".equals(val)) moveTaskToBack(true);
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
