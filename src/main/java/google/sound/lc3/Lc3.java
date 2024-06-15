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


import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.Arrays;
import java.util.Map;

import google.sound.lc3.AttDet.Analysis;
import google.sound.lc3.Bits.Mode;
import google.sound.lc3.Ltpf.Synthesis;
import google.sound.lc3.Ltpf.lc3_ltpf_data;

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
import static google.sound.lc3.Ltpf.lc3_ltpf_analyse;
import static google.sound.lc3.Ltpf.lc3_ltpf_disable;
import static google.sound.lc3.Ltpf.lc3_ltpf_get_data;
import static google.sound.lc3.Ltpf.lc3_ltpf_put_data;
import static google.sound.lc3.Ltpf.lc3_ltpf_synthesize;
import static google.sound.lc3.Mdct.lc3_mdct_forward;
import static google.sound.lc3.Mdct.lc3_mdct_inverse;
import static google.sound.lc3.Tables.lc3_max_frame_bytes;
import static google.sound.lc3.Tables.lc3_min_frame_bytes;
import static google.sound.lc3.Tables.lc3_nd;
import static google.sound.lc3.Tables.lc3_ne;
import static google.sound.lc3.Tables.lc3_nh;
import static google.sound.lc3.Tables.lc3_ns;
import static google.sound.lc3.Tables.lc3_nt;
import static google.sound.lc3.Tns.lc3_tns_analyze;
import static google.sound.lc3.Tns.lc3_tns_get_data;
import static google.sound.lc3.Tns.lc3_tns_put_data;
import static google.sound.lc3.Tns.lc3_tns_synthesize;


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
     * selection of the high-resolution mode `hrmode`.
     */
    static boolean LC3_HR_CHECK_SR_HZ(boolean hrmode, int sr) {
        return hrmode ? sr == 48000 || sr == 96000 : LC3_CHECK_SR_HZ(sr);
    }

    /*
     * Activation flags for LC3-Plus and LC3-Plus HR features
     */

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

    /*
     * Hot Function attribute
     * Selectively disable sanitizer
     */

    static boolean LC3_PLUS_HR = true;

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

    static int clip(int v, int min, int max) {
        return Math.min(Math.max(v, min), max);
    }

    static int LC3_SAT16(int v) { return clip(v, -(1 << 15), (1 << 15) - 1); }
    static int LC3_SAT24(int v) { return clip(v, -(1 << 23), (1 << 23) - 1); }

    /**
     * Return `true` when high-resolution mode
     */
    static boolean lc3_hr(SRate sr) {
        return LC3_PLUS_HR && (sr.ordinal() >= _48K_HR.ordinal());
    }

    /**
     * Return `true` when high-resolution mode
     */
    static boolean isHR(SRate sr) {
        return LC3_PLUS_HR && (sr.ordinal() >= _48K_HR.ordinal());
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
        LC3_PCM_FORMAT_S16,
        LC3_PCM_FORMAT_S24,
        LC3_PCM_FORMAT_S24_3LE,
        LC3_PCM_FORMAT_FLOAT,
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

    static class lc3_encoder {

        Duration dt;
        SRate sr, sr_pcm;

        Analysis attdet;
        Ltpf.Analysis ltpf;
        Spec.Analysis spec;

        int xt_off, xs_off, xd_off;
        float[] x = new float[1];
    }

    static int LC3_ENCODER_BUFFER_COUNT(int dt_us, int sr_hz) {
        return ((LC3_NS(dt_us, sr_hz) + LC3_NT(sr_hz)) / 2 + LC3_NS(dt_us, sr_hz) + LC3_ND(dt_us, sr_hz));
    }

    static lc3_encoder LC3_ENCODER_MEM_T(int dt_us, int sr_hz) {
        return new lc3_encoder() {
            lc3_encoder __e;
            float[] __x = new float[LC3_ENCODER_BUFFER_COUNT(dt_us, sr_hz) - 1];
        };
    }

    static class lc3_decoder {

        Duration dt;
        SRate sr, sr_pcm;

        Synthesis ltpf;
        Plc plc;

        int xh_off, xs_off, xd_off, xg_off;
        float[] x = new float[1];
    }

    static int LC3_DECODER_BUFFER_COUNT(int dt_us, int sr_hz) {
        return LC3_NH(dt_us, sr_hz) + LC3_NS(dt_us, sr_hz) +
                LC3_ND(dt_us, sr_hz) + LC3_NS(dt_us, sr_hz);
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

    interface TriConsumer<T, U, V> {

        void accept(T t, U u, V v);
    }

    interface TetraConsumer<T, U, V, W> {

        void accept(T t, U u, V v, W w);
    }

    interface PentaConsumer<T, U, V, W, X> {

        void accept(T t, U u, V v, W w, X x);
    }

    interface HexaConsumer<T, U, V, W, X, Y> {

        void accept(T t, U u, V v, W w, X x, Y y);
    }

    interface DecaConsumer<T, U, V, W, X, Y, Z, A, B, C> {

        void accept(T t, U u, V v, W w, X x, Y y, Z z, A a, B b, C c);
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
    }

    /**
     * Frame side data
     */
    static class side_data {

        BandWidth bw;
        boolean pitch_present;
        lc3_ltpf_data ltpf;
        Sns sns;
        Tns tns;
        Spec spec;
    }

    //
    // General
    //

    /**
     * Resolve frame duration in us
     *
     * @param us     Frame duration in us
     * @param hrmode High-resolution mode indication
     * @return Frame duration identifier, or null
     */
    private static Duration resolve_dt(int us, boolean hrmode) {
        return us == 2500 ? Duration._2M5 :
                us == 5000 ? Duration._5M :
                        !hrmode && us == 7500 ? Duration._7M5 :
                                us == 10000 ? Duration._10M : null;
    }

    /**
     * Resolve samplerate in Hz
     *
     * @param hz     Samplerate in Hz
     * @param hrmode High-resolution mode indication
     * @return Sample rate identifier, or null
     */
    private static SRate resolve_srate(int hz, boolean hrmode) {
        hrmode = LC3_PLUS_HR && hrmode;

        return !hrmode && hz == 8000 ? _8K :
                !hrmode && hz == 16000 ? _16K :
                        !hrmode && hz == 24000 ? _24K :
                                !hrmode && hz == 32000 ? _32K :
                                        !hrmode && hz == 48000 ? _48K :
                                                hrmode && hz == 48000 ? _48K_HR :
                                                        hrmode && hz == 96000 ? _96K_HR : null;
    }

    /**
     * Return the number of PCM samples in a frame
     */
    static int lc3_hr_frame_samples(boolean hrmode, int dt_us, int sr_hz) {
        Duration dt = resolve_dt(dt_us, hrmode);
        SRate sr = resolve_srate(sr_hz, hrmode);

        if (dt.ordinal() >= Duration.values().length || sr.ordinal() >= SRate.values().length)
            return -1;

        return lc3_ns(dt, sr);
    }

    private static int lc3_frame_samples(int dt_us, int sr_hz) {
        return lc3_hr_frame_samples(false, dt_us, sr_hz);
    }

    /**
     * Return the size of frames or frame blocks, from bitrate
     */
    private static int lc3_hr_frame_block_bytes(boolean hrmode, int dt_us, int sr_hz, int nchannels, int bitrate) {
        Duration dt = resolve_dt(dt_us, hrmode);
        SRate sr = resolve_srate(sr_hz, hrmode);

        if (dt.ordinal() >= Duration.values().length || sr.ordinal() >= SRate.values().length
                || nchannels < 1 || nchannels > 8 || bitrate < 0)
            return -1;

        bitrate = clip(bitrate, 0, 8 * LC3_HR_MAX_BITRATE);

        return clip((bitrate * (1 + dt.ordinal())) / 3200,
                nchannels * lc3_min_frame_bytes(dt, sr),
                nchannels * lc3_max_frame_bytes(dt, sr));
    }

    private static int lc3_frame_bock_bytes(int dt_us, int nchannels, int bitrate) {
        return lc3_hr_frame_block_bytes(false, dt_us, 8000, nchannels, bitrate);
    }

    private static int lc3_hr_frame_bytes(boolean hrmode, int dt_us, int sr_hz, int bitrate) {
        return lc3_hr_frame_block_bytes(hrmode, dt_us, sr_hz, 1, bitrate);
    }

    private static int lc3_frame_bytes(int dt_us, int bitrate) {
        return lc3_hr_frame_bytes(false, dt_us, 8000, bitrate);
    }

    /**
     * Resolve the bitrate, from the size of frames or frame blocks
     */
    private static int lc3_hr_resolve_bitrate(boolean hrmode, int dt_us, int sr_hz, int nbytes) {
        Duration dt = resolve_dt(dt_us, hrmode);
        SRate sr = resolve_srate(sr_hz, hrmode);

        if (dt.ordinal() >= Duration.values().length || sr.ordinal() >= SRate.values().length || nbytes < 0)
            return -1;

        return (int) Math.min(((long) nbytes * 3200 + dt.ordinal()) / (1 + dt.ordinal()), Integer.MAX_VALUE);
    }

    private static int lc3_resolve_bitrate(int dt_us, int nbytes) {
        return lc3_hr_resolve_bitrate(false, dt_us, 8000, nbytes);
    }

    /**
     * Return algorithmic delay, as a number of samples
     */
    static int lc3_hr_delay_samples(boolean hrmode, int dt_us, int sr_hz) {
        Duration dt = resolve_dt(dt_us, hrmode);
        SRate sr = resolve_srate(sr_hz, hrmode);

        if (dt.ordinal() >= Duration.values().length || sr.ordinal() >= SRate.values().length)
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
     * Input PCM Samples from signed 16 bits
     *
     * @param encoder Encoder state
     * @param _pcm,    stride     Input PCM samples, and count between two consecutives
     */
    static void load_s16(lc3_encoder encoder, byte[] _pcm, int stride) {
        ShortBuffer pcm = ByteBuffer.wrap(_pcm).asShortBuffer();

        Duration dt = encoder.dt;
        SRate sr = encoder.sr_pcm;

        int xt = encoder.xt_off; // encoder.x
        int xs = encoder.xs_off; // encoder.x
        int ns = lc3_ns(dt, sr);

        for (int i = 0; i < ns; i++/*, pcm += stride */) {
            encoder.x[xt + i] = pcm.get();
            encoder.x[xs + i] = pcm.get();
        }
    }

    /**
     * Input PCM Samples from signed 24 bits
     *
     * @param encoder Encoder state
     * @param _pcm,    stride     Input PCM samples, and count between two consecutives
     */
    static void load_s24(lc3_encoder encoder, byte[] _pcm, int stride) {
        IntBuffer pcm = ByteBuffer.wrap(_pcm).asIntBuffer();

        Duration dt = encoder.dt;
        SRate sr = encoder.sr_pcm;

        int xt = encoder.xt_off; // encoder.x
        int xs = encoder.xs_off; // encoder.x
        int ns = lc3_ns(dt, sr);

        for (int i = 0; i < ns; i++/*, pcm += stride */) {
            encoder.x[xt + i] = pcm.get() >> 8;
            encoder.x[xs + i] = lc3_ldexpf(pcm.get(), -8);
        }
    }

    /**
     * Input PCM Samples from signed 24 bits packed
     *
     * @param encoder Encoder state
     * @param _pcm,    stride     Input PCM samples, and count between two consecutives
     */
    static void load_s24_3le(lc3_encoder encoder, byte[] _pcm, int stride) {
        byte[] pcm = _pcm;

        Duration dt = encoder.dt;
        SRate sr = encoder.sr_pcm;

        int xt = encoder.xt_off; // encoder.x
        int xs = encoder.xs_off; // encoder.x
        int ns = lc3_ns(dt, sr);

        for (int i = 0; i < ns; i++/*, pcm += 3 * stride */) {
            int in = ((int) pcm[0] << 8) |
                    ((int) pcm[1] << 16) |
                    ((int) pcm[2] << 24);

            encoder.x[xt + i] = in >> 16;
            encoder.x[xs + i] = lc3_ldexpf(in, -16);
        }
    }

    /**
     * Input PCM Samples from float 32 bits
     *
     * @param encoder Encoder state
     * @param _pcm,    stride     Input PCM samples, and count between two consecutives
     */
    static void load_float(lc3_encoder encoder, byte[] _pcm, int stride) {
        FloatBuffer pcm = ByteBuffer.wrap(_pcm).asFloatBuffer();

        Duration dt = encoder.dt;
        SRate sr = encoder.sr_pcm;

        int xt = encoder.xt_off; // encoder.x
        int xs = encoder.xs_off; // encoder.x
        int ns = lc3_ns(dt, sr);

        for (int i = 0; i < ns; i++/* , pcm += stride */) {
            encoder.x[xs + i] = lc3_ldexpf(pcm.get(), 15);
            encoder.x[xt + i] = LC3_SAT16((int) encoder.x[xs + i]);
        }
    }

    /**
     * Frame Analysis
     *
     * @param encoder Encoder state
     * @param nbytes  Size in bytes of the frame
     * @param side    Return frame data
     */
    static void analyze(lc3_encoder encoder, int nbytes, side_data side) {
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

        boolean att = lc3_attdet_run(dt, sr_pcm, nbytes, encoder.attdet, null /* encoder.x */, xt);

        side.pitch_present = lc3_ltpf_analyse(dt, sr_pcm, encoder.ltpf, null /* encoder.x */, xt, side.ltpf);

        System.arraycopy(encoder.x, xt + (ns - nt), encoder.x, xt - nt, nt);
        Arrays.fill(encoder.x, xt + (ns - nt), nt, 0);

        // Spectral

        float[] e = new float[LC3_MAX_BANDS];

        lc3_mdct_forward(dt, sr_pcm, sr, encoder.x, xs, encoder.x, xd, encoder.x, xf);

        boolean nn_flag = lc3_energy_compute(dt, sr, encoder.x, xf, e);
        if (nn_flag)
            lc3_ltpf_disable(side.ltpf);

        side.bw = lc3_bwdet_run(dt, sr, e);

        side.sns.lc3_sns_analyze(dt, sr, nbytes, e, att, encoder.x, xf, encoder.x, xf);

        lc3_tns_analyze(dt, side.bw, nn_flag, nbytes, side.tns, encoder.x, xf);

        side.spec.lc3_spec_analyze(dt, sr, nbytes, side.pitch_present, side.tns, encoder.spec, encoder.x, xf);
    }

    /**
     * Encode bitstream
     *
     * @param encoder Encoder state
     * @param side    The frame data
     * @param nbytes  Target size of the frame (20 to 400)
     * @param buffer  Output bitstream buffer of `nbytes` size
     */
    static void encode(lc3_encoder encoder, side_data side, int nbytes, byte[] buffer) {
         Duration dt = encoder.dt;
         SRate sr = encoder.sr;

         int xf = encoder.xs_off; // encoder.x
         BandWidth bw = side.bw;

         Bits bits = new Bits(Mode.WRITE, buffer, nbytes);

         lc3_bwdet_put_bw(bits, sr, bw);

         side.spec.lc3_spec_put_side(bits, dt, sr);

         lc3_tns_put_data(bits, side.tns);

         bits.lc3_put_bit(side.pitch_present ? 1 : 0);

         side.sns.lc3_sns_put_data(bits);

         if (side.pitch_present)
             lc3_ltpf_put_data(bits, side.ltpf);

         side.spec.lc3_spec_encode(bits, dt, sr, bw, nbytes, encoder.x, xf);

         bits.lc3_flush_bits();
     }

    /**
     * Return size needed for an encoder
     */
    static int lc3_hr_encoder_size(boolean hrmode, int dt_us, int sr_hz) {
        if (resolve_dt(dt_us, hrmode).ordinal() >= Duration.values().length ||
                resolve_srate(sr_hz, hrmode).ordinal() >= SRate.values().length)
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
    static lc3_encoder lc3_hr_setup_encoder(boolean hrmode, int dt_us, int sr_hz, int sr_pcm_hz, byte[] mem) {
        if (sr_pcm_hz <= 0)
            sr_pcm_hz = sr_hz;

        Duration dt = resolve_dt(dt_us, hrmode);
        SRate sr = resolve_srate(sr_hz, hrmode);
        SRate sr_pcm = resolve_srate(sr_pcm_hz, hrmode);

        if (dt.ordinal() >= Duration.values().length || sr_pcm.ordinal() >= SRate.values().length || sr.ordinal() > sr_pcm.ordinal() || mem == null)
            return null;

        int ns = lc3_ns(dt, sr_pcm);
        int nt = lc3_nt(sr_pcm);

        lc3_encoder encoder = new lc3_encoder();
        encoder.dt = dt;
        encoder.sr = sr;
        encoder.sr_pcm = sr_pcm;

        encoder.xt_off = nt;
        encoder.xs_off = (nt + ns) / 2;
        encoder.xd_off = (nt + ns) / 2 + ns;

        encoder.x = new float[LC3_ENCODER_BUFFER_COUNT(dt_us, sr_pcm_hz)];

        return encoder;
    }

    static lc3_encoder lc3_setup_encoder(int dt_us, int sr_hz, int sr_pcm_hz, byte[] mem) {
        return lc3_hr_setup_encoder(false, dt_us, sr_hz, sr_pcm_hz, mem);
    }

    static final Map<lc3_pcm_format, TriConsumer<lc3_encoder, byte[], Integer>> load = Map.of(
            lc3_pcm_format.LC3_PCM_FORMAT_S16, Lc3::load_s16,
            lc3_pcm_format.LC3_PCM_FORMAT_S24, Lc3::load_s24,
            lc3_pcm_format.LC3_PCM_FORMAT_S24_3LE, Lc3::load_s24_3le,
            lc3_pcm_format.LC3_PCM_FORMAT_FLOAT, Lc3::load_float
    );

    /**
     * Encode a frame
     */
    static int lc3_encode(lc3_encoder encoder, lc3_pcm_format fmt, final byte[] pcm, int stride, int nbytes, byte[] out) {
        // Check parameters

        if (encoder == null || nbytes < lc3_min_frame_bytes(encoder.dt, encoder.sr)
                || nbytes > lc3_max_frame_bytes(encoder.dt, encoder.sr))
            return -1;

        // Processing

        side_data side = new side_data();

        load.get(fmt).accept(encoder, pcm, stride);

        analyze(encoder, nbytes, side);

        encode(encoder, side, nbytes, out);

        return 0;
    }

    //
    // Decoder
    //

    /**
     * Output PCM Samples to signed 16 bits
     *
     * @param decoder Decoder state
     * @param _pcm,   stride     Output PCM samples, and count between two consecutives
     */
    static void store_s16(lc3_decoder decoder, byte[] _pcm, int op, int stride) {
        ShortBuffer pcm = ByteBuffer.wrap(_pcm, op, _pcm.length - op).asShortBuffer();

        Duration dt = decoder.dt;
        SRate sr = decoder.sr_pcm;

        int xs = decoder.xs_off; // decoder.x
        int ns = lc3_ns(dt, sr);

        for (; ns > 0; ns--, xs++/*, pcm += stride */) {
            int s = decoder.x[xs] >= 0 ? (int) (decoder.x[xs] + 0.5f) :(int) (decoder.x[xs] - 0.5f);
            pcm.put((short) LC3_SAT16(s));
        }
    }

    /**
     * Output PCM Samples to signed 24 bits
     *
     * @param decoder Decoder state
     * @param _pcm,   stride     Output PCM samples, and count between two consecutives
     */
    static void store_s24(lc3_decoder decoder, byte[] _pcm, int op, int stride) {
        IntBuffer pcm = ByteBuffer.wrap(_pcm, op, _pcm.length - op).asIntBuffer();

        Duration dt = decoder.dt;
        SRate sr = decoder.sr_pcm;

        int xs = decoder.xs_off; // decoder.x
        int ns = lc3_ns(dt, sr);

        for (; ns > 0; ns--, xs++/*, pcm += stride */) {
            int s = decoder.x[xs] >= 0
                    ? (int) (lc3_ldexpf(decoder.x[xs], 8) + 0.5f)
                    : (int) (lc3_ldexpf(decoder.x[xs], 8) - 0.5f);
            pcm.put(LC3_SAT24(s));
        }
    }

    /**
     * Output PCM Samples to signed 24 bits packed
     *
     * @param decoder Decoder state
     * @param _pcm,   stride     Output PCM samples, and count between two consecutives
     */
    static void store_s24_3le(lc3_decoder decoder, byte[] _pcm, int op, int stride) {
        byte[] pcm = Arrays.copyOfRange(_pcm, op, _pcm.length - op);

        Duration dt = decoder.dt;
        SRate sr = decoder.sr_pcm;

        int xs = decoder.xs_off; // decoder.x
        int ns = lc3_ns(dt, sr);

        int pcmp = 0;
        for (; ns > 0; ns--, xs++, pcmp += 3 * stride) {
            int s = decoder.x[xs] >= 0 ? (int) (lc3_ldexpf(decoder.x[xs], 8)+0.5f)
                             :(int) (lc3_ldexpf(decoder.x[xs], 8)-0.5f);

            s = LC3_SAT24(s);
            pcm[pcmp + 0] = (byte) ((s >> 0) & 0xff);
            pcm[pcmp + 1] = (byte) ((s >> 8) & 0xff);
            pcm[pcmp + 2] = (byte) ((s >> 16) & 0xff);
        }
    }

    /**
     * Output PCM Samples to float 32 bits
     *
     * @param decoder Decoder state
     * @param _pcm,    stride     Output PCM samples, and count between two consecutives
     */
    static void store_float(lc3_decoder decoder, byte[] _pcm, int op, int stride) {
        FloatBuffer pcm = ByteBuffer.wrap(_pcm, op, _pcm.length - op).asFloatBuffer();

        Duration dt = decoder.dt;
        SRate sr = decoder.sr_pcm;

        int xs = decoder.xs_off; // decoder.x
        int ns = lc3_ns(dt, sr);

        for (; ns > 0; ns--, xs++/*, pcm += stride */) {
            float s = lc3_ldexpf(decoder.x[xs], -15);
            pcm.put(Math.min(Math.max(s, -1.f), 1.f));
        }
    }

    /**
     * Decode bitstream
     *
     * @param decoder Decoder state
     * @param data,   nbytes    Input bitstream buffer
     * @param side    Return the side data
     * @return 0: Ok  < 0: Bitsream error detected
     */
    static int decode(lc3_decoder decoder, byte[] data, int nbytes, side_data side) {
        Duration dt = decoder.dt;
        SRate sr = decoder.sr;

        int xf = decoder.xs_off; // decoder.x
        int ns = lc3_ns(dt, sr);
        int ne = lc3_ne(dt, sr);

        int ret = 0;

        Bits bits = new Bits(Bits.Mode.READ, (byte[]) data, nbytes);

        BandWidth[] bw = new BandWidth[1];
        if ((ret = lc3_bwdet_get_bw(bits, sr, bw)) < 0)
            return ret;
        side.bw = bw[0];

        if ((ret = side.spec.lc3_spec_get_side(bits, dt, sr)) < 0)
            return ret;

        if ((ret = lc3_tns_get_data(bits, dt, side.bw, nbytes, side.tns)) < 0)
            return ret;

        side.pitch_present = bits.lc3_get_bit() != 0;

        side.sns = new Sns(bits);

        if (side.pitch_present)
            lc3_ltpf_get_data(bits, side.ltpf);

        if ((ret = side.spec.lc3_spec_decode(bits, dt, sr, side.bw, nbytes, decoder.x, xf)) < 0)
            return ret;

        Arrays.fill(decoder.x, xf + ne, ns - ne, 0);

        return bits.lc3_check_bits();
    }

    /**
     * Frame synthesis
     *
     * @param decoder Decoder state
     * @param side    Frame data, null performs PLC
     * @param nbytes  Size in bytes of the frame
     */
    static void synthesize(lc3_decoder decoder, side_data side, int nbytes) {
        Duration dt = decoder.dt;
        SRate sr = decoder.sr;
        SRate sr_pcm = decoder.sr_pcm;

        int xf = decoder.xs_off; // decoder.x
        int ns = lc3_ns(dt, sr_pcm);
        int ne = lc3_ne(dt, sr);

        int xg = decoder.xg_off; // decoder.x
        int xs = xf;

        int xd = decoder.xd_off; // decoder.x
        int xh = decoder.xh_off; // decoder.x

        if (side != null) {
            BandWidth bw = side.bw;

            decoder.plc.lc3_plc_suspend();

            lc3_tns_synthesize(dt, bw, side.tns, decoder.x, xf);

            side.sns.lc3_sns_synthesize(dt, sr, decoder.x, xf, decoder.x, xg);

            lc3_mdct_inverse(dt, sr_pcm, sr, decoder.x, xg, decoder.x, xd, decoder.x, xs);

        } else {
            decoder.plc.lc3_plc_synthesize(dt, sr, decoder.x, xg, decoder.x, xf);

            Arrays.fill(decoder.x, xf + ne, ns - ne, 0);

            lc3_mdct_inverse(dt, sr_pcm, sr, decoder.x, xf, decoder.x, xd, decoder.x, xs);
        }

        if (lc3_hr(sr))
            lc3_ltpf_synthesize(dt, sr_pcm, nbytes, decoder.ltpf,
                    side != null & side.pitch_present ? side.ltpf : null, xh, decoder.x, xs);
    }

    /**
     * Update decoder state on decoding completion
     *
     * @param decoder Decoder state
     */
    static void complete(lc3_decoder decoder) {
        Duration dt = decoder.dt;
        SRate sr_pcm = decoder.sr_pcm;
        int nh = lc3_nh(dt, sr_pcm);
        int ns = lc3_ns(dt, sr_pcm);

        decoder.xs_off = decoder.xs_off - decoder.xh_off < nh ?
                decoder.xs_off + ns : decoder.xh_off;
    }

    /**
     * Return size needed for a decoder
     */
    static int lc3_hr_decoder_size(boolean hrmode, int dt_us, int sr_hz) {
        if (resolve_dt(dt_us, hrmode).ordinal() >= Duration.values().length ||
                resolve_srate(sr_hz, hrmode).ordinal() >= SRate.values().length)
            return 0;

        return LC3_DECODER_BUFFER_COUNT(dt_us, sr_hz) - 1;
    }

    static int lc3_decoder_size(int dt_us, int sr_hz) {
        return lc3_hr_decoder_size(false, dt_us, sr_hz);
    }

    /**
     * Setup decoder
     */
    static lc3_decoder lc3_hr_setup_decoder(boolean hrmode, int dt_us, int sr_hz, int sr_pcm_hz) {
        if (sr_pcm_hz <= 0)
            sr_pcm_hz = sr_hz;

        Duration dt = resolve_dt(dt_us, hrmode);
        SRate sr = resolve_srate(sr_hz, hrmode);
        SRate sr_pcm = resolve_srate(sr_pcm_hz, hrmode);

        if (dt.ordinal() >= Duration.values().length || sr_pcm.ordinal() >= SRate.values().length ||
                sr.ordinal() > sr_pcm.ordinal())
            return null;

        lc3_decoder decoder = new lc3_decoder();
        int nh = lc3_nh(dt, sr_pcm);
        int ns = lc3_ns(dt, sr_pcm);
        int nd = lc3_nd(dt, sr_pcm);

        decoder = new lc3_decoder();
        decoder.dt = dt;
        decoder.sr = sr;
        decoder.sr_pcm = sr_pcm;

        decoder.xh_off = 0;
        decoder.xs_off = nh;
        decoder.xd_off = nh + ns;
        decoder.xg_off = nh + ns + nd;

        decoder.plc = new Plc();
        decoder.plc.lc3_plc_reset();

        Arrays.fill(decoder.x, 0, LC3_DECODER_BUFFER_COUNT(dt_us, sr_pcm_hz), (float) 0);

        return decoder;
    }

    static lc3_decoder lc3_setup_decoder(int dt_us, int sr_hz, int sr_pcm_hz) {
        return lc3_hr_setup_decoder(false, dt_us, sr_hz, sr_pcm_hz);
    }

    static Map<lc3_pcm_format, TetraConsumer<lc3_decoder, byte[], Integer, Integer>> store = Map.of(
            lc3_pcm_format.LC3_PCM_FORMAT_S16, Lc3::store_s16,
            lc3_pcm_format.LC3_PCM_FORMAT_S24, Lc3::store_s24,
            lc3_pcm_format.LC3_PCM_FORMAT_S24_3LE, Lc3::store_s24_3le,
            lc3_pcm_format.LC3_PCM_FORMAT_FLOAT, Lc3::store_float
    );

    /**
     * Decode a frame
     */
    static boolean lc3_decode(lc3_decoder decoder, byte[] in, int ip, int nbytes, lc3_pcm_format fmt, byte[] pcm, int op, int stride) {

        // Check parameters

        if (decoder == null)
            return false;

        if (in != null & (nbytes < LC3_MIN_FRAME_BYTES ||
                nbytes > lc3_max_frame_bytes(decoder.dt, decoder.sr)))
            return false;

        // Processing

        side_data side = new side_data();

        boolean ret = in == null || (decode(decoder, in, nbytes, side) < 0);

        synthesize(decoder, ret ? null : side, nbytes);

        store.get(fmt).accept(decoder, pcm, op, stride);

        complete(decoder);

        return ret;
    }
}