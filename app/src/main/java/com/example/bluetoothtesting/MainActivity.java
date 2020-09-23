package com.example.bluetoothtesting;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.app.JobIntentService;
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
import android.content.DialogInterface;
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
import android.telecom.ConnectionService;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Toast;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.squareup.okhttp.internal.Util;
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
import com.wearnotch.internal.util.EmptyCancellable;
import com.wearnotch.internal.util.IOUtil;
import com.wearnotch.notchmaths.fvec3;
import com.wearnotch.service.NotchAndroidService;
import com.wearnotch.service.common.Cancellable;
import com.wearnotch.service.common.NotchCallback;
import com.wearnotch.service.common.NotchError;
import com.wearnotch.service.common.NotchProgress;
import com.wearnotch.service.network.NotchService;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Array;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

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
    private static final String DEFAULT_USER_LICENSE = "Fam5ERAuAnQr18tR3Kpb";  // Extended License for Notch
    private static final long CALIBRATION_TIME = 7000L;

    private static final int REQUEST_ALL_PERMISSION = 1;
    private static String[] PERMISSIONS = {Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.ACCESS_FINE_LOCATION};
    private CustomNotchService notchService;
    private Handler mHandler = new Handler(Looper.getMainLooper());

    private final List<NotchServiceConnection> mNotchServiceConnections =
            new ArrayList<NotchServiceConnection>();
    private NotchDataBase mNotchDB;
    private Measurement mCurrentNotchMeasurement;
    private NotchService mNotchService;
    private ComponentName mNotchServiceComponent;
    private NotchChannel mNotchChannel;
    private Workout mNotchWorkout;
    private ImageView dockImage;
    private AnimationDrawable mDockAnimation;
    private VisualiserData mNotchRealTimeData;
    private Skeleton mNotchSkeleton;
    private Cancellable c;

    @Override
    protected void onDestroy() {
        // stop background service
        Intent intent = new Intent(this, BloodPressureData.class);
        stopService(intent);

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
        notchService = new CustomNotchService();
//        mNotchChannel = NotchChannel.fromChar('A');

        // Intent to start Notch Service
        Intent controlServiceIntent = new Intent(this, NotchAndroidService.class);
        startService(controlServiceIntent);
        bindService(controlServiceIntent, notchService, Context.BIND_AUTO_CREATE);

        mNotchDB = NotchDataBase.getInst();
        if (!hasPermissions(mActivity, PERMISSIONS)) {
            requestPermissions(PERMISSIONS, REQUEST_ALL_PERMISSION);
        }
        // Setting up user
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (mNotchService != null) {
                    if (DEFAULT_USER_LICENSE.length() > 0) {
                        mNotchService.setLicense(DEFAULT_USER_LICENSE);
                        updateDeviceList(DEFAULT_USER_LICENSE);

                    }
                }
            }
        }, 1000L);
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
            logFile.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        updateHeartyPatchLogFileName("Logging stopped");
    }
    @RequiresApi(api = Build.VERSION_CODES.O)
    private void recordData(String tag, String[] values){
        if (logFile == null) {
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
            logFile.write(line);
        } catch (IOException e) {
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
                    if(canRecordData){
                        recordData(TAG, new float[] {globalHR, globalRRI, (globalMeanRR/100),
                                (globalSDNN/100), (globalPNN/100), (globalRMSSD/100), globalSYS, globalDIA, globalMAP, globalHR_Caretaker, globalRESP});
                    }
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
    public class CustomNotchService implements ServiceConnection, NotchServiceConnection {
        private final String TAG = "CustomNotchService";

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "onServiceConnected");
            if(service instanceof NotchService){
                Log.d(TAG, "onServiceConnected: instance of NotchService");
                mNotchServiceComponent = name;
                mNotchService = (NotchService) service;
                fireNotchServiceChange();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "onServiceDisconnected");
            if (name.equals(mNotchServiceComponent)) {
                mNotchServiceComponent = null;
                mNotchService = null;
                fireNotchServiceChange();
            }
        }

        private void fireNotchServiceChange(){
            for (NotchServiceConnection c : mNotchServiceConnections) {
                if (mNotchService != null) {
                    c.onServiceConnected(mNotchService);
                } else {
                    c.onServiceDisconnected();
                }
            }
        }

        @Override
        public void onServiceConnected(NotchService notchService) {
            Log.d(TAG, "onNotchServiceConnected");
            mNotchService = notchService;
        }

        @Override
        public void onServiceDisconnected() {
            Log.d(TAG, "onNotchServiceDisconnected");
            mNotchService = null;
        }
    }
    public class EmptyNotchCallback<T> implements NotchCallback<T>{

        @Override
        public void onProgress(@Nonnull NotchProgress notchProgress) {

        }

        @Override
        public void onSuccess(@Nullable T t) {

        }

        @Override
        public void onFailure(@Nonnull NotchError notchError) {

        }

        @Override
        public void onCancelled() {

        }
    }
    public static boolean hasPermissions(Context context, String permissions[]) {
        if (android.os.Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && context != null && permissions != null) {
            for (String permission : permissions) {
                if (ActivityCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }
            }
        }
        return true;
    }

    private void updateDeviceList(final String user){
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mNotchDB == null) mNotchDB = NotchDataBase.getInst();
                StringBuilder sb = new StringBuilder();
                sb.append("Device List:\n");
                for (Device device : mNotchDB.findAllDevices(user)) {
                    sb.append("Notch ").append(device.getNotchDevice().getNetworkId()).append(" (");
                    sb.append(device.getNotchDevice().getDeviceMac()).append(") ");
                    sb.append("FW: " + device.getSwVersion() + ", ");
                    sb.append("Ch: " + device.getChannel().toChar() + "\n");
                }
                Log.d(TAG, "UpdateDeviceList: " + sb.toString());

                if(activeFragment == imuFragment){
                    ((IMUFragment)activeFragment).updateDeviceListTextView(sb.toString());
                }
            }
        });
    }
    private void updateCurrentNetwork(){
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                sb.append("Current Network:\n");
                if (mNotchService.getNetwork() != null) {
                    for (ActionDevice device : mNotchService.getNetwork().getDeviceSet()) {
                        sb.append(device.getNetworkId()).append(", ");
                    }
                }
                Log.d(TAG, "UpdateCurrentNetwork: " + sb.toString());
                if(activeFragment == imuFragment){
                    ((IMUFragment)activeFragment).updateCurrentNetworkTextView(sb.toString());
                }
            }
        });
    }
    private char getChannel(final String user){
        if (mNotchDB == null) mNotchDB = NotchDataBase.getInst();
        Set<Character> channels = new HashSet<>();
        for (Device device : mNotchDB.findAllDevices(user)) {
            channels.add(device.getChannel().toChar());
        }

        if(channels.size() == 0){
            return '1';
        }
        return (char) channels.toArray()[0];
    }
    private void configureForRealTimeCapture(){
        c = mNotchService.capture(new NotchCallback<Void>() {
            @Override
            public void onProgress(NotchProgress progress) {
                if (canRecordData == true && progress.getState() == NotchProgress.State.REALTIME_UPDATE) {
                    mNotchRealTimeData = (VisualiserData) progress.getObject();
                    mNotchSkeleton = mNotchRealTimeData.getSkeleton();
                    calculateNotchAngle(mNotchRealTimeData.getStartingFrame());
                }
            }

            @Override
            public void onSuccess(Void nothing) {
                dismissAlertDialog();
            }

            @Override
            public void onFailure(NotchError notchError) {
                Log.d(TAG, "Failed to Capture real-time data");
                Toast.makeText(mActivity, "Failed to Capture real-time data", Toast.LENGTH_LONG).show();
                dismissAlertDialog();
            }

            @Override
            public void onCancelled() {
                Log.d(TAG, "Real-time Measurement Stopped");
                Toast.makeText(mActivity, "Real-time Measurement Stopped", Toast.LENGTH_LONG).show();
                dismissAlertDialog();
            }
        });
    }
    private void calculateNotchAngle(int frameIndex){
        new Thread(new Runnable() {
            @RequiresApi(api = Build.VERSION_CODES.O)
            public void run() {
                Bone chest = mNotchSkeleton.getBone("ChestBottom");
                Bone neck = mNotchSkeleton.getBone("Neck");
                Bone rightForeArm = mNotchSkeleton.getBone("RightForeArm");
                Bone leftForeArm = mNotchSkeleton.getBone("LeftForeArm");
                Bone rightTopFoot = mNotchSkeleton.getBone("RightFootTop");
                Bone leftTopFoot = mNotchSkeleton.getBone("LeftFootTop");

                ArrayList<Float> notchData = new ArrayList<>();
                notchData.add(globalHR);
                notchData.add(globalRRI);
                notchData.add(globalMeanRR/100);
                notchData.add(globalSDNN/100);
                notchData.add(globalPNN/100);
                notchData.add(globalRMSSD/100);
                notchData.add(globalSYS);
                notchData.add(globalDIA);
                notchData.add(globalMAP);
                notchData.add(globalHR_Caretaker);
                notchData.add(globalRESP);

                notchData.addAll(readRawNotchData(chest, frameIndex));
                notchData.addAll(readRawNotchData(neck, frameIndex));
                notchData.addAll(readRawNotchData(rightForeArm, frameIndex));
                notchData.addAll(readRawNotchData(leftForeArm, frameIndex));
                notchData.addAll(readRawNotchData(rightTopFoot, frameIndex));
                notchData.addAll(readRawNotchData(leftTopFoot, frameIndex));

                Log.d(TAG, "Real-Time Data:\n" + notchData);

                // Saving Data
                float [] floatArray = new float[notchData.size()];
                int i = 0;
                for(float f : notchData){
                    floatArray[i++] = f;
                }
                recordData(TAG, floatArray);
            }
        }).start();
    }
    private ArrayList<Float> readRawNotchData(Bone bone, int frameIndex){
        fvec3 localBoneGyro = mNotchRealTimeData.getLocalSensorGyro(bone, frameIndex);
        fvec3 localBoneAcc = mNotchRealTimeData.getLocalSensorAcc(bone, frameIndex);
        fvec3 globalBoneGyro = mNotchRealTimeData.getLocalSensorGyro(bone, frameIndex);
        fvec3 globalBoneAcc = mNotchRealTimeData.getGlobalSensorAcc(bone, frameIndex);
        ArrayList<Float> data = new ArrayList<>();

        if(localBoneGyro != null){
            data.add(localBoneGyro.get(0));
            data.add(localBoneGyro.get(1));
            data.add(localBoneGyro.get(2));
        }
        else{
            data.add((float) 0.0);
            data.add((float) 0.0);
            data.add((float) 0.0);
        }

        if(localBoneAcc != null){
            data.add(localBoneAcc.get(0));
            data.add(localBoneAcc.get(1));
            data.add(localBoneAcc.get(2));
        }
        else{
            data.add((float) 0.0);
            data.add((float) 0.0);
            data.add((float) 0.0);
        }

        if(globalBoneGyro != null){
            data.add(globalBoneGyro.get(0));
            data.add(globalBoneGyro.get(1));
            data.add(globalBoneGyro.get(2));
        }
        else{
            data.add((float) 0.0);
            data.add((float) 0.0);
            data.add((float) 0.0);
        }

        if(globalBoneAcc != null){
            data.add(globalBoneAcc.get(0));
            data.add(globalBoneAcc.get(1));
            data.add(globalBoneAcc.get(2));
        }
        else{
            data.add((float) 0.0);
            data.add((float) 0.0);
            data.add((float) 0.0);
        }

        Log.d(TAG, "RawNotchData: " + data);
        return  data;
    }

    public void pairNewNotchDevice(View v){
        showAlertDialog("Pairing Notch....", "Please wait");
        Toast.makeText(mActivity, "Pairing Device......", Toast.LENGTH_LONG).show();
        mNotchService.pair(new EmptyNotchCallback<Device>(){
            @Override
            public void onSuccess(@Nullable Device device) {
                Log.d(TAG, "NotchPair: Success!!");
                updateDeviceList(mNotchService.getLicense());
                Toast.makeText(mActivity, "Pairing Success!", Toast.LENGTH_LONG).show();
                dismissAlertDialog();
            }

            @Override
            public void onFailure(@Nonnull NotchError notchError) {
                Log.d(TAG, "NotchPair: Failed: " + notchError.getStatus());
                Toast.makeText(mActivity, "Failed to Pair Notch:\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                dismissAlertDialog();
            }
        });
    }
    public void syncNotchPairing(View v){
        showAlertDialog("Syncing Notch....", "Please wait");
        mNotchService.syncPairedDevices(new EmptyNotchCallback<Void>(){
            @Override
            public void onSuccess(@Nullable Void aVoid) {
                Log.d(TAG, "NotchSyncPairing: Success!!");
                updateDeviceList(mNotchService.getLicense());
                Toast.makeText(mActivity, "Sync Pairing Success", Toast.LENGTH_LONG);
                dismissAlertDialog();
            }

            @Override
            public void onFailure(@Nonnull NotchError notchError) {
                Log.d(TAG, "NotchSyncPairing: Failure");
                Toast.makeText(mActivity, "Failed of Sync Pairing:\n" + notchError.getStatus(), Toast.LENGTH_LONG);
                dismissAlertDialog();
            }
        });
    }
    public void removeAllNotchDevice(View v){
        showAlertDialog("Removing Paired Notches....", "Please wait");
        mNotchService.deletePairedDevices(null, new EmptyNotchCallback<Void>(){
            @Override
            public void onSuccess(@Nullable Void aVoid) {
                Log.d(TAG, "NotchRemoveAllDevice: Success");
                updateDeviceList(mNotchService.getLicense());
                updateCurrentNetwork();
                mNotchService.disconnect(new EmptyNotchCallback<Void>() {
                    @Override
                    public void onSuccess(@Nullable Void aVoid) {
                        Toast.makeText(mActivity, "Removed All Notch" , Toast.LENGTH_LONG).show();
                        dismissAlertDialog();
                    }

                    @Override
                    public void onFailure(@Nonnull NotchError notchError) {
                        dismissAlertDialog();
                    }
                });
            }

            @Override
            public void onFailure(@Nonnull NotchError notchError) {
                Log.d(TAG, "NotchRemoveAllDevice: Failed: " + notchError.getStatus());
                Toast.makeText(mActivity, "Failed to Remove All Notch:\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                dismissAlertDialog();
            }
        });
    }
    public void connectToNetwork(View v){
        showAlertDialog("Connection to Network....", "Please wait");
        if(getChannel(mNotchService.getLicense()) != '1'){
            mNotchChannel = NotchChannel.fromChar(getChannel(mNotchService.getLicense()));
        }

        mNotchService.disconnect(new EmptyNotchCallback<Void>() {
            @Override
            public void onSuccess(Void aVoid) {
                mNotchService.uncheckedInit(mNotchChannel, new EmptyNotchCallback<NotchNetwork>() {
                    @Override
                    public void onSuccess(NotchNetwork notchNetwork) {
                        updateCurrentNetwork();
                        updateDeviceList(mNotchService.getLicense());
                        Log.d(TAG, "ConnectToNetwork: Success");
                        Toast.makeText(mActivity, "ConnectToNetwork: Success", Toast.LENGTH_LONG).show();
                        dismissAlertDialog();
                    }

                    @Override
                    public void onFailure(@Nonnull NotchError notchError) {
                        Log.d(TAG, "ConnectToNetwork: Failure: " + notchError.getStatus());
                        Toast.makeText(mActivity, "ConnectToNetwork: Failure\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                        dismissAlertDialog();
                    }
                });
            }

            @Override
            public void onFailure(@Nonnull NotchError notchError) {
                Log.d(TAG, "Disconnect: Failure: " + notchError.getStatus());
                Toast.makeText(mActivity, "Disconnect: Failure\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                dismissAlertDialog();
            }
        });
    }
    public void configureCalibration(View v){
        showAlertDialog("Configuring Calibration....", "Please wait");
        if(getChannel(mNotchService.getLicense()) != '1'){
            mNotchChannel = NotchChannel.fromChar(getChannel(mNotchService.getLicense()));
        }

        mNotchService.uncheckedInit(mNotchChannel, new EmptyNotchCallback<NotchNetwork>() {
            @Override
            public void onSuccess(NotchNetwork notchNetwork) {
                updateCurrentNetwork();
                Toast.makeText(mActivity, "Success UncheckedInit!\nConfiguring Calibration....", Toast.LENGTH_LONG).show();
                mNotchService.configureCalibration(true, new EmptyNotchCallback<Void>(){
                    @Override
                    public void onSuccess(Void aVoid) {
                        Toast.makeText(mActivity, "Success Configure Calibration!\nReady to start Calibration", Toast.LENGTH_LONG).show();
                        dismissAlertDialog();
                    }

                    @Override
                    public void onFailure(@Nonnull NotchError notchError) {
                        Toast.makeText(mActivity, "Failed Configure Calibration:\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                        dismissAlertDialog();
                    }
                });
            }

            @Override
            public void onFailure(@Nonnull NotchError notchError) {
                Toast.makeText(mActivity, "Failed UncheckedInit:\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                dismissAlertDialog();
            }
        });
    }
    public void startCalibration(View v){
        Toast.makeText(mActivity, "Starting Calibration.....", Toast.LENGTH_LONG).show();
        mNotchService.calibration(new EmptyNotchCallback<Measurement>());
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                dockImage.setVisibility(View.VISIBLE);
                mDockAnimation.setVisible(false, true);
                mDockAnimation.start();
            }
        });
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                dockImage.setVisibility(View.GONE);
                mDockAnimation.stop();
                showAlertDialog("Fetching Calibration Result....", "Please wait");
                mNotchService.getCalibrationData(new EmptyNotchCallback<Boolean>(){
                    @Override
                    public void onSuccess(Boolean aBoolean) {
                        Log.d(TAG, "Successfully Completed Calibration");
                        Toast.makeText(mActivity, "Successfully Completed Calibration", Toast.LENGTH_LONG).show();
                        dismissAlertDialog();
                    }

                    @Override
                    public void onFailure(@Nonnull NotchError notchError) {
                        Log.d(TAG, "Failed Getting Calibration Data:\n" + notchError.getStatus());
                        Toast.makeText(mActivity, "Failed Getting Calibration Data:\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                        dismissAlertDialog();
                    }
                });
            }
        }, CALIBRATION_TIME);
    }
    public void configureSteady(View v){
        Skeleton skeleton;
        try {
            skeleton = Skeleton.from(new InputStreamReader(getApplicationContext().getResources().openRawResource(R.raw.skeleton_male), "UTF-8"));
            Workout workout = Workout.from("Demo_config", skeleton, IOUtil.readAll(new InputStreamReader(getApplicationContext().getResources().openRawResource(R.raw.config_1_chest))));
//            Workout workout = Workout.from("Demo_config", skeleton, IOUtil.readAll(new InputStreamReader(getApplicationContext().getResources().openRawResource(R.raw.config_6_upper_body))));

            workout = workout.withRealTime(true);           // Only for real-time
            workout = workout.withMeasurementType(MeasurementType.STEADY_SKIP);     // Only for real-time

            mNotchWorkout = workout;
            showAlertDialog("Initializing Steady....", "Please wait");
            mNotchService.init(mNotchChannel, workout, new EmptyNotchCallback<NotchNetwork>() {
                @Override
                public void onSuccess(NotchNetwork notchNetwork) {
                    updateCurrentNetwork();
                    dismissAlertDialog();
                    Log.d(TAG, "OnSuccess Init");
//                    showAlertDialog("Configuring Steady....", "Please wait");

                    // Display bone-notch configuration
                    StringBuilder sb = new StringBuilder();
                    sb.append("Measured bones:\n\n");
                    if (mNotchWorkout != null) {
                        for (Workout.BoneInfo info : mNotchWorkout.getBones().values()) {
                            ColorPair colors = info.getColor();
                            sb.append(info.getBone().getName()).append(": ");
                            sb.append(colors.getPrimary().toString());
                            sb.append(colors.getPrimary().equals(colors.getSecondary()) ? "" : ", " + colors.getSecondary().toString());
                            sb.append("\n");
                        }
                    }

                    showAlertDialog("Notch Devices:", sb.toString());

                    mNotchService.configureSteady(MeasurementType.STEADY_SIMPLE, true, new EmptyNotchCallback<Void>() {
                        @Override
                        public void onSuccess(Void aVoid) {
                            // Do nothing, leave text visible
                            Log.d(TAG, "Successfully Configure Steady");
                            Toast.makeText(mActivity, "Successfully Configure Steady", Toast.LENGTH_LONG).show();
                            dismissAlertDialog();
                            showAlertDialog("Notch Devices:", sb.toString());
                        }

                        @Override
                        public void onFailure(@Nonnull NotchError notchError) {
                            Log.d(TAG, "Failed to Configure Steady\n" + notchError.getStatus());
                            Toast.makeText(mActivity, "Failed to Configure Steady\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                            dismissAlertDialog();
                        }
                    });
                }

                @Override
                public void onFailure(@Nonnull NotchError notchError) {
                    Log.d(TAG, "Failed to Initialized Notch\n" + notchError.getStatus());
                    Toast.makeText(mActivity, "Failed to Initialized Notch\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();

                    dismissAlertDialog();
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Error while loading skeleton file!", e);
            dismissAlertDialog();
            Toast.makeText(mActivity, "Error loading skeleton file\n" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    public void startSteady(View v){
        showAlertDialog("Performing Steady Calibration....", "Please wait");
        mNotchService.steady(new EmptyNotchCallback<Measurement>(){
            @Override
            public void onSuccess(Measurement measurement) {
                Log.d(TAG, "Successfully Completed Steady\nVerifying Data....!");
                Toast.makeText(mActivity, "Successfully complete Steady\nVerifying Data....!", Toast.LENGTH_LONG).show();
                dismissAlertDialog();
                showAlertDialog("Fetching Steady Data....", "Please wait");
                mNotchService.getSteadyData(new EmptyNotchCallback<Void>(){
                    @Override
                    public void onSuccess(@Nullable Void aVoid) {
                        Log.d(TAG, "Successfully Fetched Steady Data!");
                        Toast.makeText(mActivity, "Successfully Fetched Steady Data!", Toast.LENGTH_LONG).show();
                        dismissAlertDialog();
                    }

                    @Override
                    public void onFailure(@Nonnull NotchError notchError) {
                        Log.d(TAG, "Failed to Fetch Steady Data\n" + notchError.getStatus());
                        Toast.makeText(mActivity, "Failed to Fetch Steady Data\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                        dismissAlertDialog();
                    }
                });
            }

            @Override
            public void onFailure(@Nonnull NotchError notchError) {
                Log.d(TAG, "Failed to complete Steady\n" + notchError.getStatus());
                Toast.makeText(mActivity, "Failed to complete Steady\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                dismissAlertDialog();
            }
        });
    }
    public void startCapture(View v){
        Skeleton skeleton;
        try {
            skeleton = Skeleton.from(new InputStreamReader(getApplicationContext().getResources().openRawResource(R.raw.skeleton_male), "UTF-8"));
            Workout workout = Workout.from("Demo_config", skeleton, IOUtil.readAll(new InputStreamReader(getApplicationContext().getResources().openRawResource(R.raw.config_1_chest))));
            workout = workout.withRealTime(true);
            workout = workout.withMeasurementType(MeasurementType.STEADY_SKIP);

            mNotchWorkout = workout;
            showAlertDialog("Initializing Capture....", "Please wait");
            mNotchService.init(mNotchChannel, workout, new EmptyNotchCallback<NotchNetwork>() {
                @Override
                public void onSuccess(NotchNetwork notchNetwork) {
                    updateCurrentNetwork();
                    dismissAlertDialog();
                    showAlertDialog("Successfully Initialized Notch...!", "Configuring for real-time capture.");

                    mNotchService.configureCapture(false, new EmptyNotchCallback<Void>(){
                        @Override
                        public void onSuccess(Void aVoid) {
                            Log.d(TAG, "Successfully Configured for real-time capture");
                            showAlertDialog("Successfully Configured Notch....!", "Ready for Real-Time.");
                            configureForRealTimeCapture();
//                            dismissAlertDialog();
                        }

                        @Override
                        public void onFailure(@Nonnull NotchError notchError) {
                            Log.d(TAG, "Failed to Configured for real-time capture\n" + notchError.getStatus());
                            Toast.makeText(mActivity, "Failed to Configured for real-time capture\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                            dismissAlertDialog();
                        }
                    });

                }

                @Override
                public void onFailure(@Nonnull NotchError notchError) {
                    Log.d(TAG, "Failed to Initialized Notch for real-time capture\n" + notchError.getStatus());
                    Toast.makeText(mActivity, "Failed to Initialized Notch for real-time capture\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                    dismissAlertDialog();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error while loading skeleton file!", e);
            Toast.makeText(mActivity, "Error while loading skeleton file!\n" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    public void stopCapture(View v){
        showAlertDialog("Stopping Notch Recording", "Please wait");
        mNotchService.disconnect(new EmptyNotchCallback<Void>() {
            @Override
            public void onSuccess(Void aVoid) {
                super.onSuccess(aVoid);
                updateCurrentNetwork();
                Log.d(TAG, "Notch Stopped Capturing");
                Toast.makeText(mActivity, "Notch Stopped Capturing", Toast.LENGTH_LONG).show();
                dismissAlertDialog();
            }

            @Override
            public void onFailure(@Nonnull NotchError notchError) {
                Log.d(TAG, "Failed to Stop Notch: " + notchError.getStatus());
                Toast.makeText(mActivity, "Failed to Stop Notch\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                dismissAlertDialog();
            }
        });
    }
}
