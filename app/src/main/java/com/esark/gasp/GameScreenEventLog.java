package com.esark.gasp;

import static com.esark.gasp.GameScreen.eventCount;
import static com.esark.gasp.GameScreen.timeStamp;

import android.content.Context;

import com.esark.framework.Game;
import com.esark.framework.Graphics;
import com.esark.framework.Input;
import com.esark.framework.Input.TouchEvent;
import com.esark.framework.Screen;

import java.util.List;

public class GameScreenEventLog extends Screen implements Input {
    Context context = null;

    // Grid layout parameters for 3x12 grid
    private final int cols = 3;
    private final int rows = 12;
    private final int startX = 50;
    private final int startY = 300;
    private final int buttonWidth = 500;
    private final int buttonHeight = 150;
    private final int spacingX = 50;
    private final int spacingY = 30;

    public GameScreenEventLog(Game game) {
        super(game);
    }

    public GameScreenEvent gameScreenEvent = new GameScreenEvent(game);

    @Override
    public void update(float deltaTime, Context context) {
        List<TouchEvent> touchEvents = game.getInput().getTouchEvents();
        updateRunning(touchEvents, deltaTime, context);
    }

    private void updateRunning(List<TouchEvent> touchEvents, float deltaTime, Context context) {
        Graphics g = game.getGraphics();
        
        // Re-load pixmaps (should ideally be done once in Assets class)
        Assets.eventLogBackground = g.newPixmap("eventLogBackground.png", Graphics.PixmapFormat.ARGB4444);
        Assets.eventLogButton = g.newPixmap("eventLogButton.png", Graphics.PixmapFormat.ARGB4444);

        int tLen = touchEvents.size();
        for (int i = 0; i < tLen; i++) {
            TouchEvent event = touchEvents.get(i);
            if (event.type == TouchEvent.TOUCH_UP || event.type == TouchEvent.TOUCH_DRAGGED || event.type == TouchEvent.TOUCH_DOWN) {
                // Back button to Artifact/PSD screen
                if (event.x > 25 && event.x < 675 && event.y > 2583 && event.y < 2780) {
                    game.setScreen(game.getStartScreen());
                    return;
                }

                // Check if any event button in the grid was pressed
                for (int j = 0; j < eventCount; j++) {
                    if (j >= cols * rows) break; // Don't exceed grid bounds

                    int row = j / cols;
                    int col = j % cols;
                    int x = startX + col * (buttonWidth + spacingX);
                    int y = startY + row * (buttonHeight + spacingY);

                    if (event.x > x && event.x < x + buttonWidth && event.y > y && event.y < y + buttonHeight) {
                        gameScreenEvent.selectedEventIdx = j;
                        game.setScreen(gameScreenEvent);
                        return;
                    }
                }
            }
        }

        // Draw background
        g.drawPortraitPixmap(Assets.eventLogBackground, 0, 0);

        // Draw event buttons and timestamps in a grid
        for (int i = 0; i < eventCount; i++) {
            if (i >= cols * rows) break;

            int row = i / cols;
            int col = i % cols;
            int x = startX + col * (buttonWidth + spacingX);
            int y = startY + row * (buttonHeight + spacingY);

            // Draw button pixmap
            g.drawEventLogButtonPixmap(Assets.eventLogButton, x, y);

            // Draw timestamp text on the button
            // Adjusted coordinates to center the timestamp and move it up slightly
            if (timeStamp[i] != null) {
                g.drawText(timeStamp[i], x + 135, y + 85);
            }
        }
    }

    @Override
    public void present(float deltaTime) {}
    @Override
    public void pause() {}
    @Override
    public void resume() {}
    @Override
    public void dispose() {}
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
