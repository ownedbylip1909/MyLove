package pl.ownedbylip.mylove;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

final class RealtimeClient {
    interface Listener {
        void onLetterChanged();
        void onConnectionChanged(boolean connected);
    }

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .pingInterval(25, TimeUnit.SECONDS)
            .build();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private WebSocket socket;
    private boolean stopped = true;
    private int reconnectAttempt;

    RealtimeClient(Listener listener) {
        this.listener = listener;
    }

    void connect(String accessToken) {
        stop();
        stopped = false;
        String websocketUrl = BuildConfig.SUPABASE_URL
                .replace("https://", "wss://")
                .replace("http://", "ws://")
                + "/realtime/v1/websocket?apikey="
                + BuildConfig.SUPABASE_PUBLISHABLE_KEY
                + "&vsn=1.0.0";
        Request request = new Request.Builder().url(websocketUrl).build();
        socket = httpClient.newWebSocket(request, new SocketListener(accessToken));
    }

    void stop() {
        stopped = true;
        reconnectAttempt = 0;
        mainHandler.removeCallbacksAndMessages(null);
        if (socket != null) {
            socket.close(1000, "app_background");
            socket = null;
        }
        listener.onConnectionChanged(false);
    }

    private void scheduleReconnect(String accessToken) {
        if (stopped) {
            return;
        }
        long delay = Math.min(30_000L, (1L << Math.min(reconnectAttempt++, 5)) * 1_000L);
        mainHandler.postDelayed(() -> {
            if (!stopped) {
                Request request = new Request.Builder()
                        .url(BuildConfig.SUPABASE_URL
                                .replace("https://", "wss://")
                                .replace("http://", "ws://")
                                + "/realtime/v1/websocket?apikey="
                                + BuildConfig.SUPABASE_PUBLISHABLE_KEY
                                + "&vsn=1.0.0")
                        .build();
                socket = httpClient.newWebSocket(request, new SocketListener(accessToken));
            }
        }, delay);
    }

    private final class SocketListener extends WebSocketListener {
        private final String accessToken;

        SocketListener(String accessToken) {
            this.accessToken = accessToken;
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            try {
                JSONObject postgresChange = new JSONObject()
                        .put("event", "*")
                        .put("schema", "public")
                        .put("table", "letters");
                JSONObject config = new JSONObject()
                        .put("broadcast", new JSONObject()
                                .put("ack", false)
                                .put("self", false))
                        .put("presence", new JSONObject().put("enabled", false))
                        .put("postgres_changes", new JSONArray().put(postgresChange));
                JSONObject join = new JSONObject()
                        .put("topic", "realtime:mylove-letters")
                        .put("event", "phx_join")
                        .put("payload", new JSONObject()
                                .put("config", config)
                                .put("access_token", accessToken))
                        .put("ref", "1")
                        .put("join_ref", "1");
                webSocket.send(join.toString());
            } catch (Exception exception) {
                webSocket.close(1011, "join_failed");
            }
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            try {
                JSONObject message = new JSONObject(text);
                String event = message.optString("event");
                if ("postgres_changes".equals(event)) {
                    listener.onLetterChanged();
                } else if ("phx_reply".equals(event)) {
                    JSONObject payload = message.optJSONObject("payload");
                    if (payload == null || !"ok".equals(payload.optString("status"))) {
                        return;
                    }
                    reconnectAttempt = 0;
                    listener.onConnectionChanged(true);
                }
            } catch (Exception ignored) {
                // Ignore unrelated or malformed Realtime messages.
            }
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason) {
            listener.onConnectionChanged(false);
            scheduleReconnect(accessToken);
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable throwable, Response response) {
            listener.onConnectionChanged(false);
            scheduleReconnect(accessToken);
        }
    }
}
