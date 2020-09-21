package com.example.bluetoothtesting;

import android.content.Intent;
import android.util.Log;

import com.caretakermedical.ble.CareTakerService;
import com.caretakermedical.ct.LibCT;

public class BloodPressureData extends CareTakerService {

    static final String TAG = "BloodPressureData";

    @Override
    public void onCreate() {
        super.onCreate();
    }


    @Override
    public int onStartCommand (Intent intent, int flags, int startId)
    {
        getBinder().connectToAny();
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }


    /**
     * Notification indicating the connection was established to the device.
     * Note this method runs on the UI thread to allow updating UI status.
     */
    @Override
    public void onConnected() {
        Log.d(TAG, "Connected to Caretaker: name='" + getBinder().getDevice().getName() + "'");

        Intent connectedIntent = new Intent(getApplicationContext().getPackageName() + "_BloodPressure");
        connectedIntent.putExtra(getApplicationContext().getPackageName() + "_BloodPressure_ConnectionStatus", "Connected");
        sendBroadcast(connectedIntent);

        // setup to monitor simulated data
        // CAUTION: The device sends fake data when simulatiom is enabled to demonstrate functionality.
        getBinder().getDevice().writeSimulationMode(true);

        // start manual calibration with default bp settings: sys=120, dia=75
//        getBinder().startManualCal(new LibCT.BPSettings());

        // 1: Sitting
        // 2: reclining
        // 3: Supine
        getBinder().startAutoCal((short) 1);
    }

    /**
     * Notification indicating the connection was disconnected.
     * Note this method runs on the UI thread to allow updating UI status.
     */
    @Override
    public void onDisconnected() {
        Log.d(TAG, "Disconnected from Caretaker");
        Intent connectedIntent = new Intent(getApplicationContext().getPackageName() + "_BloodPressure");
        connectedIntent.putExtra(getApplicationContext().getPackageName() + "_BloodPressure_ConnectionStatus", "Disconnected");
        sendBroadcast(connectedIntent);
    }

    /**
     * Notification indicating the connection timed out.
     * Note this method runs on the UI thread to allow updating UI status.
     */
    @Override
    public void onConnectionTimeout() {
        Log.d(TAG, "Caretaker connection timed out");
    }

    /**
     * Notification indicating a connection error.
     * Note this method runs on the UI thread to allow updating UI status.
     */
    @Override
    public void onConnectionError() {
        Log.d(TAG, "Caretaker connection error");
    }

    /**
     * Notification signalling an error was encountered while enabling data
     * streaming.
     *
     * @return true to retry the failed stream operation after this callback
     *         method returns, and false to not retry. The default behavior
     *         is to retry.
     */
    @Override
    public boolean onStreamError() {
        return true;
    }

    /**
     * Notification signalling an error was encountered while reading data
     * from the remote device.
     *
     * @return true to retry the failed read operation after this callback
     *         method returns, and false to not retry. The default behavior
     *         is to retry.
     */
    @Override
    public boolean onReadError() {
        return true;
    }

    /**
     * Notification signalling an error was encountered while writing data
     * to the remote device.
     *
     * @return true to retry the failed write operation after this callback
     *         method returns, and false to not retry. The default behavior
     *         is to retry.
     */
    @Override
    public boolean onWriteError() {
        return true;
    }

    /**
     * Notification signalling timeout expired while reading data from the
     * remote device.
     *
     * @return true to retry the timed out read operation after this callback
     *         method returns, and false to not retry. The default behavior
     *         is to retry.
     */
    @Override
    public boolean onReadTimeout() {
        return true;
    }

    /**
     * Notification signalling timeout expired while writing data to the
     * remote device.
     *
     * @return true to retry the timed out write operation after this callback
     *         method returns, and false to not retry. The default behavior is
     *         to retry.
     */
    @Override
    public boolean onWriteTimeout() {
        return true;
    }

    /**
     * Notification sent in response to calling {@link CareTaker#startStreaming()}.
     * The notification is signalled when stream data is received from the device.
     *
     * @param stream The updated stream data received from the device.
     *               The CareTaker is capable of reporting temperature and pulse
     *               oximetry data, but these should be ignored when the app is
     *               also monitoring health thermometer and pulse oximeter sensors
     *               along with the CareTaker device even if the temperature and
     *               oximetry data valid flags indicate otherwise.
     */
    @Override
    public void onStreamData(LibCT.StreamData stream) {
        if ( stream.vitalsData != null ) {
            int last = stream.vitalsData.length-1;
            LibCT.Vitals vitals = stream.vitalsData[last];
            Log.d(TAG, String.format("Caretaker vitals data: sys=%d, dia=%d, map=%d, hr=%d, resp=%d",
                    vitals.systolic,
                    vitals.diastolic,
                    vitals.map,
                    vitals.heartRate,
                    vitals.respiration));


            Intent connectedIntent = new Intent(getApplicationContext().getPackageName() + "_BloodPressure");
            connectedIntent.putExtra(getApplicationContext().getPackageName() + "_BloodPressure_Systolic", vitals.systolic);
            connectedIntent.putExtra(getApplicationContext().getPackageName() + "_BloodPressure_Diastolic", vitals.diastolic);
            connectedIntent.putExtra(getApplicationContext().getPackageName() + "_BloodPressure_MAP", vitals.map);
            connectedIntent.putExtra(getApplicationContext().getPackageName() + "_BloodPressure_HR", vitals.heartRate);
            connectedIntent.putExtra(getApplicationContext().getPackageName() + "_BloodPressure_RESP", vitals.respiration);
            sendBroadcast(connectedIntent);
        }

        if ( stream.intPulseWaveform != null ) {
            Log.d(TAG, "Got pulse pressure waveform data");
        }

        if ( stream.rawPulseWaveform != null ) {
            Log.d(TAG, "Got raw pulse waveform data");
        }

        if ( stream.cuffPressure != null ) {
            Log.d(TAG, "Got cuff status");
        }

        if ( stream.deviceStatus != null ) {
            Log.d(TAG, "Got device status");
        }

        if ( stream.batteryInfo != null ) {
            Log.d(TAG, "Got battery status: " + stream.batteryInfo.getPercentage());
            Intent connectedIntent = new Intent(getApplicationContext().getPackageName() + "_BloodPressure");
            connectedIntent.putExtra(getApplicationContext().getPackageName() + "_BloodPressure_BatteryInfo", stream.batteryInfo.getPercentage());
            sendBroadcast(connectedIntent);
        }

    }

    /**
     * Notification sent in response to calling {@link CareTaker#readSerialNumber()}
     * and also automatically after the connection is established with the caretaker.
     *
     * @param sn The device serial number.
     */
    @Override
    public void onReadSerialNumber(String sn) {
        Log.d(TAG, "Caretaker serial number=" + sn);
    }

    /**
     * Notification sent in response to calling {@link CareTaker#readVersion()}
     * and also automatically after the connection is established with the caretaker.
     *
     * @param version The device version information.
     */
    @Override
    public void onReadVersion(LibCT.VersionInfo version) {
        Log.d(TAG, String.format("Caretaker software version=%d.%d.%d.%d",
                version.firmwareVersion.major,
                version.firmwareVersion.minor,
                version.firmwareVersion.revision,
                version.firmwareVersion.build));
    }

    /**
     * Notification sent in response to calling {@link CareTaker#readUpdateInterval()}.
     *
     * @param ui The device update interval.
     */
    @Override
    public void onReadUpdateInterval(int ui) {
    }

    /**
     * Notification sent in response to calling {@link CareTaker#readCuffPressureSettings()}.
     *
     * @param params The device Cuff Pressure Settings parameters.
     */
    @Override
    public void onReadCuffPressureSettings(LibCT.CuffPressureSettings params) {
    }

    /**
     * Notification sent in response to calling {@link CareTaker#readPressureControl()}.
     *
     * @param params The device pressure control parameters.
     */
    @Override
    public void onReadPressureControl(LibCT.PressureControl params) {
    }

    /**
     * Notification sent in response to calling {@link CareTaker#readBleStreamControl()}.
     *
     * @param params The device stream control parameters.
     */
    @Override
    public void onReadBleStreamControl(LibCT.StreamControl params) {
    }

    /**
     * Notification sent in response to calling {@link CareTaker#readSimulationMode()}.
     *
     * @param mode True if simulation is enabled, false otherwise.
     */
    @Override
    public void onReadSimulationMode(boolean mode) {
    }

    /**
     * Notification sent in response to calling {@link CareTaker#readDisplayState()}.
     *
     * @param state The display state read from the device.
     *              zero : OFF
     *              non-zero : ON
     */
    @Override
    public void onReadDisplayState(byte state) {
    }

    /**
     * Notification sent in response to calling {@link CareTaker#readWaveformClamping()}.
     *
     * @param state The waveform clamping state read from the device.
     *              zero : OFF
     *              non-zero : ON
     */
    @Override
    public void onReadWaveformClamping(byte state) {
    }

    /**
     * Notification sent in response to calling {@link CareTaker#readMedianFilter()} ()}.
     *
     * @param state The waveform clamping state read from the device.
     *              zero : OFF
     *              non-zero : ON
     */
    @Override
    public void onReadMedianFilter(byte state) {
    }

    /**
     * Notification sent in response to calling {@link CareTaker#readCuffFitCheck()} ()}.
     *
     * @param enable The cuff fit checking state read from the device.
     *              zero : OFF
     *              non-zero : ON
     */
    @Override
    public void onReadCuffFitCheck(byte enable) {
    }

    /**
     * Notification sent in response to calling {@link CareTaker#readDisablePDAStop()} ()}.
     *
     * @param enable The disable pda stop state read from the device.
     *              zero : OFF
     *              non-zero : ON
     */
    @Override
    public void onReadDisablePDAStop(byte enable) {
    }
    /**
     * Notification sent in response to calling {@link CareTaker#readRecalibrationInterval()}.
     *
     * @param itvl The calibration interval read from the device.
     */
    @Override
    public void onReadRecalibrationInterval(int itvl) {
    }

    /**
     * Notification sent in response to calling {@link CareTaker#readSNRMinimum()}.
     *
     * @param value The noise filter value read from the device.
     */
    @Override
    public void onReadSNRMinimum(int value) {
    }

    /**
     * Notification sent in response to calling {@link CareTaker#readMotionTimeout()}.
     *
     * @param timeout The motion tolerance window read from the device.
     */
    @Override
    public void onReadMotionTimeout(int timeout) {
    }

    /**
     * Notification sent in response to calling {@link CareTaker#readPosture()}.
     *
     * @param value The posture value read from the device.
     */
    @Override
    public void onReadPosture(short value) {
    }

    /**
     * Notification sent in response to calling {@link CareTaker#readPosture()}.
     *
     * @param value The posture value read from the device.
     */
    @Override
    public void onReadPersistentLog(byte[] value) {
    }

    /**
     * Notification sent in response to calling {@link CareTaker#readPulseWaveform()}.
     *
     * @param wf The waveform.
     */
    @Override
    public void onReadPulseWaveform(LibCT.PulseWaveform wf) {
    }
}
