package io.github.stevejjobs8.whereareyou;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.google.android.gms.location.ActivityRecognitionResult;
import com.google.android.gms.location.DetectedActivity;

/** 폰의 움직임 센서가 판단한 상태(정지/걷기/뛰기/자전거/차량)를 받아 둠 */
public class ActivityReceiver extends BroadcastReceiver {
    static volatile String activity = "unknown";
    static volatile long at = 0;
    static volatile long lastVehicleAt = 0;

    @Override public void onReceive(Context c, Intent i) {
        if (!ActivityRecognitionResult.hasResult(i)) return;
        ActivityRecognitionResult r = ActivityRecognitionResult.extractResult(i);
        if (r == null) return;
        DetectedActivity a = r.getMostProbableActivity();
        if (a.getConfidence() < 50) return;
        String v = null;
        switch (a.getType()) {
            case DetectedActivity.IN_VEHICLE: v = "vehicle"; break;
            case DetectedActivity.ON_BICYCLE: v = "bike"; break;
            case DetectedActivity.RUNNING: v = "run"; break;
            case DetectedActivity.WALKING:
            case DetectedActivity.ON_FOOT: v = "walk"; break;
            case DetectedActivity.STILL: v = "still"; break;
            default: break;
        }
        if (v == null) return;
        activity = v; at = System.currentTimeMillis();
        if (v.equals("vehicle")) lastVehicleAt = at;
    }
}
