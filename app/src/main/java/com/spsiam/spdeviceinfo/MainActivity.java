package com.spsiam.spdeviceinfo;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * SP Device Info — shows ALL real device information using Android APIs only.
 * Anything Android hides is labelled "Not exposed by Android", never guessed.
 * No READ_PHONE_STATE / IMEI: Android 10+ blocks it.
 */
public class MainActivity extends Activity {

    private static final String HIDDEN = "Not exposed by Android";

    private LinearLayout sectionsContainer;
    private TextView summaryText;
    private EditText searchBox;
    private String query = "";
    private String publicIp = "Checking… (tap Refresh if it stays here)";
    private int batteryPct = -1;
    private String batteryStatus = "";
    private List<Section> sections = new ArrayList<Section>();

    private static class Section {
        final String title;
        final List<String[]> rows = new ArrayList<String[]>();
        Section(String title) { this.title = title; }
        void add(String label, String value) {
            rows.add(new String[]{label, value == null || value.trim().length() == 0 ? HIDDEN : value});
        }
    }

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0 && scale > 0) batteryPct = Math.round(level * 100f / scale);
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            batteryStatus = statusText(status);
            collectAndRender();
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        sectionsContainer = (LinearLayout) findViewById(R.id.sectionsContainer);
        summaryText = (TextView) findViewById(R.id.summaryText);
        searchBox = (EditText) findViewById(R.id.searchBox);

        searchBox.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                query = s.toString().toLowerCase(Locale.US).trim();
                render();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        ((Button) findViewById(R.id.refreshBtn)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { fetchPublicIp(); collectAndRender(); toast("Refreshed"); }
        });
        ((Button) findViewById(R.id.copyBtn)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { copyText(buildReport()); toast("Full report copied"); }
        });
        ((Button) findViewById(R.id.shareBtn)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_SEND);
                i.setType("text/plain");
                i.putExtra(Intent.EXTRA_SUBJECT, "SP Device Info Report");
                i.putExtra(Intent.EXTRA_TEXT, buildReport());
                startActivity(Intent.createChooser(i, "Share device report"));
            }
        });
        ((Button) findViewById(R.id.exportBtn)).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { exportReport(); }
        });

        fetchPublicIp();
        collectAndRender();
    }

    @Override protected void onResume() {
        super.onResume();
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    @Override protected void onPause() {
        super.onPause();
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
    }

    // ---------- collection ----------

    private void collectAndRender() {
        sections = collect();
        summaryText.setText(Build.MANUFACTURER + " " + Build.MODEL + "  •  Android " + Build.VERSION.RELEASE + "  •  " + Build.BRAND);
        render();
    }

    private List<Section> collect() {
        List<Section> out = new ArrayList<Section>();

        Section device = new Section("Device");
        device.add("Model", Build.MODEL);
        device.add("Manufacturer", Build.MANUFACTURER);
        device.add("Brand", Build.BRAND);
        device.add("Device", Build.DEVICE);
        device.add("Product", Build.PRODUCT);
        device.add("Board", Build.BOARD);
        device.add("Hardware", Build.HARDWARE);
        device.add("Fingerprint", Build.FINGERPRINT);
        device.add("Serial", serialValue());
        device.add("Host", Build.HOST);
        device.add("User", Build.USER);
        device.add("Time (build)", buildTime());
        out.add(device);

        Section system = new Section("System");
        system.add("Android version", Build.VERSION.RELEASE);
        system.add("SDK level", String.valueOf(Build.VERSION.SDK_INT));
        system.add("Codename", Build.VERSION.CODENAME);
        system.add("Security patch", Build.VERSION.SDK_INT >= 23 ? Build.VERSION.SECURITY_PATCH : HIDDEN);
        system.add("Bootloader", Build.BOOTLOADER);
        system.add("Radio version", radioVersion());
        system.add("Kernel version", System.getProperty("os.version"));
        system.add("Uptime", formatDuration(SystemClock.elapsedRealtime()));
        system.add("Current time", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        system.add("Language", Locale.getDefault().getDisplayLanguage(Locale.ENGLISH) + " (" + Locale.getDefault().getLanguage() + ")");
        system.add("Timezone", java.util.TimeZone.getDefault().getID());
        out.add(system);

        Section cpu = new Section("CPU");
        cpu.add("Supported ABIs", join(Build.SUPPORTED_ABIS, ", "));
        cpu.add("CPU ABI", Build.CPU_ABI);
        cpu.add("CPU ABI2", Build.CPU_ABI2);
        cpu.add("Available processors", String.valueOf(Runtime.getRuntime().availableProcessors()));
        for (String[] row : cpuinfoRows()) cpu.add(row[0], row[1]);
        out.add(cpu);

        Section mem = new Section("Memory");
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            mem.add("Total RAM", fmtBytes(mi.totalMem));
            mem.add("Available RAM", fmtBytes(mi.availMem));
            mem.add("Used RAM", fmtBytes(mi.totalMem - mi.availMem));
            mem.add("Low memory", mi.lowMemory ? "Yes" : "No");
            mem.add("Threshold", fmtBytes(mi.threshold));
        } catch (Exception e) { mem.add("RAM", HIDDEN); }
        out.add(mem);

        Section storage = new Section("Storage");
        try {
            StatFs internal = new StatFs(Environment.getDataDirectory().getPath());
            storage.add("Internal total", fmtBytes(statTotal(internal)));
            storage.add("Internal free", fmtBytes(statFree(internal)));
            storage.add("Internal available", fmtBytes(statAvail(internal)));
        } catch (Exception e) { storage.add("Internal storage", HIDDEN); }
        try {
            File ext = Environment.getExternalStorageDirectory();
            StatFs s = new StatFs(ext.getPath());
            storage.add("External total", fmtBytes(statTotal(s)));
            storage.add("External free", fmtBytes(statFree(s)));
            storage.add("External state", Environment.getExternalStorageState());
        } catch (Exception e) { storage.add("External storage", HIDDEN); }
        out.add(storage);

        Section battery = new Section("Battery");
        battery.add("Level", batteryPct >= 0 ? batteryPct + "%" : "Checking…");
        battery.add("Status", batteryStatus.length() > 0 ? batteryStatus : "Checking…");
        try {
            BatteryManager bm = (BatteryManager) getSystemService(BATTERY_SERVICE);
            if (bm != null) {
                int cap = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
                if (cap >= 0 && cap <= 100) battery.add("Capacity (BatteryManager)", cap + "%");
                int cur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
                if (cur != Integer.MIN_VALUE && cur != 0) battery.add("Current now", (cur / 1000) + " mA");
                int charge = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER);
                if (charge > 0) battery.add("Charge counter", charge + " µAh");
            }
        } catch (Exception ignored) {}
        battery.add("Health", batteryIntentExtra(BatteryManager.EXTRA_HEALTH, "health"));
        battery.add("Temperature", batteryTemp());
        battery.add("Voltage", batteryIntentExtra(BatteryManager.EXTRA_VOLTAGE, "voltage"));
        battery.add("Technology", batteryIntentExtra(BatteryManager.EXTRA_TECHNOLOGY, "technology"));
        battery.add("Plugged", batteryIntentExtra(BatteryManager.EXTRA_PLUGGED, "plugged"));
        out.add(battery);

        Section display = new Section("Display");
        try {
            DisplayMetrics dm = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(dm);
            display.add("Resolution", dm.widthPixels + " × " + dm.heightPixels + " px");
            display.add("Density", String.valueOf(dm.density));
            display.add("Density DPI", dm.densityDpi + " dpi (" + densityBucket(dm.densityDpi) + ")");
            display.add("Scaled density", String.valueOf(dm.scaledDensity));
            display.add("Width / height (dp)", Math.round(dm.widthPixels / dm.density) + " × " + Math.round(dm.heightPixels / dm.density) + " dp");
            display.add("XDPI / YDPI", dm.xdpi + " / " + dm.ydpi);
            try {
                Object rate = getWindowManager().getDefaultDisplay().getClass()
                        .getMethod("getRefreshRate").invoke(getWindowManager().getDefaultDisplay());
                display.add("Refresh rate", rate + " Hz");
            } catch (Throwable t) { display.add("Refresh rate", HIDDEN); }
            display.add("Orientation", getResources().getConfiguration().orientation == 2 ? "Landscape" : "Portrait");
        } catch (Exception e) { display.add("Display", HIDDEN); }
        out.add(display);

        Section network = new Section("Network");
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm.getActiveNetworkInfo();
            network.add("Connected", ni != null && ni.isConnected() ? "Yes" : "No");
            network.add("Connection type", ni != null ? String.valueOf(ni.getTypeName()) : HIDDEN);
            network.add("Subtype", ni != null ? String.valueOf(ni.getSubtypeName()) : HIDDEN);
            network.add("Roaming", ni != null ? String.valueOf(ni.isRoaming()) : HIDDEN);
            network.add("Metered", cm.isActiveNetworkMetered() ? "Yes" : "No");
        } catch (Exception e) { network.add("Connection", HIDDEN); }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) {
                WifiInfo wi = wm.getConnectionInfo();
                String ssid = wi != null ? wi.getSSID() : null;
                network.add("Wi-Fi SSID", ssid == null || ssid.contains("unknown") ? "Hidden — needs Location permission" : ssid);
                network.add("Wi-Fi link speed", wi != null ? wi.getLinkSpeed() + " Mbps" : HIDDEN);
                network.add("Wi-Fi signal (RSSI)", wi != null ? wi.getRssi() + " dBm" : HIDDEN);
                network.add("Wi-Fi IP", wi != null ? intToIp(wi.getIpAddress()) : HIDDEN);
            } else {
                network.add("Wi-Fi", "Off");
            }
        } catch (Exception e) { network.add("Wi-Fi", HIDDEN); }
        network.add("Local IP (all interfaces)", localIp());
        network.add("Public IP", publicIp);
        out.add(network);

        Section sensors = new Section("Sensors");
        try {
            SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);
            List<Sensor> list = sm.getSensorList(Sensor.TYPE_ALL);
            sensors.add("Sensor count", String.valueOf(list.size()));
            for (Sensor s : list) {
                sensors.add(s.getName(), s.getVendor() + " • type " + s.getType() + " • max " + s.getMaximumRange());
            }
        } catch (Exception e) { sensors.add("Sensors", HIDDEN); }
        out.add(sensors);

        return out;
    }

    // ---------- helpers ----------

    private String serialValue() {
        try {
            String s = Build.SERIAL;
            return (s == null || s.equalsIgnoreCase("unknown")) ? HIDDEN : s;
        } catch (Exception e) { return HIDDEN; }
    }

    private String radioVersion() {
        try {
            String r = Build.getRadioVersion();
            return (r == null || r.length() == 0) ? HIDDEN : r;
        } catch (Exception e) { return HIDDEN; }
    }

    private String buildTime() {
        try { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(Build.TIME)); }
        catch (Exception e) { return HIDDEN; }
    }

    private String statusText(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING: return "Charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING: return "Discharging";
            case BatteryManager.BATTERY_STATUS_FULL: return "Full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "Not charging";
            default: return "Unknown";
        }
    }

    private String batteryIntentExtra(String key, String kind) {
        try {
            Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return HIDDEN;
            if ("health".equals(kind)) {
                switch (i.getIntExtra(key, -1)) {
                    case BatteryManager.BATTERY_HEALTH_GOOD: return "Good";
                    case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "Overheat";
                    case BatteryManager.BATTERY_HEALTH_DEAD: return "Dead";
                    case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "Over voltage";
                    case BatteryManager.BATTERY_HEALTH_COLD: return "Cold";
                    default: return "Unknown";
                }
            }
            if ("voltage".equals(kind)) {
                int v = i.getIntExtra(key, -1);
                return v > 0 ? v + " mV" : HIDDEN;
            }
            if ("technology".equals(kind)) {
                String t = i.getStringExtra(key);
                return (t == null || t.length() == 0) ? HIDDEN : t;
            }
            if ("plugged".equals(kind)) {
                switch (i.getIntExtra(key, 0)) {
                    case BatteryManager.BATTERY_PLUGGED_AC: return "AC charger";
                    case BatteryManager.BATTERY_PLUGGED_USB: return "USB";
                    case BatteryManager.BATTERY_PLUGGED_WIRELESS: return "Wireless";
                    default: return "Not plugged in";
                }
            }
        } catch (Exception ignored) {}
        return HIDDEN;
    }

    private String batteryTemp() {
        try {
            Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i == null) return HIDDEN;
            int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
            return t >= 0 ? (t / 10.0) + " °C" : HIDDEN;
        } catch (Exception e) { return HIDDEN; }
    }

    private List<String[]> cpuinfoRows() {
        List<String[]> rows = new ArrayList<String[]>();
        try {
            BufferedReader br = new BufferedReader(new FileReader("/proc/cpuinfo"));
            String line;
            int count = 0;
            while ((line = br.readLine()) != null && count < 8) {
                if (line.trim().length() == 0) continue;
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    rows.add(new String[]{parts[0].trim(), parts[1].trim()});
                    count++;
                }
            }
            br.close();
        } catch (Exception e) {
            rows.add(new String[]{"CPU info (/proc/cpuinfo)", HIDDEN});
        }
        return rows;
    }

    private String localIp() {
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a.getHostAddress().indexOf(':') < 0) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return HIDDEN;
    }

    private String intToIp(int ip) {
        if (ip == 0) return HIDDEN;
        return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "." + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
    }

    private void fetchPublicIp() {
        new Thread(new Runnable() {
            @Override public void run() {
                String ip = null;
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL("https://api.ipify.org").openConnection();
                    c.setConnectTimeout(5000);
                    c.setReadTimeout(5000);
                    BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()));
                    ip = br.readLine();
                    br.close();
                } catch (Exception ignored) {}
                final String result = (ip == null || ip.trim().length() == 0) ? "Not available (offline?)" : ip.trim();
                runOnUiThread(new Runnable() {
                    @Override public void run() { publicIp = result; collectAndRender(); }
                });
            }
        }).start();
    }

    private static long statTotal(StatFs s) { return s.getBlockCountLong() * s.getBlockSizeLong(); }
    private static long statFree(StatFs s) { return s.getFreeBlocksLong() * s.getBlockSizeLong(); }
    private static long statAvail(StatFs s) { return s.getAvailableBlocksLong() * s.getBlockSizeLong(); }

    private static String densityBucket(int dpi) {
        if (dpi <= 120) return "ldpi";
        if (dpi <= 160) return "mdpi";
        if (dpi <= 240) return "hdpi";
        if (dpi <= 320) return "xhdpi";
        if (dpi <= 480) return "xxhdpi";
        return "xxxhdpi";
    }

    private static String join(String[] a, String sep) {
        if (a == null || a.length == 0) return HIDDEN;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(sep); sb.append(a[i]); }
        return sb.toString();
    }

    private static String fmtBytes(long b) {
        if (b <= 0) return "0 B";
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int i = (int) (Math.log(b) / Math.log(1024));
        if (i >= u.length) i = u.length - 1;
        return String.format(Locale.US, "%.2f %s", b / Math.pow(1024, i), u[i]);
    }

    private static String formatDuration(long ms) {
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h + "h " + m + "m " + sec + "s";
    }

    // ---------- rendering / report ----------

    private void render() {
        sectionsContainer.removeAllViews();
        boolean any = false;
        for (final Section sec : sections) {
            List<String[]> visible = new ArrayList<String[]>();
            for (String[] r : sec.rows) {
                if (query.length() == 0 || (r[0] + " " + r[1]).toLowerCase(Locale.US).contains(query)) visible.add(r);
            }
            if (visible.isEmpty()) continue;
            any = true;
            TextView title = new TextView(this);
            title.setText(sec.title);
            title.setTextSize(18);
            title.setTextColor(0xFFFFFFFF);
            title.setPadding(12, 18, 12, 4);
            sectionsContainer.addView(title);
            for (final String[] row : visible) {
                LinearLayout card = new LinearLayout(this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setBackgroundResource(R.drawable.card_bg);
                card.setPadding(14, 10, 14, 10);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, 0, 8);
                card.setLayoutParams(lp);
                TextView label = new TextView(this);
                label.setText(row[0]);
                label.setTextSize(12);
                label.setTextColor(0xFF8AA0C8);
                TextView value = new TextView(this);
                value.setText(row[1]);
                value.setTextSize(14);
                value.setTextColor(0xFFFFFFFF);
                card.addView(label);
                card.addView(value);
                card.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { copyText(row[0] + ": " + row[1]); toast(row[0] + " copied"); }
                });
                sectionsContainer.addView(card);
            }
        }
        if (!any) {
            TextView t = new TextView(this);
            t.setText("No details match your search.");
            t.setTextColor(0xFFC7D6F5);
            t.setPadding(12, 18, 12, 18);
            sectionsContainer.addView(t);
        }
    }

    private String buildReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("SP DEVICE INFO REPORT\n");
        sb.append("Generated: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
          .append(" — Android ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n\n");
        for (Section sec : sections) {
            sb.append("== ").append(sec.title).append(" ==\n");
            for (String[] r : sec.rows) sb.append(r[0]).append(": ").append(r[1]).append("\n");
            sb.append("\n");
        }
        return sb.toString();
    }

    private void copyText(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("SP Device Info", text));
    }

    private void exportReport() {
        try {
            File dir = getExternalFilesDir(null);
            File f = new File(dir, "SPDeviceInfo-report.txt");
            FileWriter w = new FileWriter(f);
            w.write(buildReport());
            w.close();
            toast("Saved: " + f.getAbsolutePath());
        } catch (Exception e) {
            toast("Export failed: " + e.getMessage());
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
