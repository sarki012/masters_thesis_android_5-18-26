package com.esark.gasp;


import static com.esark.framework.AndroidGame.signalBufferLen;
import static com.esark.gasp.GameScreen.A2DVal;
import static com.esark.gasp.GameScreen.movingRMS;
import static com.esark.gasp.GameScreen.psdResult;

import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.SystemClock;

import com.esark.framework.AndroidGame;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static com.esark.gasp.GameScreen.smoothedRMS;

// Connected Thread handles Bluetooth communication
public class ConnectedThread extends Thread {
    private final BluetoothSocket mmSocket;
    private final InputStream mmInStream;
    private final OutputStream mmOutStream;
    private final Handler mHandler;



    public ConnectedThread(BluetoothSocket socket, Handler handler) {
        mmSocket = socket;
        mHandler = handler;
        InputStream tmpIn = null;
        OutputStream tmpOut = null;

        try {
            tmpIn = socket.getInputStream();
            tmpOut = socket.getOutputStream();
        } catch (IOException e) {
            e.printStackTrace();
        }

        mmInStream = tmpIn;
        mmOutStream = tmpOut;
    }

    @Override
    public void run() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DataInputStream dataIn = new DataInputStream(mmInStream);

        double fs = 2000;
        PowerSpectralDensityCalculator psdCalc = new PowerSpectralDensityCalculator(A2DVal, fs);

        while (true) {
            try {
                byte[] buffer = new byte[24];

                // This line throws IOException, which is now caught by the 'catch' block below
                dataIn.readFully(buffer);

                // Send raw data to the Handler for immediate UI updates
                mHandler.obtainMessage(AndroidGame.MESSAGE_READ, 24, -1, buffer).sendToTarget();

                // Fire the heavy math into the executor
                executor.execute(new Runnable() {
                    @Override
                    public void run() {

                        double[] tempResult = psdCalc.calculatePSD(A2DVal, fs);
                        if (tempResult != null && tempResult.length <= psdResult.length) {
                            System.arraycopy(tempResult, 0, psdResult, 0, tempResult.length);
                        }

                        for (int i = 0; i < psdResult.length; i++) {
                            psdResult[i] = psdResult[i] * -1 + 3600;
                            if (psdResult[i] < 3165) psdResult[i] = 3165;
                        }

                        // Static imports handle movingRMS and smoothedRMS
                        movingRMS = RMSCalculator.calculateMovingRMS(A2DVal, 10);
                        smoothedRMS = MovingAverageCalculator.calculateMovingAverage(movingRMS, 20);

                        /*
                        // Trigger UI Redraw via the Activity's View
                        if (AndroidGame.getGameView() != null) {
                            AndroidGame.getGameView().postInvalidate();
                        }


                         */
                    }
                }); // THIS CLOSES THE RUNNABLE AND EXECUTOR

            } catch (IOException e) {
                e.printStackTrace();
                break; // Exit loop if connection is lost
            }
        }
    }

    public void write(String input) {
        byte[] bytes = input.getBytes(); // converts entered String into bytes
        try {
            mmOutStream.write(bytes);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /* Call this from the main activity to shutdown the connection */
    public void cancel() {
        try {
            mmSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
