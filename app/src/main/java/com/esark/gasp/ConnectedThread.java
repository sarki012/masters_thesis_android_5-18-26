package com.esark.gasp;

import static com.esark.framework.AndroidGame.signalBufferLen;
import static com.esark.gasp.GameScreen.A2DVal;
import static com.esark.gasp.GameScreen.movingRMS;
import static com.esark.gasp.GameScreen.psdResult;
import static com.esark.gasp.GameScreen.ramRecordBuffer;
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
    private final ExecutorService recordExecutor = Executors.newSingleThreadExecutor();
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
        // 1. Set the highest priority for a background thread
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);

        byte[] buffer = new byte[4096];     // Larger buffer to handle bursts
        double fs = 1000;
        PowerSpectralDensityCalculator psdCalc = null;

        // Pre-allocate temporary storage to prevent Garbage Collection (GC) stutter
        double[] packetSamples = new double[2048];

        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (A2DVal == null || ramRecordBuffer == null) {
                    SystemClock.sleep(50);
                    continue;
                }

                if (psdCalc == null) {
                    psdCalc = new PowerSpectralDensityCalculator(A2DVal, fs);
                }

                // BLOCKING READ: Waits here for the next Bluetooth burst
                int bytesRead = mmInStream.read(buffer);
                if (bytesRead > 0) {
                    int samplesInThisPacket = 0;

                    // 2. FAST PARSE: Extract samples immediately
                    for (int i = 0; i < bytesRead; i++) {
                        int b = buffer[i] & 0xFF;

                        if (b == 'x' || b == 120) {
                            expectingLowByte = false;
                            continue;
                        }

                        if (!expectingLowByte) {
                            tempHighByte = b;
                            expectingLowByte = true;
                        } else {
                            int lowByte = b;
                            int rawVal = (tempHighByte << 8) | lowByte;
                            if (samplesInThisPacket < packetSamples.length) {
                                packetSamples[samplesInThisPacket++] = rawVal / 3.0;
                            }
                            expectingLowByte = false;
                        }
                    }

                    if (samplesInThisPacket > 0) {
                        final int numNew = samplesInThisPacket;

                        // 3. RECORDING: Synchronize ONCE per packet (Massive speed boost)
                        if (GameScreen.isRecording) {
                            synchronized (ramRecordBuffer) {
                                for (int j = 0; j < numNew; j++) {
                                    ramRecordBuffer.add(packetSamples[j]);
                                }
                            }
                        }

                        // 4. IMMEDIATE DISPLAY UPDATE: Run on BT thread to eliminate queue lag
                        // Shifting 1024 doubles is much faster than queuing an executor task.
                        synchronized (A2DVal) {
                            System.arraycopy(A2DVal, numNew, A2DVal, 0, signalBufferLen - numNew);
                            for (int j = 0; j < numNew; j++) {
                                A2DVal[signalBufferLen - numNew + j] = packetSamples[j];
                            }
                        }

                        // 5. LIGHTWEIGHT UI TRIGGER
                        // Tell Android to draw the next frame. Android will sync this with the 60Hz VSync.
                        if (GameScreen.view != null) {
                            GameScreen.view.postInvalidate();
                        }

                        // 6. OFF-LOAD HEAVY MATH (PSD/RMS)
                        // Math is the only thing that should be in an executor.
                        // We throttle it to 10 times per second to keep the CPU cool.
                        final PowerSpectralDensityCalculator finalPsdCalc = psdCalc;
                        if (mathSkipCount++ % 10 == 0 && finalPsdCalc != null) {
                            // Copy data safely so the BT thread can keep moving
                            synchronized (A2DVal) {
                                System.arraycopy(A2DVal, 0, a2dCopyForMath, 0, signalBufferLen);
                            }

                            mathExecutor.execute(() -> {
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