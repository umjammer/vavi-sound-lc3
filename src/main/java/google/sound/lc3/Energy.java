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

import google.sound.lc3.Common.Duration;
import google.sound.lc3.Common.SRate;

import static google.sound.lc3.Common.Duration._10M;
import static google.sound.lc3.Common.Duration._2M5;
import static google.sound.lc3.Common.Duration._5M;
import static google.sound.lc3.Common.Duration._7M5;


class Energy {

    private static final Map<Duration, Integer> map = Map.of(
            Duration._2M5, 2,
            Duration._5M, 3,
            Duration._7M5, 4,
            Duration._10M, 2
    );

    /**
     * Energy estimation per band
     *
     * @param dt Duration of the frame
     * @param sr sampleRate of the frame
     * @param x  Input MDCT coefficient
     * @param e  Energy estimation per bands
     * @return True when high energy detected near Nyquist frequency
     */
    boolean lc3_energy_compute(Duration dt, SRate sr, final float[] x, float[] e) {
        int nb = lc3_num_bands.get(dt)[sr.ordinal()];
        int[] lim = lc3_band_lim.get(dt)[sr.ordinal()];

        // Mean the square of coefficients within each band

        float[] e_sum = new float[] {0, 0};
        int iband_h = nb - map.get(dt);

        int eP = 0; // e
        for (int iband = 0, i = lim[iband]; iband < nb; iband++) {
            int ie = lim[iband + 1];
            int n = ie - i;

            float sx2 = x[i] * x[i];
            for (i++; i < ie; i++)
                sx2 += x[i] * x[i];

            e[eP] = sx2 / n;
            e_sum[iband >= iband_h ? 1 : 0] += e[eP++];
        }

        // Return the near nyquist flag

        return e_sum[1] > 30 * e_sum[0];
    }

    /**
     * Limits of bands
     */

    private static final int[] band_lim_2m5_8k = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20
    };

    private static final int[] band_lim_2m5_16k = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
            30, 32, 34, 36, 38, 40
    };

    private static final int[] band_lim_2m5_24k = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 22, 23, 24, 25, 26, 28, 30, 32,
            34, 36, 38, 40, 42, 44, 47, 50, 53, 56,
            60
    };

    private static final int[] band_lim_2m5_32k = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 22, 23, 24, 26, 28, 30, 32, 34,
            36, 38, 40, 43, 46, 49, 52, 55, 59, 63,
            67, 71, 75, 80
    };

    private static final int[] band_lim_2m5_48k = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 23, 25, 27, 29, 31, 33, 35, 37,
            40, 43, 46, 49, 52, 56, 60, 64, 68, 72,
            77, 82, 87, 93, 100
    };

    private static final int[] band_lim_2m5_48k_hr = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            21, 23, 25, 27, 29, 31, 33, 35, 37, 40,
            43, 46, 49, 53, 57, 61, 65, 69, 74, 79,
            85, 91, 97, 104, 112, 120
    };

    private static final int[] band_lim_2m5_96k_hr = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 18, 20, 22,
            24, 26, 28, 30, 32, 35, 38, 41, 45, 49,
            53, 57, 62, 67, 73, 79, 85, 92, 100, 108,
            117, 127, 137, 149, 161, 174, 189, 204, 221, 240
    };

    private static final int[] band_lim_5m_8k = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
            30, 31, 32, 33, 34, 35, 36, 37, 38, 40
    };

    private static final int[] band_lim_5m_16k = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
            30, 32, 34, 36, 38, 40, 42, 44, 46, 48,
            50, 52, 54, 57, 60, 63, 66, 69, 72, 76,
            80
    };

    private static final int[] band_lim_5m_24k = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 22, 23, 24, 26, 28, 30, 32, 34,
            36, 38, 40, 42, 44, 47, 50, 53, 56, 59,
            62, 65, 69, 73, 77, 81, 86, 91, 96, 101,
            107, 113, 120
    };

    private static final int[] band_lim_5m_32k = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 22, 24, 26, 28, 30, 32, 34, 36,
            38, 40, 42, 45, 48, 51, 54, 57, 61, 65,
            69, 73, 78, 83, 88, 93, 99, 105, 112, 119,
            126, 134, 142, 151, 160
    };

    private static final int[] band_lim_5m_48k = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 23, 25, 27, 29, 31, 33, 35, 37,
            40, 43, 46, 49, 52, 55, 59, 63, 67, 72,
            77, 82, 87, 93, 99, 105, 112, 120, 128, 136,
            145, 155, 165, 176, 187, 200
    };

    private static final int[] band_lim_5m_48k_hr = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            21, 23, 25, 27, 29, 31, 33, 35, 38, 41,
            44, 47, 50, 54, 58, 62, 66, 71, 76, 81,
            87, 93, 100, 107, 114, 122, 131, 140, 149, 160,
            171, 183, 196, 209, 224, 240
    };

    private static final int[] band_lim_5m_96k_hr = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 17, 19, 21,
            23, 25, 27, 29, 31, 34, 37, 40, 44, 48,
            52, 56, 61, 66, 71, 77, 83, 90, 98, 106,
            115, 124, 135, 146, 158, 171, 185, 200, 217, 235,
            254, 275, 298, 323, 349, 378, 409, 443, 480
    };

    private static final int[] band_lim_7m5_8k = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
            30, 31, 32, 33, 34, 35, 36, 37, 38, 39,
            40, 41, 42, 43, 44, 45, 46, 47, 48, 49,
            50, 51, 52, 53, 54, 55, 56, 57, 58, 59,
            60
    };

    private static final int[] band_lim_7m5_16k = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
            30, 31, 32, 33, 34, 36, 38, 40, 42, 44,
            46, 48, 50, 52, 54, 56, 58, 60, 62, 65,
            68, 71, 74, 77, 80, 83, 86, 90, 94, 98,
            102, 106, 110, 115, 120
    };

    private static final int[] band_lim_7m5_24k = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 22, 23, 24, 25, 26, 27, 29, 31,
            33, 35, 37, 39, 41, 43, 45, 47, 49, 52,
            55, 58, 61, 64, 67, 70, 74, 78, 82, 86,
            90, 95, 100, 105, 110, 115, 121, 127, 134, 141,
            148, 155, 163, 171, 180
    };

    private static final int[] band_lim_7m5_32k = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 22, 23, 24, 26, 28, 30, 32, 34,
            36, 38, 40, 42, 45, 48, 51, 54, 57, 60,
            63, 67, 71, 75, 79, 84, 89, 94, 99, 105,
            111, 117, 124, 131, 138, 146, 154, 163, 172, 182,
            192, 203, 215, 227, 240
    };

    private static final int[] band_lim_7m5_48k = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 22, 24, 26, 28, 30, 32, 34, 36,
            38, 40, 43, 46, 49, 52, 55, 59, 63, 67,
            71, 75, 80, 85, 90, 96, 102, 108, 115, 122,
            129, 137, 146, 155, 165, 175, 186, 197, 209, 222,
            236, 251, 266, 283, 300
    };

    private static final int[] band_lim_10m_8k = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 22, 23, 24, 25, 26, 27, 28, 29,
            30, 31, 32, 33, 34, 35, 36, 37, 38, 39,
            40, 41, 42, 43, 44, 45, 46, 47, 48, 49,
            51, 53, 55, 57, 59, 61, 63, 65, 67, 69,
            71, 73, 75, 77, 80
    };

    private static final int[] band_lim_10m_16k = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 22, 23, 24, 25, 26, 27, 28, 30,
            32, 34, 36, 38, 40, 42, 44, 46, 48, 50,
            52, 55, 58, 61, 64, 67, 70, 73, 76, 80,
            84, 88, 92, 96, 101, 106, 111, 116, 121, 127,
            133, 139, 146, 153, 160
    };

    private static final int[] band_lim_10m_24k = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 22, 23, 25, 27, 29, 31, 33, 35,
            37, 39, 41, 43, 46, 49, 52, 55, 58, 61,
            64, 68, 72, 76, 80, 85, 90, 95, 100, 106,
            112, 118, 125, 132, 139, 147, 155, 164, 173, 183,
            193, 204, 215, 227, 240
    };

    private static final int[] band_lim_10m_32k = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 22, 24, 26, 28, 30, 32, 34, 36, 38,
            41, 44, 47, 50, 53, 56, 60, 64, 68, 72,
            76, 81, 86, 91, 97, 103, 109, 116, 123, 131,
            139, 148, 157, 166, 176, 187, 199, 211, 224, 238,
            252, 268, 284, 302, 320
    };

    private static final int[] band_lim_10m_48k = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 17, 18, 20,
            22, 24, 26, 28, 30, 32, 34, 36, 39, 42,
            45, 48, 51, 55, 59, 63, 67, 71, 76, 81,
            86, 92, 98, 105, 112, 119, 127, 135, 144, 154,
            164, 175, 186, 198, 211, 225, 240, 256, 273, 291,
            310, 330, 352, 375, 400
    };

    static final int[] band_lim_10m_48k_hr = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 13, 14, 15, 16, 17, 19, 21,
            23, 25, 27, 29, 31, 33, 36, 39, 42, 45,
            48, 51, 55, 59, 63, 67, 72, 77, 83, 89,
            95, 101, 108, 116, 124, 133, 142, 152, 163, 174,
            187, 200, 214, 229, 244, 262, 280, 299, 320, 343,
            367, 392, 419, 449, 480
    };

    private static final int[] band_lim_10m_96k_hr = {
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
            10, 11, 12, 14, 16, 18, 20, 22, 24, 26,
            28, 30, 33, 36, 39, 42, 46, 50, 54, 59,
            64, 69, 75, 82, 89, 96, 104, 113, 122, 132,
            143, 155, 168, 181, 196, 213, 230, 249, 270, 292,
            316, 342, 371, 401, 434, 470, 509, 551, 596, 646,
            699, 757, 819, 887, 960
    };

    static final int LC3_MAX_BANDS = 64;

    static Map<Duration, int[]> lc3_num_bands = Map.of(/* NUM_DURATION. NUM_SRATE */

            _2M5, new int[] {
                    band_lim_2m5_8k.length - 1,
                    band_lim_2m5_16k.length - 1,
                    band_lim_2m5_24k.length - 1,
                    band_lim_2m5_32k.length - 1,
                    band_lim_2m5_48k.length - 1,
                    band_lim_2m5_48k_hr.length - 1,
                    band_lim_2m5_96k_hr.length - 1
            },
            _5M, new int[] {
                    band_lim_5m_8k.length - 1,
                    band_lim_5m_16k.length - 1,
                    band_lim_5m_24k.length - 1,
                    band_lim_5m_32k.length - 1,
                    band_lim_5m_48k.length - 1,
                    band_lim_5m_48k_hr.length - 1,
                    band_lim_5m_96k_hr.length - 1
            },
            _7M5, new int[] {
                    band_lim_7m5_8k.length - 1,
                    band_lim_7m5_16k.length - 1,
                    band_lim_7m5_24k.length - 1,
                    band_lim_7m5_32k.length - 1,
                    band_lim_7m5_48k.length - 1
            },
            _10M, new int[] {
                    band_lim_10m_8k.length - 1,
                    band_lim_10m_16k.length - 1,
                    band_lim_10m_24k.length - 1,
                    band_lim_10m_32k.length - 1,
                    band_lim_10m_48k.length - 1,
                    band_lim_10m_48k_hr.length - 1,
                    band_lim_10m_96k_hr.length - 1
            }
    );

    static final Map<Duration, int[][]> lc3_band_lim = Map.of( /* NUM_DURATION, NUM_SRATE */

            _2M5, new int[][] {
                    band_lim_2m5_8k,
                    band_lim_2m5_16k,
                    band_lim_2m5_24k,
                    band_lim_2m5_32k,
                    band_lim_2m5_48k,
                    band_lim_2m5_48k_hr,
                    band_lim_2m5_96k_hr
            },
            _5M, new int[][] {
                    band_lim_5m_8k,
                    band_lim_5m_16k,
                    band_lim_5m_24k,
                    band_lim_5m_32k,
                    band_lim_5m_48k,
                    band_lim_5m_48k_hr,
                    band_lim_5m_96k_hr
            },
            _7M5, new int[][] {
                    band_lim_7m5_8k, band_lim_7m5_16k, band_lim_7m5_24k,
                    band_lim_7m5_32k, band_lim_7m5_48k
            },
            _10M, new int[][] {
                    band_lim_10m_8k, band_lim_10m_16k, band_lim_10m_24k,
                    band_lim_10m_32k, band_lim_10m_48k,
                    band_lim_10m_48k_hr,
                    band_lim_10m_96k_hr
            }
    );
}