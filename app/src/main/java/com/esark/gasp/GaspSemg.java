package com.esark.gasp;

import com.esark.framework.Screen;
import com.esark.framework.AndroidGame;


public class GaspSemg extends AndroidGame {
    /*
    AndroidGame is an abstract class. This means it doesn't have to implement all methods of Game,
    as long as one of the classes extending AndroidGame does this. getStartScreen() does this.
     */
    // Inside GaspSemg.java
    public static ConnectedThread connectedThread;  // Must be public AND static
    public Screen getStartScreen() {
        return new LoadingScreen(this);
    }
}