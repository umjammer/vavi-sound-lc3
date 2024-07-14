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

import java.util.Arrays;
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
    private static class AcSymbol {

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
            IntStream.range(0, s.length).forEach(i -> this.s[i] = new AcSymbol(ii[i][0], ii[i][1]));
        }
    }

    /*
     * Bitstream context
     */

    private static final int LC3_ACCU_BITS = 8 * Integer.BYTES;

    /**
     * struct lc3_bits_accu
     */
    private static class Accu {

        Buffer buffer;

        /**
         * @param buffer Bitstream buffer
         * @param mode Bitstream mode
         */
        Accu(Buffer buffer, Mode mode) {
            this.buffer = buffer;
            this.n = mode == READ ? LC3_ACCU_BITS : 0;
        }

        int v;
        int n, nover;

        /**
         * Flush the bits accumulator
         */
        void flush() {
            int nbytes = Math.min(n >>> 3, Math.max(buffer.pBw - this.buffer.pFw, 0));

            n -= 8 * nbytes;

            for (; nbytes != 0; v >>>= 8, nbytes--)
                buffer.buffer[--buffer.pBw] = (byte) (v & 0xff);

            if (n >= 8)
                n = 0;
        }

        /**
         * Load the accumulator
         */
        void load() {
            int nBytes = Math.min(n >> 3, buffer.pBw - buffer.start);

            n -= 8 * nBytes;

            for (; nBytes != 0; nBytes--) {
                v >>>= 8;
                v |= (buffer.buffer[--buffer.pBw] & 0xff) << (LC3_ACCU_BITS - 8);
            }

            if (n >= 8) {
                nover = Math.min(nover + n, LC3_ACCU_BITS);
                v >>>= n;
                n = 0;
            }
        }

        /**
         * Write from 1 to 32 bits,
         * exceeding the capacity of the accumulator
         */
        void put_bits_generic(int v, int n) {

            // Fulfill accumulator and flush

            int n1 = Math.min(LC3_ACCU_BITS - this.n, n);
            if (n1 != 0) {
                this.v |= v << this.n;
                this.n = LC3_ACCU_BITS;
            }

            this.flush();

            // Accumulate remaining bits

            this.v = v >>> n1;
            this.n = n - n1;
        }

        /**
         * Read from 1 to 32 bits,
         * exceeding the capacity of the accumulator
         */
        int get_bits_generic(int n) {

            // Fulfill accumulator and read

            this.load();

            int n1 = Math.min(LC3_ACCU_BITS - this.n, n);
            int v = (this.v >>> this.n) & ((1 << n1) - 1);
            this.n += n1;

            // Second round

            int n2 = n - n1;

            if (n2 != 0) {
                this.load();

                v |= ((this.v >>> this.n) & ((1 << n2) - 1)) << n1;
                this.n += n2;
            }
            return v;
        }

        /**
         * Put from 1 to 32 bits
         *
         * @param v Value, in range 0 to 2^n - 1
         * @param n bits count (1 to 32)
         */
        void put_bits(int v, int n) {
            if (this.n + n <= LC3_ACCU_BITS) {
                this.v |= v << this.n;
                this.n += n;
            } else {
                this.put_bits_generic(v, n);
            }
        }

        /**
         * Get from 1 to 32 bits
         *
         * @param n Number of bits to read (1 to 32)
         * @return The value read
         */
        int get_bits(int n) {
            if (this.n + n <= LC3_ACCU_BITS) {
                int v = (this.v >>> this.n) & ((1 << n) - 1);
                this.n += n;
                return v;
            } else {
                return this.get_bits_generic(n);
            }
        }

        /** */
        void terminate() {
            int nLeft = buffer.pBw - buffer.pFw;
            for (int n = 8 * nLeft - this.n; n > 0; n -= 32)
                put_bits(0, Math.min(n, 32));

            this.flush();
        }
    }

    private static final int LC3_AC_BITS = 24;

    /**
     * struct lc3_bits_ac
     */
    private static class Ac {

        Buffer buffer;

        /**
         * @param buffer Bitstream buffer
         */
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
                r >>>= 1;
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
         * @param _byte Byte to output
         */
        private void put(int _byte) {
            if (buffer.pFw < buffer.end)
                buffer.buffer[buffer.pFw++] = (byte) (_byte & 0xff);
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

                cache = low >>> 16;
                carry = 0;
            } else
                carryCount++;

            low = (low << 8) & 0xff_ffff;
        }

        /**
         * Arithmetic coder termination
         *
         * end_val/nbits   End value and count of bits to terminate (1 to 8)
         */
        void terminate() {
            int nbits = 25 - getRangeBits();
            int mask = 0xff_ffff >>> nbits;
            int val = low + mask;
            int high = low + range;

            boolean over_val = (val >>> 24) != 0;
            boolean over_high = (high >>> 24) != 0;

            val = (val & 0xff_ffff) & ~mask;
            high = high & 0xff_ffff;

            if (over_val == over_high) {

                if (val + mask >= high) {
                    nbits++;
                    mask >>>= 1;
                    val = ((low + mask) & 0xff_ffff) & ~mask;
                }

                carry |= val < low ? 1 : 0;
            }

            low = val;

            for (; nbits > 8; nbits -= 8)
                shift();
            shift();

            int end_val = cache >>> (8 - nbits);

            if (carryCount != 0) {
                put(cache);
                for (; carryCount > 1; carryCount--)
                    put(0xff);

                end_val = nbits < 8 ? 0 : 0xff;
            }

            if (buffer.pFw < buffer.end) {
                buffer.buffer[buffer.pFw] = (byte) (buffer.buffer[buffer.pFw] & (0xff >> nbits));
                buffer.buffer[buffer.pFw] = (byte) (buffer.buffer[buffer.pFw] | (end_val << (8 - nbits)));
            }
        }

        /**
         * Put arithmetic coder symbol
         *
         * @param model Model distribution
         * @param s     symbol value
         */
        void put_symbol(AcModel model, int s) {
            AcSymbol[] symbols = model.s;
            int range = this.range >> 10;

            this.low += range * symbols[s].low;
            this.range = range * symbols[s].range;

            this.carry |= this.low >>> 24;
            this.low &= 0xff_ffff;

            if (this.range < 0x10000)
                write_renorm();
        }

        int get_symbol(AcModel model) {
            AcSymbol[] symbols = model.s;

            int range = (this.range >> 10) & 0xffff;

            this.error |= (this.low >= (range << 10));
            if (this.error)
                this.low = 0;

            int s = 16;

            if (this.low < range * symbols[s].low) {
                s >>>= 1;
                s -= this.low < range * symbols[s].low ? 4 : -4;
                s -= this.low < range * symbols[s].low ? 2 : -2;
                s -= this.low < range * symbols[s].low ? 1 : -1;
                s -= this.low < range * symbols[s].low ? 1 : 0;
            }

            this.low -= range * symbols[s].low;
            this.range = range * symbols[s].range;

            if (this.range < 0x10000)
                read_renorm();

            return s;
        }

        /**
         * Arithmetic coder renormalization
         */
        void write_renorm() {
            for (; this.range < 0x1_0000; this.range <<= 8)
                this.shift();
        }

        /**
         * Arithmetic coder renormalization
         */
        void read_renorm() {
            for (; this.range < 0x1_0000; this.range <<= 8)
                this.low = ((this.low << 8) | this.buffer.ac_get()) & 0xff_ffff;
        }
    }

    /**
     * struct lc3_bits_buffer
     */
    private static class Buffer {

        byte[] buffer;
        int start;
        int end;
        int pFw;
        int pBw;
        Buffer(byte[] buffer, int offset, int len) {
            this.buffer = Arrays.copyOfRange(buffer, offset, offset + len);
            this.start = 0;
            this.end = len;
            this.pFw = 0;
            this.pBw = len;
        }

        /**
         * Arithmetic coder get byte
         *
         * @return Byte read, 0 on overflow
         */
        private int ac_get() {
            return this.pFw < this.end ? this.buffer[this.pFw++] & 0xff : 0;
        }
    }

    // struct lc3_bits

    private final Mode mode;
    private final Ac ac;
    private final Accu accu;
    private final Buffer buffer;

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
     * @param mode   Either READ or WRITE mode
     * @param buffer Output buffer
     * @param len    Output buffer length (in bytes)
     */
    Bits(Mode mode, byte[] buffer, int offset, int len) {
        this.mode = mode;
        this.buffer = new Buffer(buffer, offset, len);
        this.accu = new Accu(this.buffer, mode);
        this.ac = new Ac(this.buffer);

        if (mode == READ) {
            this.ac.low = this.buffer.ac_get() << 16;
            this.ac.low |= this.buffer.ac_get() << 8;
            this.ac.low |= this.buffer.ac_get();

            this.accu.load();
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

    //
    // Inline implementations
    //

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
        accu.put_bits(v, n);
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
        return accu.get_bits(n);
    }

    /**
     * Put arithmetic coder symbol
     *
     * @param model Model distribution
     * @param s     symbol value
     */
    void lc3_put_symbol(Bits.AcModel model, int s) {
        ac.put_symbol(model, s);
    }

    /**
     * Get arithmetic coder symbol
     *
     * @param model Model distribution
     * @return The value read
     */
    int lc3_get_symbol(AcModel model) {
        return ac.get_symbol(model);
    }

    //
    // Writing
    //

    /**
     * Flush and terminate bitstream writing
     */
    void lc3_flush_bits() {
        accu.terminate();
        ac.terminate();
    }

    /**
     * Arithmetic coder renormalization
     */
    void lc3_ac_write_renorm() {
        ac.write_renorm();
    }

    //
    // Reading
    //

    /**
     * Arithmetic coder renormalization
     */
    void lc3_ac_read_renorm() {
        ac.read_renorm();
    }
}
