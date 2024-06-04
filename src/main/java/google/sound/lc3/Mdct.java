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

import java.util.stream.IntStream;

import google.sound.lc3.Common.Complex;
import google.sound.lc3.Common.Duration;
import google.sound.lc3.Common.SRate;
import google.sound.lc3.Tables.lc3_fft_bf2_twiddles;
import google.sound.lc3.Tables.lc3_fft_bf3_twiddles;
import google.sound.lc3.Tables.lc3_mdct_rot_def;

import static google.sound.lc3.Tables.LC3_MAX_NS;
import static google.sound.lc3.Tables.lc3_fft_twiddles_bf2;
import static google.sound.lc3.Tables.lc3_fft_twiddles_bf3;
import static google.sound.lc3.Tables.lc3_mdct_rot;
import static google.sound.lc3.Tables.lc3_mdct_win;
import static google.sound.lc3.Tables.lc3_nd;
import static google.sound.lc3.Tables.lc3_ns;


class Mdct {

    //
    // FFT processing
    //

    /**
     * FFT 5 Points
     *
     * @param x output coefficients, of size 5xn
     * @param y Input coefficients, of size 5xn
     * @param n Number of interleaved transform to perform (n % 2 = 0)
     */
    private static void fft_5(Complex[] x, Complex[] y, int n) {
        float cos1 = 0.3090169944f; // cos(-2Pi 1/5)
        float cos2 = -0.8090169944f; // cos(-2Pi 2/5)

        float sin1 = -0.9510565163f; // sin(-2Pi 1/5)
        float sin2 = -0.5877852523f; // sin(-2Pi 2/5)

        int xP = 0;
        int yp = 0;
        for (int i = 0; i < n; i++, xP++, yp += 5) {

            Complex s14 =
                    new Complex(x[xP + 1 * n].re + x[xP + 4 * n].re, x[xP + 1 * n].im + x[xP + 4 * n].im);
            Complex d14 =
                    new Complex(x[xP + 1 * n].re - x[xP + 4 * n].re, x[xP + 1 * n].im - x[xP + 4 * n].im);

            Complex s23 =
                    new Complex(x[xP + 2 * n].re + x[xP + 3 * n].re, x[xP + 2 * n].im + x[xP + 3 * n].im);
            Complex d23 =
                    new Complex(x[xP + 2 * n].re - x[xP + 3 * n].re, x[xP + 2 * n].im - x[xP + 3 * n].im);

            y[yp + 0].re = x[xP + 0].re + s14.re + s23.re;

            y[yp + 0].im = x[xP + 0].im + s14.im + s23.im;

            y[yp + 1].re = x[xP + 0].re + s14.re * cos1 - d14.im * sin1 + s23.re * cos2 - d23.im * sin2;

            y[yp + 1].im = x[xP + 0].im + s14.im * cos1 + d14.re * sin1 + s23.im * cos2 + d23.re * sin2;

            y[yp + 2].re = x[xP + 0].re + s14.re * cos2 - d14.im * sin2 + s23.re * cos1 + d23.im * sin1;

            y[yp + 2].im = x[xP + 0].im + s14.im * cos2 + d14.re * sin2 + s23.im * cos1 - d23.re * sin1;

            y[yp + 3].re = x[xP + 0].re + s14.re * cos2 + d14.im * sin2 + s23.re * cos1 - d23.im * sin1;

            y[yp + 3].im = x[xP + 0].im + s14.im * cos2 - d14.re * sin2 + s23.im * cos1 + d23.re * sin1;

            y[yp + 4].re = x[xP + 0].re + s14.re * cos1 + d14.im * sin1 + s23.re * cos2 + d23.im * sin2;

            y[yp + 4].im = x[xP + 0].im + s14.im * cos1 - d14.re * sin1 + s23.im * cos2 - d23.re * sin2;
        }
    }

    /**
     * FFT Butterfly 3 Points
     *
     * @param x        Input coefficients
     * @param y        output coefficients
     * @param twiddles Twiddles factors, determine size of transform
     * @param n        Number of interleaved transforms
     */
    private static void fft_bf3(lc3_fft_bf3_twiddles twiddles, Complex[] x, Complex[] y, int n) {
        int n3 = twiddles.n3;
        Complex[][] w = twiddles.t;
        int w0 = 0; // twiddles.t;
        int w1 = w0 + n3;
        int w2 = w1 + n3;

        int x0 = 0; // x
        int x1 = x0 + n * n3;
        int x2 = x1 + n * n3;
        int y0 = 0; // y
        int y1 = y0 + n3;
        int y2 = y1 + n3;

        for (int i = 0; i < n; i++, y0 += 3 * n3, y1 += 3 * n3, y2 += 3 * n3)
            for (int j = 0; j < n3; j++, x0++, x1++, x2++) {

                y[y0 + j].re = x[x0].re + x[x1].re * w[w0 + j][0].re - x[x1].im * w[w0 + j][0].im
                        + x[x2].re * w[w0 + j][1].re - x[x2].im * w[w0 + j][1].im;

                y[y0 + j].im = x[x0].im + x[x1].im * w[w0 + j][0].re + x[x1].re * w[w0 + j][0].im
                        + x[x2].im * w[w0 + j][1].re + x[x2].re * w[w0 + j][1].im;

                y[y1 + j].re = x[x0].re + x[x1].re * w[w1 + j][0].re - x[x1].im * w[w1 + j][0].im
                        + x[x2].re * w[w1 + j][1].re - x[x2].im * w[w1 + j][1].im;

                y[y1 + j].im = x[x0].im + x[x1].im * w[w1 + j][0].re + x[x1].re * w[w1 + j][0].im
                        + x[x2].im * w[w1 + j][1].re + x[x2].re * w[w1 + j][1].im;

                y[y2 + j].re = x[x0].re + x[x1].re * w[w2 + j][0].re - x[x1].im * w[w2 + j][0].im
                        + x[x2].re * w[w2 + j][1].re - x[x2].im * w[w2 + j][1].im;

                y[y2 + j].im = x[x0].im + x[x1].im * w[w2 + j][0].re + x[x1].re * w[w2 + j][0].im
                        + x[x2].im * w[w2 + j][1].re + x[x2].re * w[w2 + j][1].im;
            }
    }

    /**
     * FFT Butterfly 2 Points
     *
     * @param twiddles Twiddles factors, determine size of transform
     * @param x        Input coefficients
     * @param y        output coefficients
     * @param n        Number of interleaved transforms
     */
    private static void fft_bf2(lc3_fft_bf2_twiddles twiddles, Complex[] x, Complex[] y, int n) {
        int n2 = twiddles.n2;
        Complex[] w = twiddles.t;

        int x0 = 0; // x
        int x1 = x0 + n * n2;
        int y0 = 0; // y
        int y1 = y0 + n2;

        for (int i = 0; i < n; i++, y0 += 2 * n2, y1 += 2 * n2) {

            for (int j = 0; j < n2; j++, x0++, x1++) {

                y[y0 + j].re = x[x0].re + x[x1].re * w[j].re - x[x1].im * w[j].im;
                y[y0 + j].im = x[x0].im + x[x1].im * w[j].re + x[x1].re * w[j].im;

                y[y1 + j].re = x[x0].re - x[x1].re * w[j].re + x[x1].im * w[j].im;
                y[y1 + j].im = x[x0].im - x[x1].im * w[j].re - x[x1].re * w[j].im;
            }
        }
    }

    /**
     * Perform FFT
     *
     * @param x  Input buffers of size `n`
     * @param y0 scratch buffers of size `n`
     * @param y1 scratch buffers of size `n`
     * @param n  Number of points 30, 40, 60, 80, 90, 120, 160, 180, 240, 480
     * @return The buffer `y0` or `y1` that hold the result
     * <p>
     * Input `x` can be the same as the `y0` second scratch buffer
     */
    static Complex[] fft(Complex[] x, int n, Complex[] y0, Complex[] y1) {
        Complex[][] y = {y1, y0};
        int i2, i3, is = 0;

        // The number of points `n` can be decomposed as :
        //
        //   n = 5^1 * 3^n3 * 2^n2
        //
        //   for n = 10, 20, 40, 80, 160    n3 = 0, n2 = [1..5]
        //       n = 30, 60, 120, 240, 480  n3 = 1, n2 = [1..5]
        //       n = 90, 180                n3 = 2, n2 = [1..2]
        //
        // Note that the expression `n & (n-1) == 0` is equivalent
        // to the check that `n` is a power of 2.

        fft_5(x, y[is], n /= 5);

        for (i3 = 0; (n & (n - 1)) != 0; i3++, is ^= 1)
            fft_bf3(lc3_fft_twiddles_bf3[i3], y[is], y[is ^ 1], n /= 3);

        for (i2 = 0; n > 1; i2++, is ^= 1)
            fft_bf2(lc3_fft_twiddles_bf2[i2][i3], y[is], y[is ^ 1], n >>= 1);

        return y[is];
    }

    //
    // MDCT processing
    //

    /**
     * Windowing of samples before MDCT
     *
     * @param dt Duration
     * @param sr sampleRate
     * @param x  Input current abd delayed samples
     * @param y  Output windowed samples
     * @param d  Output delayed ones
     */
    private void mdct_window(Duration dt, SRate sr, float[] x, float[] d, float[] y) {
        float[] win = lc3_mdct_win.get(dt)[sr.ordinal()];
        int ns = lc3_ns(dt, sr), nd = lc3_nd(dt, sr);

        int w0 = 0; // win
        int w1 = w0 + ns;
        int w2 = w1;
        int w3 = w2 + nd;

        int x0 = ns - nd; // x
        int x1 = x0;
        int y0 = ns / 2; // y
        int y1 = y0;
        int d0 = 0; // d
        int d1 = nd;

        while (x1 > 0) {
            y[--y0] = d[d0] * win[w0++] - x[--x1] * win[--w1];
            y[y1++] = (d[d0++] = x[x0++]) * win[w2++];

            y[--y0] = d[d0] * win[w0++] - x[--x1] * win[--w1];
            y[y1++] = (d[d0++] = x[x0++]) * win[w2++];
        }

        for (x1 += ns; x0 < x1; ) {
            y[--y0] = d[d0] * win[w0++] - d[--d1] * win[--w1];
            y[y1++] = (d[d0++] = x[x0++]) * win[w2++] + (d[d1] = x[--x1]) * win[--w3];

            y[--y0] = d[d0] * win[w0++] - d[--d1] * win[--w1];
            y[y1++] = (d[d0++] = x[x0++]) * win[w2++] + (d[d1] = x[--x1]) * win[--w3];
        }
    }

    /**
     * Pre-rotate MDCT coefficients of N/2 points, before FFT N/4 points FFT
     * <p>
     * `x` and y` can be the same buffer
     *
     * @param def Size and twiddles factors
     * @param x   Input  coefficients
     * @param y   output coefficients
     */
    private void mdct_pre_fft(lc3_mdct_rot_def def, float[] x, Complex[] y) {
        int n4 = def.n4;

        int x0 = 0; // x;
        int x1 = x0 + 2 * n4;
        Complex[] w = def.w;
        int w0 = 0; // def.w;
        int w1 = w0 + n4;
        int y0 = 0; // y
        int y1 = y0 + n4;

        while (x0 < x1) {
            Complex u = new Complex();
            Complex uw = w[w0++];
            u.re = -x[--x1] * uw.re + x[x0] * uw.im;
            u.im = x[x0++] * uw.re + x[x1] * uw.im;

            Complex v = new Complex();
            Complex vw = w[--w1];
            v.re = -x[--x1] * vw.im + x[x0] * vw.re;
            v.im = -x[x0++] * vw.im - x[x1] * vw.re;

            y[y0++] = u;
            y[--y1] = v;
        }
    }

    /**
     * Post-rotate FFT N/4 points coefficients, resulting MDCT N points
     * <p>
     * `x` and y` can be the same buffer
     *
     * @param def Size and twiddles factors
     * @param x   Input coefficients
     * @param y   output coefficients
     */
    private void mdct_post_fft(lc3_mdct_rot_def def, Complex[] x, float[] y) {
        int n4 = def.n4, n8 = n4 >> 1;

        Complex[] w = def.w;
        int w0 = n8; // def.w
        int w1 = w0 - 1;
        int x0 = n8; // x
        int x1 = x0 - 1;

        int y0 = n4; // y
        int y1 = y0;

        for (; y1 > 0; x0++, x1--, w0++, w1--) {

            float u0 = x[x0].im * w[w0].im + x[x0].re * w[w0].re;
            float u1 = x[x1].re * w[w1].im - x[x1].im * w[w1].re;

            float v0 = x[x0].re * w[w0].im - x[x0].im * w[w0].re;
            float v1 = x[x1].im * w[w1].im + x[x1].re * w[w1].re;

            y[y0++] = u0;
            y[y0++] = u1;
            y[--y1] = v0;
            y[--y1] = v1;
        }
    }

    /**
     * Pre-rotate IMDCT coefficients of N points, before FFT N/4 points FFT
     * <p>
     * `x` and `y` can be the same buffer
     * The real and imaginary parts of `y` are swapped,
     * to operate on FFT instead of IFFT
     *
     * @param def Size and twiddles factors
     * @param x   Input coefficients
     * @param y   output coefficients
     */
    private void imdct_pre_fft(lc3_mdct_rot_def def, float[] x, Complex[] y) {
        int n4 = def.n4;

        int x0 = 0; // x
        int x1 = x0 + 2 * n4;

        Complex[] w = def.w;
        int w0 = 0;
        int w1 = w0 + n4;
        int y0 = 0; // y
        int y1 = y0 + n4;

        while (x0 < x1) {
            float u0 = x[x0++];
            float u1 = x[--x1];
            float v0 = x[x0++];
            float v1 = x[--x1];
            Complex uw = w[w0++];
            Complex vw = w[--w1];

            y[y0].re = -u0 * uw.re - u1 * uw.im;
            y[y0++].im = -u1 * uw.re + u0 * uw.im;

            y[--y1].re = -v1 * vw.re - v0 * vw.im;
            y[y1].im = -v0 * vw.re + v1 * vw.im;
        }
    }

    /**
     * Post-rotate FFT N/4 points coefficients, resulting IMDCT N points
     * <p>
     * `x` and y` can be the same buffer
     * The real and imaginary parts of `x` are swapped,
     * to operate on FFT instead of IFFT
     *
     * @param def Size and twiddles factors
     * @param x   Input coefficients
     * @param y   output coefficients
     */
    private void imdct_post_fft(lc3_mdct_rot_def def, Complex[] x, float[] y) {
        int n4 = def.n4;

        Complex[] w = def.w;
        int w0 = 0; // w;
        int w1 = w0 + n4;
        int x0 = 0; // x
        int x1 = x0 + n4;

        int y0 = 0; // y
        int y1 = y0 + 2 * n4;

        while (x0 < x1) {
            Complex uz = x[x0++];
            Complex vz = x[--x1];
            Complex uw = w[w0++];
            Complex vw = w[--w1];

            y[y0++] = uz.re * uw.im - uz.im * uw.re;
            y[--y1] = uz.re * uw.re + uz.im * uw.im;

            y[--y1] = vz.re * vw.im - vz.im * vw.re;
            y[y0++] = vz.re * vw.re + vz.im * vw.im;
        }
    }

    /**
     * Apply windowing of samples
     *
     * @param dt Duration
     * @param sr sampleRate
     * @param x  Middle half of IMDCT coefficients
     * @param d  delayed samples
     * @param y  Output samples
     */
    private void imdct_window(Duration dt, SRate sr, float[] x, float[] d, float[] y) {

        // The full MDCT coefficients is given by symmetry :
        //   T[   0 ..  n/4-1] = -half[n/4-1 .. 0    ]
        //   T[ n/4 ..  n/2-1] =  half[0     .. n/4-1]
        //   T[ n/2 .. 3n/4-1] =  half[n/4   .. n/2-1]
        //   T[3n/4 ..    n-1] =  half[n/2-1 .. n/4  ]

        float[] win = lc3_mdct_win.get(dt)[sr.ordinal()];
        int n4 = lc3_ns(dt, sr) >> 1, nd = lc3_nd(dt, sr);
        float[] w = win;
        int w2 = 0; // win;
        int w0 = w2 + 3 * n4;
        int w1 = w0;

        int x0 = nd - n4; // d
        int x1 = x0; // d
        int y0 = nd - n4; // y
        int y1 = y0; // y
        int y2 = nd; // d
        int y3 = 0; // d

        int xp = 0; // x

        while (y0 > 0) {
            y[--y0] = d[--x0] - x[xp] * w[w1++];
            y[y1++] = d[x1++] + x[xp++] * w[--w0];

            y[--y0] = d[--x0] - x[xp] * w[w1++];
            y[y1++] = d[x1++] + x[xp++] * w[--w0];
        }

        while (y1 < nd) {
            y[y1++] = d[x1++] + x[xp++] * w[--w0];
            y[y1++] = d[x1++] + x[xp++] * w[--w0];
        }

        while (y1 < 2 * n4) {
            y[y1++] = x[xp] * w[--w0];
            d[--y2] = x[xp++] * w[w2++];

            y[y1++] = x[xp] * w[--w0];
            d[--y2] = x[xp++] * w[w2++];
        }

        while (y2 > y3) {
            d[y3++] = x[xp] * w[--w0];
            d[--y2] = x[xp++] * w[w2++];

            d[y3++] = x[xp] * w[--w0];
            d[--y2] = x[xp++] * w[w2++];
        }
    }

    /**
     * Rescale samples
     *
     * @param x Input
     * @param n count of samples, scaled as output
     * @param f Scale factor
     */
    private void rescale(float[] x, int n, float f) {
        int xP = 0;
        for (int i = 0; i < (n >> 2); i++) {
            x[xP++] *= f;
            x[xP++] *= f;
            x[xP++] *= f;
            x[xP++] *= f;
        }
    }

    private static class Union {

        float[] f() {
            float[] fs = new float[z.length * 2];
            IntStream.range(0, fs.length).forEach(i -> {
                fs[i * 2 + 0] = z[i].re;
                fs[i * 2 + 1] = z[i].im;
            });
            return fs;
        }

        Complex[] z;

        Union(Complex[] buffer) {
            z = buffer;
        }

        Union(float[] fs) {
            z = new Complex[fs.length / 2];
            IntStream.range(0, z.length).forEach(i -> {
                z[i] = new Complex();
                z[i].re = fs[i * 2 + 0];
                z[i].im = fs[i * 2 + 1];
            });
        }
    }

    /**
     * Forward MDCT transformation
     * <p>
     * `x` and `y` can be the same buffer
     *
     * @param dt,    Duration  (size of the transform)
     * @param sr     sampleRate
     * @param sr_dst sampleRate destination, scale transform accordingly
     * @param x      Temporal samples
     * @param d      delayed buffer
     * @param y      Output `ns` coefficients and `nd` delayed samples
     */
    void lc3_mdct_forward(Duration dt, SRate sr, SRate sr_dst, float[] x, float[] d, float[] y) {
        lc3_mdct_rot_def rot = lc3_mdct_rot.get(dt)[sr.ordinal()];
        int ns_dst = lc3_ns(dt, sr_dst);
        int ns = lc3_ns(dt, sr);

        Complex[] buffer = new Complex[LC3_MAX_NS / 2];
        Complex[] z = new Union(y).z;
        Union u = new Union(buffer);

        mdct_window(dt, sr, x, d, u.f());

        mdct_pre_fft(rot, u.f(), u.z);
        u.z = fft(u.z, ns / 2, u.z, z);
        mdct_post_fft(rot, u.z, y);

        if (ns != ns_dst)
            rescale(y, ns_dst, (float) Math.sqrt((float) ns_dst / ns));
    }

    /**
     * Inverse MDCT transformation
     * <p>
     * `x` and `y` can be the same buffer
     *
     * @param dt    Duration
     * @param sr    sampleRate (size of the transform)
     * @param srSrc sampleRate source, scale transform accordingly
     * @param x     Frequency coefficients
     * @param d     delayed buffer
     * @param y     Output `ns` samples and `nd` delayed ones
     */
    void lc3_mdct_inverse(Duration dt, SRate sr, SRate srSrc, float[] x, float[] d, float[] y) {
        lc3_mdct_rot_def rot = lc3_mdct_rot.get(dt)[sr.ordinal()];
        int ns_src = lc3_ns(dt, srSrc);
        int ns = lc3_ns(dt, sr);

        Complex[] buffer = new Complex[LC3_MAX_NS / 2];
        Complex[] z = new Union(y).z;
        Union u = new Union(buffer);

        imdct_pre_fft(rot, x, z);
        z = fft(z, ns / 2, z, u.z);
        imdct_post_fft(rot, z, u.f());

        if (ns != ns_src)
            rescale(u.f(), ns, (float) Math.sqrt((float) ns / ns_src));

        imdct_window(dt, sr, u.f(), d, y);
    }
}
