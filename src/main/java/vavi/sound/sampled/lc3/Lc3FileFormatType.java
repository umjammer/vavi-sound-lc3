/*
 * Copyright (c) 2023 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.sampled.lc3;

import javax.sound.sampled.AudioFileFormat;


/**
 * FileFormatTypes used by the LC3 audio decoder.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2023/05/31 umjammer initial version <br>
 */
public class Lc3FileFormatType extends AudioFileFormat.Type {

    /**
     * Specifies an LC3 file.
     */
    public static final AudioFileFormat.Type LC3 = new Lc3FileFormatType("LC3", "lc3");

    /**
     * Constructs a file type.
     *
     * @param name the name of the LC3 File Format.
     * @param extension the file extension for this LC3 File Format.
     */
    public Lc3FileFormatType(String name, String extension) {
        super(name, extension);
    }
}

/* */
