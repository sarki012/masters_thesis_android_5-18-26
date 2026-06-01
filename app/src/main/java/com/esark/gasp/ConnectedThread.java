package com.esark.gasp;


import static com.esark.framework.AndroidGame.signalBufferLen;
import static com.esark.gasp.GameScreen.A2DVal;
import static com.esark.gasp.GameScreen.movingRMS;
import static com.esark.gasp.GameScreen.psdResult;

import android.bluetooth.BluetoothSocket;
import android.os.Handler;

import com.esark.framework.AndroidGame;

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
    private StringBuilder dataAccumulator = new StringBuilder();

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
                int bytesRead = mmInStream.read(buffer);

                if (bytesRead > 0) {
                    // Create a local final string for the background thread to use
                    final String msg = new String(buffer, 0, bytesRead);

                    // 1. Send the raw string to the handler for status text ONLY if needed
                    mHandler.obtainMessage(AndroidGame.MESSAGE_READ, bytesRead, -1, msg).sendToTarget();

                    // 2. Offload EVERYTHING else to the background thread
                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            // ADD TO ACCUMULATOR
                            dataAccumulator.append(msg);
                            // 1. Collect all waiting samples into a temporary list first
                            java.util.List<Double> newSamples = new java.util.ArrayList<>();
                            int firstA;
                            while ((firstA = dataAccumulator.indexOf("a")) != -1) {
                                int nextA = dataAccumulator.indexOf("a", firstA + 1);
                                if (nextA != -1) {
                                    String sampleStr = dataAccumulator.substring(firstA + 1, nextA);
                                    if (sampleStr.length() >= 5) {
                                        try {
                                            int val = Integer.parseInt(sampleStr.substring(0, 5));
                                            newSamples.add(val / 3.0);
                                        } catch (NumberFormatException e) {
                                        }
                                    }
                                    dataAccumulator.delete(0, nextA);
                                } else {
                                    break;
                                }
                            }

                            // 2. Shift the main array ONCE for the whole block
                            int numNew = newSamples.size();
                            if (numNew > 0 && GameScreen.A2DVal != null) {
                                int len = AndroidGame.signalBufferLen;

                                // Move existing data left by 'numNew' spaces
                                System.arraycopy(GameScreen.A2DVal, numNew, GameScreen.A2DVal, 0, len - numNew);

                                // Copy the new block of samples into the end of the array
                                for (int i = 0; i < numNew; i++) {
                                    double val = newSamples.get(i);
                                    GameScreen.A2DVal[len - numNew + i] = val;

                                    // Record to file
                                    if (GameScreen.isRecording && GameScreen.writer != null) {
                                        GameScreen.writer.println(val);
                                    }
                                }
                                // 3. Trigger Redraw - No need for modulo 'count' anymore
                                // because we are now naturally updating in "packets"
                                mHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        if (GameScreen.view != null) {
                                            GameScreen.view.invalidate();
                                        }
                                    }
                                });
                                if (numNew > 0) {
                                    // 3. HEAVY MATH (ONLY DO THIS ONCE IN THE BACKGROUND)
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
                                }
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