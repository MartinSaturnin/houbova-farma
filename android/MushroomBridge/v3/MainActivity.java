package cz.mushroomfarm.bridge;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int REQ_PERMISSIONS = 41;
    private static final String DASHBOARD_URL = "https://martinsaturnin.github.io/houbova-farma/";
    private static final String WIKI_URL = "https://martinsaturnin.github.io/houbova-farma/wiki.html";

    private static final int BG = 0xFFF3F1E8;
    private static final int PANEL = 0xFFFBFAF5;
    private static final int INK = 0xFF141611;
    private static final int MUTED = 0xFF6C7165;
    private static final int LINE = 0xFFD8D6CC;
    private static final int ACCENT = 0xFF6F7F52;
    private static final int ACCENT2 = 0xFFBCC79D;
    private static final int WARN = 0xFFD8AD2F;
    private static final int DARK = 0xFF171914;

    private final Map<String, SensorReading> discovered = new LinkedHashMap<>();
    private final List<String> display = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private ListView list;
    private TextView status;
    private TextView inTemp, inRh, inMeta, outTemp, outRh, outMeta, deltaText, recordsCount;
    private int selectedPosition = -1;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (BridgeService.ACTION_READING.equals(intent.getAction())) {
                String mac = intent.getStringExtra("mac");
                SensorReading r = new SensorReading(mac,
                        intent.getDoubleExtra("temperature", 0),
                        intent.getIntExtra("humidity", 0),
                        intent.getIntExtra("battery", 0),
                        System.currentTimeMillis(),
                        intent.getIntExtra("rssi", 0));
                discovered.put(mac, r);
                refreshList();
                refreshCards();
            } else if (BridgeService.ACTION_STATUS.equals(intent.getAction())) {
                String msg = intent.getStringExtra("message");
                if (msg != null) status.setText(msg);
                refreshRecordCount();
            }
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        ensureDefaults();
        requestNeededPermissions();
        refreshCards();
        refreshRecordCount();
    }

    @Override protected void onStart() {
        super.onStart();
        IntentFilter f = new IntentFilter();
        f.addAction(BridgeService.ACTION_READING);
        f.addAction(BridgeService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(receiver, f);
    }

    @Override protected void onStop() {
        try { unregisterReceiver(receiver); } catch (Exception ignored) {}
        super.onStop();
    }

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(18), dp(14), dp(30));
        root.setBackgroundColor(BG);
        scroll.addView(root);

        TextView eyebrow = text("MÁJ FUNGI · DETVA", 11, true, MUTED);
        eyebrow.setLetterSpacing(.16f);
        root.addView(eyebrow);

        TextView title = text("Mushroom\nBridge 3", 42, true, INK);
        title.setLineSpacing(0, .88f);
        title.setLetterSpacing(-.035f);
        title.setPadding(0, dp(3), 0, dp(6));
        root.addView(title);

        TextView subtitle = text("SwitchBot Meter Plus · lokální BLE logger · bez GitHub tokenu", 14, false, MUTED);
        root.addView(subtitle);

        LinearLayout links = new LinearLayout(this);
        links.setOrientation(LinearLayout.HORIZONTAL);
        links.setPadding(0, dp(14), 0, 0);
        Button dashboard = pillButton("Dashboard ↗", DARK, Color.WHITE);
        Button wiki = pillButton("Viki / slovník ↗", PANEL, INK);
        dashboard.setOnClickListener(v -> openUrl(DASHBOARD_URL));
        wiki.setOnClickListener(v -> openUrl(WIKI_URL));
        links.addView(dashboard, weighted(1, 0, 4));
        links.addView(wiki, weighted(1, 4, 0));
        root.addView(links);

        status = text("Připraveno. Spusť Bridge a přibliž se k měřicím stanicím.", 13, true, INK);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackground(round(PANEL, LINE, 16));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.setMargins(0, dp(12), 0, dp(18));
        root.addView(status, statusLp);

        root.addView(sectionEyebrow("MIKROKLIMA"));
        root.addView(sectionTitle("Aktuální hodnoty"));

        LinearLayout sensorRow = new LinearLayout(this);
        sensorRow.setOrientation(LinearLayout.HORIZONTAL);
        sensorRow.addView(sensorCard(true), weighted(1, 0, 5));
        sensorRow.addView(sensorCard(false), weighted(1, 5, 0));
        root.addView(sensorRow);

        deltaText = text("Δ IN/OUT — čekám na oba senzory", 13, true, MUTED);
        deltaText.setPadding(dp(14), dp(12), dp(14), dp(12));
        deltaText.setBackground(round(0xFFE8E9DF, 0xFFE8E9DF, 16));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.setMargins(0, dp(10), 0, dp(22));
        root.addView(deltaText, dlp);

        root.addView(sectionEyebrow("ZÁZNAM"));
        root.addView(sectionTitle("Jedna tabulka pro chat"));
        TextView expl = text("Bridge ukládá společné řádky IN + OUT lokálně v telefonu. Při návštěvě stanice uloží první kompletní dvojici a pak maximálně jeden nový řádek každých 5 minut. Žádný GitHub ani cloud.", 13, false, MUTED);
        root.addView(expl);

        recordsCount = text("0 záznamů", 30, true, INK);
        recordsCount.setLetterSpacing(-.03f);
        recordsCount.setPadding(0, dp(10), 0, dp(5));
        root.addView(recordsCount);

        Button share = largeButton("Vytvořit CSV a sdílet", DARK, Color.WHITE);
        share.setOnClickListener(v -> shareCsv());
        root.addView(share);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button start = pillButton("● Spustit Bridge", ACCENT, Color.WHITE);
        Button saveNow = pillButton("+ Uložit teď", WARN, INK);
        start.setOnClickListener(v -> startBridge());
        saveNow.setOnClickListener(v -> saveSnapshotNow());
        controls.addView(start, weighted(1, 0, 4));
        controls.addView(saveNow, weighted(1, 4, 0));
        root.addView(controls);

        Button stop = ghostButton("Zastavit Bridge");
        stop.setOnClickListener(v -> startService(new Intent(this, BridgeService.class).setAction(BridgeService.ACTION_STOP)));
        root.addView(stop);

        root.addView(sectionEyebrow("SENZORY"));
        root.addView(sectionTitle("Přiřazení IN / OUT"));
        TextView help = text("Vyber nalezený SwitchBot a přiřaď ho. Pro tuto farmu jsou předvyplněné poslední známé adresy; kdykoliv je můžeš změnit.", 13, false, MUTED);
        root.addView(help);

        list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setDividerHeight(1);
        list.setBackground(round(PANEL, LINE, 18));
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice, display);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> selectedPosition = position);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180));
        listLp.setMargins(0, dp(10), 0, dp(8));
        root.addView(list, listLp);

        LinearLayout assign = new LinearLayout(this);
        assign.setOrientation(LinearLayout.HORIZONTAL);
        Button setIn = pillButton("Přiřadit jako IN", ACCENT2, INK);
        Button setOut = pillButton("Přiřadit jako OUT", 0xFFE7D7C8, INK);
        setIn.setOnClickListener(v -> assignSelected("in_mac"));
        setOut.setOnClickListener(v -> assignSelected("out_mac"));
        assign.addView(setIn, weighted(1, 0, 4));
        assign.addView(setOut, weighted(1, 4, 0));
        root.addView(assign);

        TextView privacy = text("CSV obsahuje čas, teplotu, RH, baterii a RSSI. MAC adresy se do exportu nezapisují. Data zůstávají v telefonu, dokud je nesdílíš.", 12, false, MUTED);
        privacy.setPadding(0, dp(12), 0, dp(4));
        root.addView(privacy);

        Button clear = ghostButton("Vymazat lokální historii");
        clear.setOnClickListener(v -> {
            LocalCsvStore.clear(this);
            refreshRecordCount();
            Toast.makeText(this, "Lokální tabulka byla vymazána.", Toast.LENGTH_SHORT).show();
        });
        root.addView(clear);

        return scroll;
    }

    private View sensorCard(boolean inside) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(14), dp(14), dp(14));
        card.setBackground(round(inside ? DARK : PANEL, inside ? DARK : LINE, 22));

        int main = inside ? Color.WHITE : INK;
        int sub = inside ? 0xFFB8BDAF : MUTED;
        TextView e = text(inside ? "IN · HOUBOVÝ SKLÁDEK" : "OUT · DVEŘE", 10, true, sub);
        e.setLetterSpacing(.08f);
        card.addView(e);

        TextView temp = text("—°", 40, true, main);
        temp.setLetterSpacing(-.04f);
        temp.setPadding(0, dp(13), 0, 0);
        card.addView(temp);
        TextView rh = text("— % RH", 23, true, main);
        rh.setPadding(0, 0, 0, dp(11));
        card.addView(rh);
        TextView meta = text("čekám na BLE", 11, false, sub);
        card.addView(meta);

        if (inside) { inTemp = temp; inRh = rh; inMeta = meta; }
        else { outTemp = temp; outRh = rh; outMeta = meta; }
        return card;
    }

    private void refreshCards() {
        SharedPreferences p = getSharedPreferences("bridge", MODE_PRIVATE);
        SensorReading in = findByMac(p.getString("in_mac", ""));
        SensorReading out = findByMac(p.getString("out_mac", ""));
        renderSensor(in, true);
        renderSensor(out, false);
        if (in != null && out != null) {
            double dt = in.temperatureC - out.temperatureC;
            int drh = in.humidityRH - out.humidityRH;
            deltaText.setText(String.format(Locale.US, "Δ IN − OUT   %+.1f °C   ·   %+.0f %% RH", dt, (double) drh));
        } else {
            deltaText.setText("Δ IN/OUT — čekám na oba senzory");
        }
    }

    private void renderSensor(SensorReading r, boolean inside) {
        TextView t = inside ? inTemp : outTemp;
        TextView h = inside ? inRh : outRh;
        TextView m = inside ? inMeta : outMeta;
        if (r == null) {
            t.setText("—°"); h.setText("— % RH"); m.setText("čekám na BLE"); return;
        }
        t.setText(String.format(Locale.US, "%.1f°", r.temperatureC));
        h.setText(String.format(Locale.US, "%d %% RH", r.humidityRH));
        m.setText(String.format(Locale.US, "bat %d %%  ·  RSSI %d dBm", r.batteryPct, r.rssi));
    }

    private SensorReading findByMac(String mac) {
        if (mac == null || mac.trim().isEmpty()) return null;
        for (Map.Entry<String, SensorReading> e : discovered.entrySet()) {
            if (e.getKey().equalsIgnoreCase(mac)) return e.getValue();
        }
        return null;
    }

    private void refreshList() {
        display.clear();
        for (SensorReading r : discovered.values()) display.add(r.displayLine());
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void assignSelected(String key) {
        if (selectedPosition < 0 || selectedPosition >= display.size()) {
            Toast.makeText(this, "Nejdřív vyber senzor.", Toast.LENGTH_SHORT).show();
            return;
        }
        SensorReading r = new ArrayList<>(discovered.values()).get(selectedPosition);
        getSharedPreferences("bridge", MODE_PRIVATE).edit().putString(key, r.mac).apply();
        refreshCards();
        Toast.makeText(this, key.equals("in_mac") ? "Přiřazeno jako IN" : "Přiřazeno jako OUT", Toast.LENGTH_SHORT).show();
    }

    private void ensureDefaults() {
        SharedPreferences p = getSharedPreferences("bridge", MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();
        if (!p.contains("in_mac")) e.putString("in_mac", "EC:6F:00:46:4D:7C");
        if (!p.contains("out_mac")) e.putString("out_mac", "EC:6F:04:06:36:2B");
        e.apply();
    }

    private void startBridge() {
        requestNeededPermissions();
        Intent i = new Intent(this, BridgeService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        status.setText("Bridge běží. Data se ukládají lokálně, jakmile jsou oba senzory v dosahu.");
    }

    private void saveSnapshotNow() {
        Intent i = new Intent(this, BridgeService.class).setAction(BridgeService.ACTION_SYNC_NOW);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
    }

    private void shareCsv() {
        try {
            File file = LocalCsvStore.createShareCopy(this);
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("text/csv");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "Mushroom Bridge 3 — mikroklima");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, "Sdílet tabulku — vyber ChatGPT"));
        } catch (Exception e) {
            Toast.makeText(this, "CSV se nepodařilo vytvořit: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void refreshRecordCount() {
        if (recordsCount != null) recordsCount.setText(LocalCsvStore.countRecords(this) + " záznamů");
    }

    private void requestNeededPermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!permissions.isEmpty()) requestPermissions(permissions.toArray(new String[0]), REQ_PERMISSIONS);
    }

    private TextView sectionEyebrow(String s) {
        TextView v = text(s, 10, true, MUTED);
        v.setLetterSpacing(.14f);
        v.setPadding(0, dp(24), 0, dp(3));
        return v;
    }

    private TextView sectionTitle(String s) {
        TextView v = text(s, 28, true, INK);
        v.setLetterSpacing(-.035f);
        v.setPadding(0, 0, 0, dp(10));
        return v;
    }

    private TextView text(String s, int sp, boolean bold, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private Button largeButton(String label, int bg, int fg) {
        Button b = pillButton(label, bg, fg);
        b.setTextSize(17);
        b.setPadding(dp(15), dp(15), dp(15), dp(15));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, dp(8));
        b.setLayoutParams(lp);
        return b;
    }

    private Button pillButton(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(14);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(11), dp(12), dp(11));
        b.setBackground(round(bg, bg == PANEL ? LINE : bg, 16));
        return b;
    }

    private Button ghostButton(String label) {
        Button b = pillButton(label, PANEL, MUTED);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private LinearLayout.LayoutParams weighted(int weight, int leftMargin, int rightMargin) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        lp.setMargins(dp(leftMargin), 0, dp(rightMargin), 0);
        return lp;
    }

    private GradientDrawable round(int fill, int stroke, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        g.setStroke(dp(1), stroke);
        return g;
    }

    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { Toast.makeText(this, "Odkaz se nepodařilo otevřít.", Toast.LENGTH_SHORT).show(); }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
