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
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);

        byte[] buffer = new byte[2048];
        double fs = 1000;
        PowerSpectralDensityCalculator psdCalc = new PowerSpectralDensityCalculator(A2DVal, fs);

        final double[] primitiveBatch = new double[256];
        int batchIdx = 0;
        final int batchThreshold = 20;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                int bytesRead = mmInStream.read(buffer);
                if (bytesRead > 0) {
                    for (int i = 0; i < bytesRead; i++) {
                        char c = (char) buffer[i];

                        if (c == 'a') {
                            if (dataAccumulator.length() >= 5) {
                                int val = 0;
                                for (int k = 0; k < 5; k++) {
                                    val = val * 10 + (dataAccumulator.charAt(k) - '0');
                                }
                                primitiveBatch[batchIdx++] = val / 3.0;
                            }
                            dataAccumulator.setLength(0);
                        } else if (c >= '0' && c <= '9') {
                            dataAccumulator.append(c);
                        }

                        // When batch is full, process it
                        if (batchIdx >= batchThreshold) {
                            final int numNew = batchIdx;
                            final double[] samplesToProcess = new double[numNew];
                            System.arraycopy(primitiveBatch, 0, samplesToProcess, 0, numNew);
                            batchIdx = 0;

                            executor.execute(() -> {
                                // Update A2DVal array
                                synchronized (A2DVal) {
                                    System.arraycopy(A2DVal, numNew, A2DVal, 0, signalBufferLen - numNew);
                                    for (int j = 0; j < numNew; j++) {
                                        double val = samplesToProcess[j];
                                        A2DVal[signalBufferLen - numNew + j] = val;
                                        if (GameScreen.isRecording && writer != null) {
                                            writer.println(val);
                                        }
                                    }
                                }

                                // Update UI
                                mHandler.post(() -> {
                                    if (GameScreen.view != null) {
                                        GameScreen.view.invalidate();
                                    }
                                });

                                // Heavy Math (PSD / RMS) throttled
                                if (mathSkipCount++ % 5 == 0) {
                                    double[] tempResult = psdCalc.calculatePSD(A2DVal, fs);
                                    if (tempResult != null && tempResult.length <= psdResult.length) {
                                        System.arraycopy(tempResult, 0, psdResult, 0, tempResult.length);
                                    }

                                    for (int j = 0; j < psdResult.length; j++) {
                                        psdResult[j] = psdResult[j] * -1 + 3600;
                                        if (psdResult[j] < 3165) psdResult[j] = 3165;
                                    }

                                    movingRMS = RMSCalculator.calculateMovingRMS(A2DVal, 10);
                                    smoothedRMS = MovingAverageCalculator.calculateMovingAverage(movingRMS, 20);
                                }
                            }); // End executor.execute
                        } // End if (batchIdx)
                    } // End for
                } // End if (bytesRead)
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