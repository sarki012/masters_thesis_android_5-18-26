package com.esark.gasp;import static com.esark.gasp.GameScreen.eventCount;
import static com.esark.gasp.GameScreen.timeStamp;

import android.content.Context;
import android.util.Log;

import com.esark.framework.Game;
import com.esark.framework.Graphics;
import com.esark.framework.Input;
import com.esark.framework.Input.TouchEvent;
import com.esark.framework.Screen;

import java.util.List;

public class GameScreenEventLog extends Screen implements Input {
    // --- 1. UNIFIED GRID CONSTANTS ---
    // Making these constants ensures update and present always use the same math
    private static final int COLS = 4;
    private static final int ROWS = 12;
    private static final int START_X = 65;
    private static final int START_Y = 200;
    private static final int BTN_W = 350;
    private static final int BTN_H = 100;
    private static final int SPACING_X = 50;
    private static final int SPACING_Y = 30;

    public GameScreenEventLog(Game game) {
        super(game);
        // Load assets once
        loadAssets();
    }

    private void loadAssets() {
        Graphics g = game.getGraphics();
        if (g == null) return;
        try {
            // Use ARGB4444 to save 50% memory
            if (Assets.eventLogBackground == null) {
                Assets.eventLogBackground = g.newPixmap("eventLogBackground.png", Graphics.PixmapFormat.ARGB4444);
            }
            if (Assets.eventLogButton == null) {
                Assets.eventLogButton = g.newPixmap("eventLogButton.png", Graphics.PixmapFormat.ARGB4444);
            }
        } catch (Exception e) {
            Log.e("EventLog", "Memory Error loading pixmaps: " + e.getMessage());
        }
    }

    @Override
    public void update(float deltaTime, Context context) {
        List<TouchEvent> touchEvents = game.getInput().getTouchEvents();
        if (touchEvents == null) return;

        int tLen = touchEvents.size();
        for (int i = 0; i < tLen; i++) {
            TouchEvent event = touchEvents.get(i);
            if (event.type == TouchEvent.TOUCH_UP) {
                // Back button to Start Screen
                if (event.x > 25 && event.x < 675 && event.y > 2583 && event.y < 2780) {
                    game.setScreen(game.getStartScreen());
                    return;
                }

                // Grid detection
                if (timeStamp == null) return;
                int limit = Math.min(eventCount, timeStamp.length);
                limit = Math.min(limit, COLS * ROWS);

                for (int j = 0; j < limit; j++) {
                    int row = j / COLS;
                    int col = j % COLS;
                    int x = START_X + col * (BTN_W + SPACING_X);
                    int y = START_Y + row * (BTN_H + SPACING_Y);

                    if (event.x > x && event.x < x + BTN_W && event.y > y && event.y < y + BTN_H) {
                        // FIX: Use the EXISTING GameScreen instance instead of 'new GameScreen'
                        // This prevents the OutOfMemory crash
                        GameScreen gs = GameScreen.liveScreen;
                        if (gs == null) {
                            gs = new GameScreen(game);
                        }

                        gs.loadSpecificEvent(j, context);
                        game.setScreen(gs);
                        return;
                    }
                }
            }
        }
    }

    @Override
    public void present(float deltaTime) {
        Graphics g = game.getGraphics();
        if (g == null) return;

        // Reload assets if Android cleared them from RAM
        if (Assets.eventLogBackground == null || Assets.eventLogButton == null) {
            loadAssets();
        }

        // 1. Draw Background
        if (Assets.eventLogBackground != null) {
            g.drawPortraitPixmap(Assets.eventLogBackground, 0, 0);
        }

        // 2. Safety Check for empty logs
        if (timeStamp == null || eventCount == 0) {
            g.drawText("No Events", 170, 400);
            return;
        }

        // 3. Boundary Calculation
        int displayLimit = Math.min(eventCount, timeStamp.length);
        displayLimit = Math.min(displayLimit, COLS * ROWS);

        // 4. Draw Grid
        if (Assets.eventLogButton != null) {
            for (int i = 0; i < displayLimit; i++) {
                int row = i / COLS;
                int col = i % COLS;
                int x = START_X + col * (BTN_W + SPACING_X);
                int y = START_Y + row * (BTN_H + SPACING_Y);

                g.drawEventLogButtonPixmap(Assets.eventLogButton, x, y);

                // NULL STRING SAFETY
                String label = timeStamp[i];
                if (label != null) {
                    // Optimized Centering for 350x100 button
                    g.drawText(label, x + 60, y + 70);
                }
            }
        }
    }

    @Override public void resume() {
        System.gc(); // Clear memory from recording session
        loadAssets();
    }

    @Override public void pause() {}
    @Override public void dispose() {}
    @Override public boolean isTouchDown(int pointer) { return false; }
    @Override public int getTouchX(int pointer) { return 0; }
    @Override public int getTouchY(int pointer) { return 0; }
    @Override public float getAccelX() { return 0; }
    @Override public float getAccelY() { return 0; }
    @Override public float getAccelZ() { return 0; }
    @Override public List<TouchEvent> getTouchEvents() { return null; }
}
