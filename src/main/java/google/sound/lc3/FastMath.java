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


/**
 * IEEE 754 Floating point representation
 */
class FastMath {

    private static final int LC3_IEEE754_SIGN_SHL = 31;
    private static final int LC3_IEEE754_SIGN_MASK = 1 << LC3_IEEE754_SIGN_SHL;

    private static final int LC3_IEEE754_EXP_SHL = 23;
    private static final int LC3_IEEE754_EXP_MASK = 0xff << LC3_IEEE754_EXP_SHL;
    private static final int LC3_IEEE754_EXP_BIAS = 127;

    /**
     * Fast multiply floating-point number by integral power of 2
     *
     * @param x   Operand, finite number
     * @param exp Exponent such that 2^x is finite
     * @return 2^exp
     */
    static float lc3_ldexpf(float x, int exp) {
        ByteBuffer _x = ByteBuffer.allocate(Integer.BYTES);
        _x.putFloat(x);

        if ((_x.getInt() & LC3_IEEE754_EXP_MASK) != 0)
            _x.putInt(_x.getInt() + exp << LC3_IEEE754_EXP_SHL);

        return _x.getFloat();
    }

    /**
     * Fast convert floating-point number to fractional and integral components
     *
     * @param x   Operand, finite number
     * @param exp Return the exponent part
     * @return The normalized fraction in [0.5:1[
     */
    static float lc3_frexpf(float x, int[] exp) {
        ByteBuffer _x = ByteBuffer.allocate(Integer.BYTES);
        _x.putFloat(x);

        int e = (_x.getInt() & LC3_IEEE754_EXP_MASK) >> LC3_IEEE754_EXP_SHL;
        exp[0] = e - (LC3_IEEE754_EXP_BIAS - 1);

        _x.putInt((_x.getInt() & ~LC3_IEEE754_EXP_MASK) | ((LC3_IEEE754_EXP_BIAS - 1) << LC3_IEEE754_EXP_SHL));

        return _x.getFloat();
    }

    /** 2^(i/8) for i from 0 to 7 */
    private static final float[] e = new float[] {
            1.00000000e+00f, 1.09050773e+00f, 1.18920712e+00f, 1.29683955e+00f,
            1.41421356e+00f, 1.54221083e+00f, 1.68179283e+00f, 1.83400809e+00f
    };

    /** Polynomial approx in range 0 to 1/8 */
    private static final float[] p = new float[] {
            1.00448128e-02f, 5.54563260e-02f, 2.40228756e-01f, 6.93147140e-01f
    };

    /**
     * Fast 2^n approximation
     *
     * @param x Operand, range -100 to 100
     * @return 2^x approximation (max relative error ~ 4e-7)
     */
    static float lc3_exp2f(float x) {
        // --- Split the operand ---
        //
        // Such as x = k/8 + y, with k an integer, and |y| < 0.5/8
        //
        // Note that `fast-math` compiler option leads to rounding error,
        // disable optimisation with `volatile`.

        ByteBuffer v = ByteBuffer.allocate(Integer.BYTES);

        v.putFloat(x + 0x1.8p20f);
        int k = v.getInt();
        x -= v.getFloat() - 0x1.8p20f;

        // Compute 2^x, with |x| < 1
        // Perform polynomial approximation in range -0.5/8 to 0.5/8,
        // and muplity by precomputed value of 2^(i/8), i in [0:7]

        ByteBuffer y = ByteBuffer.allocate(Integer.BYTES);

        y.putFloat((p[0]) * x);
        y.putFloat((y.getFloat() + p[1]) * x);
        y.putFloat((y.getFloat() + p[2]) * x);
        y.putFloat((y.getFloat() + p[3]) * x);
        y.putFloat((y.getFloat() + 1.f) * e[k & 7]);

        // Add the exponent

        y.putInt(y.getInt() + (k >> 3) << LC3_IEEE754_EXP_SHL);

        return y.getFloat();
    }

    private static final float[] c = {-1.29479677f, 5.11769018f, -8.42295281f, 8.10557963f, -3.50567360f};

    /**
     * Fast log2(x) approximation
     *
     * @param x Operand, greater than 0
     * @return log2(x) approximation (max absolute error ~ 1e-4)
     */
    static float log2f(float x) {
        int[] e = new int[1];

        // Polynomial approx in range 0.5 to 1

        x = lc3_frexpf(x, e);

        float y = (c[0]) * x;
        y = (y + c[1]) * x;
        y = (y + c[2]) * x;
        y = (y + c[3]) * x;
        y = (y + c[4]);

        // Add log2f(2^e) and return

        return e[0] + y;
    }

    /**
     * Fast log10(x) approximation
     *
     * @param x Operand, greater than 0
     * @return log10(x) approximation (max absolute error ~ 1e-4)
     */
    static float lc3_log10f(float x) {
        return (float) (Math.log10(2) * log2f(x));
    }

    /** Table in Q15 */
    private static final short[][] t = new short[][] {

            // [n][0] = 10 * log10(2) * log2(1 + n/32), with n = [0..15]
            // [n][1] = [n+1][0] - [n][0] (while defining [16][0])

            {0, 4379}, {4379, 4248}, {8627, 4125}, {12753, 4009},
            {16762, 3899}, {20661, 3795}, {24456, 3697}, {28153, 3603},
            {31755, 3514}, {(short) 35269, 3429}, {(short) 38699, 3349}, {(short) 42047, 3272},
            {(short) 45319, 3198}, {(short) 48517, 3128}, {(short) 51645, 3061}, {(short) 54705, 2996},

            // [n][0] = 10 * log10(2) * log2(1 + n/32) - 10 * log10(2) / 2,
            //     with n = [16..31]
            // [n][1] = [n+1][0] - [n][0] (while defining [32][0])

            {8381, 2934}, {11315, 2875}, {14190, 2818}, {17008, 2763},
            {19772, 2711}, {22482, 2660}, {25142, 2611}, {27754, 2564},
            {30318, 2519}, {(short) 32837, 2475}, {(short) 35312, 2433}, {(short) 37744, 2392},
            {(short) 40136, 2352}, {(short) 42489, 2314}, {(short) 44803, 2277}, {(short) 47080, 2241},
    };

    /**
     * Fast `10 * log10(x)` (or dB) approximation in fixed Q16
     *
     * @param x Operand, in range 2^-63 to 2^63 (1e-19 to 1e19)
     * @return 10 * log10(x) in fixed Q16 (-190 to 192 dB)
     * <p>
     * - The 0 value is accepted and return the minimum value ~ -191dB
     * - This function assumed that float 32 bits is coded IEEE 754
     */
    static int lc3_db_q16(float x) {

        // --- Approximation ---
        //
        // 10 * log10(x^2) = 10 * log10(2) * log2(x^2)
        //
        // And log2(x^2) = 2 * log2( (1 + m) * 2^e )
        //   = 2 * (e + log2(1 + m)) , with m in range [0..1]
        //
        // Split the float values in :
        //   e2  Double value of the exponent (2 * e + k)
        //   hi  High 5 bits of mantissa, for precalculated result `t[hi][0]`
        //   lo  Low 16 bits of mantissa, for linear interpolation `t[hi][1]`
        //
        // Two cases, from the range of the mantissa :
        //   0 to 0.5   `k = 0`, use 1st part of the table
        //   0.5 to 1   `k = 1`, use 2nd part of the table

        ByteBuffer x2 = ByteBuffer.allocate(Integer.BYTES);
        x2.putFloat(x * x);

        int e2 = (x2.getInt() >> 22) - 2 * 127;
        int hi = (x2.getInt() >> 18) & 0x1f;
        int lo = (x2.getInt() >> 2) & 0xffff;

        return e2 * 49321 + t[hi][0] + ((t[hi][1] * lo) >> 16);
    }
}