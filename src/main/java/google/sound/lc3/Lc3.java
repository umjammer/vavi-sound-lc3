/*
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
 */

package google.sound.lc3;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;

import google.sound.lc3.AttDet.Analysis;
import google.sound.lc3.Bits.Mode;
import google.sound.lc3.Ltpf.Synthesis;

import static google.sound.lc3.AttDet.lc3_attdet_run;
import static google.sound.lc3.BwDet.lc3_bwdet_get_bw;
import static google.sound.lc3.BwDet.lc3_bwdet_put_bw;
import static google.sound.lc3.BwDet.lc3_bwdet_run;
import static google.sound.lc3.Energy.LC3_MAX_BANDS;
import static google.sound.lc3.Energy.lc3_energy_compute;
import static google.sound.lc3.FastMath.lc3_ldexpf;
import static google.sound.lc3.Lc3.SRate._16K;
import static google.sound.lc3.Lc3.SRate._24K;
import static google.sound.lc3.Lc3.SRate._32K;
import static google.sound.lc3.Lc3.SRate._48K;
import static google.sound.lc3.Lc3.SRate._48K_HR;
import static google.sound.lc3.Lc3.SRate._8K;
import static google.sound.lc3.Lc3.SRate._96K_HR;
import static google.sound.lc3.Ltpf.lc3_ltpf_synthesize;
import static google.sound.lc3.Mdct.forward;
import static google.sound.lc3.Mdct.inverse;
import static google.sound.lc3.Tables.lc3_max_frame_bytes;
import static google.sound.lc3.Tables.lc3_min_frame_bytes;
import static google.sound.lc3.Tables.lc3_nd;
import static google.sound.lc3.Tables.lc3_ne;
import static google.sound.lc3.Tables.lc3_nh;
import static google.sound.lc3.Tables.lc3_ns;
import static google.sound.lc3.Tables.lc3_nt;
import static java.lang.System.getLogger;


/**
 * Low Complexity Communication Codec (LC3)
 * <p>
 * This implementation conforms to :
 * <ul>
 *   <li> Low Complexity Communication Codec (LC3)<br/>
 *     Bluetooth Specification v1.0</li>
 *
 *   <li> ETSI TS 103 634 v1.4.1<br/>
 *     Digital Enhanced Cordless Telecommunications (DECT)<br/>
 *     Low Complexity Communication Codec plus (LC3plus)</li>
 * </ul>
 * LC3 and LC3 Plus are audio codecs designed for low-latency audio transport.
 * <ul>
 * <li> Unlike most other codecs, the LC3 codec is focused on audio streaming
 *   in finalrained (on packet sizes and interval) tranport layer.
 *   In this way, the LC3 does not handle :
 *   <ul>
 *   <li>VBR (Variable Bitrate), based on input signal complexity</li>
 *   <li>ABR (Adaptative Bitrate). It does not rely on any bit reservoir,
 *       a frame will be strictly encoded in the bytes budget given by
 *       the user (or transport layer).</li>
 *    </ul>
 *   <p>
 *   However, the bitrate (bytes budget for encoding a frame) can be
 *   freely changed at any time. But will not rely on signal complexity,
 *   it can follow a temporary bandwidth increase or reduction.</li>
 *   </p>
 * <li> Unlike classic codecs, the LC3 codecs does not run on fixed amount
 *   of samples as input. It operates only on fixed frame duration, for
 *   any supported sample rates (8 to 48 KHz). Two frames duration are
 *   available 7.5ms and 10ms.</li>
 * </ul>
 * </p>
 * <h3>--- LC3 Plus features ---</h3>
 * <p>
 * In addition to LC3, following features of LC3 Plus are proposed:
 * <ul>
 * <li> Frame duration of 2.5 and 5ms.</li>
 * <li> High-Resolution mode, 48 KHz, and 96 kHz sampling rates.</li>
 * </ul>
 * <p>
 * The distinction between LC3 and LC3 plus is made according to :
 * <pre>
 *      Frame Duration  | 2.5ms |  5ms  | 7.5ms | 10 ms |
 *     ---------------- | ----- | ----- | ----- | ----- |
 *      LC3             |       |       |   X   |   X   |
 *      LC3 Plus        |   X   |   X   |       |   X   |
 * </pre>
 * <p>
 * The 10 ms frame duration is available in LC3 and LC3 plus standard.
 * In this mode, the produced bitstream can be referenced either
 * as LC3 or LC3 plus.
 * </p>
 * <p>
 * The LC3 Plus high-resolution mode should be preferred at high bitrates
 * and larger audio bandwidth. In this mode, the audio bandwidth is always
 * up to the Nyquist frequency, compared to LC3 at 48 KHz, which limits
 * the bandwidth to 20 KHz.
 * </p>
 *
 * <h3>--- Bit rate ---</h3>
 * <p>
 * The proposed implementation accepts any frame sizes between 20 and 400 Bytes
 * for non-high-resolution mode. Mind that the LC3 Plus standard defines
 * smaller sizes for frame durations shorter than 10 ms and/or sampling rates
 * less than 48 kHz.
 * </p>
 * <p>
 * In High-Resolution mode, the frame sizes (and bitrates) are restricted
 * as follows:
 * </p>
 * <pre>
 *      HR Configuration  |  Frame sizes  | Bitrate (kbps) |
 *     ------------------ | ------------- | -------------- |
 *        10 ms - 48 KHz  |   156 to 625  |   124.8 - 500  |
 *        10 ms - 96 KHz  |   187 to 625  |   149.6 - 500  |
 *     ------------------ | ------------- | -------------- |
 *         5 ms - 48 KHz  |    93 to 375  |   148.8 - 600  |
 *         5 ms - 96 KHz  |   109 to 375  |   174.4 - 600  |
 *     ------------------ | ------------- | -------------- |
 *       2.5 ms - 48 KHz  |    54 to 210  |   172.8 - 672  |
 *       2.5 ms - 96 KHz  |    62 to 210  |   198.4 - 672  |
 * </pre>
 *
 * <h3>--- About 44.1 KHz sample rate ---</h3>
 * <p>
 * The Bluetooth specification and the ETSI TS 103 634 standard references
 * the 44.1 KHz sample rate, although there is no support in the core algorithm
 * of the codec.
 * We can summarize the 44.1 KHz support by "You can put any sample rate around
 * the defined base sample rates." Please mind the following items :
 * <ol>
 *   <li>. The frame size will not be 2.5ms, 5ms, 7.5 ms or 10 ms, but is scaled
 *      by 'supported sample rate' / 'input sample rate'</li>
 *
 *   <li>. The bandwidth will be hard limited (to 20 KHz) if you select 48 KHz.
 *      The encoded bandwidth will also be affected by the above inverse
 *      factor of 20 KHz.</li>
 * </ol>
 * Applied to 44.1 KHz, we get :
 * <ol>
 *   <li>. About  8.16 ms frame duration, instead of 7.5 ms
 *      About 10.88 ms frame duration, instead of  10 ms</li>
 *
 *   <li>. The bandwidth becomes limited to 18.375 KHz</li>
 * </ol>
 * </p>
 * <h3>--- How to encode / decode ---</h3>
 * <p>
 * An encoder / decoder context needs to be setup. This context keeps states
 * on the current stream to proceed, and samples that overlapped across
 * frames.
 * </p>
 * You have two ways to setup the encoder / decoder :
 * <ol>
 * <li> Using static memory allocation (this module does not rely on
 *   any dynamic memory allocation). The types `lc3_xxcoder_mem_16k_t`,
 *   and `lc3_xxcoder_mem_48k_t` have size of the memory needed for
 *   encoding up to 16 KHz or 48 KHz.</li>
 *
 * <li> Using dynamic memory allocation. The `lc3_xxcoder_size()` procedure
 *   returns the needed memory size, for a given configuration. The memory
 *   space must be aligned to a pointer size. As an example, you can setup
 *   encoder like this :
 * <pre>
 *   | enc = lc3_setup_encoder(frame_us, sample rate,
 *   |      malloc(lc3_encoder_size(frame_us, sample rate)));
 *   | ...
 *   | free(enc);
 * </pre></li>
 * </ol>
 *   Note :
 *   <li> A null memory adress as input, will return a null encoder context.</li>
 *   <li>- The returned encoder handle is set at the address of the allocated
 *     memory space, you can directly free the handle.</li>
 * <p>
 * Next, call the `lc3_encode()` encoding procedure, for each frames.
 * To handle multichannel streams (Stereo or more), you can proceed with
 * interleaved channels PCM stream like this :
 * <pre>
 *   | for(int ich = 0; ich < nch: ich++)
 *   |     lc3_encode(encoder[ich], pcm + ich, nch, ...);
 * </pre>
 *   with `nch` as the number of channels in the PCM stream
 * </p>
 * <hr/>
 *
 * @author Antoine SOULIER, Tempow / Google LLC
 */
class Lc3 {

    private static final Logger logger = getLogger(Lc3.class.getName());

    interface HexaConsumer<T, U, V, W, X, Y> {

        void accept(T t, U u, V v, W w, X x, Y y);
    }

    interface DecaConsumer<T, U, V, W, X, Y, Z, A, B, C> {

        void accept(T t, U u, V v, W w, X x, Y y, Z z, A a, B b, C c);
    }

//#region common.h

    /**
     * Activation flags for LC3-Plus and LC3-Plus HR features
     */

    static final boolean LC3_PLUS = Boolean.parseBoolean(System.getProperty("google.sound.lc3.plus", "false"));

    static final boolean LC3_PLUS_HR = Boolean.parseBoolean(System.getProperty("google.sound.lc3.hr", "false"));

    static <T> T LC3_IF_PLUS(T a, T b) {
        return LC3_PLUS ? a : b;
    }

    static <T> T LC3_IF_PLUS_HR(T a, T b) {
        return LC3_PLUS_HR ? a : b;
    }

    static int clip(int v, int min, int max) {
        return Math.min(Math.max(v, min), max);
    }

    static int LC3_SAT16(int v) { return clip(v, -(1 << 15), (1 << 15) - 1); }
    static int LC3_SAT24(int v) { return clip(v, -(1 << 23), (1 << 23) - 1); }

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
        NB,
        WB,
        SSWB,
        SWB,
        FB,

        FB_HR,
        UB_HR,

        LC3_NUM_BANDWIDTH;
    }

    /**
     * Complex floating point number
     */
    static class Complex {

        float re;
        float im;

        Complex() {
        }

        Complex(float re, float im) {
            this.re = re;
            this.im = im;
        }

        @Override public String toString() { return String.format("{% 9.4f, % 9.4f}", re, im); }
    }

//#endregion

//#region lc3.h

    /*
     * Limitations
     * - On the bitrate, in bps
     * - On the size of the frames in bytes
     * - On the number of samples by frames
     */

    private static final int LC3_MIN_BITRATE = 16000;
    private static final int LC3_MAX_BITRATE = 320000;
    private static final int LC3_HR_MAX_BITRATE = 672000;

    static final int LC3_MIN_FRAME_BYTES = 20;
    static final int LC3_MAX_FRAME_BYTES = 400;
    static final int LC3_HR_MAX_FRAME_BYTES = 625;

    private static final int LC3_MIN_FRAME_SAMPLES = 8000;
    private static final int LC3_MAX_FRAME_SAMPLES = 48000;
    static final int LC3_HR_MAX_FRAME_SAMPLES = 96000;

    /*
     * Parameters check
     */

    /** True when frame duration in us is suitable */
    static boolean LC3_CHECK_DT_US(int us) {
        return us == 2500 || us == 5000 || us == 7500 || us == 10000;
    }

    /** True when sample rate in Hz is suitable */
    static boolean LC3_CHECK_SR_HZ(int sr) {
        return sr == 8000 || sr == 16000 || sr == 24000 || sr == 32000 || sr == 48000;
    }

    /**
     * True when sample rate in Hz is suitable, according to the
     * selection of the high-resolution mode `hrMode`.
     */
    static boolean LC3_HR_CHECK_SR_HZ(boolean hrMode, int sr) {
        return hrMode ? sr == 48000 || sr == 96000 : LC3_CHECK_SR_HZ(sr);
    }

    /**
     * PCM Sample Format
     * S16      Signed 16 bits, in 16 bits words (int16_t)
     * S24      Signed 24 bits, using low three bytes of 32 bits words (int).
     * The high byte sign extends (bits 31..24 set to b23).
     * S24_3LE  Signed 24 bits packed in 3 bytes little endian
     * FLOAT    Floating point 32 bits (float type), in range -1 to 1
     */
    enum lc3_pcm_format {
        LC3_PCM_FORMAT_S16 {
            /**
             * Input PCM Samples from signed 16 bits
             */
            @Override void load(Encoder encoder, byte[] pcm, int stride) {
                ShortBuffer pcm_ = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();

                Duration dt = encoder.dt;
                SRate sr = encoder.sr_pcm;

                int xt = encoder.xt_off; // encoder.x
                int xs = encoder.xs_off; // encoder.x
                int ns = lc3_ns(dt, sr);

                int pcmP = 0;
                for (int i = 0; i < ns; i++ , pcmP += stride) {
                    encoder.x[xt + i] = pcm_.get(pcmP);
                    encoder.x[xs + i] = pcm_.get(pcmP);
                }
            }
            /**
             * Output PCM Samples to signed 16 bits
             */
            @Override void store(Decoder decoder, byte[] pcm, int op, int stride) {
                ShortBuffer pcm_ = ByteBuffer.wrap(pcm, op, pcm.length - op).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();

                Duration dt = decoder.dt;
                SRate sr = decoder.sr_pcm;

                int xs = decoder.xs_off; // decoder.x
                int ns = lc3_ns(dt, sr);

                int pcmP = 0;
                for (; ns > 0; ns--, xs++, pcmP += stride) {
                    int s = decoder.x[xs] >= 0 ? (int) (decoder.x[xs] + 0.5f) : (int) (decoder.x[xs] - 0.5f);
                    pcm_.put(pcmP, (short) LC3_SAT16(s));
                }
            }

        },
        LC3_PCM_FORMAT_S24 {
            /**
             * Input PCM Samples from signed 24 bits
             */
            @Override void load(Encoder encoder, byte[] pcm, int stride) {
                IntBuffer pcm_ = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();

                Duration dt = encoder.dt;
                SRate sr = encoder.sr_pcm;

                int xt = encoder.xt_off; // encoder.x
                int xs = encoder.xs_off; // encoder.x
                int ns = lc3_ns(dt, sr);

                int pcmP = 0;
                for (int i = 0; i < ns; i++ , pcmP += stride) {
                    encoder.x[xt + i] = pcm_.get(pcmP) >>> 8;
                    encoder.x[xs + i] = lc3_ldexpf(pcm_.get(pcmP), -8);
                }
            }
            /**
             * Output PCM Samples to signed 24 bits
             */
            @Override void store(Decoder decoder, byte[] pcm, int op, int stride) {
                IntBuffer pcm_ = ByteBuffer.wrap(pcm, op, pcm.length - op).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();

                Duration dt = decoder.dt;
                SRate sr = decoder.sr_pcm;

                int xs = decoder.xs_off; // decoder.x
                int ns = lc3_ns(dt, sr);

                int pcmP = 0;
                for (; ns > 0; ns--, xs++ , pcmP += stride) {
                    int s = decoder.x[xs] >= 0
                            ? (int) (lc3_ldexpf(decoder.x[xs], 8) + 0.5f)
                            : (int) (lc3_ldexpf(decoder.x[xs], 8) - 0.5f);
                    pcm_.put(pcmP, LC3_SAT24(s));
                }
            }

        },
        LC3_PCM_FORMAT_S24_3LE {
            /**
             * Input PCM Samples from signed 24 bits packed
             */
            @Override void load(Encoder encoder, byte[] pcm, int stride) {

                Duration dt = encoder.dt;
                SRate sr = encoder.sr_pcm;

                int xt = encoder.xt_off; // encoder.x
                int xs = encoder.xs_off; // encoder.x
                int ns = lc3_ns(dt, sr);

                int pcmP = 0;
                for (int i = 0; i < ns; i++ , pcmP += 3 * stride) {
                    int in = ((pcm[pcmP + 0] & 0xff) << 8) |
                            ((pcm[pcmP + 1] & 0xff) << 16) |
                            ((pcm[pcmP + 2] & 0xff) << 24);

                    encoder.x[xt + i] = in >>> 16;
                    encoder.x[xs + i] = lc3_ldexpf(in, -16);
                }
            }
            /**
             * Output PCM Samples to signed 24 bits packed
             */
            @Override void store(Decoder decoder, byte[] pcm, int op, int stride) {
                byte[] pcm_ = Arrays.copyOfRange(pcm, op, pcm.length - op);

                Duration dt = decoder.dt;
                SRate sr = decoder.sr_pcm;

                int xs = decoder.xs_off; // decoder.x
                int ns = lc3_ns(dt, sr);

                int pcmP = 0;
                for (; ns > 0; ns--, xs++, pcmP += 3 * stride) {
                    int s = decoder.x[xs] >= 0 ? (int) (lc3_ldexpf(decoder.x[xs], 8)+0.5f)
                            :(int) (lc3_ldexpf(decoder.x[xs], 8)-0.5f);

                    s = LC3_SAT24(s);
                    pcm_[pcmP + 0] = (byte) ((s >> 0) & 0xff);
                    pcm_[pcmP + 1] = (byte) ((s >> 8) & 0xff);
                    pcm_[pcmP + 2] = (byte) ((s >> 16) & 0xff);
                }
            }
        },
        LC3_PCM_FORMAT_FLOAT {
            /**
             * Input PCM Samples from float 32 bits
             */
            @Override void load(Encoder encoder, byte[] pcm, int stride) {
                FloatBuffer pcm_ = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();

                Duration dt = encoder.dt;
                SRate sr = encoder.sr_pcm;

                int xt = encoder.xt_off; // encoder.x
                int xs = encoder.xs_off; // encoder.x
                int ns = lc3_ns(dt, sr);

                int pcmP = 0;
                for (int i = 0; i < ns; i++, pcmP += stride) {
                    encoder.x[xs + i] = lc3_ldexpf(pcm_.get(pcmP), 15);
                    encoder.x[xt + i] = LC3_SAT16((int) encoder.x[xs + i]);
                }
            }
            /**
             * Output PCM Samples to float 32 bits
             */
            @Override void store(Decoder decoder, byte[] pcm, int op, int stride) {
                FloatBuffer pcm_ = ByteBuffer.wrap(pcm, op, pcm.length - op).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();

                Duration dt = decoder.dt;
                SRate sr = decoder.sr_pcm;

                int xs = decoder.xs_off; // decoder.x
                int ns = lc3_ns(dt, sr);

                int pcmP = 0;
                for (; ns > 0; ns--, xs++, pcmP += stride) {
                    float s = lc3_ldexpf(decoder.x[xs], -15);
                    pcm_.put(pcmP, Math.min(Math.max(s, -1.f), 1.f));
                }
            }
        };
        /**
         * Input PCM Samples.
         *
         * @param encoder Encoder state
         * @param pcm     Input PCM samples
         * @param stride  count between two consecutive
         */
        abstract void load(Encoder encoder, byte[] pcm, int stride);
        /**
         * Output PCM Samples.
         *
         * @param decoder Decoder state
         * @param pcm     Output PCM samples
         * @param stride  count between two consecutive
         */
        abstract void store(Decoder decoder, byte[] pcm, int op, int stride);
    }

//#endregion

//#region lc3_private.h

    /*
     * Characteristics
     *
     * - The number of samples within a frame
     *
     * - The number of MDCT delayed samples, sum of half a frame and
     *   an overlap of future by 1.25 ms (2.5ms, 5ms and 10ms frame durations)
     *   or 2 ms (7.5ms frame duration).
     *
     * - For decoding, keep 18 ms of history, aligned on a frame
     *
     * - For encoding, keep 1.25 ms of temporal previous samples
     */

    static int LC3_NS(int dt_us, int sr_hz) {
        return ((dt_us) * (sr_hz) / 1000 / 1000);
    }

    static int LC3_ND(int dt_us, int sr_hz) {
        return (LC3_NS(dt_us, sr_hz) / 2 +
                LC3_NS((dt_us) == 7500 ? 2000 : 1250, sr_hz));
    }

    static int LC3_NH(int dt_us, int sr_hz) {
        return ((sr_hz) > 48000 ? 0 : (LC3_NS(18000, sr_hz) +
                LC3_NS(dt_us, sr_hz) - (LC3_NS(18000, sr_hz) % LC3_NS(dt_us, sr_hz))));
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

    /**
     * Encoder state and memory
     */
    static class Encoder {

        Duration dt;
        SRate sr, sr_pcm;

        final Analysis attdet = new Analysis();
        final Ltpf.Analysis ltpf = new Ltpf.Analysis();
        final Spec.Analysis spec = new Spec.Analysis();

        int xt_off, xs_off, xd_off;
        float[] x = new float[1];
    }

    static int LC3_ENCODER_BUFFER_COUNT(int dt_us, int sr_hz) {
        return ((LC3_NS(dt_us, sr_hz) + LC3_NT(sr_hz)) / 2 + LC3_NS(dt_us, sr_hz) + LC3_ND(dt_us, sr_hz));
    }

    static Encoder LC3_ENCODER_MEM_T(int dt_us, int sr_hz) {
        return new Encoder() {
            Encoder __e;
            final float[] __x = new float[LC3_ENCODER_BUFFER_COUNT(dt_us, sr_hz) - 1];
        };
    }

    /**
     * Decoder state and memory
     */
    static class Decoder {

        Duration dt;
        SRate sr, sr_pcm;

        final Synthesis ltpf = new Synthesis();
        Plc plc;

        int xh_off, xs_off, xd_off, xg_off;
        float[] x;
    }

    static int LC3_DECODER_BUFFER_COUNT(int dt_us, int sr_hz) {
        return LC3_NH(dt_us, sr_hz) + LC3_NS(dt_us, sr_hz) +
                LC3_ND(dt_us, sr_hz) + LC3_NS(dt_us, sr_hz);
    }

//#endregion

//#region lc3.c

    /**
     * Frame side data
     */
    static class Side {

        BandWidth bw;
        boolean pitch_present;
        final Ltpf ltpf = new Ltpf();
        Sns sns;
        final Tns tns = new Tns();
        final Spec spec = new Spec();
    }

    //
    // General
    //

    /**
     * Resolve frame duration in us
     *
     * @param us     Frame duration in us
     * @param hrMode High-resolution mode indication
     * @return Frame duration identifier, or null
     */
    private static Duration resolve_dt(int us, boolean hrMode) {
        return us == 2500 ? Duration._2M5 :
                us == 5000 ? Duration._5M :
                        !hrMode && us == 7500 ? Duration._7M5 :
                                us == 10000 ? Duration._10M : null;
    }

    /**
     * Resolve samplerate in Hz
     *
     * @param hz     Samplerate in Hz
     * @param hrMode High-resolution mode indication
     * @return Sample rate identifier, or null
     */
    private static SRate resolve_srate(int hz, boolean hrMode) {
        hrMode = LC3_PLUS_HR && hrMode;

        return !hrMode && hz == 8000 ? _8K :
                !hrMode && hz == 16000 ? _16K :
                        !hrMode && hz == 24000 ? _24K :
                                !hrMode && hz == 32000 ? _32K :
                                        !hrMode && hz == 48000 ? _48K :
                                                hrMode && hz == 48000 ? _48K_HR :
                                                        hrMode && hz == 96000 ? _96K_HR : null;
    }

    /**
     * Return the number of PCM samples in a frame
     */
    static int lc3_hr_frame_samples(boolean hrMode, int dt_us, int sr_hz) {
        Duration dt = resolve_dt(dt_us, hrMode);
        SRate sr = resolve_srate(sr_hz, hrMode);

        if (dt == null || sr == null)
            return -1;

        return lc3_ns(dt, sr);
    }

    private static int lc3_frame_samples(int dt_us, int sr_hz) {
        return lc3_hr_frame_samples(false, dt_us, sr_hz);
    }

    /**
     * Return the size of frames or frame blocks, from bitRate
     */
    private static int lc3_hr_frame_block_bytes(boolean hrMode, int dt_us, int sr_hz, int nChannels, int bitRate) {
        Duration dt = resolve_dt(dt_us, hrMode);
        SRate sr = resolve_srate(sr_hz, hrMode);

        if (dt == null || sr == null || nChannels < 1 || nChannels > 8 || bitRate < 0)
            return -1;

        bitRate = clip(bitRate, 0, 8 * LC3_HR_MAX_BITRATE);

        return clip((bitRate * (1 + dt.ordinal())) / 3200,
                nChannels * lc3_min_frame_bytes(dt, sr),
                nChannels * lc3_max_frame_bytes(dt, sr));
    }

    private static int lc3_frame_bock_bytes(int dt_us, int nChannels, int bitRate) {
        return lc3_hr_frame_block_bytes(false, dt_us, 8000, nChannels, bitRate);
    }

    private static int lc3_hr_frame_bytes(boolean hrMode, int dt_us, int sr_hz, int bitRate) {
        return lc3_hr_frame_block_bytes(hrMode, dt_us, sr_hz, 1, bitRate);
    }

    private static int lc3_frame_bytes(int dt_us, int bitRate) {
        return lc3_hr_frame_bytes(false, dt_us, 8000, bitRate);
    }

    /**
     * Resolve the bitrate, from the size of frames or frame blocks
     */
    private static int lc3_hr_resolve_bitrate(boolean hrMode, int dt_us, int sr_hz, int nBytes) {
        Duration dt = resolve_dt(dt_us, hrMode);
        SRate sr = resolve_srate(sr_hz, hrMode);

        if (dt == null || sr == null || nBytes < 0)
            return -1;

        return (int) Math.min(((long) nBytes * 3200 + dt.ordinal()) / (1 + dt.ordinal()), Integer.MAX_VALUE);
    }

    private static int lc3_resolve_bitrate(int dt_us, int nBytes) {
        return lc3_hr_resolve_bitrate(false, dt_us, 8000, nBytes);
    }

    /**
     * Return algorithmic delay, as a number of samples
     */
    static int lc3_hr_delay_samples(boolean hrMode, int dt_us, int sr_hz) {
        Duration dt = resolve_dt(dt_us, hrMode);
        SRate sr = resolve_srate(sr_hz, hrMode);

        if (dt == null || sr == null)
            return -1;

        return 2 * lc3_nd(dt, sr) - lc3_ns(dt, sr);
    }

    private static int lc3_delay_samples(int dt_us, int sr_hz) {
        return lc3_hr_delay_samples(false, dt_us, sr_hz);
    }

    //
    // Encoder
    //

    /**
     * Frame Analysis
     *
     * @param encoder Encoder state
     * @param nBytes  Size in bytes of the frame
     * @param side    Return frame data
     */
    static void analyze(Encoder encoder, int nBytes, Side side) {
        Duration dt = encoder.dt;
        SRate sr = encoder.sr;
        SRate sr_pcm = encoder.sr_pcm;

        int xt = encoder.xt_off; // encoder.x
        int xs = encoder.xs_off; // encoder.x
        int ns = lc3_ns(dt, sr_pcm);
        int nt = lc3_nt(sr_pcm);

        int xd = encoder.xd_off; // encoder.x
        int xf = xs;

        // Temporal

        boolean att = lc3_attdet_run(dt, sr_pcm, nBytes, encoder.attdet, null /* encoder.x */, xt); // TODO implement null

        side.pitch_present = side.ltpf.lc3_ltpf_analyse(dt, sr_pcm, encoder.ltpf, null /* encoder.x */, xt); // TODO implement null

        System.arraycopy(encoder.x, xt + (ns - nt), encoder.x, xt - nt, nt);
//        Arrays.fill(encoder.x, xt + (ns - nt), nt, 0);

        // Spectral

        float[] e = new float[LC3_MAX_BANDS];

        forward(dt, sr_pcm, sr, encoder.x, xs, encoder.x, xd, encoder.x, xf);

        boolean nn_flag = lc3_energy_compute(dt, sr, encoder.x, xf, e);
        if (nn_flag)
            side.ltpf.lc3_ltpf_disable();

        side.bw = lc3_bwdet_run(dt, sr, e);

        side.sns.lc3_sns_analyze(dt, sr, nBytes, e, att, encoder.x, xf, encoder.x, xf);

        side.tns.lc3_tns_analyze(dt, side.bw, nn_flag, nBytes, encoder.x, xf);

        side.spec.lc3_spec_analyze(dt, sr, nBytes, side.pitch_present, side.tns, encoder.spec, encoder.x, xf);
    }

    /**
     * Encode bitstream
     *
     * @param encoder Encoder state
     * @param side    The frame data
     * @param nBytes  Target size of the frame (20 to 400)
     * @param buffer  Output bitstream buffer of `nBytes` size
     */
    static void encode(Encoder encoder, Side side, int nBytes, byte[] buffer, int offset) {
        Duration dt = encoder.dt;
        SRate sr = encoder.sr;

        int xf = encoder.xs_off; // encoder.x
        BandWidth bw = side.bw;

        Bits bits = new Bits(Mode.WRITE, buffer, offset, nBytes);

        lc3_bwdet_put_bw(bits, sr, bw);

        side.spec.lc3_spec_put_side(bits, dt, sr);

        side.tns.lc3_tns_put_data(bits);

        bits.lc3_put_bit(side.pitch_present ? 1 : 0);

        side.sns.lc3_sns_put_data(bits);

        if (side.pitch_present)
            side.ltpf.lc3_ltpf_put_data(bits);

        side.spec.lc3_spec_encode(bits, dt, sr, bw, nBytes, encoder.x, xf);

        bits.lc3_flush_bits();
    }

    /**
     * Return size needed for an encoder
     */
    static int lc3_hr_encoder_size(boolean hrMode, int dt_us, int sr_hz) {
        Duration dt = resolve_dt(dt_us, hrMode);
        SRate sr = resolve_srate(sr_hz, hrMode);
        if (dt == null || sr == null)
            return 0;

        return LC3_ENCODER_BUFFER_COUNT(dt_us, sr_hz) - 1;
    }

    static int lc3_encoder_size(int dt_us, int sr_hz) {
        return lc3_hr_encoder_size(false, dt_us, sr_hz);
    }

    /**
     * Setup encoder
     * @param mem unused
     */
    static Encoder lc3_hr_setup_encoder(boolean hrMode, int dt_us, int sr_hz, int sr_pcm_hz, byte[] mem) {
        if (sr_pcm_hz <= 0)
            sr_pcm_hz = sr_hz;

        Duration dt = resolve_dt(dt_us, hrMode);
        SRate sr = resolve_srate(sr_hz, hrMode);
        SRate sr_pcm = resolve_srate(sr_pcm_hz, hrMode);

        if (dt == null || sr_pcm == null || sr != null && sr.ordinal() > sr_pcm.ordinal() || mem == null)
            return null;

        int ns = lc3_ns(dt, sr_pcm);
        int nt = lc3_nt(sr_pcm);

        Encoder encoder = new Encoder();
        encoder.dt = dt;
        encoder.sr = sr;
        encoder.sr_pcm = sr_pcm;

        encoder.xt_off = nt;
        encoder.xs_off = (nt + ns) / 2;
        encoder.xd_off = (nt + ns) / 2 + ns;

        encoder.x = new float[LC3_ENCODER_BUFFER_COUNT(dt_us, sr_pcm_hz)];

        return encoder;
    }

    static Encoder lc3_setup_encoder(int dt_us, int sr_hz, int sr_pcm_hz, byte[] mem) {
        return lc3_hr_setup_encoder(false, dt_us, sr_hz, sr_pcm_hz, mem);
    }

    /**
     * Encode a frame
     */
    static int lc3_encode(Encoder encoder, lc3_pcm_format fmt, byte[] pcm, int stride, int nbytes, byte[] out, int outP) {
        // Check parameters

        if (encoder == null || nbytes < lc3_min_frame_bytes(encoder.dt, encoder.sr)
                || nbytes > lc3_max_frame_bytes(encoder.dt, encoder.sr)) {
            return -1;
        }

        // Processing

        Side side = new Side();

        fmt.load(encoder, pcm, stride);

        analyze(encoder, nbytes, side);

        encode(encoder, side, nbytes, out, outP);

        return 0;
    }

    //
    // Decoder
    //

    /**
     * Decode bitstream
     *
     * @param decoder Decoder state
     * @param data,   nBytes    Input bitstream buffer
     * @param side    Return the side data
     * @return 0: Ok  < 0: Bitsream error detected
     */
    static int decode(Decoder decoder, byte[] data, int dataP, int nBytes, Side side) {
        Duration dt = decoder.dt;
        SRate sr = decoder.sr;

        int xf = decoder.xs_off; // decoder.x
        int ns = lc3_ns(dt, sr);
        int ne = lc3_ne(dt, sr);

        int ret = 0;

        Bits bits = new Bits(Mode.READ, data, dataP, nBytes);

        BandWidth[] bw = new BandWidth[1];
        if ((ret = lc3_bwdet_get_bw(bits, sr, bw)) < 0) {
            return ret;
        }
        side.bw = bw[0];

        if ((ret = side.spec.lc3_spec_get_side(bits, dt, sr)) < 0) {
            return ret;
        }

        if ((ret = side.tns.lc3_tns_get_data(bits, dt, side.bw, nBytes)) < 0) {
            return ret;
        }

        side.pitch_present = bits.lc3_get_bit() != 0;

        side.sns = new Sns(bits);

        if (side.pitch_present)
            side.ltpf.lc3_ltpf_get_data(bits);

        if ((ret = side.spec.lc3_spec_decode(bits, dt, sr, side.bw, nBytes, decoder.x, xf)) < 0) {
            return ret;
        }

        Arrays.fill(decoder.x, xf + ne, (xf + ne) + (ns - ne), 0);

        return bits.lc3_check_bits();
    }

    /**
     * Frame synthesis
     *
     * @param decoder Decoder state
     * @param side    Frame data, null performs PLC
     * @param nBytes  Size in bytes of the frame
     */
    static void synthesize(Decoder decoder, Side side, int nBytes) {
        Duration dt = decoder.dt;
        SRate sr = decoder.sr;
        SRate sr_pcm = decoder.sr_pcm;

        int xf = decoder.xs_off; // decoder.x
        int ns = lc3_ns(dt, sr_pcm);
        int ne = lc3_ne(dt, sr);

        int xg = decoder.xg_off; // decoder.x
        int xs = xf; // decoder.x

        int xd = decoder.xd_off; // decoder.x
        int xh = decoder.xh_off; // decoder.x

        if (side != null) {
            BandWidth bw = side.bw;

            decoder.plc.lc3_plc_suspend();

            side.tns.lc3_tns_synthesize(dt, bw, decoder.x, xf);

            side.sns.lc3_sns_synthesize(dt, sr, decoder.x, xf, decoder.x, xg);

            inverse(dt, sr_pcm, sr, decoder.x, xg, decoder.x, xd, decoder.x, xs);

        } else {
            decoder.plc.lc3_plc_synthesize(dt, sr, decoder.x, xg, decoder.x, xf);

            Arrays.fill(decoder.x, xf + ne, (xf + ne) + (ns - ne), 0);

            inverse(dt, sr_pcm, sr, decoder.x, xf, decoder.x, xd, decoder.x, xs);
        }

        if (!isHR(sr))
            lc3_ltpf_synthesize(dt, sr_pcm, nBytes, decoder.ltpf,
                    side != null && side.pitch_present ? side.ltpf : null, xh, decoder.x, xs);
    }

    /**
     * Update decoder state on decoding completion
     *
     * @param decoder Decoder state
     */
    static void complete(Decoder decoder) {
        Duration dt = decoder.dt;
        SRate sr_pcm = decoder.sr_pcm;
        int nh = lc3_nh(dt, sr_pcm);
        int ns = lc3_ns(dt, sr_pcm);

        decoder.xs_off = decoder.xs_off - decoder.xh_off < nh ? decoder.xs_off + ns : decoder.xh_off;
    }

    /**
     * Return size needed for a decoder
     */
    static int lc3_hr_decoder_size(boolean hrMode, int dt_us, int sr_hz) {
        Duration dt = resolve_dt(dt_us, hrMode);
        SRate sr = resolve_srate(sr_hz, hrMode);
        if (dt == null || sr == null)
            return 0;

        return LC3_DECODER_BUFFER_COUNT(dt_us, sr_hz) - 1;
    }

    static int lc3_decoder_size(int dt_us, int sr_hz) {
        return lc3_hr_decoder_size(false, dt_us, sr_hz);
    }

    /**
     * Setup decoder
     */
    static Decoder lc3_hr_setup_decoder(boolean hrMode, int dt_us, int sr_hz, int sr_pcm_hz) {
        if (sr_pcm_hz <= 0)
            sr_pcm_hz = sr_hz;

        Duration dt = resolve_dt(dt_us, hrMode);
        SRate sr = resolve_srate(sr_hz, hrMode);
        SRate sr_pcm = resolve_srate(sr_pcm_hz, hrMode);

        if (dt == null || sr_pcm == null || (sr != null && sr.ordinal() > sr_pcm.ordinal()))
            return null;

        int nh = lc3_nh(dt, sr_pcm);
        int ns = lc3_ns(dt, sr_pcm);
        int nd = lc3_nd(dt, sr_pcm);

        Decoder decoder = new Decoder();
        decoder.dt = dt;
        decoder.sr = sr;
        decoder.sr_pcm = sr_pcm;

        decoder.xh_off = 0;
        decoder.xs_off = nh;
        decoder.xd_off = nh + ns;
        decoder.xg_off = nh + ns + nd;

        decoder.plc = new Plc();
        decoder.plc.lc3_plc_reset();

        decoder.x = new float[LC3_DECODER_BUFFER_COUNT(dt_us, sr_pcm_hz)];
logger.log(Level.DEBUG, decoder.x.length);

        return decoder;
    }

    static Decoder lc3_setup_decoder(int dt_us, int sr_hz, int sr_pcm_hz) {
        return lc3_hr_setup_decoder(false, dt_us, sr_hz, sr_pcm_hz);
    }

    /**
     * Decode a frame
     */
    static boolean lc3_decode(Decoder decoder, byte[] in, int inp, int nBytes, lc3_pcm_format fmt, byte[] pcm, int op, int stride) {

        // Check parameters

        if (decoder == null)
            return false;

        if (in != null && (nBytes < LC3_MIN_FRAME_BYTES ||
                nBytes > lc3_max_frame_bytes(decoder.dt, decoder.sr)))
            return false;

        // Processing

        Side side = new Side();

        boolean ret = in == null || decode(decoder, in, inp, nBytes, side) < 0;

        synthesize(decoder, ret ? null : side, nBytes);

        fmt.store(decoder, pcm, op, stride);

        complete(decoder);

        return ret;
    }

//#endregion
}