package com.esark.framework;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Point;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.esark.gasp.Assets;
import com.esark.gasp.ConnectedThread;
import com.esark.gasp.GameScreen;
import com.esark.gasp.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public abstract class AndroidGame extends Activity implements Game {
    // --- Framework Members ---
    public AndroidFastRenderView renderView;
    protected Graphics graphics;
    protected Audio audio;
    protected Input input;
    protected FileIO fileIO;
    protected Screen screen;

    // --- Bluetooth Members ---
    private TextView mBluetoothStatus;
    private Button mScanBtn, mOffBtn, mListPairedDevicesBtn, mDiscoverBtn, mShowGraphBtn;
    private BluetoothAdapter mBTAdapter;
    private Set<BluetoothDevice> mPairedDevices;
    private ArrayAdapter<String> mBTArrayAdapter;
    private ListView mDevicesListView;
    private Handler mHandler;
    private ConnectedThread mConnectedThread;
    private BluetoothSocket mBTSocket = null;

    private static final UUID BT_MODULE_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private final static int REQUEST_ENABLE_BT = 1;
    public final static int CONNECTING_STATUS = 1;
    private final String HC05_MAC = "98:D3:02:96:BA:26";

    // --- Static Constants ---
    public static int landscape = 0;
    public static int width = 0;
    public static int height = 0;
    public static int signalBufferLen = 1436;
    private static AndroidGame instance;
    private boolean isConnecting = false;
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        // UI Setup
        mBluetoothStatus = findViewById(R.id.bluetooth_status);
        mScanBtn = findViewById(R.id.scan);
        mOffBtn = findViewById(R.id.off);
        mDiscoverBtn = findViewById(R.id.discover);
        mListPairedDevicesBtn = findViewById(R.id.paired_btn);
        mShowGraphBtn = findViewById(R.id.display_btn);
        mDevicesListView = findViewById(R.id.devices_list_view);

        mBTArrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        mDevicesListView.setAdapter(mBTArrayAdapter);
        mDevicesListView.setOnItemClickListener(mDeviceClickListener);

        mBTAdapter = BluetoothAdapter.getDefaultAdapter();
        checkPermissions();

        // Handle Bluetooth Status Messages
        mHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what == CONNECTING_STATUS) {
                    if (msg.arg1 == 1) {
                        mBluetoothStatus.setText("Connected to Device: " + msg.obj);
                        GameScreen.btStatus = "BT: Connected to HC-05";
                    } else {
                        mBluetoothStatus.setText("Connection Failed");
                        GameScreen.btStatus = "BT: Disconnected";
                    }
                }
            }
        };

        setupFramework();
        setupClickListeners();
    }

    private void setupFramework() {
        boolean isLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        landscape = isLandscape ? 1 : 0;

        int fbW = isLandscape ? 2707 : 1752;
        int fbH = isLandscape ? 1752 : 2707;
        Bitmap frameBuffer = Bitmap.createBitmap(fbW, fbH, Config.RGB_565);

        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);

        renderView = new AndroidFastRenderView(this, frameBuffer);
        graphics = new AndroidGraphics(getAssets(), frameBuffer);
        fileIO = new AndroidFileIO(this);
        audio = new AndroidAudio(this);
        input = new AndroidInput(this, renderView, (float) fbW / metrics.widthPixels, (float) fbH / metrics.heightPixels);
        screen = getStartScreen();
    }

    private void setupClickListeners() {
        mScanBtn.setOnClickListener(v -> bluetoothOn());
        mOffBtn.setOnClickListener(v -> bluetoothOff());
        mListPairedDevicesBtn.setOnClickListener(v -> listPairedDevices());
        mDiscoverBtn.setOnClickListener(v -> discover());
        mShowGraphBtn.setOnClickListener(v -> showGraph());
    }

    // --- Bluetooth Logic ---

    private void bluetoothOn() {
        if (!mBTAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            if (hasConnectPermission()) {
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            }
        }
    }

    private void bluetoothOff() {
        if (hasConnectPermission()) {
            mBTAdapter.disable();
            mBluetoothStatus.setText("Bluetooth disabled");
        }
    }

    private void listPairedDevices() {
        if (!hasConnectPermission()) return;
        mBTArrayAdapter.clear();
        mPairedDevices = mBTAdapter.getBondedDevices();
        if (mBTAdapter.isEnabled()) {
            for (BluetoothDevice device : mPairedDevices) {
                mBTArrayAdapter.add(device.getName() + "\n" + device.getAddress());
            }
        }
    }

    private final AdapterView.OnItemClickListener mDeviceClickListener = new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            if (!mBTAdapter.isEnabled()) return;
            String info = ((TextView) view).getText().toString();
            final String address = info.substring(info.length() - 17);
            final String name = info.substring(0, info.length() - 17);
            connectToDevice(address, name);
        }
    };

    private void connectToDevice(String address, String name) {
        // If we are already connected OR in the process of connecting, STOP.
        if (isConnecting || mBTSocket != null) {
            return;
        }

        isConnecting = true;
        mBluetoothStatus.setText("Connecting...");

        new Thread(() -> {
            boolean fail = false;
            BluetoothDevice device = mBTAdapter.getRemoteDevice(address);
            try {
                mBTSocket = device.createRfcommSocketToServiceRecord(BT_MODULE_UUID);
                if (hasConnectPermission()) {
                    mBTSocket.connect();
                } else {
                    fail = true;
                }
            } catch (IOException e) {
                fail = true;
                isConnecting = false; // Reset on failure
                try { mBTSocket.close(); mBTSocket = null; } catch (IOException ignored) {}
                mHandler.obtainMessage(CONNECTING_STATUS, -1, -1).sendToTarget();
            }

            if (!fail) {
                try {
                    mConnectedThread = new ConnectedThread(mBTSocket.getInputStream());
                    mConnectedThread.start();
                    mHandler.obtainMessage(CONNECTING_STATUS, 1, -1, name).sendToTarget();
                } catch (IOException e) {
                    Log.e("BT", "Stream creation failed");
                } finally {
                    isConnecting = false; // Reset on success
                }
            }
        }).start();
    }

    // --- Permission Helpers ---

    private void checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                }, 1);
            }
        }
    }

    private boolean hasConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    // --- Lifecycle and Framework Methods ---

    public static View getGameView() {
        return (instance != null) ? instance.renderView : null;
    }

    public void setScreen(Screen screen) {
        if (screen == null) throw new IllegalArgumentException("Screen must not be null");
        this.screen.pause();
        this.screen.dispose();
        screen.resume();
        screen.update(0, this);
        this.screen = screen;
    }

    private void showGraph() {
        setContentView(renderView);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (screen != null) screen.resume();
        renderView.resume();

        // AUTO-CONNECT LOGIC: If HC-05 is paired, connect immediately
        if (mBTSocket == null && mBTAdapter.isEnabled() && hasConnectPermission()) {
            Set<BluetoothDevice> paired = mBTAdapter.getBondedDevices();
            for(BluetoothDevice d : paired) {
                if(d.getAddress().equals(HC05_MAC)) {
                    connectToDevice(HC05_MAC, "HC-05");
                    break;
                }
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        renderView.pause();
        if (screen != null) screen.pause();
        if (isFinishing() && screen != null) screen.dispose();
    }

    public Input getInput() { return input; }
    public FileIO getFileIO() { return fileIO; }
    public Graphics getGraphics() { return graphics; }
    public Audio getAudio() { return audio; }
    public Screen getCurrentScreen() { return screen; }

    private void discover() { /* discovery logic */ }
}