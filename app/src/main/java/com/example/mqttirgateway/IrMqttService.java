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

    // App 内广播：把后台服务的日志实时回显到界面
    public static final String ACTION_LOG = "com.example.mqttirgateway.ACTION_LOG";
    public static final String EXTRA_LOG = "log";

    private MqttClient mqttClient;
    private ConsumerIrManager irManager;

    // 发送一条日志广播（仅限本 App 内部）
    private void sendLog(String msg) {
        Intent intent = new Intent(ACTION_LOG);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_LOG, msg);
        sendBroadcast(intent);
    }

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
                        public void connectionLost(Throwable cause) {
                            sendLog("⚠️ MQTT 连接断开" + (cause != null ? "：" + cause.getMessage() : "") + "（将自动重连）");
                        }

                        @Override
                        public void messageArrived(String topic, MqttMessage message) {
                            String payload = new String(message.getPayload());
                            sendLog("📩 收到消息: " + payload);
                            try {
                                JSONObject json = new JSONObject(payload);
                                int freq = json.optInt("freq", 38000);
                                JSONArray patternArray = json.getJSONArray("pattern");

                                int[] pattern = new int[patternArray.length()];
                                for (int i = 0; i < patternArray.length(); i++) {
                                    pattern[i] = patternArray.getInt(i);
                                }

                                // 小米/红米机型的 transmit 需要“周期数(cycles)”而非“微秒(us)”，做单位换算
                                int[] finalPattern = pattern;
                                String manufacturer = Build.MANUFACTURER.toLowerCase();
                                if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
                                    finalPattern = new int[pattern.length];
                                    for (int i = 0; i < pattern.length; i++) {
                                        finalPattern[i] = (int) ((long) pattern[i] * freq / 1000000L);
                                    }
                                    sendLog("🔄 触发小米 Cycles 模式转换");
                                }

                                if (irManager != null && irManager.hasIrEmitter()) {
                                    irManager.transmit(freq, finalPattern);
                                    sendLog("🚀 红外已发射 (freq=" + freq + ", " + finalPattern.length + " 段)");
                                } else {
                                    sendLog("❌ 此设备无红外发射头，跳过发射");
                                }
                            } catch (Exception e) {
                                sendLog("❌ 解析/发射失败: " + e.getMessage());
                            }
                        }

                        @Override
                        public void deliveryComplete(IMqttDeliveryToken token) {}
                    });

                    sendLog("⏳ 正在连接 MQTT: " + brokerUrl);
                    mqttClient.connect(options);
                    mqttClient.subscribe("home/ir/cmd", 1);
                    sendLog("✅ MQTT 连接成功，已订阅 home/ir/cmd");
                } catch (Exception e) {
                    sendLog("❌ MQTT 连接失败: " + e.getMessage());
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
