package com.fewlaps.flone.service;

import android.content.Intent;
import android.os.Handler;
import android.util.Log;

import com.fewlaps.flone.data.KnownDronesDatabase;
import com.fewlaps.flone.data.bean.Drone;
import com.fewlaps.flone.io.bean.DroneConnectionStatusChanged;
import com.fewlaps.flone.io.bean.DroneSensorData;
import com.fewlaps.flone.io.communication.Bluetooth;
import com.fewlaps.flone.io.communication.Communication;
import com.fewlaps.flone.io.communication.RCSignals;
import com.fewlaps.flone.io.communication.protocol.MultiWii230;
import com.fewlaps.flone.io.communication.protocol.MultirotorData;
import com.fewlaps.flone.io.input.UserInstructionsInput;
import com.fewlaps.flone.io.input.phone.PhoneInput;
import com.fewlaps.flone.io.input.phone.PhoneOutputData;
import com.fewlaps.flone.util.NotificationUtil;

import de.greenrobot.event.EventBus;

/**
 * This Sercive is the responsable of maintaining a connection with the Drone, asking for data, and sending data
 *
 * @author Roc Boronat (roc@fewlaps.com)
 * @date 15/02/2015
 */
public class DroneService extends BaseService {
    public static String ACTION_CONNECT = "connect";
    public static String ACTION_DISCONNECT = "disconnect";

    private static final int BAUD_RATE = 115200; //The baud rate where the BT works

    private static final int DELAY_RECONNECT = 2000; //The time the reconnect task will wait between launches
    private static final int COMMAND_TIMEOUT = 1000; //The time we consider that was "too time ago for being connected"

    public Communication communication;
    public MultirotorData protocol;

    public boolean running = false;

    private Handler connectTask = new Handler();

    private long lastDroneAnswerReceived = 0;

    private UserInstructionsInput userInput;
    private DroneSensorData droneInput;

    private PhoneOutputData phoneOutputData = new PhoneOutputData();

    public static final RCSignals rc = new RCSignals(); //Created at startup, never changed, never destroyed, totally reused at every request

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        communication = new Bluetooth(getApplicationContext());
        protocol = new MultiWii230(communication);

        startForeground(NotificationUtil.KEY_FOREGROUND_NOTIFICATION, NotificationUtil.getForegroundServiceNotification(this));

        onEventMainThread(ACTION_CONNECT);

        if (userInput == null) { //Only start once
            userInput = new PhoneInput(this);
        }

        return START_NOT_STICKY;
    }

    public void onEventMainThread(String action) {
        Log.d("SERVICE", "Action received: " + action);

        if (action.equals(ACTION_CONNECT)) {
            running = true;
            reconnectRunnable.run();
        } else if (action.equals(ACTION_DISCONNECT)) {
            communication.Close();
            running = false;
            stopForeground(true);
            stopSelf();
        }
    }

    public void onEventMainThread(DroneConnectionStatusChanged status) {
        if (status.isConnected()) {
            protocol.SendRequestMSP_ATTITUDE();
        }
    }

    /**
     * Sets the RC data, using the userInput and the droneInput. It's a common task
     * to do before sending the RC to the drone, to make it fly as the user excepts
     */
    private void updateRCWithInputData() {
        int yaw = (int) (droneInput.getHeading() - userInput.getHeading());
        int pitch = (int) (userInput.getPitch());
        int roll = (int) userInput.getRoll();

        rc.setThrottle(userInput.getThrottle());
        rc.setAdjustedYaw(yaw);
        rc.setPitch(pitch);
        rc.setRoll(roll);

        phoneOutputData.update(yaw, pitch, roll);
        EventBus.getDefault().post(phoneOutputData);
    }

    public void onEventMainThread(DroneSensorData sensorInformation) {
        droneInput = sensorInformation;

        protocol.SendRequestMSP_ATTITUDE();
        lastDroneAnswerReceived = System.currentTimeMillis();

        updateRCWithInputData();
        protocol.SendRequestMSP_SET_RAW_RC(rc.get());
    }

    private final Runnable reconnectRunnable = new Runnable() {
        public void run() {
            if (running) {
                if (!communication.Connected) {
                    Drone selectedDrone = KnownDronesDatabase.getSelectedDrone(DroneService.this);
                    if (selectedDrone != null) {
                        protocol.Connect(selectedDrone.address, BAUD_RATE, 0);
                    }
                } else {
                    if (lastDroneAnswerReceived < System.currentTimeMillis() - COMMAND_TIMEOUT) {
                        protocol.SendRequestMSP_ATTITUDE(); //Requesting the attitude, in order to make the connection fail
                    }
                }
                connectTask.postDelayed(reconnectRunnable, DELAY_RECONNECT);
            }
        }
    };
}
