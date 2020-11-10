package com.example.bluetoothtesting;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.drawable.AnimationDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.wearnotch.db.NotchDataBase;
import com.wearnotch.db.model.Device;
import com.wearnotch.framework.ActionDevice;
import com.wearnotch.framework.Bone;
import com.wearnotch.framework.ColorPair;
import com.wearnotch.framework.Measurement;
import com.wearnotch.framework.MeasurementType;
import com.wearnotch.framework.NotchChannel;
import com.wearnotch.framework.NotchNetwork;
import com.wearnotch.framework.Skeleton;
import com.wearnotch.framework.Workout;
import com.wearnotch.framework.visualiser.VisualiserData;
import com.wearnotch.internal.util.IOUtil;
import com.wearnotch.notchmaths.fvec3;
import com.wearnotch.service.NotchAndroidService;
import com.wearnotch.service.common.Cancellable;
import com.wearnotch.service.common.NotchCallback;
import com.wearnotch.service.common.NotchError;
import com.wearnotch.service.common.NotchProgress;
import com.wearnotch.service.network.NotchService;

//import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.simple.JSONObject;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

//        {
//                "name": "LeftForeArm",
//                "color1": "Red",
//                "color2": "Red",
//                "frequency": 40
//                },
public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();
    private Activity mActivity;
    private File mOutputDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);

    // UUID for HeartyPatch
    private BufferedWriter logFile;
    public final static UUID UUID_HEART_RATE_MEASUREMENT =
            UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb");

    public final static UUID UUID_BATTERY_LEVEL =
            UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb");

    public final static UUID UUID_CUSTOM_HRV =
            UUID.fromString("01bfa86f-970f-8d96-d44d-9023c47faddc");

    // BLE Elements
    BluetoothAdapter mBluetoothAdapter;
    private BluetoothLeScanner mBluetoothLEScanner;
    private boolean bleIsScanning;

    // UI Elements
    private FragmentManager fragmentManager;
    private HeartRateFragment heartRateFragment;
    private IMUFragment imuFragment;
    private BloodPressureFragment bloodPressureFragment;
    private Fragment activeFragment;
    private BottomNavigationView bottomNavigationView;
    private AlertDialog.Builder mAlertDialogBuilder;
    private AlertDialog mAlertDialog;

    // Data
    private boolean isHeartyPatchConnected;
    private int numHeartyPatchPts;
    private boolean canRecordData;
    private int numBloodPressurePts;

    // Variables for Notch
    private static final int REQUEST_ALL_PERMISSION = 1;
    private static String[] PERMISSIONS = {Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.ACCESS_FINE_LOCATION};
    private ImageView dockImage;
    private AnimationDrawable mDockAnimation;

    @Override
    protected void onDestroy() {
        // stop background service
        Intent intent = new Intent(this, BloodPressureData.class);
        stopService(intent);

        Intent notchIntent = new Intent(this, NotchBackgroundService.class);
        stopService(notchIntent);

        unregisterReceiver(bloodPressureDataBroadcastReceiver);
        unregisterReceiver(recordDataChanges);

        super.onDestroy();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mActivity = this;
        isHeartyPatchConnected = false;
        numHeartyPatchPts = 0;
        canRecordData = false;
        numBloodPressurePts = 0;

        dockImage = findViewById(R.id.dock_image);
        dockImage.setBackgroundResource(R.drawable.sensor_anim);
        mDockAnimation = (AnimationDrawable) dockImage.getBackground();
        dockImage.setVisibility(View.GONE);

        mAlertDialogBuilder = new AlertDialog.Builder(this);
        fragmentManager = getSupportFragmentManager();
        heartRateFragment = new HeartRateFragment();
        imuFragment = new IMUFragment();
        bloodPressureFragment = new BloodPressureFragment();
        activeFragment = heartRateFragment;

        bottomNavigationView = findViewById(R.id.bottom_navigation);
        bottomNavigationView.setOnNavigationItemSelectedListener(navListener);
        getSupportFragmentManager().beginTransaction().add(R.id.fragment_container,
                activeFragment).commit();

        mBluetoothAdapter = ((BluetoothManager) getSystemService(BLUETOOTH_SERVICE)).getAdapter();
        mBluetoothLEScanner = BluetoothAdapter.getDefaultAdapter().getBluetoothLeScanner();

        // Start background service to monitor Caretaker
        Intent intent = new Intent(this, BloodPressureData.class);
        startService(intent);
        bindService(intent, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                // Do nothing.....
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // Do nothing......
            }
        }, Context.BIND_AUTO_CREATE);


        // Register receiver to capture any data transferred from Caretaker
        IntentFilter filter = new IntentFilter(getApplicationContext().getPackageName() + "_BloodPressure");
        registerReceiver(bloodPressureDataBroadcastReceiver, filter);

        // Start background service to listen to record date changes
        IntentFilter recordDataFilter = new IntentFilter("com.example.bluetoothtesting.RecordData");
        registerReceiver(recordDataChanges, recordDataFilter);

        // Check if proper permission is set for the app
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED){
            Log.d(TAG, "WRITE_EXTERNAL_STORAGE Permission Denied....");
            Toast.makeText(this, "Write Storage Permission Denied", Toast.LENGTH_SHORT).show();
        }

        // Init Vars for Notch Devices
        if (!hasPermissions(mActivity, PERMISSIONS)) {
            requestPermissions(PERMISSIONS, REQUEST_ALL_PERMISSION);
        }

        // Start background service to monitor Notch
        Intent notchIntent = new Intent(this, NotchBackgroundService.class);
        startService(notchIntent);
        bindService(notchIntent, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                // Do nothing.....
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // Do nothing......
            }
        }, Context.BIND_AUTO_CREATE);

        // Listening to updates from NotchBackgroundService
        IntentFilter notchBackgroundServiceIntentFilter = new IntentFilter(getApplicationContext().getPackageName() + "_NotchService");
        registerReceiver(notchServiceBroadcastReceiver, notchBackgroundServiceIntentFilter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        /*
         * Gets Executed when app enters foreground....
         */
        if(mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()){
            enableBLE();
            finish();
        }

        if(!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)){
            Toast.makeText(this, "Bluetooth LE is NOT supported!", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Begin scanning for LE devices (HeartyPatch)
        startScan();
    }

    private BottomNavigationView.OnNavigationItemSelectedListener navListener =
            new BottomNavigationView.OnNavigationItemSelectedListener() {
                @Override
                public boolean onNavigationItemSelected(@NonNull MenuItem menuItem) {
                    switch (menuItem.getItemId()){
                        case R.id.nav_heart_rate:
                            loadFragment(heartRateFragment);
                            break;
                        case R.id.nav_imu:
                            loadFragment(imuFragment);
                            break;
                        case R.id.nav_blood_pressure:
                            loadFragment(bloodPressureFragment);
                            break;
                    }
                    return true;
                }
            };

    private void loadFragment(Fragment selectedFragment){
        fragmentManager.beginTransaction()
                .replace(R.id.fragment_container, selectedFragment)
                .commit();

        activeFragment = selectedFragment;
    }

    private void showAlertDialog(String title, String message){
        mAlertDialogBuilder.setMessage(message);
        mAlertDialogBuilder.setTitle(title);
        mAlertDialog = mAlertDialogBuilder.create();
        mAlertDialog.show();
    }
    private void dismissAlertDialog(){
        mAlertDialog.dismiss();
    }
    private void enableBLE(){
        Intent btEnableIntent = new Intent(mBluetoothAdapter.ACTION_REQUEST_ENABLE);
        startActivity(btEnableIntent);

        IntentFilter BTIntent = new IntentFilter(mBluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(mBroadcastReceiver_STATE_CHANGE, BTIntent);
    }
    public void startScan(){
        if(bleIsScanning){
            bleIsScanning = false;
            mBluetoothLEScanner.stopScan(bleScanCallback);
        }
        else{
            mBluetoothLEScanner.startScan(bleScanCallback);
        }
    }
    public void stopScan(){
        mBluetoothLEScanner.stopScan(bleScanCallback);
    }
    private void connect(BluetoothDevice device){
//        stopScan();
        device.connectGatt(this, true, bleGattCallback);
    }

    // For Catching when BLE changes state
    private final BroadcastReceiver mBroadcastReceiver_STATE_CHANGE = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if(action.equals(mBluetoothAdapter.ACTION_STATE_CHANGED)){
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, mBluetoothAdapter.ERROR);

                switch (state){
                    case BluetoothAdapter.STATE_OFF:
                        Log.d(TAG, "mBroadcastReceiver_STATE_CHANGE - onReceive: STATE OFF");
                        BluetoothAdapter.getDefaultAdapter().enable();
                        break;
                    case BluetoothAdapter.STATE_TURNING_OFF:
                        Log.d(TAG, "mBroadcastReceiver_STATE_CHANGE - onReceive: STATE TURNING OFF");
                        break;
                    case BluetoothAdapter.STATE_ON:
                        Log.d(TAG, "mBroadcastReceiver_STATE_CHANGE - onReceive: STATE ON");
                        startScan();
                        break;
                    case BluetoothAdapter.STATE_TURNING_ON:
                        Log.d(TAG, "mBroadcastReceiver_STATE_CHANGE - onReceive: STATE TURNING ON");
                        break;
                }
            }
        }
    };

    private ScanCallback bleScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            if(result.getDevice().getName() != null ){
                Log.d(TAG, "bleOnScanResult: " + result.getDevice().getName());
                if(result.getDevice().getName().contains("HeartyPatch") && isHeartyPatchConnected == false){
                    connect(result.getDevice());
                }
                else if(result.getDevice().getName().contains("Caretaker")){
                    connect(result.getDevice());
                }
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results) {
            super.onBatchScanResults(results);
            Log.d(TAG, "bleOnBatchScanResult: Num of results = " + results.size());
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            Log.d(TAG, "bleOnScanFailed: ERROR....BLE Scan Failed! ErrorCode: " + errorCode);
            Toast.makeText(mActivity, "BLEScanFailed: " + errorCode, Toast.LENGTH_SHORT);
            if(errorCode == ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED){
//                BluetoothAdapter.getDefaultAdapter().disable();
                Toast.makeText(mActivity, "BLEScanFailed: SCAN_FAILED_APPLICATION_REGISTRATION_FAILED", Toast.LENGTH_SHORT);
                // TODO: BluetoothAdapter.STATE_OFF gets triggered....enable BluetoothAdapter then
            }
            else{
                Toast.makeText(mActivity, "BLEScanFailed...ErrorCode: " + errorCode, Toast.LENGTH_SHORT);
            }
        }
    };
    private BluetoothGattCallback bleGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if(gatt.getDevice().getName().contains("HeartyPatch")){
                    isHeartyPatchConnected = true;
                }
                Log.d(TAG, "bleGattCallback: onConnectionStateChange = Connected to " + gatt.getDevice().getName());
//                stopScan();
                gatt.discoverServices();
                updateHeartyPatchConnected();
            }
            else if(newState == BluetoothProfile.STATE_DISCONNECTED){
                Log.d(TAG, "bleGattCallback: onConnectionStateChange = Disconnected");
                if(gatt.getDevice().getName().contains("HeartyPatch")){
                    isHeartyPatchConnected = false;
                    numHeartyPatchPts = 0;
                    updateHeartyPatchDisconnected();
                }
                numHeartyPatchPts = 0;
                updateHeartyPatchDisconnected();
//                startScan();
            }
            else if(newState == BluetoothProfile.STATE_CONNECTING){
                Log.d(TAG, "bleGattCallback: onConnectionStateChange = Connecting.....");
            }
            else if(newState == BluetoothProfile.STATE_DISCONNECTING){
                Log.d(TAG, "bleGattCallback: onConnectionStateChange = Disconnecting.....");
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            Log.d(TAG, "bleGattCallback: onServicesDiscovered");
            if(status == BluetoothGatt.GATT_SUCCESS){
                Log.d(TAG, "Services: " + gatt.getServices());
                for(BluetoothGattService service : gatt.getServices()){
                    Log.d(TAG, "Service UUID: " + service.getUuid());
                    for(BluetoothGattCharacteristic characteristic : service.getCharacteristics()){
                        Log.d(TAG, "Characteristic UUID: " + characteristic.getUuid());
                        Log.d(TAG, "Characteristic Properties: " + characteristic.getProperties());
                        gatt.setCharacteristicNotification(characteristic, true);

                    }
                }
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);
            Log.d(TAG, "bleGattCallback: onCharacteristicRead");
            if(status == BluetoothGatt.GATT_SUCCESS){
                Log.d(TAG, "Action_Data_Available: " + characteristic.getValue());
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            Log.d(TAG, "bleGattCallback: onCharacteristicWrite");
        }

        @RequiresApi(api = Build.VERSION_CODES.O)
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            Log.d(TAG, "bleGattCallback: onCharacteristicChanged......");
            if(UUID_HEART_RATE_MEASUREMENT.equals(characteristic.getUuid())){
                int flag = characteristic.getProperties();
                int format = -1;

                if ((flag & 0x01) != 0)
                {
                    format = BluetoothGattCharacteristic.FORMAT_UINT16;
//                    Log.d("akw", "Heart rate format UINT16.");
                } else
                {
                    format = BluetoothGattCharacteristic.FORMAT_UINT8;
//                    Log.d("akw", "Heart rate format UINT8.");
                }

                if ((flag & 0x04) != 0)
                {
                    // do nothing....
//                    Log.d("akw", "RRI present");
                }

                globalHR = characteristic.getIntValue(format, 1);
                globalRRI = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 2);
                numHeartyPatchPts++;
                Log.d(TAG, String.format("Received heart rate: %f", globalHR));
                Log.d(TAG, String.format("Received RRI: %f", globalRRI));
                updateHeartyPatchConnected();
                updateHRLevel((int) globalHR);
                updateRRI((int) globalRRI);
                updateNumPts(numHeartyPatchPts);
            }

            if(UUID_BATTERY_LEVEL.equals(characteristic.getUuid())){
                Log.d(TAG, "Battery Level: " + characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0));
                updateBatteryLevel(characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0));
            }

            if(UUID_CUSTOM_HRV.equals(characteristic.getUuid())){
                Log.d(TAG, "HRV: ");

                globalMeanRR = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT32, 0);
                globalSDNN = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 4);
                globalPNN = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 6);
                globalRMSSD = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT16, 10);
                int globalArrDetect = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 12);

                Log.d(TAG, "HRV: globalMean = " + globalMeanRR);
                Log.d(TAG, "HRV: globalSDNN = " + globalSDNN);
                Log.d(TAG, "HRV: global PNN = " + globalPNN);
                Log.d(TAG, "HRV: globalRMSSD = " + globalRMSSD);
                Log.d(TAG, "HRV: globalArrDetect = " + globalArrDetect);
                updateStats(globalMeanRR/100, globalSDNN/100, globalPNN/100, globalRMSSD/100);
            }

            if(UUID_HEART_RATE_MEASUREMENT.equals(characteristic.getUuid()) || UUID_CUSTOM_HRV.equals(characteristic.getUuid())){
                if(canRecordData){
                    recordData(TAG, new float[] {globalHR, globalRRI, (globalMeanRR/100),
                            (globalSDNN/100), (globalPNN/100), (globalRMSSD/100), globalSYS, globalDIA, globalMAP, globalHR_Caretaker, globalRESP});
                }
            }
        }

        @Override
        public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorRead(gatt, descriptor, status);
            Log.d(TAG, "bleGattCallback: onDescriptorRead");
        }
    };

    // UI Update for HeartyPatch
    private float globalHR, globalRRI, globalMeanRR, globalSDNN, globalPNN, globalRMSSD;
    private float globalSYS, globalDIA, globalMAP, globalHR_Caretaker, globalRESP;
    private void updateHeartyPatchConnected(){
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(activeFragment == heartRateFragment){
                    ((HeartRateFragment)activeFragment).statusConnected();
                    updateNumPts(0);
                }
            }
        });
    }
    private void updateHeartyPatchDisconnected(){
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(activeFragment == heartRateFragment){
                    ((HeartRateFragment)activeFragment).statusDisconnected();
                }
            }
        });
    }
    private void updateBatteryLevel(final int newBatteryLevel){
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(activeFragment == heartRateFragment){
                    ((HeartRateFragment)activeFragment).batteryLevelUpdated(newBatteryLevel);
                }
            }
        });
    }
    private void updateNumPts(final int newNumPts){
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(activeFragment == heartRateFragment){
                    ((HeartRateFragment)activeFragment).numPtsUpdated(newNumPts);
                }
            }
        });
    }
    private void updateHRLevel(final int newHR){
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(activeFragment == heartRateFragment){
                    ((HeartRateFragment)activeFragment).HRUpdated(newHR);
                }
            }
        });
    }
    private void updateRRI(final int newRRI){
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
            if(activeFragment == heartRateFragment){
                ((HeartRateFragment)activeFragment).RRIUpdated(newRRI);
            }
            }
        });
    }
    private void updateStats(final float newMeanRR, final float newSDNN, final float newPNN, final float newRMSSD){
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(activeFragment == heartRateFragment){
                    ((HeartRateFragment)activeFragment).meanRRUpdated(newMeanRR);
                    ((HeartRateFragment)activeFragment).SDNNUpdated(newSDNN);
                    ((HeartRateFragment)activeFragment).PNN50Updated(newPNN);
                    ((HeartRateFragment)activeFragment).RMSSDUpdated(newRMSSD);
                }
            }
        });
    }
    private void updateHeartyPatchLogFileName(final String newFilename){
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(activeFragment == heartRateFragment){
                    ((HeartRateFragment)activeFragment).fileNameTextViewUpdated(newFilename);
                }
            }
        });
    }
    private BroadcastReceiver recordDataChanges = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = "com.example.bluetoothtesting.RecordData";
            if(action.equals(intent.getAction())){
                canRecordData = intent.getBooleanExtra("IsChecked", false);
                Log.d(TAG, "RecordDataChanged: " + canRecordData);
                if(canRecordData){
                    startDataLog();
                }
                else{
                    stopDataLog();
                }
            }
        }
    };

    // For Saving Data onto Phone
    private void startDataLog(){
        // Prepare data storage
        File directory = Environment
                .getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        String name = "HeartyPatch" + System.currentTimeMillis() + ".csv";
        File filename = new File(directory, name);
        try {
            logFile = new BufferedWriter(new FileWriter(filename));
        } catch (IOException e) {

            Log.d(TAG,"Error creating file");
            e.printStackTrace();
        }

        updateHeartyPatchLogFileName("Recording to: Download/" + name);

        // Header for the HeartyPatch Log File
        String line = "Timestamp, HeartRate, RRI,Mean RR-I, SDNN, PNN50, RMSSD, Systolic, Diastolic, MAP, HR-Caretaker, RESP, " +
                        "LocalChestGyro_x[1/s], LocalChestGyro_y[1/s], LocalChestGyro_z[1/s]," +
                        "LocalChestAcc_x[m/s^2], LocalChestAcc_y[m/s^2], LocalChestAcc_z[m/s^2]," +
                        "GlobalChestGyro_x[1/s], GlobalChestGyro_x[1/s], GlobalChestGyro_x[1/s]," +
                        "GlobalChestAcc_x[m/s^2], GlobalChestAcc_y[m/s^2], GlobalChestAcc_z[m/s^2]," +

                        "LocalNeckGyro_x[1/s], LocalNeckGyro_y[1/s], LocalNeckGyro_z[1/s]," +
                        "LocalNeckAcc_x[m/s^2], LocalNeckAcc_y[m/s^2], LocalNeckAcc_z[m/s^2]," +
                        "GlobalNeckGyro_x[1/s], GlobalNeckGyro_y[1/s], GlobalNeckGyro_z[1/s]," +
                        "GlobalNeckAcc_x[m/s^2], GlobalNeckAcc_y[m/s^2], GlobalNeckAcc_z[m/s^2]," +

                        "LocalRightForeArmGyro_x[1/s], LocalRightForeArmGyro_y[1/s], LocalRightForeArmGyro_z[1/s]," +
                        "LocalRightForeArmAcc_x[m/s^2], LocalRightForeArmAcc_y[m/s^2], LocalRightForeArmAcc_z[m/s^2]," +
                        "GlobalRightForeArmGyro_x[1/s], GlobalRightForeArmGyro_x[1/s], GlobalRightForeArmGyro_x[1/s]," +
                        "GlobalRightForeArmAcc_x[m/s^2], GlobalRightForeArmAcc_y[m/s^2], GlobalRightForeArmAcc_z[m/s^2]," +

                        "LocalLeftForeArmGyro_x[1/s], LocalLeftForeArmGyro_y[1/s], LocalLeftForeArmGyro_z[1/s]," +
                        "LocalLeftForeArmAcc_x[m/s^2], LocalLeftForeArmAcc_y[m/s^2], LocalLeftForeArmAcc_z[m/s^2]," +
                        "GlobalLeftForeArmGyro_x[1/s], GlobalLeftForeArmGyro_x[1/s], GlobalLeftForeArmGyro_x[1/s]," +
                        "GlobalLeftForeArmAcc_x[m/s^2], GlobalLeftForeArmAcc_y[m/s^2], GlobalLeftForeArmAcc_z[m/s^2]," +

                        "LocalRightTopFootGyro_x[1/s], LocalRightTopFootGyro_y[1/s], LocalRightTopFootGyro_z[1/s]," +
                        "LocalRightTopFootAcc_x[m/s^2], LocalRightTopFootAcc_y[m/s^2], LocalRightTopFootAcc_z[m/s^2]," +
                        "GlobalRightTopFootGyro_x[1/s], GlobalRightTopFootGyro_x[1/s], GlobalRightTopFootGyro_x[1/s]," +
                        "GlobalRightTopFootAcc_x[m/s^2], GlobalRightTopFootAcc_y[m/s^2], GlobalRightTopFootAcc_z[m/s^2]," +

                        "LocalLeftFootTopGyro_x[1/s], LocalLeftFootTopGyro_y[1/s], LocalLeftFootTopGyro_z[1/s]," +
                        "LocalLeftFootTopAcc_x[m/s^2], LocalLeftFootTopAcc_y[m/s^2], LocalLeftFootTopAcc_z[m/s^2]," +
                        "GlobalLeftFootTopGyro_x[1/s], GlobalLeftFootTopGyro_x[1/s], GlobalLeftFootTopGyro_x[1/s]," +
                        "GlobalLeftFootTopAcc_x[m/s^2], GlobalLeftFootTopAcc_y[m/s^2], GlobalLeftFootTopAcc_z[m/s^2]\n";

        try
        {
            logFile.write(line);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }
    private void stopDataLog(){
        canRecordData = false;
        try {
            Log.d(TAG, "Closing LOG File");
            logFile.close();
            updateHeartyPatchLogFileName("Logging stopped");
        } catch (IOException e) {
            Log.d(TAG, "Error Closing LOG File: " + e.getMessage());
            e.printStackTrace();
        }
    }
    @RequiresApi(api = Build.VERSION_CODES.O)
    private void recordData(String tag, String[] values){
        if (logFile == null) {
            Log.d(TAG, "Log File is NULL");
            return;
        }

        DateTimeFormatter dtf = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss:SSS");
        LocalDateTime now = LocalDateTime.now();
        System.out.println(dtf.format(now));

//        String line = "";
        String line = dtf.format(now);
        line += ",";
        if (values != null)
        {
            for (String value : values)
            {
                line += value + ",";
            }
        }
        //line = Long.toString(System.currentTimeMillis()) + "," + line + "\n";

        line = line + "\n";

        try {
            Log.d(TAG, "Writing to Log File");
            logFile.write(line);
        } catch (IOException e) {
            Log.d(TAG, "Error Writing to Log File: " + e.getMessage());
            e.printStackTrace();
        }
    }
    @RequiresApi(api = Build.VERSION_CODES.O)
    private void recordData(String tag, float[] values){
        String[] array = new String[values.length];

        for (int i = 0; i < values.length; i++) {
            array[i] = Float.toString(values[i]);
        }
        recordData(tag, array);
    }

    // UI Update for Blood Pressure
    public void manualCalibration(View v){
        TextView systolicTextView = findViewById(R.id.systolicTextView);
        TextView diastolicTextView = findViewById(R.id.diastolicTextView);
        Intent connectedIntent = new Intent("com.example.bluetoothtesting.ManualCalibration");
        connectedIntent.putExtra("Systolic Data", "" + systolicTextView.getText());
        connectedIntent.putExtra("Diastolic Data", "" + diastolicTextView.getText());
        sendBroadcast(connectedIntent);
    }
    private void updateBatteryLevelBloodPressure(final float newBatteryLevel){
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(activeFragment == bloodPressureFragment){
                    ((BloodPressureFragment)activeFragment).batteryLevelUpdated(newBatteryLevel);
                }
            }
        });
    }
    private void updateNumPtsBloodPressure(final int newNumPts){
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(activeFragment == bloodPressureFragment){
                    ((BloodPressureFragment)activeFragment).numPtsUpdated(newNumPts);
                }
            }
        });
    }
    private void updateConnectionStatusBloodPressure(final String connectionStatus){
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ((BloodPressureFragment)activeFragment).updateConnectionStatus(connectionStatus);
            }
        });
    }
    private void updateVitals(final int systolic, final int diastolic, final int MAP, final int HR, final int RESP){
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ((BloodPressureFragment)activeFragment).updateConnectionStatus("Connected");
                ((BloodPressureFragment)activeFragment).vitalsUpdated(systolic, diastolic, MAP, HR, RESP);
            }
        });

    }
    private BroadcastReceiver bloodPressureDataBroadcastReceiver = new BroadcastReceiver(){
        @RequiresApi(api = Build.VERSION_CODES.O)
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = getApplicationContext().getPackageName() + "_BloodPressure";
            if(action.equals(intent.getAction())){
                // Connection Status Update
                String connectionStatus = intent.getStringExtra(
                        getApplicationContext().getPackageName() + "_BloodPressure_ConnectionStatus");
                if(activeFragment == bloodPressureFragment && connectionStatus != null){
                    updateConnectionStatusBloodPressure(connectionStatus);
                }

                // Vitals Update
                int systolic = intent.getIntExtra(getApplicationContext().getPackageName() + "_BloodPressure_Systolic", -1);
                int diastolic = intent.getIntExtra(getApplicationContext().getPackageName() + "_BloodPressure_Diastolic", -1);
                int MAP = intent.getIntExtra(getApplicationContext().getPackageName() + "_BloodPressure_MAP", -1);
                int HR = intent.getIntExtra(getApplicationContext().getPackageName() + "_BloodPressure_HR", -1);
                int RESP = intent.getIntExtra(getApplicationContext().getPackageName() + "_BloodPressure_RESP", -1);

                globalSYS = systolic;
                globalDIA = diastolic;
                globalMAP = MAP;
                globalHR_Caretaker = HR;
                globalRESP = RESP;

                if(canRecordData && systolic != -1 && diastolic != -1 && MAP != -1 && HR != -1 && RESP != -1){
                    Log.d(TAG, "Saving Data: " + globalSYS + " " + globalDIA);
                    recordData(TAG, aggregateSensorData(new float[]{}));
//                    recordData(TAG, new float[] {globalHR, globalRRI, (globalMeanRR/100),
//                            (globalSDNN/100), (globalPNN/100), (globalRMSSD/100), globalSYS, globalDIA, globalMAP, globalHR_Caretaker, globalRESP});
                }

//                if(canRecordData){
//                    Log.d(TAG, "Saving Data: " + globalSYS + " " + globalDIA);
//                    recordData(TAG, new float[] {globalHR, globalRRI, (globalMeanRR/100),
//                            (globalSDNN/100), (globalPNN/100), (globalRMSSD/100), globalSYS, globalDIA, globalMAP, globalHR_Caretaker, globalRESP,
//                            0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
//                            0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f});
//                }

                if(activeFragment == bloodPressureFragment && systolic != -1 && diastolic != -1 && MAP != -1 && HR != -1 && RESP != -1){
                    numBloodPressurePts++;
                    globalSYS = systolic;
                    globalDIA = diastolic;
                    globalMAP = MAP;
                    globalHR_Caretaker = HR;
                    globalRESP = RESP;
                    updateVitals(systolic, diastolic, MAP, HR, RESP);
                    updateNumPtsBloodPressure(numBloodPressurePts);
                    updateConnectionStatusBloodPressure("Connected");
                }

                // Battery Update
                float batteryPercentage = intent.getFloatExtra(getApplicationContext().getPackageName() + "_BloodPressure_BatteryInfo", -1);
                if(activeFragment == bloodPressureFragment && batteryPercentage != -1){
                    Log.d(TAG, "BatteryPercentage Update: " + batteryPercentage);
                    updateBatteryLevelBloodPressure(batteryPercentage);
                    updateConnectionStatusBloodPressure("Connected");
                }
            }
        }
    };

    // Methods for Notch Devices
    private BroadcastReceiver notchServiceBroadcastReceiver = new BroadcastReceiver() {
        @RequiresApi(api = Build.VERSION_CODES.O)
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = getApplicationContext().getPackageName() + "_NotchService";
            if(action.equals(intent.getAction())){
                // Show Alert
                String alertTitle = intent.getStringExtra(getApplicationContext().getPackageName() + "_NotchService_AlertTitle");
                String alertMessage = intent.getStringExtra(getApplicationContext().getPackageName() + "_NotchService_AlertMessage");
                if(alertTitle != null && alertMessage != null){
                    showAlertDialog(alertTitle, alertMessage);
                }

                // Dismiss Alert
                boolean canDismissAlert = intent.getBooleanExtra(getApplicationContext().getPackageName() + "_NotchService_AlertDismiss", false);
                if(canDismissAlert){
                    dismissAlertDialog();
                }

                // Docker Animation Enable
                boolean canEnableDocker = intent.getBooleanExtra(getApplicationContext().getPackageName() + "_NotchService_EnableDocker", false);
                if(canEnableDocker){
                    showDockerImage();
                }
                else{
                    disableDockerImage();
                }

                // Update on Notch Network
                String updatedNotchNetwork = intent.getStringExtra(
                        getApplicationContext().getPackageName() + "_NotchService_NetworkUpdate");
                if(activeFragment == imuFragment && updatedNotchNetwork != null){
                    updateCurrentNetwork(updatedNotchNetwork);
                }

                // Update on the Device List
                String updatedDeviceList =  intent.getStringExtra(
                        getApplicationContext().getPackageName() + "_NotchService_DeviceListUpdate");
                if(activeFragment == imuFragment && updatedDeviceList != null){
                    updateDeviceList(updatedDeviceList);
                }

                // Update on real-time data
                float [] real_time_data = intent.getFloatArrayExtra(getApplicationContext().getPackageName() + "_NotchService_RealTimeDataUpdate");
                if(real_time_data != null && real_time_data.length > 0 && canRecordData){
                    // Save Notch Data
                    recordData(TAG, aggregateSensorData(real_time_data));
                }

                // Update on the Battery level
                String updatedBatteryLevel =  intent.getStringExtra(
                        getApplicationContext().getPackageName() + "_NotchService_BatteryLevelUpdate");
                if(activeFragment == imuFragment && updatedBatteryLevel != null){
                    updateNotchBatteryLevel(updatedBatteryLevel);
                }
            }

        }
    };



    private boolean hasPermissions(Context context, String permissions[]) {
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && context != null && permissions != null) {
            for (String permission : permissions) {
                if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
        }
        return true;
    }

    private void showDockerImage(){
        dockImage.setVisibility(View.VISIBLE);
        mDockAnimation.setVisible(false, true);
        mDockAnimation.start();
    }
    private void disableDockerImage(){
        dockImage.setVisibility(View.GONE);
        mDockAnimation.stop();
    }
    private void updateDeviceList(final String updatedDeviceList){
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "\n\nUpdating Notch Device List\n\n");
                if(activeFragment == imuFragment){
                    ((IMUFragment)activeFragment).updateDeviceListTextView(updatedDeviceList);
                }
            }
        });
    }
    private void updateCurrentNetwork(final String newNotchNetworkString){
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(activeFragment == imuFragment){
                    ((IMUFragment)activeFragment).updateCurrentNetworkTextView(newNotchNetworkString);
                }
            }
        });
    }
    private void updateNotchBatteryLevel(final String newBatteryLevel){
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if(activeFragment == imuFragment){
                    ((IMUFragment)activeFragment).updateNotchBatteryLevel(newBatteryLevel);
                }
            }
        });
    }
    private float [] aggregateSensorData(float[] notchData){
        float [] floatArray = new float[11 + notchData.length];
        int i = 0;
        floatArray[i++] = globalHR;
        floatArray[i++] = globalRRI;
        floatArray[i++] = globalMeanRR/100;
        floatArray[i++] = globalSDNN/100;
        floatArray[i++] = globalPNN/100;
        floatArray[i++] = globalRMSSD/100;
        floatArray[i++] = globalSYS;
        floatArray[i++] = globalDIA;
        floatArray[i++] = globalMAP;
        floatArray[i++] = globalHR_Caretaker;
        floatArray[i++] = globalRESP;
        for(float f : notchData) floatArray[i++] = f;
        return floatArray;
    }


    public void pairNewNotchDevice(View v){
        // notify background Notch service to pair new notch device
        Intent notifyPairNewNotchDeviceIntent = new Intent(getApplicationContext().getPackageName() + "_NotchActionButton");
        notifyPairNewNotchDeviceIntent.putExtra(getApplicationContext().getPackageName() + "_NotchActionButton_PairNewDevice", true);
        sendBroadcast(notifyPairNewNotchDeviceIntent);
    }
    public void syncNotchPairing(View v){
        // notify background Notch service to sync all paired Notches
        Intent notifySyncNotchPairingIntent = new Intent(getApplicationContext().getPackageName() + "_NotchActionButton");
        notifySyncNotchPairingIntent.putExtra(getApplicationContext().getPackageName() + "_NotchActionButton_SyncNotchPairing", true);
        sendBroadcast(notifySyncNotchPairingIntent);
    }
    public void removeAllNotchDevice(View v){
        // notify background Notch service to pair new notch device
        Intent notifyRemoveAllDeviceIntent = new Intent(getApplicationContext().getPackageName() + "_NotchActionButton");
        notifyRemoveAllDeviceIntent.putExtra(getApplicationContext().getPackageName() + "_NotchActionButton_RemoveAllDevice", true);
        sendBroadcast(notifyRemoveAllDeviceIntent);
    }
    public void connectToNetwork(View v){
        // notify background Notch service to connect all Notches to a network
        Intent notifyConnectToNetworkIntent = new Intent(getApplicationContext().getPackageName() + "_NotchActionButton");
        notifyConnectToNetworkIntent.putExtra(getApplicationContext().getPackageName() + "_NotchActionButton_ConnectToNetwork", true);
        sendBroadcast(notifyConnectToNetworkIntent);
    }
    public void configureCalibration(View v){
        // notify background Notch service to configure calibration of Notches
        Intent notifyConfigureCalibrationIntent = new Intent(getApplicationContext().getPackageName() + "_NotchActionButton");
        notifyConfigureCalibrationIntent.putExtra(getApplicationContext().getPackageName() + "_NotchActionButton_ConfigureCalibration", true);
        sendBroadcast(notifyConfigureCalibrationIntent);
    }
    public void startCalibration(View v){
        // notify background Notch service to start calibration of Notches
        Intent notifyStartCalibrationIntent = new Intent(getApplicationContext().getPackageName() + "_NotchActionButton");
        notifyStartCalibrationIntent.putExtra(getApplicationContext().getPackageName() + "_NotchActionButton_StartCalibration", true);
        sendBroadcast(notifyStartCalibrationIntent);
    }
    public void configureSteady(View v){
        // notify background Notch service to configure for Steady calibration
        Intent notifyConfigureSteadyIntent = new Intent(getApplicationContext().getPackageName() + "_NotchActionButton");
        notifyConfigureSteadyIntent.putExtra(getApplicationContext().getPackageName() + "_NotchActionButton_ConfigureSteady", true);
        sendBroadcast(notifyConfigureSteadyIntent);
    }
    public void startSteady(View v){
        // notify background Notch service to start Steady calibration
        Intent notifyStartSteadyIntent = new Intent(getApplicationContext().getPackageName() + "_NotchActionButton");
        notifyStartSteadyIntent.putExtra(getApplicationContext().getPackageName() + "_NotchActionButton_StartSteady", true);
        sendBroadcast(notifyStartSteadyIntent);
    }
    public void startCapture(View v){
        // notify background Notch service to start real-time capture
        Intent notifyStartCaptureIntent = new Intent(getApplicationContext().getPackageName() + "_NotchActionButton");
        notifyStartCaptureIntent.putExtra(getApplicationContext().getPackageName() + "_NotchActionButton_StartCapture", true);
        sendBroadcast(notifyStartCaptureIntent);
    }
    public void stopCapture(View v){
        // notify background Notch service to stop real-time capture
        Intent notifyStopCaptureIntent = new Intent(getApplicationContext().getPackageName() + "_NotchActionButton");
        notifyStopCaptureIntent.putExtra(getApplicationContext().getPackageName() + "_NotchActionButton_StopCapture", true);
        sendBroadcast(notifyStopCaptureIntent);
    }
    public void notchBatteryLevelButtonClicked(View v){
        // notify background Notch service of check battery level of all notch connected to the network
        Intent notifyCheckBatteryLevelIntent = new Intent(getApplicationContext().getPackageName() + "_NotchActionButton");
        notifyCheckBatteryLevelIntent.putExtra(getApplicationContext().getPackageName() + "_NotchActionButton_CheckBatteryLevel", true);
        sendBroadcast(notifyCheckBatteryLevelIntent);
    }
}
