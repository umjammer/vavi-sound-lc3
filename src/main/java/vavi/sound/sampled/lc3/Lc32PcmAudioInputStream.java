/*
 * Copyright (c) 2005 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.sampled.lc3;

import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;

import vavi.io.OutputEngine;
import vavi.io.OutputEngineInputStream;
import vavi.sound.lc3.Lc3Plus;


/**
 * Converts an LC3 bitstream into a PCM 16bits/sample audio stream.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2023/05/31 umjammer initial version <br>
 */
class Lc32PcmAudioInputStream extends AudioInputStream {

    /** */
    public Lc32PcmAudioInputStream(InputStream in, AudioFormat audioFormat, int length, Lc3Plus lc3Plus) throws IOException {
        super(new OutputEngineInputStream(new Lc3OutputEngine(lc3Plus)), audioFormat, length);
    }

    /** */
    private static class Lc3OutputEngine implements OutputEngine {

        /** */
        private DataOutputStream out;

        /** */
        private Lc3Plus lc3Plus;

        /** */
        public Lc3OutputEngine(Lc3Plus lc3Plus) throws IOException {
            this.lc3Plus = lc3Plus;
        }

        /** */
        public void initialize(OutputStream out) throws IOException {
            if (this.out != null) {
                throw new IOException("Already initialized");
            } else {
                this.out = new DataOutputStream(out);
            }
        }

        /** */
        private byte[] pcmBuffer = new byte[0xffff];

        /** 24kb buffer = 4096 frames = 1 opus sample (we support max 24bps) */
        private int[] pDestBuffer = new int[1024 * 24 * 3];

        /** */
        public void execute() throws IOException {
            if (out == null) {
                throw new IOException("Not yet initialized");
            } else {
                try {
                    int nBytes = lc3Plus.read();
                    byte[] decodedData = lc3Plus.decode(nBytes);
                    out.write(decodedData, 0, decodedData.length);
                } catch (EOFException e) {
                    out.close();
                }
            }
        }

        /** */
        public void finish() throws IOException {
            lc3Plus.getInputStream().close();
        }
    }
}

/* */
