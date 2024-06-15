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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioFormat.Encoding;

import google.sound.lc3.Lc3.lc3_decoder;
import google.sound.lc3.Lc3.lc3_pcm_format;

import static google.sound.lc3.Lc3.LC3_CHECK_DT_US;
import static google.sound.lc3.Lc3.LC3_HR_CHECK_SR_HZ;
import static google.sound.lc3.Lc3.LC3_HR_MAX_FRAME_BYTES;
import static google.sound.lc3.Lc3.LC3_HR_MAX_FRAME_SAMPLES;
import static google.sound.lc3.Lc3.lc3_decode;
import static google.sound.lc3.Lc3.lc3_hr_delay_samples;
import static google.sound.lc3.Lc3.lc3_hr_frame_samples;
import static google.sound.lc3.Lc3.lc3_hr_setup_decoder;
import static google.sound.lc3.Lc3.lc3_pcm_format.LC3_PCM_FORMAT_S16;
import static google.sound.lc3.Lc3.lc3_pcm_format.LC3_PCM_FORMAT_S24_3LE;
import static google.sound.lc3.Lc3Bin.lc3bin_read_data;
import static google.sound.lc3.Lc3Bin.lc3bin_read_header;


class Decoder {

    static final int MAX_CHANNELS = 2;

    //
    // Parameters
    //

    static class parameters {
        String fname_in;
        String fname_out;
        int bitdepth;
        int srate_hz;
    }

    static parameters parse_args(String[] argv) {
        final String usage = """
                        Usage: %s [wav_file] [out_file]

                        wav_file\tInput wave file, stdin if omitted
                        out_file\tOutput bitstream file, stdout if omitted

                        Options:
                        \t-h\tDisplay help
                        \t-b\tOutput bitdepth, 16 bits (default) or 24 bits
                        \t-r\tOutput samplerate, default is LC3 stream samplerate

                        """;

        parameters p = new parameters();
        p.bitdepth = 16;

        for (int iarg = 1; iarg < argv.length; ) {
            String arg = argv[iarg++];

            if (arg.charAt(0) == '-') {
                if (arg.charAt(2) != '\0')
                    throw new IllegalArgumentException("Option " + arg);

                char opt = arg.charAt(1);
                String optarg = null;

                switch (opt) {
                    case 'b':
                    case 'r':
                        if (iarg >= argv.length)
                            throw new IllegalArgumentException("Argument " + arg);
                        optarg = argv[iarg++];
                }

                switch (opt) {
                    case 'h':
                        System.err.printf(usage, argv[0]);
                        System.exit(0);
                    case 'b':
                        p.bitdepth = Integer.parseInt(optarg);
                        break;
                    case 'r':
                        p.srate_hz = Integer.parseInt(optarg);
                        break;
                    default:
                        throw new IllegalArgumentException("Option " + arg);
                }

            } else {

                if (p.fname_in != null)
                    p.fname_in = arg;
                else if (p.fname_out != null)
                    p.fname_out = arg;
                else
                    throw new IllegalArgumentException("Argument " + arg);
            }
        }

        return p;
    }

    /**
     * Return time in (us) from unspecified point in the past
     */
    static long clock_us() {
        long ts;

        ts = System.currentTimeMillis();

        return ts;
    }

    /**
     * Entry point
     */
    public static void main(String[] argv) throws IOException {
        // Read parameters

        parameters p = parse_args(argv);
        InputStream fp_in = System.in;
        OutputStream fp_out = System.out;

        if (p.fname_in != null)
            fp_in = Files.newInputStream(Path.of(p.fname_in));

        if (p.fname_out != null)
            fp_out = Files.newOutputStream(Path.of(p.fname_out));

        if (p.bitdepth != 0 && p.bitdepth != 16 && p.bitdepth != 24)
            throw new IllegalArgumentException(String.format("Bitdepth %d", p.bitdepth));

        // Check parameters

        int[] frame_us = new int[1], srate_hz = new int[1], nchannels = new int[1], nsamples = new int[1];
        boolean[] hrmode = new boolean[1];

        if (lc3bin_read_header(fp_in, frame_us, srate_hz, hrmode, nchannels, nsamples) <0)
            throw new IllegalArgumentException("LC3 binary input file");

        if (nchannels[0] < 1 || nchannels[0] > MAX_CHANNELS)
            throw new IllegalArgumentException(String.format("Number of channels %d", nchannels));

        if (!LC3_CHECK_DT_US(frame_us[0]))
            throw new IllegalArgumentException("Frame duration");

        if (!LC3_HR_CHECK_SR_HZ(hrmode[0], srate_hz[0]))
            throw new IllegalArgumentException(String.format("Samplerate %d Hz", srate_hz));

        if (p.srate_hz != 0 && (!LC3_HR_CHECK_SR_HZ(hrmode[0], p.srate_hz) || p.srate_hz < srate_hz[0]))
            throw new IllegalArgumentException(String.format("Output samplerate %d Hz", p.srate_hz));

        int pcm_sbits = p.bitdepth;
        int pcm_sbytes = pcm_sbits / 8;

        int pcm_srate_hz = p.srate_hz == 0 ? srate_hz[0] : p.srate_hz;
        int pcm_samples = p.srate_hz == 0 ? nsamples[0] : (nsamples[0] * pcm_srate_hz) / srate_hz[0];

//        wave_write_header(fp_out,
//                pcm_sbits, pcm_sbytes, pcm_srate_hz, nchannels, pcm_samples);
        AudioFormat af = new AudioFormat(Encoding.PCM_SIGNED,
                pcm_srate_hz, pcm_sbits, nchannels[0], pcm_sbytes * nchannels[0], pcm_srate_hz, false);

        // Setup decoding

        byte[] in = new byte[2 * LC3_HR_MAX_FRAME_BYTES];
        byte[] pcm = new byte[2 * LC3_HR_MAX_FRAME_SAMPLES * Short.BYTES];
        lc3_decoder[] dec = new lc3_decoder[ 2];

        int frame_samples = lc3_hr_frame_samples(hrmode[0], frame_us[0], pcm_srate_hz);
        int encode_samples = pcm_samples + lc3_hr_delay_samples(hrmode[0], frame_us[0], pcm_srate_hz);
        lc3_pcm_format pcm_fmt = pcm_sbits == 24 ? LC3_PCM_FORMAT_S24_3LE : LC3_PCM_FORMAT_S16;

        for (int ich = 0; ich < nchannels[0]; ich++) {
            dec[ich] = lc3_hr_setup_decoder(
                    hrmode[0], frame_us[0], srate_hz[0], p.srate_hz
                    /* ,malloc(lc3_hr_decoder_size(hrmode[0], frame_us[0], pcm_srate_hz)) */);

            if (dec[ich] == null)
                throw new IllegalArgumentException("Decoder initialization failed");
        }

        // Decoding loop

        final String dash_line = "========================================";

        int nsec = 0;
        int nerr = 0;
        long t0 = clock_us();

        for (int i = 0; i * frame_samples < encode_samples; i++) {

            int block_bytes = lc3bin_read_data(fp_in, nchannels[0], in);

            if (Math.floor(i * frame_us[0] * 1e-6) > nsec) {

                float progress = Math.min((float) i * frame_samples / pcm_samples, 1);

                System.err.printf("%02d:%02d [%-40s]\r",
                        nsec / 60, nsec % 60,
                        dash_line + (int) Math.floor((1 - progress) * 40));

                nsec = (int) Math.rint(i * frame_us[0] * 1e-6);
            }

            if (block_bytes <= 0)
                Arrays.fill(pcm, 0, nchannels[0] * frame_samples * pcm_sbytes, (byte) 0);
            else {
                int in_ptr = 0; // in
                for (int ich = 0; ich < nchannels[0]; ich++) {
                    int frame_bytes = block_bytes / nchannels[0] + (ich < block_bytes % nchannels[0] ? 1 : 0);

                    boolean res = lc3_decode(
                            dec[ich], in, in_ptr, frame_bytes, pcm_fmt, pcm, ich * pcm_sbytes, nchannels[0]);

                    nerr += res ? 1 : 0;
                    in_ptr += frame_bytes;
                }
            }

            int pcm_offset = i > 0 ? 0 : encode_samples - pcm_samples;
            int pcm_nwrite = Math.min(frame_samples - pcm_offset, encode_samples - i * frame_samples);

//            wave_write_pcm(fp_out, pcm_sbytes, pcm, nchannels, pcm_offset, pcm_nwrite);
        }

        int t = (int) ((clock_us() - t0) / 1000);
        nsec = nsamples[0] / srate_hz[0];

        System.err.printf("%02d:%02d Decoded in %d.%03d seconds %20s\n",
                nsec / 60, nsec % 60, t / 1000, t % 1000, "");

        if (nerr != 0)
            System.err.printf("Warning: Decoding of %d frames failed!\n", nerr);
    }
}