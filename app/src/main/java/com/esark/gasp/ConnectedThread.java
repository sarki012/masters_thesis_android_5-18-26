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
import static com.esark.gasp.GameScreen.writer;

// ... (keep your imports at the top)

public class ConnectedThread extends Thread {private final BluetoothSocket mmSocket;
    private final InputStream mmInStream;
    private final OutputStream mmOutStream;
    private final Handler mHandler;

    // FIX 1: Move count here so it can be accessed inside the Runnable
    private int count = 0;
    public static int finalSamples = 0;

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
        // FIX: Define buffer OUTSIDE the loop to stop memory/crash issues
        byte[] buffer = new byte[1024];
        double fs = 1000;
        PowerSpectralDensityCalculator psdCalc = new PowerSpectralDensityCalculator(A2DVal, fs);

        while (true) {
            try {
                // FIX: Removed the redundant 'try' that was causing the crash
                int bytesRead = mmInStream.read(buffer);

                if (bytesRead > 0) {
                    String msg = new String(buffer, 0, bytesRead);

                    // We need to know how many samples were in THIS string.
                    // Usually, you count the newlines '\n' in the string.
                    int samplesFound = msg.length() - msg.replace("\n", "a").length();
                    if (samplesFound == 0) samplesFound = 1; // Fallback

                    // We pass the sample count into the executor
                    finalSamples = samplesFound;

                    mHandler.obtainMessage(AndroidGame.MESSAGE_READ, bytesRead, -1, msg).sendToTarget();



                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            // --- MATH SECTION ---
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

                            // --- UI THROTTLING ---
                            count++;
                            if (count % 10 == 0) {
                                mHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (GameScreen.view != null) {
                                            GameScreen.view.invalidate();
                                        }
                                    }
                                });
                            }

                            // --- RECORDING LOGIC (FIXED) ---
                            if (GameScreen.isRecording && writer != null) {
                                // Record ONLY the new samples that just arrived
                                for (int i = signalBufferLen - finalSamples; i < signalBufferLen; i++) {
                                    if (i >= 0) {
                                        writer.println(A2DVal[i]);
                                    }
                                }
                                writer.flush();
                            }
                        }
                    });
                }
            } catch (IOException e) {
                e.printStackTrace();
                break;
            }
        }
    }
}