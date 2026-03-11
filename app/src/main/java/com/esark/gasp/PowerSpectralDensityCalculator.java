package com.esark.gasp;

import org.jtransforms.fft.DoubleFFT_1D;

public class PowerSpectralDensityCalculator {
    double[] data;
    double samplingFrequency;

    //constructor
    public PowerSpectralDensityCalculator(double[] data, double samplingFrequency) {
        this.data = data;
        this.samplingFrequency = samplingFrequency;
    }

    public double[] calculatePSD(double[] data, double samplingFrequency) {
        int n = data.length;
        DoubleFFT_1D fft = new DoubleFFT_1D(n);

        // Create a copy to perform in-place FFT on
        double[] fftData = new double[n];
        System.arraycopy(data, 0, fftData, 0, n);

        // Perform real forward FFT
        fft.realForward(fftData);

        // Calculate power spectrum and then PSD
        double[] psd = new double[n / 2 + 1]; // Accounts for DC and Nyquist frequencies
        double normalizationFactor = n * samplingFrequency; // Example for basic normalization

        // DC component (k=0)
        psd[0] = (fftData[0] * fftData[0]) / normalizationFactor;

        // Positive frequencies (0 < k < n/2)
        for (int k = 1; k < n / 2; k++) {
            double realPart = fftData[2 * k];
            double imagPart = fftData[2 * k + 1];
            psd[k] = (realPart * realPart + imagPart * imagPart) / normalizationFactor;
        }

        // Nyquist frequency (if n is even)
        if (n % 2 == 0) {
            psd[n / 2] = (fftData[1] * fftData[1]) / normalizationFactor; // fftData[1] contains Re[n/2]
        }
        return psd;
    }
}
