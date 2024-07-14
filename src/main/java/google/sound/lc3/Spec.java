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

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.stream.IntStream;

import google.sound.lc3.Bits.AcModel;
import google.sound.lc3.Lc3.BandWidth;
import google.sound.lc3.Lc3.Duration;
import google.sound.lc3.Lc3.SRate;

import static google.sound.lc3.BwDet.lc3_bwdet_get_nbits;
import static google.sound.lc3.Lc3.clip;
import static google.sound.lc3.Lc3.BandWidth.FB;
import static google.sound.lc3.Lc3.Duration._10M;
import static google.sound.lc3.Lc3.Duration._2M5;
import static google.sound.lc3.Lc3.Duration._5M;
import static google.sound.lc3.Lc3.Duration._7M5;
import static google.sound.lc3.Lc3.isHR;
import static google.sound.lc3.Lc3.SRate._48K_HR;
import static google.sound.lc3.Lc3.SRate._96K_HR;
import static google.sound.lc3.FastMath.lc3_db_q16;
import static google.sound.lc3.FastMath.lc3_exp2f;
import static google.sound.lc3.FastMath.lc3_ldexpf;
import static google.sound.lc3.Ltpf.lc3_ltpf_get_nbits;
import static google.sound.lc3.Sns.lc3_sns_get_nbits;
import static google.sound.lc3.Tables.LC3_MAX_NE;
import static google.sound.lc3.Tables.lc3_ne;


/**
 * Spectral Data Arithmetic Coding
 */
class Spec {

    static class Analysis {
        float nbits_off;
        int nbits_spare;
    }

    //
    // Global Gain / Quantization
    //

    /**
     * Resolve quantized gain index offset
     *
     * @param sr     sampleRate
     * @param nbytes size of the frame
     * @return Gain index offset
     */
    private static int resolve_gain_offset(SRate sr, int nbytes) {
        int sr_ind = isHR(sr) ? 4 + (sr.ordinal() - _48K_HR.ordinal()) : sr.ordinal();

        int g_off = (nbytes * 8) / (10 * (1 + sr_ind));
        return Math.min(sr.ordinal() >= _96K_HR.ordinal() ? 181 : 255,
                105 + 5 * (1 + sr_ind) + Math.min(g_off, 115));
    }

    private static final float[] iqTable = {
            1.00000000e+00f, 1.08571112e+00f, 1.17876863e+00f, 1.27980221e+00f,
            1.38949549e+00f, 1.50859071e+00f, 1.63789371e+00f, 1.77827941e+00f,
            1.93069773e+00f, 2.09617999e+00f, 2.27584593e+00f, 2.47091123e+00f,
            2.68269580e+00f, 2.91263265e+00f, 3.16227766e+00f, 3.43332002e+00f,
            3.72759372e+00f, 4.04708995e+00f, 4.39397056e+00f, 4.77058270e+00f,
            5.17947468e+00f, 5.62341325e+00f, 6.10540230e+00f, 6.62870316e+00f,
            7.19685673e+00f, 7.81370738e+00f, 8.48342898e+00f, 9.21055318e+00f
    };

    /**
     * Unquantize gain
     *
     * @param g_int Quantization gain value
     * @return Unquantized gain value
     */
    private static float unquantize_gain(int g_int) {
        // Unquantization gain table :
        // G[i] = 10 ^ (i / 28) , i = [0..27]

        float g = 1.f;

        while (g_int < 0) {
            g_int += 28;
            g *= 0.1f;
        }
        while (g_int >= 28) {
            g_int -= 28;
            g *= 10.f;
        }

        return g * iqTable[g_int];
    }

    private static final Map<Duration, int[]> reg_c_map = Map.of( /* NUM_DURATION, NUM_SRATE - _48K_HR */
            _2M5, new int[] {-6, -6},
            _5M, new int[] {0, 0},
            _10M, new int[] {2, 5}
    );

    /**
     * Global Gain Estimation
     *
     * @param dt,          Duration  of the frame
     * @param sr           sampleRate of the frame
     * @param x            Spectral coefficients
     * @param nBytes       Size of the frame
     * @param nBitsBudget Number of bits available coding the spectrum
     * @param nbits_off    Offset on the available bits, temporarily smoothed
     * @param g_off        Gain index offset
     * @param reset_off    Return True when the nbits_off must be reset
     * @param g_min        Return lower bound of quantized gain value
     * @return The quantized gain value
     */
    private static int estimate_gain(Duration dt, SRate sr, float[] x, int xp,
                              int nBytes, int nBitsBudget, float nbits_off, int g_off,
                              boolean[] reset_off, int[] g_min) {
        int n4 = lc3_ne(dt, sr) / 4;

        ByteBuffer[] e = new ByteBuffer[LC3_MAX_NE / 4];
        IntStream.range(0, e.length).forEach(i -> e[i] = ByteBuffer.allocate(Integer.BYTES));

        // Signal adaptative noise floor

        int reg_bits = 0;
        float low_bits = 0;

        if (isHR(sr)) {
            int reg_c = reg_c_map.get(dt)[sr.ordinal() - _48K_HR.ordinal()];

            reg_bits = (8 * nBytes * 4) / (125 * (1 + dt.ordinal()));
            reg_bits = clip(reg_bits + reg_c, 6, 23);

            float m0 = 1e-5f, m1 = 1e-5f, k = 0;

            for (int i = 0; i < n4; i++) {
                m0 += Math.abs(x[xp + 4 * i + 0]);
                m1 += Math.abs(x[xp + 4 * i + 0]) * k++;
                m0 += Math.abs(x[xp + 4 * i + 1]);
                m1 += Math.abs(x[xp + 4 * i + 1]) * k++;
                m0 += Math.abs(x[xp + 4 * i + 2]);
                m1 += Math.abs(x[xp + 4 * i + 2]) * k++;
                m0 += Math.abs(x[xp + 4 * i + 3]);
                m1 += Math.abs(x[xp + 4 * i + 3]) * k++;
            }

            int m = Math.round((1.6f * m0) / ((1 + dt.ordinal()) * m1));
            low_bits = 8 - Math.min(m, 8);
        }

        // Energy (dB) by 4 MDCT blocks

        float x2_max = 0;

        for (int i = 0; i < n4; i++) {
            float x0 = x[xp + 4 * i + 0] * x[xp + 4 * i + 0];
            float x1 = x[xp + 4 * i + 1] * x[xp + 4 * i + 1];
            float x2 = x[xp + 4 * i + 2] * x[xp + 4 * i + 2];
            float x3 = x[xp + 4 * i + 3] * x[xp + 4 * i + 3];

            x2_max = Math.max(x2_max, x0);
            x2_max = Math.max(x2_max, x1);
            x2_max = Math.max(x2_max, x2);
            x2_max = Math.max(x2_max, x3);

            e[i].putFloat(x0 + x1 + x2 + x3);
        }

        float x_max = (float) Math.sqrt(x2_max);
        float nf = isHR(sr) ? lc3_ldexpf(x_max, -reg_bits) * lc3_exp2f(-low_bits) : 0;

        for (int i = 0; i < n4; i++)
            e[i].putInt(lc3_db_q16(Math.max(e[i].getFloat() + nf, 1e-10f)));

        // Determine gain index

        int nbits = (int) (nBitsBudget + nbits_off + 0.5f);
        int g_int = 255 - g_off;

        final int k_20_28 = (int) (20.f / 28 * 0x1p16f + 0.5f);
        final int k_2u7 = (int) (2.7f * 0x1p16f + 0.5f);
        final int k_1u4 = (int) (1.4f * 0x1p16f + 0.5f);

        for (int i = 128, j, j0 = n4 - 1, j1; i > 0; i >>= 1) {
            int gn = (g_int - i) * k_20_28;
            int v = 0;

            j = j0;
            while (j >= 0 && e[j].getInt() < gn) {
                j--;
            }

            for (j1 = j; j >= 0; j--) {
                int e_diff = e[j].getInt() - gn;

                v += e_diff < 0 ? k_2u7 :
                        e_diff < 43 << 16 ? e_diff + (7 << 16)
                                : 2 * e_diff - (36 << 16);
            }

            if (v > nbits * k_1u4)
                j0 = j1;
            else
                g_int = g_int - i;
        }

        // Limit gain index

        float x_lim = isHR(sr) ? 0x7fffp8f : 0x7fffp0f;

        g_min[0] = 255 - g_off;
        for (int i = 128; i > 0; i >>= 1)
            if (x_lim * unquantize_gain(g_min[0] - i) > x_max)
                g_min[0] -= i;

        reset_off[0] = g_int < g_min[0] || x_max == 0;
        if (reset_off[0])
            g_int = g_min[0];

        return g_int;
    }

    /**
     * Global Gain Adjustment
     *
     * @param dt           Duration  of the frame
     * @param sr           sampleRate of the frame
     * @param g_idx        The estimated quantized gain index
     * @param nbits        Computed number of bits coding the spectrum
     * @param nbits_budget Number of bits available for coding the spectrum
     * @param g_idx_min    Minimum gain index value
     * @return Gain adjust value (-1 to 2)
     */
    private static int adjust_gain(Duration dt, SRate sr,
                                   int g_idx, int nbits, int nbits_budget, int g_idx_min) {

        // Compute delta threshold

        int[] t = new int[/* NUM_SRATE */][/* 3 */] {
                {80, 500, 850}, {230, 1025, 1700}, {380, 1550, 2550},
                {530, 2075, 3400}, {680, 2600, 4250},
                {680, 2600, 4250}, {830, 3125, 5100}
        }[sr.ordinal()];

        int delta, den = 48;

        if (nbits < t[0]) {
            delta = 3 * (nbits + 48);

        } else if (nbits < t[1]) {
            int n0 = 3 * (t[0] + 48), range = t[1] - t[0];
            delta = n0 * range + (nbits - t[0]) * (t[1] - n0);
            den *= range;

        } else {
            delta = Math.min(nbits, t[2]);
        }

        delta = (delta + den / 2) / den;

        // Adjust gain

        if (isHR(sr) && nbits > nbits_budget) {
            int factor = 1 + (dt.ordinal() <= _5M.ordinal() ? 1 : 0) +
                    (dt.ordinal() <= _2M5.ordinal() ? 1 : 0) * (1 + (nbits >= 520 ? 1 : 0));

            int g_incr = factor + (factor * (nbits - nbits_budget)) / delta;
            return Math.min(g_idx + g_incr, 255) - g_idx;
        }

        if (!isHR(sr) && nbits < nbits_budget - (delta + 2))
            return -(g_idx > g_idx_min ? 1 : 0);

        if (!isHR(sr) && nbits > nbits_budget)
            return (g_idx < 255 ? 1 : 0) + (g_idx < 254 && nbits >= nbits_budget + delta ? 1 : 0);

        return 0;
    }

    /**
     * Spectrum quantization
     *
     * @param dt    Duration of the frame
     * @param sr    sampleRate of the frame
     * @param g_int Quantization gain value
     * @param x     Spectral coefficients, scaled as output
     */
    private void quantize(Duration dt, SRate sr, int g_int, float[] x) {
        float g_inv = unquantize_gain(-g_int);
        int ne = lc3_ne(dt, sr);

        this.nq = ne;

        for (int i = 0; i < ne; i += 2) {
            float xq_min = isHR(sr) ? 0.5f : 10.f / 16;

            x[i + 0] *= g_inv;
            x[i + 1] *= g_inv;

            this.nq = Math.abs(x[i + 0]) >= xq_min || Math.abs(x[i + 1]) >= xq_min ? ne : this.nq - 2;
        }
    }

    /**
     * Spectrum quantization inverse
     *
     * @param dt    Duration of the frame
     * @param sr    sampleRate of the frame
     * @param g_int Quantization gain value
     * @param x     Spectral quantized of significants
     * @param nq    count of significants
     * @return Unquantized gain value
     */
    private static float unquantize(Duration dt, SRate sr, int g_int, float[] x, int xp, int nq) {
        float g = unquantize_gain(g_int);
        int i, ne = lc3_ne(dt, sr);

        for (i = 0; i < nq; i++)
            x[xp + i] = x[xp + i] * g;

        for (; i < ne; i++)
            x[xp + i] = 0;

        return g;
    }

    //
    // Spectrum coding
    //

    /**
     * Resolve High-bitrate and LSB modes according size of the frame
     *
     * @param sr         sampleRate of the frame
     * @param nbytes     size of the frame
     * @param p_lsb_mode True when LSB mode allowed, when not NULL
     * @return True when High-Rate mode enabled
     */
    private static boolean resolve_modes(SRate sr, int nbytes, boolean[] p_lsb_mode) {
        int sr_ind = isHR(sr) ? 4 + (sr.ordinal() - _48K_HR.ordinal()) : sr.ordinal();

        if (p_lsb_mode != null)
            p_lsb_mode[0] = (nbytes >= 20 * (3 + sr_ind)) && (sr.ordinal() < _96K_HR.ordinal());

        return (nbytes > 20 * (1 + sr_ind)) && (sr.ordinal() < _96K_HR.ordinal());
    }

    /**
     * Bit consumption
     *
     * @param dt           Duration of the frame
     * @param sr           sampleRate of the frame
     * @param nbytes       size of the frame
     * @param x            Spectral quantized coefficients
     * @param nbits_budget Truncate to stay in budget, when not zero
     * @param p_lsb_mode   set {@link #lsb_mode} True when LSB's are not AC coded, or false
     * @return The number of bits coding the spectrum
     */
    private int compute_nbits(Duration dt, SRate sr, int nbytes,
                              float[] x, int nbits_budget, boolean p_lsb_mode) {

        boolean[] lsb_mode = new boolean[1];
        boolean high_rate = resolve_modes(sr, nbytes, lsb_mode);
        int ne = lc3_ne(dt, sr);

        // Loop on quantized coefficients

        int nbits = 0, nbits_lsb = 0;
        byte state = 0;

        int nbits_end = 0;
        int n_end = 0;

        nbits_budget = nbits_budget != 0 ? nbits_budget * 2048 : Integer.MAX_VALUE;

        for (int i = 0, h = 0; h < 2; h++) {
            byte[/* 4 */][] lut_coeff = lc3_spectrum_lookup[high_rate ? 1 : 0][h];

            for (; i < Math.min(this.nq, (ne + 2) >> (1 - h)) && nbits <= nbits_budget; i += 2) {

                float xq_off = isHR(sr) ? 0.5f : 6.f / 16;
                int a = (int) (Math.abs(x[i + 0]) + xq_off);
                int b = (int) (Math.abs(x[i + 1]) + xq_off);

                byte[] lut = lut_coeff[state];

                // Sign values

                int s = (a != 0 ? 1 : 0) + (b != 0 ? 1 : 0);
                nbits += s * 2048;

                /* --- LSB values Reduce to 2*2 bits MSB values ---
                 * Reduce to 2x2 bits MSB values. The LSB's pair are arithmetic
                 * coded with an escape code followed by 1 bit for each values.
                 * The LSB mode does not arthmetic code the first LSB,
                 * add the sign of the LSB when one of pair was at value 1 */

                int m = (a | b) >> 2;
                int k = 0;

                if (m != 0) {

                    if (lsb_mode[0]) {
                        nbits += lc3_spectrum_bits[lut[k++]][16] - 2 * 2048;
                        nbits_lsb += 2 + (a == 1 ? 1 : 0) + (b == 1 ? 1 : 0);
                    }

                    for (m >>= lsb_mode[0] ? 1 : 0; m != 0; m >>= 1, k++)
                        nbits += lc3_spectrum_bits[lut[Math.min(k, 3)]][16];

                    nbits += k * 2 * 2048;
                    a >>= k;
                    b >>= k;

                    k = Math.min(k, 3);
                }

                // MSB values

                nbits += lc3_spectrum_bits[lut[k]][a + 4 * b];

                // Update state

                if (s != 0 && nbits <= nbits_budget) {
                    n_end = i + 2;
                    nbits_end = nbits;
                }

                state = (byte) ((state << 4) + (k > 1 ? 12 + k : 1 + (a + b) * (k + 1)));
            }
        }

        // Return

        this.nq = n_end;

        if (p_lsb_mode)
            this.lsb_mode = lsb_mode[0] && nbits_end + nbits_lsb * 2048 > nbits_budget;

        if (nbits_budget >= Integer.MAX_VALUE)
            nbits_end += nbits_lsb * 2048;

        return (nbits_end + 2047) / 2048;
    }

    /**
     * Put quantized spectrum
     *
     * @param bits Bitstream context
     * @param dt,  sr, nbytes  Duration, sampleRate and size of the frame
     * @param x    Spectral quantized coefficients
     * @param nq,  lsb_mode    Count of significants, and LSB discard indication
     */
    private static void put_quantized(Bits bits,
                                      Duration dt, SRate sr, int nbytes,
                                      float[] x, int xp, int nq, boolean lsb_mode) {

        boolean high_rate = resolve_modes(sr, nbytes, null);
        int ne = lc3_ne(dt, sr);

        // Loop on quantized coefficients

        int state = 0;

        for (int i = 0, h = 0; h < 2; h++) {
            byte[/* 4 */][] lut_coeff = lc3_spectrum_lookup[high_rate ? 1 : 0][h];

            for (; i < Math.min(nq, (ne + 2) >> (1 - h)); i += 2) {

                float xq_off = isHR(sr) ? 0.5f : 6.f / 16;
                int a = (int) (Math.abs(x[xp + i + 0]) + xq_off);
                int b = (int) (Math.abs(x[xp + i + 1]) + xq_off);

                byte[] lut = lut_coeff[state];

                // --- LSB values Reduce to 2*2 bits MSB values ---
                // Reduce to 2x2 bits MSB values. The LSB's pair are arithmetic
                // coded with an escape code and 1 bits for each values.
                // The LSB mode discard the first LSB (at this step)

                int m = (a | b) >> 2;
                int k = 0, shr = 0;

                if (m != 0) {

                    if (lsb_mode)
                        bits.lc3_put_symbol(lc3_spectrum_models[lut[k++]], 16);

                    for (m >>= lsb_mode ? 1 : 0; m != 0; m >>= 1, k++) {
                        bits.lc3_put_bit((a >> k) & 1);
                        bits.lc3_put_bit((b >> k) & 1);
                        bits.lc3_put_symbol(lc3_spectrum_models[lut[Math.min(k, 3)]], 16);
                    }

                    a >>= lsb_mode ? 1 : 0;
                    b >>= lsb_mode ? 1 : 0;

                    shr = k - (lsb_mode ? 1 : 0);
                    k = Math.min(k, 3);
                }

                // Sign values

                if (a != 0) bits.lc3_put_bit(x[xp + i + 0] < 0 ? 1 : 0);
                if (b != 0) bits.lc3_put_bit(x[xp + i + 1] < 0 ? 1 : 0);

                // MSB values

                a >>= shr;
                b >>= shr;

                bits.lc3_put_symbol(lc3_spectrum_models[lut[k]], a + 4 * b);

                // Update state

                state = (state << 4) + (k > 1 ? 12 + k : 1 + (a + b) * (k + 1));
            }
        }
    }

    /**
     * Get quantized spectrum
     *
     * @param bits    Bitstream context
     * @param dt,     sr, nBytes  Duration, sampleRate and size of the frame
     * @param nq,     lsb_mode    Count of significants, and LSB discard indication
     * @param x       Return `nq` spectral quantized coefficients
     * @param nf_seed Return the noise factor seed associated
     * @return 0: Ok  -1: Invalid bitstream data
     */
    private static int get_quantized(Bits bits,
                                     Duration dt, SRate sr, int nBytes,
                                     int nq, boolean lsb_mode, float[] x, int xp, short[] nf_seed) {

        boolean high_rate = resolve_modes(sr, nBytes, null);
        int ne = lc3_ne(dt, sr);

        nf_seed[0] = 0;

        // Loop on quantized coefficients

        int state = 0;

        for (int i = 0, h = 0; h < 2; h++) {
            byte[/*4*/][] lut_coeff = lc3_spectrum_lookup[high_rate ? 1 : 0][h];

            for (; i < Math.min(nq, (ne + 2) >> (1 - h)); i += 2) {

                byte[] lut = lut_coeff[state];
                int max_shl = isHR(sr) ? 22 : 14;

                // --- LSB values ---
                // Until the symbol read indicates the escape value 16,
                // read an LSB bit for each values.
                // The LSB mode discard the first LSB (at this step)

                int u = 0, v = 0;
                int k = 0, shl = 0;

                int s = bits.lc3_get_symbol(lc3_spectrum_models[lut[k]]);

                if (lsb_mode && s >= 16) {
                    s = bits.lc3_get_symbol(lc3_spectrum_models[lut[++k]]);
                    shl++;
                }

                for (; s >= 16 && shl < max_shl; shl++) {
                    u |= bits.lc3_get_bit() << shl;
                    v |= bits.lc3_get_bit() << shl;

                    k += (k < 3) ? 1 : 0;
                    s = bits.lc3_get_symbol(lc3_spectrum_models[lut[k]]);
                }

                if (s >= 16)
                    return -1;

                // MSB & sign values

                int a = s % 4;
                int b = s / 4;

                u |= a << shl;
                v |= b << shl;

                x[xp + i + 0] = u != 0 && bits.lc3_get_bit() != 0 ? -u : u;
                x[xp + i + 1] = v != 0 && bits.lc3_get_bit() != 0 ? -v : v;

                nf_seed[0] = (short) ((nf_seed[0] + (u & 0x7fff) * (i) + (v & 0x7fff) * (i + 1)) & 0xffff);

                // Update state

                state = ((state << 4) + (k > 1 ? 12 + k : 1 + (a + b) * (k + 1))) & 0xff;
            }
        }

        return 0;
    }

    /**
     * Put residual bits of quantization
     *
     * @param bits   Bitstream context
     * @param nbits  Maximum number of bits to output
     * @param hrmode High-Resolution mode
     * @param x      Spectral quantized
     * @param n      count of significants
     */
    private static void put_residual(Bits bits, int nbits, boolean hrmode, float[] x, int xp, int n) {
        float xq_lim = hrmode ? 0.5f : 10.f / 16;
        float xq_off = xq_lim / 2;

        for (int iter = 0; iter < (hrmode ? 20 : 1) && nbits > 0; iter++) {
            for (int i = 0; i < n && nbits > 0; i++) {

                float xr = Math.abs(x[xp + i]);
                if (xr < xq_lim)
                    continue;

                boolean b = (xr - Math.floor(xr) < xq_lim) ^ (x[xp + i] < 0);
                bits.lc3_put_bit(b ? 1 : 0);
                nbits--;

                x[xp + i] += b ? -xq_off : xq_off;
            }

            xq_off *= xq_lim;
        }
    }

    /**
     * Get residual bits of quantization
     *
     * @param bits   Bitstream context
     * @param nbits  Maximum number of bits to output
     * @param hrmode High-Resolution mode
     * @param x      Spectral quantized
     * @param n      count of significants
     */
    private static void get_residual(Bits bits, int nbits, boolean hrmode, float[] x, int xp, int n) {
        float xq_off_1 = hrmode ? 0.25f : 5.f / 16;
        float xq_off_2 = hrmode ? 0.25f : 3.f / 16;

        for (int iter = 0; iter < (hrmode ? 20 : 1) && nbits > 0; iter++) {
            for (int i = 0; i < n && nbits > 0; i++) {

                if (x[xp + i] == 0)
                    continue;

                if (bits.lc3_get_bit() == 0)
                    x[xp + i] -= x[xp + i] < 0 ? xq_off_1 : xq_off_2;
                else
                    x[xp + i] += x[xp + i] > 0 ? xq_off_1 : xq_off_2;

                nbits--;
            }

            xq_off_1 *= 0.5f;
            xq_off_2 *= 0.5f;
        }
    }

    /**
     * Put LSB values of quantized spectrum values
     *
     * @param bits   Bitstream context
     * @param nBits  Maximum number of bits to output
     * @param hrMode High-Resolution mode
     * @param x      Spectral quantized
     * @param n      count of significants
     */
    private static void put_lsb(Bits bits, int nBits, boolean hrMode, float[] x, int xp, int n) {
        for (int i = 0; i < n && nBits > 0; i += 2) {

            float xq_off = hrMode ? 0.5f : 6.f / 16;
            int a = (int) (Math.abs(x[xp + i + 0]) + xq_off);
            int b = (int) (Math.abs(x[xp + i + 1]) + xq_off);

            if ((a | b) >> 2 == 0)
                continue;

            if (nBits-- > 0)
                bits.lc3_put_bit(a & 1);

            if (a == 1 && nBits-- > 0)
                bits.lc3_put_bit(x[xp + i + 0] < 0 ? 1 : 0);

            if (nBits-- > 0)
                bits.lc3_put_bit(b & 1);

            if (b == 1 && nBits-- > 0)
                bits.lc3_put_bit(x[xp + i + 1] < 0 ? 1 : 0);
        }
    }

    /**
     * Get LSB values of quantized spectrum values
     *
     * @param bits    Bitstream context
     * @param nbits   Maximum number of bits to output
     * @param x       Spectral quantized of significants
     * @param nq      count of significants
     * @param nf_seed Update the noise factor seed according
     */
    private static void get_lsb(Bits bits, int nbits, float[] x, int xp, int nq, short[] nf_seed) {
        for (int i = 0; i < nq && nbits > 0; i += 2) {

            float a = Math.abs(x[xp + i]), b = Math.abs(x[xp + i + 1]);

            if (Math.max(a, b) < 4)
                continue;

            if (nbits-- > 0 && bits.lc3_get_bit() != 0) {
                if (a != 0) {
                    x[xp + i] += x[xp + i] < 0 ? -1 : 1;
                    nf_seed[0] = (short) ((nf_seed[0] + i) & 0xffff);
                } else if (nbits-- > 0) {
                    x[xp + i] = bits.lc3_get_bit() != 0 ? -1 : 1;
                    nf_seed[0] = (short) ((nf_seed[0] + i) & 0xffff);
                }
            }

            if (nbits-- > 0 && bits.lc3_get_bit() != 0) {
                if (b != 0) {
                    x[xp + i + 1] += x[xp + i + 1] < 0 ? -1 : 1;
                    nf_seed[0] = (short) ((nf_seed[0] + i + 1) & 0xffff);
                } else if (nbits-- > 0) {
                    x[xp + i + 1] = bits.lc3_get_bit() != 0 ? -1 : 1;
                    nf_seed[0] = (short) ((nf_seed[0] + i + 1) & 0xffff);
                }
            }
        }
    }

    //
    // Noise coding
    //

    /**
     * Estimate noise level
     *
     * @param dt     bandwidth of the frame
     * @param bw     Duration of the frame
     * @param hrMode High-Resolution mode
     * @param x      Spectral quantized
     * @param n      count of significants
     * @return Noise factor (0 to 7)
     */
    private static int estimate_noise(Duration dt, BandWidth bw, boolean hrMode, float[] x, int xp, int n) {

        int bw_stop = lc3_ne(dt, Lc3.SRate.values()[Math.min(bw.ordinal(), FB.ordinal())]);
        int w = 1 + (dt.ordinal() >= _7M5.ordinal() ? 1 : 0) + (dt.ordinal() >= _10M.ordinal() ? 1 : 0);

        float xq_lim = hrMode ? 0.5f : 10.f / 16;
        float sum = 0;
        int i, ns = 0, z = 0;

        for (i = 6 * (1 + dt.ordinal()) - w; i < Math.min(n, bw_stop); i++) {
            z = Math.abs(x[xp + i]) < xq_lim ? z + 1 : 0;
            if (z > 2 * w)
                sum += Math.abs(x[xp + i - w]);
            ns++;
        }

        for (; i < bw_stop + w; i++)
            if (++z > 2 * w)
                sum += Math.abs(x[xp + i - w]);
        ns++;

        int nf = ns != 0 ? 8 - (int) ((16 * sum) / ns + 0.5f) : 8;

        return clip(nf, 0, 7);
    }

    /**
     * Noise filling
     *
     * @param dt      Duration  of the frame
     * @param bw      bandwidth of the frame
     * @param nf      The noise factor
     * @param nf_seed pseudo-random seed
     * @param g       Quantization gain
     * @param x       Spectral quantized
     * @param nq      and count of significants
     */
    private static void fill_noise(Duration dt, BandWidth bw, int nf, short nf_seed, float g, float[] x, int xp, int nq) {

        int bw_stop = lc3_ne(dt, Lc3.SRate.values()[Math.min(bw.ordinal(), FB.ordinal())]);
        int w = 1 + (dt.ordinal() >= _7M5.ordinal() ? 1 : 0) + (dt.ordinal() >= _10M.ordinal() ? 1 : 0);

        float s = g * (float) (8 - nf) / 16;
        int i, z = 0;

        for (i = 6 * (1 + dt.ordinal()) - w; i < Math.min(nq, bw_stop); i++) {
            z = x[xp + i] != 0 ? 0 : z + 1;
            if (z > 2 * w) {
                nf_seed = (short) ((13849 + nf_seed * 31821) & 0xffff);
                x[xp + i - w] = (nf_seed & 0x8000) != 0 ? -s : s;
            }
        }

        for (; i < bw_stop + w; i++)
            if (++z > 2 * w) {
                nf_seed = (short) ((13849 + nf_seed * 31821) & 0xffff);
                x[xp + i - w] = (nf_seed & 0x8000) != 0 ? -s : s;
            }
    }

    /**
     * Put noise factor
     *
     * @param bits Bitstream context
     * @param nf   Noise factor (0 to 7)
     */
    private static void put_noise_factor(Bits bits, int nf) {
        bits.lc3_put_bits(nf, 3);
    }

    /**
     * Get noise factor
     *
     * @param bits Bitstream context
     * @return Noise factor (0 to 7)
     */
    private static int get_noise_factor(Bits bits) {
        return bits.lc3_get_bits(3);
    }

    //
    // Encoding
    //

    /**
     * Bit consumption of the number of coded coefficients
     *
     * @param dt  Duration of the frame
     * @param sr  sampleRate of the frame
     * @return Bit consumpution of the number of coded coefficients
     */
    private static int get_nbits_nq(Duration dt, SRate sr) {
        int ne = lc3_ne(dt, sr);
        return 4 + (ne > 32 ? 1 : 0) + (ne > 64 ? 1 : 0) + (ne > 128 ? 1 : 0) + (ne > 256 ? 1 : 0) + (ne > 512 ? 1 : 0);
    }

    /**
     * Bit consumption of the arithmetic coder
     *
     * @param dt  Duration of the frame
     * @param sr  sampleRate of the frame
     * @param nBytes size of the frame
     * @return Bit consumption of bitstream data
     */
    private static int get_nbits_ac(Duration dt, SRate sr, int nBytes) {
        return get_nbits_nq(dt, sr) + 3 + (isHR(sr) ? 1 : 0) + Math.min((nBytes - 1) / 160, 2);
    }

    private int g_idx;
    private int nq;
    private boolean lsb_mode;

    //
    // Encoding
    //

    /**
     * Spectrum analysis
     *
     * @param dt     Duration
     * @param sr,    sampleRate
     * @param nBytes size of the frame
     * @param pitch  Pitch present indication
     * @param tns    TNS bistream data
     * @param spec   Context of analysis
     * @param x      Spectral coefficients, scaled as output
     */
    void lc3_spec_analyze(Duration dt, SRate sr, int nBytes,
                          boolean pitch, Tns tns, Analysis spec,
                          float[] x, int xp) {

        boolean[] reset_off = new boolean[1];

        // Bit budget

        final int nbits_gain = 8;
        final int nbits_nf = 3;

        int nbits_budget = 8 * nBytes - get_nbits_ac(dt, sr, nBytes) -
                lc3_bwdet_get_nbits(sr) - lc3_ltpf_get_nbits(pitch) -
                lc3_sns_get_nbits() - tns.lc3_tns_get_nbits() - nbits_gain - nbits_nf;

        // Global gain

        float nbits_off = spec.nbits_off + spec.nbits_spare;
        nbits_off = Math.min(Math.max(nbits_off, -40), 40);
        nbits_off = 0.8f * spec.nbits_off + 0.2f * nbits_off;

        int g_off = resolve_gain_offset(sr, nBytes);

        int[] g_min = new int[1];
        int g_int = estimate_gain(dt, sr, x, xp, nBytes, nbits_budget, nbits_off, g_off, reset_off, g_min);

        // Quantization

        quantize(dt, sr, g_int, x);

        int nbits = compute_nbits(dt, sr, nBytes, x, 0, false);

        spec.nbits_off = reset_off[0] ? 0 : nbits_off;
        spec.nbits_spare = reset_off[0] ? 0 : nbits_budget - nbits;

        // Adjust gain and requantize

        int g_adj = adjust_gain(dt, sr, g_off + g_int, nbits, nbits_budget, g_off + g_min[0]);

        if (g_adj != 0)
            quantize(dt, sr, g_adj, x);

        this.g_idx = g_int + g_adj + g_off;
        nbits = compute_nbits(dt, sr, nBytes, x, nbits_budget, true);
    }


    /**
     * Put spectral quantization side data
     *
     * @param bits Bitstream context
     * @param dt   Duration  of the frame
     * @param sr   sampleRate of the frame
     */
    void lc3_spec_put_side(Bits bits, Duration dt, SRate sr) {
        int nbits_nq = get_nbits_nq(dt, sr);

        bits.lc3_put_bits(Math.max(this.nq >> 1, 1) - 1, nbits_nq);
        bits.lc3_put_bits(this.lsb_mode ? 1 : 0, 1);
        bits.lc3_put_bits(this.g_idx, 8);
    }

    /**
     * Encode spectral coefficients
     *
     * @param bits   Bitstream context
     * @param dt,    Duration
     * @param sr     sampleRate
     * @param bw     bandwidth
     * @param nbytes and size of the frame
     * @param x      and scaled coefficients
     */
    void lc3_spec_encode(Bits bits, Duration dt, SRate sr, BandWidth bw, int nbytes, float[] x, int xp) {

        boolean lsb_mode = this.lsb_mode;
        int nq = this.nq;

        put_noise_factor(bits, estimate_noise(dt, bw, isHR(sr), x, xp, nq));

        put_quantized(bits, dt, sr, nbytes, x, xp, nq, lsb_mode);

        int nbits_left = bits.lc3_get_bits_left();

        if (lsb_mode)
            put_lsb(bits, nbits_left, isHR(sr), x, xp, nq);
        else
            put_residual(bits, nbits_left, isHR(sr), x, xp, nq);
    }

    //
    // Decoding
    //

    /**
     * Get spectral quantization side data
     *
     * @param bits Bitstream context
     * @param dt   Duration of the frame
     * @param sr   sampleRate of the frame
     * @return 0: Ok  -1: Invalid bandwidth indication
     */
    int lc3_spec_get_side(Bits bits, Duration dt, SRate sr) {
        int nbits_nq = get_nbits_nq(dt, sr);
        int ne = lc3_ne(dt, sr);

        this.nq = (bits.lc3_get_bits(nbits_nq) + 1) << 1;
        this.lsb_mode = bits.lc3_get_bit() != 0;
        this.g_idx = bits.lc3_get_bits(8);

        if (this.nq > ne) {
            this.nq = ne;
            return -1;
        } else {
            return 0;
        }
    }

    /**
     * Decode spectral coefficients
     *
     * @param bits   Bitstream context
     * @param dt,    sr, bw      Duration, sampleRate, bandwidth
     * @param nBytes and size of the frame
     * @param x      Spectral coefficients
     * @return 0: Ok  -1: Invalid bitstream data
     */
    int lc3_spec_decode(Bits bits, Duration dt, SRate sr, BandWidth bw, int nBytes, float[] x, int xp) {
        boolean lsb_mode = this.lsb_mode;
        int nq = this.nq;
        int ret = 0;

        int nf = get_noise_factor(bits);
        short[] nf_seed = new short[1];

        if ((ret = get_quantized(bits, dt, sr, nBytes, nq, lsb_mode, x, xp, nf_seed)) < 0)
            return ret;

        int nbits_left = bits.lc3_get_bits_left();

        if (lsb_mode)
            get_lsb(bits, nbits_left, x, xp, nq, nf_seed);
        else
            get_residual(bits, nbits_left, isHR(sr), x, xp, nq);

        int g_int = this.g_idx - resolve_gain_offset(sr, nBytes);
        float g = unquantize(dt, sr, g_int, x, xp, nq);

        if (nq > 2 || x[0] != 0 || x[1] != 0 || this.g_idx > 0 || nf < 7)
            fill_noise(dt, bw, nf, nf_seed[0], g, x, xp, nq);

        return 0;
    }

//#region table.c

    /**
     * Spectral Data Arithmetic Coding
     * The number of bits are given at 2048th of bits
     * <p>
     * The dimensions of the lookup table are set as following :
     * 1: Rate selection
     * 2: Half spectrum selection (1st half / 2nd half)
     * 3: State of the arithmetic coder
     * 4: Number of msb bits (significant - 2), limited to 3
     * <p>
     * table[r][h][s][k] = table(normative)[s + h*256 + r*512 + k*1024]
     */
    private static final byte[][][][] lc3_spectrum_lookup = new byte[][][][] {{{
            {1, 13, 0, 0}, {39, 13, 0, 0}, {7, 13, 0, 0}, {25, 13, 0, 0},
            {22, 13, 0, 0}, {22, 13, 0, 0}, {28, 13, 0, 0}, {22, 13, 0, 0},
            {22, 60, 0, 0}, {22, 60, 0, 0}, {22, 60, 0, 0}, {28, 60, 0, 0},
            {28, 60, 0, 0}, {28, 60, 13, 0}, {34, 60, 13, 0}, {31, 16, 13, 0},
            {31, 16, 13, 0}, {40, 0, 0, 0}, {43, 0, 0, 0}, {46, 0, 0, 0},
            {49, 0, 0, 0}, {52, 0, 0, 0}, {14, 0, 0, 0}, {17, 0, 0, 0},
            {36, 0, 0, 0}, {36, 0, 0, 0}, {36, 0, 0, 0}, {38, 0, 0, 0},
            {0, 0, 0, 0}, {57, 0, 0, 0}, {38, 13, 0, 0}, {22, 60, 0, 0},
            {0, 0, 0, 0}, {8, 0, 0, 0}, {9, 0, 0, 0}, {11, 0, 0, 0},
            {47, 0, 0, 0}, {14, 0, 0, 0}, {14, 0, 0, 0}, {17, 0, 0, 0},
            {36, 0, 0, 0}, {36, 0, 0, 0}, {36, 0, 0, 0}, {38, 0, 0, 0},
            {59, 0, 0, 0}, {59, 0, 0, 0}, {38, 13, 0, 0}, {22, 60, 0, 0},
            {22, 60, 0, 0}, {26, 0, 0, 0}, {46, 0, 0, 0}, {29, 0, 0, 0},
            {30, 0, 0, 0}, {32, 0, 0, 0}, {33, 0, 0, 0}, {35, 0, 0, 0},
            {36, 0, 0, 0}, {36, 0, 0, 0}, {36, 0, 0, 0}, {38, 0, 0, 0},
            {0, 13, 0, 0}, {59, 13, 0, 0}, {23, 13, 0, 0}, {22, 60, 0, 0},
            {46, 60, 0, 0}, {46, 0, 0, 0}, {45, 0, 0, 0}, {47, 0, 0, 0},
            {48, 0, 0, 0}, {50, 0, 0, 0}, {50, 0, 0, 0}, {18, 0, 0, 0},
            {54, 0, 0, 0}, {54, 0, 0, 0}, {54, 0, 0, 0}, {38, 0, 0, 0},
            {59, 13, 0, 0}, {59, 13, 0, 0}, {59, 13, 0, 0}, {22, 60, 0, 0},
            {0, 60, 0, 0}, {62, 0, 0, 0}, {63, 0, 0, 0}, {3, 0, 0, 0},
            {33, 0, 0, 0}, {2, 0, 0, 0}, {2, 0, 0, 0}, {61, 0, 0, 0},
            {20, 0, 0, 0}, {20, 0, 0, 0}, {20, 13, 0, 0}, {21, 13, 0, 0},
            {59, 13, 0, 0}, {59, 13, 0, 0}, {39, 13, 0, 0}, {28, 60, 0, 0},
            {28, 60, 0, 0}, {63, 0, 0, 0}, {63, 0, 0, 0}, {3, 0, 0, 0},
            {33, 0, 0, 0}, {2, 0, 0, 0}, {2, 0, 0, 0}, {61, 0, 0, 0},
            {38, 0, 0, 0}, {38, 0, 0, 0}, {38, 13, 0, 0}, {21, 13, 0, 0},
            {59, 13, 0, 0}, {59, 13, 0, 0}, {39, 13, 0, 0}, {28, 60, 0, 0},
            {28, 60, 0, 0}, {6, 0, 0, 0}, {6, 0, 0, 0}, {6, 0, 0, 0},
            {2, 0, 0, 0}, {18, 0, 0, 0}, {61, 0, 0, 0}, {20, 0, 0, 0},
            {21, 0, 0, 0}, {21, 0, 0, 0}, {21, 13, 0, 0}, {59, 13, 0, 0},
            {39, 13, 0, 0}, {39, 13, 0, 0}, {7, 13, 0, 0}, {34, 60, 13, 0},
            {34, 60, 13, 0}, {34, 60, 13, 0}, {34, 60, 13, 0}, {34, 60, 13, 0},
            {34, 60, 13, 0}, {34, 60, 13, 0}, {34, 60, 13, 0}, {34, 60, 13, 0},
            {34, 60, 13, 0}, {34, 60, 13, 0}, {34, 60, 13, 0}, {34, 60, 13, 0},
            {34, 60, 13, 0}, {34, 60, 13, 0}, {34, 60, 13, 0}, {34, 60, 13, 0},
            {34, 60, 13, 0}, {51, 0, 0, 0}, {51, 0, 0, 0}, {51, 0, 0, 0},
            {53, 0, 0, 0}, {54, 0, 0, 0}, {20, 0, 0, 0}, {38, 0, 0, 0},
            {38, 0, 0, 0}, {57, 0, 0, 0}, {39, 13, 0, 0}, {39, 13, 0, 0},
            {39, 13, 0, 0}, {7, 13, 0, 0}, {24, 13, 0, 0}, {34, 60, 13, 0},
            {4, 60, 0, 0}, {4, 60, 0, 0}, {4, 60, 0, 0}, {4, 60, 0, 0},
            {4, 60, 0, 0}, {4, 60, 0, 0}, {4, 60, 0, 0}, {4, 60, 0, 0},
            {4, 60, 0, 0}, {4, 60, 0, 0}, {4, 60, 0, 0}, {4, 60, 0, 0},
            {4, 60, 0, 0}, {4, 60, 0, 0}, {4, 60, 0, 0}, {4, 60, 0, 0},
            {4, 60, 0, 0}, {4, 0, 0, 0}, {4, 0, 0, 0}, {4, 0, 0, 0},
            {4, 0, 0, 0}, {56, 0, 0, 0}, {38, 0, 0, 0}, {57, 0, 0, 0},
            {57, 13, 0, 0}, {59, 13, 0, 0}, {7, 13, 0, 0}, {7, 13, 0, 0},
            {7, 13, 0, 0}, {42, 13, 0, 0}, {42, 13, 0, 0}, {34, 60, 13, 0},
            {0, 60, 13, 0}, {0, 60, 13, 0}, {0, 60, 13, 0}, {0, 60, 13, 0},
            {0, 60, 13, 0}, {0, 60, 13, 0}, {0, 60, 13, 0}, {0, 60, 13, 0},
            {0, 60, 13, 0}, {0, 60, 13, 0}, {0, 60, 13, 0}, {0, 60, 13, 0},
            {0, 60, 13, 0}, {0, 60, 13, 0}, {0, 60, 13, 0}, {0, 60, 13, 0},
            {0, 60, 13, 0}, {5, 0, 0, 0}, {4, 0, 0, 0}, {4, 0, 0, 0},
            {5, 0, 0, 0}, {21, 0, 0, 0}, {21, 0, 0, 0}, {59, 13, 0, 0},
            {7, 13, 0, 0}, {7, 13, 0, 0}, {7, 13, 0, 0}, {7, 13, 0, 0},
            {25, 13, 0, 0}, {25, 13, 0, 0}, {25, 13, 0, 0}, {34, 60, 13, 0},
            {4, 13, 0, 0}, {4, 13, 0, 0}, {4, 13, 0, 0}, {4, 13, 0, 0},
            {5, 13, 0, 0}, {23, 13, 0, 0}, {23, 13, 0, 0}, {39, 13, 0, 0},
            {7, 13, 0, 0}, {7, 13, 0, 0}, {7, 13, 0, 0}, {42, 13, 0, 0},
            {25, 13, 0, 0}, {25, 13, 0, 0}, {22, 13, 0, 0}, {31, 60, 13, 0},
            {31, 60, 13, 0}, {39, 60, 0, 0}, {39, 60, 0, 0}, {39, 60, 0, 0},
            {39, 60, 0, 0}, {7, 60, 0, 0}, {7, 60, 0, 0}, {42, 60, 0, 0},
            {0, 60, 0, 0}, {25, 60, 0, 0}, {22, 60, 0, 0}, {22, 60, 0, 0},
            {22, 60, 0, 0}, {28, 60, 0, 0}, {34, 60, 0, 0}, {31, 16, 13, 0}
    }, {
            {55, 0, 13, 0}, {55, 0, 13, 0}, {55, 0, 13, 0}, {55, 0, 13, 0},
            {55, 0, 13, 0}, {55, 0, 13, 0}, {55, 0, 13, 0}, {55, 0, 13, 0},
            {55, 0, 13, 0}, {55, 0, 13, 0}, {55, 0, 13, 0}, {55, 0, 13, 0},
            {55, 0, 13, 0}, {55, 0, 13, 0}, {55, 0, 13, 0}, {55, 0, 13, 0},
            {55, 0, 13, 0}, {55, 0, 0, 0}, {40, 0, 0, 0}, {8, 0, 0, 0},
            {9, 0, 0, 0}, {49, 0, 0, 0}, {49, 0, 0, 0}, {52, 0, 0, 0},
            {17, 0, 0, 0}, {17, 0, 0, 0}, {17, 0, 0, 0}, {4, 13, 0, 0},
            {0, 13, 0, 0}, {20, 13, 0, 0}, {17, 0, 0, 0}, {60, 13, 60, 13},
            {40, 0, 0, 13}, {40, 0, 0, 0}, {8, 0, 0, 0}, {43, 0, 0, 0},
            {27, 0, 0, 0}, {49, 0, 0, 0}, {49, 0, 0, 0}, {14, 0, 0, 0},
            {17, 0, 0, 0}, {17, 0, 0, 0}, {17, 0, 0, 0}, {36, 0, 0, 0},
            {42, 13, 0, 0}, {42, 13, 0, 0}, {17, 0, 0, 0}, {57, 60, 13, 0},
            {57, 0, 13, 0}, {40, 0, 0, 0}, {8, 0, 0, 0}, {26, 0, 0, 0},
            {27, 0, 0, 0}, {49, 0, 0, 0}, {12, 0, 0, 0}, {14, 0, 0, 0},
            {17, 0, 0, 0}, {17, 0, 0, 0}, {17, 0, 0, 0}, {36, 0, 0, 0},
            {0, 0, 13, 0}, {38, 0, 13, 0}, {36, 13, 0, 0}, {1, 60, 0, 0},
            {8, 60, 0, 0}, {8, 0, 0, 0}, {43, 0, 0, 0}, {9, 0, 0, 0},
            {11, 0, 0, 0}, {49, 0, 0, 0}, {12, 0, 0, 0}, {14, 0, 0, 0},
            {14, 0, 13, 0}, {33, 0, 13, 0}, {50, 0, 13, 0}, {50, 0, 0, 0},
            {50, 0, 13, 0}, {61, 0, 13, 0}, {36, 13, 0, 0}, {39, 60, 0, 0},
            {8, 60, 0, 0}, {8, 0, 0, 0}, {43, 0, 0, 0}, {46, 0, 0, 0},
            {49, 0, 0, 0}, {52, 0, 0, 0}, {30, 0, 0, 0}, {14, 0, 0, 0},
            {14, 0, 13, 0}, {33, 0, 13, 0}, {50, 0, 13, 0}, {50, 0, 13, 0},
            {50, 13, 13, 0}, {50, 13, 0, 0}, {18, 13, 13, 0}, {25, 60, 13, 0},
            {8, 60, 13, 13}, {8, 0, 0, 13}, {43, 0, 0, 13}, {46, 0, 0, 13},
            {49, 0, 0, 13}, {52, 0, 0, 0}, {30, 0, 0, 0}, {14, 0, 0, 0},
            {14, 0, 0, 0}, {18, 0, 60, 0}, {5, 0, 0, 13}, {5, 0, 0, 13},
            {5, 0, 0, 13}, {61, 13, 0, 13}, {18, 13, 13, 0}, {23, 13, 60, 0},
            {43, 13, 0, 13}, {43, 0, 0, 13}, {43, 0, 0, 13}, {9, 0, 0, 13},
            {49, 0, 0, 13}, {52, 0, 0, 0}, {3, 0, 0, 0}, {14, 0, 0, 0},
            {14, 0, 0, 0}, {50, 0, 0, 0}, {50, 13, 13, 0}, {50, 13, 13, 0},
            {50, 13, 13, 0}, {61, 0, 0, 0}, {17, 13, 13, 0}, {24, 60, 13, 0},
            {43, 60, 13, 0}, {43, 60, 13, 0}, {43, 60, 13, 0}, {43, 60, 13, 0},
            {43, 60, 13, 0}, {43, 60, 13, 0}, {43, 60, 13, 0}, {43, 60, 13, 0},
            {43, 60, 13, 0}, {43, 60, 13, 0}, {43, 60, 13, 0}, {43, 60, 13, 0},
            {43, 60, 13, 0}, {43, 60, 13, 0}, {43, 60, 13, 0}, {43, 60, 13, 0},
            {43, 60, 13, 0}, {43, 0, 0, 0}, {43, 0, 19, 0}, {9, 0, 0, 0},
            {11, 0, 0, 0}, {52, 0, 0, 0}, {52, 0, 0, 0}, {14, 0, 0, 0},
            {14, 0, 0, 0}, {17, 0, 0, 0}, {61, 13, 0, 0}, {61, 13, 0, 0},
            {61, 13, 0, 0}, {54, 0, 0, 0}, {17, 0, 13, 13}, {39, 13, 13, 0},
            {45, 13, 13, 0}, {45, 13, 13, 0}, {45, 13, 13, 0}, {45, 13, 13, 0},
            {45, 13, 13, 0}, {45, 13, 13, 0}, {45, 13, 13, 0}, {45, 13, 13, 0},
            {45, 13, 13, 0}, {45, 13, 13, 0}, {45, 13, 13, 0}, {45, 13, 13, 0},
            {45, 13, 13, 0}, {45, 13, 13, 0}, {45, 13, 13, 0}, {45, 13, 13, 0},
            {45, 13, 13, 0}, {45, 0, 13, 0}, {44, 0, 13, 0}, {27, 0, 0, 0},
            {29, 0, 0, 0}, {52, 0, 0, 0}, {48, 0, 0, 0}, {52, 0, 0, 0},
            {52, 0, 0, 0}, {17, 0, 0, 0}, {17, 0, 0, 0}, {17, 0, 19, 0},
            {17, 0, 13, 0}, {2, 0, 13, 0}, {17, 0, 13, 0}, {7, 13, 0, 0},
            {27, 0, 0, 13}, {27, 0, 0, 13}, {27, 0, 0, 13}, {27, 0, 0, 13},
            {27, 0, 0, 13}, {27, 0, 0, 13}, {27, 0, 0, 13}, {27, 0, 0, 13},
            {27, 0, 0, 13}, {27, 0, 0, 13}, {27, 0, 0, 13}, {27, 0, 0, 13},
            {27, 0, 0, 13}, {27, 0, 0, 13}, {27, 0, 0, 13}, {27, 0, 0, 13},
            {27, 0, 0, 13}, {27, 0, 0, 13}, {9, 0, 0, 13}, {27, 0, 0, 13},
            {27, 0, 0, 13}, {12, 0, 0, 13}, {52, 0, 0, 13}, {14, 0, 0, 13},
            {14, 0, 0, 13}, {58, 0, 0, 13}, {41, 0, 0, 13}, {41, 0, 0, 13},
            {41, 0, 0, 13}, {6, 0, 0, 13}, {17, 60, 0, 13}, {37, 0, 19, 13},
            {9, 0, 0, 13}, {9, 16, 0, 13}, {9, 0, 0, 13}, {27, 0, 0, 13},
            {11, 0, 0, 13}, {49, 0, 0, 0}, {12, 0, 0, 0}, {52, 0, 0, 0},
            {14, 0, 0, 0}, {14, 0, 0, 0}, {14, 0, 0, 0}, {50, 0, 0, 0},
            {0, 0, 0, 13}, {53, 0, 0, 13}, {17, 0, 0, 13}, {28, 0, 13, 0},
            {52, 0, 13, 0}, {52, 0, 13, 0}, {49, 0, 13, 0}, {52, 0, 0, 0},
            {12, 0, 0, 0}, {52, 0, 0, 0}, {30, 0, 0, 0}, {14, 0, 0, 0},
            {14, 0, 0, 0}, {17, 0, 0, 0}, {2, 0, 0, 0}, {2, 0, 0, 0},
            {2, 0, 0, 0}, {38, 0, 0, 0}, {38, 0, 0, 0}, {34, 0, 0, 0}
    }}, {{
            {31, 16, 60, 13}, {34, 16, 13, 0}, {34, 16, 13, 0}, {31, 16, 13, 0},
            {31, 16, 13, 0}, {31, 16, 13, 0}, {31, 16, 13, 0}, {19, 16, 60, 0},
            {19, 16, 60, 0}, {19, 16, 60, 0}, {19, 16, 60, 0}, {19, 16, 60, 0},
            {19, 16, 60, 0}, {19, 16, 60, 0}, {31, 16, 60, 13}, {19, 37, 16, 60},
            {44, 0, 0, 60}, {44, 0, 0, 0}, {62, 0, 0, 0}, {30, 0, 0, 0},
            {32, 0, 0, 0}, {58, 0, 0, 0}, {35, 0, 0, 0}, {36, 0, 0, 0},
            {36, 0, 0, 0}, {38, 13, 0, 0}, {0, 13, 0, 0}, {59, 13, 0, 0},
            {7, 13, 0, 0}, {7, 13, 0, 0}, {39, 13, 0, 0}, {34, 60, 13, 0},
            {34, 0, 13, 0}, {45, 0, 0, 0}, {47, 0, 0, 0}, {48, 0, 0, 0},
            {33, 0, 0, 0}, {35, 0, 0, 0}, {35, 0, 0, 0}, {36, 0, 0, 0},
            {38, 13, 0, 0}, {38, 13, 0, 0}, {38, 13, 0, 0}, {59, 13, 0, 0},
            {7, 13, 0, 0}, {7, 13, 0, 0}, {39, 13, 0, 0}, {34, 60, 13, 0},
            {34, 0, 13, 0}, {62, 0, 0, 0}, {30, 0, 0, 0}, {15, 0, 0, 0},
            {50, 0, 0, 0}, {53, 0, 0, 0}, {53, 0, 0, 0}, {54, 13, 0, 0},
            {21, 13, 0, 0}, {21, 13, 0, 0}, {21, 13, 0, 0}, {59, 13, 0, 0},
            {7, 13, 0, 0}, {7, 13, 0, 0}, {7, 13, 0, 0}, {34, 60, 13, 0},
            {30, 0, 13, 0}, {30, 0, 0, 0}, {48, 0, 0, 0}, {33, 0, 0, 0},
            {58, 0, 0, 0}, {18, 0, 0, 0}, {18, 0, 0, 0}, {56, 13, 0, 0},
            {23, 13, 0, 0}, {23, 13, 0, 0}, {23, 13, 0, 0}, {59, 13, 0, 0},
            {7, 13, 0, 0}, {7, 13, 0, 0}, {24, 13, 0, 0}, {34, 60, 13, 0},
            {34, 0, 13, 0}, {6, 0, 0, 0}, {6, 0, 0, 0}, {58, 0, 0, 0},
            {53, 0, 0, 0}, {54, 0, 0, 0}, {54, 0, 0, 0}, {21, 13, 0, 0},
            {59, 13, 0, 0}, {59, 13, 0, 0}, {59, 13, 0, 0}, {39, 13, 0, 0},
            {7, 13, 0, 0}, {7, 13, 0, 0}, {42, 60, 0, 0}, {34, 16, 13, 0},
            {6, 0, 13, 0}, {6, 0, 0, 0}, {33, 0, 0, 0}, {58, 0, 0, 0},
            {53, 0, 0, 0}, {54, 0, 0, 0}, {61, 0, 0, 0}, {21, 13, 0, 0},
            {59, 13, 0, 0}, {59, 13, 0, 0}, {59, 13, 0, 0}, {39, 13, 0, 0},
            {7, 13, 0, 0}, {7, 13, 0, 0}, {42, 60, 0, 0}, {34, 16, 13, 0},
            {34, 0, 13, 0}, {51, 0, 0, 0}, {51, 0, 0, 0}, {53, 0, 0, 0},
            {54, 0, 0, 0}, {56, 13, 0, 0}, {56, 13, 0, 0}, {57, 13, 0, 0},
            {39, 13, 0, 0}, {39, 13, 0, 0}, {39, 13, 0, 0}, {7, 13, 0, 0},
            {42, 13, 0, 0}, {42, 13, 0, 0}, {25, 60, 0, 0}, {31, 16, 13, 0},
            {31, 0, 13, 0}, {31, 0, 13, 0}, {31, 0, 13, 0}, {31, 0, 13, 0},
            {31, 0, 13, 0}, {31, 0, 13, 0}, {31, 0, 13, 0}, {31, 0, 13, 0},
            {31, 0, 13, 0}, {31, 0, 13, 0}, {31, 0, 13, 0}, {31, 0, 13, 0},
            {31, 0, 13, 0}, {31, 0, 13, 0}, {31, 0, 13, 0}, {31, 0, 13, 0},
            {31, 0, 13, 0}, {4, 0, 0, 0}, {4, 0, 0, 0}, {4, 0, 0, 0},
            {5, 13, 0, 0}, {23, 13, 0, 0}, {23, 13, 0, 0}, {39, 13, 0, 0},
            {7, 13, 0, 0}, {7, 13, 0, 0}, {7, 13, 0, 0}, {42, 13, 0, 0},
            {25, 13, 0, 0}, {25, 13, 0, 0}, {22, 60, 0, 0}, {31, 16, 60, 0},
            {31, 13, 0, 0}, {31, 13, 0, 0}, {31, 13, 0, 0}, {31, 13, 0, 0},
            {31, 13, 0, 0}, {31, 13, 0, 0}, {31, 13, 0, 0}, {31, 13, 0, 0},
            {31, 13, 0, 0}, {31, 13, 0, 0}, {31, 13, 0, 0}, {31, 13, 0, 0},
            {31, 13, 0, 0}, {31, 13, 0, 0}, {31, 13, 0, 0}, {31, 13, 0, 0},
            {31, 13, 0, 0}, {5, 13, 0, 0}, {5, 13, 0, 0}, {5, 13, 0, 0},
            {5, 13, 0, 0}, {57, 13, 0, 0}, {57, 13, 0, 0}, {39, 13, 0, 0},
            {24, 13, 0, 0}, {24, 13, 0, 0}, {24, 13, 0, 0}, {42, 13, 0, 0},
            {22, 13, 0, 0}, {22, 60, 0, 0}, {28, 60, 13, 0}, {31, 16, 60, 0},
            {31, 13, 0, 0}, {31, 13, 0, 0}, {31, 13, 0, 0}, {31, 13, 0, 0},
            {31, 13, 0, 0}, {31, 13, 0, 0}, {31, 13, 0, 0}, {31, 13, 0, 0},
            {31, 13, 0, 0}, {31, 13, 0, 0}, {31, 13, 0, 0}, {31, 13, 0, 0},
            {31, 13, 0, 0}, {31, 13, 0, 0}, {31, 13, 0, 0}, {31, 13, 0, 0},
            {31, 13, 0, 0}, {41, 13, 0, 0}, {41, 13, 0, 0}, {41, 13, 0, 0},
            {41, 13, 0, 0}, {39, 13, 0, 0}, {39, 13, 0, 0}, {7, 13, 0, 0},
            {42, 13, 0, 0}, {42, 13, 0, 0}, {42, 13, 0, 0}, {25, 13, 0, 0},
            {28, 13, 0, 0}, {28, 60, 0, 0}, {28, 60, 13, 0}, {31, 16, 60, 13},
            {31, 13, 0, 0}, {41, 13, 0, 0}, {41, 13, 0, 0}, {41, 13, 0, 0},
            {41, 13, 0, 0}, {39, 13, 0, 0}, {39, 13, 0, 0}, {24, 13, 0, 0},
            {25, 60, 0, 0}, {25, 60, 0, 0}, {25, 60, 0, 0}, {22, 60, 0, 0},
            {28, 60, 0, 0}, {28, 60, 0, 0}, {34, 60, 13, 0}, {31, 16, 60, 13},
            {31, 60, 13, 13}, {10, 60, 13, 0}, {10, 60, 13, 0}, {10, 60, 13, 0},
            {10, 60, 13, 0}, {10, 60, 13, 0}, {10, 60, 13, 0}, {28, 60, 13, 0},
            {34, 60, 13, 0}, {34, 60, 13, 0}, {34, 16, 13, 0}, {34, 16, 13, 0},
            {34, 16, 60, 0}, {34, 16, 60, 0}, {31, 16, 60, 0}, {19, 37, 16, 13}
    }, {
            {8, 0, 16, 0}, {8, 0, 16, 0}, {8, 0, 16, 0}, {8, 0, 16, 0},
            {8, 0, 16, 0}, {8, 0, 16, 0}, {8, 0, 16, 0}, {8, 0, 16, 0},
            {8, 0, 16, 0}, {8, 0, 16, 0}, {8, 0, 16, 0}, {8, 0, 16, 0},
            {8, 0, 16, 0}, {8, 0, 16, 0}, {8, 0, 16, 0}, {8, 0, 16, 0},
            {8, 0, 16, 0}, {8, 0, 0, 0}, {9, 0, 0, 0}, {11, 0, 0, 0},
            {47, 0, 0, 0}, {32, 0, 0, 0}, {50, 0, 0, 0}, {18, 0, 0, 0},
            {18, 0, 0, 0}, {20, 0, 0, 0}, {21, 0, 0, 0}, {21, 0, 0, 0},
            {21, 13, 0, 0}, {39, 13, 0, 0}, {59, 13, 0, 0}, {34, 16, 60, 0},
            {26, 0, 0, 0}, {26, 0, 0, 0}, {27, 0, 0, 0}, {29, 0, 0, 0},
            {30, 0, 0, 0}, {33, 0, 0, 0}, {50, 0, 0, 0}, {18, 0, 0, 0},
            {18, 0, 0, 0}, {20, 0, 0, 0}, {57, 0, 0, 0}, {57, 13, 0, 0},
            {57, 13, 0, 0}, {59, 13, 0, 0}, {59, 13, 0, 0}, {34, 16, 60, 0},
            {27, 0, 0, 0}, {27, 0, 0, 0}, {11, 0, 0, 0}, {12, 0, 0, 0},
            {48, 0, 0, 0}, {50, 0, 0, 0}, {58, 0, 0, 0}, {61, 0, 0, 0},
            {61, 0, 0, 0}, {56, 0, 0, 0}, {57, 13, 0, 0}, {57, 13, 0, 0},
            {57, 13, 0, 0}, {59, 13, 0, 0}, {39, 13, 0, 0}, {34, 16, 60, 0},
            {45, 0, 0, 0}, {45, 0, 0, 0}, {12, 0, 0, 0}, {30, 0, 0, 0},
            {32, 0, 0, 0}, {2, 0, 0, 0}, {2, 0, 0, 0}, {61, 0, 0, 0},
            {38, 0, 0, 0}, {38, 0, 0, 0}, {38, 13, 0, 0}, {57, 13, 0, 0},
            {0, 13, 0, 0}, {59, 13, 0, 0}, {39, 13, 0, 0}, {34, 16, 60, 0},
            {63, 0, 0, 0}, {63, 0, 0, 0}, {3, 0, 0, 0}, {32, 0, 0, 0},
            {58, 0, 0, 0}, {18, 0, 0, 0}, {18, 0, 0, 0}, {20, 0, 0, 0},
            {21, 0, 0, 0}, {21, 0, 0, 0}, {21, 13, 0, 0}, {59, 13, 0, 0},
            {39, 13, 0, 0}, {39, 13, 0, 0}, {7, 13, 13, 0}, {31, 16, 60, 0},
            {31, 0, 0, 0}, {3, 0, 0, 0}, {3, 0, 0, 0}, {33, 0, 0, 0},
            {58, 0, 0, 0}, {18, 0, 0, 0}, {18, 0, 0, 0}, {20, 0, 0, 0},
            {21, 0, 0, 0}, {21, 0, 0, 0}, {21, 13, 0, 0}, {59, 13, 0, 0},
            {7, 13, 0, 0}, {7, 13, 0, 0}, {7, 13, 13, 0}, {31, 16, 60, 0},
            {6, 0, 0, 0}, {6, 0, 0, 0}, {51, 0, 0, 0}, {51, 0, 0, 0},
            {53, 0, 0, 0}, {54, 0, 0, 0}, {54, 0, 0, 0}, {38, 0, 0, 0},
            {57, 13, 0, 0}, {57, 13, 0, 0}, {57, 13, 0, 0}, {39, 13, 0, 0},
            {7, 13, 0, 0}, {7, 13, 0, 0}, {42, 60, 13, 0}, {31, 16, 60, 0},
            {31, 0, 0, 0}, {31, 0, 0, 0}, {31, 0, 0, 0}, {31, 0, 0, 0},
            {31, 0, 0, 0}, {31, 0, 0, 0}, {31, 0, 0, 0}, {31, 0, 0, 0},
            {31, 0, 0, 0}, {31, 0, 0, 0}, {31, 0, 0, 0}, {31, 0, 0, 0},
            {31, 0, 0, 0}, {31, 0, 0, 0}, {31, 0, 0, 0}, {31, 0, 0, 0},
            {31, 0, 0, 0}, {51, 0, 0, 0}, {53, 0, 0, 0}, {53, 0, 0, 0},
            {54, 0, 0, 0}, {56, 0, 0, 0}, {56, 0, 0, 0}, {57, 13, 0, 0},
            {59, 13, 0, 0}, {59, 13, 0, 0}, {59, 13, 0, 0}, {7, 13, 0, 0},
            {24, 13, 0, 0}, {24, 13, 0, 0}, {25, 60, 13, 0}, {31, 16, 60, 0},
            {31, 0, 0, 0}, {31, 0, 0, 0}, {31, 0, 0, 0}, {31, 0, 0, 0},
            {31, 0, 0, 0}, {31, 0, 0, 0}, {31, 0, 0, 0}, {31, 0, 0, 0},
            {31, 0, 0, 0}, {31, 0, 0, 0}, {31, 0, 0, 0}, {31, 0, 0, 0},
            {31, 0, 0, 0}, {31, 0, 0, 0}, {31, 0, 0, 0}, {31, 0, 0, 0},
            {31, 0, 0, 0}, {4, 0, 0, 0}, {4, 0, 0, 0}, {4, 0, 0, 0},
            {54, 0, 0, 0}, {21, 13, 0, 0}, {21, 0, 0, 0}, {57, 13, 0, 0},
            {39, 13, 0, 0}, {39, 13, 0, 0}, {39, 13, 0, 0}, {7, 13, 0, 0},
            {42, 13, 13, 0}, {42, 13, 13, 0}, {22, 60, 13, 0}, {31, 16, 60, 0},
            {31, 16, 0, 0}, {31, 16, 0, 0}, {31, 16, 0, 0}, {31, 16, 0, 0},
            {31, 16, 0, 0}, {31, 16, 0, 0}, {31, 16, 0, 0}, {31, 16, 0, 0},
            {31, 16, 0, 0}, {31, 16, 0, 0}, {31, 16, 0, 0}, {31, 16, 0, 0},
            {31, 16, 0, 0}, {31, 16, 0, 0}, {31, 16, 0, 0}, {31, 16, 0, 0},
            {31, 16, 0, 0}, {5, 0, 0, 0}, {5, 0, 0, 0}, {5, 0, 0, 0},
            {5, 13, 0, 0}, {23, 13, 0, 0}, {23, 13, 0, 0}, {59, 13, 0, 0},
            {7, 13, 0, 0}, {7, 13, 0, 0}, {7, 13, 13, 0}, {42, 13, 13, 0},
            {22, 60, 13, 0}, {22, 60, 13, 0}, {28, 60, 13, 0}, {31, 16, 60, 0},
            {31, 13, 0, 0}, {4, 13, 0, 0}, {4, 13, 0, 0}, {4, 13, 0, 0},
            {5, 13, 0, 0}, {23, 13, 0, 0}, {23, 13, 0, 0}, {39, 13, 13, 0},
            {24, 60, 13, 0}, {24, 60, 13, 0}, {24, 60, 13, 0}, {25, 60, 13, 0},
            {28, 60, 13, 0}, {28, 60, 13, 0}, {34, 16, 13, 0}, {31, 16, 60, 0},
            {31, 16, 13, 0}, {10, 16, 13, 0}, {10, 16, 13, 0}, {10, 16, 13, 0},
            {10, 16, 13, 0}, {10, 16, 60, 0}, {10, 16, 60, 0}, {28, 16, 60, 0},
            {34, 16, 60, 0}, {34, 16, 60, 0}, {34, 16, 60, 0}, {31, 16, 60, 0},
            {31, 16, 60, 0}, {31, 16, 60, 0}, {31, 16, 60, 0}, {19, 37, 60, 0}
    }}};

    private static final short[][] lc3_spectrum_bits = new short[][/* 17 */] {

            {20480, 20480, 5220, 9042, 20480, 20480, 6619, 9892,
                    5289, 6619, 9105, 11629, 8982, 9892, 11629, 13677, 4977},

            {11940, 10854, 12109, 13677, 10742, 9812, 11090, 12288,
                    11348, 10240, 11348, 12683, 12109, 10854, 11629, 12902, 1197},

            {7886, 7120, 8982, 10970, 7496, 6815, 8334, 10150,
                    9437, 8535, 9656, 11216, 11348, 10431, 11348, 12479, 4051},

            {5485, 6099, 9168, 11940, 6311, 6262, 8640, 11090,
                    9233, 8640, 10334, 12479, 11781, 11090, 12479, 13988, 6009},

            {7886, 7804, 10150, 11940, 7886, 7685, 9368, 10854,
                    10061, 9300, 10431, 11629, 11629, 10742, 11485, 12479, 2763},

            {9042, 8383, 10240, 11781, 8483, 8013, 9437, 10742,
                    10334, 9437, 10431, 11485, 11781, 10742, 11485, 12288, 2346},

            {5922, 6619, 9368, 11940, 6566, 6539, 8750, 10970,
                    9168, 8640, 10240, 12109, 11485, 10742, 11940, 13396, 5009},

            {12288, 11090, 11348, 12109, 11090, 9892, 10334, 10970,
                    11629, 10431, 10970, 11629, 12479, 11348, 11781, 12288, 1289},

            {1685, 5676, 13138, 18432, 5598, 7804, 13677, 18432,
                    12683, 13396, 17234, 20480, 17234, 17234, 20480, 20480, 15725},

            {2793, 5072, 10970, 15725, 5204, 6487, 11216, 15186,
                    10970, 11216, 14336, 17234, 15186, 15186, 17234, 18432, 12109},

            {12902, 11485, 11940, 13396, 11629, 10531, 11348, 12479,
                    12683, 11629, 12288, 13138, 13677, 12683, 13138, 13677, 854},

            {3821, 5088, 9812, 13988, 5289, 5901, 9812, 13677,
                    9976, 9892, 12479, 15186, 13988, 13677, 15186, 17234, 9812},

            {4856, 5412, 9168, 12902, 5598, 5736, 8863, 12288,
                    9368, 8982, 11090, 13677, 12902, 12288, 13677, 15725, 8147},

            {20480, 20480, 7088, 9300, 20480, 20480, 7844, 9733,
                    7320, 7928, 9368, 10970, 9581, 9892, 10970, 12288, 2550},

            {6031, 5859, 8192, 10635, 6410, 6286, 8433, 10742,
                    9656, 9042, 10531, 12479, 12479, 11629, 12902, 14336, 5756},

            {6144, 6215, 8982, 11940, 6262, 6009, 8433, 11216,
                    8982, 8433, 10240, 12479, 11781, 11090, 12479, 13988, 5817},

            {20480, 20480, 11216, 12109, 20480, 20480, 11216, 11940,
                    11629, 11485, 11940, 12479, 12479, 12109, 12683, 13138, 704},

            {7928, 6994, 8239, 9733, 7218, 6539, 8147, 9892,
                    9812, 9105, 10240, 11629, 12109, 11216, 12109, 13138, 4167},

            {8640, 7724, 9233, 10970, 8013, 7185, 8483, 10150,
                    9656, 8694, 9656, 10970, 11348, 10334, 11090, 12288, 3391},

            {20480, 18432, 18432, 18432, 18432, 18432, 18432, 18432,
                    18432, 18432, 18432, 18432, 18432, 18432, 18432, 18432, 91},

            {10061, 8863, 9733, 11090, 8982, 7970, 8806, 9976,
                    10061, 9105, 9812, 10742, 11485, 10334, 10970, 11781, 2557},

            {10431, 9368, 10240, 11348, 9368, 8433, 9233, 10334,
                    10431, 9437, 10061, 10970, 11781, 10635, 11216, 11940, 2119},

            {13988, 12479, 12683, 12902, 12683, 11348, 11485, 11940,
                    12902, 11629, 11940, 12288, 13396, 12109, 12479, 12683, 828},

            {10431, 9300, 10334, 11629, 9508, 8483, 9437, 10635,
                    10635, 9656, 10431, 11348, 11940, 10854, 11485, 12288, 1946},

            {12479, 11216, 11629, 12479, 11348, 10150, 10635, 11348,
                    11940, 10854, 11216, 11940, 12902, 11629, 11940, 12479, 1146},

            {13396, 12109, 12288, 12902, 12109, 10854, 11216, 11781,
                    12479, 11348, 11629, 12109, 13138, 11940, 12288, 12683, 928},

            {2443, 5289, 11629, 16384, 5170, 6730, 11940, 16384,
                    11216, 11629, 14731, 18432, 15725, 15725, 18432, 20480, 13396},

            {3328, 5009, 10531, 15186, 5040, 6031, 10531, 14731,
                    10431, 10431, 13396, 16384, 15186, 14731, 16384, 18432, 11629},

            {14336, 12902, 12902, 13396, 12902, 11629, 11940, 12288,
                    13138, 12109, 12288, 12902, 13677, 12683, 12902, 13138, 711},

            {4300, 5204, 9437, 13396, 5430, 5776, 9300, 12902,
                    9656, 9437, 11781, 14731, 13396, 12902, 14731, 16384, 8982},

            {5394, 5776, 8982, 12288, 5922, 5901, 8640, 11629,
                    9105, 8694, 10635, 13138, 12288, 11629, 13138, 14731, 6844},

            {17234, 15725, 15725, 15725, 15725, 14731, 14731, 14731,
                    16384, 14731, 14731, 15186, 16384, 15186, 15186, 15186, 272},

            {6461, 6286, 8806, 11348, 6566, 6215, 8334, 10742,
                    9233, 8535, 10061, 12109, 11781, 10970, 12109, 13677, 5394},

            {6674, 6487, 8863, 11485, 6702, 6286, 8334, 10635,
                    9168, 8483, 9976, 11940, 11629, 10854, 11940, 13396, 5105},

            {15186, 13677, 13677, 13988, 13677, 12479, 12479, 12683,
                    13988, 12683, 12902, 13138, 14336, 13138, 13396, 13677, 565},

            {7844, 7252, 8922, 10854, 7389, 6815, 8383, 10240,
                    9508, 8750, 9892, 11485, 11629, 10742, 11629, 12902, 3842},

            {9233, 8239, 9233, 10431, 8334, 7424, 8483, 9892,
                    10061, 9105, 10061, 11216, 11781, 10742, 11485, 12479, 2906},

            {20480, 20480, 14731, 14731, 20480, 20480, 14336, 14336,
                    15186, 14336, 14731, 14731, 15186, 14731, 14731, 15186, 266},

            {10531, 9300, 9976, 11090, 9437, 8286, 9042, 10061,
                    10431, 9368, 9976, 10854, 11781, 10531, 11090, 11781, 2233},

            {11629, 10334, 10970, 12109, 10431, 9368, 10061, 10970,
                    11348, 10240, 10854, 11485, 12288, 11216, 11629, 12288, 1469},

            {952, 6787, 15725, 20480, 6646, 9733, 16384, 20480,
                    14731, 15725, 18432, 20480, 18432, 20480, 20480, 20480, 18432},

            {9437, 8806, 10742, 12288, 8982, 8483, 9892, 11216,
                    10742, 9892, 10854, 11940, 12109, 11090, 11781, 12683, 1891},

            {12902, 11629, 11940, 12479, 11781, 10531, 10854, 11485,
                    12109, 10970, 11348, 11940, 12902, 11781, 12109, 12479, 1054},

            {2113, 5323, 11781, 16384, 5579, 7252, 12288, 16384,
                    11781, 12288, 15186, 18432, 15725, 16384, 18432, 20480, 12902},

            {2463, 5965, 11348, 15186, 5522, 6934, 11216, 14731,
                    10334, 10635, 13677, 16384, 13988, 13988, 15725, 18432, 10334},

            {3779, 5541, 9812, 13677, 5467, 6122, 9656, 13138,
                    9581, 9437, 11940, 14731, 13138, 12683, 14336, 16384, 8982},

            {3181, 5154, 10150, 14336, 5448, 6311, 10334, 13988,
                    10334, 10431, 13138, 15725, 14336, 13988, 15725, 18432, 10431},

            {4841, 5560, 9105, 12479, 5756, 5944, 8922, 12109,
                    9300, 8982, 11090, 13677, 12479, 12109, 13677, 15186, 7460},

            {5859, 6009, 8922, 11940, 6144, 5987, 8483, 11348,
                    9042, 8535, 10334, 12683, 11940, 11216, 12683, 14336, 6215},

            {4250, 4916, 8587, 12109, 5901, 6191, 9233, 12288,
                    10150, 9892, 11940, 14336, 13677, 13138, 14731, 16384, 8383},

            {7153, 6702, 8863, 11216, 6904, 6410, 8239, 10431,
                    9233, 8433, 9812, 11629, 11629, 10742, 11781, 13138, 4753},

            {6674, 7057, 9508, 11629, 7120, 6964, 8806, 10635,
                    9437, 8750, 10061, 11629, 11485, 10531, 11485, 12683, 4062},

            {5341, 5289, 8013, 10970, 6311, 6262, 8640, 11090,
                    10061, 9508, 11090, 13138, 12902, 12288, 13396, 15186, 6539},

            {8057, 7533, 9300, 11216, 7685, 7057, 8535, 10334,
                    9508, 8694, 9812, 11216, 11485, 10431, 11348, 12479, 3541},

            {9168, 8239, 9656, 11216, 8483, 7608, 8806, 10240,
                    9892, 8982, 9812, 11090, 11485, 10431, 11090, 12109, 2815},

            {558, 7928, 18432, 20480, 7724, 12288, 20480, 20480,
                    18432, 20480, 20480, 20480, 20480, 20480, 20480, 20480, 20480},

            {9892, 8806, 9976, 11348, 9042, 8057, 9042, 10240,
                    10240, 9233, 9976, 11090, 11629, 10531, 11216, 12109, 2371},

            {11090, 9812, 10531, 11629, 9976, 8863, 9508, 10531,
                    10854, 9733, 10334, 11090, 11940, 10742, 11216, 11940, 1821},

            {7354, 6964, 9042, 11216, 7153, 6592, 8334, 10431,
                    9233, 8483, 9812, 11485, 11485, 10531, 11629, 12902, 4349},

            {11348, 10150, 10742, 11629, 10150, 9042, 9656, 10431,
                    10854, 9812, 10431, 11216, 12109, 10970, 11485, 12109, 1700},

            {20480, 20480, 8694, 10150, 20480, 20480, 8982, 10240,
                    8982, 9105, 9976, 10970, 10431, 10431, 11090, 11940, 1610},

            {9233, 8192, 9368, 10970, 8286, 7496, 8587, 9976,
                    9812, 8863, 9733, 10854, 11348, 10334, 11090, 11940, 3040},

            {4202, 5716, 9733, 13138, 5598, 6099, 9437, 12683,
                    9300, 9168, 11485, 13988, 12479, 12109, 13988, 15725, 7804},

            {4400, 5965, 9508, 12479, 6009, 6360, 9105, 11781,
                    9300, 8982, 10970, 13138, 12109, 11629, 13138, 14731, 6994}
    };

    private static final AcModel[] lc3_spectrum_models = new AcModel[] {

            new AcModel(new int[][] {
                    {0, 1}, {1, 1}, {2, 175}, {177, 48},
                    {225, 1}, {226, 1}, {227, 109}, {336, 36},
                    {372, 171}, {543, 109}, {652, 47}, {699, 20},
                    {719, 49}, {768, 36}, {804, 20}, {824, 10},
                    {834, 190}
            }),
            new AcModel(new int[][] {
                    {0, 18}, {18, 26}, {44, 17}, {61, 10},
                    {71, 27}, {98, 37}, {135, 24}, {159, 16},
                    {175, 22}, {197, 32}, {229, 22}, {251, 14},
                    {265, 17}, {282, 26}, {308, 20}, {328, 13},
                    {341, 683}
            }),
            new AcModel(new int[][] {
                    {0, 71}, {71, 92}, {163, 49}, {212, 25},
                    {237, 81}, {318, 102}, {420, 61}, {481, 33},
                    {514, 42}, {556, 57}, {613, 39}, {652, 23},
                    {675, 22}, {697, 30}, {727, 22}, {749, 15},
                    {764, 260}
            }),
            new AcModel(new int[][] {
                    {0, 160}, {160, 130}, {290, 46}, {336, 18},
                    {354, 121}, {475, 123}, {598, 55}, {653, 24},
                    {677, 45}, {722, 55}, {777, 31}, {808, 15},
                    {823, 19}, {842, 24}, {866, 15}, {881, 9},
                    {890, 134}
            }),
            new AcModel(new int[][] {
                    {0, 71}, {71, 73}, {144, 33}, {177, 18},
                    {195, 71}, {266, 76}, {342, 43}, {385, 26},
                    {411, 34}, {445, 44}, {489, 30}, {519, 20},
                    {539, 20}, {559, 27}, {586, 21}, {607, 15},
                    {622, 402}
            }),
            new AcModel(new int[][] {
                    {0, 48}, {48, 60}, {108, 32}, {140, 19},
                    {159, 58}, {217, 68}, {285, 42}, {327, 27},
                    {354, 31}, {385, 42}, {427, 30}, {457, 21},
                    {478, 19}, {497, 27}, {524, 21}, {545, 16},
                    {561, 463}
            }),
            new AcModel(new int[][] {
                    {0, 138}, {138, 109}, {247, 43}, {290, 18},
                    {308, 111}, {419, 112}, {531, 53}, {584, 25},
                    {609, 46}, {655, 55}, {710, 32}, {742, 17},
                    {759, 21}, {780, 27}, {807, 18}, {825, 11},
                    {836, 188}
            }),
            new AcModel(new int[][] {
                    {0, 16}, {16, 24}, {40, 22}, {62, 17},
                    {79, 24}, {103, 36}, {139, 31}, {170, 25},
                    {195, 20}, {215, 30}, {245, 25}, {270, 20},
                    {290, 15}, {305, 22}, {327, 19}, {346, 16},
                    {362, 662}}
            ),
            new AcModel(new int[][] {
                    {0, 579}, {579, 150}, {729, 12}, {741, 2},
                    {743, 154}, {897, 73}, {970, 10}, {980, 2},
                    {982, 14}, {996, 11}, {1007, 3}, {1010, 1},
                    {1011, 3}, {1014, 3}, {1017, 1}, {1018, 1},
                    {1019, 5}
            }),
            new AcModel(new int[][] {
                    {0, 398}, {398, 184}, {582, 25}, {607, 5},
                    {612, 176}, {788, 114}, {902, 23}, {925, 6},
                    {931, 25}, {956, 23}, {979, 8}, {987, 3},
                    {990, 6}, {996, 6}, {1002, 3}, {1005, 2},
                    {1007, 17}
            }),
            new AcModel(new int[][] {
                    {0, 13}, {13, 21}, {34, 18}, {52, 11},
                    {63, 20}, {83, 29}, {112, 22}, {134, 15},
                    {149, 14}, {163, 20}, {183, 16}, {199, 12},
                    {211, 10}, {221, 14}, {235, 12}, {247, 10},
                    {257, 767}
            }),
            new AcModel(new int[][] {
                    {0, 281}, {281, 183}, {464, 37}, {501, 9},
                    {510, 171}, {681, 139}, {820, 37}, {857, 10},
                    {867, 35}, {902, 36}, {938, 15}, {953, 6},
                    {959, 9}, {968, 10}, {978, 6}, {984, 3},
                    {987, 37}
            }),
            new AcModel(new int[][] {
                    {0, 198}, {198, 164}, {362, 46}, {408, 13},
                    {421, 154}, {575, 147}, {722, 51}, {773, 16},
                    {789, 43}, {832, 49}, {881, 24}, {905, 10},
                    {915, 13}, {928, 16}, {944, 10}, {954, 5},
                    {959, 65}
            }),
            new AcModel(new int[][] {
                    {0, 1}, {1, 1}, {2, 93}, {95, 44},
                    {139, 1}, {140, 1}, {141, 72}, {213, 38},
                    {251, 86}, {337, 70}, {407, 43}, {450, 25},
                    {475, 40}, {515, 36}, {551, 25}, {576, 16},
                    {592, 432}
            }),
            new AcModel(new int[][] {
                    {0, 133}, {133, 141}, {274, 64}, {338, 28},
                    {366, 117}, {483, 122}, {605, 59}, {664, 27},
                    {691, 39}, {730, 48}, {778, 29}, {807, 15},
                    {822, 15}, {837, 20}, {857, 13}, {870, 8},
                    {878, 146}
            }),
            new AcModel(new int[][] {
                    {0, 128}, {128, 125}, {253, 49}, {302, 18},
                    {320, 123}, {443, 134}, {577, 59}, {636, 23},
                    {659, 49}, {708, 59}, {767, 32}, {799, 15},
                    {814, 19}, {833, 24}, {857, 15}, {872, 9},
                    {881, 143}
            }),
            new AcModel(new int[][] {
                    {0, 1}, {1, 1}, {2, 23}, {25, 17},
                    {42, 1}, {43, 1}, {44, 23}, {67, 18},
                    {85, 20}, {105, 21}, {126, 18}, {144, 15},
                    {159, 15}, {174, 17}, {191, 14}, {205, 12},
                    {217, 807}
            }),
            new AcModel(new int[][] {
                    {0, 70}, {70, 96}, {166, 63}, {229, 38},
                    {267, 89}, {356, 112}, {468, 65}, {533, 36},
                    {569, 37}, {606, 47}, {653, 32}, {685, 20},
                    {705, 17}, {722, 23}, {745, 17}, {762, 12},
                    {774, 250}
            }),
            new AcModel(new int[][] {
                    {0, 55}, {55, 75}, {130, 45}, {175, 25},
                    {200, 68}, {268, 90}, {358, 58}, {416, 33},
                    {449, 39}, {488, 54}, {542, 39}, {581, 25},
                    {606, 22}, {628, 31}, {659, 24}, {683, 16},
                    {699, 325}
            }),
            new AcModel(new int[][] {
                    {0, 1}, {1, 2}, {3, 2}, {5, 2},
                    {7, 2}, {9, 2}, {11, 2}, {13, 2},
                    {15, 2}, {17, 2}, {19, 2}, {21, 2},
                    {23, 2}, {25, 2}, {27, 2}, {29, 2},
                    {31, 993}
            }),
            new AcModel(new int[][] {
                    {0, 34}, {34, 51}, {85, 38}, {123, 24},
                    {147, 49}, {196, 69}, {265, 52}, {317, 35},
                    {352, 34}, {386, 47}, {433, 37}, {470, 27},
                    {497, 21}, {518, 31}, {549, 25}, {574, 19},
                    {593, 431}
            }),
            new AcModel(new int[][] {
                    {0, 30}, {30, 43}, {73, 32}, {105, 22},
                    {127, 43}, {170, 59}, {229, 45}, {274, 31},
                    {305, 30}, {335, 42}, {377, 34}, {411, 25},
                    {436, 19}, {455, 28}, {483, 23}, {506, 18},
                    {524, 500}
            }),
            new AcModel(new int[][] {
                    {0, 9}, {9, 15}, {24, 14}, {38, 13},
                    {51, 14}, {65, 22}, {87, 21}, {108, 18},
                    {126, 13}, {139, 20}, {159, 18}, {177, 16},
                    {193, 11}, {204, 17}, {221, 15}, {236, 14},
                    {250, 774}
            }),
            new AcModel(new int[][] {
                    {0, 30}, {30, 44}, {74, 31}, {105, 20},
                    {125, 41}, {166, 58}, {224, 42}, {266, 28},
                    {294, 28}, {322, 39}, {361, 30}, {391, 22},
                    {413, 18}, {431, 26}, {457, 21}, {478, 16},
                    {494, 530}
            }),
            new AcModel(new int[][] {
                    {0, 15}, {15, 23}, {38, 20}, {58, 15},
                    {73, 22}, {95, 33}, {128, 28}, {156, 22},
                    {178, 18}, {196, 26}, {222, 23}, {245, 18},
                    {263, 13}, {276, 20}, {296, 18}, {314, 15},
                    {329, 695}
            }),
            new AcModel(new int[][] {
                    {0, 11}, {11, 17}, {28, 16}, {44, 13},
                    {57, 17}, {74, 26}, {100, 23}, {123, 19},
                    {142, 15}, {157, 22}, {179, 20}, {199, 17},
                    {216, 12}, {228, 18}, {246, 16}, {262, 14},
                    {276, 748}
            }),
            new AcModel(new int[][] {
                    {0, 448}, {448, 171}, {619, 20}, {639, 4},
                    {643, 178}, {821, 105}, {926, 18}, {944, 4},
                    {948, 23}, {971, 20}, {991, 7}, {998, 2},
                    {1000, 5}, {1005, 5}, {1010, 2}, {1012, 1},
                    {1013, 11}
            }),
            new AcModel(new int[][] {
                    {0, 332}, {332, 188}, {520, 29}, {549, 6},
                    {555, 186}, {741, 133}, {874, 29}, {903, 7},
                    {910, 30}, {940, 30}, {970, 11}, {981, 4},
                    {985, 6}, {991, 7}, {998, 4}, {1002, 2},
                    {1004, 20}
            }),
            new AcModel(new int[][] {
                    {0, 8}, {8, 13}, {21, 13}, {34, 11},
                    {45, 13}, {58, 20}, {78, 18}, {96, 16},
                    {112, 12}, {124, 17}, {141, 16}, {157, 13},
                    {170, 10}, {180, 14}, {194, 13}, {207, 12},
                    {219, 805}
            }),
            new AcModel(new int[][] {
                    {0, 239}, {239, 176}, {415, 42}, {457, 11},
                    {468, 163}, {631, 145}, {776, 44}, {820, 13},
                    {833, 39}, {872, 42}, {914, 19}, {933, 7},
                    {940, 11}, {951, 13}, {964, 7}, {971, 4},
                    {975, 49}
            }),
            new AcModel(new int[][] {
                    {0, 165}, {165, 145}, {310, 49}, {359, 16},
                    {375, 138}, {513, 139}, {652, 55}, {707, 20},
                    {727, 47}, {774, 54}, {828, 28}, {856, 12},
                    {868, 16}, {884, 20}, {904, 12}, {916, 7},
                    {923, 101}
            }),
            new AcModel(new int[][] {
                    {0, 3}, {3, 5}, {8, 5}, {13, 5},
                    {18, 5}, {23, 7}, {30, 7}, {37, 7},
                    {44, 4}, {48, 7}, {55, 7}, {62, 6},
                    {68, 4}, {72, 6}, {78, 6}, {84, 6},
                    {90, 934}
            }),
            new AcModel(new int[][] {
                    {0, 115}, {115, 122}, {237, 52}, {289, 22},
                    {311, 111}, {422, 125}, {547, 61}, {608, 27},
                    {635, 45}, {680, 57}, {737, 34}, {771, 17},
                    {788, 19}, {807, 25}, {832, 17}, {849, 10},
                    {859, 165}
            }),
            new AcModel(new int[][] {
                    {0, 107}, {107, 114}, {221, 51}, {272, 21},
                    {293, 106}, {399, 122}, {521, 61}, {582, 28},
                    {610, 46}, {656, 58}, {714, 35}, {749, 18},
                    {767, 20}, {787, 26}, {813, 18}, {831, 11},
                    {842, 182}
            }),
            new AcModel(new int[][] {
                    {0, 6}, {6, 10}, {16, 10}, {26, 9},
                    {35, 10}, {45, 15}, {60, 15}, {75, 14},
                    {89, 9}, {98, 14}, {112, 13}, {125, 12},
                    {137, 8}, {145, 12}, {157, 11}, {168, 10},
                    {178, 846}
            }),
            new AcModel(new int[][] {
                    {0, 72}, {72, 88}, {160, 50}, {210, 26},
                    {236, 84}, {320, 102}, {422, 60}, {482, 32},
                    {514, 41}, {555, 53}, {608, 36}, {644, 21},
                    {665, 20}, {685, 27}, {712, 20}, {732, 13},
                    {745, 279}
            }),
            new AcModel(new int[][] {
                    {0, 45}, {45, 63}, {108, 45}, {153, 30},
                    {183, 61}, {244, 83}, {327, 58}, {385, 36},
                    {421, 34}, {455, 47}, {502, 34}, {536, 23},
                    {559, 19}, {578, 27}, {605, 21}, {626, 15},
                    {641, 383}
            }),
            new AcModel(new int[][] {
                    {0, 1}, {1, 1}, {2, 7}, {9, 7},
                    {16, 1}, {17, 1}, {18, 8}, {26, 8},
                    {34, 6}, {40, 8}, {48, 7}, {55, 7},
                    {62, 6}, {68, 7}, {75, 7}, {82, 6},
                    {88, 936}
            }),
            new AcModel(new int[][] {
                    {0, 29}, {29, 44}, {73, 35}, {108, 24},
                    {132, 42}, {174, 62}, {236, 48}, {284, 34},
                    {318, 30}, {348, 43}, {391, 35}, {426, 26},
                    {452, 19}, {471, 29}, {500, 24}, {524, 19},
                    {543, 481}
            }),
            new AcModel(new int[][] {
                    {0, 20}, {20, 31}, {51, 25}, {76, 17},
                    {93, 30}, {123, 43}, {166, 34}, {200, 25},
                    {225, 22}, {247, 32}, {279, 26}, {305, 21},
                    {326, 16}, {342, 23}, {365, 20}, {385, 16},
                    {401, 623}
            }),
            new AcModel(new int[][] {
                    {0, 742}, {742, 103}, {845, 5}, {850, 1},
                    {851, 108}, {959, 38}, {997, 4}, {1001, 1},
                    {1002, 7}, {1009, 5}, {1014, 2}, {1016, 1},
                    {1017, 2}, {1019, 1}, {1020, 1}, {1021, 1},
                    {1022, 2}
            }),
            new AcModel(new int[][] {
                    {0, 42}, {42, 52}, {94, 27}, {121, 16},
                    {137, 49}, {186, 58}, {244, 36}, {280, 23},
                    {303, 27}, {330, 36}, {366, 26}, {392, 18},
                    {410, 17}, {427, 24}, {451, 19}, {470, 14},
                    {484, 540}
            }),
            new AcModel(new int[][] {
                    {0, 13}, {13, 20}, {33, 18}, {51, 15},
                    {66, 19}, {85, 29}, {114, 26}, {140, 21},
                    {161, 17}, {178, 25}, {203, 22}, {225, 18},
                    {243, 13}, {256, 19}, {275, 17}, {292, 15},
                    {307, 717}
            }),
            new AcModel(new int[][] {
                    {0, 501}, {501, 169}, {670, 19}, {689, 4},
                    {693, 155}, {848, 88}, {936, 16}, {952, 4},
                    {956, 19}, {975, 16}, {991, 6}, {997, 2},
                    {999, 5}, {1004, 4}, {1008, 2}, {1010, 1},
                    {1011, 13}
            }),
            new AcModel(new int[][] {
                    {0, 445}, {445, 136}, {581, 22}, {603, 6},
                    {609, 158}, {767, 98}, {865, 23}, {888, 7},
                    {895, 31}, {926, 28}, {954, 10}, {964, 4},
                    {968, 9}, {977, 9}, {986, 5}, {991, 2},
                    {993, 31}
            }),
            new AcModel(new int[][] {
                    {0, 285}, {285, 157}, {442, 37}, {479, 10},
                    {489, 161}, {650, 129}, {779, 39}, {818, 12},
                    {830, 40}, {870, 42}, {912, 18}, {930, 7},
                    {937, 12}, {949, 14}, {963, 8}, {971, 4},
                    {975, 49}
            }),
            new AcModel(new int[][] {
                    {0, 349}, {349, 179}, {528, 33}, {561, 8},
                    {569, 162}, {731, 121}, {852, 31}, {883, 9},
                    {892, 31}, {923, 30}, {953, 12}, {965, 5},
                    {970, 8}, {978, 9}, {987, 5}, {992, 2},
                    {994, 30}
            }),
            new AcModel(new int[][] {
                    {0, 199}, {199, 156}, {355, 47}, {402, 15},
                    {417, 146}, {563, 137}, {700, 50}, {750, 17},
                    {767, 44}, {811, 49}, {860, 24}, {884, 10},
                    {894, 15}, {909, 17}, {926, 10}, {936, 6},
                    {942, 82}
            }),
            new AcModel(new int[][] {
                    {0, 141}, {141, 134}, {275, 50}, {325, 18},
                    {343, 128}, {471, 135}, {606, 58}, {664, 22},
                    {686, 48}, {734, 57}, {791, 31}, {822, 14},
                    {836, 18}, {854, 23}, {877, 14}, {891, 8},
                    {899, 125}
            }),
            new AcModel(new int[][] {
                    {0, 243}, {243, 194}, {437, 56}, {493, 17},
                    {510, 139}, {649, 126}, {775, 45}, {820, 16},
                    {836, 33}, {869, 36}, {905, 18}, {923, 8},
                    {931, 10}, {941, 12}, {953, 7}, {960, 4},
                    {964, 60}
            }),
            new AcModel(new int[][] {
                    {0, 91}, {91, 106}, {197, 51}, {248, 23},
                    {271, 99}, {370, 117}, {487, 63}, {550, 30},
                    {580, 45}, {625, 59}, {684, 37}, {721, 20},
                    {741, 20}, {761, 27}, {788, 19}, {807, 12},
                    {819, 205}
            }),
            new AcModel(new int[][] {
                    {0, 107}, {107, 94}, {201, 41}, {242, 20},
                    {262, 92}, {354, 97}, {451, 52}, {503, 28},
                    {531, 42}, {573, 53}, {626, 34}, {660, 20},
                    {680, 21}, {701, 29}, {730, 21}, {751, 14},
                    {765, 259}
            }),
            new AcModel(new int[][] {
                    {0, 168}, {168, 171}, {339, 68}, {407, 25},
                    {432, 121}, {553, 123}, {676, 55}, {731, 24},
                    {755, 34}, {789, 41}, {830, 24}, {854, 12},
                    {866, 13}, {879, 16}, {895, 11}, {906, 6},
                    {912, 112}
            }),
            new AcModel(new int[][] {
                    {0, 67}, {67, 80}, {147, 44}, {191, 23},
                    {214, 76}, {290, 94}, {384, 57}, {441, 31},
                    {472, 41}, {513, 54}, {567, 37}, {604, 23},
                    {627, 21}, {648, 30}, {678, 22}, {700, 15},
                    {715, 309}
            }),
            new AcModel(new int[][] {
                    {0, 46}, {46, 63}, {109, 39}, {148, 23},
                    {171, 58}, {229, 78}, {307, 52}, {359, 32},
                    {391, 36}, {427, 49}, {476, 37}, {513, 24},
                    {537, 21}, {558, 30}, {588, 24}, {612, 17},
                    {629, 395}
            }),
            new AcModel(new int[][] {
                    {0, 848}, {848, 70}, {918, 2}, {920, 1},
                    {921, 75}, {996, 16}, {1012, 1}, {1013, 1},
                    {1014, 2}, {1016, 1}, {1017, 1}, {1018, 1},
                    {1019, 1}, {1020, 1}, {1021, 1}, {1022, 1},
                    {1023, 1}
            }),
            new AcModel(new int[][] {
                    {0, 36}, {36, 52}, {88, 35}, {123, 22},
                    {145, 48}, {193, 67}, {260, 48}, {308, 32},
                    {340, 32}, {372, 45}, {417, 35}, {452, 24},
                    {476, 20}, {496, 29}, {525, 23}, {548, 17},
                    {565, 459}
            }),
            new AcModel(new int[][] {
                    {0, 24}, {24, 37}, {61, 29}, {90, 20},
                    {110, 35}, {145, 51}, {196, 41}, {237, 29},
                    {266, 26}, {292, 38}, {330, 31}, {361, 24},
                    {385, 18}, {403, 27}, {430, 23}, {453, 18},
                    {471, 553}
            }),
            new AcModel(new int[][] {
                    {0, 85}, {85, 97}, {182, 48}, {230, 23},
                    {253, 91}, {344, 110}, {454, 61}, {515, 30},
                    {545, 45}, {590, 58}, {648, 37}, {685, 21},
                    {706, 21}, {727, 29}, {756, 20}, {776, 13},
                    {789, 235}
            }),
            new AcModel(new int[][] {
                    {0, 22}, {22, 33}, {55, 27}, {82, 20},
                    {102, 33}, {135, 48}, {183, 39}, {222, 30},
                    {252, 26}, {278, 37}, {315, 30}, {345, 23},
                    {368, 17}, {385, 25}, {410, 21}, {431, 17},
                    {448, 576}
            }),
            new AcModel(new int[][] {
                    {0, 1}, {1, 1}, {2, 54}, {56, 33},
                    {89, 1}, {90, 1}, {91, 49}, {140, 32},
                    {172, 49}, {221, 47}, {268, 35}, {303, 25},
                    {328, 30}, {358, 30}, {388, 24}, {412, 18},
                    {430, 594}
            }),
            new AcModel(new int[][] {
                    {0, 45}, {45, 64}, {109, 43}, {152, 25},
                    {177, 62}, {239, 81}, {320, 56}, {376, 35},
                    {411, 37}, {448, 51}, {499, 38}, {537, 26},
                    {563, 22}, {585, 31}, {616, 24}, {640, 18},
                    {658, 366}
            }),
            new AcModel(new int[][] {
                    {0, 247}, {247, 148}, {395, 38}, {433, 12},
                    {445, 154}, {599, 130}, {729, 42}, {771, 14},
                    {785, 44}, {829, 46}, {875, 21}, {896, 9},
                    {905, 15}, {920, 17}, {937, 9}, {946, 5},
                    {951, 73}
            }),
            new AcModel(new int[][] {
                    {0, 231}, {231, 136}, {367, 41}, {408, 15},
                    {423, 134}, {557, 119}, {676, 47}, {723, 19},
                    {742, 44}, {786, 49}, {835, 25}, {860, 12},
                    {872, 17}, {889, 20}, {909, 12}, {921, 7},
                    {928, 96}
            })
    };

//#endregion
}
