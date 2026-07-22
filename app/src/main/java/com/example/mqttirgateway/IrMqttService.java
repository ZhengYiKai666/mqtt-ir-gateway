package com.example.mqttirgateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.ConsumerIrManager;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.json.JSONArray;
import org.json.JSONObject;

public class IrMqttService extends Service {
    private static final String CHANNEL_ID = "IrMqttChannel";
    private MqttClient mqttClient;
    private ConsumerIrManager irManager;

    @Override
    public void onCreate() {
        super.onCreate();
        irManager = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);
        startForegroundService();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra("broker_ip")) {
            String brokerIp = intent.getStringExtra("broker_ip");
            connectMqtt("tcp://" + brokerIp + ":1883");
        }
        return START_STICKY;
    }

    private void connectMqtt(String brokerUrl) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    mqttClient = new MqttClient(brokerUrl, "AndroidIRGateway", new MemoryPersistence());
                    MqttConnectOptions options = new MqttConnectOptions();
                    options.setAutomaticReconnect(true);
                    options.setCleanSession(false);

                    mqttClient.setCallback(new MqttCallback() {
                        @Override
                        public void connectionLost(Throwable cause) {}

                        @Override
                        public void messageArrived(String topic, MqttMessage message) throws Exception {
                            String payload = new String(message.getPayload());
                            JSONObject json = new JSONObject(payload);
                            int freq = json.getInt("freq");
                            JSONArray patternArray = json.getJSONArray("pattern");
                            
                            int[] pattern = new int[patternArray.length()];
                            for (int i = 0; i < patternArray.length(); i++) {
                                pattern[i] = patternArray.getInt(i);
                            }

                            if (irManager != null && irManager.hasIrEmitter()) {
                                irManager.transmit(freq, pattern);
                            }
                        }

                        @Override
                        public void deliveryComplete(IMqttDeliveryToken token) {}
                    });

                    mqttClient.connect(options);
                    mqttClient.subscribe("home/ir/cmd", 1);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    private void startForegroundService() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "红外网关后台服务", NotificationManager.IMPORTANCE_LOW);
            if (manager != null) manager.createNotificationChannel(channel);
        }

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("红外 MQTT 网关运行中")
                .setContentText("正在监听 home/ir/cmd ...")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .build();

        startForeground(1, notification);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
