package cz.mushroomfarm.bridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BridgeService extends Service implements BleScanner.Listener {
    public static final String ACTION_READING = "cz.mushroomfarm.bridge.READING";
    public static final String ACTION_STATUS = "cz.mushroomfarm.bridge.STATUS";
    public static final String ACTION_SYNC_NOW = "cz.mushroomfarm.bridge.SYNC_NOW";
    public static final String ACTION_STOP = "cz.mushroomfarm.bridge.STOP";

    private static final String CHANNEL_ID = "mushroom_bridge_v3_scan";
    private static final int NOTIFICATION_ID = 73;
    private static final long AWAY_AFTER_MS = 10 * 60_000L;
    private static final long FRESH_MS = 3 * 60_000L;
    private static final long RECORD_INTERVAL_MS = 5 * 60_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private BleScanner scanner;
    private SensorReading latestIn;
    private SensorReading latestOut;
    private long lastSeenAny;
    private long lastRecordedMs;
    private boolean visitActive;
    private String visitId;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Čekám na SwitchBot Meter Plus…"));
        scanner = new BleScanner(this, this);
        startScannerIfPermitted();
        handler.post(presenceCheck);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null && ACTION_SYNC_NOW.equals(intent.getAction())) recordCurrent(true);
        startScannerIfPermitted();
        return START_STICKY;
    }

    private void startScannerIfPermitted() {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            broadcastStatus("Chybí oprávnění BLUETOOTH_SCAN.");
            return;
        }
        scanner.start();
    }

    @Override public void onReading(SensorReading reading) {
        SharedPreferences p = getSharedPreferences("bridge", MODE_PRIVATE);
        String inMac = normalizeMac(p.getString("in_mac", ""));
        String outMac = normalizeMac(p.getString("out_mac", ""));
        String mac = normalizeMac(reading.mac);

        Intent discovered = new Intent(ACTION_READING).setPackage(getPackageName());
        discovered.putExtra("mac", reading.mac);
        discovered.putExtra("temperature", reading.temperatureC);
        discovered.putExtra("humidity", reading.humidityRH);
        discovered.putExtra("battery", reading.batteryPct);
        discovered.putExtra("rssi", reading.rssi);
        sendBroadcast(discovered);

        boolean configured = false;
        if (!inMac.isEmpty() && mac.equals(inMac)) { latestIn = reading; configured = true; }
        if (!outMac.isEmpty() && mac.equals(outMac)) { latestOut = reading; configured = true; }
        if (!configured) return;

        lastSeenAny = System.currentTimeMillis();
        if (!visitActive) {
            visitActive = true;
            visitId = Instant.now().toString() + "-" + UUID.randomUUID().toString().substring(0, 8);
            lastRecordedMs = 0;
            broadcastStatus("Stanice v dosahu. Čekám na kompletní dvojici IN + OUT…");
        }

        if (fresh(latestIn) && fresh(latestOut)) recordCurrent(false);
        updateNotification();
    }

    private boolean fresh(SensorReading r) {
        return r != null && System.currentTimeMillis() - r.sampledAtMs < FRESH_MS;
    }

    private void recordCurrent(boolean forced) {
        SensorReading in = fresh(latestIn) ? latestIn : null;
        SensorReading out = fresh(latestOut) ? latestOut : null;
        if (in == null && out == null) {
            broadcastStatus("Žádné čerstvé měření k uložení.");
            return;
        }
        long now = System.currentTimeMillis();
        if (!forced) {
            if (in == null || out == null) return;
            if (lastRecordedMs > 0 && now - lastRecordedMs < RECORD_INTERVAL_MS) return;
        }
        lastRecordedMs = now;
        String id = visitId != null ? visitId : Instant.now().toString() + "-manual";
        io.execute(() -> {
            try {
                LocalCsvStore.append(this, id, in, out);
                broadcastStatus("Uloženo lokálně · " + formatPair(in, out) + " · celkem " + LocalCsvStore.countRecords(this) + " řádků");
            } catch (Exception e) {
                broadcastStatus("Lokální uložení selhalo: " + e.getMessage());
            }
        });
    }

    private final Runnable presenceCheck = new Runnable() {
        @Override public void run() {
            long now = System.currentTimeMillis();
            if (visitActive && lastSeenAny > 0 && now - lastSeenAny > AWAY_AFTER_MS) {
                visitActive = false;
                visitId = null;
                latestIn = null;
                latestOut = null;
                lastRecordedMs = 0;
                broadcastStatus("Stanice mimo dosah. Čekám na další návštěvu.");
                updateNotification();
            }
            handler.postDelayed(this, 60_000L);
        }
    };

    @Override public void onError(String message) { broadcastStatus(message); }

    private void broadcastStatus(String message) {
        Intent i = new Intent(ACTION_STATUS).setPackage(getPackageName());
        i.putExtra("message", message);
        sendBroadcast(i);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(message));
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, buildNotification(formatPair(latestIn, latestOut)));
    }

    private String formatPair(SensorReading in, SensorReading out) {
        String a = in == null ? "IN —" : String.format(Locale.US, "IN %.1f °C / %d%%", in.temperatureC, in.humidityRH);
        String b = out == null ? "OUT —" : String.format(Locale.US, "OUT %.1f °C / %d%%", out.temperatureC, out.humidityRH);
        return a + " · " + b;
    }

    private Notification buildNotification(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return b.setContentTitle("Mushroom Bridge 3")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "Mushroom Bridge 3 BLE", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Lokální záznam teploty a relativní vlhkosti ze SwitchBot Meter Plus.");
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    private String normalizeMac(String mac) { return mac == null ? "" : mac.trim().toUpperCase(Locale.US); }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (scanner != null) scanner.stop();
        io.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
