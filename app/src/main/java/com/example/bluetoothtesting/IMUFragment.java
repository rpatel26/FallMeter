package com.example.bluetoothtesting;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class IMUFragment extends Fragment {
    private final static String TAG = IMUFragment.class.getSimpleName();

    private  View root;
    private TextView deviceListTextView;
    private TextView currentNetworkNextView;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        System.out.println("IMUFragment.....");
        root = inflater.inflate(R.layout.fragment_imu, container, false);
        deviceListTextView = root.findViewById(R.id.deviceListTextView);
        currentNetworkNextView = root.findViewById(R.id.currentNetworkTextView);

        if(savedInstanceState != null){
            deviceListTextView.setText(savedInstanceState.getString("DeviceListText"));
        }
        return root;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if(savedInstanceState != null){
            deviceListTextView.setText(savedInstanceState.getString("DeviceListText"));
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        Log.d(TAG, "IMU Fragment Saving State....");
        outState.putString("DeviceListText", deviceListTextView.getText().toString());
    }

    public void updateDeviceListTextView(String newDevices){
        deviceListTextView.setText(newDevices);
    }

    public void updateCurrentNetworkTextView(String newNetwork){
        currentNetworkNextView.setText(newNetwork);
    }
}
