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

import google.sound.lc3.Lc3.Duration;
import google.sound.lc3.Lc3.SRate;

import static google.sound.lc3.Tables.lc3_ne;


class Plc {

    private int seed;
    private int count;
    private float alpha;

    /**
     * Reset PLC state
     */
    void lc3_plc_reset() {
        this.seed = 24607;
        lc3_plc_suspend();
    }

    /**
     * Suspend PLC synthesis (Error-free frame decoded)
     */
    void lc3_plc_suspend() {
        this.count = 1;
        this.alpha = 1.0f;
    }

    /**
     * Synthesis of a PLC frame
     * <p>
     * `x` and `y` can be the same buffer
     *
     * @param dt Duration of the frame
     * @param sr SampleRate of the frame
     * @param x  Last good spectral coefficients
     * @param y  Return emulated ones
     */
    void lc3_plc_synthesize(Duration dt, SRate sr, float[] x, int xp, float[] y, int yp) {
        int ne = lc3_ne(dt, sr);

        alpha *= (this.count < 4 ? 1.0f : this.count < 8 ? 0.9f : 0.85f);

        for (int i = 0; i < ne; i++) {
            seed = (16831 + seed * 12821) & 0xffff;
            y[yp + i] = alpha * ((seed & 0x8000) != 0 ? -x[xp + i] : x[xp + i]);
        }

        this.count++;
    }
}
