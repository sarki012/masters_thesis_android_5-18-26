package com.esark.gasp;

import static com.esark.framework.AndroidGame.signalBufferLen;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.esark.framework.AndroidAudio;
import com.esark.framework.Game;
import com.esark.framework.Graphics;
import com.esark.framework.Input;
import com.esark.framework.Input.TouchEvent;
import com.esark.framework.Screen;
import com.esark.framework.Sound;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

public class GameScreen extends Screen implements Input {
    boolean isAlertPlaying = false;
    Sound alertSound;
    Context context = null;
    private static final String TAG = "GameScreen";
    int xStart = 0, xStop = 0;
    double xStartPSD = 0, xStopPSD = 0;
    //public static double[] A2DVal = new double[3500];
    public static double[] A2DVal = new double[signalBufferLen];   //was 1435
    public double[] A2DValCopy = new double[signalBufferLen];
    public static double[] movingRMS = new double[signalBufferLen];
    public static double[] smoothedRMS = new double[signalBufferLen];
    double rmsScale = 0;
    double[] psd = new double[2048];

    double[] sineWave = new double[2048];
    public static double[][] eventArray = new double [50][2048];
    public static double[] lastEventArray = new double[2048];
    double[] psdResult = new double[2048];
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
    // Add these to your class member variables


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

  //  public GameScreenLastEvent gameScreenLastEvent = new GameScreenLastEvent(game);
    //public GameScreenEventLog gameScreenEventLog = new GameScreenEventLog(game);
    @Override
    public void update(float deltaTime, Context context) {
        //framework.input
        List<TouchEvent> touchEvents = game.getInput().getTouchEvents();
        updateRunning(touchEvents, deltaTime, context);
    }
    private void updateRunning(List<TouchEvent> touchEvents, float deltaTime, Context context) {
        Graphics g = game.getGraphics();
        // 1. Draw Background First
        if (Assets.laryngospasmBackgroundMain != null) {
            g.drawPortraitPixmap(Assets.laryngospasmBackgroundMain, 0, 0);
        }

        // 2. Handle Touch Events
        len = touchEvents.size();
        for (int i = 0; i < len; i++) {
            TouchEvent event = touchEvents.get(i);
            if (event.type == TouchEvent.TOUCH_DOWN) {
                if (event.x > 1245 && event.x < 1715 && event.y > 2610 && event.y < 2710) {
                    Intent intent2 = new Intent(context.getApplicationContext(), GaspSemg.class);
                    context.startActivity(intent2);
                    return;
                } else if (event.x > 685 && event.x < 840 && event.y > 2110 && event.y < 2215) {
                    rmsAmpThresh += 5;
                } else if (event.x > 685 && event.x < 840 && event.y > 2220 && event.y < 2325) {
                    rmsAmpThresh -= 5;
                } else if (event.x > 720 && event.x < 1190 && event.y > 2600 && event.y < 2700) {
                    game.setScreen(new GameScreenEventLog(game));
                }
            }
        }

        if (rmsAmpThresh < 0) rmsAmpThresh = 0;
        g.drawText(String.valueOf(eventCount), 570, 2660);
        g.drawText(String.valueOf(rmsAmpThresh), 395, 2235);

        // 3. Thread-Safe Data Copy
        synchronized (A2DVal) {
            if (A2DVal != null && A2DValCopy != null) {
                System.arraycopy(A2DVal, 0, A2DValCopy, 0, Math.min(A2DVal.length, A2DValCopy.length));
            }
        }

        // 4. Draw Raw Signal (Black)
        int rawCenterY = 480;
        xStart = 1600;
        int xStep = 2;
        for (int n = signalBufferLen - 1; n > 0; n--) {
            int y1 = (int) (rawCenterY - (A2DValCopy[n] - 410));
            int y2 = (int) (rawCenterY - (A2DValCopy[n - 1] - 410));
            g.drawBlackLine(xStart, y1, xStart - xStep, y2, 0);
            xStart -= xStep;
            if (xStart <= 165) break;
        }

        // 5. RMS Logic & Drawing (Blue)
        movingRMS = RMSCalculator.calculateMovingRMS(A2DValCopy, 40);
        smoothedRMS = MovingAverageCalculator.calculateMovingAverage(movingRMS, 20);

        int blueCenterY = 1100;
        float rmsYScale = 25.0f;
        int thresholdY = (int) (1900 - (rmsAmpThresh * 2.0f));

        if (smoothedRMS != null && smoothedRMS.length > 2) {
            xStart = 1600;
            for (int n = smoothedRMS.length - 1; n > 1; n--) {
                int y1 = (int) (blueCenterY - (smoothedRMS[n] - 410.0) * rmsYScale);
                int y2 = (int) (blueCenterY - (smoothedRMS[n - 1] - 410.0) * rmsYScale);
                if (y1 < 850) y1 = 850; if (y1 > 1350) y1 = 1350;
                if (y2 < 850) y2 = 850; if (y2 > 1350) y2 = 1350;
                g.drawBlueLine(xStart, y1, xStart - 15, y2, 0);
                xStart -= 15;
                if (xStart <= 180) break;
            }

            // --- FIXED ALERT LOGIC ---
            int latestY = (int) (blueCenterY - (smoothedRMS[smoothedRMS.length - 1] - 410.0) * rmsYScale);
            if (latestY < thresholdY) {
                if (!isAlertPlaying && alertSound != null) {
                    alertSound.play(1.0f);
                    isAlertPlaying = true;
                }
            } else {
                isAlertPlaying = false;
            }
        }

        // 6. PSD Calculation & Drawing (Red)
        if (A2DValCopy != null && A2DValCopy.length >= 256) {
            try {
                PowerSpectralDensityCalculator psdCalc = new PowerSpectralDensityCalculator(A2DValCopy, 10000);
                psdResult = psdCalc.calculatePSD(A2DValCopy, 10000);

                if (psdResult != null && psdResult.length > 1) {
                    float currentXpsd = 170;
                    float xStepPsd = 13.5f;
                    for (int i = 1; i < psdResult.length; i++) {
                        float nextXpsd = 170 + (i * xStepPsd);
                        int py1 = (int) (psdResult[i - 1] * -20 + 3600) - 1695;
                        int py2 = (int) (psdResult[i] * -20 + 3600) - 1695;
                        if (py1 < 1470) py1 = 1470;
                        if (py2 < 1470) py2 = 1470;
                        g.drawRedLine((int) currentXpsd, py1, (int) nextXpsd, py2, 0);
                        currentXpsd = nextXpsd;
                        if (currentXpsd >= 1600) break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "PSD error: " + e.getMessage());
            }
        }

        // 7. Threshold Line
        g.drawRedLine(155, thresholdY, 1590, thresholdY, 0);
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