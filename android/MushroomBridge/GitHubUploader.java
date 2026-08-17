package cz.mushroomfarm.bridge;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

public final class GitHubUploader {
    public static final class Config {
        public String token;
        public String owner;
        public String repo;
        public String branch;
        public String livePath;
        public String historyPath;
    }

    private final Config config;
    private String authenticatedLogin = "";

    public GitHubUploader(Config config) {
        this.config = config;
        normalizeConfig();
    }

    public void uploadVisit(String visitId, SensorReading in, SensorReading out) throws Exception {
        validateToken();

        JSONObject visit = new JSONObject();
        visit.put("schemaVersion", 1);
        visit.put("visitId", visitId);
        visit.put("updatedAt", Instant.now().toString());
        visit.put("source", "Mushroom Bridge Android");
        JSONObject sensors = new JSONObject();
        if (in != null) sensors.put("ENV-IN-01", in.toJson());
        if (out != null) sensors.put("ENV-OUT-01", out.toJson());
        visit.put("sensors", sensors);

        upsertJson(config.livePath, visit, "Mushroom Bridge: aktualizace mikroklimatu");
        appendHistory(visit);
    }

    private void normalizeConfig() {
        config.token = clean(config.token);
        config.owner = clean(config.owner);
        config.repo = clean(config.repo);
        config.branch = clean(config.branch);
        config.livePath = clean(config.livePath);
        config.historyPath = clean(config.historyPath);
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    private void validateToken() throws Exception {
        if (config.token.isEmpty()) throw new Exception("GitHub token chybí.");
        if (config.owner.isEmpty() || config.repo.isEmpty()) throw new Exception("GitHub owner/repo není vyplněn.");

        HttpURLConnection c = open("https://api.github.com/user", "GET");
        int code = c.getResponseCode();
        String body = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code == 401) {
            throw new Exception("GitHub token je neplatný nebo expirovaný (401). Vytvoř nový fine-grained token a vlož ho do aplikace.");
        }
        if (code == 403) {
            throw new Exception("GitHub token byl rozpoznán, ale GitHub ho odmítl (403): " + compact(body));
        }
        if (code < 200 || code >= 300) {
            throw new Exception("Test GitHub tokenu selhal HTTP " + code + ": " + compact(body));
        }
        try {
            authenticatedLogin = new JSONObject(body).optString("login", "");
        } catch (Exception ignored) {}
    }

    private void appendHistory(JSONObject visit) throws Exception {
        RemoteFile remote = getRemoteFile(config.historyPath);
        JSONArray history = new JSONArray();
        if (remote != null && remote.decodedContent != null && !remote.decodedContent.trim().isEmpty()) {
            JSONObject root = new JSONObject(remote.decodedContent);
            history = root.optJSONArray("visits");
            if (history == null) history = new JSONArray();
        }

        String id = visit.optString("visitId");
        boolean exists = false;
        for (int i = 0; i < history.length(); i++) {
            JSONObject item = history.optJSONObject(i);
            if (item != null && id.equals(item.optString("visitId"))) {
                exists = true;
                break;
            }
        }
        if (!exists) history.put(visit);

        while (history.length() > 5000) {
            JSONArray trimmed = new JSONArray();
            for (int i = 1; i < history.length(); i++) trimmed.put(history.get(i));
            history = trimmed;
        }

        JSONObject root = new JSONObject();
        root.put("schemaVersion", 1);
        root.put("updatedAt", Instant.now().toString());
        root.put("visits", history);
        putFile(config.historyPath, root.toString(2), remote != null ? remote.sha : null,
                "Mushroom Bridge: archiv měření");
    }

    private void upsertJson(String path, JSONObject value, String message) throws Exception {
        RemoteFile remote = getRemoteFile(path);
        putFile(path, value.toString(2), remote != null ? remote.sha : null, message);
    }

    private static final class RemoteFile {
        String sha;
        String decodedContent;
    }

    private RemoteFile getRemoteFile(String path) throws Exception {
        String url = "https://api.github.com/repos/" + enc(config.owner) + "/" + enc(config.repo) +
                "/contents/" + encodePath(path) + "?ref=" + enc(config.branch);
        HttpURLConnection c = open(url, "GET");
        int code = c.getResponseCode();
        if (code == 404) return null;
        String body = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code < 200 || code >= 300) throw new Exception("GitHub GET " + code + ": " + compact(body));
        JSONObject obj = new JSONObject(body);
        RemoteFile f = new RemoteFile();
        f.sha = obj.optString("sha", null);
        String b64 = obj.optString("content", "").replace("\n", "");
        if (!b64.isEmpty()) {
            f.decodedContent = new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8);
        }
        return f;
    }

    private void putFile(String path, String content, String sha, String message) throws Exception {
        String url = "https://api.github.com/repos/" + enc(config.owner) + "/" + enc(config.repo) +
                "/contents/" + encodePath(path);
        JSONObject payload = new JSONObject();
        payload.put("message", message);
        payload.put("branch", config.branch);
        payload.put("content", Base64.encodeToString(content.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
        if (sha != null && !sha.isEmpty()) payload.put("sha", sha);

        HttpURLConnection c = open(url, "PUT");
        c.setDoOutput(true);
        byte[] bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        c.getOutputStream().write(bytes);
        int code = c.getResponseCode();
        String body = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
        if (code >= 200 && code < 300) return;

        String accepted = clean(c.getHeaderField("X-Accepted-GitHub-Permissions"));
        String granted = clean(c.getHeaderField("X-OAuth-Scopes"));
        String who = authenticatedLogin.isEmpty() ? "" : " Přihlášený GitHub účet: " + authenticatedLogin + ".";

        if (code == 404) {
            StringBuilder m = new StringBuilder();
            m.append("GitHub token je platný, ale nemůže zapisovat do ")
                    .append(config.owner).append('/').append(config.repo).append(" (PUT 404).")
                    .append(who)
                    .append(" U fine-grained tokenu nastav Resource owner = ")
                    .append(config.owner)
                    .append(", Repository access = Only select repositories → ")
                    .append(config.repo)
                    .append(", Repository permissions → Contents = Read and write.");
            if (!accepted.isEmpty()) m.append(" GitHub endpoint vyžaduje: ").append(accepted).append('.');
            if (!granted.isEmpty()) m.append(" Token hlásí scopes: ").append(granted).append('.');
            throw new Exception(m.toString());
        }
        if (code == 403) {
            String extra = accepted.isEmpty() ? "" : " Vyžadováno: " + accepted + ".";
            throw new Exception("GitHub odmítl zápis (403). Zkontroluj Contents: Read and write." + extra + " " + compact(body));
        }
        throw new Exception("GitHub PUT " + code + ": " + compact(body));
    }

    private HttpURLConnection open(String url, String method) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        c.setRequestProperty("Authorization", "Bearer " + config.token);
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setRequestProperty("User-Agent", "MushroomBridge/0.2");
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        return c;
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) b.append(line);
        return b.toString();
    }

    private static String compact(String s) {
        if (s == null) return "";
        String out = s.replace('\n', ' ').replace('\r', ' ').trim();
        return out.length() > 500 ? out.substring(0, 500) + "…" : out;
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }

    private static String encodePath(String path) throws Exception {
        StringBuilder b = new StringBuilder();
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) b.append('/');
            b.append(enc(parts[i]));
        }
        return b.toString();
    }
}
