package com.tabletcontroller;

import android.app.*;
import android.app.admin.DevicePolicyManager;
import android.content.*;
import android.media.AudioManager;
import android.os.*;
import android.util.Log;
import android.view.KeyEvent;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class MqttService extends Service {
    private static final String TAG = "TabletController";

    public static final String TOPIC_COMMAND      = "tablet/command";
    public static final String TOPIC_TEST         = "tablet/test";
    public static final String TOPIC_SCREEN       = "tablet/status/screen";
    public static final String TOPIC_VOLUME       = "tablet/status/volume";
    public static final String TOPIC_MEDIA_STATE  = "tablet/status/media_state";
    public static final String TOPIC_MEDIA_TITLE  = "tablet/status/media_title";
    public static final String TOPIC_MEDIA_ARTIST = "tablet/status/media_artist";

    public static final String ACTION_STATUS   = "com.tabletcontroller.STATUS";
    public static final String ACTION_MESSAGE  = "com.tabletcontroller.MESSAGE";
    public static final String EXTRA_CONNECTED = "connected";
    public static final String EXTRA_TOPIC     = "topic";
    public static final String EXTRA_PAYLOAD   = "payload";

    public static MqttService instance;
    public static String brokerUrl = "tcp://192.168.9.x:1883";
    public static String username  = "";
    public static String password  = "";

    private MqttClient mqttClient;
    private PowerManager powerManager;
    private AudioManager audioManager;
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private final Handler handler = new Handler();
    private BroadcastReceiver screenReceiver;
    private PowerManager.WakeLock wakeLock;
    private boolean screenShouldBeOn = false;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;

        SharedPreferences prefs = getSharedPreferences("mqtt_prefs", MODE_PRIVATE);
        brokerUrl = prefs.getString("broker", brokerUrl);
        username  = prefs.getString("username", "");
        password  = prefs.getString("password", "");

        powerManager  = (PowerManager) getSystemService(POWER_SERVICE);
        audioManager  = (AudioManager) getSystemService(AUDIO_SERVICE);
        dpm           = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, AdminReceiver.class);

        registerScreenReceiver();
        startForegroundService();
        connectMqtt();
        startPeriodicStatus();
    }

    private void registerScreenReceiver() {
        screenReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_SCREEN_ON.equals(intent.getAction()))
                    publish(TOPIC_SCREEN, "ON");
                else if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction()))
                    publish(TOPIC_SCREEN, "OFF");
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenReceiver, f);
    }

    private void startForegroundService() {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
            new Intent(this, MainActivity.class), 0);
        Notification n = new Notification.Builder(this)
            .setContentTitle("Tablet Controller")
            .setContentText("Running")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .build();
        startForeground(1, n);
    }

    public void broadcastStatus(boolean connected) {
        Intent i = new Intent(ACTION_STATUS);
        i.putExtra(EXTRA_CONNECTED, connected);
        sendBroadcast(i);
    }

    public void broadcastMessage(String topic, String payload) {
        Intent i = new Intent(ACTION_MESSAGE);
        i.putExtra(EXTRA_TOPIC, topic);
        i.putExtra(EXTRA_PAYLOAD, payload);
        sendBroadcast(i);
    }

    private void applyWakeLock() {
        if (wakeLock == null) {
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "TabletController:wake");
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }
    }

    private void connectMqtt() {
        try {
            SharedPreferences prefs = getSharedPreferences("mqtt_prefs", MODE_PRIVATE);
            String clientId = prefs.getString("client_id", null);
            if (clientId == null) {
                clientId = "TabletController_" + java.util.UUID.randomUUID().toString();
                prefs.edit().putString("client_id", clientId).apply();
            }

            mqttClient = new MqttClient(brokerUrl, clientId, new MemoryPersistence());

            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setCleanSession(true);
            opts.setAutomaticReconnect(true);
            opts.setKeepAliveInterval(60);
            if (!username.isEmpty()) {
                opts.setUserName(username);
                opts.setPassword(password.toCharArray());
            }

            mqttClient.setCallback(new MqttCallbackExtended() {
                @Override public void connectComplete(boolean reconnect, String serverURI) {
                    Log.d(TAG, "Connected (reconnect=" + reconnect + ")");
                    broadcastStatus(true);
                    try {
                        mqttClient.subscribe(TOPIC_COMMAND, 0);
                        mqttClient.subscribe(TOPIC_TEST, 0);
                        Log.d(TAG, "Subscribed to topics");
                    } catch (MqttException e) {
                        Log.e(TAG, "Subscribe failed: " + e.getMessage());
                    }
                    if (screenShouldBeOn) {
                        applyWakeLock();
                    }
                }
                @Override public void connectionLost(Throwable cause) {
                    Log.w(TAG, "Connection lost, will auto-reconnect");
                    broadcastStatus(false);
                }
                @Override public void messageArrived(String topic, MqttMessage msg) {
                    String payload = new String(msg.getPayload()).trim();
                    Log.d(TAG, "Message on " + topic + ": " + payload);
                    broadcastMessage(topic, payload);
                    if (topic.equals(TOPIC_COMMAND))
                        handleCommand(payload.toLowerCase());
                }
                @Override public void deliveryComplete(IMqttDeliveryToken t) {}
            });

            Log.d(TAG, "Connecting to: " + brokerUrl);
            mqttClient.connect(opts);

        } catch (MqttException e) {
            Log.e(TAG, "MQTT connect failed: " + e.getMessage() + " reason: " + e.getReasonCode() + " cause: " + e.getCause());
            broadcastStatus(false);
            handler.postDelayed(new Runnable() {
                @Override public void run() { connectMqtt(); }
            }, 15000);
        }
    }

    private void handleCommand(String cmd) {
        switch (cmd) {
            case "wake":
                screenShouldBeOn = true;
                applyWakeLock();
                break;
            case "sleep":
                screenShouldBeOn = false;
                if (wakeLock != null && wakeLock.isHeld()) {
                    wakeLock.release();
                }
                if (dpm.isAdminActive(adminComponent))
                    dpm.lockNow();
                else
                    Log.e(TAG, "Device admin not granted");
                break;
            case "play_pause":
                dispatchMedia(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                break;
            case "next":
                dispatchMedia(KeyEvent.KEYCODE_MEDIA_NEXT);
                break;
            case "prev":
                dispatchMedia(KeyEvent.KEYCODE_MEDIA_PREVIOUS);
                break;
            case "vol_up":
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                publishVolume();
                break;
            case "vol_down":
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                publishVolume();
                break;
        }
    }

    private void dispatchMedia(int keyCode) {
        audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    private void publishVolume() {
        int cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        publish(TOPIC_VOLUME, cur + "/" + max);
    }

    private void startPeriodicStatus() {
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                publishVolume();
                publish(TOPIC_SCREEN, powerManager.isInteractive() ? "ON" : "OFF");
                handler.postDelayed(this, 30000);
            }
        }, 5000);
    }

    public void publish(String topic, String payload) {
        try {
            if (mqttClient != null && mqttClient.isConnected())
                mqttClient.publish(topic, payload.getBytes(), 0, true);
        } catch (MqttException e) {
            Log.e(TAG, "Publish failed: " + e.getMessage());
        }
    }

    public boolean isConnected() {
        return mqttClient != null && mqttClient.isConnected();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (screenReceiver != null) unregisterReceiver(screenReceiver);
        try { if (mqttClient != null) mqttClient.disconnect(); }
        catch (MqttException ignored) {}
    }
}
