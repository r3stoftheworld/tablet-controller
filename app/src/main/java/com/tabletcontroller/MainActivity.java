package com.tabletcontroller;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.*;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private TextView tvMqttStatus;
    private TextView tvMessages;
    private ScrollView scrollMessages;
    private StringBuilder messageLog = new StringBuilder();

    private BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (MqttService.ACTION_STATUS.equals(intent.getAction())) {
                boolean connected = intent.getBooleanExtra(MqttService.EXTRA_CONNECTED, false);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        tvMqttStatus.setText(connected ? "Connected" : "Disconnected");
                        tvMqttStatus.setTextColor(connected ? 0xFF4CAF50 : 0xFFF44336);
                    }
                });
            } else if (MqttService.ACTION_MESSAGE.equals(intent.getAction())) {
                String topic   = intent.getStringExtra(MqttService.EXTRA_TOPIC);
                String payload = intent.getStringExtra(MqttService.EXTRA_PAYLOAD);
                String time    = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        messageLog.append("[").append(time).append("] ")
                                  .append(topic).append(": ").append(payload).append("\n");
                        tvMessages.setText(messageLog.toString());
                        scrollMessages.post(new Runnable() {
                            @Override public void run() {
                                scrollMessages.fullScroll(ScrollView.FOCUS_DOWN);
                            }
                        });
                    }
                });
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        EditText etBroker   = (EditText) findViewById(R.id.et_broker);
        EditText etUsername = (EditText) findViewById(R.id.et_username);
        EditText etPassword = (EditText) findViewById(R.id.et_password);
        Button btnSave      = (Button) findViewById(R.id.btn_save);
        Button btnAdmin     = (Button) findViewById(R.id.btn_admin);
        Button btnNotif     = (Button) findViewById(R.id.btn_notification);
        Button btnClear     = (Button) findViewById(R.id.btn_clear);
        tvMqttStatus        = (TextView) findViewById(R.id.tv_mqtt_status);
        tvMessages          = (TextView) findViewById(R.id.tv_messages);
        scrollMessages      = (ScrollView) findViewById(R.id.scroll_messages);

        SharedPreferences prefs = getSharedPreferences("mqtt_prefs", MODE_PRIVATE);
        etBroker.setText(prefs.getString("broker", "tcp://192.168.9.x:1883"));
        etUsername.setText(prefs.getString("username", ""));
        etPassword.setText(prefs.getString("password", ""));

        // Show current status immediately if service is already running
        if (MqttService.instance != null) {
            boolean connected = MqttService.instance.isConnected();
            tvMqttStatus.setText(connected ? "Connected" : "Disconnected");
            tvMqttStatus.setTextColor(connected ? 0xFF4CAF50 : 0xFFF44336);
        }

        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String broker = etBroker.getText().toString().trim();
                String user   = etUsername.getText().toString().trim();
                String pass   = etPassword.getText().toString().trim();

                prefs.edit()
                    .putString("broker", broker)
                    .putString("username", user)
                    .putString("password", pass)
                    .apply();

                MqttService.brokerUrl = broker;
                MqttService.username  = user;
                MqttService.password  = pass;

                stopService(new Intent(MainActivity.this, MqttService.class));
                startService(new Intent(MainActivity.this, MqttService.class));
                Toast.makeText(MainActivity.this, "Saved — reconnecting", Toast.LENGTH_SHORT).show();
            }
        });

        btnAdmin.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    new ComponentName(MainActivity.this, AdminReceiver.class));
                i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Needed to lock the screen remotely via MQTT");
                startActivity(i);
            }
        });

        btnNotif.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            }
        });

        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                messageLog.setLength(0);
                tvMessages.setText("");
            }
        });

        startService(new Intent(this, MqttService.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter();
        f.addAction(MqttService.ACTION_STATUS);
        f.addAction(MqttService.ACTION_MESSAGE);
        registerReceiver(statusReceiver, f);

        // Refresh status when app comes to foreground
        if (MqttService.instance != null) {
            boolean connected = MqttService.instance.isConnected();
            tvMqttStatus.setText(connected ? "Connected" : "Disconnected");
            tvMqttStatus.setTextColor(connected ? 0xFF4CAF50 : 0xFFF44336);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(statusReceiver);
    }
}
