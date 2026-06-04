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
    private int mathSkipCount = 0;      // Counter to throttle heavy math
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
        // Urgent Display priority ensures the UI redraw isn't delayed by background OS tasks
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);

        byte[] buffer = new byte[2048];
        double fs = 1000;
        PowerSpectralDensityCalculator psdCalc = new PowerSpectralDensityCalculator(A2DVal, fs);

        final java.util.ArrayList<Double> persistentBatch = new java.util.ArrayList<>();

        // 18 samples @ 1000Hz = ~18ms. This matches the 60Hz refresh rate of Android screens.
        final int batchThreshold = 18;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                int bytesRead = mmInStream.read(buffer);
                if (bytesRead > 0) {
                    // Step 1: Parse characters into numbers immediately
                    for (int i = 0; i < bytesRead; i++) {
                        char c = (char) buffer[i];
                        if (c == 'a') {
                            if (dataAccumulator.length() >= 5) {
                                try {
                                    int val = Integer.parseInt(dataAccumulator.toString().substring(0, 5));
                                    persistentBatch.add(val / 3.0);
                                } catch (Exception e) {
                                    // Skip malformed data
                                }
                            }
                            dataAccumulator.setLength(0);
                        } else if (Character.isDigit(c)) {
                            dataAccumulator.append(c);
                        }
                    }

                    // Step 2: If we have a batch ready, process it
                    if (persistentBatch.size() >= batchThreshold) {
                        final Double[] samplesToProcess = persistentBatch.toArray(new Double[0]);
                        persistentBatch.clear();

                        executor.execute(() -> {
                            int numNew = samplesToProcess.length;

                            // Update the shared array (Shift left and add new)
                            System.arraycopy(A2DVal, numNew, A2DVal, 0, signalBufferLen - numNew);
                            for (int i = 0; i < numNew; i++) {
                                double val = samplesToProcess[i];
                                A2DVal[signalBufferLen - numNew + i] = val;
                                if (GameScreen.isRecording && writer != null) {
                                    writer.println(val);
                                }
                            }

                            // REDRAW IMMEDIATELY
                            // We do this BEFORE the math so the visual sweep is never delayed by the FFT
                            mHandler.post(() -> {
                                if (GameScreen.view != null) {
                                    GameScreen.view.invalidate();
                                }
                            });

                            // THROTTLED HEAVY MATH
                            // We only do PSD and RMS every 5th batch (~every 100ms).
                            // This is plenty for a human-readable graph but saves 80% CPU usage.
                            if (mathSkipCount++ % 5 == 0) {
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