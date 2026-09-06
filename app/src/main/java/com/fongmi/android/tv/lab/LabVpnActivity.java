package com.fongmi.android.tv.lab;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;

/**
 * 系统级 VPN 授权入口。
 * 负责触发 Android 系统的 VPN 授权弹窗，授权成功后启动 SystemVpnService。
 */
public class LabVpnActivity extends Activity {

    private static final int REQ_VPN = 0x51;

    public static void start(Context context) {
        context.startActivity(new Intent(context, LabVpnActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = VpnService.prepare(this);
        if (intent != null) {
            try {
                startActivityForResult(intent, REQ_VPN);
            } catch (Exception e) {
                Toast.makeText(this, "无法弹出VPN授权", Toast.LENGTH_LONG).show();
                finish();
            }
        } else {
            // 已授权过，直接启动
            SystemVpnService.start(this);
            Toast.makeText(this, "系统级VPN已开启", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_VPN) {
            if (resultCode == RESULT_OK) {
                SystemVpnService.start(this);
                Toast.makeText(this, "系统级VPN已开启", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "已拒绝VPN授权", Toast.LENGTH_SHORT).show();
            }
            finish();
        }
    }
}
