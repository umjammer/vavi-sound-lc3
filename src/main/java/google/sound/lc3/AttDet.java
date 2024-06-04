/******************************************************************************
 *
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
 *
 ******************************************************************************/

package google.sound.lc3;

import java.util.Map;

import google.sound.lc3.Common.Duration;
import google.sound.lc3.Common.SRate;

import static google.sound.lc3.Common.Duration._10M;
import static google.sound.lc3.Common.Duration._7M5;
import static google.sound.lc3.Common.isHR;
import static google.sound.lc3.Common.SRate._32K;


class AttDet {

    /**
     * Encoder state and memory
     */

    static class Analysis {
        int en1, an1;
        int p_att;
    }

    static final Map<Integer, int[/*NUM_SRATE - _32K*/][/*2*/]> nBytesRanges = Map.of(
            _7M5.ordinal() - _7M5.ordinal(), new int[][] {{61, 149}, {75, 149}},
            _10M.ordinal() - _7M5.ordinal(), new int[][] {{81, Integer.MAX_VALUE}, {100, Integer.MAX_VALUE}}
    );

    /**
     * Time domain attack detector
     *
     * @param dt     Duration of the frame
     * @param sr     sampleRate of the frame
     * @param nBytes Size in bytes of the frame
     * @param attDet Context of the Attack Detector
     * @param x      [-6..-1] Previous, [0..ns-1] Current samples
     * @return 1: Attack detected  0: Otherwise
     */
    static boolean lc3_attdet_run(Duration dt, SRate sr,
                                  int nBytes, Analysis attDet, short[] x) {

        // Check enabling

        if (dt.ordinal() < _7M5.ordinal() || sr.ordinal() < _32K.ordinal() || isHR(sr) ||
                nBytes < nBytesRanges.get(dt.ordinal() - _7M5.ordinal())[sr.ordinal() - _32K.ordinal()][0] ||
                nBytes > nBytesRanges.get(dt.ordinal() - _7M5.ordinal())[sr.ordinal() - _32K.ordinal()][1])
            return false;

        // Filtering & Energy calculation

        int nBlk = 4 - (dt == _7M5 ? 1 : 0);
        int[] e = new int[4];

        int xP = 0;
        for (int i = 0; i < nBlk; i++) {
            e[i] = 0;

            if (sr == _32K) {
                int xn2 = (x[xP + -4] + x[xP + -3]) >> 1;
                int xn1 = (x[xP + -2] + x[xP + -1]) >> 1;
                int xn, xf;

                for (int j = 0; j < 40; j++, xP += 2, xn2 = xn1, xn1 = xn) {
                    xn = (x[xP + 0] + x[xP + 1]) >> 1;
                    xf = (3 * xn - 4 * xn1 + 1 * xn2) >> 3;
                    e[i] += (xf * xf) >> 5;
                }
            } else {
                int xn2 = (short) ((x[xP + -6] + x[xP + -5] + x[xP + -4]) >> 2);
                int xn1 = (short) ((x[xP + -3] + x[xP + -2] + x[xP + -1]) >> 2);
                int xn, xf;

                for (int j = 0; j < 40; j++, xP += 3, xn2 = xn1, xn1 = xn) {
                    xn = (x[xP + 0] + x[xP + 1] + x[xP + 2]) >> 2;
                    xf = (3 * xn - 4 * xn1 + 1 * xn2) >> 3;
                    e[i] += (xf * xf) >> 5;
                }
            }
        }

        // Attack detection
        //
        // The attack block `pAtt` is defined as the normative value + 1,
        // in such way, it will be initialized to 0 */

        int pAtt = 0;
        int[] a = new int[4];

        for (int i = 0; i < nBlk; i++) {
            a[i] = Math.max(attDet.an1 >> 2, attDet.en1);
            attDet.en1 = e[i];
            attDet.an1 = a[i];

            if ((e[i] >> 3) > a[i] + (a[i] >> 4))
                pAtt = i + 1;
        }

        boolean att = attDet.p_att >= 1 + (nBlk >> 1) || pAtt > 0;
        attDet.p_att = pAtt;

        return att;
    }
}