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

// ... (keep your imports at the top)

public class ConnectedThread extends Thread {private final BluetoothSocket mmSocket;
    private final InputStream mmInStream;
    private final OutputStream mmOutStream;
    private final Handler mHandler;

    // FIX 1: Move count here so it can be accessed inside the Runnable
    private int count = 0;

    public ConnectedThread(BluetoothSocket socket, Handler handler) {
        mmSocket = socket;
        mHandler = handler;
        InputStream tmpIn = null;
        OutputStream tmpOut = null;

        // Get the input and output streams; using temp objects because
        // member streams are final.
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

        // REMOVED 'int count = 0' from here

        while (true) {
            try {
                byte[] buffer = new byte[24];
                dataIn.readFully(buffer);

                mHandler.obtainMessage(AndroidGame.MESSAGE_READ, 24, -1, buffer).sendToTarget();

                executor.execute(new Runnable() {
                    @Override
                    public void run() {
                        // ... (Keep your PSD and RMS math here) ...
                        double[] tempResult = psdCalc.calculatePSD(A2DVal, fs);
                        if (tempResult != null && tempResult.length <= psdResult.length) {
                            System.arraycopy(tempResult, 0, psdResult, 0, tempResult.length);
                        }
                        for (int i = 0; i < psdResult.length; i++) {
                            psdResult[i] = psdResult[i] * -1 + 3600;
                            if (psdResult[i] < 3165) psdResult[i] = 3165;
                        }
                        movingRMS = RMSCalculator.calculateMovingRMS(A2DVal, 10);
                        smoothedRMS = MovingAverageCalculator.calculateMovingAverage(movingRMS, 20);

                        // FIX 2: Corrected Count and Redraw Logic
                        count++;
                        if (count % 10 == 0) {
                            // We use the mHandler to "post" a redraw request to the UI thread
                            mHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    // This checks if the view exists and refreshes it
                                    if (GameScreen.view != null) {
                                        GameScreen.view.invalidate();
                                    }
                                }
                            });
                        }
                    }
                });

            } catch (IOException e) {
                e.printStackTrace();
                break;
            }
        }
    }
    // ... (keep write and cancel methods)
}