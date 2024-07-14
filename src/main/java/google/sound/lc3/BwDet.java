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

import google.sound.lc3.Lc3.BandWidth;
import google.sound.lc3.Lc3.Duration;
import google.sound.lc3.Lc3.SRate;

import static google.sound.lc3.Lc3.Duration._10M;
import static google.sound.lc3.Lc3.Duration._2M5;
import static google.sound.lc3.Lc3.Duration._5M;
import static google.sound.lc3.Lc3.Duration._7M5;
import static google.sound.lc3.Lc3.isHR;


class BwDet {

    /* Bandwidth regions */
    private record region(
            int is, // 8
            int ie // 8
    ) {}

    private static final region[][] bws_table_2m5 = new region[][/* SWB + 1 */] {
            {new region(24, 34 + 1)},
            {new region(24, 32 + 1), new region(35, 39 + 1)},
            {new region(24, 31 + 1), new region(33, 38 + 1), new region(39, 42 + 1)},
            {new region(22, 29 + 1), new region(31, 35 + 1), new region(37, 40 + 1), new region(41, 43 + 1)},
    };

    private static final region[][] bws_table_5m = new region[][/* SWB + 1 */] {
            {new region(39, 49 + 1)},
            {new region(35, 44 + 1), new region(47, 51 + 1)},
            {new region(34, 42 + 1), new region(44, 49 + 1), new region(50, 53 + 1)},
            {new region(32, 40 + 1), new region(42, 46 + 1), new region(48, 51 + 1), new region(52, 54 + 1)},
    };

    private static final region[][] bws_table_7m5 = new region[][/* SWB + 1 */] {
            {new region(51, 63 + 1)},
            {new region(45, 55 + 1), new region(58, 63 + 1)},
            {new region(42, 51 + 1), new region(53, 58 + 1), new region(60, 63 + 1)},
            {new region(40, 48 + 1), new region(51, 55 + 1), new region(57, 60 + 1), new region(61, 63 + 1)},
    };

    private static final region[][] bws_table_10m = new region[][/* SWB + 1 */] {
            {new region(53, 63 + 1)},
            {new region(47, 56 + 1), new region(59, 63 + 1)},
            {new region(44, 52 + 1), new region(54, 59 + 1), new region(60, 63 + 1)},
            {new region(41, 49 + 1), new region(51, 55 + 1), new region(57, 60 + 1), new region(61, 63 + 1)},
    };

    private static final Map<Duration, region[][/* SWB + 1 */]> bws_table = Map.of(
            _2M5, bws_table_2m5, // LC3_IF_PLUS(bws_table_2m5, null),
            _5M, bws_table_5m, // LC3_IF_PLUS(bws_table_5m, null),
            _7M5, bws_table_7m5,
            _10M, bws_table_10m
    );

    private static final Map<Duration, int[]> l_table = Map.of( /* NUM_DURATION */ /* SWB + 1 */
            _2M5, new int[] {4, 4, 3, 1},
            _5M, new int[] {4, 4, 3, 1},
            _7M5, new int[] {4, 4, 3, 2},
            _10M, new int[] {4, 4, 3, 1}
    );

    /**
     * Bandwidth detector
     *
     * @param dt Duration of the frame
     * @param sr sampleRate of the frame
     * @param e  Energy estimation per bands
     * @return Return detected bandwitdth
     */
    static BandWidth lc3_bwdet_run(Duration dt, SRate sr, float[] e) {

        // Stage 1
        // Determine bw0 candidate

        BandWidth bw0 = Lc3.BandWidth.NB;
        BandWidth bwn = Lc3.BandWidth.values()[sr.ordinal()];

        if (bwn.ordinal() == 0 || isHR(sr))
            return bwn;

        region[] bwr = bws_table.get(dt)[bwn.ordinal() - 1];

        for (int j = 0; j < bwn.ordinal(); j++) {
            BandWidth bw = Lc3.BandWidth.values()[j];
            int i = bwr[bw.ordinal()].is;
            int ie = bwr[bw.ordinal()].ie;
            int n = ie - i;

            float se = e[i];
            for (i++; i < ie; i++)
                se += e[i];

            if (se >= (10 << (bw == Lc3.BandWidth.NB ? 1 : 0)) * n)
                bw0 = Lc3.BandWidth.values()[bw.ordinal() + 1];
        }

        // Stage 2
        // Detect drop above cut-off frequency.
        // The Tc condition (13) is precalculated, as
        // Tc[] = 10 ^ (n / 10) , n = { 15, 23, 20, 20 } */

        boolean hold = bw0.ordinal() >= bwn.ordinal();

        if (!hold) {
            int i0 = bwr[bw0.ordinal()].is;
            int l = l_table.get(dt)[bw0.ordinal()];
            float tc = new float[] {31.62277660f, 199.52623150f, 100, 100}[bw0.ordinal()];

            for (int i = i0 - l + 1; !hold && i <= i0 + 1; i++) {
                hold = e[i - l] > tc * e[i];
            }
        }

        return hold ? bw0 : bwn;
    }

    /**
     * Return number of bits coding the bandwidth value
     *
     * @param sr sampleRate of the frame
     * @return Number of bits coding the bandwidth value
     */
    static int lc3_bwdet_get_nbits(SRate sr) {
        return isHR(sr) ? 0 : (sr.ordinal() > 0 ? 1 : 0) + (sr.ordinal() > 1 ? 1 : 0) + (sr.ordinal() > 3 ? 1 : 0);
    }

    /**
     * Put bandwidth indication
     *
     * @param bits Bitstream context
     * @param sr   sampleRate of the frame
     * @param bw   Bandwidth detected
     */
    static void lc3_bwdet_put_bw(Bits bits, SRate sr, BandWidth bw) {
        int nbits_bw = lc3_bwdet_get_nbits(sr);
        if (nbits_bw > 0)
            bits.lc3_put_bits(bw.ordinal(), nbits_bw);
    }

    /**
     * Get bandwidth indication
     *
     * @param bits Bitstream context
     * @param sr   sampleRate of the frame
     * @param bw   Return bandwidth indication
     * @return 0: Ok  -1: Invalid bandwidth indication
     */
    static int lc3_bwdet_get_bw(Bits bits, SRate sr, BandWidth[] bw) {
        BandWidth max_bw = Lc3.BandWidth.values()[sr.ordinal()];
        int nbits_bw = lc3_bwdet_get_nbits(sr);

        bw[0] = nbits_bw <= 0 ? max_bw : Lc3.BandWidth.values()[bits.lc3_get_bits(nbits_bw)];

        if (bw[0].ordinal() > max_bw.ordinal()) {
            bw[0] = max_bw;
            return -1;
        } else {
            return 0;
        }
    }
}