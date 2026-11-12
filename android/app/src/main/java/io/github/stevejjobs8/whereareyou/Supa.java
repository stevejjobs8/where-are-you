package io.github.stevejjobs8.whereareyou;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Supabase RPC 호출 (공개용 publishable key만 사용. 표는 잠겨 있고 함수로만 드나듦) */
final class Supa {
    static final String URL_BASE = "https://vjfzfjjktszxgqhwxqko.supabase.co";
    static final String KEY = "sb_publishable_HvlTYMKZ4AQzELwmWqcS9g_SW29EBwB";
    static final String VIEW_BASE = "https://stevejjobs8.github.io/where-are-you/?c=";

    static String rpc(String name, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(URL_BASE + "/rest/v1/rpc/" + name).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setDoOutput(true);
        c.setRequestProperty("apikey", KEY);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream os = c.getOutputStream()) { os.write(json.getBytes(StandardCharsets.UTF_8)); }
        int code = c.getResponseCode();
        InputStream is = code < 400 ? c.getInputStream() : c.getErrorStream();
        String body = "";
        if (is != null) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while ((n = is.read(b)) > 0) bo.write(b, 0, n);
            body = bo.toString("UTF-8");
        }
        c.disconnect();
        if (code >= 400) throw new Exception(name + " " + code + " " + body);
        return body;
    }

    private Supa() {}
}
