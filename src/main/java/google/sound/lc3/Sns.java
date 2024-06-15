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

import google.sound.lc3.Lc3.Duration;
import google.sound.lc3.Lc3.SRate;

import static google.sound.lc3.Energy.LC3_MAX_BANDS;
import static google.sound.lc3.Energy.lc3_band_lim;
import static google.sound.lc3.Energy.lc3_num_bands;
import static google.sound.lc3.FastMath.log2f;
import static google.sound.lc3.Lc3.Duration._10M;
import static google.sound.lc3.Lc3.Duration._7M5;
import static google.sound.lc3.Lc3.SRate._16K;
import static google.sound.lc3.Lc3.SRate._24K;
import static google.sound.lc3.Lc3.SRate._32K;
import static google.sound.lc3.Lc3.SRate._48K;
import static google.sound.lc3.Lc3.SRate._48K_HR;
import static google.sound.lc3.Lc3.SRate._8K;
import static google.sound.lc3.Lc3.SRate._96K_HR;
import static google.sound.lc3.Lc3.isHR;


/**
 * SNS Quantization
 */
class Sns {

    //
    // Bitstream data
    //

    //
    // DCT-16
    //

    /**
     * Matrix of DCT-16 coefficients
     * <pre>
     * M[n][k] = 2f cos( Pi k (2n + 1) / 2N )
     *
     *   k = [0..N-1], n = [0..N-1], N = 16
     *   f = sqrt(1/4N) for k=0, sqrt(1/2N) otherwise
     * </pre>
     */
    private static final float[][] dct16_m = new float[][] {{
            2.50000000e-01f, 3.51850934e-01f, 3.46759961e-01f, 3.38329500e-01f,
            3.26640741e-01f, 3.11806253e-01f, 2.93968901e-01f, 2.73300467e-01f,
            2.50000000e-01f, 2.24291897e-01f, 1.96423740e-01f, 1.66663915e-01f,
            1.35299025e-01f, 1.02631132e-01f, 6.89748448e-02f, 3.46542923e-02f
    }, {
            2.50000000e-01f, 3.38329500e-01f, 2.93968901e-01f, 2.24291897e-01f,
            1.35299025e-01f, 3.46542923e-02f, -6.89748448e-02f, -1.66663915e-01f,
            -2.50000000e-01f, -3.11806253e-01f, -3.46759961e-01f, -3.51850934e-01f,
            -3.26640741e-01f, -2.73300467e-01f, -1.96423740e-01f, -1.02631132e-01f
    }, {
            2.50000000e-01f, 3.11806253e-01f, 1.96423740e-01f, 3.46542923e-02f,
            -1.35299025e-01f, -2.73300467e-01f, -3.46759961e-01f, -3.38329500e-01f,
            -2.50000000e-01f, -1.02631132e-01f, 6.89748448e-02f, 2.24291897e-01f,
            3.26640741e-01f, 3.51850934e-01f, 2.93968901e-01f, 1.66663915e-01f
    }, {
            2.50000000e-01f, 2.73300467e-01f, 6.89748448e-02f, -1.66663915e-01f,
            -3.26640741e-01f, -3.38329500e-01f, -1.96423740e-01f, 3.46542923e-02f,
            2.50000000e-01f, 3.51850934e-01f, 2.93968901e-01f, 1.02631132e-01f,
            -1.35299025e-01f, -3.11806253e-01f, -3.46759961e-01f, -2.24291897e-01f
    }, {
            2.50000000e-01f, 2.24291897e-01f, -6.89748448e-02f, -3.11806253e-01f,
            -3.26640741e-01f, -1.02631132e-01f, 1.96423740e-01f, 3.51850934e-01f,
            2.50000000e-01f, -3.46542923e-02f, -2.93968901e-01f, -3.38329500e-01f,
            -1.35299025e-01f, 1.66663915e-01f, 3.46759961e-01f, 2.73300467e-01f
    }, {
            2.50000000e-01f, 1.66663915e-01f, -1.96423740e-01f, -3.51850934e-01f,
            -1.35299025e-01f, 2.24291897e-01f, 3.46759961e-01f, 1.02631132e-01f,
            -2.50000000e-01f, -3.38329500e-01f, -6.89748448e-02f, 2.73300467e-01f,
            3.26640741e-01f, 3.46542923e-02f, -2.93968901e-01f, -3.11806253e-01f
    }, {
            2.50000000e-01f, 1.02631132e-01f, -2.93968901e-01f, -2.73300467e-01f,
            1.35299025e-01f, 3.51850934e-01f, 6.89748448e-02f, -3.11806253e-01f,
            -2.50000000e-01f, 1.66663915e-01f, 3.46759961e-01f, 3.46542923e-02f,
            -3.26640741e-01f, -2.24291897e-01f, 1.96423740e-01f, 3.38329500e-01f
    }, {
            2.50000000e-01f, 3.46542923e-02f, -3.46759961e-01f, -1.02631132e-01f,
            3.26640741e-01f, 1.66663915e-01f, -2.93968901e-01f, -2.24291897e-01f,
            2.50000000e-01f, 2.73300467e-01f, -1.96423740e-01f, -3.11806253e-01f,
            1.35299025e-01f, 3.38329500e-01f, -6.89748448e-02f, -3.51850934e-01f
    }, {
            2.50000000e-01f, -3.46542923e-02f, -3.46759961e-01f, 1.02631132e-01f,
            3.26640741e-01f, -1.66663915e-01f, -2.93968901e-01f, 2.24291897e-01f,
            2.50000000e-01f, -2.73300467e-01f, -1.96423740e-01f, 3.11806253e-01f,
            1.35299025e-01f, -3.38329500e-01f, -6.89748448e-02f, 3.51850934e-01f
    }, {
            2.50000000e-01f, -1.02631132e-01f, -2.93968901e-01f, 2.73300467e-01f,
            1.35299025e-01f, -3.51850934e-01f, 6.89748448e-02f, 3.11806253e-01f,
            -2.50000000e-01f, -1.66663915e-01f, 3.46759961e-01f, -3.46542923e-02f,
            -3.26640741e-01f, 2.24291897e-01f, 1.96423740e-01f, -3.38329500e-01f
    }, {
            2.50000000e-01f, -1.66663915e-01f, -1.96423740e-01f, 3.51850934e-01f,
            -1.35299025e-01f, -2.24291897e-01f, 3.46759961e-01f, -1.02631132e-01f,
            -2.50000000e-01f, 3.38329500e-01f, -6.89748448e-02f, -2.73300467e-01f,
            3.26640741e-01f, -3.46542923e-02f, -2.93968901e-01f, 3.11806253e-01f
    }, {
            2.50000000e-01f, -2.24291897e-01f, -6.89748448e-02f, 3.11806253e-01f,
            -3.26640741e-01f, 1.02631132e-01f, 1.96423740e-01f, -3.51850934e-01f,
            2.50000000e-01f, 3.46542923e-02f, -2.93968901e-01f, 3.38329500e-01f,
            -1.35299025e-01f, -1.66663915e-01f, 3.46759961e-01f, -2.73300467e-01f
    }, {
            2.50000000e-01f, -2.73300467e-01f, 6.89748448e-02f, 1.66663915e-01f,
            -3.26640741e-01f, 3.38329500e-01f, -1.96423740e-01f, -3.46542923e-02f,
            2.50000000e-01f, -3.51850934e-01f, 2.93968901e-01f, -1.02631132e-01f,
            -1.35299025e-01f, 3.11806253e-01f, -3.46759961e-01f, 2.24291897e-01f
    }, {
            2.50000000e-01f, -3.11806253e-01f, 1.96423740e-01f, -3.46542923e-02f,
            -1.35299025e-01f, 2.73300467e-01f, -3.46759961e-01f, 3.38329500e-01f,
            -2.50000000e-01f, 1.02631132e-01f, 6.89748448e-02f, -2.24291897e-01f,
            3.26640741e-01f, -3.51850934e-01f, 2.93968901e-01f, -1.66663915e-01f
    }, {
            2.50000000e-01f, -3.38329500e-01f, 2.93968901e-01f, -2.24291897e-01f,
            1.35299025e-01f, -3.46542923e-02f, -6.89748448e-02f, 1.66663915e-01f,
            -2.50000000e-01f, 3.11806253e-01f, -3.46759961e-01f, 3.51850934e-01f,
            -3.26640741e-01f, 2.73300467e-01f, -1.96423740e-01f, 1.02631132e-01f
    }, {
            2.50000000e-01f, -3.51850934e-01f, 3.46759961e-01f, -3.38329500e-01f,
            3.26640741e-01f, -3.11806253e-01f, 2.93968901e-01f, -2.73300467e-01f,
            2.50000000e-01f, -2.24291897e-01f, 1.96423740e-01f, -1.66663915e-01f,
            1.35299025e-01f, -1.02631132e-01f, 6.89748448e-02f, -3.46542923e-02f
    }};

    /**
     * Forward DCT-16 transformation
     *
     * @param x Input  16 values
     * @param y output 16 values
     */
    private static void dct16_forward(float[] x, float[] y) {
        for (int i = 0, j; i < 16; i++)
            for (y[i] = 0, j = 0; j < 16; j++)
                y[i] += x[j] * dct16_m[j][i];
    }

    /**
     * Inverse DCT-16 transformation
     *
     * @param x Input 16 values
     * @param y output 16 values
     */
    private static void dct16_inverse(float[] x, float[] y) {
        for (int i = 0, j; i < 16; i++)
            for (y[i] = 0, j = 0; j < 16; j++)
                y[i] += x[j] * dct16_m[i][j];
    }

    //
    // Scale factors
    //

    // Pre-emphasis gain table :
    // Ge[b] = 10 ^ (b * g_tilt) / 630 , b = [0..63]

    private static final float[] ge_14 = { /* g_tilt = 14 */
            1.00000000e+00f, 1.05250029e+00f, 1.10775685e+00f, 1.16591440e+00f,
            1.22712524e+00f, 1.29154967e+00f, 1.35935639e+00f, 1.43072299e+00f,
            1.50583635e+00f, 1.58489319e+00f, 1.66810054e+00f, 1.75567629e+00f,
            1.84784980e+00f, 1.94486244e+00f, 2.04696827e+00f, 2.15443469e+00f,
            2.26754313e+00f, 2.38658979e+00f, 2.51188643e+00f, 2.64376119e+00f,
            2.78255940e+00f, 2.92864456e+00f, 3.08239924e+00f, 3.24422608e+00f,
            3.41454887e+00f, 3.59381366e+00f, 3.78248991e+00f, 3.98107171e+00f,
            4.19007911e+00f, 4.41005945e+00f, 4.64158883e+00f, 4.88527357e+00f,
            5.14175183e+00f, 5.41169527e+00f, 5.69581081e+00f, 5.99484250e+00f,
            6.30957344e+00f, 6.64082785e+00f, 6.98947321e+00f, 7.35642254e+00f,
            7.74263683e+00f, 8.14912747e+00f, 8.57695899e+00f, 9.02725178e+00f,
            9.50118507e+00f, 1.00000000e+01f, 1.05250029e+01f, 1.10775685e+01f,
            1.16591440e+01f, 1.22712524e+01f, 1.29154967e+01f, 1.35935639e+01f,
            1.43072299e+01f, 1.50583635e+01f, 1.58489319e+01f, 1.66810054e+01f,
            1.75567629e+01f, 1.84784980e+01f, 1.94486244e+01f, 2.04696827e+01f,
            2.15443469e+01f, 2.26754313e+01f, 2.38658979e+01f, 2.51188643e+01f
    };

    private static final float[] ge_18 = { /* g_tilt = 18 */
            1.00000000e+00f, 1.06800043e+00f, 1.14062492e+00f, 1.21818791e+00f,
            1.30102522e+00f, 1.38949549e+00f, 1.48398179e+00f, 1.58489319e+00f,
            1.69266662e+00f, 1.80776868e+00f, 1.93069773e+00f, 2.06198601e+00f,
            2.20220195e+00f, 2.35195264e+00f, 2.51188643e+00f, 2.68269580e+00f,
            2.86512027e+00f, 3.05994969e+00f, 3.26802759e+00f, 3.49025488e+00f,
            3.72759372e+00f, 3.98107171e+00f, 4.25178630e+00f, 4.54090961e+00f,
            4.84969343e+00f, 5.17947468e+00f, 5.53168120e+00f, 5.90783791e+00f,
            6.30957344e+00f, 6.73862717e+00f, 7.19685673e+00f, 7.68624610e+00f,
            8.20891416e+00f, 8.76712387e+00f, 9.36329209e+00f, 1.00000000e+01f,
            1.06800043e+01f, 1.14062492e+01f, 1.21818791e+01f, 1.30102522e+01f,
            1.38949549e+01f, 1.48398179e+01f, 1.58489319e+01f, 1.69266662e+01f,
            1.80776868e+01f, 1.93069773e+01f, 2.06198601e+01f, 2.20220195e+01f,
            2.35195264e+01f, 2.51188643e+01f, 2.68269580e+01f, 2.86512027e+01f,
            3.05994969e+01f, 3.26802759e+01f, 3.49025488e+01f, 3.72759372e+01f,
            3.98107171e+01f, 4.25178630e+01f, 4.54090961e+01f, 4.84969343e+01f,
            5.17947468e+01f, 5.53168120e+01f, 5.90783791e+01f, 6.30957344e+01f
    };

    private static final float[] ge_22 = { /* g_tilt = 22 */
            1.00000000e+00f, 1.08372885e+00f, 1.17446822e+00f, 1.27280509e+00f,
            1.37937560e+00f, 1.49486913e+00f, 1.62003281e+00f, 1.75567629e+00f,
            1.90267705e+00f, 2.06198601e+00f, 2.23463373e+00f, 2.42173704e+00f,
            2.62450630e+00f, 2.84425319e+00f, 3.08239924e+00f, 3.34048498e+00f,
            3.62017995e+00f, 3.92329345e+00f, 4.25178630e+00f, 4.60778348e+00f,
            4.99358789e+00f, 5.41169527e+00f, 5.86481029e+00f, 6.35586411e+00f,
            6.88803330e+00f, 7.46476041e+00f, 8.08977621e+00f, 8.76712387e+00f,
            9.50118507e+00f, 1.02967084e+01f, 1.11588399e+01f, 1.20931568e+01f,
            1.31057029e+01f, 1.42030283e+01f, 1.53922315e+01f, 1.66810054e+01f,
            1.80776868e+01f, 1.95913107e+01f, 2.12316686e+01f, 2.30093718e+01f,
            2.49359200e+01f, 2.70237760e+01f, 2.92864456e+01f, 3.17385661e+01f,
            3.43959997e+01f, 3.72759372e+01f, 4.03970086e+01f, 4.37794036e+01f,
            4.74450028e+01f, 5.14175183e+01f, 5.57226480e+01f, 6.03882412e+01f,
            6.54444792e+01f, 7.09240702e+01f, 7.68624610e+01f, 8.32980665e+01f,
            9.02725178e+01f, 9.78309319e+01f, 1.06022203e+02f, 1.14899320e+02f,
            1.24519708e+02f, 1.34945600e+02f, 1.46244440e+02f, 1.58489319e+02f
    };

    private static final float[] ge_26 = { /* g_tilt = 26 */
            1.00000000e+00f, 1.09968890e+00f, 1.20931568e+00f, 1.32987103e+00f,
            1.46244440e+00f, 1.60823388e+00f, 1.76855694e+00f, 1.94486244e+00f,
            2.13874364e+00f, 2.35195264e+00f, 2.58641621e+00f, 2.84425319e+00f,
            3.12779366e+00f, 3.43959997e+00f, 3.78248991e+00f, 4.15956216e+00f,
            4.57422434e+00f, 5.03022373e+00f, 5.53168120e+00f, 6.08312841e+00f,
            6.68954879e+00f, 7.35642254e+00f, 8.08977621e+00f, 8.89623710e+00f,
            9.78309319e+00f, 1.07583590e+01f, 1.18308480e+01f, 1.30102522e+01f,
            1.43072299e+01f, 1.57335019e+01f, 1.73019574e+01f, 1.90267705e+01f,
            2.09235283e+01f, 2.30093718e+01f, 2.53031508e+01f, 2.78255940e+01f,
            3.05994969e+01f, 3.36499270e+01f, 3.70044512e+01f, 4.06933843e+01f,
            4.47500630e+01f, 4.92111475e+01f, 5.41169527e+01f, 5.95118121e+01f,
            6.54444792e+01f, 7.19685673e+01f, 7.91430346e+01f, 8.70327166e+01f,
            9.57089124e+01f, 1.05250029e+02f, 1.15742288e+02f, 1.27280509e+02f,
            1.39968963e+02f, 1.53922315e+02f, 1.69266662e+02f, 1.86140669e+02f,
            2.04696827e+02f, 2.25102829e+02f, 2.47543082e+02f, 2.72220379e+02f,
            2.99357729e+02f, 3.29200372e+02f, 3.62017995e+02f, 3.98107171e+02f
    };

    private static final float[] ge_30 = { /* g_tilt = 30 */
            1.00000000e+00f, 1.11588399e+00f, 1.24519708e+00f, 1.38949549e+00f,
            1.55051578e+00f, 1.73019574e+00f, 1.93069773e+00f, 2.15443469e+00f,
            2.40409918e+00f, 2.68269580e+00f, 2.99357729e+00f, 3.34048498e+00f,
            3.72759372e+00f, 4.15956216e+00f, 4.64158883e+00f, 5.17947468e+00f,
            5.77969288e+00f, 6.44946677e+00f, 7.19685673e+00f, 8.03085722e+00f,
            8.96150502e+00f, 1.00000000e+01f, 1.11588399e+01f, 1.24519708e+01f,
            1.38949549e+01f, 1.55051578e+01f, 1.73019574e+01f, 1.93069773e+01f,
            2.15443469e+01f, 2.40409918e+01f, 2.68269580e+01f, 2.99357729e+01f,
            3.34048498e+01f, 3.72759372e+01f, 4.15956216e+01f, 4.64158883e+01f,
            5.17947468e+01f, 5.77969288e+01f, 6.44946677e+01f, 7.19685673e+01f,
            8.03085722e+01f, 8.96150502e+01f, 1.00000000e+02f, 1.11588399e+02f,
            1.24519708e+02f, 1.38949549e+02f, 1.55051578e+02f, 1.73019574e+02f,
            1.93069773e+02f, 2.15443469e+02f, 2.40409918e+02f, 2.68269580e+02f,
            2.99357729e+02f, 3.34048498e+02f, 3.72759372e+02f, 4.15956216e+02f,
            4.64158883e+02f, 5.17947468e+02f, 5.77969288e+02f, 6.44946677e+02f,
            7.19685673e+02f, 8.03085722e+02f, 8.96150502e+02f, 1.00000000e+03f
    };

    private static final float[] ge_34 = { /* g_tilt = 34 */
            1.00000000e+00f, 1.13231759e+00f, 1.28214312e+00f, 1.45179321e+00f,
            1.64389099e+00f, 1.86140669e+00f, 2.10770353e+00f, 2.38658979e+00f,
            2.70237760e+00f, 3.05994969e+00f, 3.46483486e+00f, 3.92329345e+00f,
            4.44241419e+00f, 5.03022373e+00f, 5.69581081e+00f, 6.44946677e+00f,
            7.30284467e+00f, 8.26913948e+00f, 9.36329209e+00f, 1.06022203e+01f,
            1.20050806e+01f, 1.35935639e+01f, 1.53922315e+01f, 1.74288945e+01f,
            1.97350438e+01f, 2.23463373e+01f, 2.53031508e+01f, 2.86512027e+01f,
            3.24422608e+01f, 3.67349426e+01f, 4.15956216e+01f, 4.70994540e+01f,
            5.33315403e+01f, 6.03882412e+01f, 6.83786677e+01f, 7.74263683e+01f,
            8.76712387e+01f, 9.92716858e+01f, 1.12407076e+02f, 1.27280509e+02f,
            1.44121960e+02f, 1.63191830e+02f, 1.84784980e+02f, 2.09235283e+02f,
            2.36920791e+02f, 2.68269580e+02f, 3.03766364e+02f, 3.43959997e+02f,
            3.89471955e+02f, 4.41005945e+02f, 4.99358789e+02f, 5.65432741e+02f,
            6.40249439e+02f, 7.24965701e+02f, 8.20891416e+02f, 9.29509790e+02f,
            1.05250029e+03f, 1.19176459e+03f, 1.34945600e+03f, 1.52801277e+03f,
            1.73019574e+03f, 1.95913107e+03f, 2.21835857e+03f, 2.51188643e+03f
    };

    private static final Map<SRate, float[]> ge_table = Map.of(
            _8K, ge_14,
            _16K, ge_18,
            _24K, ge_22,
            _32K, ge_26,
            _48K, ge_30,
            _48K_HR, ge_30,
            _96K_HR, ge_34
    );

    private static final float[] e = new float[LC3_MAX_BANDS];

    /**
     * Scale factors
     *
     * @param dt     Duration of the frame
     * @param sr     sampleRate of the frame
     * @param nBytes Size in bytes of the frame
     * @param eb     Energy estimation per bands
     * @param att    1: Attack detected  0: Otherwise
     * @param scf    Output 16 scale factors
     */
    private static void compute_scale_factors(Duration dt, SRate sr, int nBytes, float[] eb, boolean att, float[] scf) {

        // Copy and padding

        int nb = lc3_num_bands.get(dt)[sr.ordinal()];
        int n4 = nb < 32 ? 32 % nb : 0;
        int n2 = nb < 32 ? nb - n4 : LC3_MAX_BANDS - nb;

        for (int i4 = 0; i4 < n4; i4++)
            e[4 * i4 + 0] = e[4 * i4 + 1] = e[4 * i4 + 2] = e[4 * i4 + 3] = eb[i4];

        for (int i2 = n4; i2 < n4 + n2; i2++)
            e[2 * (n4 + i2) + 0] = e[2 * (n4 + i2) + 1] = eb[i2];

        System.arraycopy(eb, n4 + n2, e, 4 * n4 + 2 * n2, nb - n4 - n2);

        // Smoothing, pre-emphasis and logarithm

        float[] ge = ge_table.get(sr);

        float e0 = e[0], e1 = e[0], e2;
        float eSum = 0;

        for (int i = 0; i < LC3_MAX_BANDS - 1; ) {
            e[i] = (e0 * 0.25f + e1 * 0.5f + (e2 = e[i + 1]) * 0.25f) * ge[i];
            eSum += e[i++];

            e[i] = (e1 * 0.25f + e2 * 0.5f + (e0 = e[i + 1]) * 0.25f) * ge[i];
            eSum += e[i++];

            e[i] = (e2 * 0.25f + e0 * 0.5f + (e1 = e[i + 1]) * 0.25f) * ge[i];
            eSum += e[i++];
        }

        e[LC3_MAX_BANDS - 1] = (e0 * 0.25f + e1 * 0.75f) * ge[LC3_MAX_BANDS - 1];
        eSum += e[LC3_MAX_BANDS - 1];

        float noise_floor = Math.max(eSum * (1e-4f / 64), 0x1p-32f);

        for (int i = 0; i < LC3_MAX_BANDS; i++)
            e[i] = log2f(Math.max(e[i], noise_floor)) * 0.5f;

        // Grouping & scaling

        float scfSum;

        scf[0] = (e[0] + e[4]) * 1.f / 12 + (e[0] + e[3]) * 2.f / 12 + (e[1] + e[2]) * 3.f / 12;
        scfSum = scf[0];

        for (int i = 1; i < 15; i++) {
            scf[i] = (e[4 * i - 1] + e[4 * i + 4]) * 1.f / 12 +
                    (e[4 * i] + e[4 * i + 3]) * 2.f / 12 + (e[4 * i + 1] + e[4 * i + 2]) * 3.f / 12;
            scfSum += scf[i];
        }

        scf[15] = (e[59] + e[63]) * 1.f / 12 + (e[60] + e[63]) * 2.f / 12 + (e[61] + e[62]) * 3.f / 12;
        scfSum += scf[15];

        float cf = isHR(sr) ? 0.6f : 0.85f;
        if (isHR(sr) && 8 * nBytes > (dt.ordinal() < _10M.ordinal() ? 1150 * (int) (1 + dt.ordinal()) : 4400))
            cf *= dt.ordinal() < _10M.ordinal() ? 0.25f : 0.35f;

        for (int i = 0; i < 16; i++)
            scf[i] = cf * (scf[i] - scfSum * 1.f / 16);

        // Attack handling

        if (!att) return;

        float s0, s1 = scf[0], s2 = scf[1], s3 = scf[2], s4 = scf[3];
        float sn = s1 + s2;

        scf[0] = (sn += s3) * 1.f / 3;
        scf[1] = (sn += s4) * 1.f / 4;
        scfSum = scf[0] + scf[1];

        for (int i = 2; i < 14; i++, sn -= s0) {
            s0 = s1;
            s1 = s2;
            s2 = s3;
            s3 = s4;
            s4 = scf[i + 2];
            scf[i] = (sn += s4) * 1.f / 5;
            scfSum += scf[i];
        }

        scf[14] = (sn) * 1.f / 4;
        scf[15] = (sn -= s1) * 1.f / 3;
        scfSum += scf[14] + scf[15];

        for (int i = 0; i < 16; i++)
            scf[i] = (dt == _7M5 ? 0.3f : 0.5f) * (scf[i] - scfSum * 1.f / 16);
    }

    /**
     * Codebooks
     *
     * @param scf Input 16 scale factors
     */
    private void resolve_codebooks(float[] scf) {
        float dlfcb_max = 0, dhfcb_max = 0;
        this.lfcb = 0;
        this.hfcb = 0;

        for (int icb = 0; icb < 32; icb++) {
            float[] lfcb = lc3_sns_lfcb[icb];
            float[] hfcb = lc3_sns_hfcb[icb];
            float dlfcb = 0, dhfcb = 0;

            for (int i = 0; i < 8; i++) {
                dlfcb += (scf[i] - lfcb[i]) * (scf[i] - lfcb[i]);
                dhfcb += (scf[8 + i] - hfcb[i]) * (scf[8 + i] - hfcb[i]);
            }

            if (icb == 0 || dlfcb < dlfcb_max) this.lfcb = icb;
            dlfcb_max = dlfcb;

            if (icb == 0 || dhfcb < dhfcb_max) this.hfcb = icb;
            dhfcb_max = dhfcb;
        }
    }

    /**
     * Unit energy normalize pulse configuration
     *
     * @param c  Pulse configuration
     * @param cn Normalized pulse configuration
     */
    private static void normalize(int[] c, float[] cn) {
        int c2_sum = 0;
        for (int i = 0; i < 16; i++)
            c2_sum += c[i] * c[i];

        float c_norm = 1.f / (float) Math.sqrt(c2_sum);

        for (int i = 0; i < 16; i++)
            cn[i] = c[i] * c_norm;
    }

    /**
     * Sub-procedure of `quantize()`, add unit pulse
     *
     * @param x      Transformed residual
     * @param y      vector of pulses
     * @param n      length
     * @param start  Current number of pulses
     * @param end    limit to reach
     * @param corr   Correlation (x,y) and y energy
     * @param energy updated at output
     */
    private static void add_pulse(float[] x, int xP, int[] y, int yP, int n, int start, int end, float[] corr, float[] energy) {
        for (int k = start; k < end; k++) {
            float best_c2 = (corr[0] + x[xP + 0]) * (corr[0] + x[xP + 0]);
            float best_e = energy[0] + 2 * y[yP + 0] + 1;
            int nbest = 0;

            for (int i = 1; i < n; i++) {
                float c2 = (corr[0] + x[xP + i]) * (corr[0] + x[xP + i]);
                float e = energy[0] + 2 * y[yP + i] + 1;

                if (c2 * best_e > e * best_c2) best_c2 = c2;
                best_e = e;
                nbest = i;
            }

            corr[0] += x[xP + nbest];
            energy[0] += 2 * y[yP + nbest] + 1;
            y[yP + nbest]++;
        }
    }

    /**
     * Quantization of codebooks residual
     *
     * @param scf       Input 16 scale factors, output quantized version
     * @param lfcbIndex Codebooks index
     * @param hfcbIndex Codebooks index
     * @param c         Output 4 pulse configurations candidates
     * @param cn        Output 4 pulse configurations normalized
     */
    private void quantize(float[] scf, int lfcbIndex, int hfcbIndex, int[][] c, float[][] cn) {

        // Residual

        float[] lfcb = lc3_sns_lfcb[lfcbIndex];
        float[] hfcb = lc3_sns_hfcb[hfcbIndex];
        float[] r = new float[16], x = new float[16];

        for (int i = 0; i < 8; i++) {
            r[i] = scf[i] - lfcb[i];
            r[8 + i] = scf[8 + i] - hfcb[i];
        }

        dct16_forward(r, x);

        // Shape 3 candidate
        // Project to or below pyramid N = 16, K = 6,
        // then add unit pulses until you reach K = 6, over N = 16

        float[] xm = new float[16];
        float xmSum = 0;

        for (int i = 0; i < 16; i++) {
            xm[i] = Math.abs(x[i]);

            xmSum += xm[i];
        }

        float projFactor = (6 - 1) / Math.max(xmSum, 1e-31f);
        float[] corr = new float[] {0}, energy = new float[] {0};
        int nPulses = 0;

        for (int i = 0; i < 16; i++) {
            c[3][i] = (int) Math.floor(xm[i] * projFactor);

            nPulses += c[3][i];
            corr[0] += c[3][i] * xm[i];
            energy[0] += c[3][i] * c[3][i];
        }

        add_pulse(xm, 0, c[3], 0, 16, nPulses, 6, corr, energy);

        nPulses = 6;

        // Shape 2 candidate
        // Add unit pulses until you reach K = 8 on shape 3

        System.arraycopy(c[2], 0, c[3], 0, c[2].length);

        add_pulse(xm, 0, c[2], 0, 16, nPulses, 8, corr, energy);

        nPulses = 8;

        // Shape 1 candidate
        // Remove any unit pulses from shape 2 that are not part of 0 to 9
        // Update energy and correlation terms accordingly
        // Add unit pulses until you reach K = 10, over N = 10

        System.arraycopy(c[2], 0, c[1], 0, c[1].length);

        for (int i = 10; i < 16; i++) {
            c[1][i] = 0;
            nPulses -= c[2][i];
            corr[0] -= c[2][i] * xm[i];
            energy[0] -= c[2][i] * c[2][i];
        }

        add_pulse(xm, 0, c[1], 0, 10, nPulses, 10, corr, energy);

        nPulses = 10;

        // Shape 0 candidate
        // Add unit pulses until you reach K = 1, on shape 1

        System.arraycopy(c[1], 0, c[0], 0, c[0].length);

        add_pulse(xm, 10, c[0], 10, 6, 0, 1, corr, energy);

        // Add sign and unit energy normalize

        for (int j = 0; j < 16; j++)
            for (int i = 0; i < 4; i++)
                c[i][j] = x[j] < 0 ? -c[i][j] : c[i][j];

        for (int i = 0; i < 4; i++)
            normalize(c[i], cn[i]);

        // Determe shape & gain index
        // Search the Mean Square Error, within (shape, gain) combinations

        float mseMin = Float.NEGATIVE_INFINITY;
        this.shape = this.gain = 0;

        for (int ic = 0; ic < 4; ic++) {
            lc3_sns_vq_gains cGains = _lc3_sns_vq_gains[ic];
            float cmseMin = Float.NEGATIVE_INFINITY;
            int cGainIndex = 0;

            for (int ig = 0; ig < cGains.count; ig++) {
                float g = cGains.v[ig];

                float mse = 0;
                for (int i = 0; i < 16; i++)
                    mse += (x[i] - g * cn[ic][i]) * (x[i] - g * cn[ic][i]);

                if (mse < cmseMin) {
                    cGainIndex = ig;
                    cmseMin = mse;
                }
            }

            if (cmseMin < mseMin) {
                this.shape = ic;
                this.gain = cGainIndex;
                mseMin = cmseMin;
            }
        }
    }

    /**
     * Unquantization of codebooks residual
     *
     * @param lfcbIndex Low  frequency codebooks index
     * @param hfcbIndex high frequency codebooks index
     * @param c         Table of normalized pulse configuration
     * @param shape     Selected shape
     * @param gain      gain indexes
     * @param scf       Return unquantized scale factors
     */
    private static void unquantize(int lfcbIndex, int hfcbIndex, float[] c, int shape, int gain, float[] scf) {
        float[] lfcb = lc3_sns_lfcb[lfcbIndex];
        float[] hfcb = lc3_sns_hfcb[hfcbIndex];
        float g = _lc3_sns_vq_gains[shape].v[gain];

        dct16_inverse(c, scf);

        for (int i = 0; i < 8; i++)
            scf[i] = lfcb[i] + g * scf[i];

        for (int i = 8; i < 16; i++)
            scf[i] = hfcb[i - 8] + g * scf[i];
    }

    /**
     * Sub-procedure of `sns_enumerate()`, enumeration of a vector
     *
     * @param c   Table of pulse configuration
     * @param n   length
     * @param idx Return enumeration set
     * @param ls  Return enumeration set
     */
    private static void enum_mvpq(int[] c, int cp, int n, int[] idx, boolean[] ls) {
        int ci, i;

        // Scan for 1st significant coeff

        i = 0;
        cp += n;
        while ((ci = c[--cp]) == 0 && i < 15) {
            i++;
        }

        idx[0] = 0;
        ls[0] = ci < 0;

        // Scan remaining coefficients

        int j = Math.abs(ci);

        for (i++; i < n; i++, j += Math.abs(ci)) {

            if ((ci = c[--cp]) != 0) {
                idx[0] = (idx[0] << 1) | (ls[0] ? 1 : 0);
                ls[0] = ci < 0;
            }

            idx[0] += lc3_sns_mpvq_offsets[i][Math.min(j, 10)];
        }
    }

    /**
     * Sub-procedure of `sns_deenumerate()`, deenumeration of a vector
     *
     * @param idx     Enumeration set
     * @param ls      Enumeration set
     * @param nPulses Number of pulses in the set
     * @param c       Table of pulses configuration
     * @param n       and length
     */
    private static void deenum_mvpq(int idx, boolean ls, int nPulses, int[] c, int cp, int n) {
        int i;

        // Scan for coefficients

        for (i = n - 1; i >= 0 && idx != 0; i--) {

            int ci = 0;

            while (idx < lc3_sns_mpvq_offsets[i][nPulses - ci]) ci++;
            idx -= lc3_sns_mpvq_offsets[i][nPulses - ci];

            c[cp++] = ls ? -ci : ci;
            nPulses -= ci;
            if (ci > 0) {
                ls = (idx & 1) != 0;
                idx >>= 1;
            }
        }

        // Set last significant

        int ci = nPulses;

        if (i-- >= 0) c[cp++] = ls ? -ci : ci;

        while (i-- >= 0) c[cp++] = 0;
    }

    /**
     * SNS Enumeration of PVQ configuration
     *
     * @param shape Selected shape index
     * @param c     Selected pulse configuration
     * @param sns   Return enumeration set A, enumeration set B (shape = 0)
     */
    private static void enumerate(int shape, int[] c, Sns sns) {
        int[] i = new int[1];
        boolean[] b = new boolean[1];
        enum_mvpq(c, 0, shape < 2 ? 10 : 16, i, b);
        sns.idx_a = i[0];
        sns.ls_a = b[0];

        if (shape == 0) {
            enum_mvpq(c, +10, 6, i, b);
            sns.idx_b = i[0];
            sns.ls_b = b[0];
        }
    }

    private static final int[] nPulses = new int[] {10, 10, 8, 6};

    /**
     * SNS Deenumeration of PVQ configuration
     *
     * @param shape Selected shape index
     * @param idx_a enumeration set A
     * @param ls_a  enumeration set A
     * @param idx_b enumeration set B (shape = 0)
     * @param ls_b  enumeration set B (shape = 0)
     * @param c     Return pulse configuration
     */
    private static void deenumerate(int shape, int idx_a, boolean ls_a, int idx_b, boolean ls_b, int[] c) {

        deenum_mvpq(idx_a, ls_a, nPulses[shape], c, 0, shape < 2 ? 10 : 16);

        if (shape == 0) deenum_mvpq(idx_b, ls_b, 1, c, 10, 6);
        else if (shape == 1) Arrays.fill(c, 10, 10 + 6, 0);
    }

    //
    // Filtering
    //

    /**
     * Spectral shaping
     * <p>
     * `x` and `y` can be the same buffer
     *
     * @param dt    Duration of the frame
     * @param sr    sampleRate of the frame
     * @param scf_q Quantized scale factors
     * @param inv   True on inverse shaping, False otherwise
     * @param x     Spectral coefficients
     * @param y     Return shapped coefficients
     */
    private static void spectral_shaping(Duration dt, SRate sr, float[] scf_q, boolean inv, float[] x, int xp, float[] y, int yp) {
        // Interpolate scale factors

        float[] scf = new float[LC3_MAX_BANDS];
        float s0 = inv ? -scf_q[0] : scf_q[0];
        float s1 = s0;

        scf[0] = scf[1] = s1;
        for (int i = 0; i < 15; i++) {
            s0 = s1;
            s1 = inv ? -scf_q[i + 1] : scf_q[i + 1];
            scf[4 * i + 2] = s0 + 0.125f * (s1 - s0);
            scf[4 * i + 3] = s0 + 0.375f * (s1 - s0);
            scf[4 * i + 4] = s0 + 0.625f * (s1 - s0);
            scf[4 * i + 5] = s0 + 0.875f * (s1 - s0);
        }
        scf[62] = s1 + 0.125f * (s1 - s0);
        scf[63] = s1 + 0.375f * (s1 - s0);

        int nb = lc3_num_bands.get(dt)[sr.ordinal()];
        int n4 = nb < 32 ? 32 % nb : 0;
        int n2 = nb < 32 ? nb - n4 : LC3_MAX_BANDS - nb;

        for (int i4 = 0; i4 < n4; i4++)
            scf[i4] = 0.25f * (scf[4 * i4 + 0] + scf[4 * i4 + 1] + scf[4 * i4 + 2] + scf[4 * i4 + 3]);

        for (int i2 = n4; i2 < n4 + n2; i2++)
            scf[i2] = 0.5f * (scf[2 * (n4 + i2)] + scf[2 * (n4 + i2) + 1]);

        System.arraycopy(scf, 4 * n4 + 2 * n2, scf, n4 + n2, nb - n4 - n2);
        Arrays.fill(scf, 4 * n4 + 2 * n2, nb - n4 - n2, 0);

        // Spectral shaping

        final int[] lim = lc3_band_lim.get(dt)[sr.ordinal()];

        for (int i = 0, ib = 0; ib < nb; ib++) {
            float g_sns = (float) Math.exp(-scf[ib]);

            for (; i < lim[ib + 1]; i++)
                y[yp + i] = x[xp + i] * g_sns;
        }
    }

    private int lfcb, hfcb;
    private int shape, gain;
    private int idx_a, idx_b;
    private boolean ls_a, ls_b;

    //
    // Encoding
    //

    /**
     * SNS analysis
     * <p>
     * `x` and `y` can be the same buffer
     *
     * @param dt     Duration of the frame
     * @param sr     sampleRate of the frame
     * @param nBytes Size in bytes of the frame
     * @param eb     Energy estimation per bands, and count of bands
     * @param att    1: Attack detected  0: Otherwise
     * @param x      Spectral coefficients
     * @param y      Return shapped coefficients
     */
    void lc3_sns_analyze(Duration dt, SRate sr, int nBytes, float[] eb, boolean att, float[] x, int xp, float[] y, int yp) {

        // Processing steps :
        // - Determine 16 scale factors from bands energy estimation
        // - Get codebooks indexes that match thoses scale factors
        // - Quantize the residual with the selected codebook
        // - The pulse configuration `c[]` is enumerated
        // - Finally shape the spectrum coefficients accordingly */

        float[] scf = new float[16];
        float[][] cn = new float[4][16];
        int[][] c = new int[4][16];

        compute_scale_factors(dt, sr, nBytes, eb, att, scf);

        resolve_codebooks(scf);

        quantize(scf, this.lfcb, this.hfcb, c, cn);

        unquantize(this.lfcb, this.hfcb, cn[this.shape], this.shape, this.gain, scf);

        enumerate(this.shape, c[this.shape], this);

        spectral_shaping(dt, sr, scf, false, x, xp, y, yp);
    }

    /**
     * Return number of bits coding the bitstream data
     *
     * @return Bit consumption
     */
    static int lc3_sns_get_nbits() {
        return 38;
    }

    /**
     * Put bitstream data
     *
     * @param bits Bitstream context
     */
    void lc3_sns_put_data(Bits bits) {

        // Codebooks

        bits.lc3_put_bits(this.lfcb, 5);
        bits.lc3_put_bits(this.hfcb, 5);

        // Shape, gain and vectors
        // Write MSB bit of shape index, next LSB bits of shape and gain,
        // and MVPQ vectors indexes are muxed */

        int shape_msb = this.shape >> 1;
        bits.lc3_put_bit(shape_msb);

        if (shape_msb == 0) {
            final int size_a = 2390004;
            int submode = this.shape & 1;

            int mux_high = submode == 0 ? 2 * (this.idx_b + 1) + (this.ls_b ? 1 : 0) : this.gain & 1;
            int mux_code = mux_high * size_a + this.idx_a;

            bits.lc3_put_bits(this.gain >> submode, 1);
            bits.lc3_put_bits(this.ls_a ? 1 : 0, 1);
            bits.lc3_put_bits(mux_code, 25);

        } else {
            final int size_a = 15158272;
            int submode = this.shape & 1;

            int mux_code = submode == 0 ? this.idx_a : size_a + 2 * this.idx_a + (this.gain & 1);

            bits.lc3_put_bits(this.gain >> submode, 2);
            bits.lc3_put_bits(this.ls_a ? 1 : 0, 1);
            bits.lc3_put_bits(mux_code, 24);
        }
    }

    //
    // Decoding
    //

    /**
     * Get bitstream data
     *
     * @param bits Bitstream context
     */
    Sns(Bits bits) { // lc3_sns_get_data

        // Codebooks

        this.lfcb = bits.lc3_get_bits(5);
        this.hfcb = bits.lc3_get_bits(5);

        // Shape, gain and vectors

        int shape_msb = bits.lc3_get_bit();
        this.gain = bits.lc3_get_bits(1 + shape_msb);
        this.ls_a = bits.lc3_get_bit() != 0;

        int muxCode = bits.lc3_get_bits(25 - shape_msb);

        if (shape_msb == 0) {
            final int size_a = 2390004;

            if (muxCode >= size_a * 14) throw new IllegalStateException("when size_a = 2390004");

            this.idx_a = muxCode % size_a;
            muxCode = muxCode / size_a;

            this.shape = (muxCode < 2) ? 1 : 0;

            if (this.shape == 0) {
                this.idx_b = (muxCode - 2) / 2;
                this.ls_b = ((muxCode - 2) % 2) != 0;
            } else {
                this.gain = (this.gain << 1) + (muxCode % 2);
            }

        } else {
            final int size_a = 15158272;

            if (muxCode >= size_a + 1549824) throw new IllegalStateException("when size_a = 15158272");

            this.shape = 2 + ((muxCode >= size_a) ? 1 : 0);
            if (this.shape == 2) {
                this.idx_a = muxCode;
            } else {
                muxCode -= size_a;
                this.idx_a = muxCode / 2;
                this.gain = (this.gain << 1) + (muxCode % 2);
            }
        }
    }

    /**
     * SNS synthesis
     * <p>
     * `x` and `y` can be the same buffer
     *
     * @param dt   Duration and sampleRate of the frame
     * @param sr   Duration and sampleRate of the frame
     * @param x    Spectral coefficients
     * @param y    Return shapped coefficients
     */
    void lc3_sns_synthesize(Duration dt, SRate sr, float[] x, int xp, float[] y, int yp) {
        float[] scf = new float[16], cn = new float[16];
        int[] c = new int[16];

        deenumerate(this.shape, this.idx_a, this.ls_a, this.idx_b, this.ls_b, c);

        normalize(c, cn);

        unquantize(this.lfcb, this.hfcb, cn, this.shape, this.gain, scf);

        spectral_shaping(dt, sr, scf, true, x, xp, y, yp);
    }

    private static final float[][] lc3_sns_lfcb = {

            {2.26283366e+00f, 8.13311269e-01f, -5.30193495e-01f, -1.35664836e+00f,
                    -1.59952177e+00f, -1.44098768e+00f, -1.14381648e+00f, -7.55203768e-01f},

            {2.94516479e+00f, 2.41143318e+00f, 9.60455106e-01f, -4.43226488e-01f,
                    -1.22913612e+00f, -1.55590039e+00f, -1.49688656e+00f, -1.11689987e+00f},

            {-2.18610707e+00f, -1.97152136e+00f, -1.78718620e+00f, -1.91865896e+00f,
                    -1.79399122e+00f, -1.35738404e+00f, -7.05444279e-01f, -4.78172945e-02f},

            {6.93688237e-01f, 9.55609857e-01f, 5.75230787e-01f, -1.14603419e-01f,
                    -6.46050637e-01f, -9.52351370e-01f, -1.07405247e+00f, -7.58087707e-01f},

            {-1.29752132e+00f, -7.40369057e-01f, -3.45372484e-01f, -3.13285696e-01f,
                    -4.02977243e-01f, -3.72020853e-01f, -7.83414177e-02f, 9.70441304e-02f},

            {9.14652038e-01f, 1.74293043e+00f, 1.90906627e+00f, 1.54408484e+00f,
                    1.09344961e+00f, 6.47479550e-01f, 3.61790752e-02f, -2.97092807e-01f},

            {-2.51428813e+00f, -2.89175271e+00f, -2.00450667e+00f, -7.50912274e-01f,
                    4.41202105e-01f, 1.20190988e+00f, 1.32742857e+00f, 1.22049081e+00f},

            {-9.22188405e-01f, 6.32495141e-01f, 1.08736431e+00f, 6.08628625e-01f,
                    1.31174568e-01f, -2.96149158e-01f, -2.07013517e-01f, 1.34924917e-01f},

            {7.90322288e-01f, 6.28401262e-01f, 3.93117924e-01f, 4.80007711e-01f,
                    4.47815138e-01f, 2.09734215e-01f, 6.56691996e-03f, -8.61242342e-02f},

            {1.44775580e+00f, 2.72399952e+00f, 2.31083269e+00f, 9.35051270e-01f,
                    -2.74743911e-01f, -9.02077697e-01f, -9.40681512e-01f, -6.33697039e-01f},

            {7.93354526e-01f, 1.43931186e-02f, -5.67834845e-01f, -6.54760468e-01f,
                    -4.79458998e-01f, -1.73894662e-01f, 6.80162706e-02f, 2.95125948e-01f},

            {2.72425347e+00f, 2.95947572e+00f, 1.84953559e+00f, 5.63284922e-01f,
                    1.39917088e-01f, 3.59641093e-01f, 6.89461355e-01f, 6.39790177e-01f},

            {-5.30830198e-01f, -2.12690683e-01f, 5.76613628e-03f, 4.24871484e-01f,
                    4.73128952e-01f, 8.58894199e-01f, 1.19111161e+00f, 9.96189670e-01f},

            {1.68728411e+00f, 2.43614509e+00f, 2.33019429e+00f, 1.77983778e+00f,
                    1.44411295e+00f, 1.51995177e+00f, 1.47199394e+00f, 9.77682474e-01f},

            {-2.95183273e+00f, -1.59393497e+00f, -1.09918773e-01f, 3.88609073e-01f,
                    5.12932650e-01f, 6.28112597e-01f, 8.22621796e-01f, 8.75891425e-01f},

            {1.01878343e-01f, 5.89857324e-01f, 6.19047647e-01f, 1.26731314e+00f,
                    2.41961048e+00f, 2.25174253e+00f, 5.26537031e-01f, -3.96591513e-01f},

            {2.68254575e+00f, 1.32738011e+00f, 1.30185274e-01f, -3.38533089e-01f,
                    -3.68219236e-01f, -1.91689947e-01f, -1.54782377e-01f, -2.34207178e-01f},

            {4.82697924e+00f, 3.11947804e+00f, 1.39513671e+00f, 2.50295316e-01f,
                    -3.93613839e-01f, -6.43458173e-01f, -6.42570737e-01f, -7.23193223e-01f},

            {8.78419936e-02f, -5.69586840e-01f, -1.14506016e+00f, -1.66968488e+00f,
                    -1.84534418e+00f, -1.56468027e+00f, -1.11746759e+00f, -5.33981663e-01f},

            {1.39102308e+00f, 1.98146479e+00f, 1.11265796e+00f, -2.20107509e-01f,
                    -7.74965612e-01f, -5.94063874e-01f, 1.36937681e-01f, 8.18242891e-01f},

            {3.84585894e-01f, -1.60588786e-01f, -5.39366810e-01f, -5.29309079e-01f,
                    1.90433547e-01f, 2.56062918e+00f, 2.81896398e+00f, 6.56670876e-01f},

            {1.93227399e+00f, 3.01030180e+00f, 3.06543894e+00f, 2.50110161e+00f,
                    1.93089593e+00f, 5.72153811e-01f, -8.11741794e-01f, -1.17641811e+00f},

            {1.75080463e-01f, -7.50522832e-01f, -1.03943893e+00f, -1.13577509e+00f,
                    -1.04197904e+00f, -1.52060099e-02f, 2.07048392e+00f, 3.42948918e+00f},

            {-1.18817020e+00f, 3.66792874e-01f, 1.30957830e+00f, 1.68330687e+00f,
                    1.25100924e+00f, 9.42375752e-01f, 8.26250483e-01f, 4.39952741e-01f},

            {2.53322203e+00f, 2.11274643e+00f, 1.26288412e+00f, 7.61513512e-01f,
                    5.22117938e-01f, 1.18680070e-01f, -4.52346828e-01f, -7.00352426e-01f},

            {3.99889837e+00f, 4.07901751e+00f, 2.82285661e+00f, 1.72607213e+00f,
                    6.47144377e-01f, -3.31148521e-01f, -8.84042571e-01f, -1.12697341e+00f},

            {5.07902593e-01f, 1.58838450e+00f, 1.72899024e+00f, 1.00692230e+00f,
                    3.77121232e-01f, 4.76370767e-01f, 1.08754740e+00f, 1.08756266e+00f},

            {3.16856825e+00f, 3.25853458e+00f, 2.42230591e+00f, 1.79446078e+00f,
                    1.52177911e+00f, 1.17196707e+00f, 4.89394597e-01f, -6.22795716e-02f},

            {1.89414767e+00f, 1.25108695e+00f, 5.90451211e-01f, 6.08358583e-01f,
                    8.78171010e-01f, 1.11912511e+00f, 1.01857662e+00f, 6.20453891e-01f},

            {9.48880605e-01f, 2.13239439e+00f, 2.72345350e+00f, 2.76986077e+00f,
                    2.54286973e+00f, 2.02046264e+00f, 8.30045859e-01f, -2.75569174e-02f},

            {-1.88026757e+00f, -1.26431073e+00f, 3.11424977e-01f, 1.83670210e+00f,
                    2.25634192e+00f, 2.04818998e+00f, 2.19526837e+00f, 2.02659614e+00f},

            {2.46375746e-01f, 9.55621773e-01f, 1.52046777e+00f, 1.97647400e+00f,
                    1.94043867e+00f, 2.23375847e+00f, 1.98835978e+00f, 1.27232673e+00f},

    };

    private static final float[][] lc3_sns_hfcb = {

            {2.32028419e-01f, -1.00890271e+00f, -2.14223503e+00f, -2.37533814e+00f,
                    -2.23041933e+00f, -2.17595881e+00f, -2.29065914e+00f, -2.53286398e+00f},

            {-1.29503937e+00f, -1.79929965e+00f, -1.88703148e+00f, -1.80991660e+00f,
                    -1.76340038e+00f, -1.83418428e+00f, -1.80480981e+00f, -1.73679545e+00f},

            {1.39285716e-01f, -2.58185126e-01f, -6.50804573e-01f, -1.06815732e+00f,
                    -1.61928742e+00f, -2.18762566e+00f, -2.63757587e+00f, -2.97897750e+00f},

            {-3.16513102e-01f, -4.77747657e-01f, -5.51162076e-01f, -4.84788283e-01f,
                    -2.38388394e-01f, -1.43024507e-01f, 6.83186674e-02f, 8.83061717e-02f},

            {8.79518405e-01f, 2.98340096e-01f, -9.15386396e-01f, -2.20645975e+00f,
                    -2.74142181e+00f, -2.86139074e+00f, -2.88841597e+00f, -2.95182608e+00f},

            {-2.96701922e-01f, -9.75004919e-01f, -1.35857500e+00f, -9.83721106e-01f,
                    -6.52956939e-01f, -9.89986993e-01f, -1.61467225e+00f, -2.40712302e+00f},

            {3.40981100e-01f, 2.68899789e-01f, 5.63335685e-02f, 4.99114047e-02f,
                    -9.54130727e-02f, -7.60166146e-01f, -2.32758120e+00f, -3.77155485e+00f},

            {-1.41229759e+00f, -1.48522119e+00f, -1.18603580e+00f, -6.25001634e-01f,
                    1.53902497e-01f, 5.76386498e-01f, 7.95092604e-01f, 5.96564632e-01f},

            {-2.28839512e-01f, -3.33719070e-01f, -8.09321359e-01f, -1.63587877e+00f,
                    -1.88486397e+00f, -1.64496691e+00f, -1.40515778e+00f, -1.46666471e+00f},

            {-1.07148629e+00f, -1.41767015e+00f, -1.54891762e+00f, -1.45296062e+00f,
                    -1.03182970e+00f, -6.90642640e-01f, -4.28843805e-01f, -4.94960215e-01f},

            {-5.90988511e-01f, -7.11737759e-02f, 3.45719523e-01f, 3.00549461e-01f,
                    -1.11865218e+00f, -2.44089151e+00f, -2.22854732e+00f, -1.89509228e+00f},

            {-8.48434099e-01f, -5.83226811e-01f, 9.00423688e-02f, 8.45025008e-01f,
                    1.06572385e+00f, 7.37582999e-01f, 2.56590452e-01f, -4.91963360e-01f},

            {1.14069146e+00f, 9.64016892e-01f, 3.81461206e-01f, -4.82849341e-01f,
                    -1.81632721e+00f, -2.80279513e+00f, -3.23385725e+00f, -3.45908714e+00f},

            {-3.76283238e-01f, 4.25675462e-02f, 5.16547697e-01f, 2.51716882e-01f,
                    -2.16179968e-01f, -5.34074091e-01f, -6.40786096e-01f, -8.69745032e-01f},

            {6.65004121e-01f, 1.09790765e+00f, 1.38342667e+00f, 1.34327359e+00f,
                    8.22978837e-01f, 2.15876799e-01f, -4.04925753e-01f, -1.07025606e+00f},

            {-8.26265954e-01f, -6.71181233e-01f, -2.28495593e-01f, 5.18980853e-01f,
                    1.36721896e+00f, 2.18023038e+00f, 2.53596093e+00f, 2.20121099e+00f},

            {1.41008327e+00f, 7.54441908e-01f, -1.30550585e+00f, -1.87133711e+00f,
                    -1.24008685e+00f, -1.26712925e+00f, -2.03670813e+00f, -2.89685162e+00f},

            {3.61386818e-01f, -2.19991705e-02f, -5.79368834e-01f, -8.79427961e-01f,
                    -8.50685023e-01f, -7.79397050e-01f, -7.32182927e-01f, -8.88348515e-01f},

            {4.37469239e-01f, 3.05440420e-01f, -7.38786566e-03f, -4.95649855e-01f,
                    -8.06651271e-01f, -1.22431892e+00f, -1.70157770e+00f, -2.24491914e+00f},

            {6.48100319e-01f, 6.82299134e-01f, 2.53247464e-01f, 7.35842144e-02f,
                    3.14216709e-01f, 2.34729881e-01f, 1.44600134e-01f, -6.82120179e-02f},

            {1.11919833e+00f, 1.23465533e+00f, 5.89170238e-01f, -1.37192460e+00f,
                    -2.37095707e+00f, -2.00779783e+00f, -1.66688540e+00f, -1.92631846e+00f},

            {1.41847497e-01f, -1.10660071e-01f, -2.82824593e-01f, -6.59813475e-03f,
                    2.85929280e-01f, 4.60445530e-02f, -6.02596416e-01f, -2.26568729e+00f},

            {5.04046955e-01f, 8.26982163e-01f, 1.11981236e+00f, 1.17914044e+00f,
                    1.07987429e+00f, 6.97536239e-01f, -9.12548817e-01f, -3.57684747e+00f},

            {-5.01076050e-01f, -3.25678006e-01f, 2.80798195e-02f, 2.62054555e-01f,
                    3.60590806e-01f, 6.35623722e-01f, 9.59012467e-01f, 1.30745157e+00f},

            {3.74970983e+00f, 1.52342612e+00f, -4.57715662e-01f, -7.98711008e-01f,
                    -3.86819329e-01f, -3.75901062e-01f, -6.57836900e-01f, -1.28163964e+00f},

            {-1.15258991e+00f, -1.10800886e+00f, -5.62615117e-01f, -2.20562124e-01f,
                    -3.49842880e-01f, -7.53432770e-01f, -9.88596593e-01f, -1.28790472e+00f},

            {1.02827246e+00f, 1.09770519e+00f, 7.68645546e-01f, 2.06081978e-01f,
                    -3.42805735e-01f, -7.54939405e-01f, -1.04196178e+00f, -1.50335653e+00f},

            {1.28831972e-01f, 6.89439395e-01f, 1.12346905e+00f, 1.30934523e+00f,
                    1.35511965e+00f, 1.42311381e+00f, 1.15706449e+00f, 4.06319438e-01f},

            {1.34033030e+00f, 1.38996825e+00f, 1.04467922e+00f, 6.35822746e-01f,
                    -2.74733756e-01f, -1.54923372e+00f, -2.44239710e+00f, -3.02457607e+00f},

            {2.13843105e+00f, 4.24711267e+00f, 2.89734110e+00f, 9.32730658e-01f,
                    -2.92822250e-01f, -8.10404297e-01f, -7.88868099e-01f, -9.35353149e-01f},

            {5.64830487e-01f, 1.59184978e+00f, 2.39771699e+00f, 3.03697344e+00f,
                    2.66424350e+00f, 1.39304485e+00f, 4.03834024e-01f, -6.56270971e-01f},

            {-4.22460548e-01f, 3.26149625e-01f, 1.39171313e+00f, 2.23146615e+00f,
                    2.61179442e+00f, 2.66540340e+00f, 2.40103554e+00f, 1.75920380e+00f},
    };

    private static class lc3_sns_vq_gains {

        int count;
        float[] v;

        public lc3_sns_vq_gains(int count, float[] v) {
            this.count = count;
            this.v = v;
        }
    }

    private static final lc3_sns_vq_gains[] _lc3_sns_vq_gains = {

            new lc3_sns_vq_gains(2, new float[] {
                    8915.f / 4096, 12054.f / 4096}),

            new lc3_sns_vq_gains(4, new float[] {
                    6245.f / 4096, 15043.f / 4096, 17861.f / 4096, 21014.f / 4096}),

            new lc3_sns_vq_gains(4, new float[] {
                    7099.f / 4096, 9132.f / 4096, 11253.f / 4096, 14808.f / 4096}),

            new lc3_sns_vq_gains(8, new float[] {
                    4336.f / 4096, 5067.f / 4096, 5895.f / 4096, 8149.f / 4096,
                    10235.f / 4096, 12825.f / 4096, 16868.f / 4096, 19882.f / 4096})
    };

    private static final int[][] lc3_sns_mpvq_offsets = {
            {0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1},
            {0, 1, 3, 5, 7, 9, 11, 13, 15, 17, 19},
            {0, 1, 5, 13, 25, 41, 61, 85, 113, 145, 181},
            {0, 1, 7, 25, 63, 129, 231, 377, 575, 833, 1159},
            {0, 1, 9, 41, 129, 321, 681, 1289, 2241, 3649, 5641},
            {0, 1, 11, 61, 231, 681, 1683, 3653, 7183, 13073, 22363},
            {0, 1, 13, 85, 377, 1289, 3653, 8989, 19825, 40081, 75517},
            {0, 1, 15, 113, 575, 2241, 7183, 19825, 48639, 108545, 224143},
            {0, 1, 17, 145, 833, 3649, 13073, 40081, 108545, 265729, 598417},
            {0, 1, 19, 181, 1159, 5641, 22363, 75517, 224143, 598417, 1462563},
            {0, 1, 21, 221, 1561, 8361, 36365, 134245, 433905, 1256465, 3317445},
            {0, 1, 23, 265, 2047, 11969, 56695, 227305, 795455, 2485825, 7059735},
            {0, 1, 25, 313, 2625, 16641, 85305, 369305, 1392065, 4673345, 14218905},
            {0, 1, 27, 365, 3303, 22569, 124515, 579125, 2340495, 8405905, 27298155},
            {0, 1, 29, 421, 4089, 29961, 177045, 880685, 3800305, 14546705, 50250765},
            {0, 1, 31, 481, 4991, 39041, 246047, 1303777, 5984767, 24331777, 89129247},
    };
}