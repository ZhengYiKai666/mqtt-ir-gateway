package com.example.mqttirgateway;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate();
        
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(50, 50, 50, 50);

        final EditText etIp = new EditText(this);
        etIp.setHint("输入家里电脑 IP (如 192.168.0.100)");
        layout.addView(etIp);

        Button btnStart = new Button(this);
        btnStart.setText("启动红外网关后台服务");
        layout.addView(btnStart);

        setContentView(layout);

        btnStart.setOnClickListener(v -> {
            String ip = etIp.getText().toString().trim();
            if (ip.isEmpty()) {
                Toast.makeText(this, "请填写 IP 地址！", Toast.LENGTH_SHORT).show();
                return;
            }
            Intent intent = new Intent(this, IrMqttService.class);
            intent.putExtra("broker_ip", ip);
            startService(intent);
            Toast.makeText(this, "服务已启动！后台常驻中...", Toast.LENGTH_SHORT).show();
        });
    }
}
