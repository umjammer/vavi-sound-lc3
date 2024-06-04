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

import static google.sound.lc3.Common.SRate._16K;
import static google.sound.lc3.Common.SRate._24K;
import static google.sound.lc3.Common.SRate._32K;
import static google.sound.lc3.Common.SRate._48K;
import static google.sound.lc3.Common.SRate._48K_HR;
import static google.sound.lc3.Common.SRate._8K;
import static google.sound.lc3.Common.SRate._96K_HR;


/**
 * Activation flags for LC3-Plus and LC3-Plus HR features
 */
class Common {

    public interface PentaConsumer<T, U, V, W, X> {

        void accept(T t, U u, V v, W w, X x);
    }

    public interface DecaConsumer<T, U, V, W, X, Y, Z, A, B, C> {

        void accept(T t, U u, V v, W w, X x, Y y,Z z, A a, B b, C c);
    }

    static boolean LC3_PLUS_HR = true;

    /*
     * Characteristics
     *
     * - The number of samples within a frame
     *
     * - The number of MDCT delayed samples, sum of half a frame and
     *   an ovelap of future by 1.25 ms (2.5ms, 5ms and 10ms frame durations)
     *   or 2 ms (7.5ms frame duration).
     *
     * - For decoding, keep 18 ms of history, aligned on a frame
     *
     * - For encoding, keep 1.25 ms of temporal previous samples
     */

    static int LC3_NS(int dt_us, int sr_hz) { return ( (dt_us) * (sr_hz) / 1000 / 1000 ); }

    static int LC3_ND(int dt_us, int sr_hz) {
        return (LC3_NS(dt_us, sr_hz) / 2 +
                LC3_NS((dt_us) == 7500 ? 2000 : 1250, sr_hz) );
    }

    static int LC3_NH(int dt_us, int sr_hz) {
        return ((sr_hz) > 48000 ? 0 : (LC3_NS(18000, sr_hz) +
                LC3_NS(dt_us, sr_hz) - (LC3_NS(18000, sr_hz) % LC3_NS(dt_us, sr_hz)) ) );
    }

    static int LC3_NT(int sr_hz) {
        return (LC3_NS(1250, sr_hz));
    }

    /**
     * Frame duration
     */
    enum Duration {
        _2M5,
        _5M,
        _7M5,
        _10M,
    }

    /**
     * Sampling frequency and high-resolution mode
     */
    enum SRate {
        _8K,
        _16K,
        _24K,
        _32K,
        _48K,
        _48K_HR,
        _96K_HR,
    }

    /*
     * Hot Function attribute
     * Selectively disable sanitizer
     */

    static int clip(int v, int min, int max) { return Math.min(Math.max(v, min), max); }

    /**
     * Return `true` when high-resolution mode
     */
    static boolean isHR(SRate sr) {
        return LC3_PLUS_HR && (sr.ordinal() >= _48K_HR.ordinal());
    }

    /**
     * Bandwidth, mapped to Nyquist frequency of sampleRates
     */
    enum BandWidth {
        NB(_8K),
        WB(_16K),
        SSWB(_24K),
        SWB(_32K),
        FB(_48K),

        FB_HR(_48K_HR),
        UB_HR(_96K_HR);

        final SRate sr;
        BandWidth(SRate sr) {
            this.sr = sr;
        }
    }

    /**
     * Complex floating point number
     */
    static class Complex {
        float re;
        float im;
        Complex() {}
        Complex(float re, float im) {
            this.re = re;
            this.im = im;
        }
    }
}