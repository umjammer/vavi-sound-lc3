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

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.stream.IntStream;

import google.sound.lc3.Common.Complex;
import google.sound.lc3.Common.Duration;
import google.sound.lc3.Common.SRate;

import static google.sound.lc3.Common.Duration._10M;
import static google.sound.lc3.Common.Duration._2M5;
import static google.sound.lc3.Common.Duration._5M;
import static google.sound.lc3.Common.Duration._7M5;
import static google.sound.lc3.Common.LC3_NS;
import static google.sound.lc3.Common.LC3_PLUS_HR;
import static google.sound.lc3.Common.SRate._16K;
import static google.sound.lc3.Common.SRate._24K;
import static google.sound.lc3.Common.SRate._32K;
import static google.sound.lc3.Common.SRate._48K;
import static google.sound.lc3.Common.SRate._48K_HR;
import static google.sound.lc3.Common.SRate._8K;
import static google.sound.lc3.Common.SRate._96K_HR;
import static google.sound.lc3.Common.isHR;
import static google.sound.lc3.Lc3.LC3_MAX_FRAME_BYTES;
import static google.sound.lc3.Lc3.LC3_MIN_FRAME_BYTES;


/**
 * Characteristics
 * <p>
 * ns   Number of temporal samples / frequency coefficients within a frame
 * <p>
 * ne   Number of encoded frequency coefficients
 * <p>
 * nd   Number of MDCT delayed samples, sum of half a frame and an ovelap
 * of future by 1.25 ms (2.5ms, 5ms and 10ms frame durations),
 * or 2 ms (7.5ms frame duration).
 * <p>
 * nh   Number of 18 ms samples of the history buffer, aligned on a frame
 * <p>
 * nt   Number of 1.25 ms previous samples
 */
class Tables {

    private static final Map<SRate, Integer> lc3_ns_2m5 = Map.of(
            _8K, LC3_NS(2500, 8000),
            _16K, LC3_NS(2500, 16000),
            _24K, LC3_NS(2500, 24000),
            _32K, LC3_NS(2500, 32000),
            _48K, LC3_NS(2500, 48000),
            _48K_HR, LC3_NS(2500, 48000),
            _96K_HR, LC3_NS(2500, 96000)
    );

    private static final Map<SRate, Integer> lc3_ne_2m5 = Map.of(
            _8K, LC3_NS(2500, 8000),
            _16K, LC3_NS(2500, 16000),
            _24K, LC3_NS(2500, 24000),
            _32K, LC3_NS(2500, 32000),
            _48K, LC3_NS(2500, 40000),
            _48K_HR, LC3_NS(2500, 48000),
            _96K_HR, LC3_NS(2500, 96000)
    );

    static final Map<SRate, Integer> lc3_ns_4m = Map.of(
            _8K, LC3_NS(4000, 8000),
            _16K, LC3_NS(4000, 16000),
            _24K, LC3_NS(4000, 24000),
            _32K, LC3_NS(4000, 32000),
            _48K, LC3_NS(4000, 48000),
            _48K_HR, LC3_NS(4000, 48000),
            _96K_HR, LC3_NS(4000, 96000)
    );

    static int lc3_ns(Duration dt, SRate sr) {
        return lc3_ns_2m5.get(sr) * (1 + dt.ordinal());
    }

    static int lc3_ne(Duration dt, SRate sr) {
        return lc3_ne_2m5.get(sr) * (1 + dt.ordinal());
    }

    static int lc3_nd(Duration dt, SRate sr) {
        return (lc3_ns(dt, sr) + (dt == _7M5 ? lc3_ns_4m.get(sr) : lc3_ns_2m5.get(sr))) >> 1;
    }

    static int lc3_nh(Duration dt, SRate sr) {
        return sr.ordinal() > _48K_HR.ordinal() ? 0 : (8 + (dt == _7M5 ? 1 : 0)) * lc3_ns_2m5.get(sr);
    }

    static int lc3_nt(SRate sr) {
        return lc3_ns_2m5.get(sr) >> 1;
    }

    static final int LC3_MAX_SRATE_HZ = LC3_PLUS_HR ? 96000 : 48000;

    static final int LC3_MAX_NS = LC3_NS(10000, LC3_MAX_SRATE_HZ);
    static final int LC3_MAX_NE = LC3_PLUS_HR ? LC3_MAX_NS : LC3_NS(10000, 40000);

    /*
     * Limits on size of frame
     */

    /**
     * Limits on size of frame
     * For fallback operation, half-size should be accepted.
     */
    private static final Map<Duration, Map<Integer, int[]>> lc3_frame_bytes_hr_lim = Map.of(
            // NUM_DURATION, NUM_SRATE - _48K_HR
            _2M5, Map.of(
                    _48K_HR.ordinal() - _48K_HR.ordinal(), new int[] {54 / 2, 210},
                    _96K_HR.ordinal() - _48K_HR.ordinal(), new int[] {62 / 2, 210}
            ),
            _5M, Map.of(
                    _48K_HR.ordinal() - _48K_HR.ordinal(), new int[] {93 / 2, 375},
                    _96K_HR.ordinal() - _48K_HR.ordinal(), new int[] {109 / 2, 375}
            ),
            _10M, Map.of(
                    _48K_HR.ordinal() - _48K_HR.ordinal(), new int[] {156 / 2, 625},
                    _96K_HR.ordinal() - _48K_HR.ordinal(), new int[] {187 / 2, 625}
            )
    );

    private int lc3_min_frame_bytes(Duration dt, SRate sr) {
        return !isHR(sr) ? LC3_MIN_FRAME_BYTES :
                lc3_frame_bytes_hr_lim.get(dt).get(sr.ordinal() - _48K_HR.ordinal())[0];
    }

    private int lc3_max_frame_bytes(Duration dt, SRate sr) {
        return !isHR(sr) ? LC3_MAX_FRAME_BYTES :
                lc3_frame_bytes_hr_lim.get(dt).get(sr.ordinal() - _48K_HR.ordinal())[1];
    }

    /**
     * MDCT Twiddles and window coefficients
     */

    static class lc3_fft_bf3_twiddles {

        int n3;
        Complex[][] t; // = new Complex[][2]

        public lc3_fft_bf3_twiddles(int n3, float[][][] f) {
            this.n3 = n3;
            this.t = new Complex[f.length][];
            IntStream.range(0, f.length).forEach(i -> {
                this.t[i] = new Complex[] {
                        new Complex(f[i][0][0], f[i][0][0]),
                        new Complex(f[i][1][0], f[i][1][0]),
                };
            });
        }
    }

    static class lc3_fft_bf2_twiddles {

        int n2;
        Complex[] t;

        public lc3_fft_bf2_twiddles(int n2, String f) {
            this.n2 = n2;
            List<Complex> l = new ArrayList<>();
            Scanner s = new Scanner(Tables.class.getResourceAsStream(f + ".txt"));
            s.useDelimiter("[\s,]");
            while (s.hasNextFloat()) {
                l.add(new Complex(s.nextFloat(), s.nextFloat()));
            }
            this.t = l.toArray(Complex[]::new);
        }
    }

    static class lc3_mdct_rot_def {

        int n4;
        Complex[] w;

        public lc3_mdct_rot_def(int n4, String f) {
            this.n4 = n4;
            List<Complex> l = new ArrayList<>();
            Scanner s = new Scanner(Tables.class.getResourceAsStream(f + ".txt"));
            s.useDelimiter("[\s,]");
            while (s.hasNextFloat()) {
                l.add(new Complex(s.nextFloat(), s.nextFloat()));
            }
            this.w = l.toArray(Complex[]::new);
        }
    }

    private static final Tables.lc3_fft_bf3_twiddles fft_twiddles_15 = new Tables.lc3_fft_bf3_twiddles(
            15 / 3, new float[][][] {
            {{1.0000000e+0f, -0.0000000e+0f}, {1.0000000e+0f, -0.0000000e+0f}},
            {{9.1354546e-1f, -4.0673664e-1f}, {6.6913061e-1f, -7.4314483e-1f}},
            {{6.6913061e-1f, -7.4314483e-1f}, {-1.0452846e-1f, -9.9452190e-1f}},
            {{3.0901699e-1f, -9.5105652e-1f}, {-8.0901699e-1f, -5.8778525e-1f}},
            {{-1.0452846e-1f, -9.9452190e-1f}, {-9.7814760e-1f, 2.0791169e-1f}},
            {{-5.0000000e-1f, -8.6602540e-1f}, {-5.0000000e-1f, 8.6602540e-1f}},
            {{-8.0901699e-1f, -5.8778525e-1f}, {3.0901699e-1f, 9.5105652e-1f}},
            {{-9.7814760e-1f, -2.0791169e-1f}, {9.1354546e-1f, 4.0673664e-1f}},
            {{-9.7814760e-1f, 2.0791169e-1f}, {9.1354546e-1f, -4.0673664e-1f}},
            {{-8.0901699e-1f, 5.8778525e-1f}, {3.0901699e-1f, -9.5105652e-1f}},
            {{-5.0000000e-1f, 8.6602540e-1f}, {-5.0000000e-1f, -8.6602540e-1f}},
            {{-1.0452846e-1f, 9.9452190e-1f}, {-9.7814760e-1f, -2.0791169e-1f}},
            {{3.0901699e-1f, 9.5105652e-1f}, {-8.0901699e-1f, 5.8778525e-1f}},
            {{6.6913061e-1f, 7.4314483e-1f}, {-1.0452846e-1f, 9.9452190e-1f}},
            {{9.1354546e-1f, 4.0673664e-1f}, {6.6913061e-1f, 7.4314483e-1f}},
    });

    private static final lc3_fft_bf3_twiddles fft_twiddles_45 = new lc3_fft_bf3_twiddles(
            45 / 3, new float[][][] {
            {{1.0000000e+0f, -0.0000000e+0f}, {1.0000000e+0f, -0.0000000e+0f}},
            {{9.9026807e-1f, -1.3917310e-1f}, {9.6126170e-1f, -2.7563736e-1f}},
            {{9.6126170e-1f, -2.7563736e-1f}, {8.4804810e-1f, -5.2991926e-1f}},
            {{9.1354546e-1f, -4.0673664e-1f}, {6.6913061e-1f, -7.4314483e-1f}},
            {{8.4804810e-1f, -5.2991926e-1f}, {4.3837115e-1f, -8.9879405e-1f}},
            {{7.6604444e-1f, -6.4278761e-1f}, {1.7364818e-1f, -9.8480775e-1f}},
            {{6.6913061e-1f, -7.4314483e-1f}, {-1.0452846e-1f, -9.9452190e-1f}},
            {{5.5919290e-1f, -8.2903757e-1f}, {-3.7460659e-1f, -9.2718385e-1f}},
            {{4.3837115e-1f, -8.9879405e-1f}, {-6.1566148e-1f, -7.8801075e-1f}},
            {{3.0901699e-1f, -9.5105652e-1f}, {-8.0901699e-1f, -5.8778525e-1f}},
            {{1.7364818e-1f, -9.8480775e-1f}, {-9.3969262e-1f, -3.4202014e-1f}},
            {{3.4899497e-2f, -9.9939083e-1f}, {-9.9756405e-1f, -6.9756474e-2f}},
            {{-1.0452846e-1f, -9.9452190e-1f}, {-9.7814760e-1f, 2.0791169e-1f}},
            {{-2.4192190e-1f, -9.7029573e-1f}, {-8.8294759e-1f, 4.6947156e-1f}},
            {{-3.7460659e-1f, -9.2718385e-1f}, {-7.1933980e-1f, 6.9465837e-1f}},
            {{-5.0000000e-1f, -8.6602540e-1f}, {-5.0000000e-1f, 8.6602540e-1f}},
            {{-6.1566148e-1f, -7.8801075e-1f}, {-2.4192190e-1f, 9.7029573e-1f}},
            {{-7.1933980e-1f, -6.9465837e-1f}, {3.4899497e-2f, 9.9939083e-1f}},
            {{-8.0901699e-1f, -5.8778525e-1f}, {3.0901699e-1f, 9.5105652e-1f}},
            {{-8.8294759e-1f, -4.6947156e-1f}, {5.5919290e-1f, 8.2903757e-1f}},
            {{-9.3969262e-1f, -3.4202014e-1f}, {7.6604444e-1f, 6.4278761e-1f}},
            {{-9.7814760e-1f, -2.0791169e-1f}, {9.1354546e-1f, 4.0673664e-1f}},
            {{-9.9756405e-1f, -6.9756474e-2f}, {9.9026807e-1f, 1.3917310e-1f}},
            {{-9.9756405e-1f, 6.9756474e-2f}, {9.9026807e-1f, -1.3917310e-1f}},
            {{-9.7814760e-1f, 2.0791169e-1f}, {9.1354546e-1f, -4.0673664e-1f}},
            {{-9.3969262e-1f, 3.4202014e-1f}, {7.6604444e-1f, -6.4278761e-1f}},
            {{-8.8294759e-1f, 4.6947156e-1f}, {5.5919290e-1f, -8.2903757e-1f}},
            {{-8.0901699e-1f, 5.8778525e-1f}, {3.0901699e-1f, -9.5105652e-1f}},
            {{-7.1933980e-1f, 6.9465837e-1f}, {3.4899497e-2f, -9.9939083e-1f}},
            {{-6.1566148e-1f, 7.8801075e-1f}, {-2.4192190e-1f, -9.7029573e-1f}},
            {{-5.0000000e-1f, 8.6602540e-1f}, {-5.0000000e-1f, -8.6602540e-1f}},
            {{-3.7460659e-1f, 9.2718385e-1f}, {-7.1933980e-1f, -6.9465837e-1f}},
            {{-2.4192190e-1f, 9.7029573e-1f}, {-8.8294759e-1f, -4.6947156e-1f}},
            {{-1.0452846e-1f, 9.9452190e-1f}, {-9.7814760e-1f, -2.0791169e-1f}},
            {{3.4899497e-2f, 9.9939083e-1f}, {-9.9756405e-1f, 6.9756474e-2f}},
            {{1.7364818e-1f, 9.8480775e-1f}, {-9.3969262e-1f, 3.4202014e-1f}},
            {{3.0901699e-1f, 9.5105652e-1f}, {-8.0901699e-1f, 5.8778525e-1f}},
            {{4.3837115e-1f, 8.9879405e-1f}, {-6.1566148e-1f, 7.8801075e-1f}},
            {{5.5919290e-1f, 8.2903757e-1f}, {-3.7460659e-1f, 9.2718385e-1f}},
            {{6.6913061e-1f, 7.4314483e-1f}, {-1.0452846e-1f, 9.9452190e-1f}},
            {{7.6604444e-1f, 6.4278761e-1f}, {1.7364818e-1f, 9.8480775e-1f}},
            {{8.4804810e-1f, 5.2991926e-1f}, {4.3837115e-1f, 8.9879405e-1f}},
            {{9.1354546e-1f, 4.0673664e-1f}, {6.6913061e-1f, 7.4314483e-1f}},
            {{9.6126170e-1f, 2.7563736e-1f}, {8.4804810e-1f, 5.2991926e-1f}},
            {{9.9026807e-1f, 1.3917310e-1f}, {9.6126170e-1f, 2.7563736e-1f}},
    });

    static final Tables.lc3_fft_bf3_twiddles[] lc3_fft_twiddles_bf3 = {fft_twiddles_15, fft_twiddles_45};

    static final lc3_fft_bf2_twiddles[][] lc3_fft_twiddles_bf2 = {{
            new lc3_fft_bf2_twiddles(10 / 2, "fft_twiddles_10"),
            new lc3_fft_bf2_twiddles(30 / 2, "fft_twiddles_30"),
            new lc3_fft_bf2_twiddles(90 / 2, "fft_twiddles_90")
    }, {
            new lc3_fft_bf2_twiddles(20 / 2, "fft_twiddles_20"),
            new lc3_fft_bf2_twiddles(60 / 2, "fft_twiddles_60"),
            new lc3_fft_bf2_twiddles(180 / 2, "fft_twiddles_180")
    }, {
            new lc3_fft_bf2_twiddles(40 / 2, "fft_twiddles_40"),
            new lc3_fft_bf2_twiddles(120 / 2, "fft_twiddles_120")
    }, {
            new lc3_fft_bf2_twiddles(80 / 2, "fft_twiddles_80"),
            new lc3_fft_bf2_twiddles(240 / 2, "fft_twiddles_240")
    }, {
            new lc3_fft_bf2_twiddles(160 / 2, "fft_twiddles_160"),
            new lc3_fft_bf2_twiddles(480 / 2, "fft_twiddles_480")}
    };

    static final Map<Duration, lc3_mdct_rot_def[]> lc3_mdct_rot = Map.of(
            _2M5, new lc3_mdct_rot_def[] {
                    new lc3_mdct_rot_def(40 / 4, "mdct_rot_40"),
                    new lc3_mdct_rot_def(80 / 4, "mdct_rot_80"),
                    new lc3_mdct_rot_def(120 / 4, "mdct_rot_120"),
                    new lc3_mdct_rot_def(160 / 4, "mdct_rot_160"),
                    new lc3_mdct_rot_def(240 / 4, "mdct_rot_240"),
                    new lc3_mdct_rot_def(240 / 4, "mdct_rot_240"),
                    new lc3_mdct_rot_def(480 / 4, "mdct_rot_480")
            }, _5M, new lc3_mdct_rot_def[] {
                    new lc3_mdct_rot_def(80 / 4, "mdct_rot_80"),
                    new lc3_mdct_rot_def(160 / 4, "mdct_rot_160"),
                    new lc3_mdct_rot_def(240 / 4, "mdct_rot_240"),
                    new lc3_mdct_rot_def(320 / 4, "mdct_rot_320"),
                    new lc3_mdct_rot_def(480 / 4, "mdct_rot_480"),
                    new lc3_mdct_rot_def(480 / 4, "mdct_rot_480"),
                    new lc3_mdct_rot_def(960 / 4, "mdct_rot_960")
            }, _7M5, new lc3_mdct_rot_def[] {
                    new lc3_mdct_rot_def(120 / 4, "mdct_rot_120"),
                    new lc3_mdct_rot_def(240 / 4, "mdct_rot_240"),
                    new lc3_mdct_rot_def(360 / 4, "mdct_rot_360"),
                    new lc3_mdct_rot_def(480 / 4, "mdct_rot_480"),
                    new lc3_mdct_rot_def(720 / 4, "mdct_rot_720")
            }, _10M, new lc3_mdct_rot_def[] {
                    new lc3_mdct_rot_def(160 / 4, "mdct_rot_160"),
                    new lc3_mdct_rot_def(320 / 4, "mdct_rot_320"),
                    new lc3_mdct_rot_def(480 / 4, "mdct_rot_480"),
                    new lc3_mdct_rot_def(640 / 4, "mdct_rot_640"),
                    new lc3_mdct_rot_def(960 / 4, "mdct_rot_960"),
                    new lc3_mdct_rot_def(960 / 4, "mdct_rot_960"),
                    new lc3_mdct_rot_def(1920 / 4, "mdct_rot_1920")
            }
    );

    private static float[] init_mdct_win(String f) {
        List<Float> l = new ArrayList<>();
        Scanner s = new Scanner(Tables.class.getResourceAsStream(f + ".txt"));
        s.useDelimiter("[\s,]");
        while (s.hasNextFloat()) {
            l.add(s.nextFloat());
        }
        return l.stream().collect(() -> FloatBuffer.allocate(l.size()), FloatBuffer::put, (left, right) -> {}).array();
    }

    static final Map<Duration, float[][]> lc3_mdct_win = Map.of(
            _2M5, new float[][] {
                    init_mdct_win("mdct_win_2m5_8k"),
                    init_mdct_win("mdct_win_2m5_16k"),
                    init_mdct_win("mdct_win_2m5_24k"),
                    init_mdct_win("mdct_win_2m5_32k"),
                    init_mdct_win("mdct_win_2m5_48k"),
                    init_mdct_win("mdct_win_2m5_48k_hr"),
                    init_mdct_win("mdct_win_2m5_96k_hr")
            }, _5M, new float[][] {
                    init_mdct_win("mdct_win_5m_8k"),
                    init_mdct_win("mdct_win_5m_16k"),
                    init_mdct_win("mdct_win_5m_24k"),
                    init_mdct_win("mdct_win_5m_32k"),
                    init_mdct_win("mdct_win_5m_48k"),
                    init_mdct_win("mdct_win_5m_48k_hr"),
                    init_mdct_win("mdct_win_5m_96k_hr")
            }, _7M5, new float[][] {
                    init_mdct_win("mdct_win_7m5_8k"),
                    init_mdct_win("mdct_win_7m5_16k"),
                    init_mdct_win("mdct_win_7m5_24k"),
                    init_mdct_win("mdct_win_7m5_32k"),
                    init_mdct_win("mdct_win_7m5_48k")
            }, _10M, new float[][] {
                    init_mdct_win("mdct_win_10m_8k"),
                    init_mdct_win("mdct_win_10m_16k"),
                    init_mdct_win("mdct_win_10m_24k"),
                    init_mdct_win("mdct_win_10m_32k"),
                    init_mdct_win("mdct_win_10m_48k"),
                    init_mdct_win("mdct_win_10m_48k_hr"),
                    init_mdct_win("mdct_win_10m_96k_hr"),
            }
    );
}
