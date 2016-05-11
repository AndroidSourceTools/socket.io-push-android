package com.yy.httpproxy.socketio;

import android.content.Context;
import android.os.Handler;
import android.util.Base64;
import android.util.Log;

import com.yy.httpproxy.AndroidLoggingHandler;
import com.yy.httpproxy.requester.RequestException;
import com.yy.httpproxy.requester.RequestInfo;
import com.yy.httpproxy.requester.ResponseHandler;
import com.yy.httpproxy.service.ConnectionService;
import com.yy.httpproxy.service.PushedNotification;
import com.yy.httpproxy.stats.Stats;
import com.yy.httpproxy.subscribe.CachedSharedPreference;
import com.yy.httpproxy.subscribe.ConnectCallback;
import com.yy.httpproxy.subscribe.PushCallback;
import com.yy.httpproxy.subscribe.PushSubscriber;
import com.yy.httpproxy.thirdparty.NotificationProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import io.socket.client.IO;
import io.socket.client.Socket;
import io.socket.emitter.Emitter;


public class SocketIOProxyClient implements PushSubscriber {

    private static final int PROTOCOL_VERSION = 1;
    private static String TAG = "SocketIOProxyClient";
    private final NotificationProvider notificationProvider;
    private PushCallback pushCallback;
    private String pushId;
    private NotificationCallback notificationCallback;
    private CachedSharedPreference cachedSharedPreference;
    private Map<String, Boolean> topics = new HashMap<>();
    private Map<String, String> topicToLastPacketId = new HashMap<>();
    private ResponseHandler responseHandler;
    private ConnectCallback connectCallback;
    private boolean connected = false;
    private String uid;
    private Stats stats = new Stats();
    private String host = "";


    public void setResponseHandler(ResponseHandler responseHandler) {
        this.responseHandler = responseHandler;
    }

    public void unsubscribeBroadcast(String topic) {
        topics.remove(topic);
        topicToLastPacketId.remove(topic);
        if (socket.connected()) {
            JSONObject data = new JSONObject();
            try {
                data.put("topic", topic);
                socket.emit("unsubscribeTopic", data);
            } catch (JSONException e) {
            }
        }
    }

    public void setConnectCallback(ConnectionService connectCallback) {
        this.connectCallback = connectCallback;
    }

    public String getHost() {
        return host;
    }

    public interface NotificationCallback {
        void onNotification(PushedNotification notification);
    }

    private final Emitter.Listener connectListener = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            stats.onConnect();
            sendPushIdAndTopicToServer();
            reSendFailedRequest();
        }
    };

    private final Emitter.Listener disconnectListener = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            connected = false;
            uid = null;
            stats.onDisconnect();
            if (connectCallback != null) {
                connectCallback.onDisconnect();
            }
        }
    };

    private void reSendFailedRequest() {
        if (!replyCallbacks.isEmpty()) {
            List<RequestInfo> values = new ArrayList<>(replyCallbacks.values());
            replyCallbacks.clear();
            for (RequestInfo request : values) {
                Log.i(TAG, "StompClient onConnected repost request " + request.getPath());
                request(request);
            }
        }
    }

    public void unbindUid() {
        if (pushId != null && socket.connected()) {
            socket.emit("unbindUid");
        }
    }

    private void sendPushIdAndTopicToServer() {
        if (pushId != null && socket.connected()) {
            Log.i(TAG, "sendPushIdAndTopicToServer " + pushId);
            JSONObject object = new JSONObject();
            try {
                object.put("id", pushId);
                object.put("version", PROTOCOL_VERSION);
                object.put("platform", "android");
                if (topics.size() > 0) {
                    JSONArray array = new JSONArray();
                    object.put("topics", array);
                    for (String topic : topics.keySet()) {
                        array.put(topic);
                    }
                    JSONObject lastPacketIds = new JSONObject();
                    object.put("lastPacketIds", lastPacketIds);
                    for (Map.Entry<String, String> entry : topicToLastPacketId.entrySet()) {
                        if (entry.getValue() != null && topics.containsKey(entry.getKey())) {
                            lastPacketIds.put(entry.getKey(), entry.getValue());
                        }
                    }
                    String lastUniCastId = cachedSharedPreference.get("lastUnicastId");
                    if (lastUniCastId != null) {
                        object.put("lastUnicastId", lastUniCastId);
                    }
                }
                socket.emit("pushId", object);
            } catch (JSONException e) {
                Log.e(TAG, "connectListener error ", e);
            }
        }
    }

    private final Emitter.Listener pushIdListener = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            JSONObject data = (JSONObject) args[0];
            String pushId = data.optString("id");
            uid = data.optString("uid", "");
            Log.v(TAG, "on pushId " + pushId + " ,uid " + uid);
            connected = true;
            if (connectCallback != null) {
                connectCallback.onConnect(uid);
            }
            sendTokenToServer();
        }
    };

    public void sendTokenToServer() {
        if (notificationProvider != null && notificationProvider.getToken() != null && socket.connected()) {
            Log.i(TAG, "sendTokenToServer " + pushId);
            JSONObject object = new JSONObject();
            try {
                object.put("token", notificationProvider.getToken());
                object.put("type", notificationProvider.getType());
                socket.emit("token", object);
            } catch (JSONException e) {
                Log.e(TAG, "sendTokenToServer error ", e);
            }
        }
    }

    private final Emitter.Listener notificationListener = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            if (notificationCallback != null) {
                try {
                    JSONObject data = (JSONObject) args[0];
                    JSONObject android = data.optJSONObject("android");
                    Log.v(TAG, "on notification topic " + android);
                    String id = data.optString("id", null);
                    notificationCallback.onNotification(new PushedNotification(id, android));
                    updateLastPacketId(id, data.optString("ttl", null), data.optString("unicast", null), "noti");
                    long timestamp = data.optLong("timestamp", 0);
                    if (timestamp > 0 && id != null) {
                        JSONObject object = new JSONObject();
                        object.put("id", id);
                        object.put("timestamp", timestamp);
                        socket.emit("notificationReply", object);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "handle notification error ", e);
                }
            }
        }
    };

    private void updateLastPacketId(String id, Object ttl, Object unicast, String topic) {
        Boolean reciveTtl = topics.get(topic);
        if (id != null && ttl != null) {
            Log.v(TAG, "on push topic " + topic + " id " + id);
            if (unicast != null) {
                cachedSharedPreference.save("lastUnicastId", id);
            } else if (reciveTtl && topic != null) {
                topicToLastPacketId.put(topic, id);
            }
        }
    }

    private final Emitter.Listener pushListener = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            if (pushCallback != null) {
                try {
                    JSONObject data = (JSONObject) args[0];
                    String topic = data.optString("topic", null);
                    if (topic == null) {
                        topic = data.optString("t", null);
                    }
                    String dataBase64 = data.optString("data", null);
                    if (dataBase64 == null) {
                        dataBase64 = data.optString("d", null);
                    }
                    byte[] dataBytes;
                    if (dataBase64 == null) {
                        String json = data.optString("j");
                        dataBytes = json.getBytes("UTF-8");
                    } else {
                        dataBytes = Base64.decode(dataBase64, Base64.DEFAULT);
                    }

                    Log.v(TAG, "on push topic " + topic + ",reply " + ", data:" + new String(dataBytes));
                    pushCallback.onPush(topic, dataBytes);

                    String id = data.optString("id", null);
                    if (id == null) {
                        id = data.optString("i", null);
                    }

                    String ttl = data.optString("ttl", null);
                    if (ttl == null) {
                        ttl = data.optString("t", null);
                    }

                    String unicast = data.optString("unicast", null);
                    if (unicast == null) {
                        unicast = data.optString("u", null);
                    }

                    updateLastPacketId(id, ttl, unicast, topic);
                } catch (Exception e) {
                    Log.e(TAG, "handle push error ", e);
                }
            }
        }
    };
    private Map<String, RequestInfo> replyCallbacks = Collections.synchronizedMap(new LinkedHashMap<String, RequestInfo>());
    private Handler handler = new Handler();
    private long timeout = 20000;
    private Runnable timeoutTask = new Runnable() {
        @Override
        public void run() {
            Iterator<Map.Entry<String, RequestInfo>> it = replyCallbacks.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, RequestInfo> pair = it.next();
                RequestInfo request = pair.getValue();
                if (request.timeoutForRequest(timeout)) {
                    Log.i(TAG, "StompClient timeoutForRequest " + request.getPath());
                    if (responseHandler != null) {
                        if (request.getTimestamp() > 0) {
                            stats.reportError(request.getPath());
                        }
                        responseHandler.onResponse(request.getSequenceId(), RequestException.Error.TIMEOUT_ERROR.value, RequestException.Error.TIMEOUT_ERROR.name(), null);
                    }
                    it.remove();
                    continue;
                }
            }
            postTimeout();
        }
    };

    private Runnable statsTask = new Runnable() {
        @Override
        public void run() {
            sendStats();
        }
    };

    private void postTimeout() {
        handler.removeCallbacks(timeoutTask);
        if (replyCallbacks.size() > 0) {
            handler.postDelayed(timeoutTask, 1000);
        }
    }

    private void sendStats() {
        if (socket.connected()) {
            try {
                JSONArray requestStats = stats.getRequestJsonArray();
                if (requestStats.length() > 0) {
                    JSONObject object = new JSONObject();
                    object.put("requestStats", requestStats);
                    socket.emit("stats", object);
                    Log.v(TAG, "send stats " + requestStats.length());
                }
            } catch (JSONException e) {
                Log.e(TAG, "sendStats error", e);
            }
        }
        postStatsTask();
    }

    private void postStatsTask() {
        handler.removeCallbacks(statsTask);
        handler.postDelayed(statsTask, 10 * 60 * 1000L);
    }

    public void reportStats(String path, int successCount, int errorCount, int latency) {
        stats.reportStats(path, successCount, errorCount, latency);
    }

    private final Emitter.Listener httpProxyListener = new Emitter.Listener() {
        @Override
        public void call(Object... args) {
            Log.v(TAG, "httpProxy call " + args + " thread " + Thread.currentThread().getName());
            if (args.length > 0 && args[0] instanceof JSONObject) {
                JSONObject data = (JSONObject) args[0];
                String responseSeqId = data.optString("sequenceId", "");
                RequestInfo request = replyCallbacks.remove(responseSeqId);
                if (request != null && responseHandler != null) {
                    String response = data.optString("data");
                    byte[] decodedResponse = Base64.decode(response, Base64.DEFAULT);
                    Log.i(TAG, "response " + new String(decodedResponse));
                    int code = data.optInt("code", 1);
                    String sequenceId = data.optString("sequenceId", "");
                    String message = data.optString("message", "");
                    responseHandler.onResponse(sequenceId, code, message, decodedResponse);
                    stats.reportSuccess(request.getPath(), request.getTimestamp());
                }
            }
        }
    };

    private Socket socket;

    public SocketIOProxyClient(Context context, String host, NotificationProvider provider) {
        cachedSharedPreference = new CachedSharedPreference(context);
        AndroidLoggingHandler.reset(new AndroidLoggingHandler());
        java.util.logging.Logger.getLogger("").setLevel(Level.FINEST);
        this.host = host;
        this.notificationProvider = provider;
        if (provider == null) {
            topics.put("noti", true);
        }
        try {
            IO.Options opts = new IO.Options();
            opts.transports = new String[]{"websocket"};
            if (host.startsWith("https")) {
                try {
                    opts.sslContext = SSLContext.getInstance("TLS");
                    TrustManager tm = new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                        }

                        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                        }

                        public X509Certificate[] getAcceptedIssuers() {
                            return null;
                        }
                    };
                    opts.sslContext.init(null, new TrustManager[]{tm}, null);
                    opts.hostnameVerifier = new HostnameVerifier() {

                        @Override
                        public boolean verify(String s, SSLSession sslSession) {
                            return true;
                        }
                    };
                } catch (Exception e) {
                    Log.e(TAG, "ssl init error ", e);
                }
            }
            socket = IO.socket(host, opts);
            socket.on("packetProxy", httpProxyListener);
            socket.on(Socket.EVENT_CONNECT, connectListener);
            socket.on("pushId", pushIdListener);
            socket.on("push", pushListener);
            socket.on("p", pushListener);
            socket.on("noti", notificationListener);
            socket.on("n", notificationListener);
            socket.on(Socket.EVENT_DISCONNECT, disconnectListener);
            socket.connect();
            postStatsTask();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }

    }

    public void disconnect() {
        socket.disconnect();
        this.connectCallback = null;
        this.notificationCallback = null;
        this.pushCallback = null;
    }

    public void request(RequestInfo requestInfo) {

        try {
            Log.v(TAG, "request " + requestInfo.getPath());
            if (requestInfo.isExpectReply()) {
                replyCallbacks.put(requestInfo.getSequenceId(), requestInfo);
            }

            requestInfo.setTimestamp();

            postTimeout();

            if (socket.connected()) {
                JSONObject object = new JSONObject();
                if (requestInfo.getBody() != null) {
                    object.put("data", Base64.encodeToString(requestInfo.getBody(), Base64.NO_WRAP));
                }
                object.put("path", requestInfo.getPath());
                object.put("sequenceId", String.valueOf(requestInfo.getSequenceId()));

                socket.emit("packetProxy", object);
            }

        } catch (Exception e) {
            responseHandler.onResponse(requestInfo.getSequenceId(), RequestException.Error.CLIENT_DATA_SERIALIZE_ERROR.value, RequestException.Error.CLIENT_DATA_SERIALIZE_ERROR.name(), null);
        }
    }

    @Override
    public void subscribeBroadcast(String topic, boolean receiveTtlPackets) {
        if (!topics.containsKey(topic)) {
            topics.put(topic, receiveTtlPackets);
            if (socket.connected()) {
                JSONObject data = new JSONObject();
                try {
                    data.put("topic", topic);
                    String lastPacketId = topicToLastPacketId.get(topic);
                    if (lastPacketId != null && receiveTtlPackets) {
                        data.put("lastPacketId", lastPacketId);
                    }
                    socket.emit("subscribeTopic", data);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void setPushCallback(PushCallback pushCallback) {
        this.pushCallback = pushCallback;
    }

    public void setPushId(String pushId) {
        this.pushId = pushId;
        sendPushIdAndTopicToServer();
    }

    public void setNotificationCallback(NotificationCallback notificationCallback) {
        this.notificationCallback = notificationCallback;
    }

    public String getUid() {
        return uid;
    }

    public boolean isConnected() {
        return connected;
    }
}
