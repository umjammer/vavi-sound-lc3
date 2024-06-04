/*
 *  Copyright 2022 Google LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package google.sound.lc3;

import java.util.Arrays;
import java.util.Map;

import google.sound.lc3.Common.DecaConsumer;
import google.sound.lc3.Common.PentaConsumer;
import google.sound.lc3.Common.Duration;
import google.sound.lc3.Common.SRate;

import static google.sound.lc3.Common.Duration._10M;
import static google.sound.lc3.Common.Duration._2M5;
import static google.sound.lc3.Common.Duration._5M;
import static google.sound.lc3.Common.Duration._7M5;
import static google.sound.lc3.Common.isHR;
import static google.sound.lc3.Common.SRate._16K;
import static google.sound.lc3.Common.SRate._24K;
import static google.sound.lc3.Common.SRate._32K;
import static google.sound.lc3.Common.SRate._48K;
import static google.sound.lc3.Common.SRate._48K_HR;
import static google.sound.lc3.Common.SRate._8K;
import static google.sound.lc3.Common.SRate._96K_HR;
import static google.sound.lc3.Tables.LC3_MAX_SRATE_HZ;
import static google.sound.lc3.Tables.lc3_nh;
import static google.sound.lc3.Tables.lc3_ns;
import static google.sound.lc3.Tables.lc3_ns_4m;


/**
 * Long Term Postfilter
 */
class Ltpf {

    static class lc3_ltpf_hp50_state {
        long s1, s2;
    }

    static class lc3_ltpf_analysis {
        boolean active;
        int pitch;
        float[] nc = new float[2];

        lc3_ltpf_hp50_state hp50;
        short[] x_12k8 = new short[384];
        short[] x_6k4 = new short[178];
        int tc;
    }

    static class lc3_ltpf_synthesis {
        boolean active;
        int pitch;
        float[] c = new float[2*12];
        float[] x = new float[12];
    }

    //
    // Resampling
    //

    /*
     * Resampling coefficients
     * The coefficients, in fixed Q15, are reordered by phase for each source
     * sampleRate (coefficient matrix transposed)
     */

    private static final short[] h_8k_12k8_q15 = {
            214, 417, -1052, -4529, 26233, -4529, -1052, 417, 214, 0,
            180, 0, -1522, -2427, 24506, -5289, 0, 763, 156, -28,
            92, -323, -1361, 0, 19741, -3885, 1317, 861, 0, -61,
            0, -457, -752, 1873, 13068, 0, 2389, 598, -213, -79,
            -61, -398, 0, 2686, 5997, 5997, 2686, 0, -398, -61,
            -79, -213, 598, 2389, 0, 13068, 1873, -752, -457, 0,
            -61, 0, 861, 1317, -3885, 19741, 0, -1361, -323, 92,
            -28, 156, 763, 0, -5289, 24506, -2427, -1522, 0, 180
    };

    private static final short[] h_16k_12k8_q15 = {
            -61, 214, -398, 417, 0, -1052, 2686, -4529, 5997, 26233,
            5997, -4529, 2686, -1052, 0, 417, -398, 214, -61, 0,

            -79, 180, -213, 0, 598, -1522, 2389, -2427, 0, 24506,
            13068, -5289, 1873, 0, -752, 763, -457, 156, 0, -28,

            -61, 92, 0, -323, 861, -1361, 1317, 0, -3885, 19741,
            19741, -3885, 0, 1317, -1361, 861, -323, 0, 92, -61,

            -28, 0, 156, -457, 763, -752, 0, 1873, -5289, 13068,
            24506, 0, -2427, 2389, -1522, 598, 0, -213, 180, -79
    };

    private static final short[] h_32k_12k8_q15 = {
            -30, -31, 46, 107, 0, -199, -162, 209, 430, 0,
            -681, -526, 658, 1343, 0, -2264, -1943, 2999, 9871, 13116,
            9871, 2999, -1943, -2264, 0, 1343, 658, -526, -681, 0,
            430, 209, -162, -199, 0, 107, 46, -31, -30, 0,

            -14, -39, 0, 90, 78, -106, -229, 0, 382, 299,
            -376, -761, 0, 1194, 937, -1214, -2644, 0, 6534, 12253,
            12253, 6534, 0, -2644, -1214, 937, 1194, 0, -761, -376,
            299, 382, 0, -229, -106, 78, 90, 0, -39, -14,
    };

    private static final short[] h_24k_12k8_q15 = {
            -50, 19, 143, -93, -290, 278, 485, -658, -701, 1396,
            901, -3019, -1042, 10276, 17488, 10276, -1042, -3019, 901, 1396,
            -701, -658, 485, 278, -290, -93, 143, 19, -50, 0,

            -46, 0, 141, -45, -305, 185, 543, -501, -854, 1153,
            1249, -2619, -1908, 8712, 17358, 11772, 0, -3319, 480, 1593,
            -504, -796, 399, 367, -261, -142, 138, 40, -52, -5,

            -41, -17, 133, 0, -304, 91, 574, -334, -959, 878,
            1516, -2143, -2590, 7118, 16971, 13161, 1202, -3495, 0, 1731,
            -267, -908, 287, 445, -215, -188, 125, 62, -52, -12,

            -34, -30, 120, 41, -291, 0, 577, -164, -1015, 585,
            1697, -1618, -3084, 5534, 16337, 14406, 2544, -3526, -523, 1800,
            0, -985, 152, 509, -156, -230, 104, 83, -48, -19,

            -26, -41, 103, 76, -265, -83, 554, 0, -1023, 288,
            1791, -1070, -3393, 3998, 15474, 15474, 3998, -3393, -1070, 1791,
            288, -1023, 0, 554, -83, -265, 76, 103, -41, -26,

            -19, -48, 83, 104, -230, -156, 509, 152, -985, 0,
            1800, -523, -3526, 2544, 14406, 16337, 5534, -3084, -1618, 1697,
            585, -1015, -164, 577, 0, -291, 41, 120, -30, -34,

            -12, -52, 62, 125, -188, -215, 445, 287, -908, -267,
            1731, 0, -3495, 1202, 13161, 16971, 7118, -2590, -2143, 1516,
            878, -959, -334, 574, 91, -304, 0, 133, -17, -41,

            -5, -52, 40, 138, -142, -261, 367, 399, -796, -504,
            1593, 480, -3319, 0, 11772, 17358, 8712, -1908, -2619, 1249,
            1153, -854, -501, 543, 185, -305, -45, 141, 0, -46,
    };

    private static final short[] h_48k_12k8_q15 = {
            -13, -25, -20, 10, 51, 71, 38, -47, -133, -145,
            -42, 139, 277, 242, 0, -329, -511, -351, 144, 698,
            895, 450, -535, -1510, -1697, -521, 1999, 5138, 7737, 8744,
            7737, 5138, 1999, -521, -1697, -1510, -535, 450, 895, 698,
            144, -351, -511, -329, 0, 242, 277, 139, -42, -145,
            -133, -47, 38, 71, 51, 10, -20, -25, -13, 0,

            -9, -23, -24, 0, 41, 71, 52, -23, -115, -152,
            -78, 92, 254, 272, 76, -251, -493, -427, 0, 576,
            900, 624, -262, -1309, -1763, -954, 1272, 4356, 7203, 8679,
            8169, 5886, 2767, 0, -1542, -1660, -809, 240, 848, 796,
            292, -252, -507, -398, -82, 199, 288, 183, 0, -130,
            -145, -71, 20, 69, 60, 20, -15, -26, -17, -3,

            -6, -20, -26, -8, 31, 67, 62, 0, -94, -152,
            -108, 45, 223, 287, 143, -167, -454, -480, -134, 439,
            866, 758, 0, -1071, -1748, -1295, 601, 3559, 6580, 8485,
            8485, 6580, 3559, 601, -1295, -1748, -1071, 0, 758, 866,
            439, -134, -480, -454, -167, 143, 287, 223, 45, -108,
            -152, -94, 0, 62, 67, 31, -8, -26, -20, -6,

            -3, -17, -26, -15, 20, 60, 69, 20, -71, -145,
            -130, 0, 183, 288, 199, -82, -398, -507, -252, 292,
            796, 848, 240, -809, -1660, -1542, 0, 2767, 5886, 8169,
            8679, 7203, 4356, 1272, -954, -1763, -1309, -262, 624, 900,
            576, 0, -427, -493, -251, 76, 272, 254, 92, -78,
            -152, -115, -23, 52, 71, 41, 0, -24, -23, -9
    };

    private static final short[] h_96k_12k8_q15 = {
            -3, -7, -10, -13, -13, -10, -4, 5, 15, 26,
            33, 36, 31, 19, 0, -23, -47, -66, -76, -73,
            -54, -21, 23, 70, 111, 139, 143, 121, 72, 0,
            -84, -165, -227, -256, -240, -175, -67, 72, 219, 349,
            433, 448, 379, 225, 0, -268, -536, -755, -874, -848,
            -648, -260, 301, 1000, 1780, 2569, 3290, 3869, 4243, 4372,
            4243, 3869, 3290, 2569, 1780, 1000, 301, -260, -648, -848,
            -874, -755, -536, -268, 0, 225, 379, 448, 433, 349,
            219, 72, -67, -175, -240, -256, -227, -165, -84, 0,
            72, 121, 143, 139, 111, 70, 23, -21, -54, -73,
            -76, -66, -47, -23, 0, 19, 31, 36, 33, 26,
            15, 5, -4, -10, -13, -13, -10, -7, -3, 0,

            -1, -5, -8, -12, -13, -12, -8, 0, 10, 21,
            30, 35, 34, 26, 10, -11, -35, -58, -73, -76,
            -65, -39, 0, 46, 92, 127, 144, 136, 100, 38,
            -41, -125, -199, -246, -254, -214, -126, 0, 146, 288,
            398, 450, 424, 312, 120, -131, -405, -655, -830, -881,
            -771, -477, 0, 636, 1384, 2178, 2943, 3601, 4084, 4340,
            4340, 4084, 3601, 2943, 2178, 1384, 636, 0, -477, -771,
            -881, -830, -655, -405, -131, 120, 312, 424, 450, 398,
            288, 146, 0, -126, -214, -254, -246, -199, -125, -41,
            38, 100, 136, 144, 127, 92, 46, 0, -39, -65,
            -76, -73, -58, -35, -11, 10, 26, 34, 35, 30,
            21, 10, 0, -8, -12, -13, -12, -8, -5, -1
    };

    private static final int hp50_a1 = -2110217691, hp50_a2 = 1037111617;
    private static final int hp50_b1 = -2110535566, hp50_b2 = 1055267782;

    /**
     * High-pass 50Hz filtering, at 12.8 KHz sampleRate
     *
     * @param hp50 Biquad filter state
     * @param xn   Input sample, in fixed Q30
     * @return Filtered sample, in fixed Q30
     */
    private static int filter_hp50(lc3_ltpf_hp50_state hp50, int xn) {
        int yn = (int) ((hp50.s1 + (long) xn * hp50_b2) >> 30);
        hp50.s1 = (hp50.s2 + (long) xn * hp50_b1 - (long) yn * hp50_a1);
        hp50.s2 = ((long) xn * hp50_b2 - (long) yn * hp50_a2);

        return yn;
    }

    /**
     * Resample from 8 / 16 / 32 KHz to 12.8 KHz Template
     * <p>
     * The `x` vector is aligned on 32 bits
     * The number of previous samples `d` accessed on `x` is :
     * d: { 10, 20, 40 } - 1 for resampling factors 8, 4 and 2.
     *
     * @param p    Resampling factor with compared to 192 KHz (8, 4 or 2)
     * @param h    Arrange by phase coefficients table
     * @param hp50 High-Pass biquad filter state
     * @param x    [-d..-1] Previous, [0..ns-1] Current samples, Q15
     * @param y    [0..n-1] Output processed samples, Q14
     * @param n    processed samples, Q14
     */
    private static void resample_x64k_12k8(int p, short[] h, lc3_ltpf_hp50_state hp50, short[] x, short[] y, int yp, int n) {
        int w = 2 * (40 / p);

        int xp = -(w - 1); // x

        for (int i = 0; i < 5 * n; i += 5) {
            int hn = (i % p) * w; // h
            int xn = xp + (i / p);
            int un = 0;

            for (int k = 0; k < w; k += 10) {
                un += x[xn++] * h[hn++];
                un += x[xn++] * h[hn++];
                un += x[xn++] * h[hn++];
                un += x[xn++] * h[hn++];
                un += x[xn++] * h[hn++];
                un += x[xn++] * h[hn++];
                un += x[xn++] * h[hn++];
                un += x[xn++] * h[hn++];
                un += x[xn++] * h[hn++];
                un += x[xn++] * h[hn++];
            }

            int yn = filter_hp50(hp50, un);
            y[yp++] = (short) ((yn + (1 << 15)) >> 16);
        }
    }

    /**
     * Resample from 24 / 48 KHz to 12.8 KHz Template
     * <p>
     * The `x` vector is aligned on 32 bits
     * The number of previous samples `d` accessed on `x` is :
     * d: { 30, 60 } - 1 for resampling factors 8 and 4.
     *
     * @param p    Resampling factor with compared to 192 KHz (8 or 4)
     * @param h    Arrange by phase coefficients table
     * @param hp50 High-Pass biquad filter state
     * @param x    [-d..-1] Previous, [0..ns-1] Current samples, Q15
     * @param y    [0..n-1] Output processed samples, Q14
     * @param n    processed samples, Q14
     */
    private static void resample_x192k_12k8(int p, short[] h, lc3_ltpf_hp50_state hp50, short[] x, short[] y, int yp, int n) {
        int w = 2 * (120 / p);

        int xp = -(w - 1); // x

        for (int i = 0; i < 15 * n; i += 15) {
            int hn = (i % p) * w; // h
            int xn = xp + (i / p);
            int un = 0;

            for (int k = 0; k < w; k += 15) {
                un += x[xn++] * h[hn++];
                un += x[xn++] * h[hn++];
                un += x[xn++] * h[hn++];
                un += x[xn++] * h[hn++];
                un += x[xn++] * h[hn++];
                un += x[xn++] * h[hn++];
                un += x[xn++] * h[hn++];
                un += x[xn++] * h[hn++];
                un += x[xn++] * h[hn++];
                un += x[xn++] * h[hn++];
                un += x[xn++] * h[hn++];
                un += x[xn++] * h[hn++];
                un += x[xn++] * h[hn++];
                un += x[xn++] * h[hn++];
                un += x[xn++] * h[hn++];
            }

            int yn = filter_hp50(hp50, un);
            y[yp++] = (short) ((yn + (1 << 15)) >> 16);
        }
    }

    /**
     * Resample from 8 Khz to 12.8 KHz
     * <p>
     * The `x` vector is aligned on 32 bits
     *
     * @param hp50 High-Pass biquad filter state
     * @param x    [-10..-1] Previous, [0..ns-1] Current samples, Q15
     * @param y    [0..n-1] Output processed samples, Q14
     * @param n    processed samples, Q14
     */
    private static void resample_8k_12k8(lc3_ltpf_hp50_state hp50, short[] x, short[] y, int yp, int n) {
        resample_x64k_12k8(8, h_8k_12k8_q15, hp50, x, y, yp, n);
    }

    /**
     * Resample from 16 Khz to 12.8 KHz
     * <p>
     * The `x` vector is aligned on 32 bits
     *
     * @param hp50 High-Pass biquad filter state
     * @param x    [-20..-1] Previous, [0..ns-1] Current samples, in fixed Q15
     * @param y    [0..n-1] Output processed samples, in fixed Q14
     * @param n    processed samples, in fixed Q14
     */
    private static void resample_16k_12k8(lc3_ltpf_hp50_state hp50, short[] x, short[] y, int yp, int n) {
        resample_x64k_12k8(4, h_16k_12k8_q15, hp50, x, y, yp, n);
    }

    /**
     * Resample from 32 Khz to 12.8 KHz
     * <p>
     * The `x` vector is aligned on 32 bits
     *
     * @param hp50 High-Pass biquad filter state
     * @param x    [-30..-1] Previous, [0..ns-1] Current samples, in fixed Q15
     * @param y    [0..n-1] Output processed samples, in fixed Q14
     * @param n    processed samples, in fixed Q14
     */
    private static void resample_32k_12k8(lc3_ltpf_hp50_state hp50, short[] x, short[] y, int yp, int n) {
        resample_x64k_12k8(2, h_32k_12k8_q15, hp50, x, y, yp, n);
    }

    /**
     * Resample from 24 Khz to 12.8 KHz
     * <p>
     * The `x` vector is aligned on 32 bits
     *
     * @param hp50 High-Pass biquad filter state
     * @param x    [-30..-1] Previous, [0..ns-1] Current samples, in fixed Q15
     * @param y    [0..n-1] Output processed samples, in fixed Q14
     * @param n    processed samples, in fixed Q14
     */
    private static void resample_24k_12k8(lc3_ltpf_hp50_state hp50, short[] x, short[] y, int yp, int n) {
        resample_x192k_12k8(8, h_24k_12k8_q15, hp50, x, y, yp, n);
    }

    /**
     * Resample from 48 Khz to 12.8 KHz
     * <p>
     * The `x` vector is aligned on 32 bits
     *
     * @param hp50 High-Pass biquad filter state
     * @param x    [-60..-1] Previous, [0..ns-1] Current samples, in fixed Q15
     * @param y    [0..n-1] Output processed samples, in fixed Q14
     * @param n    processed samples, in fixed Q14
     */
    private static void resample_48k_12k8(lc3_ltpf_hp50_state hp50, short[] x, short[] y, int yp, int n) {
        resample_x192k_12k8(4, h_48k_12k8_q15, hp50, x, y, yp, n);
    }

    /**
     * Resample from 96 Khz to 12.8 KHz
     * <p>
     * The `x` vector is aligned on 32 bits
     *
     * @param hp50 High-Pass biquad filter state
     * @param x    [-120..-1] Previous, [0..ns-1] Current samples, in fixed Q15
     * @param y    [0..n-1] Output processed samples, in fixed Q14
     * @param n    processed samples, in fixed Q14
     */
    private static void resample_96k_12k8(lc3_ltpf_hp50_state hp50, short[] x, short[] y, int yp, int n) {
        resample_x192k_12k8(2, h_96k_12k8_q15, hp50, x, y, yp, n);
    }

    /**
     * Resample to 6.4 KHz
     * <p>
     * The `x` vector is aligned on 32 bits
     *
     * @param x [-3..-1] Previous, [0..n-1] Current samples
     * @param y [0..n-1] Output processed samples
     * @param n processed samples
     */
    private void resample_6k4(short[] x, int yp, short[] y, int xp, int n) {
        short[] h = {18477, 15424, 8105};

        for (xp--; yp < n; xp += 2)
            y[yp++] = (short) ((x[0] * h[0] + (x[xp + -1] + x[1]) * h[1] + (x[xp - 2] + x[xp + 2]) * h[2]) >> 16);
    }

    /**
     * LTPF Resample to 12.8 KHz implementations for each sampleRates
     */
    private static Map<SRate, PentaConsumer<lc3_ltpf_hp50_state, short[], short[], Integer, Integer>> resample_12k8 = Map.of(
            _8K, Ltpf::resample_8k_12k8,
            _16K, Ltpf::resample_16k_12k8,
            _24K, Ltpf::resample_24k_12k8,
            _32K, Ltpf::resample_32k_12k8,
            _48K, Ltpf::resample_48k_12k8,
            _48K_HR, Ltpf::resample_48k_12k8,
            _96K_HR, Ltpf::resample_96k_12k8
    );

    //
    // Analysis
    //

    /**
     * Return dot product of 2 vectors
     * <p>
     * The size `n` of vectors must be multiple of 16, and less or equal to 128
     *
     * @param a vector
     * @param b vector
     * @param n The 2 vectors of size (> 0 and <= 128)
     * @return sum(a[i] * b[i]), i = [0..n-1]
     */
    private static float dot(short[] a, int ap, short[] b, int bp, int n) {
        long v = 0;

        for (int i = 0; i < (n >> 4); i++)
            for (int j = 0; j < 16; j++)
                v += a[ap++] * b[bp++];

        int v32 = (int) ((v + (1 << 5)) >> 6);
        return (float) v32;
    }

    /**
     * Return vector of correlations
     * <p>
     * The first vector `a` is aligned of 32 bits
     * The size `n` of vectors is multiple of 16, and less or equal to 128
     *
     * @param a  vector
     * @param b  vector
     * @param n  The 2 vector of size `n` (> 0 and <= 128)
     * @param y  Output the correlation vector
     * @param nc Output the correlation vector of size
     */
    private static void correlate(short[] a, int ap, short[] b, int bp, int n, float[] y, int nc) {
        for (int yp = 0; yp < nc; yp++)
            y[yp] = dot(a, ap, b, bp--, n);
    }

    /**
     * Search the maximum value and returns its argument
     *
     * @param x     The input vector
     * @param n     The input vector of size `n`
     * @param x_max Return the maximum value
     * @return Return the argument of the maximum
     */
    private static int argmax(final float[] x, int xp, int n, float[] x_max) {
        int arg = 0;

        x_max[0] = x[xp + (arg = 0)];
        for (int i = 1; i < n; i++)
            if (x_max[0] < x[xp + i])
                x_max[0] = x[xp + (arg = i)];

        return arg;
    }

    /**
     * Search the maximum weithed value and returns its argument
     *
     * @param x      The input vector
     * @param n      The input vector of size
     * @param w_incr Increment of the weight
     * @param x_max  Return the maximum not weighted value
     */
    private static int argmax_weighted(final float[] x, int n, float w_incr, float[] x_max) {
        int arg;

        float xw_max = (x_max[0] = x[arg = 0]);
        float w = 1 + w_incr;

        for (int i = 1; i < n; i++, w += w_incr)
            if (xw_max < x[i] * w)
                xw_max = (x_max[0] = x[arg = i]) * w;

        return arg;
    }

    private static final short[][] h4_q15 = {
            {6877, 19121, 6877, 0},
            {3506, 18025, 11000, 220},
            {1300, 15048, 15048, 1300},
            {220, 11000, 18025, 3506}
    };

    /**
     * Interpolate from pitch detected value
     * <p>
     * The size `n` of vectors must be multiple of 4
     *
     * @param x [-2..-1] Previous
     * @param n [0..n] Current input
     * @param d The phase of interpolation (0 to 3)
     * @param y return The interpolated vector
     */
    private void interpolate(short[] x, int xp, int n, int d, short[] y) {
        short[] h = h4_q15[d];
        short x3 = x[xp - 2];
        short x2 = x[xp - 1];
        short x1, x0;

        x1 = x[xp++];
        for (int yp = 0; yp < n; yp++) {
            int yn;

            yn = (x0 = x[xp++]) * h[0] + x1 * h[1] + x2 * h[2] + x3 * h[3];
            y[yp++] = (short) (yn >> 15);

            yn = (x3 = x[xp++]) * h[0] + x0 * h[1] + x1 * h[2] + x2 * h[3];
            y[yp++] = (short) (yn >> 15);

            yn = (x2 = x[xp++]) * h[0] + x3 * h[1] + x0 * h[2] + x1 * h[3];
            y[yp++] = (short) (yn >> 15);

            yn = (x1 = x[xp++]) * h[0] + x2 * h[1] + x3 * h[2] + x0 * h[3];
            y[yp++] = (short) (yn >> 15);
        }
    }

    private static final float[][] h4 = {{
            1.53572770e-02f, -4.72963246e-02f, 8.35788573e-02f, 8.98638285e-01f,
            8.35788573e-02f, -4.72963246e-02f, 1.53572770e-02f,
    }, {
            2.74547165e-03f, 4.59833449e-03f, -7.54404636e-02f, 8.17488686e-01f,
            3.30182571e-01f, -1.05835916e-01f, 2.86823405e-02f, -2.87456116e-03f
    }, {
            -3.00125103e-03f, 2.95038503e-02f, -1.30305021e-01f, 6.03297008e-01f,
            6.03297008e-01f, -1.30305021e-01f, 2.95038503e-02f, -3.00125103e-03f
    }, {
            -2.87456116e-03f, 2.86823405e-02f, -1.05835916e-01f, 3.30182571e-01f,
            8.17488686e-01f, -7.54404636e-02f, 4.59833449e-03f, 2.74547165e-03f
    }};

    /**
     * Interpolate auto-correlation
     *
     * @param x [-4..-1] Previous, [0..4] Current input
     * @param d The phase of interpolation (-3 to 3)
     * @return The interpolated value
     */
    private static float interpolate_corr(float[] x, int xp, int d) {
        float[] h = h4[(4 + d) % 4];
        int hp = 0;

        float y = d < 0 ? x[xp - 4] * h[hp++] : d > 0 ? x[4] * h[hp + 7] : 0;

        y += x[xp - 3] * h[0] + x[xp - 2] * h[1] + x[xp - 1] * h[2] + x[0] * h[3] +
                x[1] * h[4] + x[2] * h[5] + x[3] * h[6];

        return y;
    }

    /**
     * Pitch detection algorithm
     * <p>
     * The `x` vector is aligned on 32 bits
     *
     * @param ltpf Context of analysis
     * @param x    [-114..-17] Previous, [0..n-1] Current 6.4KHz samples
     * @param n    samples
     * @param tc   Return the pitch-lag estimation
     * @return True when pitch present
     */
    private static boolean detect_pitch(lc3_ltpf_analysis ltpf, short[] x, int xp, int n, int[] tc) {
        float[] rm1 = new float[1], rm2 = new float[1];
        float[] r = new float[98];

        final int r0 = 17, nr = 98;
        int k0 = Math.max(0, ltpf.tc - 4);
        int nk = Math.min(nr - 1, ltpf.tc + 4) - k0 + 1;

        correlate(x, xp, x, r0, n, r, nr);

        int t1 = argmax_weighted(r, nr, -.5f / (nr - 1), rm1);
        int t2 = k0 + argmax(r, k0, nk, rm2);

        int x1 = -(r0 + t1); // x
        int x2 = -(r0 + t2); // x

        float nc1 = rm1[0] <= 0 ? 0 : (float) (rm1[0] / Math.sqrt(dot(x, 0, x, 0, n) * dot(x, x1, x, x1, n)));

        float nc2 = rm2[0] <= 0 ? 0 : (float) (rm2[0] / Math.sqrt(dot(x, 0, x, 0, n) * dot(x, x2, x, x2, n)));

        boolean t1sel = nc2 <= 0.85f * nc1;
        ltpf.tc = (t1sel ? t1 : t2);

        tc[0] = r0 + ltpf.tc;
        return (t1sel ? nc1 : nc2) > 0.6f;
    }

    /**
     * Pitch-lag parameter
     * <p>
     * The `x` vector is aligned on 32 bits
     *
     * @param x     [-232..-28] Previous, [0..n-1] Current 12.8KHz samples, Q14
     * @param n     samples
     * @param tc    Pitch-lag estimation
     * @param pitch The pitch value, in fixed .4
     * @return The bitstream pitch index value
     */
    private static int refine_pitch(short[] x, int xp, int n, int tc, int[] pitch) {
        float[] r = new float[17];
        float[] rm = new float[1];
        int e, f;

        int r0 = Math.max(32, 2 * tc - 4);
        int nr = Math.min(228, 2 * tc + 4) - r0 + 1;

        correlate(x, xp, x, -(r0 - 4), n, r, nr + 8);

        e = r0 + argmax(r, 4, nr, rm);
        int re = (e - (r0 - 4)); // r

        float dm = interpolate_corr(r, re, f = 0);
        for (int i = 1; i <= 3; i++) {
            float d;

            if (e >= 127 && (((i & 1) != 0) | (e >= 157)))
                continue;

            if ((d = interpolate_corr(r, re, i)) > dm)
                dm = d;
            f = i;

            if (e > 32 && (d = interpolate_corr(r, re, -i)) > dm)
                dm = d;
            f = -i;
        }

        e -= (f < 0) ? 1 : 0;
        f += 4 * (f < 0 ? 1 : 0);

        pitch[0] = 4 * e + f;
        return e < 127 ? 4 * e + f - 128 : e < 157 ? 2 * e + (f >> 1) + 126 : e + 283;
    }

    private static class lc3_ltpf_data {

        boolean active;
        int pitch_index;
    }

    //
    // Encoding
    //

    /**
     * LTPF analysis
     * <p>
     * The `x` vector is aligned on 32 bits
     * The number of previous samples `d` accessed on `x` is :
     * d: { 10, 20, 30, 40, 60 } - 1 for sampleRates from 8KHz to 48KHz
     *
     * @param dt   Duration of the frame
     * @param sr   sampleRate of the frame
     * @param ltpf Context of analysis
     * @param x    [-d..-1] Previous, [0..ns-1] Current samples
     * @param data Return bitstream data
     * @return True when pitch present, False otherwise
     */
    boolean lc3_ltpf_analyse(Duration dt, SRate sr, lc3_ltpf_analysis ltpf, short[] x, lc3_ltpf_data data) {

        // Resampling to 12.8 KHz

        int z_12k8 = ltpf.x_12k8.length / Short.BYTES;
        int n_12k8 = (1 + dt.ordinal()) * 32;

        System.arraycopy(ltpf.x_12k8, n_12k8, ltpf.x_12k8, 0, z_12k8 - n_12k8);
        Arrays.fill(ltpf.x_12k8, n_12k8, z_12k8 - n_12k8, (short) 0);

        int x_12k8 = z_12k8 - n_12k8; // ltpf.x_12k8

        resample_12k8.get(sr).accept(ltpf.hp50, x, ltpf.x_12k8, x_12k8, n_12k8);

        x_12k8 -= (short) (dt.ordinal() == _7M5.ordinal() ? 44 : 24);

        // Resampling to 6.4 KHz

        int z_6k4 = ltpf.x_6k4.length / Short.BYTES;
        int n_6k4 = n_12k8 >> 1;

        System.arraycopy(ltpf.x_6k4, n_6k4, ltpf.x_6k4, 0, z_6k4 - n_6k4);
        Arrays.fill(ltpf.x_6k4, n_6k4, z_6k4 - n_6k4, (short) 0);

        int x_6k4 = z_6k4 - n_6k4; // ltpf.x_6k4

        resample_6k4(ltpf.x_12k8, x_12k8, ltpf.x_6k4, x_6k4, n_6k4);

        // Enlarge for small frame size

        if (dt == _2M5) {
            x_12k8 -= n_12k8;
            x_6k4 -= n_6k4;
            n_12k8 += n_12k8;
            n_6k4 += n_6k4;
        }

        // Pitch detection

        int[] tc = new int[1];
        int[] pitch = new int[] {0};
        float nc = 0;

        boolean pitch_present = detect_pitch(ltpf, ltpf.x_6k4, x_6k4, n_6k4, tc);

        if (pitch_present) {
            short[] u = new short[128], v = new short[128];

            data.pitch_index = refine_pitch(ltpf.x_12k8, x_12k8, n_12k8, tc[0], pitch);

            interpolate(ltpf.x_12k8, x_12k8, n_12k8, 0, u);
            interpolate(ltpf.x_12k8, x_12k8 - (pitch[0] >> 2), n_12k8, pitch[0] & 3, v);

            nc = (float) (dot(u, 0, v, 0, n_12k8) /
                    Math.sqrt(dot(u, 0, u, 0, n_12k8) * dot(v, 0, v, 0, n_12k8)));
        }

        // Activation

        if (ltpf.active) {
            int pitch_diff = Math.max(pitch[0], ltpf.pitch) - Math.min(pitch[0], ltpf.pitch);
            float nc_diff = nc - ltpf.nc[0];

            data.active = !isHR(sr) && pitch_present &&
                    ((nc > 0.9f) || (nc > 0.84f && pitch_diff < 8 && nc_diff > -0.1f));

        } else {
            data.active = !isHR(sr) && pitch_present &&
                    ((dt == _10M || ltpf.nc[1] > 0.94f) &&
                            (ltpf.nc[0] > 0.94f && nc > 0.94f));
        }

        ltpf.active = data.active;
        ltpf.pitch = pitch[0];
        ltpf.nc[1] = ltpf.nc[0];
        ltpf.nc[0] = nc;

        return pitch_present;
    }

    /**
     * LTPF disable
     *
     * @param data LTPF data, disabled activation on return
     */
    void lc3_ltpf_disable(lc3_ltpf_data data) {
        data.active = false;
    }

    /**
     * Return number of bits coding the bitstream data
     *
     * @param pitch True when pitch present, False otherwise
     * @return Bit consumption, including the pitch present flag
     */
    static int lc3_ltpf_get_nbits(boolean pitch) {
        return 1 + 10 * (pitch ? 1 : 0);
    }

    /**
     * Put bitstream data
     *
     * @param bits Bitstream context
     * @param data LTPF data
     */
    void lc3_ltpf_put_data(Bits bits, final lc3_ltpf_data data) {
        bits.lc3_put_bit(data.active ? 1 : 0);
        bits.lc3_put_bits(data.pitch_index, 9);
    }

    //
    // Decoding
    //

    /**
     * Get bitstream data
     *
     * @param bits Bitstream context
     * @param data Return bitstream data
     */
    void lc3_ltpf_get_data(Bits bits, lc3_ltpf_data data) {
        data.active = bits.lc3_get_bit() != 0;
        data.pitch_index = bits.lc3_get_bits(9);
    }

    //
    // Synthesis
    //

    /**
     * Width of synthesis filter
     */
    static final int MAX_FILTER_WIDTH = LC3_MAX_SRATE_HZ / 4000;

    /**
     * Synthesis filter template
     *
     * @param xh   History ring buffer of filtered samples
     * @param nh   History ring buffer of filtered samples
     * @param lag  Lag parameter in the ring buffer
     * @param x0   w-1 previous input samples
     * @param x    Current samples as input
     * @param n    filtered as output
     * @param c    Coefficients `den` then `num`
     * @param w    width of filter
     * @param fade Fading mode of filter  -1: Out  1: In  0: None
     */
    private static void synthesize_template(
            int /* float[] */ xh, int nh, int lag,
            float[] x0, int x0p, float[] x, int xp, int n,
            float[] c, int w, int fade) {

        float g = (float) (fade <= 0 ? 1 : 0);
        float g_incr = (float) ((fade > 0 ? 1 : 0) - (fade < 0 ? 1 : 0)) / n;
        float[] u = new float[MAX_FILTER_WIDTH];

        // Load previous samples

        lag += (w >> 1);

        int y = -xh < lag ? nh - lag : -lag; // x
        int y_end = xh + nh - 1;

        for (int j = 0; j < w - 1; j++) {

            u[j] = 0;

            float yi = x[y];
            float xi = x0[x0p++];
            y = y < y_end ? y + 1 : xh;

            for (int k = 0; k <= j; k++)
                u[j - k] -= yi * c[k];

            for (int k = 0; k <= j; k++)
                u[j - k] += xi * c[w + k];
        }

        u[w - 1] = 0;

        // Process by filter length

        for (int i = 0; i < n; i += w)
            for (int j = 0; j < w; j++, g += g_incr) {

                float yi = x[y];
                float xi = x[xp];
                y = y < y_end ? y + 1 : xh;

                for (int k = 0; k < w; k++)
                    u[(j + (w - 1) - k) % w] -= yi * c[k];

                for (int k = 0; k < w; k++)
                    u[(j + (w - 1) - k) % w] += xi * c[w + k];

                x[xp++] = xi - g * u[j];
                u[j] = 0;
            }
    }

    /*
     * Synthesis filter for each sampleRates (width of filter)
     */

    private static void synthesize_4(int /* float[] */ xh, int nh, int lag, float[] x0, int x0p, float[] x, int xp, int n, float[] c, int fade) {
        synthesize_template(xh, nh, lag, x0, x0p, x, xp, n, c, 4, fade);
    }

    private static void synthesize_6(int /* float[] */  xh, int nh, int lag, float[] x0, int x0p, float[] x, int xp, int n, float[] c, int fade) {
        synthesize_template(xh, nh, lag, x0, x0p, x, xp, n, c, 6, fade);
    }

    private static void synthesize_8(int /* float[] */  xh, int nh, int lag, float[] x0, int x0p, float[] x, int xp, int n, float[] c, int fade) {
        synthesize_template(xh, nh, lag, x0, x0p, x, xp, n, c, 8, fade);
    }

    private static void synthesize_12(int /* float[] */  xh, int nh, int lag, float[] x0, int x0p, float[] x, int xp, int n, float[] c, int fade) {
        synthesize_template(xh, nh, lag, x0, x0p, x, xp, n, c, 12, fade);
    }

    static Map<SRate, DecaConsumer<Integer, Integer, Integer, float[], Integer, float[], Integer, Integer, float[], Integer>> synthesize = Map.of(
            _8K, Ltpf::synthesize_4,
            _16K, Ltpf::synthesize_4,
            _24K, Ltpf::synthesize_6,
            _32K, Ltpf::synthesize_8,
            _48K, Ltpf::synthesize_12
    );

    /**
     * LTPF synthesis
     * <p>
     * The size of the ring buffer is `nh + ns`.
     * The filtering needs a history of at least 18 ms.
     *
     * @param dt     Duration of the frame
     * @param sr     sampleRate of the frame
     * @param nbytes Size in bytes of the frame
     * @param ltpf   Context of synthesis
     * @param data   Bitstream data, NULL when pitch not present
     * @param xh     Base address of ring buffer of decoded samples
     * @param x      Samples to proceed in the ring buffer, filtered as output
     */
    void lc3_ltpf_synthesize(Duration dt, SRate sr, int nbytes,
                             lc3_ltpf_synthesis ltpf, lc3_ltpf_data data,
                             int /* float[] */ xh, float[] x) {
int xp = 0; // TODO

        int nh = lc3_ns(dt, sr) + lc3_nh(dt, sr);

        // Filter parameters

        int p_idx = data != null ? data.pitch_index : 0;
        int pitch = p_idx >= 440 ? (((p_idx) - 283) << 2) :
                p_idx >= 380 ? (((p_idx >> 1) - 63) << 2) + (((p_idx & 1)) << 1) :
                        (((p_idx >> 2) + 32) << 2) + (((p_idx & 3)) << 0);

        pitch = (pitch * lc3_ns(_10M, sr) + 64) / 128;

        int nbits = (nbytes * 8 * (1 + _10M.ordinal())) / (1 + dt.ordinal());
        if (dt == _2M5)
            nbits = (6 * nbits + 5) / 10;
        if (dt == _5M)
            nbits -= 160;

        int g_idx = Math.max(nbits / 80, 3 + sr.ordinal()) - (3 + sr.ordinal());
        boolean active = data != null && data.active && g_idx < 4;

        int w = Math.max(4, lc3_ns_4m.get(sr) >> 4);
        float[] c = new float[2 * MAX_FILTER_WIDTH];

        for (int i = 0; i < w; i++) {
            float g = active ? 0.4f - 0.05f * g_idx : 0;
            c[i] = g * lc3_ltpf_cden.get(sr)[pitch & 3][(w - 1) - i];
            c[w + i] = 0.85f * g * lc3_ltpf_cnum.get(sr)[Math.min(g_idx, 3)][(w - 1) - i];
        }

        // Transition handling

        int ns = lc3_ns(dt, sr);
        int nt = ns / (1 + dt.ordinal());
        float[][] x0 = new float[2][MAX_FILTER_WIDTH];

        System.arraycopy(ltpf.x, 0, x0[0], 0, w - 1);
        System.arraycopy(x, +ns - (w - 1), ltpf.x, 0, w - 1);

        if (active)
            System.arraycopy(x, nt - (w - 1), x0[1], 0, w - 1);

        if (!ltpf.active && active)
            synthesize.get(sr).accept(xh, nh, pitch / 4, x0[0], 0, x, xp, nt, c, 1);
        else if (ltpf.active && !active)
            synthesize.get(sr).accept(xh, nh, ltpf.pitch / 4, x0[0], 0, x, xp, nt, ltpf.c, -1);
        else if (ltpf.active && active && ltpf.pitch == pitch)
            synthesize.get(sr).accept(xh, nh, pitch / 4, x0[0], 0, x, xp, nt, c, 0);
        else if (ltpf.active && active) {
            synthesize.get(sr).accept(xh, nh, ltpf.pitch / 4, x0[0], 0, x, xp, nt, ltpf.c, -1);
            synthesize.get(sr).accept(xh, nh, pitch / 4,
                    x, (xp <= xh ? xp + nh : xp) - (w - 1), x, xp, nt, c, 1);
        }

        // Remainder

        if (active && ns > nt)
            synthesize.get(sr).accept(xh, nh, pitch / 4, x0[1], 0, x, xp + nt, ns - nt, c, 0);

        // Update state

        ltpf.active = active;
        ltpf.pitch = pitch;
        System.arraycopy(c, 0, ltpf.c, 0, 2 * w);
    }

    /**
     * Long Term Postfilter Synthesis
     * with - addition of a 0 for num coefficients
     * - remove of first 0 den coefficients
     */
    private static final Map<SRate, float[][]> lc3_ltpf_cnum = Map.of(
            _8K, new float[][] {
                    {6.02361821e-01f, 4.19760926e-01f, -1.88342453e-02f, 0.f},
                    {5.99476858e-01f, 4.19760926e-01f, -1.59492828e-02f, 0.f},
                    {5.96776466e-01f, 4.19760926e-01f, -1.32488910e-02f, 0.f},
                    {5.94241012e-01f, 4.19760926e-01f, -1.07134366e-02f, 0.f},
            }, _16K, new float[][] {
                    {6.02361821e-01f, 4.19760926e-01f, -1.88342453e-02f, 0.f},
                    {5.99476858e-01f, 4.19760926e-01f, -1.59492828e-02f, 0.f},
                    {5.96776466e-01f, 4.19760926e-01f, -1.32488910e-02f, 0.f},
                    {5.94241012e-01f, 4.19760926e-01f, -1.07134366e-02f, 0.f},
            }, _24K, new float[][] {
                    {3.98969559e-01f, 5.14250861e-01f, 1.00438297e-01f, -1.27889396e-02f,
                            -1.57228008e-03f, 0.f},
                    {3.94863491e-01f, 5.12381921e-01f, 1.04319493e-01f, -1.09199996e-02f,
                            -1.34740833e-03f, 0.f},
                    {3.90984448e-01f, 5.10605352e-01f, 1.07983252e-01f, -9.14343107e-03f,
                            -1.13212462e-03f, 0.f},
                    {3.87309389e-01f, 5.08912208e-01f, 1.11451738e-01f, -7.45028713e-03f,
                            -9.25551405e-04f, 0.f},
            }, _32K, new float[][] {
                    {2.98237945e-01f, 4.65280920e-01f, 2.10599743e-01f, 3.76678038e-02f,
                            -1.01569616e-02f, -2.53588100e-03f, -3.18294617e-04f, 0.f},
                    {2.94383415e-01f, 4.61929400e-01f, 2.12946577e-01f, 4.06617500e-02f,
                            -8.69327230e-03f, -2.17830711e-03f, -2.74288806e-04f, 0.f},
                    {2.90743921e-01f, 4.58746191e-01f, 2.15145697e-01f, 4.35010477e-02f,
                            -7.29549535e-03f, -1.83439564e-03f, -2.31692019e-04f, 0.f},
                    {2.87297585e-01f, 4.55714889e-01f, 2.17212695e-01f, 4.62008888e-02f,
                            -5.95746380e-03f, -1.50293428e-03f, -1.90385191e-04f, 0.f},
            }, _48K, new float[][] {
                    {1.98136374e-01f, 3.52449490e-01f, 2.51369527e-01f, 1.42414624e-01f,
                            5.70473102e-02f, 9.29336624e-03f, -7.22602537e-03f, -3.17267989e-03f,
                            -1.12183596e-03f, -2.90295724e-04f, -4.27081559e-05f, 0.f},
                    {1.95070943e-01f, 3.48466041e-01f, 2.50998846e-01f, 1.44116741e-01f,
                            5.92894732e-02f, 1.10892383e-02f, -6.19290811e-03f, -2.72670551e-03f,
                            -9.66712583e-04f, -2.50810092e-04f, -3.69993877e-05f, 0.f},
                    {1.92181006e-01f, 3.44694556e-01f, 2.50622009e-01f, 1.45710245e-01f,
                            6.14113213e-02f, 1.27994140e-02f, -5.20372109e-03f, -2.29732451e-03f,
                            -8.16560813e-04f, -2.12385575e-04f, -3.14127133e-05f, 0.f},
                    {1.89448531e-01f, 3.41113925e-01f, 2.50240688e-01f, 1.47206563e-01f,
                            6.34247723e-02f, 1.44320343e-02f, -4.25444914e-03f, -1.88308147e-03f,
                            -6.70961906e-04f, -1.74936334e-04f, -2.59386474e-05f, 0.f},
            }
    );

    private static final Map<SRate, float[][]> lc3_ltpf_cden = Map.of(
            _8K, new float[][] {
                    {2.09880463e-01f, 5.83527575e-01f, 2.09880463e-01f, 0.00000000e+00f},
                    {1.06999186e-01f, 5.50075002e-01f, 3.35690625e-01f, 6.69885837e-03f},
                    {3.96711478e-02f, 4.59220930e-01f, 4.59220930e-01f, 3.96711478e-02f},
                    {6.69885837e-03f, 3.35690625e-01f, 5.50075002e-01f, 1.06999186e-01f},
            },
            _16K, new float[][] {
                    {2.09880463e-01f, 5.83527575e-01f, 2.09880463e-01f, 0.00000000e+00f},
                    {1.06999186e-01f, 5.50075002e-01f, 3.35690625e-01f, 6.69885837e-03f},
                    {3.96711478e-02f, 4.59220930e-01f, 4.59220930e-01f, 3.96711478e-02f},
                    {6.69885837e-03f, 3.35690625e-01f, 5.50075002e-01f, 1.06999186e-01f},
            },
            _24K, new float[][] {
                    {6.32223163e-02f, 2.50730961e-01f, 3.71390943e-01f, 2.50730961e-01f,
                            6.32223163e-02f, 0.00000000e+00f},
                    {3.45927217e-02f, 1.98651560e-01f, 3.62641173e-01f, 2.98675055e-01f,
                            1.01309287e-01f, 4.26354371e-03f},
                    {1.53574678e-02f, 1.47434488e-01f, 3.37425955e-01f, 3.37425955e-01f,
                            1.47434488e-01f, 1.53574678e-02f},
                    {4.26354371e-03f, 1.01309287e-01f, 2.98675055e-01f, 3.62641173e-01f,
                            1.98651560e-01f, 3.45927217e-02f},
            },
            _32K, new float[][] {
                    {2.90040188e-02f, 1.12985742e-01f, 2.21202403e-01f, 2.72390947e-01f,
                            2.21202403e-01f, 1.12985742e-01f, 2.90040188e-02f, 0.00000000e+00f},
                    {1.70315342e-02f, 8.72250379e-02f, 1.96140776e-01f, 2.68923798e-01f,
                            2.42499910e-01f, 1.40577336e-01f, 4.47487717e-02f, 3.12703024e-03f},
                    {8.56367375e-03f, 6.42622294e-02f, 1.68767671e-01f, 2.58744594e-01f,
                            2.58744594e-01f, 1.68767671e-01f, 6.42622294e-02f, 8.56367375e-03f},
                    {3.12703024e-03f, 4.47487717e-02f, 1.40577336e-01f, 2.42499910e-01f,
                            2.68923798e-01f, 1.96140776e-01f, 8.72250379e-02f, 1.70315342e-02f},
            },
            _48K, new float[][] {
                    {1.08235939e-02f, 3.60896922e-02f, 7.67640147e-02f, 1.24153058e-01f,
                            1.62759644e-01f, 1.77677142e-01f, 1.62759644e-01f, 1.24153058e-01f,
                            7.67640147e-02f, 3.60896922e-02f, 1.08235939e-02f, 0.00000000e+00f},
                    {7.04140493e-03f, 2.81970232e-02f, 6.54704494e-02f, 1.12464799e-01f,
                            1.54841896e-01f, 1.76712238e-01f, 1.69150721e-01f, 1.35290158e-01f,
                            8.85142501e-02f, 4.49935385e-02f, 1.55761371e-02f, 2.03972196e-03f},
                    {4.14699847e-03f, 2.13575731e-02f, 5.48273558e-02f, 1.00497144e-01f,
                            1.45606034e-01f, 1.73843984e-01f, 1.73843984e-01f, 1.45606034e-01f,
                            1.00497144e-01f, 5.48273558e-02f, 2.13575731e-02f, 4.14699847e-03f},
                    {2.03972196e-03f, 1.55761371e-02f, 4.49935385e-02f, 8.85142501e-02f,
                            1.35290158e-01f, 1.69150721e-01f, 1.76712238e-01f, 1.54841896e-01f,
                            1.12464799e-01f, 6.54704494e-02f, 2.81970232e-02f, 7.04140493e-03f},
            }
    );
}