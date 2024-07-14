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
        int srate_100hz;
        int bitrate_100bps;
        int channels;
        int frame_10us;
        int epmode;
        int nsamples_low;
        int nsamples_high;

        @Override public String toString() {
            return new StringJoiner(", ", Lc3Header.class.getSimpleName() + "[", "]")
                    .add("file_id=" + Integer.toHexString(file_id))
                    .add("header_size=" + header_size)
                    .add("srate_100hz=" + srate_100hz)
                    .add("bitrate_100bps=" + bitrate_100bps)
                    .add("channels=" + channels)
                    .add("frame_10us=" + frame_10us)
                    .add("epmode=" + epmode)
                    .add("nsamples_low=" + nsamples_low)
                    .add("nsamples_high=" + nsamples_high)
                    .toString();
        }
    }

    /**
     * Read LC3 binary header
     *
     * @param fp        Opened file, moved after header on return
     * @param frame_us  Return frame duration, in us
     * @param srate_hz  Return samplerate, in Hz
     * @param hrmode    Return true when high-resolution mode enabled
     * @param nchannels Return number of channels
     * @param nsamples  Return count of source samples by channels
     * @return 0: Ok  -1: Bad LC3 File
     */
    static int lc3bin_read_header(InputStream fp,
                                  int[] frame_us, int[] srate_hz, boolean[] hrmode, int[] nchannels, int[] nsamples) throws IOException {
        Lc3Header hdr = new Lc3Header();
        int hdr_hrmode = 0;

        LittleEndianDataInputStream dis = new LittleEndianDataInputStream(fp);
        hdr.file_id = dis.readUnsignedShort();
        hdr.header_size = dis.readUnsignedShort();
        hdr.srate_100hz = dis.readUnsignedShort();
        hdr.bitrate_100bps = dis.readUnsignedShort();
        hdr.channels = dis.readUnsignedShort();
        hdr.frame_10us = dis.readUnsignedShort();
        hdr.epmode = dis.readUnsignedShort();
        hdr.nsamples_low = dis.readUnsignedShort();
        hdr.nsamples_high = dis.readUnsignedShort();
Debug.println("hdr: " + hdr);

        if (hdr.file_id != (LC3_FILE_ID & 0xffff) || hdr.header_size < 18) {
logger.log(Level.DEBUG, "illegal header id or size: " + hdr.file_id + ", " + LC3_FILE_ID + ", " + hdr.header_size);
            return -1;
        }

        int num_extended_params = (hdr.header_size - 18) / Short.BYTES;
        if (num_extended_params >= 1) {
            hdr_hrmode = dis.readUnsignedShort();
        }

        nchannels[0] = hdr.channels;
        frame_us[0] = hdr.frame_10us * 10;
        srate_hz[0] = hdr.srate_100hz * 100;
        nsamples[0] = hdr.nsamples_low | (hdr.nsamples_high << 16);
        hrmode[0] = hdr_hrmode != 0;

        if (hdr.epmode != 0) {
logger.log(Level.DEBUG, "illegal epmode");
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
     * @param srate_hz  Samplerate, in Hz
     * @param hrmode    True when high-resolution mode enabled
     * @param bitrate   Bitrate indication of the stream, in bps
     * @param nchannels Number of channels
     * @param nsamples  Count of source samples by channels
     */
    static void lc3bin_write_header(OutputStream fp,
                                    int frame_us, int srate_hz, boolean hrmode,
                                    int bitrate, int nchannels, int nsamples) throws IOException {
        int hdr_hrmode = (hrmode ? 1 : 0);

        Lc3Header header = new Lc3Header();
        header.file_id = LC3_FILE_ID;
        header.header_size = (short) (18 + (hrmode ? Short.BYTES : 0));
        header.srate_100hz = (short) (srate_hz / 100);
        header.bitrate_100bps = (short) (bitrate / 100);
        header.channels = (short) nchannels;
        header.frame_10us = (short) (frame_us / 10);
        header.nsamples_low = (short) (nsamples & 0xffff);
        header.nsamples_high = (short) (nsamples >> 16);

        DataOutputStream dos = new DataOutputStream(fp);
        dos.writeShort(header.file_id);
        dos.writeShort(header.header_size);
        dos.writeShort(header.srate_100hz);
        dos.writeShort(header.bitrate_100bps);
        dos.writeShort( header.frame_10us );
        dos.writeShort(header.channels );
        dos.writeShort(header.epmode);
        dos.writeShort(header.nsamples_low);
        dos.writeShort(header.nsamples_high);

        if (hrmode)
            dos.writeShort(hdr_hrmode);
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