package com.esark.framework;

import static com.esark.gasp.GameScreen.A2DVal;

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
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.esark.gasp.GameScreen;
import com.esark.gasp.R;
import com.esark.gasp.GaspSemg;
import com.esark.gasp.ConnectedThread;


import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.Set;
import java.util.UUID;

public abstract class AndroidGame extends Activity implements Game {
    Bundle newBundy = new Bundle();
    AndroidFastRenderView renderView;
    Graphics graphics;
    Audio audio;
    Input input;
    FileIO fileIO;
    public Screen screen;

    private final String TAG = AndroidGame.class.getSimpleName();

    private static final UUID BT_MODULE_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB"); // "random" unique identifier
    // #defines for identifying shared types between calling functions
    private final static int REQUEST_ENABLE_BT = 1; // used to identify adding bluetooth names
    public final static int MESSAGE_READ = 2; // used in bluetooth handler to identify message update
    private final static int CONNECTING_STATUS = 3; // used in bluetooth handler to identify message status

    // GUI Components
    private TextView mBluetoothStatus;
    private Button mScanBtn;
    private Button mOffBtn;
    private Button mListPairedDevicesBtn;
    private Button mDiscoverBtn;
    private Button mShowGraphBtn;
    private ListView mDevicesListView;

    private BluetoothAdapter mBTAdapter;
    private Set<BluetoothDevice> mPairedDevices;
    private ArrayAdapter<String> mBTArrayAdapter;

    private Handler mHandler; // Our main handler that will receive callback notifications
    private ConnectedThread mConnectedThread; // bluetooth background worker thread to send and receive data
    private BluetoothSocket mBTSocket = null; // bi-directional client-to-client data path
    private int number10000 = 0;
    private int number1000 = 0;
    private int number100 = 0;
    private int number10 = 0;
    private int number1 = 0;
    private int totalA2DVal = 0;
    private int numberHolder = 0;
    private int numberCount = 0;
    private char bluetoothVal4 = 0;
    private char bluetoothVal3 = 0;
    private char bluetoothVal2 = 0;
    private char bluetoothVal1 = 0;
    private char bluetoothVal0 = 0;
    private int j = 0;
    int n = 0;
    int i = 0;
    int t = 0;
    public static int landscape = 0;
    public static char startChar = 0;
    public static int width = 0;
    public static int height = 0;

    public static int bufferFlag = 0;

   // private LruCache<String, Bitmap> mMemoryCache;
    //public int count = 0;
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

                /*

    If you setup the cache in the Application class then it will never get destroyed
    until the app shuts down. You are guaranteed that the Application class will always
    be "alive" when ever one of your activities are.
         */

        setContentView(R.layout.activity_main);

        // Get the pixel dimensions of the screen
        Display display = getWindowManager().getDefaultDisplay();
        // Initialize the result into a Point object
        Point size = new Point();
        display.getSize(size);
        width = size.x;
        height = size.y;
        mBluetoothStatus = (TextView)findViewById(R.id.bluetooth_status);
        mScanBtn = (Button)findViewById(R.id.scan);
        mOffBtn = (Button)findViewById(R.id.off);
        mDiscoverBtn = (Button)findViewById(R.id.discover);
        mListPairedDevicesBtn = (Button)findViewById(R.id.paired_btn);
        mShowGraphBtn = (Button)findViewById((R.id.display_btn));
        mBTArrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);    //Array of type string
        mBTAdapter = BluetoothAdapter.getDefaultAdapter(); // get a handle on the bluetooth radio

        mDevicesListView = (ListView)findViewById(R.id.devices_list_view);
        mDevicesListView.setAdapter(mBTArrayAdapter); // assign model to view
        mDevicesListView.setOnItemClickListener(mDeviceClickListener);

        // Ask for location permission if not already allowed
        if(ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 1);

        //Message from run() in ConnectedThread mHandler.obtain message
        mHandler = new Handler(Looper.getMainLooper()){
            @Override
            public void handleMessage(Message msg){
                if(msg.what == MESSAGE_READ){
                    String readMessage = null;
                    try {
                        t = 0;
                        readMessage = new String((byte[]) msg.obj, "UTF-8");
                        System.out.println(readMessage);
                        while (readMessage.charAt(t) != 'a') {
                            t++;
                            if(t == 22){
                                break;
                            }
                        }
                        for(i = 0; i < 3; i++) {
                           // if(readMessage.charAt(t) == 'a' | readMessage.charAt(t) <= 9 | readMessage.charAt(t) == '-'){
                            if (readMessage.charAt(t) == 'a') {         //Next char is a number
                                t++;
                                if(Character.getNumericValue(readMessage.charAt(t)) >= 0 && Character.getNumericValue(readMessage.charAt(t)) <= 9 &&
                                        Character.getNumericValue(readMessage.charAt(t + 1)) >= 0 && Character.getNumericValue(readMessage.charAt(t + 1)) <= 9 &&
                                        Character.getNumericValue(readMessage.charAt(t + 2)) >= 0 && Character.getNumericValue(readMessage.charAt(t + 2)) <= 9 &&
                                        Character.getNumericValue(readMessage.charAt(t + 3)) >= 0 && Character.getNumericValue(readMessage.charAt(t + 3)) <= 9 &&
                                        Character.getNumericValue(readMessage.charAt(t + 4)) >= 0 && Character.getNumericValue(readMessage.charAt(t + 4)) <= 9 ) {
                                    bluetoothVal4 = readMessage.charAt((t));
                                    number10000 = (Character.getNumericValue(bluetoothVal4)) * 10000;
                                    t++;
                                    bluetoothVal3 = readMessage.charAt((t));
                                    number1000 = (Character.getNumericValue(bluetoothVal3)) * 1000;
                                    t++;
                                    bluetoothVal2 = readMessage.charAt((t));
                                    number100 = (Character.getNumericValue(bluetoothVal2)) * 100;
                                    t++;
                                    bluetoothVal1 = readMessage.charAt((t));
                                    number10 = (Character.getNumericValue(bluetoothVal1)) * 10;
                                    t++;
                                    bluetoothVal0 = readMessage.charAt((t));
                                    number1 = Character.getNumericValue(bluetoothVal0);
                                    totalA2DVal = number10000 + number1000 + number100 + number10 + number1;
                                    if (totalA2DVal > 0 && totalA2DVal <= 99999) {
                                        for (int k = 0; k < 499; k++) {
                                            A2DVal[k] = A2DVal[k + 1];      //Shift all values 1 to the right. Rolling buffer
                                        }
                                        A2DVal[499] = (((double) totalA2DVal / -3.0 + 1400));
                                        if(A2DVal[499] <= 60)
                                        {
                                            A2DVal[499]  = 60;
                                        }
                                        else if(A2DVal[499]  >= 1900)
                                        {
                                            A2DVal[499]  = 1900;
                                        }
                                   //     System.out.println("A2DVal[2247] " + A2DVal[2247]);
                                        //  bufferFlag = 1;
                                        //if(GameScreen.A2DVal[j] < 40) {
                                        // GameScreen.A2DVal[j] = 40;
                                        //}
                                        //   j++;
                                        // if (j > 3499)
                                        //   j = 0;
                                    }
                                }
                            }
                        }
                    } catch (UnsupportedEncodingException e) {
                        e.printStackTrace();
                    }
                }

                if(msg.what == CONNECTING_STATUS){
                    if(msg.arg1 == 1)
                        mBluetoothStatus.setText("Connected to Device: " + msg.obj);
                    else
                        mBluetoothStatus.setText("Connection Failed");
                }
            }
        };

        if (mBTArrayAdapter == null) {
            // Device does not support Bluetooth
            mBluetoothStatus.setText("Status: Bluetooth not found");
            Toast.makeText(getApplicationContext(),"Bluetooth device not found!",Toast.LENGTH_SHORT).show();
        }
        mScanBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                bluetoothOn();
            }
        });

        mOffBtn.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v){
                bluetoothOff();
            }
        });

        mListPairedDevicesBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v){
                listPairedDevices();
            }
        });

        mShowGraphBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { showGraph(); }
        });

        mDiscoverBtn.setOnClickListener(new View.OnClickListener(){
            @Override
            public void onClick(View v){
                discover();
            }
        });


        boolean isLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if(isLandscape == true)
            landscape = 1;
        else if(isLandscape == false)
            landscape = 0;
        int frameBufferWidth = isLandscape ? 5000 : 3500;
        int frameBufferHeight = isLandscape ? 3500 : 5000;
        Bitmap frameBuffer = Bitmap.createBitmap(frameBufferWidth,
                frameBufferHeight, Config.RGB_565);



        DisplayMetrics displaymetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(displaymetrics);

        float scaleX = (float) frameBufferWidth
                / displaymetrics.widthPixels;
        float scaleY = (float) frameBufferHeight
                / displaymetrics.heightPixels;

        renderView = new AndroidFastRenderView(this, frameBuffer);
        graphics = new AndroidGraphics(getAssets(), frameBuffer);
        fileIO = new AndroidFileIO(this);
        audio = new AndroidAudio(this);
        input = new AndroidInput(this, renderView, scaleX, scaleY);
        screen = getStartScreen();

    }

    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Graphics g = this.getGraphics();
        if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            landscape = 1;
            Intent intent3 = new Intent(this.getApplicationContext(), GaspSemg.class);
            this.startActivity(intent3);
        } else if (newConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            landscape = 0;
            Intent intent4 = new Intent(this.getApplicationContext(), GaspSemg.class);
            this.startActivity(intent4);
        }
    }



    private void bluetoothOn(){
        if (!mBTAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            mBluetoothStatus.setText("Bluetooth enabled");
            Toast.makeText(getApplicationContext(),"Bluetooth turned on",Toast.LENGTH_SHORT).show();

        }
        else{
            Toast.makeText(getApplicationContext(),"Bluetooth is already on", Toast.LENGTH_SHORT).show();
        }
    }

    // Enter here after user selects "yes" or "no" to enabling radio
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent Data) {
        // Check which request we're responding to
        super.onActivityResult(requestCode, resultCode, Data);
        if (requestCode == REQUEST_ENABLE_BT) {
            // Make sure the request was successful
            if (resultCode == RESULT_OK) {
                // The user picked a contact.
                // The Intent's data Uri identifies which contact was selected.
                mBluetoothStatus.setText("Enabled");
            } else
                mBluetoothStatus.setText("Disabled");
        }
    }

    private void bluetoothOff(){
        mBTAdapter.disable(); // turn off
        mBluetoothStatus.setText("Bluetooth disabled");
        Toast.makeText(getApplicationContext(),"Bluetooth turned Off", Toast.LENGTH_SHORT).show();
    }

    private void discover(){
        // Check if the device is already discovering
        if(mBTAdapter.isDiscovering()){
            mBTAdapter.cancelDiscovery();
            Toast.makeText(getApplicationContext(),"Discovery stopped",Toast.LENGTH_SHORT).show();
        }
        else{
            if(mBTAdapter.isEnabled()) {
                mBTArrayAdapter.clear(); // clear items
                mBTAdapter.startDiscovery();
                Toast.makeText(getApplicationContext(), "Discovery started", Toast.LENGTH_SHORT).show();
                registerReceiver(blReceiver, new IntentFilter(BluetoothDevice.ACTION_FOUND));
            }
            else{
                Toast.makeText(getApplicationContext(), "Bluetooth not on", Toast.LENGTH_SHORT).show();
            }
        }
    }

    final BroadcastReceiver blReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if(BluetoothDevice.ACTION_FOUND.equals(action)){
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                // add the name to the list
                mBTArrayAdapter.add(device.getName() + "\n" + device.getAddress());
                mBTArrayAdapter.notifyDataSetChanged();
            }
        }
    };

    private void listPairedDevices(){
        mBTArrayAdapter.clear();
        mPairedDevices = mBTAdapter.getBondedDevices();
        if(mBTAdapter.isEnabled()) {
            // put it's one to the adapter
            for (BluetoothDevice device : mPairedDevices)
                mBTArrayAdapter.add(device.getName() + "\n" + device.getAddress());

            Toast.makeText(getApplicationContext(), "Show Paired Devices", Toast.LENGTH_SHORT).show();
        }
        else
            Toast.makeText(getApplicationContext(), "Bluetooth not on", Toast.LENGTH_SHORT).show();
    }

    private AdapterView.OnItemClickListener mDeviceClickListener = new AdapterView.OnItemClickListener() {
        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {

            if(!mBTAdapter.isEnabled()) {
                Toast.makeText(getBaseContext(), "Bluetooth not on", Toast.LENGTH_SHORT).show();
                return;
            }

            mBluetoothStatus.setText("Connecting...");
            // Get the device MAC address, which is the last 17 chars in the View
            String info = ((TextView) view).getText().toString();
            final String address = info.substring(info.length() - 17);
            final String name = info.substring(0,info.length() - 17);

            // Spawn a new thread to avoid blocking the GUI one
            new Thread()
            {
                @Override
                public void run() {
                    boolean fail = false;

                    BluetoothDevice device = mBTAdapter.getRemoteDevice(address);

                    try {
                        mBTSocket = createBluetoothSocket(device);
                    } catch (IOException e) {
                        fail = true;
                        Toast.makeText(getBaseContext(), "Socket creation failed", Toast.LENGTH_SHORT).show();
                    }
                    // Establish the Bluetooth socket connection.
                    try {
                        mBTSocket.connect();
                    } catch (IOException e) {
                        try {
                            fail = true;
                            mBTSocket.close();
                            mHandler.obtainMessage(CONNECTING_STATUS, -1, -1)
                                    .sendToTarget();
                        } catch (IOException e2) {
                            //insert code to deal with this
                            Toast.makeText(getBaseContext(), "Socket creation failed", Toast.LENGTH_SHORT).show();
                        }
                    }
                    if(!fail) {
                        mConnectedThread = new ConnectedThread(mBTSocket, mHandler);
                        mConnectedThread.start();

                        mHandler.obtainMessage(CONNECTING_STATUS, 1, -1, name)
                                .sendToTarget();
                    }
                }
            }.start();
        }
    };

    /*
    Investigate createBluetoothSocket more.
     */
    private BluetoothSocket createBluetoothSocket(BluetoothDevice device) throws IOException {
        //This function works with the try-catch block commented out
        /*
        try {
            final Method m = device.getClass().getMethod("createInsecureRfcommSocketToServiceRecord", UUID.class);
            return (BluetoothSocket) m.invoke(device, BT_MODULE_UUID);
        } catch (Exception e) {
            Log.e(TAG, "Could not create Insecure RFComm Connection",e);
        }
*/
        return  device.createRfcommSocketToServiceRecord(BT_MODULE_UUID);
    }

    private void showGraph(){
        setContentView(renderView);
        // Intent intent = new Intent(this, MuscleVolt.class);
        //startActivity(intent);
    }

    @Override
    public void onResume() {
        super.onResume();
        screen.resume();
        renderView.resume();
    }
    @Override
    public void onPause() {
        super.onPause();
        renderView.pause();
        screen.pause();
   //     System.gc();
        //screen.dispose();
        if (isFinishing())
            screen.dispose();
    }
    public Input getInput() {
        return input;
    }

    public FileIO getFileIO() {
        return fileIO;
    }

    public Graphics getGraphics() {
        return graphics;
    }

    public Audio getAudio() {
        return audio;
    }
    public void setScreen(Screen screen) {
        if (screen == null)
            throw new IllegalArgumentException("Screen must not be null");

        this.screen.pause();
        this.screen.dispose();
        screen.resume();
        screen.update(0, getBaseContext());
        this.screen = screen;
    }
    public Screen getCurrentScreen() {
        return screen;
    }


}
