package com.example.bluetoothtesting;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class BloodPressureFragment extends Fragment {
    private final static String TAG = BloodPressureFragment.class.getSimpleName();

    // Pulse Pressure is the graph
    private View root;
    private TextView bleStatusTextView;
    private TextView batteryPercentageTextView;
    private TextView numPtsTextView;
    private TextView SYSTextView;
    private TextView DIATextView;
    private TextView MAPTextView;
    private TextView HRTextView;
    private TextView RESPTextView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        root = inflater.inflate(R.layout.fragment_blood_pressure, container, false);
        bleStatusTextView = root.findViewById(R.id.caretakerStatusTextView);
        batteryPercentageTextView = root.findViewById(R.id.caretakerBatteryPercentageTextView);
        numPtsTextView = root.findViewById(R.id.caretakerNumPtsTextView);
        SYSTextView = root.findViewById(R.id.SYSTextView);
        DIATextView = root.findViewById(R.id.DIATextView);
        MAPTextView = root.findViewById(R.id.MAPTextView);
        HRTextView = root.findViewById(R.id.caretakerHRTextView);
        RESPTextView = root.findViewById(R.id.RESPTextView);
        return root;
    }

    public void updateConnectionStatus(String newStatus){
        bleStatusTextView.setText(newStatus);
    }

    public void batteryLevelUpdated(float newBatteryPercent){
        batteryPercentageTextView.setText(String.format("%.2f %%", newBatteryPercent));
    }

    public void numPtsUpdated(int newNumPts){
        numPtsTextView.setText(String.format("NumPts: %d", newNumPts));
    }

    public void vitalsUpdated(int newSYS, int newDIA, int newMAP, int newHR, int newRESP){
        SYSTextView.setText(String.format("%d", newSYS));
        DIATextView.setText(String.format("%d", newDIA));
        MAPTextView.setText(String.format("%d", newMAP));
        HRTextView.setText(String.format("%d", newHR));
        RESPTextView.setText(String.format("%d", newRESP));
    }
}
