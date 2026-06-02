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
        // Set priority to urgent to ensure smooth Bluetooth reading
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);

        byte[] buffer = new byte[2048];
        double fs = 1000;
        PowerSpectralDensityCalculator psdCalc = new PowerSpectralDensityCalculator(A2DVal, fs);

        // List to hold samples parsed from a single Bluetooth read
        java.util.ArrayList<Double> sampleBatch = new java.util.ArrayList<>();

        while (!Thread.currentThread().isInterrupted()) {
            try {
                int bytesRead = mmInStream.read(buffer);
                if (bytesRead > 0) {
                    final byte[] dataBlock = new byte[bytesRead];
                    System.arraycopy(buffer, 0, dataBlock, 0, bytesRead);

                    executor.execute(() -> {
                        sampleBatch.clear();

                        // 1. PARSE ALL SAMPLES IN THIS PACKET
                        for (int i = 0; i < dataBlock.length; i++) {
                            char c = (char) dataBlock[i];
                            if (c == 'a') {
                                if (dataAccumulator.length() >= 5) {
                                    try {
                                        int val = Integer.parseInt(dataAccumulator.substring(0, 5));
                                        sampleBatch.add(val / 3.0);
                                    } catch (Exception e) {}
                                }
                                dataAccumulator.setLength(0);
                            } else {
                                dataAccumulator.append(c);
                            }
                        }

                        // 2. BLOCK UPDATE A2DVal
                        int numNewSamples = sampleBatch.size();
                        if (numNewSamples > 0) {
                            // Shift array left once by the number of new samples
                            System.arraycopy(A2DVal, numNewSamples, A2DVal, 0, signalBufferLen - numNewSamples);

                            // Add all new samples to the end and record to file
                            for (int i = 0; i < numNewSamples; i++) {
                                double val = sampleBatch.get(i);
                                A2DVal[signalBufferLen - numNewSamples + i] = val;

                                if (GameScreen.isRecording && writer != null) {
                                    writer.println(val);
                                }
                            }

                            // 3. PERFORM HEAVY MATH ONLY ONCE PER PACKET
                            // This is the key to speed! 1000 FFTs/sec -> ~60 FFTs/sec
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

                            // 4. TRIGGER REDRAW
                            mHandler.post(() -> {
                                if (GameScreen.view != null) {
                                    GameScreen.view.invalidate();
                                }
                            });
                        }
                    });
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