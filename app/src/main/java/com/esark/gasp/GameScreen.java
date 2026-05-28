package com.esark.gasp;

import static com.esark.framework.AndroidGame.signalBufferLen;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.esark.framework.Game;
import com.esark.framework.Graphics;
import com.esark.framework.Input;
import com.esark.framework.Input.TouchEvent;
import com.esark.framework.Screen;
import com.esark.framework.AndroidAudio;
import com.esark.framework.Sound;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class GameScreen extends Screen implements Input {
    boolean isAlertPlaying = false;
    Sound alertSound;
    int thresholdY = 0;
    Context context = null;
    private static final String TAG = "GameScreen";
    int xStart = 0, xStop = 0;
    double xStartPSD = 0, xStopPSD = 0;
    //public static double[] A2DVal = new double[3500];
    public static volatile double[] A2DVal = new double[signalBufferLen];   //was 1435
    //  public double[] A2DValMean = new double[signalBufferLen];
    public double A2DValMean = 0;

    public static volatile double[] movingRMS = new double[signalBufferLen];
    public static volatile double[] smoothedRMS = new double[signalBufferLen];
    double rmsScale = 0;
    double[] psd = new double[2048];

    double[] sineWave = new double[2048];
    public static double[][] eventArray = new double [50][2048];
    public static double[] lastEventArray = new double[2048];

    // Remove volatile, use final to keep the reference stable
// Ensure the size matches what your PSD calculator actually outputs
    public static volatile double[] psdResult = new double[2048];
    public static double[][] PSDArray = new double[50][2048];
    public static double[] lastEventPSDArray = new double[2048];
    int freq = 0;

    double freqScalar = 100;
    int amplitude = 100;
    int increasingFlag = 1;
    int freqIncreasingFlag = 1;
    int startRecording = 0;
    long startTimeMillis = 0;
    long recDeltaTimeMillis = 0;
    long currentTimeMillis = 0;
    long minutes = 0;
    long seconds = 0;
    long remainingMilliseconds = 0;
    int rmsThresholdTouch = 0;
    int rmsAmpThresh = 100, rmsWidthThresh = 0;
    int leftUpCount = 0, leftDownCount = 0, rightUpCount = 0, rightDownCount = 0;
    private static final double PI = 3.1415927;

    public static final int PSDYVAL = 3850;
    private static final int INVALID_POINTER_ID = -1;
    // The ‘active pointer’ is the one currently moving our object.
    private int mActivePointerId = INVALID_POINTER_ID;
    // public static int len = 0;
    public static int len = 0;
    public static String[] timeStamp = new String[100];
    public static int eventCount = 0;
    public int manualPatientEventUpCount = 0;
    public int rmsWidthThreshTouch = 0;
    public static android.view.View view;

    // Recording and Replay Variables
    public static boolean isRecording = false;
    public static boolean isReplaying = false;
    private static FileOutputStream fos;
    public static PrintWriter writer;
    private static List<Double> replayList = new ArrayList<>();
    private static int replayPosition = 0;
    private String fileName = "sEMG_Data.csv";
    //Constructor
    public GameScreen(Game game) {
        super(game);
        try {
            alertSound = game.getAudio().newSound("ringtone.mp3");
        } catch (Exception e) {
            Log.e(TAG, "Failed to load ringtone.mp3: " + e.getMessage());
            alertSound = null; // Ensure it's null so the check in updateRunning works
        }
    }
    public GameScreenLastEvent gameScreenLastEvent = new GameScreenLastEvent(game);
    public GameScreenEventLog gameScreenEventLog = new GameScreenEventLog(game);
    @Override
    public void update(float deltaTime, Context context) {
        //framework.input
        List<TouchEvent> touchEvents = game.getInput().getTouchEvents();
        updateRunning(touchEvents, deltaTime, context);
    }

    private void updateRunning(List<TouchEvent> touchEvents, float deltaTime, Context context) {
        //updateRunning() contains controller code of our MVC scheme
        Graphics g = game.getGraphics();
        g.drawPortraitPixmap(Assets.laryngospasmBackgroundMain, 0, 0);
        len = touchEvents.size();
        //Check to see if paused
        for (int i = 0; i < len; i++) {
            TouchEvent event = touchEvents.get(i);
            if (event.type == TouchEvent.TOUCH_DRAGGED || event.type == TouchEvent.TOUCH_DOWN) {
                if (event.x > 1245 && event.x < 1715 && event.y > 2610 && event.y < 2710) {
                    //Back to Bluetooth Connect Screen
                    Intent intent2 = new Intent(context.getApplicationContext(), GaspSemg.class);
                    context.startActivity(intent2);
                    return;
                }
                //Start Recording Buttono
                else if (event.x > 45 && event.x < 1240 && event.y > 1240 && event.y < 2100) {
                    //Start
                    startTimeMillis = System.currentTimeMillis();
                    startRecording = 1;
                }
                //////////////////// Left Up Button ////////////////////////////////////////////////
                else if (event.x > 685 && event.x < 840 && event.y > 2110 && event.y < 2215) {
                    //RMS threshold amplitude to trigger event. Left Up Button.
                    rmsThresholdTouch = 1;
                    if (leftUpCount == 0) {       //Flag so we only increment the delay by 5 once per touch
                        rmsAmpThresh += 5;
                        leftUpCount = 1;

                    }
                }
                //////////////////// Left Down Button ////////////////////////////////////////////////
                else if (event.x > 685 && event.x < 840 && event.y > 2220 && event.y < 2325) {
                    //RMS threshold amplitude to trigger event. Left Down Button.
                    rmsThresholdTouch = 1;
                    if (leftDownCount == 0) {       //Flag so we only increment the delay by 5 once per touch
                        rmsAmpThresh -= 5;
                        leftDownCount = 1;
                    }
                }
                //////////////////// Right Up Button ////////////////////////////////////////////////
                else if (event.x > 1560 && event.x < 1715 && event.y > 2110 && event.y < 2215) {
                    //RMS threshold amplitude to trigger event. Left Up Button.
                    rmsWidthThreshTouch = 1;
                    if (rightUpCount == 0) {       //Flag so we only increment the delay by 5 once per touch
                        rmsWidthThresh += 5;
                        rightUpCount = 1;
                    }
                }
                //////////////////// Right Down Button ////////////////////////////////////////////////
                else if (event.x > 1560 && event.x < 1715 && event.y > 2220 && event.y < 2325) {
                    //RMS threshold amplitude to trigger event. Left Down Button.
                    rmsWidthThreshTouch = 1;
                    if (rightDownCount == 0) {       //Flag so we only increment the delay by 5 once per touch
                        rmsWidthThresh -= 5;
                        rightDownCount = 1;
                    }
                } else if (event.x > 720 && event.x < 1190 && event.y > 2600 && event.y < 2700) {
                    //Event Log Screen
                    game.setScreen(gameScreenEventLog);
                } else if (event.x > 1315 && event.x < 1660 && event.y > 2000 && event.y < 2100) {
                    //Stop Now clear events
                    //game.setScreen(gameScreenLastEvent);
                    eventCount = 0;
                } else if (event.x > 10 && event.x < 675 && event.y > 2450 && event.y < 2800) {
                    //Manual Patient Event
                    if (manualPatientEventUpCount == 0 && eventCount < 50) {
                        // Fast array copy instead of loop
                        System.arraycopy(A2DVal, 0, eventArray[eventCount], 0, Math.min(signalBufferLen, 2048));
                        System.arraycopy(psdResult, 0, PSDArray[eventCount], 0, psdResult.length);

                        SimpleDateFormat dateFormat = new SimpleDateFormat("HH:mm:ss");
                        timeStamp[eventCount] = dateFormat.format(new Date());
                        eventCount++;
                        manualPatientEventUpCount = 1;
                    }
                }

                //////////////////////////// Start/Stop/Save Recording /////////////////////////////
                /*  1. Start/Stop: It uses PrintWriter with a BufferedWriter. This is much faster than
                    standard file writing and prevents UI stuttering.
                    2. External Files Dir: It saves the data to Android/data/com.esark.gasp/files/sEMG_Data.csv.
                    This doesn't require extra Android permissions on newer versions.
                    3. Replay Logic: It loads the entire CSV into a List. During the draw loop, it swaps the
                    source of val1 and val2 from the live A2DVal array to the replayList.
                    4. Looping: When replaying, it increments replayPosition every frame, making the recorded
                    signal "slide" across the screen exactly like the real-time one.
                 */
                else if (event.x > 1600 && event.x < 1700 && event.y > 1330 && event.y < 1600) {
                    // --- Start/Stop/Save Sample ---
                    if (!isRecording) {
                        // START RECORDING
                        try {
                            /* File path = context.getExternalFilesDir(null): It finds the safe, private
                            folder on the Android device where the app is allowed to save files
                            (usually /Android/data/com.esark.gasp/files).
                             */
                            File path = context.getExternalFilesDir(null);
                            File file = new File(path, fileName);
                            /* fos = new FileOutputStream(file, false): It opens the file for writing.
                            The false tells Android to overwrite the file if it already exists
                            (starting a fresh recording).
                             */
                            fos = new FileOutputStream(file, false); // false = overwrite
                            /* The "Writer" Chain: It creates a PrintWriter wrapped in a BufferedWriter.
                            Why this is important: At 2000Hz, writing to a disk is very slow. The
                            BufferedWriter collects data in RAM and writes it in "chunks" so your
                            sine wave doesn't stutter or lag while recording.
                             */
                            writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(fos)));
                            /* Flags: It sets isRecording = true (which triggers the logic in your
                            ConnectedThread to start saving samples) and isReplaying = false
                            (to ensure you aren't trying to watch old data while recording new data).
                             */
                            isRecording = true;
                            isReplaying = false; // Stop replaying if we start recording
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    } else {
                        // STOP AND SAVE
                        isRecording = false;
                        if (writer != null) {
                            writer.flush();
                            writer.close();
                            writer = null;
                        }
                    }
                }

                /////////////////////// Replay Recording ///////////////////////////////////////////
                else if (event.x > 1600 && event.x < 1700 && event.y > 1610 && event.y < 1920) {
                    // --- Replay ---
                    if (!isReplaying) {
                        loadReplayData(context);
                    } else {
                        isReplaying = false;
                    }
                }

                if (rmsAmpThresh < 0) {
                    rmsAmpThresh = 0;
                }
                //else if (landscape == 1 && event.x < 100 && event.y > 230)
            } else if (event.type == TouchEvent.TOUCH_UP) {
                // Reset flags on any lift to ensure buttons remain responsive
                leftUpCount = 0;
                leftDownCount = 0;
                rightUpCount = 0;
                rightDownCount = 0;
                manualPatientEventUpCount = 0;
            }
        }

        //   if(landscape == 0) {

        /*
        g.drawRect(1245, 2610, 470, 100, 0);       //Bluetooth Connect
        g.drawRect(45, 2000, 1195, 100, 0);       //Start
        g.drawRect(1315, 2000, 345, 100, 0);       //Stop
      //  g.drawRect(350, 2185, 250, 85, 0);       //Manual RMS Height Above Threshold Text
        g.drawText("50", 395, 2235);    //Manual RMS Height Above Threshold Text
       // g.drawRect(350, 2380, 250, 85, 0);       //Auto RMS Height Threshold Text
        g.drawText("50", 395, 2445);        //Auto RMS Height Threshold Text
        g.drawRect(685, 2110, 155, 105, 0);       //Left Up Button
        g.drawRect(685, 2220, 155, 105, 0);       //Left Down Button
     //   g.drawRect(1240, 2180, 250, 85, 0);       //Manual RMS Width Above Threshold Text
        g.drawText("50", 1330, 2235);       //Manual RMS Width Above Threshold Text
        g.drawRect(1560, 2110, 155, 105, 0);       //Right Up Button
        g.drawRect(1560, 2220, 155, 105, 0);       //Right Down Button
        g.drawRect(720, 2600, 470, 100, 0);       //Event Log
        g.drawRect(25, 2580, 650, 200, 0);       //Manual Patient Event

     //   g.drawRect(725, 2400, 285, 150, 0);       //True Positive
        g.drawText("50", 880, 2480);    //True Positive Text
     //   g.drawRect(1055, 2400, 285, 150, 0);       //False Positive
        g.drawText("50", 1235, 2480);       //False Positive Text
     //   g.drawRect(1400, 2400, 285, 150, 0);       //False Negative
        g.drawText("50", 1560, 2480);       //False Negative Text
*/
        g.drawRect(1600, 1330, 100, 270, 0);       //Start/Stop Save a Sample
        g.drawRect(1600, 1610, 100, 310, 0);       //Replay


        String eventCountStr = String.valueOf(eventCount);
        g.drawText(eventCountStr, 570, 2660);
        ////////////////// Start / Stop Recording //////////////////////////////////////////
        if (startRecording == 0) {
            recDeltaTimeMillis = 0;
            minutes = 0;
            seconds = 0;
            remainingMilliseconds = 0;
            String formattedTime = String.format("%02d:%02d:%03d", minutes, seconds, remainingMilliseconds);
            g.drawText(formattedTime, 840, 2070);
        } else if (startRecording == 1) {
            currentTimeMillis = System.currentTimeMillis();
            recDeltaTimeMillis = (int) (currentTimeMillis - startTimeMillis);
            minutes = (int) recDeltaTimeMillis / 60000;
            seconds = (int) recDeltaTimeMillis / 1000;
            remainingMilliseconds = (int) recDeltaTimeMillis % 1000;
            String formattedTime = String.format("%02d:%02d:%03d", minutes, seconds, remainingMilliseconds);
            g.drawText(formattedTime, 840, 2070);
        }

        //////////////////// RMS Threshold to Trigger Event //////////////////////////////////
        if (rmsThresholdTouch == 0) {
            g.drawText("95", 395, 2235);    //Manual RMS Height Above Threshold Text
        } else if (rmsThresholdTouch == 1) {
            String rmsAmpThreshStr = String.valueOf(rmsAmpThresh);
            g.drawText(rmsAmpThreshStr, 395, 2235);    //Manual RMS Height Above Threshold Text

        }

        //////////////////////////////////////////////////////////////////////////////////////

        //////////////////// Manual RMS Width Above Threshold to Trigger Event //////////////////////
        if (rmsWidthThresh == 0) {
            g.drawText("0", 1330, 2235);    //Manual RMS Height Above Threshold Text
        } else if (rmsWidthThresh == 1) {
            String rmsWidthThreshStr = String.valueOf(rmsWidthThresh);
            g.drawText(rmsWidthThreshStr, 1330, 2235);    //Manual RMS Height Above Threshold Text
        }

        ////////////////////////////////////////////////////////////////////////
        // 4. Draw Raw Signal (Black)
        int screenCenterY = 460;
        int topLimit = 230;
        int bottomLimit = 690;

        // Calculate dynamic baseline to center the signal regardless of DC offset

        /*double sum = 0;
        int nonZeroCount = 0;
        for (double v : A2DVal) {
            if (v != 0) {
                sum += v;
                nonZeroCount++;
            }
        }
*/



        //&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&77

        //&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&&77


        // This calculates the actual "resting" center of your data
       // double dataBaseline = (nonZeroCount > 0) ? (sum / nonZeroCount) : 410;

        double dataBaseline = 410;

        // --- FIXED: 2000Hz -> 1Hz Smooth Visualization ---
        xStart = 1600;
        int xStep = 10;      // REDUCED: Smaller steps make the wave narrower and smoother
        int drawSkip = 6;   // INCREASED: Skipping more samples fits more "time" on screen
        float gain = 0.2f;

        // MATH:
        // 1400 pixels / 2 (xStep) = 700 line segments.
        // 700 segments * 6 (drawSkip) = 4200 samples.
        // 4200 samples / 2000Hz = 2.1 seconds of data on screen.
        // You will now see TWO full sine waves across the screen.

        for (int n = signalBufferLen - 1; n > drawSkip; n -= drawSkip) {
            // Calculate Y positions
            int y1 = (int) (screenCenterY - (A2DVal[n] - dataBaseline) * gain);
            int y2 = (int) (screenCenterY - (A2DVal[n - drawSkip] - dataBaseline) * gain);

            // CLAMPING
            if (y1 < topLimit) y1 = topLimit;
            if (y1 > bottomLimit) y1 = bottomLimit;
            if (y2 < topLimit) y2 = topLimit;
            if (y2 > bottomLimit) y2 = bottomLimit;

            // Draw line
            g.drawBlackLine(xStart, y1, xStart - xStep, y2, 0);

            xStart -= xStep;

            // Stop when we hit the left border of the graph
            if (xStart <= 165) break;
        }


        /*
        xStart = 1600;
        int xStep = 2;

        // Gain/Multiplier: 1.0f is standard. If the signal is too large,
        // decrease this (e.g., 0.5f). If too small, increase it (e.g., 2.0f).
        float gain = 0.1f;

        for (int n = signalBufferLen - 1; n > 0; n--) {
            // Formula: Center - (CurrentValue - DynamicBaseline) * Gain
            int y1 = (int) (screenCenterY - (A2DVal[n] - dataBaseline) * gain);
            int y2 = (int) (screenCenterY - (A2DVal[n - 1] - dataBaseline) * gain);

            // CLAMPING: Prevent the line from going off the top (245) or bottom (695)
            if (y1 < topLimit) y1 = topLimit;
            if (y1 > bottomLimit) y1 = bottomLimit;
            if (y2 < topLimit) y2 = topLimit;
            if (y2 > bottomLimit) y2 = bottomLimit;

            g.drawBlackLine(xStart, y1, xStart - xStep, y2, 0);

            xStart -= xStep;
            if (xStart <= 165) break;
        }

*/
        // +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++
        /*
        for (int n = signalBufferLen - 1; n > 0; n--) {
            // Formula: Center - (CurrentValue - DynamicBaseline) * Gain
            int y1 = (int) (screenCenterY - (A2DVal[n] - dataBaseline) * gain);
            int y2 = (int) (screenCenterY - (A2DVal[n - 1] - dataBaseline) * gain);

            // CLAMPING: Prevent the line from going off the top (245) or bottom (695)
            if (y1 < topLimit) y1 = topLimit;
            if (y1 > bottomLimit) y1 = bottomLimit;
            if (y2 < topLimit) y2 = topLimit;
            if (y2 > bottomLimit) y2 = bottomLimit;

            g.drawBlackLine(xStart, y1, xStart - xStep, y2, 0);

            xStart -= xStep;
            if (xStart <= 165) break;
        }
*/

        // +++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++

        int latestY = 0;
        if (smoothedRMS.length > 2) {
            xStart = 1600;
            // Target center for the blue line
            //  int blueCenterY = 800;
            int blueCenterY = 1300;

            //double rmsBaseline = 410.0;
            //   double rmsBaseline = 1500.0;

            // REDUCED SCALE: 1000.0f was too high, causing it to hit the clamp instantly.
            // Try 20.0f for visible fluctuations.
            // float rmsYScale = 10.0f;
            float rmsYScale = 0.3f;

            for (int n = smoothedRMS.length - 1; n > 1; n--) {
                // INVERSION MATH:
                // blueCenterY MINUS (difference) moves the line UP as signal strength increases
                int y1 = (int) (blueCenterY - smoothedRMS[n] * rmsYScale);
                int y2 = (int) (blueCenterY - smoothedRMS[n - 1] * rmsYScale);
                latestY = y2;

                //%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%
                // %%%%%%%%%%%%%%%%%%%% Sound Code %%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%
                // Use 1100 as the baseline (the center of your blue RMS graph)
                // Subtracting the threshold makes it move UP as the value increases
                //thresholdY = (int) (1900 - (rmsAmpThresh * 2.0f));
                thresholdY = (int) (1050 - (rmsAmpThresh * 2.0f));
                // int thresholdY = (int) (1100 - (rmsAmpThresh * 2.0f));

                // thresholdY = (int) (1000 - (rmsAmpThresh * 1.0f));

                // Clamping to keep it within the same bounds as your blue line (869 to 1308)
                //if (thresholdY < 869) thresholdY = 869;
                //
                //  if (thresholdY > 1308) thresholdY = 1308;

                g.drawRedLine(155, thresholdY, 1590, thresholdY, 0);

                // g.drawRedLine(155, (rmsAmpThresh*100/445 + 877), 1590, (rmsAmpThresh*100/445 + 877), 0);
                // --- FIXED ALERT LOGIC ---

                // int latestY = (int) (blueCenterY - (smoothedRMS[smoothedRMS.length - 1] - 410.0) * rmsYScale);

                //%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%
               // latestY = (int) (blueCenterY - smoothedRMS[0] * rmsYScale);

                //int y1 = (int) (blueCenterY - (smoothedRMS[n] - rmsBaseline) * rmsYScale);
                //int y2 = (int) (blueCenterY - (smoothedRMS[n - 1] - rmsBaseline) * rmsYScale);

                // Clamping: Keep the line within the visible graph box (869 to 1308)
                if (y1 < 869) y1 = 869;
                if (y1 > 1308) y1 = 1308;
                if (y2 < 869) y2 = 869;
                if (y2 > 1308) y2 = 1308;

                // Draw the blue line
                // Use a consistent step of 15 pixels so the wave is readable
                g.drawBlueLine(xStart, y1, xStart - 2, y2, 0);

                xStart -= 2;

                // Stop drawing when hitting the left edge
                if (xStart <= 180) {
                    break;
                }

            }

            // Get the very latest Y position
            latestY = (int) (blueCenterY - smoothedRMS[smoothedRMS.length - 1] * rmsYScale);
            if (latestY < thresholdY) {
                if (!isAlertPlaying && alertSound != null) {
                    alertSound.play(5.0f);
                    isAlertPlaying = true;
                }
            } else {
                isAlertPlaying = false;
            }
            A2DValMean = 0;
        }

        // --- 2. PSD Drawing Logic ---
        // Start at i=1 to capture the 2Hz signal (Bin 0 is DC offset, usually skipped)
        float currentXpsd = 170;

        // Target: 250Hz at x=861.
        // 250Hz is approx bin 51. (861-170)/51 = ~13.5
        //float xStepPsd = 13.5f;
        float xStepPsd = 2.0f;

        for (int i = 1; i < psdResult.length; i++) {
            float nextXpsd = 170 + (i * xStepPsd);

            // We subtract 1200 instead of 1695 to keep the baseline around y=1600
            g.drawRedLine((int) currentXpsd, (int) psdResult[i - 1] - 1695, (int) nextXpsd, (int) psdResult[i] - 1695, 0);

            currentXpsd = nextXpsd;

            // Stop at the right edge of the graph
            if (currentXpsd >= 1600) {
                break;
            }
        }

        // To make the screen show the replayed data instead of the live data when in
        // Replay mode, modify your drawing loop at the bottom of GameScreen.java:
        // Inside the drawing loop in GameScreen
        for (int n = signalBufferLen - 1; n > drawSkip; n -= drawSkip) {
            double val1, val2;

            if (isReplaying && replayPosition < replayList.size() - signalBufferLen) {
                // Use data from the loaded file
                val1 = replayList.get(replayPosition + n);
                val2 = replayList.get(replayPosition + (n - drawSkip));
            } else {
                // Use live Bluetooth data
                val1 = A2DVal[n];
                val2 = A2DVal[n - drawSkip];
            }

            int y1 = (int) (screenCenterY - (val1 - dataBaseline) * gain);
            int y2 = (int) (screenCenterY - (val2 - dataBaseline) * gain);

            // ... rest of your drawBlackLine and Clamping code ...
        }

        // Increment replay position to make it move
        if (isReplaying) {
            replayPosition += 10; // Speed of replay (adjust based on look)
            if (replayPosition >= replayList.size() - signalBufferLen) {
                replayPosition = 0; // Loop replay
            }
        }
    }

    /////////////// LoadReplayData Helper Method ///////////////////////////////////////////////////
    private void loadReplayData(Context context) {
        replayList.clear();
        replayPosition = 0;
        try {
            File path = context.getExternalFilesDir(null);
            File file = new File(path, fileName);
            BufferedReader br = new BufferedReader(new FileReader(file));
            String line;
            while ((line = br.readLine()) != null) {
                replayList.add(Double.parseDouble(line));
            }
            br.close();
            if (!replayList.isEmpty()) {
                isReplaying = true;
                isRecording = false;
            }
        } catch (IOException | NumberFormatException e) {
            e.printStackTrace();
        }
    }


    @Override
    public void present ( float deltaTime){
        Graphics g = game.getGraphics();
    }

    @Override
    public void pause () {

    }

    @Override
    public void resume () {

    }

    @Override
    public void dispose () {
    }

    @Override
    public boolean isTouchDown(int pointer) {
        return false;
    }

    @Override
    public int getTouchX(int pointer) {
        return 0;
    }

    @Override
    public int getTouchY(int pointer) {
        return 0;
    }

    @Override
    public float getAccelX() {
        return 0;
    }

    @Override
    public float getAccelY() {
        return 0;
    }

    @Override
    public float getAccelZ() {
        return 0;
    }

    @Override
    public List<TouchEvent> getTouchEvents() {
        return null;
    }
}