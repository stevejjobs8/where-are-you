package io.github.stevejjobs8.whereareyou;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** 공유 켜기/끄기 버튼 하나와 보여줄 사람에게 보낼 링크만 있는 최소 화면 */
public class MainActivity extends Activity {
    private SharedPreferences sp;
    private TextView state, status, link;
    private Button toggle;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("share", MODE_PRIVATE);
        int pad = dp(22);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(pad, dp(36), pad, pad);
        col.setBackgroundColor(Color.parseColor("#E3EBF6"));

        TextView title = text("지금 너는 어디쯤", 26, "#2C3150"); title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);
        col.addView(space(6));
        col.addView(text("공유를 켜면 화면이 꺼져 있어도 몇 초마다 내 위치가 전송돼요. 상단 알림이 떠 있는 동안 공유 중이에요.", 15, "#646B8E"));
        col.addView(space(20));

        state = text("", 20, "#2C3150"); state.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(state);
        status = text("", 14, "#646B8E");
        col.addView(status);
        col.addView(space(14));

        toggle = new Button(this);
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        toggle.setAllCaps(false);
        toggle.setOnClickListener(v -> onToggle());
        col.addView(toggle, new LinearLayout.LayoutParams(-1, dp(58)));
        col.addView(space(26));

        col.addView(text("보여줄 사람에게 보낼 링크", 17, "#2C3150"));
        link = text("공유를 처음 켜면 만들어져요", 14, "#646B8E");
        link.setTextIsSelectable(true);
        col.addView(link);
        Button share = button("링크 보내기");
        share.setOnClickListener(v -> shareLink());
        col.addView(share, new LinearLayout.LayoutParams(-1, dp(50)));
        col.addView(space(26));

        col.addView(text("삼성 폰은 절전 기능이 공유를 멈출 수 있어요. 끊기면 아래 버튼에서 이 앱의 배터리를 '제한 없음'으로 바꿔 주세요.", 13, "#646B8E"));
        Button bat = button("배터리 설정 열기");
        bat.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        col.addView(bat, new LinearLayout.LayoutParams(-1, dp(50)));

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
        render();
        ui.post(new Runnable() { @Override public void run() { status.setText(LocationService.lastStatus); ui.postDelayed(this, 1000); } });
    }

    private void onToggle() {
        if (sp.getBoolean("on", false)) {
            stopService(new Intent(this, LocationService.class));
            sp.edit().putBoolean("on", false).apply();
            render();
            return;
        }
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.ACCESS_FINE_LOCATION); need.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.ACTIVITY_RECOGNITION);
        if (!need.isEmpty()) { requestPermissions(need.toArray(new String[0]), 7); return; }
        startSharing();
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) startSharing();
        else status.setText("위치 권한이 있어야 공유할 수 있어요");
    }

    private void startSharing() {
        if (sp.getString("view_code", null) != null) { launch(); return; }
        status.setText("공유 링크를 만드는 중이에요");
        new Thread(() -> {
            try {
                String body = Supa.rpc("create_share", "{}");
                JSONObject row = body.trim().startsWith("[") ? new JSONArray(body).getJSONObject(0) : new JSONObject(body);
                sp.edit().putString("view_code", row.getString("view_code")).putString("write_key", row.getString("write_key")).apply();
                ui.post(this::launch);
            } catch (Exception e) {
                ui.post(() -> status.setText("서버 연결 실패: " + e.getMessage()));
            }
        }).start();
    }

    private void launch() {
        startForegroundService(new Intent(this, LocationService.class));
        sp.edit().putBoolean("on", true).apply();
        render();
    }

    private void shareLink() {
        String v = sp.getString("view_code", null);
        if (v == null) { status.setText("먼저 공유를 한 번 켜면 링크가 만들어져요"); return; }
        Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, Supa.VIEW_BASE + v);
        startActivity(Intent.createChooser(i, "링크 보내기"));
    }

    private void render() {
        boolean on = sp.getBoolean("on", false);
        state.setText(on ? "● 공유 중" : "공유 꺼짐");
        state.setTextColor(Color.parseColor(on ? "#2E9E6A" : "#2C3150"));
        toggle.setText(on ? "공유 멈추기" : "위치 공유 시작");
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(29));
        bg.setColor(Color.parseColor(on ? "#FFFFFF" : "#FF6F91")); if (on) bg.setStroke(dp(2), Color.parseColor("#FF6F91"));
        toggle.setBackground(bg); toggle.setTextColor(Color.parseColor(on ? "#B3264E" : "#FFFFFF"));
        String v = sp.getString("view_code", null);
        if (v != null) link.setText(Supa.VIEW_BASE + v);
    }

    private TextView text(String s, int sp, String color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(Color.parseColor(color)); t.setLineSpacing(0, 1.2f); return t;
    }
    private Button button(String s) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(25)); bg.setColor(Color.WHITE); bg.setStroke(dp(2), Color.parseColor("#CFD8EA"));
        b.setBackground(bg); b.setTextColor(Color.parseColor("#2C3150")); return b;
    }
    private View space(int h) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h))); return v; }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
