package com.example.bluetoothtesting;

import com.wearnotch.service.network.NotchService;

public interface NotchServiceConnection {
    void onServiceConnected(NotchService notchService);
    void onServiceDisconnected();
}
