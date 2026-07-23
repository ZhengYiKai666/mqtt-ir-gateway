package com.example.irgateway; // ⚠️ 修改为你的实际包名

import android.content.Context;
import android.hardware.ConsumerIrManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "IRGateway";
    private static final String MQTT_TOPIC = "home/ir/cmd";

    private EditText etBrokerIp;
    private Button btnStart, btnTestUs, btnTestCycles, btnTestFreq;
    private TextView tvStatus, tvLog;

    private ConsumerIrManager irManager;
    private MqttClient mqttClient;

    // 美的空调测试脉冲基准 (微秒 us)
    private final int[] mideaTestPatternUs = new int[]{
        4400, 4400, 540, 1620, 540, 540, 540, 1620, 540, 540, 540, 540, 540, 1620,
        540, 540, 540, 1620, 540, 1620, 540, 540, 540, 1620, 540, 1620, 540, 540,
        540, 1620, 540, 540, 540, 1620, 540, 540, 540, 540, 540, 1620, 540, 1620,
        540, 540, 540, 1620, 540, 1620, 540, 540, 540, 1620, 540, 1620, 540, 540,
        540, 1620, 540, 540, 540, 1620, 540, 540, 540, 540, 540, 1620, 540, 540,
        540, 1620, 540, 540, 540, 540, 540, 1620, 540, 540, 540, 1620, 540, 1620,
        540, 540, 540, 1620, 540, 1620, 540, 540, 540, 5220
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etBrokerIp = findViewById(R.id.etBrokerIp);
        btnStart = findViewById(R.id.btnStart);
        btnTestUs = findViewById(R.id.btnTestUs);
        btnTestCycles = findViewById(R.id.btnTestCycles);
        btnTestFreq = findViewById(R.id.btnTestFreq);
        tvStatus = findViewById(R.id.tvStatus);
        tvLog = findViewById(R.id.tvLog);

        irManager = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);

        if (irManager == null || !irManager.hasIrEmitter()) {
            appendLog("❌ 警告：此手机不支持红外功能或无红外发射器！");
            btnStart.setEnabled(false);
            return;
        } else {
            appendLog("✅ 检测到红外硬件，手机品牌: " + Build.MANUFACTURER + " " + Build.MODEL);
        }

        // MQTT 连接按钮
        btnStart.setOnClickListener(v -> {
            String ip = etBrokerIp.getText().toString().trim();
            if (ip.isEmpty()) {
                Toast.makeText(this, "请输入 MQTT 服务器 IP", Toast.LENGTH_SHORT).show();
                return;
            }
            connectMqtt(ip);
        });

        // 🧪 测试功能 1：原生微秒模式发射
        btnTestUs.setOnClickListener(v -> {
            appendLog("🧪 手动测试 [微秒模式] 发射中...");
            transmitDirect(38000, mideaTestPatternUs);
        });

        // 🧪 测试功能 2：小米周期数 (Cycles) 模式发射
        btnTestCycles.setOnClickListener(v -> {
            appendLog("🧪 手动测试 [小米 Cycles 模式] 发射中...");
            int[] cyclesPattern = new int[mideaTestPatternUs.length];
            for (int i = 0; i < mideaTestPatternUs.length; i++) {
                cyclesPattern[i] = (int) ((long) mideaTestPatternUs[i] * 38000 / 1000000L);
            }
            transmitDirect(38000, cyclesPattern);
        });

        // 🧪 测试功能 3：查询手机支持的红外频率范围
        btnTestFreq.setOnClickListener(v -> {
            ConsumerIrManager.CarrierFrequencyRange[] ranges = irManager.getCarrierFrequencies();
            if (ranges != null && ranges.length > 0) {
                StringBuilder sb = new StringBuilder("📡 硬件支持频率范围:\n");
                for (ConsumerIrManager.CarrierFrequencyRange r : ranges) {
                    sb.append("  - ").append(r.getMinFrequency()).append(" Hz ~ ").append(r.getMaxFrequency()).append(" Hz\n");
                }
                appendLog(sb.toString());
            } else {
                appendLog("⚠️ 无法获取硬件频率列表（可能驱动受限）");
            }
        });
    }

    private void transmitDirect(int freq, int[] pattern) {
        try {
            irManager.transmit(freq, pattern);
            appendLog("⚡ 发射函数 transmit() 调用成功！");
        } catch (Exception e) {
            appendLog("❌ 发射失败 (异常): " + e.getMessage());
        }
    }

    private void appendLog(String log) {
        runOnUiThread(() -> {
            Log.d(TAG, log);
            tvLog.append("\n" + log);
        });
    }

    private void connectMqtt(String brokerIp) {
        String serverUri = "tcp://" + brokerIp + ":1883";
        String clientId = "Android_IR_Gateway_" + System.currentTimeMillis();

        try {
            if (mqttClient != null && mqttClient.isConnected()) {
                mqttClient.disconnect();
            }

            mqttClient = new MqttClient(serverUri, clientId, new MemoryPersistence());
            MqttConnectOptions options = new MqttConnectOptions();
            options.setCleanSession(true);
            options.setAutomaticReconnect(true);

            mqttClient.setCallback(new MqttCallback() {
                @Override
                public void connectionLost(Throwable cause) {
                    appendLog("⚠️ MQTT 连接已断开！");
                }

                @Override
                public void messageArrived(String topic, MqttMessage message) {
                    String payload = new String(message.getPayload());
                    appendLog("📩 收到 MQTT 消息: " + payload);
                    handleIrCommand(payload);
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {}
            });

            appendLog("⏳ 正在连接 MQTT: " + serverUri);
            new Thread(() -> {
                try {
                    mqttClient.connect(options);
                    mqttClient.subscribe(MQTT_TOPIC, 0);
                    runOnUiThread(() -> tvStatus.setText("状态：✅ 已连接 " + MQTT_TOPIC));
                    appendLog("✅ MQTT 连接成功！");
                } catch (MqttException e) {
                    appendLog("❌ MQTT 连接失败: " + e.getMessage());
                }
            }).start();

        } catch (Exception e) {
            appendLog("❌ 初始化 MQTT 错误: " + e.getMessage());
        }
    }

    private void handleIrCommand(String jsonStr) {
        try {
            JSONObject json = new JSONObject(jsonStr);
            int frequency = json.optInt("freq", 38000);
            JSONArray patternArray = json.getJSONArray("pattern");

            int[] pattern = new int[patternArray.length()];
            for (int i = 0; i < patternArray.length(); i++) {
                pattern[i] = patternArray.getInt(i);
            }

            // 自动判断小米/华为品牌转换
            int[] finalPattern;
            String manufacturer = Build.MANUFACTURER.toLowerCase();
            if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
                finalPattern = new int[pattern.length];
                for (int i = 0; i < pattern.length; i++) {
                    finalPattern[i] = (int) ((long) pattern[i] * frequency / 1000000L);
                }
                appendLog("🔄 已触发小米 Cycles 驱动修正");
            } else {
                finalPattern = pattern;
            }

            irManager.transmit(frequency, finalPattern);
            appendLog("🚀 红外命令发射成功！");

        } catch (Exception e) {
            appendLog("❌ 解析红外 JSON 异常: " + e.getMessage());
        }
    }
}
