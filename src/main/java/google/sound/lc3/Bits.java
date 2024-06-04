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


import java.util.stream.IntStream;

import static google.sound.lc3.Bits.Mode.READ;


/**
 * The bitstream is written by the 2 ends of the buffer :
 * <ul>
 *  <li> Arthmetic coder put bits while increasing memory addresses
 *   in the buffer (forward)</li>
 *
 *  <li> Plain bits are puts starting the end of the buffer, with memeory
 *   addresses decreasing (backward)</li>
 *
 * <pre>
 *       .---------------------------------------------------.
 *       | > > > > > > > > > > :         : < < < < < < < < < |
 *       '---------------------------------------------------'
 *       |--------------------. - - - - - - - - - - - - - .|
 *                              |< - - - <-------------------|
 *          Arithmetic coding                  Plain bits
 *          `lc3_put_symbol()`               `lc3_put_bits()`
 * </pre>
 * <li> The forward writing is protected against buffer overflow, it cannot
 *   write after the buffer, but can overwrite plain bits previously
 *   written in the buffer.</li>
 *
 * <li> The backward writing is protected against overwrite of the arithmetic
 *   coder bitstream. In such way, the backward bitstream is always limited
 *   by the aritmetic coder bitstream, and can be overwritten by him.</li>
 * <pre>
 *       .---------------------------------------------------.
 *       | > > > > > > > > > > :         : < < < < < < < < < |
 *       '---------------------------------------------------'
 *       |--------------------. - - - - - - - - - - - - - .|
 *       |< - - - - - - - - - - -  - - - <-------------------|
 *          Arithmetic coding                  Plain bits
 *          `lc3_get_symbol()`               `lc3_get_bits()`
 * </pre>
 * <li> Reading is limited to read of the complementary end of the buffer.</li>
 *
 * <li> The procedure `lc3_check_bits()` returns indication that read has been
 *   made crossing the other bit plane.</li>
 * </ul>
 */
class Bits {

    /**
     * Bitstream mode
     */

    enum Mode {
        READ,
        WRITE,
    }

    /**
     * Arithmetic coder symbol interval
     * The model split the interval in 17 symbols
     */
    static class AcSymbol {

        short low;
        short range;

        public AcSymbol(int low, int range) {
            this.low = (short) low;
            this.range = (short) range;
        }
    }

    static class AcModel {

        AcSymbol[] s; // = new AcSymbol[17];

        public AcModel(int[][] ii) {
            this.s = new AcSymbol[ii.length];
            IntStream.range(0, s.length).forEach(i -> {
                this.s[i] = new AcSymbol(ii[i][0], ii[i][1]);
            });
        }
    }

    /**
     * Bitstream context
     */

    private static final int LC3_ACCU_BITS = 8 * Integer.BYTES;

    private static class Accu {

        int v;
        int n, nover;

        /**
         * Flush the bits accumulator
         *
         * @param buffer Bitstream buffer
         */
        void flush(Buffer buffer) {
            int nbytes = Math.min(n >> 3, Math.max(buffer.pBw - buffer.pFw, 0));

            n -= 8 * nbytes;

            for (; nbytes != 0; v >>= 8, nbytes--)
                buffer.buffer[--buffer.pBw] = (byte) (v & 0xff);

            if (n >= 8)
                n = 0;
        }

        /**
         * Load the accumulator
         *
         * @param buffer Bitstream buffer
         */
        private void load(Buffer buffer) {
            int nBytes = Math.min(n >> 3, buffer.pBw - buffer.start);

            n -= 8 * nBytes;

            int p = buffer.pBw;
            for (; nBytes != 0; nBytes--) {
                v >>= 8;
                v |= (int) buffer.buffer[buffer.pFw + --p] << (LC3_ACCU_BITS - 8);
            }

            if (n >= 8) {
                nover = Math.min(nover + n, LC3_ACCU_BITS);
                v >>= n;
                n = 0;
            }
        }
    }

    private static final int LC3_AC_BITS = 24;

    private static class Ac {
        Buffer buffer;
        Ac(Buffer buffer) {
            this.buffer = buffer;
            this.range = 0xff_ffff;
            this.cache = -1;
        }

        int low, range;
        int cache, carry, carryCount;
        boolean error;

        /**
         * Arithmetic coder return range bits
         *
         * @return 1 + log2(ac.range)
         */
        int getRangeBits() {
            int nBits = 0;

            int r = range;
            while (r != 0) {
                r >>= 1;
                nBits++;
            }

            return nBits;
        }

        /**
         * Arithmetic coder return pending bits
         *
         * @return Pending bits
         */
        int getPendingBits() {
            return 26 - getRangeBits() + ((cache >= 0 ? 1 : 0) + carryCount) * 8;
        }

        /**
         * Arithmetic coder put byte
         *
         * @param _byte  Byte to output
         */
        private void put(int _byte) {
            int p = 0;
            if (p++ < buffer.end)
                buffer.buffer[buffer.pFw + p++] = (byte) _byte;
        }

        /**
         * Arithmetic coder range shift
         */
        void shift() {
            if (low < 0xff_0000 || carry != 0) {
                if (cache >= 0)
                    put(cache + carry);

                for (; carryCount > 0; carryCount--)
                    put(carry != 0 ? 0x00 : 0xff);

                cache = low >> 16;
                carry = 0;
            } else
                carryCount++;

            low = (low << 8) & 0xffffff;
        }

        /**
         * Arithmetic coder termination
         *
         * end_val/nbits   End value and count of bits to terminate (1 to 8)
         */
        void terminate() {
            int nbits = 25 - getRangeBits();
            int mask = 0xff_ffff >> nbits;
            int val = low + mask;
            int high = low + range;

            boolean over_val = (val >> 24) != 0;
            boolean over_high = (high >> 24) != 0;

            val = (val & 0xff_ffff) & ~mask;
            high = (high & 0xff_ffff);

            if (over_val == over_high) {

                if (val + mask >= high) {
                    nbits++;
                    mask >>= 1;
                    val = ((low + mask) & 0xff_ffff) & ~mask;
                }

                carry |= val < low ? 1 : 0;
            }

            low = val;

            for (; nbits > 8; nbits -= 8)
                shift();
            shift();

            int end_val = cache >> (8 - nbits);

            if (carryCount != 0) {
                put(cache);
                for (; carryCount > 1; carryCount--)
                    put(0xff);

                end_val = nbits < 8 ? 0 : 0xff;
            }

            int p = 0;
            if (p++ < buffer.end) {
                int i = buffer.pFw + p;
                byte[] b = buffer.buffer;
                b[i] = (byte) (b[i] & (byte) (0xff >> nbits));
                b[i] = (byte) (b[i] | (byte) (end_val << (8 - nbits)));
            }
        }
    }

    private static class Buffer {

        byte[] buffer;
        int start;
        int end;
        int pFw;
        int pBw;
        Buffer(byte[] buffer, int len) {
            this.buffer = buffer;
            this.start = 0;
            this.end = len;
            this.pFw = 0;
            this.pBw = len;
        }
    }

    private Mode mode;
    private Ac ac;
    private Accu accu;
    private Buffer buffer;

    //
    // Common
    //

    /**
     * Return number of bits left in the bitstream
     *
     * @return >= 0: Number of bits left  < 0: Overflow
     */
    private int get_bits_left() {
        int end = buffer.pBw + (this.mode == READ ? LC3_ACCU_BITS / 8 : 0);

        int start = buffer.pFw - (this.mode == READ ? LC3_AC_BITS / 8 : 0);

        int n = end > start ? (end - start) : -(start - end);

        return 8 * n - (accu.n + accu.nover + ac.getPendingBits());
    }

    /**
     * Setup bitstream reading/writing
     *
     * @param bits   Bitstream context
     * @param mode   Either READ or WRITE mode
     * @param buffer Output buffer
     * @param len    Output bufferlength (in bytes)
     */
    void lc3_setup_bits(Bits bits, Mode mode, byte[] buffer, int len) {
        bits = new Bits();
        bits.mode = mode;
        bits.accu = new Accu();
        bits.accu.n = mode == READ ? LC3_ACCU_BITS : 0;
        bits.buffer = new Buffer(buffer, len);
        bits.ac = new Ac(bits.buffer);

        if (mode == READ) {
            Ac ac = bits.ac;
            Accu accu = bits.accu;
            Buffer _buffer = bits.buffer;

            ac.low = ac_get(_buffer) << 16;
            ac.low |= ac_get(_buffer) << 8;
            ac.low |= ac_get(_buffer);

            accu.load(_buffer);
        }
    }

    /**
     * Return number of bits left in the bitstream
     *
     * @return Number of bits left
     */
    int lc3_get_bits_left() {
        return Math.max(get_bits_left(), 0);
    }

    /**
     * Check if error occured on bitstream reading/writing
     *
     * @return 0: Ok  -1: Bitstream overflow or AC reading error
     */
    int lc3_check_bits() {
        return -(this.get_bits_left() < 0 || ac.error ? 1 : 0);
    }

    /**
     * Put a bit
     *
     * @param v Bit value, 0 or 1
     */
    void lc3_put_bit(int v) {
        lc3_put_bits(v, 1);
    }

    /**
     * Put from 1 to 32 bits
     *
     * @param v Value, in range 0 to 2^n - 1
     * @param n bits count (1 to 32)
     */
    void lc3_put_bits(int v, int n) {
        if (accu.n + n <= LC3_ACCU_BITS) {
            accu.v |= v << accu.n;
            accu.n += n;
        } else {
            lc3_put_bits_generic(v, n);
        }
    }

    /**
     * Put arithmetic coder symbol
     *
     * @param model Model distribution
     * @param s     symbol value
     */
    void lc3_put_symbol(AcModel model, int s) {
        AcSymbol[] symbols = model.s;
        int range = ac.range >> 10;

        ac.low += range * symbols[s].low;
        ac.range = range * symbols[s].range;

        ac.carry |= ac.low >> 24;
        ac.low &= 0xff_ffff;

        if (ac.range < 0x10000)
            lc3_ac_write_renorm();
    }

    //
    // Writing
    //

    /**
     * Flush and terminate bitstream writing
     */
    void lc3_flush_bits() {
        int nLeft = buffer.pBw - buffer.pFw;
        for (int n = 8 * nLeft - accu.n; n > 0; n -= 32)
            lc3_put_bits(0, Math.min(n, 32));

        accu.flush(buffer);

        ac.terminate();
    }

    /**
     * Get a bit
     */
    int lc3_get_bit() {
        return lc3_get_bits(1);
    }

    /**
     * Get from 1 to 32 bits
     *
     * @param n Number of bits to read (1 to 32)
     * @return The value read
     */
    int lc3_get_bits(int n) {
        if (accu.n + n <= LC3_ACCU_BITS) {
            int v = (accu.v >> accu.n) & ((1 << n) - 1);
            accu.n += n;
            return v;
        } else {
            return lc3_get_bits_generic(n);
        }
    }

    /**
     * Get arithmetic coder symbol
     *
     * @param model Model distribution
     * @return The value read
     */
    int lc3_get_symbol(AcModel model) {
        AcSymbol[] symbols = model.s;

        int range = (ac.range >> 10) & 0xffff;

        ac.error |= (ac.low >= (range << 10));
        if (ac.error)
            ac.low = 0;

        int s = 16;

        if (ac.low < range * symbols[s].low) {
            s >>= 1;
            s -= ac.low < range * symbols[s].low ? 4 : -4;
            s -= ac.low < range * symbols[s].low ? 2 : -2;
            s -= ac.low < range * symbols[s].low ? 1 : -1;
            s -= ac.low < range * symbols[s].low ? 1 : 0;
        }

        ac.low -= range * symbols[s].low;
        ac.range = range * symbols[s].range;

        if (ac.range < 0x10000)
            lc3_ac_read_renorm();

        return s;
    }

    //
    // Inline implementations
    //

    void lc3_put_bits_generic(int v, int n) {

        // Fulfill accumulator and flush

        int n1 = Math.min(LC3_ACCU_BITS - accu.n, n);
        if (n1 != 0) {
            accu.v |= v << accu.n;
            accu.n = LC3_ACCU_BITS;
        }

        accu.flush(this.buffer);

        // Accumulate remaining bits

        accu.v = v >> n1;
        accu.n = n - n1;
    }

    int lc3_get_bits_generic(int n) {

        // Fulfill accumulator and read

        accu.load(buffer);

        int n1 = Math.min(LC3_ACCU_BITS - accu.n, n);
        int v = (accu.v >> accu.n) & ((1 << n1) - 1);
        accu.n += n1;

        // Second round

        int n2 = n - n1;

        if (n2 != 0) {
            accu.load(buffer);

            v |= ((accu.v >> accu.n) & ((1 << n2) - 1)) << n1;
            accu.n += n2;
        }

        return v;
    }

    void lc3_ac_read_renorm() {

        for (; ac.range < 0x10000; ac.range <<= 8)
            ac.low = ((ac.low << 8) | ac_get(this.buffer)) & 0xff_ffff;
    }

    void lc3_ac_write_renorm() {

        for (; ac.range < 0x10000; ac.range <<= 8)
            ac.shift();
    }

    //
    // Reading
    //

    /**
     * Arithmetic coder get byte
     *
     * @param buffer Bitstream buffer
     * @return Byte read, 0 on overflow
     */
    private int ac_get(Buffer buffer) {
        int p = 0;
        return p < buffer.end ? buffer.buffer[buffer.pFw + p++] : 0;
    }
}
