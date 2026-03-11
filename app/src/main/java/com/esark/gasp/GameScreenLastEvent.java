package com.esark.gasp;

//import static com.esark.gasp.GameScreen.lastEventArray;
import static com.esark.gasp.GameScreen.lastEventArray;
import static com.esark.gasp.GameScreen.lastEventPSDArray;
import static com.esark.gasp.GameScreen.len;
import static java.lang.Math.sin;

import android.content.Context;
import android.content.Intent;

import com.esark.framework.Game;
import com.esark.framework.Graphics;
import com.esark.framework.Input;
import com.esark.framework.Screen;

import java.util.List;

public class GameScreenLastEvent extends Screen implements Input {
    Context context = null;

    int xStart = 0, xStop = 0;
    //public static double[] A2DVal = new double[3500];
    public static double[] A2DVal = new double[3500];
    double[] psd = new double[2048];

    double[] sineWave = new double[2048];

    double[] psdResult = new double[2048];

    private static final int INVALID_POINTER_ID = -1;
    // The ‘active pointer’ is the one currently moving our object.
    private int mActivePointerId = INVALID_POINTER_ID;


    //Constructor
    public GameScreenLastEvent(Game game) {
        super(game);
    }

    @Override
    public void update(float deltaTime, Context context) {
        //framework.input
        List<TouchEvent> touchEvents = game.getInput().getTouchEvents();
        updateRunning(touchEvents, deltaTime, context);
    }

    private void updateRunning(List<TouchEvent> touchEvents, float deltaTime, Context context) {
        //updateRunning() contains controller code of our MVC scheme
        Graphics g = game.getGraphics();
        Assets.lastEventBackground = g.newPixmap("lastEventBackground.png", Graphics.PixmapFormat.ARGB4444);
        len = touchEvents.size();
        //Check to see if paused
        for (int i = 0; i < len; i++) {
            TouchEvent event = touchEvents.get(i);
           // if (event.type == TouchEvent.TOUCH_UP) {
           // }
            if (event.type == TouchEvent.TOUCH_UP || event.type == TouchEvent.TOUCH_DRAGGED || event.type == TouchEvent.TOUCH_DOWN) {
                if (event.x > 185 && event.x < 1735 && event.y > 4700 && event.y < 4975) {
                    //Artifact/PSD Screen
                    game.setScreen(game.getStartScreen());
                }
            }
        }
        g.drawPortraitPixmap(Assets.lastEventBackground, 0, 0);
        xStart = 3370;
        xStop = 3369;
        for (int n = 2047; n > 5; n -= 2) {
            g.drawBlackLine(xStart, (int) lastEventArray[n], xStop, (int) (lastEventArray[n - 2]), 0);
            xStart = xStop;
            xStop-= 5;
            if(xStart <= 380){
                break;
            }
        }

        xStart = 365;
        xStop = 366;
        for (int i = 1; i < psdResult.length; i++) {
            g.drawRedLine(xStart, (int) lastEventPSDArray[i - 1], xStop, (int) lastEventPSDArray[i], 0);
            xStart = xStop;
            xStop += 3;
            if(xStop >= 3370){
                break;
            }
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