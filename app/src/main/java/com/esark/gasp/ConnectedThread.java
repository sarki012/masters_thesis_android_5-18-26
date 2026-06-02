package com.esark.gasp;

import static com.esark.framework.AndroidGame.signalBufferLen;
import static com.esark.gasp.GameScreen.A2DVal;
import static com.esark.gasp.GameScreen.movingRMS;
import static com.esark.gasp.GameScreen.psdResult;
import static com.esark.gasp.GameScreen.smoothedRMS;
import static com.esark.gasp.GameScreen.writer;

import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Process;
import android.util.Log;

import com.esark.framework.AndroidGame;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ConnectedThread extends Thread {
    private final BluetoothSocket mmSocket;
    private final InputStream mmInStream;
    private final OutputStream mmOutStream;
    private final Handler mHandler;

    private int count = 0;
    private final StringBuilder dataAccumulator = new StringBuilder();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public ConnectedThread(BluetoothSocket socket, Handler handler) {
        mmSocket = socket;
        mHandler = handler;
        InputStream tmpIn = null;
        OutputStream tmpOut = null;

        try {
            tmpIn = socket.getInputStream();
            tmpOut = socket.getOutputStream();
        } catch (IOException e) {
            Log.e("ConnectedThread", "Error creating streams", e);
        }

        mmInStream = tmpIn;
        mmOutStream = tmpOut;
    }

    @Override
    public void run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);

        byte[] buffer = new byte[2048];
        double fs = 1000;
        PowerSpectralDensityCalculator psdCalc = new PowerSpectralDensityCalculator(A2DVal, fs);

        // Persistent list to accumulate samples across multiple Bluetooth reads
        final java.util.ArrayList<Double> persistentBatch = new java.util.ArrayList<>();
        // Threshold: Only perform Heavy Math/Shifting every 50 samples (~20 times per second)
        final int batchThreshold = 50;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                int bytesRead = mmInStream.read(buffer);
                if (bytesRead > 0) {
                    // Convert raw bytes to string to parse characters
                    String incoming = new String(buffer, 0, bytesRead);
                    dataAccumulator.append(incoming);

                    // 1. Parse all available 'aXXXXXa' packets into the persistentBatch
                    int firstA;
                    while ((firstA = dataAccumulator.indexOf("a")) != -1) {
                        int nextA = dataAccumulator.indexOf("a", firstA + 1);
                        if (nextA != -1) {
                            try {
                                String sampleStr = dataAccumulator.substring(firstA + 1, nextA);
                                if (sampleStr.length() >= 5) {
                                    int val = Integer.parseInt(sampleStr.substring(0, 5));
                                    persistentBatch.add(val / 3.0);
                                }
                            } catch (Exception e) {
                                // Ignore malformed segments
                            }
                            dataAccumulator.delete(0, nextA);
                        } else {
                            break; // Wait for more data to complete the packet
                        }
                    }

                    // 2. ONLY if we have reached our threshold, offload to the executor
                    // This prevents the executor from being flooded with tiny tasks
                    if (persistentBatch.size() >= batchThreshold) {
                        // Create a final copy of the current batch for the background thread
                        final Double[] samplesToProcess = persistentBatch.toArray(new Double[0]);
                        persistentBatch.clear();

                        executor.execute(() -> {
                            int numNew = samplesToProcess.length;

                            // 3. BLOCK ARRAY SHIFT (Perform once for the whole batch)
                            System.arraycopy(A2DVal, numNew, A2DVal, 0, signalBufferLen - numNew);

                            // 4. UPDATE ARRAY AND RECORD
                            for (int i = 0; i < numNew; i++) {
                                double val = samplesToProcess[i];
                                A2DVal[signalBufferLen - numNew + i] = val;

                                if (GameScreen.isRecording && writer != null) {
                                    writer.println(val);
                                }
                            }

                            // 5. HEAVY MATH (PSD / RMS) - Now runs significantly less often
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

                            // 6. TRIGGER UI REDRAW
                            mHandler.post(() -> {
                                if (GameScreen.view != null) {
                                    GameScreen.view.invalidate();
                                }
                            });
                        });
                    }
                }
            } catch (IOException e) {
                Log.d("ConnectedThread", "Input stream disconnected");
                break;
            }
        }
        executor.shutdownNow();
    }


    public void cancel() {
        try {
            mmSocket.close();
            executor.shutdownNow();
        } catch (IOException e) {
            Log.e("ConnectedThread", "Could not close the connect socket", e);
        }
    }
}