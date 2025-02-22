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

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.StringJoiner;

import vavi.io.LittleEndianDataInputStream;
import vavi.util.Debug;

import static google.sound.lc3.Lc3.LC3_HR_MAX_FRAME_BYTES;
import static java.lang.System.getLogger;


class Lc3Bin {

    private static final Logger logger = getLogger(Lc3Bin.class.getName());

    /**
     * LC3 binary header
     */

    static final short LC3_FILE_ID = (short) (0x1C | (0xCC << 8));

    static class Lc3Header {

        int file_id;
        int header_size;
        int sRate_100hz;
        int bitRate_100bps;
        int channels;
        int frame_10us;
        int epMode;
        int nSamples_low;
        int nSamples_high;

        @Override public String toString() {
            return new StringJoiner(", ", Lc3Header.class.getSimpleName() + "[", "]")
                    .add("file_id=" + Integer.toHexString(file_id))
                    .add("header_size=" + header_size)
                    .add("sRate_100hz=" + sRate_100hz)
                    .add("bitRate_100bps=" + bitRate_100bps)
                    .add("channels=" + channels)
                    .add("frame_10us=" + frame_10us)
                    .add("epMode=" + epMode)
                    .add("nSamples_low=" + nSamples_low)
                    .add("nSamples_high=" + nSamples_high)
                    .toString();
        }
    }

    /**
     * Read LC3 binary header
     *
     * @param fp        Opened file, moved after header on return
     * @param frame_us  Return frame duration, in us
     * @param sRate_hz  Return samplerate, in Hz
     * @param hrMode    Return true when high-resolution mode enabled
     * @param nChannels Return number of channels
     * @param nSamples  Return count of source samples by channels
     * @return 0: Ok  -1: Bad LC3 File
     */
    static int lc3bin_read_header(InputStream fp,
                                  int[] frame_us, int[] sRate_hz, boolean[] hrMode, int[] nChannels, int[] nSamples) throws IOException {
        Lc3Header hdr = new Lc3Header();
        int hdr_hrmode = 0;

        LittleEndianDataInputStream dis = new LittleEndianDataInputStream(fp);
        hdr.file_id = dis.readUnsignedShort();
        hdr.header_size = dis.readUnsignedShort();
        hdr.sRate_100hz = dis.readUnsignedShort();
        hdr.bitRate_100bps = dis.readUnsignedShort();
        hdr.channels = dis.readUnsignedShort();
        hdr.frame_10us = dis.readUnsignedShort();
        hdr.epMode = dis.readUnsignedShort();
        hdr.nSamples_low = dis.readUnsignedShort();
        hdr.nSamples_high = dis.readUnsignedShort();
Debug.println("hdr: " + hdr);

        if (hdr.file_id != (LC3_FILE_ID & 0xffff) || hdr.header_size < 18) {
logger.log(Level.DEBUG, "illegal header id or size: " + hdr.file_id + ", " + LC3_FILE_ID + ", " + hdr.header_size);
            return -1;
        }

        int num_extended_params = (hdr.header_size - 18) / Short.BYTES;
        if (num_extended_params >= 1) {
            hdr_hrmode = dis.readUnsignedShort();
        }

        nChannels[0] = hdr.channels;
        frame_us[0] = hdr.frame_10us * 10;
        sRate_hz[0] = hdr.sRate_100hz * 100;
        nSamples[0] = hdr.nSamples_low | (hdr.nSamples_high << 16);
        hrMode[0] = hdr_hrmode != 0;

        if (hdr.epMode != 0) {
logger.log(Level.DEBUG, "illegal epMode");
            return -1;
        }

        // seek Lc3Header.header_size

        return 0;
    }

    /**
     * Read LC3 block of data
     *
     * @param fp        Opened file
     * @param nChannels Number of channels
     * @param buffer    Output buffer of `nChannels * LC3_HR_MAX_FRAME_BYTES`
     * @param offset    offset for buffer
     * @return Size of the frames block, -1 on error
     */
    static int lc3bin_read_data(InputStream fp, int nChannels, byte[] buffer, int offset) throws IOException {

        LittleEndianDataInputStream dis = new LittleEndianDataInputStream(fp);
        int nBytes = dis.readUnsignedShort();

        if (nBytes > nChannels * LC3_HR_MAX_FRAME_BYTES) {
logger.log(Level.WARNING, nBytes);
            return -1;
        }

        dis.readFully(buffer, offset, nBytes);

        return nBytes;
    }

    /**
     * Write LC3 binary header
     *
     * @param fp        Opened file, moved after header on return
     * @param frame_us  Frame duration, in us
     * @param sRate_hz  Samplerate, in Hz
     * @param hrMode    True when high-resolution mode enabled
     * @param bitRate   Bitrate indication of the stream, in bps
     * @param nChannels Number of channels
     * @param nSamples  Count of source samples by channels
     */
    static void lc3bin_write_header(OutputStream fp,
                                    int frame_us, int sRate_hz, boolean hrMode,
                                    int bitRate, int nChannels, int nSamples) throws IOException {
        int hdr_hrMode = (hrMode ? 1 : 0);

        Lc3Header header = new Lc3Header();
        header.file_id = LC3_FILE_ID;
        header.header_size = (short) (18 + (hrMode ? Short.BYTES : 0));
        header.sRate_100hz = (short) (sRate_hz / 100);
        header.bitRate_100bps = (short) (bitRate / 100);
        header.channels = (short) nChannels;
        header.frame_10us = (short) (frame_us / 10);
        header.nSamples_low = (short) (nSamples & 0xffff);
        header.nSamples_high = (short) (nSamples >> 16);

        DataOutputStream dos = new DataOutputStream(fp);
        dos.writeShort(header.file_id);
        dos.writeShort(header.header_size);
        dos.writeShort(header.sRate_100hz);
        dos.writeShort(header.bitRate_100bps);
        dos.writeShort( header.frame_10us );
        dos.writeShort(header.channels );
        dos.writeShort(header.epMode);
        dos.writeShort(header.nSamples_low);
        dos.writeShort(header.nSamples_high);

        if (hrMode)
            dos.writeShort(hdr_hrMode);
    }

    /**
     * Write LC3 block of data
     *
     * @param fp     Opened file
     * @param data   The frames data
     * @param nBytes Size of the frames block
     */
    static void lc3bin_write_data(OutputStream fp, byte[] data, int nBytes) throws IOException {
        DataOutputStream dos = new DataOutputStream(fp);
        dos.writeShort(nBytes);
        dos.write(data, 0, nBytes);
    }
}