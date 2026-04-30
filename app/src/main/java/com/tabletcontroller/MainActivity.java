package com.tabletcontroller;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.*;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.*;

public class MainActivity extends Activity {
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

        SharedPreferences prefs = getSharedPreferences("mqtt_prefs", MODE_PRIVATE);
        etBroker.setText(prefs.getString("broker", "tcp://192.168.9.x:1883"));
        etUsername.setText(prefs.getString("username", ""));
        etPassword.setText(prefs.getString("password", ""));

        btnSave.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
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

        btnAdmin.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
                i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    new ComponentName(MainActivity.this, AdminReceiver.class));
                i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Needed to lock the screen remotely via MQTT");
                startActivity(i);
            }
        });

        btnNotif.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            }
        });

        startService(new Intent(this, MqttService.class));
    }
}
