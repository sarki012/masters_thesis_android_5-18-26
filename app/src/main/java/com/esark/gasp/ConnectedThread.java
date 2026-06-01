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


                    // Inside the run() method, inside the while(true) loop:
                    if (bytesRead > 0) {
                        // We already defined 'msg' above, let's use it.
                        // No need for a second 'if (bytesRead > 0)' here.

                        executor.execute(new Runnable() {
                            @Override
                            public void run() {
                                // 1. Add new data to the persistent accumulator
                                dataAccumulator.append(msg);

                                // 2. Process all complete "aXXXXXa" packets in the buffer
                                int firstA;
                                while ((firstA = dataAccumulator.indexOf("a")) != -1) {
                                    int nextA = dataAccumulator.indexOf("a", firstA + 1);

                                    if (nextA != -1) {
                                        // We found a complete segment between two 'a's
                                        String sampleStr = dataAccumulator.substring(firstA + 1, nextA);

                                        if (sampleStr.length() >= 5) {
                                            try {
                                                // Extract digits and parse
                                                int val = Integer.parseInt(sampleStr.substring(0, 5));

                                                // 3. Update the shared A2DVal array immediately
                                                if (GameScreen.A2DVal != null) {
                                                    System.arraycopy(GameScreen.A2DVal, 1, GameScreen.A2DVal, 0, AndroidGame.signalBufferLen - 1);
                                                    // Applying your specific logic (val / 3.0)
                                                    GameScreen.A2DVal[AndroidGame.signalBufferLen - 1] = (double) (val / 3.0);
                                                }

                                                // Recording logic
                                                if (GameScreen.isRecording && GameScreen.writer != null) {
                                                    GameScreen.writer.println(val / 3.0);
                                                }

                                            } catch (NumberFormatException e) {
                                                // Skip garbled data
                                            }
                                        }
                                        // Delete the processed segment (up to the second 'a')
                                        dataAccumulator.delete(0, nextA);
                                    } else {
                                        // Fragmented data: wait for more Bluetooth data.
                                        break;
                                    }
                                }

                                // 4. Heavy Math (Moved INSIDE the executor to stop UI lag)
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

                                // 5. Trigger Redraw every 10 samples
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
                            }
                        });
                    }
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
            } catch (IOException e) {
                e.printStackTrace();
                break;
            }
        }
    }
}