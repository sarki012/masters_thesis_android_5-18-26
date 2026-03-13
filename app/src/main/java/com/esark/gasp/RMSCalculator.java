package com.esark.gasp;
import java.lang.Math;

public class RMSCalculator {
    /**
     * Calculates the Root Mean Square (RMS) of a given array of doubles.
     *
     * @param arr The input array of double values.
     * @return The RMS value of the array elements.
     */
    public static double calculateRMS(double[] arr) {
        if (arr == null || arr.length == 0) {
            return 0.0;
        }

        double sumOfSquares = 0.0;
        for (double element : arr) {
            sumOfSquares += element * element;
        }
        return Math.sqrt(sumOfSquares / arr.length);
    }

    /**
     * Calculates the moving RMS of a given array using a window size.
     *
     * @param data The input array.
     * @param windowSize The size of the window for RMS calculation.
     * @return An array of RMS values.
     */
    public static double[] calculateMovingRMS(double[] data, int windowSize) {
        if (data == null || windowSize <= 0 || windowSize > data.length) {
            return new double[0];
        }

        int resultLength = data.length - windowSize + 1;
        double[] rmsValues = new double[resultLength];

        for (int i = 0; i < resultLength; i++) {
            double sumOfSquares = 0.0;
            for (int j = 0; j < windowSize; j++) {
                double val = data[i + j];
                sumOfSquares += val * val;
            }
            rmsValues[i] = Math.sqrt(sumOfSquares / windowSize);
        }
        return rmsValues;
    }
}
