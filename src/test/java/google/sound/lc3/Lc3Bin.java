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

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static google.sound.lc3.Lc3.LC3_HR_MAX_FRAME_BYTES;


class Lc3Bin {

    /**
     * LC3 binary header
     */

    static final short LC3_FILE_ID = (short) (0x1C | (0xCC << 8));

    static class lc3bin_header {

        short file_id;
        short header_size;
        short srate_100hz;
        short bitrate_100bps;
        short channels;
        short frame_10us;
        short epmode;
        short nsamples_low;
        short nsamples_high;
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
        lc3bin_header hdr = new lc3bin_header();
        int hdr_hrmode = 0;

        DataInputStream dis = new DataInputStream(fp);
        hdr.file_id = dis.readShort();
        hdr.header_size = dis.readShort();
        hdr.srate_100hz = dis.readShort();
        hdr.bitrate_100bps = dis.readShort();
        hdr.frame_10us = dis.readShort();
        hdr.channels = dis.readShort();
        hdr.epmode = dis.readShort();
        hdr.nsamples_low = dis.readShort();
        hdr.nsamples_high = dis.readShort();

        if (hdr.file_id != LC3_FILE_ID || hdr.header_size < 18)
            return -1;

        int num_extended_params = (hdr.header_size - 18) / Short.BYTES;
        if (num_extended_params >= 1) {
            hdr_hrmode = dis.readShort();
        }

        nchannels[0] = hdr.channels;
        frame_us[0] = hdr.frame_10us * 10;
        srate_hz[0] = hdr.srate_100hz * 100;
        nsamples[0] = hdr.nsamples_low | (hdr.nsamples_high << 16);
        hrmode[0] = hdr_hrmode != 0;

        if (hdr.epmode != 0)
            return -1;

        // seek lc3bin_header.header_size

        return 0;
    }

    /**
     * Read LC3 block of data
     *
     * @param fp        Opened file
     * @param nChannels Number of channels
     * @param buffer    Output buffer of `nChannels * LC3_HR_MAX_FRAME_BYTES`
     * @return Size of the frames block, -1 on error
     */
    static int lc3bin_read_data(InputStream fp, int nChannels, byte[] buffer) throws IOException {
        int nbytes;

        DataInputStream dis = new DataInputStream(fp);
        nbytes = dis.readShort();

        if (nbytes > nChannels * LC3_HR_MAX_FRAME_BYTES)
            return -1;

        dis.readFully(buffer, 0, nbytes);

        return nbytes;
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

        lc3bin_header hdr = new lc3bin_header();
        hdr.file_id = LC3_FILE_ID;
        hdr.header_size = (short) (18 + (hrmode ? Short.BYTES : 0));
        hdr.srate_100hz = (short) (srate_hz / 100);
        hdr.bitrate_100bps = (short) (bitrate / 100);
        hdr.channels = (short) nchannels;
        hdr.frame_10us = (short) (frame_us / 10);
        hdr.nsamples_low = (short) (nsamples & 0xffff);
        hdr.nsamples_high = (short) (nsamples >> 16);

        DataOutputStream dos = new DataOutputStream(fp);
        dos.writeShort(hdr.file_id);
        dos.writeShort(hdr.header_size);
        dos.writeShort(hdr.srate_100hz);
        dos.writeShort(hdr.bitrate_100bps);
        dos.writeShort( hdr.frame_10us );
        dos.writeShort(hdr.channels );
        dos.writeShort(hdr.epmode);
        dos.writeShort(hdr.nsamples_low);
        dos.writeShort(hdr.nsamples_high);

        if (hrmode)
            dos.writeShort(hdr_hrmode);
    }

    /**
     * Write LC3 block of data
     *
     * @param fp     Opened file
     * @param data   The frames data
     * @param nbytes Size of the frames block
     */
    static void lc3bin_write_data(OutputStream fp, byte[] data, int nbytes) throws IOException {
        DataOutputStream dos = new DataOutputStream(fp);
        dos.writeShort(nbytes);
        dos.write(data, 0, nbytes);
    }
}