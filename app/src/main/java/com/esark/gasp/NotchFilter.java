package com.esark.gasp;

/**
 * 60Hz Notch Filter for 1000Hz Sampling Rate
 */
public class NotchFilter {
    // Coefficients for 60Hz Notch @ 1000Hz Sample Rate (Q=30)
    private final double b0 = 0.94247;
    private final double b1 = -1.7523;
    private final double b2 = 0.94247;
    private final double a1 = -1.7523;
    private final double a2 = 0.88495;

    // Filter state (delay lines)
    private double x1 = 0, x2 = 0; // previous inputs
    private double y1 = 0, y2 = 0; // previous outputs

    public double filter(double x0) {
        // Direct Form I implementation
        double y0 = (b0 * x0) + (b1 * x1) + (b2 * x2) - (a1 * y1) - (a2 * y2);

        // Update states
        x2 = x1;
        x1 = x0;
        y2 = y1;
        y1 = y0;

        return y0;
    }
}
