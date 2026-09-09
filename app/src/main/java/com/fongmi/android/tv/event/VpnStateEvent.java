package com.fongmi.android.tv.event;

import org.greenrobot.eventbus.EventBus;

/**
 * 系统 VPN / mihomo 代理状态变更事件（2026-09-09）。
 *
 * SystemVpnService 每个状态迁移点 post 一次，设置页 / VPN 弹窗 @Subscribe
 * 后实时刷新状态文字，修复"切了开关设置页文字不跟着变、要退出重进才刷新"的 BUG。
 *
 * Type 含义：
 *   STARTING_PROXY / STARTING_VPN — 内核/TUN 正在启动（按钮点了立刻反馈）
 *   PROXY  — mihomo 7890 运行中（VPN 未开）
 *   VPN    — 系统级 VPN 运行中（全流量）
 *   OFF    — 全停 / 启动失败
 */
public record VpnStateEvent(Type type) {

    public static void proxyStarting() {
        EventBus.getDefault().post(new VpnStateEvent(Type.STARTING_PROXY));
    }

    public static void vpnStarting() {
        EventBus.getDefault().post(new VpnStateEvent(Type.STARTING_VPN));
    }

    public static void proxy() {
        EventBus.getDefault().post(new VpnStateEvent(Type.PROXY));
    }

    public static void vpn() {
        EventBus.getDefault().post(new VpnStateEvent(Type.VPN));
    }

    public static void off() {
        EventBus.getDefault().post(new VpnStateEvent(Type.OFF));
    }

    public enum Type {
        STARTING_PROXY, STARTING_VPN, PROXY, VPN, OFF
    }
}
