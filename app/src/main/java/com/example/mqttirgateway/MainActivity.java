package com.example.mqttirgateway;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
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
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "IRGateway";
    private static final String MQTT_TOPIC = "home/ir/cmd";

    private EditText etBrokerIp;
    private Button btnStart, btnTestUs, btnTestCycles, btnTestFreq;
    private TextView tvStatus, tvLog;

    private ConsumerIrManager irManager;

    // 接收来自 IrMqttService 的日志广播，回显到界面日志区
    private final BroadcastReceiver logReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && IrMqttService.ACTION_LOG.equals(intent.getAction())) {
                appendLog(intent.getStringExtra(IrMqttService.EXTRA_LOG));
            }
        }
    };

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

        // Android 13+ 需要运行时申请通知权限，否则前台服务的常驻通知会被系统隐藏
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }

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
            startGateway(ip);
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

    @Override
    protected void onStart() {
        super.onStart();
        // 注册服务日志广播接收器（NOT_EXPORTED：仅本 App 内部可见）
        ContextCompat.registerReceiver(this, logReceiver,
                new IntentFilter(IrMqttService.ACTION_LOG),
                ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(logReceiver);
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

    // 启动后台前台服务，把 MQTT 监听 + 红外发射交给 IrMqttService 长期运行
    private void startGateway(String brokerIp) {
        Intent intent = new Intent(this, IrMqttService.class);
        intent.putExtra("broker_ip", brokerIp);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        tvStatus.setText("状态：✅ 网关服务已启动（后台监听 " + MQTT_TOPIC + "）");
        appendLog("🚀 已启动后台前台服务，broker=" + brokerIp);
        appendLog("ℹ️ 现在可退到后台，App 仍会持续接收 MQTT 指令并发射红外。");
    }
}
