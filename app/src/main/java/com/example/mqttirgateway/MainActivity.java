package com.example.irgateway; // ⚠️ 修改为你的实际包名

import android.content.Context;
import android.graphics.Color;
import android.hardware.ConsumerIrManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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

        // 1. 动态用代码构建界面（无需任何 xml）
        initDynamicUI();

        // 2. 初始化系统红外服务
        irManager = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);

        if (irManager == null || !irManager.hasIrEmitter()) {
            appendLog("❌ 警告：此手机不支持红外功能或未找到红外发射头！");
            btnStart.setEnabled(false);
            return;
        } else {
            appendLog("✅ 硬件正常，识别手机品牌: " + Build.MANUFACTURER + " " + Build.MODEL);
        }

        // 3. 绑定按键逻辑
        btnStart.setOnClickListener(v -> {
            String ip = etBrokerIp.getText().toString().trim();
            if (ip.isEmpty()) {
                Toast.makeText(this, "请输入 MQTT 服务器 IP", Toast.LENGTH_SHORT).show();
                return;
            }
            connectMqtt(ip);
        });

        // 🧪 测试模式 1：原生微秒模式
        btnTestUs.setOnClickListener(v -> {
            appendLog("🧪 手动测试 [1.微秒模式] 发射中...");
            transmitDirect(38000, mideaTestPatternUs);
        });

        // 🧪 测试模式 2：小米 Cycles 周期数模式
        btnTestCycles.setOnClickListener(v -> {
            appendLog("🧪 手动测试 [2.Cycles 周期数模式] 发射中...");
            int[] cyclesPattern = new int[mideaTestPatternUs.length];
            for (int i = 0; i < mideaTestPatternUs.length; i++) {
                cyclesPattern[i] = (int) ((long) mideaTestPatternUs[i] * 38000 / 1000000L);
            }
            transmitDirect(38000, cyclesPattern);
        });

        // 🧪 测试模式 3：检测支持频率
        btnTestFreq.setOnClickListener(v -> {
            ConsumerIrManager.CarrierFrequencyRange[] ranges = irManager.getCarrierFrequencies();
            if (ranges != null && ranges.length > 0) {
                StringBuilder sb = new StringBuilder("📡 硬件支持的频率区间:\n");
                for (ConsumerIrManager.CarrierFrequencyRange r : ranges) {
                    sb.append("  - ").append(r.getMinFrequency()).append(" Hz ~ ").append(r.getMaxFrequency()).append(" Hz\n");
                }
                appendLog(sb.toString());
            } else {
                appendLog("⚠️ 无法获取硬件频率列表");
            }
        });
    }

    // 用 Java 代码画出 UI 布局
    private void initDynamicUI() {
        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(40, 40, 40, 40);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("MQTT 红外网关（现场测试诊断版）");
        tvTitle.setTextSize(18);
        tvTitle.setTextColor(Color.BLACK);
        mainLayout.addView(tvTitle);

        etBrokerIp = new EditText(this);
        etBrokerIp.setHint("MQTT IP (例 192.168.0.100)");
        mainLayout.addView(etBrokerIp);

        btnStart = new Button(this);
        btnStart.setText("启动红外监听网关");
        mainLayout.addView(btnStart);

        tvStatus = new TextView(this);
        tvStatus.setText("状态：未连接");
        tvStatus.setTextColor(Color.DKGRAY);
        tvStatus.setPadding(0, 10, 0, 20);
        mainLayout.addView(tvStatus);

        TextView tvToolTitle = new TextView(this);
        tvToolTitle.setText("🛠️ 红外现场诊断测试（对准空调直接按）：");
        tvToolTitle.setTextColor(Color.BLACK);
        mainLayout.addView(tvToolTitle);

        LinearLayout btnGroup = new LinearLayout(this);
        btnGroup.setOrientation(LinearLayout.HORIZONTAL);

        btnTestUs = new Button(this);
        btnTestUs.setText("1.微秒");
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        btnGroup.addView(btnTestUs, p1);

        btnTestCycles = new Button(this);
        btnTestCycles.setText("2.Cycles");
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        btnGroup.addView(btnTestCycles, p2);

        btnTestFreq = new Button(this);
        btnTestFreq.setText("检查频率");
        LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        btnGroup.addView(btnTestFreq, p3);

        mainLayout.addView(btnGroup);

        TextView tvLogLabel = new TextView(this);
        tvLogLabel.setText("📋 运行日志：");
        tvLogLabel.setPadding(0, 20, 0, 10);
        mainLayout.addView(tvLogLabel);

        ScrollView scrollView = new ScrollView(this);
        scrollView.setBackgroundColor(Color.parseColor("#1E1E1E"));
        scrollView.setPadding(20, 20, 20, 20);

        tvLog = new TextView(this);
        tvLog.setText("等待操作...");
        tvLog.setTextColor(Color.GREEN);
        tvLog.setTextSize(12);

        scrollView.addView(tvLog);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        mainLayout.addView(scrollView, scrollParams);

        setContentView(mainLayout);
    }

    private void transmitDirect(int freq, int[] pattern) {
        try {
            irManager.transmit(freq, pattern);
            appendLog("⚡ transmit() 发射指令已下发！");
        } catch (Exception e) {
            appendLog("❌ 发射失败: " + e.getMessage());
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
                    appendLog("⚠️ MQTT 连接断开！");
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

            int[] finalPattern;
            String manufacturer = Build.MANUFACTURER.toLowerCase();
            if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi")) {
                finalPattern = new int[pattern.length];
                for (int i = 0; i < pattern.length; i++) {
                    finalPattern[i] = (int) ((long) pattern[i] * frequency / 1000000L);
                }
                appendLog("🔄 触发小米 Cycles 模式转换");
            } else {
                finalPattern = pattern;
            }

            irManager.transmit(frequency, finalPattern);
            appendLog("🚀 红外命令发射成功！");

        } catch (Exception e) {
            appendLog("❌ 解析 JSON 错误: " + e.getMessage());
        }
    }
}
