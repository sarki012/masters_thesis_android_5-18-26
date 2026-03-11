package com.esark.gasp;


import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.os.SystemClock;

import com.esark.framework.AndroidGame;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

// Connected Thread handles Bluetooth communication
public class ConnectedThread extends Thread {
    private final BluetoothSocket mmSocket;
    private final InputStream mmInStream;
    private final OutputStream mmOutStream;
    private final Handler mHandler;

    public ConnectedThread(BluetoothSocket socket, Handler handler) {
        mmSocket = socket;
        mHandler = handler;
        InputStream tmpIn = null;
        OutputStream tmpOut = null;

        try {
            tmpIn = socket.getInputStream();
            tmpOut = socket.getOutputStream();
        } catch (IOException e) {
            e.printStackTrace();
        }

        mmInStream = tmpIn;
        mmOutStream = tmpOut;
    }

    @Override
    public void run() {
        DataInputStream dataIn = new DataInputStream(mmInStream);
        
        // Keep listening to the InputStream until an exception occurs
        while (true) {
            try {
                // Create a new buffer for each 50-byte packet
                byte[] buffer = new byte[24];
                
                // readFully blocks until exactly 50 bytes are read or an exception occurs
                dataIn.readFully(buffer);
                SystemClock.sleep(10);
                // Send the full 50-byte buffer to the UI handler
                mHandler.obtainMessage(AndroidGame.MESSAGE_READ, 24, -1, buffer)
                        .sendToTarget();
                
            } catch (IOException e) {
                // Connection was likely lost
                e.printStackTrace();
                break;
            }
        }
    }

    public void write(String input) {
        byte[] bytes = input.getBytes(); // converts entered String into bytes
        try {
            mmOutStream.write(bytes);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /* Call this from the main activity to shutdown the connection */
    public void cancel() {
        try {
            mmSocket.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}