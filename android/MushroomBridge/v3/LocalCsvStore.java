package cz.mushroomfarm.bridge;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class LocalCsvStore {
    private static final Object LOCK = new Object();
    private static final String NAME = "mushroom-bridge-measurements.csv";
    private static final String HEADER = "cas_lokalni;cas_utc;navsteva;IN_teplota_C;IN_RH_pct;IN_baterie_pct;IN_RSSI_dBm;OUT_teplota_C;OUT_RH_pct;OUT_baterie_pct;OUT_RSSI_dBm\n";
    private static final DateTimeFormatter LOCAL_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FILE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm").withZone(ZoneId.systemDefault());

    private LocalCsvStore() {}

    public static void append(Context context, String visitId, SensorReading in, SensorReading out) throws Exception {
        synchronized (LOCK) {
            File f = new File(context.getFilesDir(), NAME);
            boolean empty = !f.exists() || f.length() == 0;
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
                if (empty) w.write(HEADER);
                Instant now = Instant.now();
                StringBuilder b = new StringBuilder();
                b.append(LOCAL_FMT.format(now)).append(';')
                        .append(now.toString()).append(';')
                        .append(clean(visitId)).append(';');
                appendSensor(b, in);
                appendSensor(b, out);
                b.append('\n');
                w.write(b.toString());
            }
        }
    }

    private static void appendSensor(StringBuilder b, SensorReading r) {
        if (r == null) {
            b.append(";;;;");
            return;
        }
        b.append(String.format(Locale.US, "%.1f", r.temperatureC)).append(';')
                .append(r.humidityRH).append(';')
                .append(r.batteryPct).append(';')
                .append(r.rssi).append(';');
    }

    public static int countRecords(Context context) {
        synchronized (LOCK) {
            File f = new File(context.getFilesDir(), NAME);
            if (!f.exists()) return 0;
            int lines = 0;
            try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                while (r.readLine() != null) lines++;
            } catch (Exception ignored) {}
            return Math.max(0, lines - 1);
        }
    }

    public static File createShareCopy(Context context) throws Exception {
        synchronized (LOCK) {
            File src = new File(context.getFilesDir(), NAME);
            if (!src.exists() || src.length() == 0) {
                try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(src), StandardCharsets.UTF_8)) { w.write(HEADER); }
            }
            File dir = new File(context.getCacheDir(), "exports");
            if (!dir.exists() && !dir.mkdirs()) throw new Exception("Nelze vytvořit exportní složku");
            File dst = new File(dir, "MushroomBridge_v3_" + FILE_FMT.format(Instant.now()) + ".csv");
            try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return dst;
        }
    }

    public static void clear(Context context) {
        synchronized (LOCK) {
            File f = new File(context.getFilesDir(), NAME);
            if (f.exists()) f.delete();
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace(';', '-').replace('\n', ' ').replace('\r', ' ');
    }
}
