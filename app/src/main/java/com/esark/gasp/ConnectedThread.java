package com.esark.gasp;

import static com.esark.framework.AndroidGame.signalBufferLen;
import static com.esark.gasp.GameScreen.A2DVal;
import static com.esark.gasp.GameScreen.movingRMS;
import static com.esark.gasp.GameScreen.psdResult;
import static com.esark.gasp.GameScreen.smoothedRMS;

import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConnectedThread extends Thread {
    private final BluetoothSocket mmSocket;
    private final InputStream mmInStream;
    private final OutputStream mmOutStream;
    private final Handler mHandler;

    private int mathSkipCount = 0;
    private boolean expectingLowByte = false;
    private int tempHighByte = 0;

    // displayExecutor is removed for UI updates to prevent queue lag
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
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        byte[] buffer = new byte[4096];
        double fs = 1000;
        PowerSpectralDensityCalculator psdCalc = null;

        // Temporary storage for samples found in the "Drain" cycle
        final double[] packetSamples = new double[8192];
        final java.util.concurrent.atomic.AtomicBoolean mathIsBusy = new java.util.concurrent.atomic.AtomicBoolean(false);

        // CADENCE TRACKING: This prevents the "slow/bunched" on-off switch behavior
        long lastUiUpdateTime = 0;
        int accumulatedSamples = 0;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (psdCalc == null && A2DVal != null) {
                    psdCalc = new PowerSpectralDensityCalculator(A2DVal, fs);
                }

                // 1. THE SUPER-DRAIN: Always empty the hardware buffer first
                int samplesInThisCycle = 0;
                while (mmInStream.available() > 0 || samplesInThisCycle == 0) {
                    int bytesRead = mmInStream.read(buffer);
                    if (bytesRead <= 0) break;

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
                            if (samplesInThisCycle < packetSamples.length) {
                                packetSamples[samplesInThisCycle++] = ((tempHighByte << 8) | b) / 3.0;
                            }
                            expectingLowByte = false;
                        }
                    }
                    if (samplesInThisCycle > 4000) break;
                }

                if (samplesInThisCycle > 0) {
                    final int numNew = samplesInThisCycle;

                    // 2. IMMEDIATE RECORDING: Record everything in RAM as it arrives
                    if (GameScreen.isRecording) {
                        synchronized (GameScreen.ramRecordBuffer) {
                            int spaceLeft = GameScreen.ramRecordBuffer.length - GameScreen.ramRecordBufferIdx;
                            int toCopy = Math.min(numNew, spaceLeft);
                            if (toCopy > 0) {
                                System.arraycopy(packetSamples, 0, GameScreen.ramRecordBuffer, GameScreen.ramRecordBufferIdx, toCopy);
                                GameScreen.ramRecordBufferIdx += toCopy;
                            }
                        }
                    }

                    // 3. ATOMIC UI ARRAY UPDATE: Update data immediately to stay in sync
                    synchronized (A2DVal) {
                        System.arraycopy(A2DVal, numNew, A2DVal, 0, signalBufferLen - numNew);
                        System.arraycopy(packetSamples, 0, A2DVal, signalBufferLen - numNew, numNew);
                    }

                    accumulatedSamples += numNew;

                    // 4. CADENCE STABILIZER: The key to fixing the "on-off switch"
                    // Only request a redraw at 60Hz (every 16ms).
                    // This prevents "bunching" at low frequencies and "flying" at high frequencies.
                    long currentTime = SystemClock.uptimeMillis();
                    if (currentTime - lastUiUpdateTime >= 16 || accumulatedSamples > 100) {
                        if (GameScreen.view != null) {
                            GameScreen.view.postInvalidate();
                        }
                        lastUiUpdateTime = currentTime;
                        accumulatedSamples = 0;
                    }

                    // 5. NON-BLOCKING MATH (PSD/RMS)
                    if (mathIsBusy.compareAndSet(false, true)) {
                        synchronized (A2DVal) {
                            System.arraycopy(A2DVal, 0, a2dCopyForMath, 0, signalBufferLen);
                        }
                        final PowerSpectralDensityCalculator finalPsd = psdCalc;
                        mathExecutor.execute(() -> {
                            try {
                                double[] tempResult = finalPsd.calculatePSD(a2dCopyForMath, fs);
                                if (tempResult != null && psdResult != null) {
                                    int copyLen = Math.min(tempResult.length, psdResult.length);
                                    System.arraycopy(tempResult, 0, psdResult, 0, copyLen);
                                    for (int j = 0; j < copyLen; j++) {
                                        psdResult[j] = psdResult[j] * -1 + 3600;
                                        if (psdResult[j] < 3165) psdResult[j] = 3165;
                                    }
                                }
                                movingRMS = RMSCalculator.calculateMovingRMS(a2dCopyForMath, 10);
                                if (movingRMS != null) {
                                    smoothedRMS = MovingAverageCalculator.calculateMovingAverage(movingRMS, 20);
                                }
                            } finally {
                                mathIsBusy.set(false);
                            }
                        });
                    }
                }
            } catch (IOException e) {
                Log.e("BT", "Stream disconnected");
                break;
            } catch (Exception e) {
                Log.e("BT", "Parsing Error", e);
            }
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