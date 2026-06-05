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

    private int mathSkipCount = 0;
    private final StringBuilder dataAccumulator = new StringBuilder();

    private final ExecutorService displayExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService mathExecutor = Executors.newSingleThreadExecutor();

    // Use signalBufferLen from AndroidGame to ensure sizes match perfectly
    private final double[] a2dCopyForMath = new double[signalBufferLen];

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

        // Use a local reference to avoid re-initialization crashes
        PowerSpectralDensityCalculator psdCalc = null;
        double[] localBatch = new double[1024];

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // Ensure A2DVal is initialized before proceeding
                if (A2DVal == null) {
                    SystemClock.sleep(100);
                    continue;
                }

                // Initialize PSD calculator only once A2DVal exists
                if (psdCalc == null) {
                    psdCalc = new PowerSpectralDensityCalculator(A2DVal, fs);
                }

                int bytesRead = mmInStream.read(buffer);
                if (bytesRead > 0) {
                    int samplesInThisRead = 0;
                    String msg = new String(buffer, 0, bytesRead);
                    dataAccumulator.append(msg);

                    int firstA;
                    while ((firstA = dataAccumulator.indexOf("a")) != -1) {
                        int nextA = dataAccumulator.indexOf("a", firstA + 1);
                        if (nextA != -1) {
                            String sampleStr = dataAccumulator.substring(firstA + 1, nextA);
                            if (sampleStr.length() >= 5) {
                                try {
                                    int val = Integer.parseInt(sampleStr.substring(0, 5));
                                    if (samplesInThisRead < localBatch.length) {
                                        localBatch[samplesInThisRead++] = val / 3.0;
                                    }
                                } catch (NumberFormatException e) { }
                            }
                            dataAccumulator.delete(0, nextA);
                        } else {
                            break;
                        }
                    }

                    if (samplesInThisRead > 0) {
                        final int numNew = samplesInThisRead;
                        final double[] batchCopy = new double[numNew];
                        System.arraycopy(localBatch, 0, batchCopy, 0, numNew);

                        // TASK 1: DISPLAY
                        displayExecutor.execute(() -> {
                            // Null check A2DVal inside the lambda too
                            if (A2DVal != null) {
                                synchronized (A2DVal) {
                                    System.arraycopy(A2DVal, numNew, A2DVal, 0, signalBufferLen - numNew);
                                    for (int j = 0; j < numNew; j++) {
                                        double val = batchCopy[j];
                                        A2DVal[signalBufferLen - numNew + j] = val;
                                        if (GameScreen.isRecording && writer != null) {
                                            writer.println(val);
                                        }
                                    }
                                }
                                if (GameScreen.view != null) {
                                    GameScreen.view.postInvalidate();
                                }
                            }
                        });

                        // TASK 2: MATH
                        final PowerSpectralDensityCalculator finalPsdCalc = psdCalc;
                        if (mathSkipCount++ % 10 == 0 && finalPsdCalc != null) {
                            mathExecutor.execute(() -> {
                                if (A2DVal != null) {
                                    synchronized (A2DVal) {
                                        System.arraycopy(A2DVal, 0, a2dCopyForMath, 0, signalBufferLen);
                                    }

                                    double[] tempResult = finalPsdCalc.calculatePSD(a2dCopyForMath, fs);

                                    // Null checks for all shared results
                                    if (tempResult != null && psdResult != null && tempResult.length <= psdResult.length) {
                                        System.arraycopy(tempResult, 0, psdResult, 0, tempResult.length);
                                        for (int j = 0; j < psdResult.length; j++) {
                                            psdResult[j] = psdResult[j] * -1 + 3600;
                                            if (psdResult[j] < 3165) psdResult[j] = 3165;
                                        }
                                    }

                                    // Calculate RMS only if arrays are ready
                                    movingRMS = RMSCalculator.calculateMovingRMS(a2dCopyForMath, 10);
                                    if (movingRMS != null) {
                                        smoothedRMS = MovingAverageCalculator.calculateMovingAverage(movingRMS, 20);
                                    }
                                }
                            });
                        }
                    }
                }
            } catch (IOException e) {
                break;
            } catch (Exception e) {
                Log.e("ConnectedThread", "Unexpected error", e);
            }
        }
        displayExecutor.shutdownNow();
        mathExecutor.shutdownNow();
    }

    // Helper for null-safety wait
    private static class SystemClock {
        public static void sleep(long ms) {
            try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
        }
    }

    public void cancel() {
        try {
            mmSocket.close();
        } catch (IOException e) {
            Log.e("ConnectedThread", "Could not close the socket", e);
        }
    }
}