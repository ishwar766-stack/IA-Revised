package com.ia.expenses;
import android.Manifest;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.content.pm.PackageManager;
import android.content.Intent;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.FileProvider;
import com.getcapacitor.*;
import com.getcapacitor.annotation.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@CapacitorPlugin(name = "IaNative", permissions = { @Permission(alias = "sms", strings = { Manifest.permission.READ_SMS }), @Permission(alias = "storage", strings = { Manifest.permission.WRITE_EXTERNAL_STORAGE }) })
public class IaNativePlugin extends Plugin {
  private volatile String data = "{}";
  private boolean started = false;

  @PluginMethod public void setData(PluginCall c) { data = c.getString("json", "{}"); c.resolve(); }

  @PluginMethod public void readSms(PluginCall c) {
    if (getPermissionState("sms") != PermissionState.GRANTED) { requestPermissionForAlias("sms", c, "smsCb"); return; }
    read(c);
  }
  @PermissionCallback private void smsCb(PluginCall c) {
    if (getPermissionState("sms") == PermissionState.GRANTED) read(c); else c.reject("denied");
  }
  private void read(PluginCall c) {
    long since = c.getData().optLong("since", 0);
    try (Cursor cur = getContext().getContentResolver().query(Uri.parse("content://sms/inbox"),
        new String[] { "_id", "address", "body", "date" }, "date>?", new String[] { String.valueOf(since) }, "date ASC")) {
      JSArray a = new JSArray(); int n = 0;
      while (cur != null && cur.moveToNext() && n++ < 500) {
        JSObject o = new JSObject();
        o.put("id", cur.getLong(0)); o.put("from", cur.getString(1)); o.put("body", cur.getString(2)); o.put("date", cur.getLong(3));
        a.put(o);
      }
      JSObject r = new JSObject(); r.put("msgs", a); c.resolve(r);
    } catch (Exception e) { c.reject(String.valueOf(e.getMessage())); }
  }

  @PluginMethod public void start(PluginCall c) {
    if (!started) { started = true; new Thread(this::udp).start(); new Thread(this::http).start(); }
    c.resolve();
  }
  private void udp() {
    try (DatagramSocket s = new DatagramSocket(null)) {
      s.setReuseAddress(true); s.bind(new InetSocketAddress(8766)); byte[] b = new byte[16];
      while (true) {
        DatagramPacket p = new DatagramPacket(b, b.length); s.receive(p);
        if (new String(p.getData(), 0, p.getLength()).equals("IA?")) {
          byte[] r = "IA!".getBytes(); s.send(new DatagramPacket(r, r.length, p.getAddress(), p.getPort()));
        }
      }
    } catch (Exception e) { started = false; }
  }
  private void http() {
    try (ServerSocket ss = new ServerSocket(8765)) {
      while (true) { Socket k = ss.accept(); new Thread(() -> handle(k)).start(); }
    } catch (Exception e) { started = false; }
  }
  private void handle(Socket k) {
    try (k) {
      k.setSoTimeout(5000); InputStream in = k.getInputStream();
      StringBuilder h = new StringBuilder(); int c;
      while ((c = in.read()) != -1) { h.append((char) c); if (h.length() > 4 && h.toString().endsWith("\r\n\r\n")) break; }
      int len = 0;
      for (String l : h.toString().split("\r\n")) if (l.toLowerCase().startsWith("content-length:")) len = Integer.parseInt(l.substring(15).trim());
      if (len > 0) {
        byte[] body = new byte[len]; new DataInputStream(in).readFully(body);
        JSObject o = new JSObject(); o.put("json", new String(body, StandardCharsets.UTF_8)); notifyListeners("peerData", o);
      }
      byte[] out = data.getBytes(StandardCharsets.UTF_8);
      OutputStream os = k.getOutputStream();
      os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: " + out.length + "\r\nConnection: close\r\n\r\n").getBytes());
      os.write(out); os.flush();
    } catch (Exception e) { }
  }

  @PluginMethod public void syncNow(PluginCall c) {
    final String mine = c.getString("json", "{}"); data = mine;
    new Thread(() -> {
      try {
        Set<String> ips = new HashSet<>();
        try (DatagramSocket s = new DatagramSocket()) {
          s.setBroadcast(true); byte[] q = "IA?".getBytes();
          s.send(new DatagramPacket(q, q.length, InetAddress.getByName("255.255.255.255"), 8766));
          s.setSoTimeout(2000); byte[] b = new byte[16];
          while (true) { try { DatagramPacket p = new DatagramPacket(b, b.length); s.receive(p); ips.add(p.getAddress().getHostAddress()); } catch (SocketTimeoutException e) { break; } }
        }
        Set<String> own = new HashSet<>();
        for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
          for (InetAddress a : Collections.list(ni.getInetAddresses())) own.add(a.getHostAddress());
        JSArray peers = new JSArray();
        for (String ip : ips) { if (own.contains(ip)) continue; try { peers.put(post(ip, mine)); } catch (Exception e) { } }
        JSObject r = new JSObject(); r.put("peers", peers); c.resolve(r);
      } catch (Exception e) { c.reject(String.valueOf(e.getMessage())); }
    }).start();
  }
  private boolean safeDone = false;
  private File bdir() { return new File(Environment.getExternalStorageDirectory(), "IA-Expenses"); }
  private boolean storageOk() {
    if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
    return getPermissionState("storage") == PermissionState.GRANTED;
  }
  @PluginMethod public void storageReady(PluginCall c) { JSObject r = new JSObject(); r.put("ok", storageOk()); c.resolve(r); }
  @PluginMethod public void askStorage(PluginCall c) {
    if (Build.VERSION.SDK_INT >= 30) {
      if (!Environment.isExternalStorageManager()) {
        Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getContext().getPackageName()));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); getContext().startActivity(i);
      }
      c.resolve();
    } else requestPermissionForAlias("storage", c, "stCb");
  }
  @PermissionCallback private void stCb(PluginCall c) { c.resolve(); }
  @PluginMethod public void saveBackup(PluginCall c) {
    if (!storageOk()) { c.reject("perm"); return; }
    try {
      File d = bdir(); d.mkdirs();
      File f = new File(d, "ia-expenses-backup.json"), p = new File(d, "ia-expenses-backup-prev.json"), t = new File(d, "tmp.json"), sf = new File(d, "ia-expenses-backup-safe.json");
      byte[] b = c.getString("json", "{}").getBytes(StandardCharsets.UTF_8);
      if (!safeDone && f.exists() && f.length() > b.length) {
        try (InputStream in = new FileInputStream(f); FileOutputStream o = new FileOutputStream(sf)) { byte[] x = new byte[8192]; int n; while ((n = in.read(x)) > 0) o.write(x, 0, n); }
      }
      safeDone = true;
      try (FileOutputStream o = new FileOutputStream(t)) { o.write(b); }
      if (f.exists()) { p.delete(); f.renameTo(p); }
      t.renameTo(f); c.resolve();
    } catch (Exception e) { c.reject(String.valueOf(e.getMessage())); }
  }
  @PluginMethod public void loadBackup(PluginCall c) {
    JSObject r = new JSObject(); r.put("json", "");
    if (!storageOk()) { c.resolve(r); return; }
    try {
      for (String n : new String[] { "ia-expenses-backup.json", "ia-expenses-backup-prev.json" }) {
        File f = new File(bdir(), n);
        if (f.exists() && f.length() > 2) {
          ByteArrayOutputStream o = new ByteArrayOutputStream(); try (InputStream in = new FileInputStream(f)) { byte[] x = new byte[8192]; int k; while ((k = in.read(x)) > 0) o.write(x, 0, k); }
          r.put("json", o.toString("UTF-8")); break;
        }
      }
      c.resolve(r);
    } catch (Exception e) { c.reject(String.valueOf(e.getMessage())); }
  }
  @PluginMethod public void installApk(PluginCall c) {
    final String url = c.getString("url");
    if (Build.VERSION.SDK_INT >= 26 && !getContext().getPackageManager().canRequestPackageInstalls()) {
      Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getContext().getPackageName()));
      i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); getContext().startActivity(i); c.reject("perm"); return;
    }
    new Thread(() -> {
      try {
        File f = new File(getContext().getCacheDir(), "update.apk");
        HttpURLConnection u = (HttpURLConnection) new URL(url).openConnection();
        u.setInstanceFollowRedirects(true); u.setConnectTimeout(10000); u.setReadTimeout(20000);
        int len = u.getContentLength();
        try (InputStream in = u.getInputStream(); FileOutputStream o = new FileOutputStream(f)) {
          byte[] b = new byte[16384]; int n, last = -1; long t = 0;
          while ((n = in.read(b)) > 0) {
            o.write(b, 0, n); t += n;
            if (len > 0) { int p = (int) (t * 100 / len); if (p != last) { last = p; JSObject e = new JSObject(); e.put("p", p); notifyListeners("dl", e); } }
          }
        }
        Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", f);
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(uri, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i); c.resolve();
      } catch (Exception e) { c.reject(String.valueOf(e.getMessage())); }
    }).start();
  }
  private String post(String ip, String body) throws Exception {
    HttpURLConnection u = (HttpURLConnection) new URL("http://" + ip + ":8765/sync").openConnection();
    u.setConnectTimeout(4000); u.setReadTimeout(8000); u.setRequestMethod("POST"); u.setDoOutput(true);
    byte[] b = body.getBytes(StandardCharsets.UTF_8); u.setFixedLengthStreamingMode(b.length); u.getOutputStream().write(b);
    ByteArrayOutputStream o = new ByteArrayOutputStream(); InputStream i = u.getInputStream(); byte[] buf = new byte[8192]; int n;
    while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
    return o.toString("UTF-8");
  }
}
