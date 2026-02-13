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

package vavi.sound.sampled.lc3;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.StringJoiner;

import vavi.io.LittleEndianDataInputStream;

import static google.sound.lc3.Lc3.LC3_CHECK_DT_US;
import static google.sound.lc3.Lc3.LC3_HR_CHECK_SR_HZ;
import static google.sound.lc3.Lc3.LC3_HR_MAX_FRAME_BYTES;
import static java.lang.System.getLogger;


class GoogleLc3 {

    private static final Logger logger = getLogger(GoogleLc3.class.getName());

    static final int MAX_CHANNELS = 2;

    /**
     * LC3 binary header
     */

    static final short LC3_FILE_ID = (short) (0x1C | (0xCC << 8));

    static class Lc3Header {

        int file_id;
        int nChannels;
        int frame_us;
        int sRate_hz;
        int bitRate;
        int nSamples;
        int epMode;
        boolean hrMode;

        @Override public String toString() {
            return new StringJoiner(", ", Lc3Header.class.getSimpleName() + "[", "]")
                    .add("file_id=" + Integer.toHexString(file_id))
                    .add("nChannels=" + nChannels)
                    .add("frame_us=" + frame_us)
                    .add("sRate_hz=" + sRate_hz)
                    .add("bitRate=" + bitRate)
                    .add("nSamples=" + nSamples)
                    .add("epMode=" + epMode)
                    .add("hrMode=" + hrMode)
                    .toString();
        }
    }

    /**
     * Read LC3 binary header
     *
     * @param fp        Opened file, moved after header on return
     * @return 0: Ok  -1: Bad LC3 File
     * @throws IllegalArgumentException
     */
    static Lc3Header lc3bin_read_header(InputStream fp) throws IOException {
        Lc3Header header = new Lc3Header();
        int hdr_hrmode = 0;

        LittleEndianDataInputStream dis = new LittleEndianDataInputStream(fp);
        int file_id = dis.readUnsignedShort();
        int header_size = dis.readUnsignedShort();
        int sRate_100hz = dis.readUnsignedShort();
        int bitRate_100bps = dis.readUnsignedShort();
        int channels = dis.readUnsignedShort();
        int frame_10us = dis.readUnsignedShort();
        int epMode = dis.readUnsignedShort();
        int nSamples_low = dis.readUnsignedShort();
        int nSamples_high = dis.readUnsignedShort();

        if (file_id != (LC3_FILE_ID & 0xffff) || header_size < 18) {
logger.log(Level.TRACE, "illegal header id or size: " + file_id + ", " + LC3_FILE_ID + ", " + header_size);
            throw new IllegalArgumentException("illegal header id or size: " + file_id + ", " + LC3_FILE_ID + ", " + header_size);
        }

        int num_extended_params = (header_size - 18) / Short.BYTES;
        if (num_extended_params >= 1) {
            hdr_hrmode = dis.readUnsignedShort();
        }

        header.file_id = file_id;
        header.nChannels = channels;
        header.frame_us = frame_10us * 10;
        header.sRate_hz = sRate_100hz * 100;
        header.bitRate = bitRate_100bps * 100;
        header.nSamples = nSamples_low | (nSamples_high << 16);
        header.hrMode = hdr_hrmode != 0;
        header.epMode = epMode;
logger.log(Level.TRACE, "header: " + header);

        if (header.epMode != 0) {
logger.log(Level.TRACE, "illegal epMode: " + header.epMode);
            throw new IllegalArgumentException("illegal epMode");
        }

        if (header.nChannels < 1 || header.nChannels > MAX_CHANNELS) {
logger.log(Level.TRACE, "Number of channels " + header.nChannels);
            throw new IllegalArgumentException("Number of channels " + header.nChannels);
        }

        if (!LC3_CHECK_DT_US(header.frame_us)) {
logger.log(Level.TRACE, "Frame duration");
            throw new IllegalArgumentException("Frame duration");
        }

        if (!LC3_HR_CHECK_SR_HZ(header.hrMode, header.sRate_hz)) {
logger.log(Level.TRACE, "SampleRate %d Hz".formatted(header.sRate_hz));
            throw new IllegalArgumentException("SampleRate %d Hz".formatted(header.sRate_hz));
        }

        return header;
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
logger.log(Level.TRACE, "underflow: " + nBytes);
            return -1;
        }

        dis.readFully(buffer, offset, nBytes);

        return nBytes;
    }

    /**
     * Write LC3 binary header
     */
    static void lc3bin_write_header(OutputStream fp, Lc3Header header) throws IOException {
        int hdr_hrMode = (header.hrMode ? 1 : 0);

        short file_id = LC3_FILE_ID;
        short header_size = (short) (18 + (header.hrMode ? Short.BYTES : 0));
        short sRate_100hz = (short) (header.sRate_hz / 100);
        short bitRate_100bps = (short) (header.bitRate / 100);
        short channels = (short) header.nChannels;
        short frame_10us = (short) (header.frame_us / 10);
        short nSamples_low = (short) (header.nSamples & 0xffff);
        short nSamples_high = (short) (header.nSamples >> 16);
        short epMode = (short) header.epMode;

        DataOutputStream dos = new DataOutputStream(fp);
        dos.writeShort(file_id);
        dos.writeShort(header_size);
        dos.writeShort(sRate_100hz);
        dos.writeShort(bitRate_100bps);
        dos.writeShort(frame_10us);
        dos.writeShort(channels);
        dos.writeShort(epMode);
        dos.writeShort(nSamples_low);
        dos.writeShort(nSamples_high);

        if (header.hrMode)
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