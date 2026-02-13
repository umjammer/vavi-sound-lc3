/*
 * Copyright (c) 2026 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.sampled.lc3;

import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Arrays;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

import google.sound.lc3.Lc3;
import google.sound.lc3.Lc3.lc3_pcm_format;
import vavi.io.OutputEngine;
import vavi.io.OutputEngineInputStream;
import vavi.sound.sampled.lc3.GoogleLc3.Lc3Header;

import static google.sound.lc3.Lc3.LC3_HR_MAX_FRAME_BYTES;
import static google.sound.lc3.Lc3.LC3_HR_MAX_FRAME_SAMPLES;
import static google.sound.lc3.Lc3.lc3_decode;
import static google.sound.lc3.Lc3.lc3_hr_delay_samples;
import static google.sound.lc3.Lc3.lc3_hr_frame_samples;
import static google.sound.lc3.Lc3.lc3_hr_setup_decoder;
import static google.sound.lc3.Lc3.lc3_pcm_format.LC3_PCM_FORMAT_S16;
import static google.sound.lc3.Lc3.lc3_pcm_format.LC3_PCM_FORMAT_S24_3LE;
import static vavi.sound.sampled.lc3.GoogleLc3.lc3bin_read_data;


/**
 * Converts an LC3 bitstream into a PCM 16bits/sample audio stream pure java version.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2026/02/13 umjammer initial version <br>
 */
class GoogleLc3ToPcmAudioInputStream extends AudioInputStream {

    private static final Logger logger = System.getLogger(GoogleLc3ToPcmAudioInputStream.class.getName());

    /** */
    public GoogleLc3ToPcmAudioInputStream(InputStream in, AudioFormat audioFormat, int length, Lc3Header header) throws IOException {
        super(new OutputEngineInputStream(new GoogleLc3OutputEngine(in, header, audioFormat)), audioFormat, length);
    }

    /** */
    private static class GoogleLc3OutputEngine implements OutputEngine {

        InputStream is;

        /** */
        private DataOutputStream out;

        /** */
        private final Lc3Header header;

        int pcm_sBytes;
        int pcm_samples;

        byte[] in;
        byte[] pcm;
        Lc3.Decoder[] dec;

        int frame_samples;
        int encode_samples;
        lc3_pcm_format pcm_fmt;

        int count;

        /** */
        public GoogleLc3OutputEngine(InputStream is, Lc3Header header, AudioFormat audioFormat) throws IOException {
            this.is = is;
            this.header = header;

            int pcm_sBits = audioFormat.getSampleSizeInBits();
            this.pcm_sBytes = pcm_sBits / 8;

            int pcm_sRate_hz = (int) audioFormat.getSampleRate();
            this.pcm_samples = (int) (((long) header.nSamples * pcm_sRate_hz) / header.sRate_hz);

            // Setup decoding

            this.in = new byte[2 * LC3_HR_MAX_FRAME_BYTES];
            this.pcm = new byte[2 * LC3_HR_MAX_FRAME_SAMPLES * Short.BYTES];
            this.dec = new Lc3.Decoder[2];

            this.frame_samples = lc3_hr_frame_samples(header.hrMode, header.frame_us, pcm_sRate_hz);
            this.encode_samples = pcm_samples + lc3_hr_delay_samples(header.hrMode, header.frame_us, pcm_sRate_hz);
            this.pcm_fmt = pcm_sBits == 24 ? LC3_PCM_FORMAT_S24_3LE : LC3_PCM_FORMAT_S16;

            for (int ich = 0; ich < header.nChannels; ich++) {
                dec[ich] = lc3_hr_setup_decoder(header.hrMode, header.frame_us, header.sRate_hz, pcm_sRate_hz);

                if (dec[ich] == null)
                    throw new IllegalArgumentException("Decoder initialization failed");
            }
        }

        @Override
        public void initialize(OutputStream out) throws IOException {
            if (this.out != null) {
                throw new IOException("Already initialized");
            } else {
                this.out = new DataOutputStream(out);
            }
        }

        @Override
        public void execute() throws IOException {
            if (out == null) {
                throw new IOException("Not yet initialized");
            } else {
                try {
//logger.log(Level.TRACE, "count: " + count + ", " + count * frame_samples + ", " + encode_samples);
                    if (count * frame_samples < encode_samples) {

                        int in_ptr = 0; // in
                        int block_bytes = lc3bin_read_data(is, header.nChannels, in, in_ptr);

                        if (block_bytes <= 0)
                            Arrays.fill(pcm, 0, header.nChannels * frame_samples * pcm_sBytes, (byte) 0);
                        else {
                            for (int ich = 0; ich < header.nChannels; ich++) {
                                int frame_bytes = block_bytes / header.nChannels + (ich < block_bytes % header.nChannels ? 1 : 0);

                                boolean res = lc3_decode(
                                        dec[ich], in, in_ptr, frame_bytes, pcm_fmt, pcm, ich * pcm_sBytes, header.nChannels);

                                in_ptr += frame_bytes;
                            }
                        }

                        int pcm_offset = count > 0 ? 0 : encode_samples - pcm_samples;
                        int pcm_nWrite = Math.min(frame_samples - pcm_offset, encode_samples - count * frame_samples);

                        out.write(pcm, header.nChannels * pcm_offset * pcm_sBytes, header.nChannels * pcm_sBytes * pcm_nWrite);

                        count++;
                    } else {
logger.log(Level.TRACE, "finish");
                        out.close();
                    }
                } catch (EOFException e) {
logger.log(Level.TRACE, "eof");
                    out.close();
                }
            }
        }

        @Override
        public void finish() throws IOException {
        }
    }
}
