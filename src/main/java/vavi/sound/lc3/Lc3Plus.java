/*
 * https://github.com/creamtoss/LC3_RT_Audio_Wrapper/blob/d7dfc25ecc03b42799082f2386f7a49299e25845/lc3_wrapper.c
 */

package vavi.sound.lc3;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.logging.Level;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import vavi.io.LittleEndianDataInputStream;
import vavi.sound.lc3.jna.Lc3Library.LC3PLUS_Error;
import vavi.sound.lc3.jna.Lc3Library.LC3PLUS_PlcMode;
import vavi.util.Debug;

import static vavi.sound.lc3.jna.Lc3Library.ERROR_MESSAGES;
import static vavi.sound.lc3.jna.Lc3Library.INSTANCE;
import static vavi.sound.lc3.jna.Lc3Library.LC3PLUS_MAX_BYTES;


/**
 * Lc3Plus.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2023-02-05 nsano initial version <br>
 */
public class Lc3Plus {

    /** the decoder structure */
    private PointerByReference decoder = new PointerByReference();

    // input data

    /** MUST be 8000 Hz, 16000 Hz, 24000 Hz, 32000 Hz, 44100 Hz, 48000 Hz or 96000 Hz */
    private int sampleRate = 48000;
    private int bitrate;
    private int signalLength;
    private int channels;
    /** MUST be 10 ms, 5 ms or 2.5 ms */
    private float frameMs = 10;
    private boolean epMode;

    // options

    private int plcMode = LC3PLUS_PlcMode.LC3PLUS_PLC_ADVANCED;
    private boolean g192;
    private int hrMode;
    private int sampleSizeInBit = 16;

    // runtime

    private LittleEndianDataInputStream ledis;

    /** read buffer */
    private Memory input;
    /** scratch */
    private int scratchSize;
    /** scratch */
    private Memory scratch;
    /** pointer array */
    private Memory output16s;
    /** decoded samples */
    private Memory[] output16ch;
    /** */
    private int samples;
    /** */
    private int bfiExt;

    /** */
    public float getSampleRate() {
        return sampleRate;
    }

    /** */
    public int getSampleSizeInBit() {
        return sampleSizeInBit;
    }

    /** */
    public int getChannels() {
        return channels;
    }

    /** */
    public InputStream getInputStream() {
        return ledis;
    }

    /**
     * Creates a decoder.
     *
     * @param in must be mark supported
     */
    public Lc3Plus(InputStream in) throws IOException {
        in.mark(20);
        try {
            ledis = new LittleEndianDataInputStream(in);
            int i = ledis.readUnsignedShort();
            if (i != 0xcc1c) {
                sampleRate = i * 100;
                bitrate = ledis.readUnsignedShort() * 100;
                channels = ledis.readUnsignedShort();
Debug.println(Level.FINE, "sampleRate: " + sampleRate);
Debug.println(Level.FINE, "bitrate: " + bitrate);
Debug.println(Level.FINE, "channels: " + channels);
                in.reset();
                ledis.skipBytes(6);
            } else {
                int v = ledis.readUnsignedShort();
Debug.println(Level.FINE, "v: " + v);
                assert v >= 18;
                sampleRate = ledis.readUnsignedShort() * 100;
                bitrate = ledis.readUnsignedShort() * 100;
                channels = ledis.readUnsignedShort();
                frameMs = ledis.readUnsignedShort() / 100f;
                epMode = ledis.readUnsignedShort() != 0;
                signalLength = ledis.readInt();
                hrMode = v > 18 ? ledis.readUnsignedShort() : 0;
Debug.println(Level.FINE, "sampleRate: " + sampleRate);
Debug.println(Level.FINE, "bitrate: " + bitrate);
Debug.println(Level.FINE, "channels: " + channels);
Debug.println(Level.FINE, "frameMs: " + frameMs);
Debug.println(Level.FINE, "epMode: " + epMode);
Debug.println(Level.FINE, "signalLength: " + signalLength);
                in.reset();
                ledis.skipBytes(v);
            }

            input = new Memory(LC3PLUS_MAX_BYTES);

            init();
        } catch (Throwable t) {
            in.reset();
            throw t;
        }
    }

    /** init decoder */
    private void init() throws IOException {
        int size = INSTANCE.lc3plus_dec_get_size(sampleRate, channels, plcMode);
        Memory m = new Memory(size);
Debug.println(Level.FINER, m.size() + ", " + m);
        decoder.setPointer(m);

Debug.println(Level.FINE, "hrMode: " + hrMode);
        int r = INSTANCE.lc3plus_dec_init(decoder, sampleRate, channels, plcMode, hrMode);
        if (r != LC3PLUS_Error.LC3PLUS_OK) {
            throw new IOException("lc3plus_dec_init: " + ERROR_MESSAGES[r]);
        }

        r = INSTANCE.lc3plus_dec_set_frame_dms(decoder, (int) frameMs * 10);
        if (r != LC3PLUS_Error.LC3PLUS_OK) {
            throw new IOException("lc3plus_dec_set_frame_dms: " + ERROR_MESSAGES[r]);
        }

        r = INSTANCE.lc3plus_dec_set_ep_enabled(decoder, epMode ? 1 : 0);
        if (r != LC3PLUS_Error.LC3PLUS_OK) {
            throw new IOException("lc3plus_dec_set_ep_enabled: " + ERROR_MESSAGES[r]);
        }

        samples = INSTANCE.lc3plus_dec_get_output_samples(decoder);
Debug.println(Level.FINE, "samples: " + samples);

        scratchSize = INSTANCE.lc3plus_dec_get_scratch_size(decoder);
Debug.println(Level.FINE, "scratchSize: " + scratchSize);
        scratch = new Memory(scratchSize);

Debug.println(Level.FINER, "Native.POINTER_SIZE: " + Native.POINTER_SIZE);
        output16s = new Memory((long) channels * Native.POINTER_SIZE);
        output16ch = new Memory[channels];
        for (int i = 0; i < channels; i++) {
            output16ch[i] = new Memory((long) samples * Short.BYTES);
            output16s.setPointer((long) i * Native.POINTER_SIZE, output16ch[i]);
        }
    }

    /** decode */
    public byte[] decode(int inSize) throws IOException {

Debug.println(Level.FINER, "decode: " + inSize + ", " + bfiExt);
        int r = INSTANCE.lc3plus_dec16(decoder, input, inSize, new NativeLong(Pointer.nativeValue(output16s)), scratch, bfiExt);
        if (r != LC3PLUS_Error.LC3PLUS_OK) {
            throw new IOException("lc3plus_dec16: " + ERROR_MESSAGES[r]);
        }

        ByteBuffer bb = ByteBuffer.allocate(samples * channels * Short.BYTES).order(ByteOrder.nativeOrder());

        interleave16(output16ch, bb.asShortBuffer(), samples);

        return bb.array();
    }

    /** */
    private void interleave16(Memory[] in, ShortBuffer out, int n) {
        for (int ch = 0; ch < channels; ch++) {
            for (int i = 0; i < n; i++) {
                out.put(i * channels + ch, in[ch].getShort((long) i * Short.BYTES));
            }
        }
    }

    /* G192 bitstream writing/reading */
    private static final int G192_GOOD_FRAME = 0x6B21;
    private static final int G192_BAD_FRAME = 0x6B20;
    private static final int G192_REDUNDANCY_FRAME = 0x6B22;
    private static final int G192_ZERO = 0x007F;
    private static final int G192_ONE = 0x0081;

    /** */
    public int read() throws IOException {
        if (g192) {
            return read_g192();
        } else {
            int nbytes = ledis.readUnsignedShort();
            for (int i = 0; i < nbytes && i < input.size(); i++) {
                input.setByte(i, ledis.readByte());
            }
            return nbytes;
        }
    }

    /** */
    private int read_g192() throws IOException {
        int frameIndicator = ledis.readShort();
        int nbits = ledis.readUnsignedShort();
        int nbytes = nbits / 8;

        for (int i = 0; i < nbytes && i < input.size(); i++) {
            byte byte_ = 0;
            for (int j = 0; j < 8; j++) {
                int currentBit = ledis.readShort();
                if (currentBit == G192_ONE) {
                    byte_ |= 1 << j;
                }
            }
            input.setByte(i, byte_);
        }
        if (frameIndicator == G192_GOOD_FRAME) {
            bfiExt = 0;
        } else if (frameIndicator == G192_BAD_FRAME) {
            nbytes = 0;
            bfiExt = 1;
        } else if (frameIndicator == G192_REDUNDANCY_FRAME) {
            bfiExt = 3;
        }

        return nbytes;
    }
}
