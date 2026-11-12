package io.github.stevejjobs8.whereareyou;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 화면이 꺼져도 위치를 몇 초마다 보내는 포그라운드 서비스 (상단에 알림이 떠 있는 동안 동작) */
public class LocationService extends Service {
    static final String CH = "sharing";
    static final long INTERVAL_MS = 4000;
    static volatile String lastStatus = "";

    private LocationManager lm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private volatile Location best;
    private long lastSentFix = 0;

    private final LocationListener listener = new LocationListener() {
        @Override public void onLocationChanged(Location l) {
            Location b = best;
            // 더 최근이거나 더 정확한 쪽을 씀
            if (b == null || l.getTime() > b.getTime() + 5000 || l.getAccuracy() <= b.getAccuracy() || l.getTime() > b.getTime()) best = l;
        }
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
        @Override public void onStatusChanged(String p, int s, android.os.Bundle e) {}
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            Location l = best;
            if (l != null && l.getTime() != lastSentFix) { lastSentFix = l.getTime(); push(l, true); }
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "위치 공유 중", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("위치를 공유하고 있어요")
                .setContentText("앱을 열어 공유를 멈출 수 있어요")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        else startForeground(1, n);

        lm = getSystemService(LocationManager.class);
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER))
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000, 0, listener, Looper.getMainLooper());
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000, 0, listener, Looper.getMainLooper());
        } catch (SecurityException e) {
            lastStatus = "위치 권한이 없어요";
            stopSelf();
            return START_NOT_STICKY;
        }
        handler.removeCallbacks(tick);
        handler.post(tick);
        lastStatus = "위치를 찾는 중이에요";
        return START_STICKY;
    }

    private void push(Location l, boolean sharing) {
        SharedPreferences sp = getSharedPreferences("share", MODE_PRIVATE);
        String view = sp.getString("view_code", null), write = sp.getString("write_key", null);
        if (view == null || write == null) return;
        io.execute(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("p_view_code", view);
                o.put("p_write_key", write);
                o.put("p_lat", l != null ? l.getLatitude() : JSONObject.NULL);
                o.put("p_lng", l != null ? l.getLongitude() : JSONObject.NULL);
                o.put("p_accuracy", l != null && l.hasAccuracy() ? l.getAccuracy() : JSONObject.NULL);
                o.put("p_speed", l != null && l.hasSpeed() ? l.getSpeed() : JSONObject.NULL);
                o.put("p_heading", l != null && l.hasBearing() ? l.getBearing() : JSONObject.NULL);
                o.put("p_activity", JSONObject.NULL);
                o.put("p_sharing", sharing);
                Supa.rpc("push_location", o.toString());
                lastStatus = sharing ? ("보냄 (정확도 약 " + Math.round(l.getAccuracy()) + "m)") : "공유를 껐어요";
            } catch (Exception e) {
                lastStatus = "전송 실패: " + e.getMessage();
            }
        });
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(tick);
        if (lm != null) lm.removeUpdates(listener);
        push(null, false);           // 서버에서 좌표 지우기
        io.shutdown();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
