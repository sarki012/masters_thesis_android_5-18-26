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
import java.util.concurrent.locks.LockSupport;

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
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        byte[] buffer = new byte[2048];
        final double[] packetSamples = new double[4096];

        // Precision constants
        final long NS_PER_SAMPLE = 1000000L; // Exactly 1ms in nanoseconds
        long startTimeNs = System.nanoTime();
        long samplesReleased = 0;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                // 1. BLOCKING READ
                int bytesRead = mmInStream.read(buffer);
                if (bytesRead <= 0) continue;

                int samplesFound = 0;
                for (int i = 0; i < bytesRead; i++) {
                    int b = buffer[i] & 0xFF;
                    if (b == 120) { expectingLowByte = false; continue; }
                    if (!expectingLowByte) {
                        tempHighByte = b;
                        expectingLowByte = true;
                    } else {
                        if (samplesFound < packetSamples.length) {
                            packetSamples[samplesFound++] = ((tempHighByte << 8) | b) / 3.0;
                        }
                        expectingLowByte = false;
                    }
                }

                if (samplesFound > 0) {
                    // Immediate Recording for CSV integrity
                    if (GameScreen.isRecording) {
                        synchronized (GameScreen.ramRecordBuffer) {
                            int spaceLeft = GameScreen.ramRecordBuffer.length - GameScreen.ramRecordBufferIdx;
                            int toCopy = Math.min(samplesFound, spaceLeft);
                            if (toCopy > 0) {
                                System.arraycopy(packetSamples, 0, GameScreen.ramRecordBuffer, GameScreen.ramRecordBufferIdx, toCopy);
                                GameScreen.ramRecordBufferIdx += toCopy;
                            }
                        }
                    }

                    // 2. PRECISION RELEASE ENGINE
                    int processedInPacket = 0;
                    while (processedInPacket < samplesFound) {
                        long now = System.nanoTime();
                        // Calculate debt in fractional samples for perfect accuracy
                        long elapsedNs = now - startTimeNs;
                        int targetSamples = (int) (elapsedNs / 1000000L); // 1ms = 1,000,000ns
                        int debt = targetSamples - (int) samplesReleased;

                        if (debt > 0) {
                            // Release small chunks (max 10) to keep duty cycle consistent
                            int chunkSize = Math.min(debt, samplesFound - processedInPacket);
                            chunkSize = Math.min(chunkSize, 4);

                            synchronized (A2DVal) {
                                // Shift display array
                                System.arraycopy(A2DVal, chunkSize, A2DVal, 0, signalBufferLen - chunkSize);
                                // Insert new data
                                System.arraycopy(packetSamples, processedInPacket, A2DVal, signalBufferLen - chunkSize, chunkSize);
                            }

                            if (GameScreen.view != null) {
                                GameScreen.view.postInvalidateOnAnimation();
                            }

                            processedInPacket += chunkSize;
                            samplesReleased += chunkSize;
                        } else {
                            // We are ahead of the clock.
                            // Using a very short sleep to avoid CPU maxing,
                            // but keeping it shorter than 1ms to maintain precision.
                            LockSupport.parkNanos(100000L); // Wait 0.1ms
                        }
                    }
                }
            } catch (IOException e) {
                break;
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