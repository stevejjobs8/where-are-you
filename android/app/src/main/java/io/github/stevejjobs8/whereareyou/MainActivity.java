package io.github.stevejjobs8.whereareyou;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
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
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 첫 화면: 위쪽은 짝꿍의 미니어처 지도(웹 화면), 아래쪽은 내 공유 상태와 버튼.
 * 짝꿍 연결: 한 사람이 8자리 초대 코드를 만들고, 다른 사람이 입력하면 서로 연결됨.
 */
public class MainActivity extends Activity {
    static final String MAP_BASE = "https://stevejjobs8.github.io/where-are-you/";
    private SharedPreferences sp;
    private TextView state, status, empty;
    private Button toggle, pairBtn;
    private WebView web;
    private String loadedPartner = null;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private AlertDialog inviteDialog;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences("share", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#E3EBF6"));

        FrameLayout top = new FrameLayout(this);
        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        web.setWebViewClient(new WebViewClient());
        web.setBackgroundColor(Color.parseColor("#E3EBF6"));
        top.addView(web, new FrameLayout.LayoutParams(-1, -1));
        empty = text("짝꿍을 연결하면 여기에 그 사람이 미니어처 지도로 보여요", 17, "#646B8E");
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(30), 0, dp(30), 0);
        top.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        root.addView(top, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(14), dp(18), dp(18));
        GradientDrawable pbg = new GradientDrawable(); pbg.setColor(Color.WHITE);
        pbg.setCornerRadii(new float[]{dp(24),dp(24),dp(24),dp(24),0,0,0,0});
        panel.setBackground(pbg);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        state = text("", 17, "#2C3150"); state.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(state, new LinearLayout.LayoutParams(0, -2, 1f));
        toggle = pill("", true);
        toggle.setOnClickListener(v -> onToggle());
        head.addView(toggle, new LinearLayout.LayoutParams(-2, dp(46)));
        panel.addView(head);
        status = text("", 13, "#646B8E");
        status.setPadding(0, dp(4), 0, dp(10));
        panel.addView(status);

        LinearLayout row = new LinearLayout(this);
        pairBtn = pill("짝꿍 연결", false);
        pairBtn.setOnClickListener(v -> pairMenu());
        Button nameBtn = pill("내 이름", false);
        nameBtn.setOnClickListener(v -> askName(false));
        Button more = pill("배터리 설정", false);
        more.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()))));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(44), 1f); lp.setMargins(0, 0, dp(8), 0);
        row.addView(pairBtn, lp);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, dp(44), 1f); lp2.setMargins(0, 0, dp(8), 0);
        row.addView(nameBtn, lp2);
        row.addView(more, new LinearLayout.LayoutParams(0, dp(44), 1f));
        panel.addView(row);
        root.addView(panel, new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        render();
        ui.post(new Runnable() { @Override public void run() {
            if (sp.getBoolean("on", false)) status.setText(LocationService.lastStatus);
            ui.postDelayed(this, 1000); } });
        if (sp.getString("name", null) == null) askName(true);
    }

    @Override protected void onResume() { super.onResume(); refreshPartner(); }

    /* ---------- 공유 켜기/끄기 ---------- */
    private void onToggle() {
        if (sp.getBoolean("on", false)) {
            stopService(new Intent(this, LocationService.class));
            sp.edit().putBoolean("on", false).apply();
            status.setText("공유를 껐어요. 짝꿍 화면에서도 내 위치가 사라져요.");
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
        withCreds(this::launch);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) withCreds(this::launch);
        else status.setText("위치 권한이 있어야 공유할 수 있어요");
    }

    private void launch() {
        startForegroundService(new Intent(this, LocationService.class));
        sp.edit().putBoolean("on", true).apply();
        render();
    }

    /* ---------- 서버 계정(보기 코드·쓰기 열쇠) ---------- */
    private void withCreds(Runnable next) {
        if (sp.getString("view_code", null) != null) { next.run(); return; }
        status.setText("처음 준비하는 중이에요");
        bg(() -> {
            String body = Supa.rpc("create_share", "{}");
            JSONObject row = body.trim().startsWith("[") ? new JSONArray(body).getJSONObject(0) : new JSONObject(body);
            sp.edit().putString("view_code", row.getString("view_code")).putString("write_key", row.getString("write_key")).apply();
            String n = sp.getString("name", null);
            if (n != null) Supa.rpc("set_name", auth().put("p_name", n).toString());
            ui.post(next);
        });
    }
    private JSONObject auth() throws Exception {
        return new JSONObject().put("p_view_code", sp.getString("view_code", "")).put("p_write_key", sp.getString("write_key", ""));
    }

    /* ---------- 이름 ---------- */
    private void askName(boolean first) {
        EditText in = new EditText(this);
        in.setHint("예: 하루");
        in.setFilters(new InputFilter[]{new InputFilter.LengthFilter(20)});
        in.setText(sp.getString("name", ""));
        in.setPadding(dp(20), dp(14), dp(20), dp(14));
        new AlertDialog.Builder(this)
                .setTitle(first ? "짝꿍에게 보일 내 이름" : "내 이름 바꾸기")
                .setView(in)
                .setPositiveButton("저장", (d, w) -> {
                    String n = in.getText().toString().trim(); if (n.isEmpty()) return;
                    sp.edit().putString("name", n).apply();
                    withCreds(() -> bg(() -> { Supa.rpc("set_name", auth().put("p_name", n).toString()); ui.post(() -> status.setText("이름을 '" + n + "'(으)로 저장했어요")); }));
                })
                .setNegativeButton("나중에", null)
                .show();
    }

    /* ---------- 짝꿍 연결 ---------- */
    private void pairMenu() {
        String pn = sp.getString("partner_name", null);
        new AlertDialog.Builder(this)
                .setTitle("짝꿍 연결")
                .setMessage(sp.getString("partner", null) != null ? "지금 " + (pn != null ? pn : "짝꿍") + "과(와) 연결돼 있어요. 다시 연결하면 새 짝꿍으로 바뀌어요." :
                        "한 사람이 초대 코드를 만들고, 다른 사람이 그 코드를 입력하면 서로의 위치를 볼 수 있어요.")
                .setPositiveButton("초대 코드 만들기", (d, w) -> withCreds(this::makeInvite))
                .setNegativeButton("코드 입력하기", (d, w) -> withCreds(this::enterInvite))
                .show();
    }

    private void makeInvite() {
        bg(() -> {
            String body = Supa.rpc("pair_create", auth().toString()).trim().replace("\"", "");
            String shown = body.length() == 8 ? body.substring(0, 4) + "-" + body.substring(4) : body;
            ui.post(() -> {
                inviteDialog = new AlertDialog.Builder(this)
                        .setTitle("초대 코드")
                        .setMessage(shown + "\n\n짝꿍 앱에서 '코드 입력하기'에 이 코드를 넣어 주세요. 10분 동안 한 번만 쓸 수 있어요.")
                        .setPositiveButton("코드 보내기", (d, w) -> {
                            Intent i = new Intent(Intent.ACTION_SEND).setType("text/plain")
                                    .putExtra(Intent.EXTRA_TEXT, "지금 너는 어디쯤 짝꿍 초대 코드: " + shown);
                            startActivity(Intent.createChooser(i, "초대 코드 보내기"));
                        })
                        .setNegativeButton("닫기", null)
                        .show();
                pollPartner(0);
            });
        });
    }

    /** 초대한 쪽: 상대가 코드를 넣었는지 3초마다 확인 (최대 10분) */
    private void pollPartner(int n) {
        if (n > 200) return;
        String before = sp.getString("partner", null);
        refreshPartner();
        ui.postDelayed(() -> {
            String now = sp.getString("partner", null);
            if (now != null && !now.equals(before)) {
                if (inviteDialog != null) inviteDialog.dismiss();
                status.setText("짝꿍과 연결됐어요!");
            } else pollPartner(n + 1);
        }, 3000);
    }

    private void enterInvite() {
        EditText in = new EditText(this);
        in.setHint("예: AB12-CD34");
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        in.setPadding(dp(20), dp(14), dp(20), dp(14));
        new AlertDialog.Builder(this)
                .setTitle("짝꿍의 초대 코드")
                .setView(in)
                .setPositiveButton("연결", (d, w) -> {
                    String code = in.getText().toString();
                    bg(() -> {
                        String other = Supa.rpc("pair_accept", auth().put("p_code", code).toString()).trim().replace("\"", "");
                        sp.edit().putString("partner", other).apply();
                        ui.post(() -> { status.setText("짝꿍과 연결됐어요!"); refreshPartner(); });
                    });
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void refreshPartner() {
        if (sp.getString("view_code", null) == null) { render(); return; }
        bg(() -> {
            String body = Supa.rpc("get_partner", auth().toString());
            JSONArray arr = new JSONArray(body);
            if (arr.length() > 0 && !arr.getJSONObject(0).isNull("partner")) {
                JSONObject o = arr.getJSONObject(0);
                sp.edit().putString("partner", o.getString("partner"))
                        .putString("partner_name", o.isNull("partner_name") ? null : o.getString("partner_name")).apply();
            }
            ui.post(this::render);
        });
    }

    /* ---------- 화면 ---------- */
    private void render() {
        boolean on = sp.getBoolean("on", false);
        state.setText(on ? "● 내 위치 공유 중" : "내 위치 공유 꺼짐");
        state.setTextColor(Color.parseColor(on ? "#2E9E6A" : "#2C3150"));
        toggle.setText(on ? "멈추기" : "공유 시작");
        stylePill(toggle, !on);
        String partner = sp.getString("partner", null), pn = sp.getString("partner_name", null);
        pairBtn.setText(partner != null ? (pn != null ? pn : "짝꿍") + " 연결됨" : "짝꿍 연결");
        if (partner != null && !partner.equals(loadedPartner)) {
            loadedPartner = partner;
            web.loadUrl(MAP_BASE + "?c=" + partner + "&app=1");
        }
        empty.setVisibility(partner != null ? View.GONE : View.VISIBLE);
        if (!on && status.getText().length() == 0) status.setText("공유를 켜면 짝꿍 화면에 내가 나타나요");
    }

    interface Job { void run() throws Exception; }
    private void bg(Job j) {
        new Thread(() -> {
            try { j.run(); }
            catch (Exception e) {
                String m = String.valueOf(e.getMessage());
                String nice = m.contains("invite expired") ? "코드가 틀렸거나 10분이 지났어요" : m.contains("own code") ? "내가 만든 코드는 입력할 수 없어요" : "서버 연결 실패: " + m;
                ui.post(() -> status.setText(nice));
            }
        }).start();
    }

    private TextView text(String s, int size, String color) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(Color.parseColor(color)); t.setLineSpacing(0, 1.2f); return t;
    }
    private Button pill(String s, boolean primary) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setPadding(dp(16), 0, dp(16), 0); stylePill(b, primary); return b;
    }
    private void stylePill(Button b, boolean primary) {
        GradientDrawable bg = new GradientDrawable(); bg.setCornerRadius(dp(23));
        if (primary) { bg.setColor(Color.parseColor("#FF6F91")); b.setTextColor(Color.WHITE); }
        else { bg.setColor(Color.WHITE); bg.setStroke(dp(2), Color.parseColor("#CFD8EA")); b.setTextColor(Color.parseColor("#2C3150")); }
        b.setBackground(bg);
    }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
