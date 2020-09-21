package com.example.bluetoothtesting;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class HeartRateFragment extends Fragment {
    private View root;
    private TextView bleStatusTextView;
    private TextView batteryPercentageTextView;
    private TextView numPtsTextView;
    private TextView HRTextView;
    private TextView RRITextView;
    private TextView meanRRTextView;
    private TextView PNN50TextView;
    private TextView SDNNTextView;
    private TextView RMSSDTextView;
    private TextView fileNameTextView;
    private Switch recordDataSwitch;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        root = inflater.inflate(R.layout.fragment_heart_rate, container, false);
        bleStatusTextView = root.findViewById(R.id.bleStatusTextView);
        batteryPercentageTextView = root.findViewById(R.id.batteryPercentageTextView);
        numPtsTextView = root.findViewById(R.id.numPtsTextView);
        HRTextView = root.findViewById(R.id.HRTextView);
        RRITextView = root.findViewById(R.id.RRITextView);
        meanRRTextView = root.findViewById(R.id.meanRRTextView);
        PNN50TextView = root.findViewById(R.id.PNN50TextView);
        SDNNTextView = root.findViewById(R.id.SDNNTextView);
        RMSSDTextView = root.findViewById(R.id.RMSSDTextView);
        fileNameTextView = root.findViewById(R.id.fileNameTextView);
        recordDataSwitch = root.findViewById(R.id.recordDataSwitch);
        recordDataSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                Intent checkedChangedIntent = new Intent("com.example.bluetoothtesting.RecordData");
                checkedChangedIntent.putExtra("IsChecked", isChecked);
                getActivity().sendBroadcast(checkedChangedIntent);
            }
        });
        return root;
    }

    public void statusConnected(){
        bleStatusTextView.setText("Connected");
    }

    public void statusDisconnected(){ bleStatusTextView.setText("Disconnected"); }

    public void numPtsUpdated(int newNumPts){
        numPtsTextView.setText(String.format("NumPts: %3d", newNumPts));
    }

    public void batteryLevelUpdated(int newBatteryLevel){
        batteryPercentageTextView.setText(String.format("%3d %%", newBatteryLevel));
    }

    public void HRUpdated(int newHR){
        HRTextView.setText(String.format("%3d", newHR));
    }

    public void RRIUpdated(int newRRI){
        RRITextView.setText(String.format("%3d", newRRI));
    }

    public void meanRRUpdated(float newMeanRRI){
        meanRRTextView.setText(String.format("%3.2f", newMeanRRI));
    }

    public void PNN50Updated(float newPNN50){
        PNN50TextView.setText(String.format("%3.2f", newPNN50));
    }

    public void SDNNUpdated(float newSDNN){
        SDNNTextView.setText(String.format("%3.2f", newSDNN));
    }

    public void RMSSDUpdated(float newRMSSD){
        RMSSDTextView.setText(String.format("%3.2f", newRMSSD));
    }

    public void fileNameTextViewUpdated(String newFileName){
        fileNameTextView.setText(newFileName);
    }

}
