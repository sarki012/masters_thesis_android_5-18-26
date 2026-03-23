package com.esark.gasp;

import static com.esark.framework.AndroidGame.signalBufferLen;
import static com.esark.gasp.GameScreen.PSDArray;
import static com.esark.gasp.GameScreen.eventArray;
import static com.esark.gasp.GameScreen.eventCount;
import static com.esark.gasp.GameScreen.len;
import static com.esark.gasp.GameScreen.smoothedRMS;

import android.content.Context;
import android.util.Log;

import com.esark.framework.Game;
import com.esark.framework.Graphics;
import com.esark.framework.Input;
import com.esark.framework.Screen;

import java.util.List;

public class GameScreenEvent extends Screen implements Input {
    Context context = null;
    private static final String TAG = "GameScreenEvent";
    int xStart = 0, xStop = 0;
    public static double[] A2DVal = new double[signalBufferLen];
    double[] psd = new double[2048];
    double[] sineWave = new double[2048];
    double[] psdResult = new double[2048];
    
    // Field to track which event we are currently viewing
    public int selectedEventIdx = 0;

    private static final int INVALID_POINTER_ID = -1;
    private int mActivePointerId = INVALID_POINTER_ID;

    public GameScreenEvent(Game game) {
        super(game);
    }

    @Override
    public void update(float deltaTime, Context context) {
        List<TouchEvent> touchEvents = game.getInput().getTouchEvents();
        updateRunning(touchEvents, deltaTime, context);
    }

    private void updateRunning(List<TouchEvent> touchEvents, float deltaTime, Context context) {
        Graphics g = game.getGraphics();
        
        // Background loading should ideally be done once, but we'll leave it for now or ensure it's referenced
        if (Assets.gaspMainBackground == null) {
            Assets.gaspMainBackground = g.newPixmap("gaspMainBackground.png", Graphics.PixmapFormat.ARGB4444);
        }
        
        len = touchEvents.size();
        
        for (int i = 0; i < len; i++) {
            TouchEvent event = touchEvents.get(i);
            if (event.type == TouchEvent.TOUCH_UP || event.type == TouchEvent.TOUCH_DRAGGED || event.type == TouchEvent.TOUCH_DOWN) {
                if (event.x > 25 && event.x < 675 && event.y > 2583 && event.y < 2780) {
                    game.setScreen(new GameScreenEventLog(game));
                    return;
                }
            }
        }
        g.drawPortraitPixmap(Assets.gaspMainBackground, 0, 0);

        // Safety check for the index - updated to 50
        int idx = selectedEventIdx;
        if (idx < 0) idx = 0;
        if (idx >= 50) idx = 49;

        // Draw Black Line Signal
        xStart = 1600;
        int xStep = 2;
        xStop = xStart - xStep;
        for (int n = signalBufferLen - 1; n > 1; n--) {
            g.drawBlackLine(xStart, (int) eventArray[idx][n], xStop, (int) (eventArray[idx][n - 2]), 0);
            xStart = xStop;
            xStop -= xStep;
            if (xStop <= 165) {
                break;
            }
        }

        // Draw Blue RMS Lines
        if (smoothedRMS != null && smoothedRMS.length > 2) {
            xStart = 1600;
            xStop = 1585;
            for (int n = smoothedRMS.length - 1; n > 1; n--) {
                double val1 = smoothedRMS[n];
                double val2 = smoothedRMS[n-1];
                if(val1 > 423) val1 = 423;
                if(val2 > 423) val2 = 423;
                if(val1 < 273) val1 = 273;
                if(val2 < 273) val2 = 273;
                g.drawBlueLine(xStart, (int) (3 * val1), xStop, (int) (3 * val2), 0);
                xStart = xStop;
                xStop -= 15;
                if (xStop <= 180) {
                    break;
                }
            }
        }
        
        // Draw Red PSD Lines
        xStart = 170;
        xStop = 180;
        for (int i = 1; i < 2048; i++) {
            g.drawRedLine(xStart, (int) PSDArray[idx][i - 1] - 1695, xStop, (int) PSDArray[idx][i] - 1695, 0);
            xStart = xStop;
            xStop += 10;
            if(xStop >= 1600){
                break;
            }
        }
    }

    @Override
    public void present ( float deltaTime){ }
    @Override
    public void pause () { }
    @Override
    public void resume () { }
    @Override
    public void dispose () { }
    @Override
    public boolean isTouchDown(int pointer) { return false; }
    @Override
    public int getTouchX(int pointer) { return 0; }
    @Override
    public int getTouchY(int pointer) { return 0; }
    @Override
    public float getAccelX() { return 0; }
    @Override
    public float getAccelY() { return 0; }
    @Override
    public float getAccelZ() { return 0; }
    @Override
    public List<TouchEvent> getTouchEvents() { return null; }
}
