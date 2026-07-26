package pl.ownedbylip.mylove;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class SupabaseClient {
    private static final String TAG = "MyLoveSupabase";
    interface SyncCallback {
        void onSuccess(List<RemoteLetter> letters);
        void onError(String message);
    }

    interface PairingCallback {
        void onSuccess(String pairingCode);
        void onError(String message);
    }

    interface TokenCallback {
        void onSuccess(String accessToken);
        void onError();
    }

    interface ActionCallback {
        void onComplete(boolean success);
    }

    interface DataCallback {
        void onComplete(byte[] data);
    }

    static final class RemoteAttachment {
        final String storagePath;
        final String mimeType;

        RemoteAttachment(String storagePath, String mimeType) {
            this.storagePath = storagePath;
            this.mimeType = mimeType;
        }
    }

    static final class RemoteLetter {
        final String id;
        final String title;
        final String preview;
        final String body;
        final String dateLabel;
        final String publishedAt;
        final boolean read;
        final String readAt;
        final List<RemoteAttachment> attachments;

        RemoteLetter(String id, String title, String preview, String body, String dateLabel,
                     String publishedAt, boolean read, String readAt,
                     List<RemoteAttachment> attachments) {
            this.id = id;
            this.title = title;
            this.preview = preview;
            this.body = body;
            this.dateLabel = dateLabel;
            this.publishedAt = publishedAt;
            this.read = read;
            this.readAt = readAt;
            this.attachments = attachments;
        }
    }

    private static final String PREFS = "supabase_session";
    private static final String ACCESS_TOKEN = "access_token";
    private static final String REFRESH_TOKEN = "refresh_token";
    private static final String EXPIRES_AT = "expires_at";

    private final SharedPreferences preferences;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final SecureRandom secureRandom = new SecureRandom();

    SupabaseClient(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    void syncLetters(SyncCallback callback) {
        executor.execute(() -> {
            try {
                String token = validAccessToken();
                callRpc("ensure_recipient_mailbox",
                        new JSONObject().put("display_name", "Bella"), token);
                callback.onSuccess(fetchLetters(token));
            } catch (Exception exception) {
                callback.onError(exception.getMessage() == null
                        ? "Synchronisierung fehlgeschlagen"
                        : exception.getMessage());
            }
        });
    }

    void createPairingCode(PairingCallback callback) {
        executor.execute(() -> {
            try {
                String token = validAccessToken();
                callRpc("ensure_recipient_mailbox",
                        new JSONObject().put("display_name", "Bella"), token);
                String code = randomPairingCode();
                callRpc("create_mailbox_pairing_code", new JSONObject()
                        .put("pairing_code", code)
                        .put("valid_for_minutes", 30), token);
                callback.onSuccess(code);
            } catch (Exception exception) {
                callback.onError(exception.getMessage() == null
                        ? "Pairing-Code konnte nicht erstellt werden"
                        : exception.getMessage());
            }
        });
    }

    void requestAccessToken(TokenCallback callback) {
        executor.execute(() -> {
            try {
                callback.onSuccess(validAccessToken());
            } catch (Exception exception) {
                callback.onError();
            }
        });
    }

    void markLetterRead(String remoteId, ActionCallback callback) {
        letterAction("mark_letter_read", remoteId, callback);
    }

    void archiveLetter(String remoteId, ActionCallback callback) {
        letterAction("archive_letter", remoteId, callback);
    }

    void deleteLetter(String remoteId, ActionCallback callback) {
        letterAction("delete_letter", remoteId, callback);
    }

    void downloadAttachment(String storagePath, DataCallback callback) {
        executor.execute(() -> {
            try {
                String encodedPath = encodeStoragePath(storagePath);
                HttpURLConnection connection = connection(
                        BuildConfig.SUPABASE_URL
                                + "/storage/v1/object/authenticated/letter-attachments/"
                                + encodedPath,
                        "GET");
                connection.setRequestProperty(
                        "Authorization", "Bearer " + validAccessToken());
                callback.onComplete(readBytes(connection));
            } catch (Exception exception) {
                callback.onComplete(null);
            }
        });
    }

    void shutdown() {
        executor.shutdownNow();
    }

    private void letterAction(String function, String remoteId, ActionCallback callback) {
        if (remoteId == null) {
            callback.onComplete(true);
            return;
        }
        executor.execute(() -> {
            try {
                callRpc(function,
                        new JSONObject().put("letter_id", remoteId),
                        validAccessToken());
                callback.onComplete(true);
            } catch (Exception exception) {
                Log.e(TAG, "RPC " + function + " fehlgeschlagen", exception);
                callback.onComplete(false);
            }
        });
    }

    private String validAccessToken() throws Exception {
        String accessToken = preferences.getString(ACCESS_TOKEN, null);
        long expiresAt = preferences.getLong(EXPIRES_AT, 0);
        if (accessToken != null && System.currentTimeMillis() < expiresAt - 60_000) {
            return accessToken;
        }
        String refreshToken = preferences.getString(REFRESH_TOKEN, null);
        if (refreshToken != null) {
            return saveSession(requestSession(
                    "/auth/v1/token?grant_type=refresh_token",
                    new JSONObject().put("refresh_token", refreshToken)));
        }
        return saveSession(requestSession("/auth/v1/signup", new JSONObject()));
    }

    private JSONObject requestSession(String path, JSONObject body) throws Exception {
        HttpURLConnection connection = connection(BuildConfig.SUPABASE_URL + path, "POST");
        connection.setRequestProperty("Content-Type", "application/json");
        writeBody(connection, body.toString());
        return new JSONObject(readResponse(connection));
    }

    private String saveSession(JSONObject session) throws Exception {
        String accessToken = session.getString("access_token");
        preferences.edit()
                .putString(ACCESS_TOKEN, accessToken)
                .putString(REFRESH_TOKEN, session.getString("refresh_token"))
                .putLong(EXPIRES_AT, System.currentTimeMillis()
                        + session.optLong("expires_in", 3600) * 1000)
                .apply();
        return accessToken;
    }

    private List<RemoteLetter> fetchLetters(String accessToken) throws Exception {
        String endpoint = BuildConfig.SUPABASE_URL
                + "/rest/v1/letters"
                + "?select=id,title,preview,body,date_label,published_at,is_read,read_at,"
                + "archived_at,letter_attachments(storage_path,mime_type)"
                + "&published_at=lte.now()"
                + "&archived_at=is.null"
                + "&deleted_at=is.null"
                + "&order=published_at.desc"
                + "&limit=500";
        HttpURLConnection connection = connection(endpoint, "GET");
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        connection.setRequestProperty("Cache-Control", "no-cache");
        JSONArray json = new JSONArray(readResponse(connection));
        Log.d(TAG, "Geladene Cloud-Briefe: " + json.length());
        List<RemoteLetter> letters = new ArrayList<>();
        for (int i = 0; i < json.length(); i++) {
            JSONObject item = json.getJSONObject(i);
            List<RemoteAttachment> attachments = new ArrayList<>();
            JSONArray attachmentRows = item.optJSONArray("letter_attachments");
            if (attachmentRows != null) {
                for (int j = 0; j < attachmentRows.length(); j++) {
                    JSONObject attachment = attachmentRows.getJSONObject(j);
                    attachments.add(new RemoteAttachment(
                            attachment.getString("storage_path"),
                            attachment.optString("mime_type", "image/jpeg")));
                }
            }
            letters.add(new RemoteLetter(
                    item.getString("id"),
                    item.getString("title"),
                    item.optString("preview", ""),
                    item.getString("body"),
                    item.optString("date_label", "NEU"),
                    item.optString("published_at", ""),
                    item.optBoolean("is_read", false),
                    item.optString("read_at", null),
                    attachments
            ));
        }
        return letters;
    }

    private void callRpc(String function, JSONObject body, String accessToken) throws Exception {
        HttpURLConnection connection = connection(
                BuildConfig.SUPABASE_URL + "/rest/v1/rpc/" + function,
                "POST");
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        connection.setRequestProperty("Content-Type", "application/json");
        writeBody(connection, body.toString());
        readResponse(connection);
    }

    private String randomPairingCode() {
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder code = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            code.append(alphabet.charAt(secureRandom.nextInt(alphabet.length())));
        }
        return code.toString();
    }

    private HttpURLConnection connection(String address, String method) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(15_000);
        connection.setRequestProperty("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY);
        connection.setRequestProperty("Accept", "application/json");
        connection.setDoInput(true);
        return connection;
    }

    private void writeBody(HttpURLConnection connection, String body) throws Exception {
        connection.setDoOutput(true);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(bytes);
        }
    }

    private String readResponse(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        StringBuilder response = new StringBuilder();
        if (stream != null) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }
        }
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Supabase HTTP " + status + ": " + response);
        }
        return response.toString();
    }

    private byte[] readBytes(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
                ? connection.getInputStream()
                : connection.getErrorStream();
        byte[] result = null;
        if (stream != null) {
            try (InputStream input = stream;
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                result = output.toByteArray();
            }
        }
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("Supabase storage HTTP " + status);
        }
        return result;
    }

    private String encodeStoragePath(String path) throws Exception {
        String[] segments = path.split("/");
        StringBuilder encoded = new StringBuilder();
        for (String segment : segments) {
            if (encoded.length() > 0) encoded.append('/');
            encoded.append(java.net.URLEncoder.encode(
                    segment, "UTF-8").replace("+", "%20"));
        }
        return encoded.toString();
    }
}
