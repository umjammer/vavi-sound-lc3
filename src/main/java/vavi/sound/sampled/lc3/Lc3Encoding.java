/*
 * Copyright (c) 2023 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.sampled.lc3;

import javax.sound.sampled.AudioFormat;


/**
 * Encodings used by the LC3 audio decoder.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2023/05/31 umjammer initial version <br>
 */
public class Lc3Encoding extends AudioFormat.Encoding {

    /** Specifies any LC3 encoded data. */
    public static final Lc3Encoding LC3 = new Lc3Encoding("LC3");

    /**
     * Constructs a new encoding.
     *
     * @param name Name of the LC3 encoding.
     */
    public Lc3Encoding(String name) {
        super(name);
    }
}
