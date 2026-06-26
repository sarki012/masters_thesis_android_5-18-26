package com.esark.gasp;

import static com.esark.gasp.GameScreen.eventCount;
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
    private static final int COLS = 4;
    private static final int ROWS = 16;
    private static final int MAX_EVENTS = 64;

    private static final int START_X = 65;
    private static final int START_Y = 120;   // Moved up slightly to ensure bottom row fits
    private static final int BTN_W = 350;
    private static final int BTN_H = 100;
    private static final int SPACING_X = 50;
    private static final int SPACING_Y = 20;  // Tightened to ensure all 16 rows fit in screen height

    public GameScreenEventLog(Game game) {
        super(game);
        loadAssets();
    }

    private void loadAssets() {
        Graphics g = game.getGraphics();
        if (g == null) return;
        try {
            // Using ARGB4444 saves 50% memory compared to the default
            if (Assets.eventLogBackground == null) {
                Assets.eventLogBackground = g.newPixmap("eventLogBackground.png", Graphics.PixmapFormat.ARGB4444);
            }
            if (Assets.eventLogButtonJpeg == null) {
                Assets.eventLogButtonJpeg = g.newPixmap("eventLogButtonJpeg.jpg", Graphics.PixmapFormat.ARGB4444);
            }
        } catch (Exception e) {
            Log.e("EventLog", "Memory Error loading assets: " + e.getMessage());
        }
    }

    @Override
    public void update(float deltaTime, Context context) {
        List<TouchEvent> touchEvents = game.getInput().getTouchEvents();
        if (touchEvents == null) return;

        for (int i = 0; i < touchEvents.size(); i++) {
            TouchEvent event = touchEvents.get(i);
            if (event.type == TouchEvent.TOUCH_UP) {
                // Back button
                if (event.x > 25 && event.x < 675 && event.y > 2583 && event.y < 2780) {
                    game.setScreen(game.getStartScreen());
                    return;
                }

                // Grid detection
                if (timeStamp == null) return;

                // CRITICAL SAFETY: Limit loop to the physical array size and visual grid
                int limit = Math.min(eventCount, timeStamp.length);
                limit = Math.min(limit, MAX_EVENTS);

                for (int j = 0; j < limit; j++) {
                    int row = j / COLS;
                    int col = j % COLS;
                    int x = START_X + col * (BTN_W + SPACING_X);
                    int y = START_Y + row * (BTN_H + SPACING_Y);

                    if (event.x > x && event.x < x + BTN_W && event.y > y && event.y < y + BTN_H) {
                        // Use existing GameScreen instance to save memory
                        GameScreen gs = GameScreen.liveScreen;
                        if (gs == null) {
                            gs = new GameScreen(game);
                        }

                        // Pass j (the selected index) to the replay loader
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

        // Force a re-load if the OS cleared images to save memory
        if (Assets.eventLogBackground == null || Assets.eventLogButtonJpeg == null) {
            loadAssets();
        }

        // 1. Draw Background
        if (Assets.eventLogBackground != null) {
            g.drawPortraitPixmap(Assets.eventLogBackground, 0, 0);
        }

        // 2. Bound Calculation
        if (timeStamp == null || eventCount == 0) {
            g.drawText("No Events Recorded", 170, 400);
            return;
        }

        int displayLimit = Math.min(eventCount, timeStamp.length);
        displayLimit = Math.min(displayLimit, MAX_EVENTS);

        // 3. OPTIMIZED Drawing Loop
        if (Assets.eventLogButtonJpeg != null) {
            for (int i = 0; i < displayLimit; i++) {
                int row = i / COLS;
                int col = i % COLS;
                int x = START_X + col * (BTN_W + SPACING_X);
                int y = START_Y + row * (BTN_H + SPACING_Y);

                // DRAW IMAGE
                g.drawEventLogButtonPixmap(Assets.eventLogButtonJpeg, x, y);

                // DRAW TEXT (Centered)
                String label = timeStamp[i];
                if (label != null) {
                    // Coordinates x+65, y+68 provide decent centering for 350x100 buttons
                    g.drawText(label, x + 65, y + 68);
                }
            }
        }
    }

    @Override public void resume() {
        // Clear old garbage before starting heavy drawing
        System.gc();
        loadAssets();
    }

    @Override public void pause() {}
    @Override public void dispose() {}
    @Override public boolean isTouchDown(int p) { return false; }
    @Override public int getTouchX(int p) { return 0; }
    @Override public int getTouchY(int p) { return 0; }
    @Override public float getAccelX() { return 0; }
    @Override public float getAccelY() { return 0; }
    @Override public float getAccelZ() { return 0; }
    @Override public List<TouchEvent> getTouchEvents() { return null; }
}