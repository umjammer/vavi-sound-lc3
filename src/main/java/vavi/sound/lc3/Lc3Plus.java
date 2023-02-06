/*
 * https://github.com/creamtoss/LC3_RT_Audio_Wrapper/blob/d7dfc25ecc03b42799082f2386f7a49299e25845/lc3_wrapper.c
 */

package vavi.sound.lc3;

import java.io.IOException;
import java.nio.ByteBuffer;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import vavi.sound.lc3.jna.Lc3Library.LC3PLUS_Error;
import vavi.sound.lc3.jna.Lc3Library.LC3PLUS_PlcMode;

import static vavi.sound.lc3.jna.Lc3Library.INSTANCE;
import static vavi.sound.lc3.jna.Lc3Library.LC3PLUS_MAX_BYTES;
import static vavi.sound.lc3.jna.Lc3Library.LC3PLUS_MAX_CHANNELS;
import static vavi.sound.lc3.jna.Lc3Library.LC3PLUS_MAX_SAMPLES;


/**
 * Lc3Plus.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2023-02-05 nsano initial version <br>
 */
public class Lc3Plus {

    /** */
    private PointerByReference decoder = new PointerByReference();

    // MUST be 8000 Hz, 16000 Hz, 24000 Hz, 32000 Hz, 44100 Hz, 48000 Hz or 96000 Hz
    int sampleRate = 48000;

    // MUST be 10 ms, 5 ms or 2.5 ms
    int frame_ms = 5;

    int plcMode = LC3PLUS_PlcMode.LC3PLUS_PLC_ADVANCED;
    int nChannels;
    int nSamples;
    int nBytes = 0;

    int[] iBufferSample = new int[LC3PLUS_MAX_CHANNELS * LC3PLUS_MAX_SAMPLES];
    int[] oBufferSample = new int[LC3PLUS_MAX_CHANNELS * LC3PLUS_MAX_SAMPLES];
    int[] buf_24 = new int[LC3PLUS_MAX_CHANNELS * LC3PLUS_MAX_SAMPLES];
    byte[] bytes = new byte[LC3PLUS_MAX_CHANNELS * LC3PLUS_MAX_BYTES];

    /** */
    public Lc3Plus(int sampleRate, int channels) throws IOException {
        int size = INSTANCE.lc3plus_dec_get_size(sampleRate, channels, plcMode);
        Memory m = new Memory(size);
        Runtime.getRuntime().addShutdownHook(new Thread(m::close));
        decoder.setPointer(m.getPointer(0));
        int r = INSTANCE.lc3plus_dec_init(decoder, sampleRate, channels, plcMode);
        if (r != LC3PLUS_Error.LC3PLUS_OK) {
            throw new IOException("lc3plus_dec_init: " + r);
        }
        r = INSTANCE.lc3plus_dec_set_frame_dms(decoder, frame_ms);
        if (r != LC3PLUS_Error.LC3PLUS_OK) {
            throw new IOException("lc3plus_dec_set_frame_dms: " + r);
        }
        nSamples = INSTANCE.lc3plus_dec_get_output_samples(decoder);
    }

    public byte[] decode(byte[] in) throws IOException {
        // oBufferLC3 is defined according to the amount of channels
        int[] oBufferLC3 = new int[nChannels];
        for (int i = 0; i < nChannels; i++) {
            oBufferLC3[i] = buf_24[i * nSamples];
        }

        ByteBuffer input = ByteBuffer.allocateDirect(nBytes);
        input.put(bytes);
        Pointer input_bytes = Native.getDirectBufferPointer(input);
        nBytes = bytes.length;

        ByteBuffer output = ByteBuffer.allocateDirect(oBufferSample.length);
        Pointer outputP = Native.getDirectBufferPointer(output);
        PointerByReference output_samples = new PointerByReference(outputP);

        ByteBuffer s = ByteBuffer.allocateDirect(buf_24.length);
        Pointer scratch = Native.getDirectBufferPointer(s);
        //Decoder is called with oBufferLC3
        int r = INSTANCE.lc3plus_dec16(decoder, input_bytes, nBytes, output_samples, scratch, 0);
        if (r != LC3PLUS_Error.LC3PLUS_OK) {
            throw new IOException("lc3plus_dec16: " + r);
        }
        //oBufferLC3

        //oBufferLC3 is interleaved and written on oBufferSample
        interleave(oBufferLC3, oBufferSample, nSamples, nChannels);

        return null;
    }

    static void interleave(int[] in, int[] out, int n, int channels) {
        for (int ch = 0; ch < channels; ch++) {
            for (int i = 0; i < n; i++) {
                out[i * channels + ch] = in[ch * n + i];
            }
        }
    }
}
