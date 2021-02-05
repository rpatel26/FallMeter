package com.example.bluetoothtesting;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.drawable.AnimationDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

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

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

public class NotchBackgroundService extends Service {
    private static final String TAG = NotchBackgroundService.class.getSimpleName();
    private static final String DEFAULT_USER_LICENSE = "Fam5ERAuAnQr18tR3Kpb";  // Extended License for Notch
    private static final long CALIBRATION_TIME = 7000L;
    private boolean captureFromAllSensor = true;    // Boolean to capture from all sensor vs 1 sensor
    private int config_file_1_sensor = R.raw.config_1_chest;
    private int config_file = R.raw.config_6_full_body;

    private CustomNotchService notchService;
    private ComponentName mNotchServiceComponent;
    private NotchService mNotchService;
    private final List<NotchServiceConnection> mNotchServiceConnections = new ArrayList<>();
    private NotchDataBase mNotchDB;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private NotchChannel mNotchChannel;
    private Workout mNotchWorkout;
    private VisualiserData mNotchRealTimeData;
    private Skeleton mNotchSkeleton;
    private Cancellable c;

    private BroadcastReceiver notchButtonListener = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Action to pair new device
            boolean canPairNewDevice = intent.getBooleanExtra(getApplicationContext().getPackageName() + "_NotchActionButton_PairNewDevice", false);
            if(canPairNewDevice){
                pairtNotchDevice();
            }

            // Action to remove all device
            boolean canRemoveAllDevice = intent.getBooleanExtra(getApplicationContext().getPackageName() + "_NotchActionButton_RemoveAllDevice", false);
            if(canRemoveAllDevice){
                removeAllDevice();
            }

            // Action to sync Notch devices
            boolean canSyncDevice = intent.getBooleanExtra(getApplicationContext().getPackageName() + "_NotchActionButton_SyncNotchPairing", false);
            if(canSyncDevice){
                syncNotchDevice();
            }

            // Action to connect to a network
            boolean canConnectToNetwork = intent.getBooleanExtra(getApplicationContext().getPackageName() + "_NotchActionButton_ConnectToNetwork", false);
            if(canConnectToNetwork){
                connectToNetwork();
            }

            // Action to configure calibration
            boolean canConfigureCalibation = intent.getBooleanExtra(getApplicationContext().getPackageName() + "_NotchActionButton_ConfigureCalibration", false);
            if(canConfigureCalibation){
                configureCalibration();
            }

            // Action to start calibration
            boolean canStartCalibration = intent.getBooleanExtra(getApplicationContext().getPackageName() + "_NotchActionButton_StartCalibration", false);
            if(canStartCalibration){
                startCalibration();
            }

            // Action to configure for steady calibration
            boolean canConfigureSteady = intent.getBooleanExtra(getApplicationContext().getPackageName() + "_NotchActionButton_ConfigureSteady", false);
            if(canConfigureSteady){
                configureSteady();
            }

            // Action to start steady calibration
            boolean canStartSteady = intent.getBooleanExtra(getApplicationContext().getPackageName() + "_NotchActionButton_StartSteady", false);
            if(canStartSteady){
                startSteady();
            }

            // Action to start real-time capture
            boolean canStartCapture = intent.getBooleanExtra(getApplicationContext().getPackageName() + "_NotchActionButton_StartCapture", false);
            if(canStartCapture){
                startRealTimeCapture();
            }

            // Action to stop real-time capture
            boolean canStopCapture = intent.getBooleanExtra(getApplicationContext().getPackageName() + "_NotchActionButton_StopCapture", false);
            if(canStopCapture){
                stopRealTimeCapture();
            }

            // Action to check battery level
            boolean canCheckBatteryLevel = intent.getBooleanExtra(getApplicationContext().getPackageName() + "_NotchActionButton_CheckBatteryLevel", false);
            if(canCheckBatteryLevel){
                checkNotchBatteryLevel();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        // Register receiver to capture any data transferred from Caretaker
        IntentFilter filter = new IntentFilter(getApplicationContext().getPackageName() + "_NotchActionButton");
        registerReceiver(notchButtonListener, filter);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Init Vars for Notch Devices
        notchService = new CustomNotchService();

        // Intent to start Notch Service
        Intent controlServiceIntent = new Intent(this, NotchAndroidService.class);
        startService(controlServiceIntent);
        bindService(controlServiceIntent, notchService, Context.BIND_AUTO_CREATE);

        mNotchDB = NotchDataBase.getInst();
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

        return super.onStartCommand(intent, flags, startId);
    }

    // Helper Methods
    private void updateDeviceList(final String user) {
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
        // notify MainActivity of update device list
        Intent notchDeviceListUpdateIntent = new Intent(getApplicationContext().getPackageName() + "_NotchService");
        notchDeviceListUpdateIntent.putExtra(getApplicationContext().getPackageName() + "_NotchService_DeviceListUpdate", sb.toString());
        sendBroadcast(notchDeviceListUpdateIntent);
    }
    private void updateCurrentNetwork(){
        StringBuilder sb = new StringBuilder();
        sb.append("Current Network:\n");
        if (mNotchService.getNetwork() != null) {
            for (ActionDevice device : mNotchService.getNetwork().getDeviceSet()) {
                sb.append(device.getNetworkId()).append(", ");
            }
        }
        Log.d(TAG, "UpdateCurrentNetwork: " + sb.toString());

        // notify MainActivity to update current network
        Intent notchNetworkUpdateIntent = new Intent(getApplicationContext().getPackageName() + "_NotchService");
        notchNetworkUpdateIntent.putExtra(getApplicationContext().getPackageName() + "_NotchService_NetworkUpdate", sb.toString());
        sendBroadcast(notchNetworkUpdateIntent);
    }
    private void updateNotchBatteryLevel(String newBatteryLevel){
        // notify MainActivity to update Battery Level
        Intent notchNetworkUpdateIntent = new Intent(getApplicationContext().getPackageName() + "_NotchService");
        notchNetworkUpdateIntent.putExtra(getApplicationContext().getPackageName() + "_NotchService_BatteryLevelUpdate", newBatteryLevel);
        sendBroadcast(notchNetworkUpdateIntent);
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
    private void configureForRealTimeCapture() {
        c = mNotchService.capture(new NotchCallback<Void>() {
            @Override
            public void onProgress(NotchProgress progress) {
                if (progress.getState() == NotchProgress.State.REALTIME_UPDATE) {
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
                Toast.makeText(getApplicationContext(), "Failed to Capture real-time data", Toast.LENGTH_LONG).show();
                dismissAlertDialog();
            }

            @Override
            public void onCancelled() {
                Log.d(TAG, "Real-time Measurement Stopped");
                Toast.makeText(getApplicationContext(), "Real-time Measurement Stopped", Toast.LENGTH_LONG).show();
                dismissAlertDialog();
            }
        });
    }
    private void calculateNotchAngle(int frameIndex) {
        new Thread(new Runnable() {
            @RequiresApi(api = Build.VERSION_CODES.O)
            public void run() {
                Bone chest = mNotchSkeleton.getBone("ChestBottom");
                Bone neck = mNotchSkeleton.getBone("Neck");
                Bone rightForeArm = mNotchSkeleton.getBone("RightForeArm");
                Bone leftForeArm = mNotchSkeleton.getBone("LeftForeArm");
                Bone rightTopFoot = mNotchSkeleton.getBone("RightFootTop");
                Bone leftTopFoot = mNotchSkeleton.getBone("LeftFootTop");

                List<Float> notchData = new ArrayList<>();

                notchData.addAll(readRawNotchData(chest, frameIndex));
                notchData.addAll(readRawNotchData(neck, frameIndex));
                notchData.addAll(readRawNotchData(rightForeArm, frameIndex));
                notchData.addAll(readRawNotchData(leftForeArm, frameIndex));
                notchData.addAll(readRawNotchData(rightTopFoot, frameIndex));
                notchData.addAll(readRawNotchData(leftTopFoot, frameIndex));

                float[] float_notch_data  = new float[notchData.size()];
                int index = 0;
                for(Float f : notchData) float_notch_data[index++] = f;
                Log.d(TAG, "RawNotchData: " + float_notch_data);

                // notify MainActivity to save Notch data
                Intent notchRealTimeDataCaptureIntent = new Intent(getApplicationContext().getPackageName() + "_NotchService");
                notchRealTimeDataCaptureIntent.putExtra(getApplicationContext().getPackageName() + "_NotchService_RealTimeDataUpdate", float_notch_data);
                sendBroadcast(notchRealTimeDataCaptureIntent);
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
        return  data;
    }

    // Action Methods
    private void pairtNotchDevice(){
        showAlertDialog("Pairing Notch....", "Please wait");
        Toast.makeText(getApplicationContext(), "Pairing Device......", Toast.LENGTH_LONG).show();
        mNotchService.pair(new EmptyNotchCallback<Device>(){
            @Override
            public void onSuccess(@javax.annotation.Nullable Device device) {
                Log.d(TAG, "NotchPair: Success!!");
                updateDeviceList(mNotchService.getLicense());
                dismissAlertDialog();
                Toast.makeText(getApplicationContext(), "Pairing Success!", Toast.LENGTH_LONG).show();
            }

            @Override
            public void onFailure(@Nonnull NotchError notchError) {
                Log.d(TAG, "NotchPair: Failed: " + notchError.getStatus());
                Toast.makeText(getApplicationContext(), "Failed to Pair Notch:\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                dismissAlertDialog();
            }
        });
    }
    private void syncNotchDevice(){
        showAlertDialog("Syncing Notch....", "Please wait");
        mNotchService.syncPairedDevices(new EmptyNotchCallback<Void>(){
            @Override
            public void onSuccess(@javax.annotation.Nullable Void aVoid) {
                Log.d(TAG, "NotchSyncPairing: Success!!");
                updateDeviceList(mNotchService.getLicense());
                Toast.makeText(getApplicationContext(), "Sync Pairing Success", Toast.LENGTH_LONG);
                dismissAlertDialog();
            }

            @Override
            public void onFailure(@Nonnull NotchError notchError) {
                Log.d(TAG, "NotchSyncPairing: Failure");
                Toast.makeText(getApplicationContext(), "Failed of Sync Pairing:\n" + notchError.getStatus(), Toast.LENGTH_LONG);
                dismissAlertDialog();
            }

        });
    }
    private void removeAllDevice(){
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
                        Toast.makeText(getApplicationContext(), "Removed All Notch" , Toast.LENGTH_LONG).show();
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
                Toast.makeText(getApplicationContext(), "Failed to Remove All Notch:\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                dismissAlertDialog();
            }
        });
    }
    private void connectToNetwork(){
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
                        Toast.makeText(getApplicationContext(), "ConnectToNetwork: Success", Toast.LENGTH_LONG).show();
                        dismissAlertDialog();
                    }

                    @Override
                    public void onFailure(@Nonnull NotchError notchError) {
                        Log.d(TAG, "ConnectToNetwork: Failure: " + notchError.getStatus());
                        Toast.makeText(getApplicationContext(), "ConnectToNetwork: Failure\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                        dismissAlertDialog();
                    }
                });
            }

            @Override
            public void onFailure(@Nonnull NotchError notchError) {
                Log.d(TAG, "Disconnect: Failure: " + notchError.getStatus());
                Toast.makeText(getApplicationContext(), "Disconnect: Failure\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                dismissAlertDialog();
            }
        });
    }
    private void configureCalibration(){
        showAlertDialog("Configuring Calibration....", "Please wait");
        if(getChannel(mNotchService.getLicense()) != '1'){
            mNotchChannel = NotchChannel.fromChar(getChannel(mNotchService.getLicense()));
        }

        mNotchService.uncheckedInit(mNotchChannel, new EmptyNotchCallback<NotchNetwork>() {
            @Override
            public void onSuccess(NotchNetwork notchNetwork) {
                updateCurrentNetwork();
                Toast.makeText(getApplicationContext(), "Success UncheckedInit!\nConfiguring Calibration....", Toast.LENGTH_LONG).show();
                mNotchService.configureCalibration(true, new EmptyNotchCallback<Void>(){
                    @Override
                    public void onSuccess(Void aVoid) {
                        Toast.makeText(getApplicationContext(), "Success Configure Calibration!\nReady to start Calibration", Toast.LENGTH_LONG).show();
                        dismissAlertDialog();
                    }

                    @Override
                    public void onFailure(@Nonnull NotchError notchError) {
                        Toast.makeText(getApplicationContext(), "Failed Configure Calibration:\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                        dismissAlertDialog();
                    }
                });
            }

            @Override
            public void onFailure(@Nonnull NotchError notchError) {
                Toast.makeText(getApplicationContext(), "Failed UncheckedInit:\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                dismissAlertDialog();
            }
        });
    }
    private void startCalibration(){
        Toast.makeText(getApplicationContext(), "Starting Calibration.....", Toast.LENGTH_LONG).show();
        mNotchService.calibration(new EmptyNotchCallback<Measurement>());
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                // notify MainActivity to enable docker image
                Intent notchDockerEnableIntent = new Intent(getApplicationContext().getPackageName() + "_NotchService");
                notchDockerEnableIntent.putExtra(getApplicationContext().getPackageName() + "_NotchService_EnableDocker", true);
                sendBroadcast(notchDockerEnableIntent);
            }
        });
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // notify MainActivity to disable docker image
                Intent notchDockerDisableIntent = new Intent(getApplicationContext().getPackageName() + "_NotchService");
                notchDockerDisableIntent.putExtra(getApplicationContext().getPackageName() + "_NotchService_EnableDocker", false);
                sendBroadcast(notchDockerDisableIntent);

                showAlertDialog("Fetching Calibration Result....", "Please wait");
                mNotchService.getCalibrationData(new EmptyNotchCallback<Boolean>(){
                    @Override
                    public void onSuccess(Boolean aBoolean) {
                        Log.d(TAG, "Successfully Completed Calibration");
                        Toast.makeText(getApplicationContext(), "Successfully Completed Calibration", Toast.LENGTH_LONG).show();
                        dismissAlertDialog();
                    }

                    @Override
                    public void onFailure(@Nonnull NotchError notchError) {
                        Log.d(TAG, "Failed Getting Calibration Data:\n" + notchError.getStatus());
                        Toast.makeText(getApplicationContext(), "Failed Getting Calibration Data:\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                        dismissAlertDialog();
                    }
                });
            }
        }, CALIBRATION_TIME);
    }
    private void configureSteady(){
        Skeleton skeleton;
        try {
            skeleton = Skeleton.from(new InputStreamReader(getApplicationContext().getResources().openRawResource(R.raw.skeleton_male), StandardCharsets.UTF_8));
            Workout workout;
            if(captureFromAllSensor){

                workout = Workout.from("Demo_config", skeleton, IOUtil.readAll(new InputStreamReader(getApplicationContext().getResources().openRawResource(config_file))));
            }
            else{
                workout = Workout.from("Demo_config", skeleton, IOUtil.readAll(new InputStreamReader(getApplicationContext().getResources().openRawResource(config_file_1_sensor))));
            }

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
                            Toast.makeText(getApplicationContext(), "Successfully Configure Steady", Toast.LENGTH_LONG).show();
                            dismissAlertDialog();
                            showAlertDialog("Notch Devices:", sb.toString());
                        }

                        @Override
                        public void onFailure(@Nonnull NotchError notchError) {
                            Log.d(TAG, "Failed to Configure Steady\n" + notchError.getStatus());
                            Toast.makeText(getApplicationContext(), "Failed to Configure Steady\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                            dismissAlertDialog();
                        }
                    });
                }

                @Override
                public void onFailure(@Nonnull NotchError notchError) {
                    Log.d(TAG, "Failed to Initialized Notch\n" + notchError.getStatus());
                    Toast.makeText(getApplicationContext(), "Failed to Initialized Notch\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();

                    dismissAlertDialog();
                }
            });

        } catch (Exception e) {
            Log.e(TAG, "Error while loading skeleton file: " + e.getMessage());
            dismissAlertDialog();
            Toast.makeText(getApplicationContext(), "Error loading skeleton file\n" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    private void startSteady(){
        showAlertDialog("Performing Steady Calibration....", "Please wait");
        mNotchService.steady(new EmptyNotchCallback<Measurement>(){
            @Override
            public void onSuccess(Measurement measurement) {
                Log.d(TAG, "Successfully Completed Steady\nVerifying Data....!");
                Toast.makeText(getApplicationContext(), "Successfully complete Steady\nVerifying Data....!", Toast.LENGTH_LONG).show();
                dismissAlertDialog();
                showAlertDialog("Fetching Steady Data....", "Please wait");
                mNotchService.getSteadyData(new EmptyNotchCallback<Void>(){
                    @Override
                    public void onSuccess(@javax.annotation.Nullable Void aVoid) {
                        Log.d(TAG, "Successfully Fetched Steady Data!");
                        Toast.makeText(getApplicationContext(), "Successfully Fetched Steady Data!", Toast.LENGTH_LONG).show();
                        dismissAlertDialog();
                    }

                    @Override
                    public void onFailure(@Nonnull NotchError notchError) {
                        Log.d(TAG, "Failed to Fetch Steady Data\n" + notchError.getStatus());
                        Toast.makeText(getApplicationContext(), "Failed to Fetch Steady Data\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                        dismissAlertDialog();
                    }
                });
            }

            @Override
            public void onFailure(@Nonnull NotchError notchError) {
                Log.d(TAG, "Failed to complete Steady\n" + notchError.getStatus());
                Toast.makeText(getApplicationContext(), "Failed to complete Steady\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                dismissAlertDialog();
            }
        });
    }
    private void startRealTimeCapture(){
        Skeleton skeleton;
        try {
            skeleton = Skeleton.from(new InputStreamReader(getApplicationContext().getResources().openRawResource(R.raw.skeleton_male), StandardCharsets.UTF_8));
            Workout workout;
            if(captureFromAllSensor){
                workout = Workout.from("Demo_config", skeleton, IOUtil.readAll(new InputStreamReader(getApplicationContext().getResources().openRawResource(config_file))));
            }
            else{
                workout = Workout.from("Demo_config", skeleton, IOUtil.readAll(new InputStreamReader(getApplicationContext().getResources().openRawResource(config_file_1_sensor))));
            }

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
                            Toast.makeText(getApplicationContext(), "Failed to Configured for real-time capture\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                            dismissAlertDialog();
                        }
                    });

                }

                @Override
                public void onFailure(@Nonnull NotchError notchError) {
                    Log.d(TAG, "Failed to Initialized Notch for real-time capture\n" + notchError.getStatus());
                    Toast.makeText(getApplicationContext(), "Failed to Initialized Notch for real-time capture\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                    dismissAlertDialog();
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Error while loading skeleton file!", e);
            Toast.makeText(getApplicationContext(), "Error while loading skeleton file!\n" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    private void stopRealTimeCapture(){
        showAlertDialog("Stopping Notch Recording", "Please wait");
        mNotchService.disconnect(new EmptyNotchCallback<Void>() {
            @Override
            public void onSuccess(Void aVoid) {
                super.onSuccess(aVoid);
                updateCurrentNetwork();
                Log.d(TAG, "Notch Stopped Capturing");
                Toast.makeText(getApplicationContext(), "Notch Stopped Capturing", Toast.LENGTH_LONG).show();
                dismissAlertDialog();
            }

            @Override
            public void onFailure(@Nonnull NotchError notchError) {
                Log.d(TAG, "Failed to Stop Notch: " + notchError.getStatus());
                Toast.makeText(getApplicationContext(), "Failed to Stop Notch\n" + notchError.getStatus(), Toast.LENGTH_LONG).show();
                dismissAlertDialog();
            }
        });
    }
    private void checkNotchBatteryLevel(){
        showAlertDialog("Checking battery level....", "Please wait");
        mNotchService.checkBatteryStatus(new EmptyNotchCallback<Void>(){
            @Override
            public void onSuccess(@javax.annotation.Nullable Void aVoid) {
                super.onSuccess(aVoid);
                List<Device> notchDevicesList = mNotchService.findAllDevices();
                StringBuilder sb = new StringBuilder();
                sb.append("Battery Level:\n");
                for(Device d: notchDevicesList){
                    Log.d(TAG, "NOTCH Battery Level: " + d.getBatteryPercent() + "\t for device " + d.getNotchDevice().getDeviceMac());
                    sb.append(d.getNotchDevice().getDeviceMac() + ": " + d.getBatteryPercent() + "%\n");
                }
                sb.append("\n\n\n\n");
                updateNotchBatteryLevel(sb.toString());
                dismissAlertDialog();
            }

            @Override
            public void onFailure(@Nonnull NotchError notchError) {
                Log.d(TAG, "Failed to read Notch bettery level");
                Toast.makeText(getApplicationContext(), "Failed of read battery level:\n" + notchError.getStatus(), Toast.LENGTH_LONG);
                dismissAlertDialog();
            }
        });
    }

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
    public class EmptyNotchCallback<T> implements NotchCallback<T> {

        @Override
        public void onProgress(@Nonnull NotchProgress notchProgress) {

        }

        @Override
        public void onSuccess(@javax.annotation.Nullable T t) {

        }

        @Override
        public void onFailure(@Nonnull NotchError notchError) {

        }

        @Override
        public void onCancelled() {

        }
    }
    private void showAlertDialog(String title, String message){
        // notify MainActivity to show alert
        Intent notchShowAlertIntent = new Intent(getApplicationContext().getPackageName() + "_NotchService");
        notchShowAlertIntent.putExtra(getApplicationContext().getPackageName() + "_NotchService_AlertTitle", title);
        notchShowAlertIntent.putExtra(getApplicationContext().getPackageName() + "_NotchService_AlertMessage", message);
        sendBroadcast(notchShowAlertIntent);
    }
    private void dismissAlertDialog(){
        // notify MainActivity to disable alert
        Intent notchDismissAlertIntent = new Intent(getApplicationContext().getPackageName() + "_NotchService");
        notchDismissAlertIntent.putExtra(getApplicationContext().getPackageName() + "_NotchService_AlertDismiss", true);
        sendBroadcast(notchDismissAlertIntent);
    }
}
