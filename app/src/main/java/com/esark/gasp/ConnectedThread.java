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
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        byte[] buffer = new byte[4096];
        final double[] packetSamples = new double[4096];
        final java.util.concurrent.atomic.AtomicBoolean mathIsBusy = new java.util.concurrent.atomic.AtomicBoolean(false);

        while (!Thread.currentThread().isInterrupted()) {
            try {
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
                    // 1. Record immediately
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

                    // 2. ADAPTIVE DRIP (The Smoothness Fix)
                    int processed = 0;
                    while (processed < samplesFound) {
                        // Move 16 samples (16ms of data)
                        int chunkSize = Math.min(16, samplesFound - processed);
                        final double[] subBatch = new double[chunkSize];
                        System.arraycopy(packetSamples, processed, subBatch, 0, chunkSize);

                        synchronized (A2DVal) {
                            System.arraycopy(A2DVal, chunkSize, A2DVal, 0, signalBufferLen - chunkSize);
                            System.arraycopy(subBatch, 0, A2DVal, signalBufferLen - chunkSize, chunkSize);
                        }

                        if (GameScreen.view != null) {
                            GameScreen.view.postInvalidateOnAnimation();
                        }

                        processed += chunkSize;

                        // --- ADAPTIVE SLEEP ---
                        // If we have a lot of data left in this packet (backlog),
                        // sleep less (12ms) to "catch up" smoothly.
                        // Otherwise, sleep 16ms to match the screen.
                        if (samplesFound - processed > 32) {
                            SystemClock.sleep(10); // Fast catch-up
                        } else {
                            SystemClock.sleep(16); // Normal steady flow
                        }
                    }
                }
            } catch (IOException e) { break; }
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