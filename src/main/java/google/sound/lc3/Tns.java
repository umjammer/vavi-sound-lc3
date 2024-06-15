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

import java.util.Map;

import google.sound.lc3.Bits.AcModel;
import google.sound.lc3.Lc3.BandWidth;
import google.sound.lc3.Lc3.Duration;

import static google.sound.lc3.Lc3.BandWidth.FB;
import static google.sound.lc3.Lc3.BandWidth.SWB;
import static google.sound.lc3.Lc3.Duration._10M;
import static google.sound.lc3.Lc3.Duration._2M5;
import static google.sound.lc3.Lc3.Duration._5M;
import static google.sound.lc3.Lc3.Duration._7M5;
import static google.sound.lc3.Tables.lc3_ne;


/**
 * TNS Arithmetic Coding
 */
class Tns {

    //
    // Bitstream data
    //

    //
    // Filter Coefficients
    //

    /**
     * Resolve LPC Weighting indication according bitrate
     *
     * @param dt     Duration of the frame
     * @param nBytes size of the frame
     * @return True when LPC Weighting enabled
     */
    private static boolean resolve_lpc_weighting(Duration dt, int nBytes) {
        return nBytes * 8 < 120 * (1 + dt.ordinal());
    }

    /**
     * Return dot product of 2 vectors
     *
     * @param a The vector
     * @param b The vector
     * @param n The size `n`
     * @return sum(a[i] * b[i]), i = [0..n-1]
     */
    private static float dot(float[] a, int ap, float[] b, int bp, int n) {
        float v = 0;

        while (n-- != 0)
            v += a[ap++] * b[bp++];

        return v;
    }

    private static final int[] sub_2m5_nb = {3, 10, 20};
    private static final int[] sub_2m5_wb = {3, 20, 40};
    private static final int[] sub_2m5_sswb = {3, 30, 60};
    private static final int[] sub_2m5_swb = {3, 40, 80};
    private static final int[] sub_2m5_fb = {3, 51, 100};

    private static final int[] sub_5m_nb = {6, 23, 40};
    private static final int[] sub_5m_wb = {6, 43, 80};
    private static final int[] sub_5m_sswb = {6, 63, 120};
    private static final int[] sub_5m_swb = {6, 43, 80, 120, 160};
    private static final int[] sub_5m_fb = {6, 53, 100, 150, 200};

    private static final int[] sub_7m5_nb = {9, 26, 43, 60};
    private static final int[] sub_7m5_wb = {9, 46, 83, 120};
    private static final int[] sub_7m5_sswb = {9, 66, 123, 180};
    private static final int[] sub_7m5_swb = {9, 46, 82, 120, 159, 200, 240};
    private static final int[] sub_7m5_fb = {9, 56, 103, 150, 200, 250, 300};

    private static final int[] sub_10m_nb = {12, 34, 57, 80};
    private static final int[] sub_10m_wb = {12, 61, 110, 160};
    private static final int[] sub_10m_sswb = {12, 88, 164, 240};
    private static final int[] sub_10m_swb = {12, 61, 110, 160, 213, 266, 320};
    private static final int[] sub_10m_fb = {12, 74, 137, 200, 266, 333, 400};

    private static final float[] lag_window = {
            1.00000000e+00f, 9.98028026e-01f, 9.92135406e-01f, 9.82391584e-01f,
            9.68910791e-01f, 9.51849807e-01f, 9.31404933e-01f, 9.07808230e-01f,
            8.81323137e-01f
    };

    /** <NUM_DURATION, NUM_BANDWIDTH> */
    private static final Map<Duration, int[][]> subTable = Map.of(

            _2M5, new int[][] {
                    sub_2m5_nb, sub_2m5_wb, sub_2m5_sswb, sub_2m5_swb,
                    sub_2m5_fb, sub_2m5_fb, sub_2m5_fb
            },
            _5M, new int[][] {
                    sub_5m_nb, sub_5m_wb, sub_5m_sswb, sub_5m_swb,
                    sub_5m_fb, sub_5m_fb, sub_5m_fb
            },
            _7M5, new int[][] {
                    sub_7m5_nb, sub_7m5_wb, sub_7m5_sswb, sub_7m5_swb,
                    sub_7m5_fb
            },
            _10M, new int[][] {
                    sub_10m_nb, sub_10m_wb, sub_10m_sswb, sub_10m_swb,
                    sub_10m_fb, sub_10m_fb, sub_10m_fb
            }
    );

    /**
     * LPC Coefficients
     *
     * @param dt       Duration of the frame
     * @param bw       bandwidth of the frame
     * @param maxOrder Maximum order of filter
     * @param x        Spectral coefficients
     * @param gain     Output the prediction gains
     * @param a        LPC coefficients
     */
    private static void compute_lpc_coeffs(Duration dt, BandWidth bw, int maxOrder,
                                           float[] x, int xp, float[] gain, float[][/* 9 */] a) {

        int[] sub = subTable.get(dt)[bw.ordinal()];

        // Normalized auto-correlation

        int nFilters = 1 + ((dt.ordinal() >= _5M.ordinal() && bw.ordinal() >= SWB.ordinal()) ? 1 : 0);
        int nSubdivisions = 2 + (dt.ordinal() >= _7M5.ordinal() ? 1 : 0);

        int subP = 0; // sub
        int xs;
        int xe = xp + sub[subP]; // x
        float[][] r = new float[2][9];

        for (int f = 0; f < nFilters; f++) {
            float[][] c = new float[9][3];

            for (int s = 0; s < nSubdivisions; s++) {
                xs = xe;
                xe = sub[++subP]; // x

                for (int k = 0; k <= maxOrder; k++)
                    c[k][s] = dot(x, xs, x, xs + k, (xe - xs) - k);
            }

            r[f][0] = nSubdivisions;
            if (nSubdivisions == 2) {
                float e0 = c[0][0], e1 = c[0][1];
                for (int k = 1; k <= maxOrder; k++)
                    r[f][k] = e0 == 0 || e1 == 0 ? 0 :
                            (c[k][0] / e0 + c[k][1] / e1) * lag_window[k];

            } else {
                float e0 = c[0][0], e1 = c[0][1], e2 = c[0][2];
                for (int k = 1; k <= maxOrder; k++)
                    r[f][k] = e0 == 0 || e1 == 0 || e2 == 0 ? 0 :
                            (c[k][0] / e0 + c[k][1] / e1 + c[k][2] / e2) * lag_window[k];
            }
        }

        // Levinson-Durbin recursion

        for (int f = 0; f < nFilters; f++) {
            float[] a0 = a[f];
            float[] a1 = new float[9];
            float err = r[f][0], rc;

            gain[f] = err;

            a0[0] = 1;
            for (int k = 1; k <= maxOrder; ) {

                rc = -r[f][k];
                for (int i = 1; i < k; i++)
                    rc -= a0[i] * r[f][k - i];

                rc /= err;
                err *= 1 - rc * rc;

                for (int i = 1; i < k; i++)
                    a1[i] = a0[i] + rc * a0[k - i];
                a1[k++] = rc;

                rc = -r[f][k];
                for (int i = 1; i < k; i++)
                    rc -= a1[i] * r[f][k - i];

                rc /= err;
                err *= 1 - rc * rc;

                for (int i = 1; i < k; i++)
                    a0[i] = a1[i] + rc * a1[k - i];
                a0[k++] = rc;
            }

            gain[f] /= err;
        }
    }

    /**
     * LPC Weighting
     *
     * @param predGain Prediction gain
     * @param a        LPC coefficients, weighted as output
     */
    private static void lpc_weighting(float predGain, float[] a) {
        float gamma = 1.f - (1.f - 0.85f) * (2.f - predGain) / (2.f - 1.5f);
        float g = 1.f;

        for (int i = 1; i < 9; i++)
            a[i] *= (g *= gamma);
    }

    /**
     * LPC reflection
     *
     * @param a        LPC coefficients
     * @param maxOrder maximum order (4 or 8)
     * @param rc       Output reflection coefficients
     */
    private static void lpc_reflection(float[] a, int maxOrder, float[] rc) {
        float e;
        float[][] b = new float[2][7];
        float[] b0;
        float[] b1;

        rc[maxOrder - 1] = a[maxOrder];
        e = 1 - rc[maxOrder - 1] * rc[maxOrder - 1];

        b1 = b[1];
        for (int i = 0; i < maxOrder - 1; i++)
            b1[i] = (a[1 + i] - rc[maxOrder - 1] * a[(maxOrder - 1) - i]) / e;

        for (int k = maxOrder - 2; k > 0; k--) {
            b0 = b1;
            b1 = b[k & 1];

            rc[k] = b0[k];
            e = 1 - rc[k] * rc[k];

            for (int i = 0; i < k; i++)
                b1[i] = (b0[i] - rc[k] * b0[k - 1 - i]) / e;
        }

        rc[0] = b1[0];
    }

    /**
     * Quantization table, sin(delta * (i + 0.5)), delta = Pi / 17,
     * rounded to fixed point Q15 value (LC3-Plus HR requirements).
     */
    private static final float[] q_thr = {
            0x0bcfp-15f, 0x2307p-15f, 0x390ep-15f, 0x4d23p-15f,
            0x5e98p-15f, 0x6cd4p-15f, 0x775bp-15f, 0x7dd2p-15f,
    };

    /**
     * Quantization of RC coefficients
     *
     * @param rc       Reflection coefficients
     * @param maxorder maximum order (4 or 8)
     * @param order    Return order of coefficients
     * @param rc_q     Return quantized coefficients
     */
    private static void quantize_rc(final float[] rc, int maxorder, int[] order, int orderP, int[] rc_q) {

        order[orderP] = maxorder;

        for (int i = 0; i < maxorder; i++) {
            float rc_m = Math.abs(rc[i]);

            rc_q[i] = 4 * (rc_m >= q_thr[4] ? 1 : 0);
            int j = 0;
            while (j < 4 && rc_m >= q_thr[rc_q[i]]) {
                j++;
                rc_q[i]++;
            }

            if (rc[i] < 0)
                rc_q[i] = -rc_q[i];

            order[orderP] = rc_q[i] != 0 ? maxorder : order[orderP] - 1;
        }
    }

    /**
     * Quantization table, sin(delta * i), delta = Pi / 17,
     * rounded to fixed point Q15 value (LC3-Plus HR requirements).
     */
    private static final float[] q_inv = {
            0x0000p-15f, 0x1785p-15f, 0x2e3dp-15f, 0x4362p-15f,
            0x563cp-15f, 0x6625p-15f, 0x7295p-15f, 0x7b1dp-15f, 0x7f74p-15f,
    };

    /**
     * Unquantization of RC coefficients
     *
     * @param rc_q  Quantized coefficients
     * @param order order
     * @param rc    Return reflection coefficients
     */
    private static void unquantize_rc(final int[] rc_q, int order, float[/* 8 */] rc) {
        for (int i = 0; i < order; i++) {
            float rc_m = q_inv[Math.abs(rc_q[i])];
            rc[i] = rc_q[i] < 0 ? -rc_m : rc_m;
        }
    }

    //
    // Filtering
    //

    /**
     * Forward filtering
     *
     * @param dt       Duration of the frame
     * @param bw       bandwidth of the frame
     * @param rc_order Order of coefficients
     * @param rc       coefficients
     * @param x        Spectral coefficients, filtered as output
     */
    private static void forward_filtering(Duration dt, BandWidth bw,
                                          int[/* 2 */] rc_order, float[][/* 8 */] rc, float[] x) {

        int nFilters = 1 + (dt.ordinal() >= _5M.ordinal() && bw.ordinal() >= SWB.ordinal() ? 1 : 0);
        int nf = lc3_ne(dt, Lc3.SRate.values()[Math.min(bw.ordinal(), FB.ordinal())]) >> (nFilters - 1);
        int i0, ie = 3 * (1 + dt.ordinal());

        float[] s = new float[8];

        for (int f = 0; f < nFilters; f++) {

            i0 = ie;
            ie = nf * (1 + f);

            if (rc_order[f] == 0)
                continue;

            for (int i = i0; i < ie; i++) {
                float xi = x[i];
                float s0, s1 = xi;

                for (int k = 0; k < rc_order[f]; k++) {
                    s0 = s[k];
                    s[k] = s1;

                    s1 = rc[f][k] * xi + s0;
                    xi += rc[f][k] * s0;
                }

                x[i] = xi;
            }
        }
    }

    /**
     * Inverse filtering
     *
     * @param dt       Duration of the frame
     * @param bw       bandwidth of the frame
     * @param rc_order Order of coefficients
     * @param rc       unquantized coefficients
     * @param x        Spectral coefficients, filtered as output
     */
    private static void inverse_filtering(Duration dt, BandWidth bw,
                                          int[/* 2 */] rc_order, float[][/* 8 */] rc, float[] x, int xp) {

        int nFilters = 1 + (dt.ordinal() >= _5M.ordinal() && bw.ordinal() >= SWB.ordinal() ? 1 : 0);
        int nf = lc3_ne(dt, Lc3.SRate.values()[Math.min(bw.ordinal(), FB.ordinal())]) >> (nFilters - 1);
        int i0, ie = 3 * (1 + dt.ordinal());

        float[] s = new float[8];

        for (int f = 0; f < nFilters; f++) {

            i0 = ie;
            ie = nf * (1 + f);

            if (rc_order[f] == 0)
                continue;

            for (int i = i0; i < ie; i++) {
                float xi = x[xp + i];

                xi -= s[7] * rc[f][7];
                for (int k = 6; k >= 0; k--) {
                    xi -= s[k] * rc[f][k];
                    s[k + 1] = s[k] + rc[f][k] * xi;
                }
                s[0] = xi;
                x[xp + i] = xi;
            }

            for (int k = 7; k >= rc_order[f]; k--)
                s[k] = 0;
        }
    }

    private int nFilters;
    private boolean lpcWeighting;
    private final int[] rcOrder = new int[2];
    private final int[][] rc = new int[2][8];

    //
    // Encoding
    //

    /**
     * TNS analysis
     *
     * @param dt     Duration of the frame
     * @param bw     bandwidth of the frame
     * @param nnFlag True when high energy detected near Nyquist frequency
     * @param nBytes Size in bytes of the frame
     * @param data   Return bitstream data
     * @param x      Spectral coefficients, filtered as output
     */
    static void lc3_tns_analyze(Duration dt, BandWidth bw, boolean nnFlag, int nBytes, Tns data, float[] x, int xp) {
        // Processing steps :
        // - Determine the LPC (Linear Predictive Coding) Coefficients
        // - Check is the filtering is disabled
        // - The coefficients are weighted on low bitrates and predicition gain
        // - Convert to reflection coefficients and quantize
        // - Finally filter the spectral coefficients

        float[] predGain = new float[2];
        float[][] a = new float[2][9];
        float[][] rc = new float[2][8];

        data.lpcWeighting = resolve_lpc_weighting(dt, nBytes);
        data.nFilters = 1 + (dt.ordinal() >= _5M.ordinal() && bw.ordinal() >= SWB.ordinal() ? 1 : 0);
        int maxOrder = dt.ordinal() <= _5M.ordinal() ? 4 : 8;

        compute_lpc_coeffs(dt, bw, maxOrder, x, xp, predGain, a);

        for (int f = 0; f < data.nFilters; f++) {

            data.rcOrder[f] = 0;
            if (nnFlag || predGain[f] <= 1.5f)
                continue;

            if (data.lpcWeighting && predGain[f] < 2.f)
                lpc_weighting(predGain[f], a[f]);

            lpc_reflection(a[f], maxOrder, rc[f]);

            quantize_rc(rc[f], maxOrder, data.rcOrder, f, data.rc[f]);
            unquantize_rc(data.rc[f], data.rcOrder[f], rc[f]);
        }

        forward_filtering(dt, bw, data.rcOrder, rc, x);
    }

    /**
     * Return number of bits coding the data
     *
     * @param data Bitstream data
     * @return Bit consumption
     */
    static int lc3_tns_get_nbits(Tns data) {
        int nBits = 0;

        for (int f = 0; f < data.nFilters; f++) {

            int nbits_2048 = 2048;
            int rc_order = data.rcOrder[f];

            nbits_2048 += rc_order > 0 ? lc3_tns_order_bits[data.lpcWeighting ? 1 : 0][rc_order - 1] : 0;

            for (int i = 0; i < rc_order; i++)
                nbits_2048 += lc3_tns_coeffs_bits[i][8 + data.rc[f][i]];

            nBits += (nbits_2048 + (1 << 11) - 1) >> 11;
        }

        return nBits;
    }

    /**
     * Put bitstream data
     *
     * @param bits Bitstream context
     * @param data Bitstream data
     */
    static void lc3_tns_put_data(Bits bits, Tns data) {
        for (int f = 0; f < data.nFilters; f++) {
            int rc_order = data.rcOrder[f];

            bits.lc3_put_bits(rc_order > 0 ? 1 : 0, 1);
            if (rc_order <= 0)
                continue;

            bits.lc3_put_symbol(lc3_tns_order_models[data.lpcWeighting ? 1 : 0], rc_order - 1);

            for (int i = 0; i < rc_order; i++)
                bits.lc3_put_symbol(lc3_tns_coeffs_models[i], 8 + data.rc[f][i]);
        }
    }

    //
    // Decoding
    //

    /**
     * Get bitstream data
     *
     * @param bits   Bitstream context
     * @param dt     Duration of the frame
     * @param bw     bandwidth of the frame
     * @param nBytes Size in bytes of the frame
     * @param data   Bitstream data
     * @return 0: Ok  -1: Invalid bitstream data
     */
    static int lc3_tns_get_data(Bits bits, Duration dt, BandWidth bw, int nBytes, Tns data) {
        data.nFilters = 1 + ((dt.ordinal() >= _5M.ordinal() && bw.ordinal() >= SWB.ordinal()) ? 1 : 0);
        data.lpcWeighting = resolve_lpc_weighting(dt, nBytes);

        for (int f = 0; f < data.nFilters; f++) {

            data.rcOrder[f] = bits.lc3_get_bit();
            if (data.rcOrder[f] == 0)
                continue;

            data.rcOrder[f] += bits.lc3_get_symbol(lc3_tns_order_models[data.lpcWeighting ? 1 : 0]);
            if (dt.ordinal() <= _5M.ordinal() && data.rcOrder[f] > 4)
                return -1;

            for (int i = 0; i < data.rcOrder[f]; i++)
                data.rc[f][i] = (int) bits.lc3_get_symbol(lc3_tns_coeffs_models[i]) - 8;
        }

        return 0;
    }

    /**
     * TNS synthesis
     *
     * @param dt   Duration  of the frame
     * @param bw   bandwidth of the frame
     * @param data Bitstream data
     * @param x    Spectral coefficients, filtered as output
     */
    static void lc3_tns_synthesize(Duration dt, BandWidth bw, Tns data, float[] x, int xp) {
        float[][] rc = new float[2][8];

        for (int f = 0; f < data.nFilters; f++)
            if (data.rcOrder[f] != 0)
                unquantize_rc(data.rc[f], data.rcOrder[f], rc[f]);

        inverse_filtering(dt, bw, data.rcOrder, rc, x, xp);
    }

    private static final AcModel[] lc3_tns_order_models = {
            new AcModel(new int[][] {
                    {0, 3}, {3, 9}, {12, 23}, {35, 54},
                    {89, 111}, {200, 190}, {390, 268}, {658, 366},
                    {1024, 0}, {1024, 0}, {1024, 0}, {1024, 0},
                    {1024, 0}, {1024, 0}, {1024, 0}, {1024, 0},
                    {1024, 0}
            }),
            new AcModel(new int[][] {
                    {0, 14}, {14, 42}, {56, 100}, {156, 157},
                    {313, 181}, {494, 178}, {672, 167}, {839, 185},
                    {1024, 0}, {1024, 0}, {1024, 0}, {1024, 0},
                    {1024, 0}, {1024, 0}, {1024, 0}, {1024, 0},
                    {1024, 0}
            }),
    };

    private static final short[][] lc3_tns_order_bits = {
            {17234, 13988, 11216, 8694, 6566, 4977, 3961, 3040},
            {12683, 9437, 6874, 5541, 5121, 5170, 5359, 5056}
    };

    private static final AcModel[] lc3_tns_coeffs_models = {

            new AcModel(new int[][] {
                    {0, 1}, {1, 5}, {6, 15}, {21, 31},
                    {52, 54}, {106, 86}, {192, 97}, {289, 120},
                    {409, 159}, {568, 152}, {720, 111}, {831, 104},
                    {935, 59}, {994, 22}, {1016, 6}, {1022, 1},
                    {1023, 1}
            }),
            new AcModel(new int[][] {
                    {0, 1}, {1, 1}, {2, 1}, {3, 1},
                    {4, 13}, {17, 43}, {60, 94}, {154, 139},
                    {293, 173}, {466, 160}, {626, 154}, {780, 131},
                    {911, 78}, {989, 27}, {1016, 6}, {1022, 1},
                    {1023, 1}
            }),
            new AcModel(new int[][] {
                    {0, 1}, {1, 1}, {2, 1}, {3, 1},
                    {4, 9}, {13, 43}, {56, 106}, {162, 199},
                    {361, 217}, {578, 210}, {788, 141}, {929, 74},
                    {1003, 17}, {1020, 1}, {1021, 1}, {1022, 1},
                    {1023, 1}
            }),
            new AcModel(new int[][] {
                    {0, 1}, {1, 1}, {2, 1}, {3, 1},
                    {4, 2}, {6, 11}, {17, 49}, {66, 204},
                    {270, 285}, {555, 297}, {852, 120}, {972, 39},
                    {1011, 9}, {1020, 1}, {1021, 1}, {1022, 1},
                    {1023, 1}
            }),
            new AcModel(new int[][] {
                    {0, 1}, {1, 1}, {2, 1}, {3, 1},
                    {4, 1}, {5, 7}, {12, 42}, {54, 241},
                    {295, 341}, {636, 314}, {950, 58}, {1008, 9},
                    {1017, 3}, {1020, 1}, {1021, 1}, {1022, 1},
                    {1023, 1}
            }),
            new AcModel(new int[][] {
                    {0, 1}, {1, 1}, {2, 1}, {3, 1},
                    {4, 1}, {5, 1}, {6, 13}, {19, 205},
                    {224, 366}, {590, 377}, {967, 47}, {1014, 5},
                    {1019, 1}, {1020, 1}, {1021, 1}, {1022, 1},
                    {1023, 1}
            }),
            new AcModel(new int[][] {
                    {0, 1}, {1, 1}, {2, 1}, {3, 1},
                    {4, 1}, {5, 1}, {6, 13}, {19, 281},
                    {300, 330}, {630, 371}, {1001, 17}, {1018, 1},
                    {1019, 1}, {1020, 1}, {1021, 1}, {1022, 1},
                    {1023, 1}
            }),
            new AcModel(new int[][] {
                    {0, 1}, {1, 1}, {2, 1}, {3, 1},
                    {4, 1}, {5, 1}, {6, 5}, {11, 297},
                    {308, 1}, {309, 682}, {991, 26}, {1017, 2},
                    {1019, 1}, {1020, 1}, {1021, 1}, {1022, 1},
                    {1023, 1}
            })
    };

    private static final short[][] lc3_tns_coeffs_bits = {

            {20480, 15725, 12479, 10334, 8694, 7320, 6964, 6335,
                    5504, 5637, 6566, 6758, 8433, 11348, 15186, 20480, 20480},

            {20480, 20480, 20480, 20480, 12902, 9368, 7057, 5901,
                    5254, 5485, 5598, 6076, 7608, 10742, 15186, 20480, 20480},

            {20480, 20480, 20480, 20480, 13988, 9368, 6702, 4841,
                    4585, 4682, 5859, 7764, 12109, 20480, 20480, 20480, 20480},

            {20480, 20480, 20480, 20480, 18432, 13396, 8982, 4767,
                    3779, 3658, 6335, 9656, 13988, 20480, 20480, 20480, 20480},

            {20480, 20480, 20480, 20480, 20480, 14731, 9437, 4275,
                    3249, 3493, 8483, 13988, 17234, 20480, 20480, 20480, 20480},

            {20480, 20480, 20480, 20480, 20480, 20480, 12902, 4753,
                    3040, 2953, 9105, 15725, 20480, 20480, 20480, 20480, 20480},

            {20480, 20480, 20480, 20480, 20480, 20480, 12902, 3821,
                    3346, 3000, 12109, 20480, 20480, 20480, 20480, 20480, 20480},

            {20480, 20480, 20480, 20480, 20480, 20480, 15725, 3658,
                    20480, 1201, 10854, 18432, 20480, 20480, 20480, 20480, 20480}

    };
}
