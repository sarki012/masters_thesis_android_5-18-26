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
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);

        byte[] buffer = new byte[2048];
        double fs = 1000;
        PowerSpectralDensityCalculator psdCalc = null;

        // Temporary array to hold samples parsed from ONE Bluetooth packet
        double[] readPacketSamples = new double[1024];

        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (A2DVal == null || ramRecordBuffer == null) {
                    SystemClock.sleep(100);
                    continue;
                }

                if (psdCalc == null) {
                    psdCalc = new PowerSpectralDensityCalculator(A2DVal, fs);
                }

                int bytesRead = mmInStream.read(buffer);
                if (bytesRead > 0) {
                    int samplesFoundInPacket = 0;

                    // 1. PARSE ALL BYTES IN THE CURRENT PACKET
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
                            double parsedVal = rawVal / 3.0;
                            expectingLowByte = false;

                            // RECORDING: Add every sample to RAM immediately
                            if (GameScreen.isRecording) {
                                synchronized (ramRecordBuffer) {
                                    ramRecordBuffer.add(parsedVal);
                                }
                            }

                            // Store in temporary packet buffer for UI update
                            if (samplesFoundInPacket < readPacketSamples.length) {
                                readPacketSamples[samplesFoundInPacket++] = parsedVal;
                            }
                        }
                    }

                    // 2. UPDATE UI ONCE PER BLUETOOTH READ (Natural Batching)
                    // This eliminates "bunching" and lag by aligning drawing with data arrival
                    if (samplesFoundInPacket > 0) {
                        final int numNew = samplesFoundInPacket;
                        final double[] samplesToProcess = new double[numNew];
                        System.arraycopy(readPacketSamples, 0, samplesToProcess, 0, numNew);

                        // TASK: DISPLAY (High Priority)
                        displayExecutor.execute(() -> {
                            if (A2DVal != null) {
                                synchronized (A2DVal) {
                                    // Shift existing data to the left
                                    System.arraycopy(A2DVal, numNew, A2DVal, 0, signalBufferLen - numNew);
                                    // Add the new packet data to the end
                                    for (int j = 0; j < numNew; j++) {
                                        A2DVal[signalBufferLen - numNew + j] = samplesToProcess[j];
                                    }
                                }
                                if (GameScreen.view != null) {
                                    // postInvalidateOnAnimation is smoother on modern Android
                                    GameScreen.view.postInvalidate();
                                }
                            }
                        });

                        // TASK: MATH (Low Priority - Throttled)
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