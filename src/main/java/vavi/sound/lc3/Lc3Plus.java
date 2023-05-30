/*
 * https://github.com/creamtoss/LC3_RT_Audio_Wrapper/blob/d7dfc25ecc03b42799082f2386f7a49299e25845/lc3_wrapper.c
 */

package vavi.sound.lc3;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import vavi.io.LittleEndianDataInputStream;
import vavi.sound.lc3.jna.Lc3Library.LC3PLUS_Error;
import vavi.sound.lc3.jna.Lc3Library.LC3PLUS_PlcMode;
import vavi.util.Debug;

import static vavi.sound.lc3.jna.Lc3Library.ERROR_MESSAGES;
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

    /**  */
    private PointerByReference decoder = new PointerByReference();

    // MUST be 8000 Hz, 16000 Hz, 24000 Hz, 32000 Hz, 44100 Hz, 48000 Hz or 96000 Hz
    int sampleRate = 48000;
    int bitrate;
    int signal_len;
    int hrmode;

    // MUST be 10 ms, 5 ms or 2.5 ms
    float frame_ms = 10;

    boolean epmode;

    int plcMode = LC3PLUS_PlcMode.LC3PLUS_PLC_ADVANCED;
    int nChannels;
    int nSamples;

    int scratch_size;
    boolean g192;

    Memory bytes;

    static class SafeMemory extends Memory {
        static {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> SafeMemory.mems.forEach(Memory::close)));
        }
        /** */
        static List<Memory> mems = new ArrayList<>();
        SafeMemory(long size) {
            super(size);
            mems.add(this);
        }
    }

    /** */
    public Lc3Plus() {
        bytes = new SafeMemory(LC3PLUS_MAX_BYTES);
    }

    /** */
    private Memory scratch;

    /** init decoder */
    public void init() throws IOException {
        int size = INSTANCE.lc3plus_dec_get_size(sampleRate, nChannels, plcMode);
        Memory m = new SafeMemory(size);
Debug.println(Level.FINER, m.size() + ", " + m);
        decoder.setPointer(m);

Debug.println(Level.FINE, "hrmode: " + hrmode);
        int r = INSTANCE.lc3plus_dec_init(decoder, sampleRate, nChannels, plcMode, hrmode);
        if (r != LC3PLUS_Error.LC3PLUS_OK) {
            throw new IOException("lc3plus_dec_init: " + ERROR_MESSAGES[r]);
        }

        r = INSTANCE.lc3plus_dec_set_frame_dms(decoder, (int) frame_ms * 10);
        if (r != LC3PLUS_Error.LC3PLUS_OK) {
            throw new IOException("lc3plus_dec_set_frame_dms: " + ERROR_MESSAGES[r]);
        }

        r = INSTANCE.lc3plus_dec_set_ep_enabled(decoder, epmode ? 1 : 0);
        if (r != LC3PLUS_Error.LC3PLUS_OK) {
            throw new IOException("lc3plus_dec_set_ep_enabled: " + ERROR_MESSAGES[r]);
        }

        nSamples = INSTANCE.lc3plus_dec_get_output_samples(decoder);
Debug.println(Level.FINE, "nSamples: " + nSamples);

        scratch_size = INSTANCE.lc3plus_dec_get_scratch_size(decoder);
Debug.println(Level.FINE, "scratch_size: " + scratch_size);
        scratch = new SafeMemory(scratch_size);
Debug.println(Level.FINE, "Native.POINTER_SIZE: " + Native.POINTER_SIZE);
    }

    /** decode */
    public byte[] decode(int nBytes) throws IOException {

        Memory output16 = new Memory((long) LC3PLUS_MAX_CHANNELS * Native.POINTER_SIZE);
        Memory[] c16 = new Memory[nChannels];
        for (int i = 0; i < nChannels; i++) {
            c16[i] = new Memory(LC3PLUS_MAX_SAMPLES * Short.SIZE);
            output16.setPointer((long) i * Native.POINTER_SIZE, c16[i]);
        }

Debug.println(Level.FINE, "decode: " + nBytes + ", " + bfi_ext);
        int r = INSTANCE.lc3plus_dec16(decoder, bytes, nBytes, new PointerByReference(output16), scratch, bfi_ext);
        if (r != LC3PLUS_Error.LC3PLUS_OK) {
            throw new IOException("lc3plus_dec16: " + ERROR_MESSAGES[r]);
        }
Debug.println(Level.FINE, "here");

        ByteBuffer bb = ByteBuffer.allocate(nSamples * nChannels * 2);
        interleave(c16, bb.asShortBuffer(), nSamples, nChannels);

        for (int i = 0; i < nChannels; i++) {
            c16[i].close();
        }
        output16.close();

        return bb.array();
    }

    /** */
    static void interleave(Memory[] in, ShortBuffer out, int n, int channels) {
        for (int ch = 0; ch < channels; ch++) {
            for (int i = 0; i < n; i++) {
                out.put(i * channels + ch, in[ch].getShort((long) i * Short.SIZE));
            }
        }
    }

    LittleEndianDataInputStream ledis;

    /** check header */
    public void header(InputStream in) throws IOException {
        in.mark(20);
        ledis = new LittleEndianDataInputStream(in);
        int i = ledis.readUnsignedShort();
        if (i != 0xcc1c) {
            sampleRate = i * 100;
            bitrate = ledis.readUnsignedShort() * 100;
            nChannels = ledis.readUnsignedShort();
Debug.println("sampleRate: " + sampleRate);
Debug.println("bitrate: " + bitrate);
Debug.println("nChannels: " + nChannels);
            in.reset();
            ledis.skipBytes(6);
        } else {
            int v = ledis.readUnsignedShort();
            Debug.println("v: " + v);
            assert v >= 18;
            sampleRate = ledis.readUnsignedShort() * 100;
            bitrate = ledis.readUnsignedShort() * 100;
            nChannels = ledis.readUnsignedShort();
            frame_ms = ledis.readUnsignedShort() / 100f;
            epmode = ledis.readUnsignedShort() != 0;
            signal_len = ledis.readInt();
            hrmode = v > 18 ? ledis.readUnsignedShort() : 0;
Debug.println("sampleRate: " + sampleRate);
Debug.println("bitrate: " + bitrate);
Debug.println("nChannels: " + nChannels);
Debug.println("frame_ms: " + frame_ms);
Debug.println("epmode: " + epmode);
Debug.println("signal_len: " + signal_len);
            in.reset();
            ledis.skipBytes(v);
        }
    }

    /** */
    int bfi_ext;
    /* G192 bitstream writing/reading */
    static final int G192_GOOD_FRAME = 0x6B21;
    static final int G192_BAD_FRAME = 0x6B20;
    static final int G192_REDUNDANCY_FRAME = 0x6B22;
    static final int G192_ZERO = 0x007F;
    static final int G192_ONE = 0x0081;

    /** */
    int read() throws IOException {
        if (g192) {
            return read_g192();
        } else {
            int nbytes = ledis.readUnsignedShort();
            for (int i = 0; i < nbytes && i < bytes.size(); i++) {
                bytes.setByte(i, ledis.readByte());
            }
            return nbytes;
        }
    }

    /** */
    int read_g192() throws IOException {
        int frameIndicator = ledis.readShort();
        int nbits = ledis.readUnsignedShort();
        int nbytes = nbits / 8;

        for (int i = 0; i < nbytes && i < bytes.size(); i++) {
            byte byte_ = 0;
            for (int j = 0; j < 8; j++) {
                int currentBit = ledis.readShort();
                if (currentBit == G192_ONE) {
                    byte_ |= 1 << j;
                }
            }
            bytes.setByte(i, byte_);
        }
        if (frameIndicator == G192_GOOD_FRAME) {
            bfi_ext = 0;
        } else if (frameIndicator == G192_BAD_FRAME) {
            nbytes = 0;
            bfi_ext = 1;
        } else if (frameIndicator == G192_REDUNDANCY_FRAME) {
            bfi_ext = 3;
        }

        return nbytes;
    }
}
