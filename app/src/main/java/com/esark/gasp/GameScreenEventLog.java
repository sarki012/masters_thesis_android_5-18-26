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
    private final int cols = 4;
    private final int rows = 16;
    private final int startX = 65;
    private final int startY = 200;
    private final int buttonWidth = 350;
    private final int buttonHeight = 100;
    private final int spacingX = 50;
    private final int spacingY = 30;

    // DO NOT initialize here to prevent recursive memory leaks
    public GameScreenEvent gameScreenEvent = null;

    public GameScreenEventLog(Game game) {
        super(game);
        // We load assets only if they were cleared from memory
        loadAssets();
    }

    private void loadAssets() {
        Graphics g = game.getGraphics();
        if (Assets.eventLogBackground == null) {
            Assets.eventLogBackground = g.newPixmap("eventLogBackground.png", Graphics.PixmapFormat.ARGB4444);
        }
        if (Assets.eventLogButton == null) {
            Assets.eventLogButton = g.newPixmap("eventLogButton.png", Graphics.PixmapFormat.ARGB4444);
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

                // Grid detection for event buttons
                int limit = Math.min(eventCount, timeStamp.length);
                limit = Math.min(limit, cols * rows);

                for (int j = 0; j < limit; j++) {
                    int row = j / cols;
                    int col = j % cols;
                    int x = startX + col * (buttonWidth + spacingX);
                    int y = startY + row * (buttonHeight + spacingY);

                    if (event.x > x && event.x < x + buttonWidth && event.y > y && event.y < y + buttonHeight) {
                        // LAZY INITIALIZATION: Only create the event detail screen when clicked
                        if (gameScreenEvent == null) {
                            gameScreenEvent = new GameScreenEvent(game);
                        }
                        gameScreenEvent.selectedEventIdx = j;
                        game.setScreen(gameScreenEvent);
                        return;
                    }
                }
            }
        }
    }

    @Override
    public void present(float deltaTime) {
        Graphics g = game.getGraphics();

        // If the Android OS reclaimed memory while we were recording, reload assets
        if (Assets.eventLogBackground == null || Assets.eventLogButton == null) {
            loadAssets();
        }

        // 1. Draw Background
        if (Assets.eventLogBackground != null) {
            g.drawPortraitPixmap(Assets.eventLogBackground, 0, 0);
        }

        // 2. Determine how many buttons to draw
        int displayLimit = Math.min(eventCount, timeStamp.length);
        displayLimit = Math.min(displayLimit, cols * rows);

        // 3. Optimized Drawing Loop
        if (displayLimit > 0 && Assets.eventLogButton != null) {
            for (int i = 0; i < displayLimit; i++) {
                int row = i / cols;
                int col = i % cols;

                int x = startX + col * (buttonWidth + spacingX);
                int y = startY + row * (buttonHeight + spacingY);

                // Draw the button
                g.drawEventLogButtonPixmap(Assets.eventLogButton, x, y);

                // Draw the timestamp text
                if (timeStamp[i] != null) {
                    // Center the text: 75 pixels in, 65 pixels down
                    g.drawText(timeStamp[i], x + 60, y + 70);
                }
            }
        }
    }

    @Override public void pause() {}
    @Override public void resume() {
        // Ensure assets are re-synced when returning to this screen
        loadAssets();
    }
    @Override public void dispose() {}
    @Override public boolean isTouchDown(int pointer) { return false; }
    @Override public int getTouchX(int pointer) { return 0; }
    @Override public int getTouchY(int pointer) { return 0; }
    @Override public float getAccelX() { return 0; }
    @Override public float getAccelY() { return 0; }
    @Override public float getAccelZ() { return 0; }
    @Override public List<TouchEvent> getTouchEvents() { return null; }
}