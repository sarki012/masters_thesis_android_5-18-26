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
import java.io.PrintWriter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ConnectedThread extends Thread {
    private final BluetoothSocket mmSocket;
    private final InputStream mmInStream;
    private final OutputStream mmOutStream;
    private final Handler mHandler;

    private int mathSkipCount = 0;

    // State machine variables for binary parsing
    private boolean expectingLowByte = false;
    private int tempHighByte = 0;

    private final ExecutorService displayExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService mathExecutor = Executors.newSingleThreadExecutor();
    private final double[] a2dCopyForMath = new double[signalBufferLen];
    private final ExecutorService recordExecutor = Executors.newSingleThreadExecutor();

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
        PowerSpectralDensityCalculator psdCalc = null;

        // Temporary array to hold samples parsed from a single Bluetooth packet
        double[] parsedSamples = new double[1024];

        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (A2DVal == null) {
                    SystemClock.sleep(100);
                    continue;
                }

                if (psdCalc == null) {
                    psdCalc = new PowerSpectralDensityCalculator(A2DVal, fs);
                }

                int bytesRead = mmInStream.read(buffer);
                if (bytesRead > 0) {
                    int samplesFound = 0;

                    // 1. PARSE ENTIRE BUFFER FIRST
                    for (int i = 0; i < bytesRead; i++) {
                        int b = buffer[i] & 0xFF;

                        if (b == 'x') {
                            expectingLowByte = false;
                            continue;
                        }

                        if (!expectingLowByte) {
                            tempHighByte = b;
                            expectingLowByte = true;
                        } else {
                            int lowByte = b;
                            int rawVal = (tempHighByte << 8) | lowByte;
                            if (samplesFound < parsedSamples.length) {
                                parsedSamples[samplesFound++] = rawVal / 3.0;
                            }
                            expectingLowByte = false;
                        }
                    }

                    // 2. PROCESS THE BATCH IF SAMPLES WERE FOUND
                    if (samplesFound > 0) {
                        final int numNew = samplesFound;
                        final double[] batchCopy = new double[numNew];
                        System.arraycopy(parsedSamples, 0, batchCopy, 0, numNew);

                        // TASK 1: RECORDING (Async Batch Write)
                        // This prevents the "180 points" issue by writing everything in one go
                        if (GameScreen.isRecording && writer != null) {
                            recordExecutor.execute(() -> {
                                if (writer != null) {
                                    StringBuilder sb = new StringBuilder();
                                    for (double val : batchCopy) {
                                        sb.append(val).append("\n");
                                    }
                                    writer.print(sb.toString());
                                }
                            });
                        }

                        // TASK 2: DISPLAY (Async Shift)
                        displayExecutor.execute(() -> {
                            if (A2DVal != null) {
                                synchronized (A2DVal) {
                                    System.arraycopy(A2DVal, numNew, A2DVal, 0, signalBufferLen - numNew);
                                    for (int j = 0; j < numNew; j++) {
                                        A2DVal[signalBufferLen - numNew + j] = batchCopy[j];
                                    }
                                }
                                if (GameScreen.view != null) {
                                    GameScreen.view.postInvalidate();
                                }
                            }
                        });

                        // TASK 3: MATH (Throttled)
                        final PowerSpectralDensityCalculator finalPsdCalc = psdCalc;
                        if (mathSkipCount++ % 10 == 0 && finalPsdCalc != null) {
                            mathExecutor.execute(() -> {
                                if (A2DVal != null) {
                                    synchronized (A2DVal) {
                                        System.arraycopy(A2DVal, 0, a2dCopyForMath, 0, signalBufferLen);
                                    }
                                    double[] tempResult = finalPsdCalc.calculatePSD(a2dCopyForMath, fs);
                                    if (tempResult != null && psdResult != null) {
                                        System.arraycopy(tempResult, 0, psdResult, 0, Math.min(tempResult.length, psdResult.length));
                                        for (int j = 0; j < psdResult.length; j++) {
                                            psdResult[j] = psdResult[j] * -1 + 3600;
                                            if (psdResult[j] < 3165) psdResult[j] = 3165;
                                        }
                                    }
                                    movingRMS = RMSCalculator.calculateMovingRMS(a2dCopyForMath, 10);
                                    if (movingRMS != null) {
                                        smoothedRMS = MovingAverageCalculator.calculateMovingAverage(movingRMS, 20);
                                    }
                                    // Flush the file on the math thread (~10 times per second)
                                    if (writer != null) {
                                        writer.flush();
                                    }
                                }
                            });
                        }
                    }
                }
            } catch (IOException e) {
                break;
            } catch (Exception e) {
                Log.e("ConnectedThread", "Parsing Error", e);
            }
        }
        displayExecutor.shutdownNow();
        mathExecutor.shutdownNow();
        recordExecutor.shutdownNow();
    }

    private static class SystemClock {
        public static void sleep(long ms) {
            try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
        }
    }

    public void cancel() {
        try {
            mmSocket.close();
        } catch (IOException e) {
            Log.e("ConnectedThread", "Could not close socket", e);
        }
    }
}