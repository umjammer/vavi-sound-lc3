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
 *   in constrained (on packet sizes and interval) tranport layer.
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
 *
 * In addition to LC3, following features of LC3 Plus are proposed:
 * <ul>
 * <li> Frame duration of 2.5 and 5ms.</li>
 * <li> High-Resolution mode, 48 KHz, and 96 kHz sampling rates.</li>
 * </ul>
 *
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
 *   <li> A NULL memory adress as input, will return a NULL encoder context.</li>
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
    private static final int LC3_HR_MAX_FRAME_BYTES = 625;

    private static final int LC3_MIN_FRAME_SAMPLES = 8000;
    private static final int LC3_MAX_FRAME_SAMPLES = 48000;
    private static final int LC3_HR_MAX_FRAME_SAMPLES = 96000;

    /**
     * PCM Sample Format
     * S16      Signed 16 bits, in 16 bits words (int16_t)
     * S24      Signed 24 bits, using low three bytes of 32 bits words (int32_t).
     * The high byte sign extends (bits 31..24 set to b23).
     * S24_3LE  Signed 24 bits packed in 3 bytes little endian
     * FLOAT    Floating point 32 bits (float type), in range -1 to 1
     */
    private enum lc3_pcm_format {
        LC3_PCM_FORMAT_S16,
        LC3_PCM_FORMAT_S24,
        LC3_PCM_FORMAT_S24_3LE,
        LC3_PCM_FORMAT_FLOAT,
    }
}