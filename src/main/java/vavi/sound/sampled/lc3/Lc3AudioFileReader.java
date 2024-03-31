/*
 * Copyright (c) 2023 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.sampled.lc3;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.logging.Level;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.spi.AudioFileReader;

import vavi.sound.lc3.Lc3Plus;
import vavi.util.Debug;


/**
 * Provider for LC3 audio file reading services. This implementation can parse
 * the format information from LC3 audio file, and can produce audio input
 * streams from files of this type.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2023/05/31 umjammer initial version <br>
 */
public class Lc3AudioFileReader extends AudioFileReader {

    @Override
    public AudioFileFormat getAudioFileFormat(File file) throws UnsupportedAudioFileException, IOException {
        try (InputStream inputStream = Files.newInputStream(file.toPath())) {
            return getAudioFileFormat(inputStream, (int) file.length());
        }
    }

    @Override
    public AudioFileFormat getAudioFileFormat(URL url) throws UnsupportedAudioFileException, IOException {
        try (InputStream inputStream = url.openStream()) {
            return getAudioFileFormat(inputStream);
        }
    }

    @Override
    public AudioFileFormat getAudioFileFormat(InputStream stream) throws UnsupportedAudioFileException, IOException {
        return getAudioFileFormat(stream, AudioSystem.NOT_SPECIFIED);
    }

    /**
     * Return the AudioFileFormat from the given InputStream. Implementation.
     *
     * @param bitStream
     * @param mediaLength
     * @return an AudioInputStream object based on the audio file data contained
     *         in the input stream.
     * @exception UnsupportedAudioFileException if the File does not point to a
     *                valid audio file data recognized by the system.
     * @exception IOException if an I/O exception occurs.
     */
    protected AudioFileFormat getAudioFileFormat(InputStream bitStream, int mediaLength) throws UnsupportedAudioFileException, IOException {
//Debug.println("here: " + bitStream.markSupported());
Debug.println(Level.FINE, "enter available: " + bitStream.available());
        Lc3Plus lc3Plus;
        try {
            lc3Plus = new Lc3Plus(bitStream);
        } catch (IllegalArgumentException e) {
Debug.println(Level.FINER, "error exit available: " + bitStream.available() + ", " + e.getMessage());
Debug.printStackTrace(Level.FINEST, e);
            throw (UnsupportedAudioFileException) new UnsupportedAudioFileException(e.getMessage()).initCause(e);
        }
        // TODO AudioSystem.NOT_SPECIFIED cause IllegalArgumentException at Clip#open()
        AudioFormat format = new AudioFormat(Lc3Encoding.LC3, lc3Plus.getSampleRate(), lc3Plus.getSampleSizeInBit(), lc3Plus.getChannels(), AudioSystem.NOT_SPECIFIED, AudioSystem.NOT_SPECIFIED, true, new HashMap<>() {{
            put("lc3Plus", lc3Plus);
        }});
        return new AudioFileFormat(Lc3FileFormatType.LC3, format, AudioSystem.NOT_SPECIFIED);
    }

    @Override
    public AudioInputStream getAudioInputStream(File file) throws UnsupportedAudioFileException, IOException {
        InputStream inputStream = Files.newInputStream(file.toPath());
        try {
            return getAudioInputStream(inputStream, (int) file.length());
        } catch (UnsupportedAudioFileException | IOException e) {
            inputStream.close();
            throw e;
        }
    }

    @Override
    public AudioInputStream getAudioInputStream(URL url) throws UnsupportedAudioFileException, IOException {
        InputStream inputStream = url.openStream();
        try {
            return getAudioInputStream(inputStream);
        } catch (UnsupportedAudioFileException | IOException e) {
            inputStream.close();
            throw e;
        }
    }

    @Override
    public AudioInputStream getAudioInputStream(InputStream stream) throws UnsupportedAudioFileException, IOException {
        return getAudioInputStream(stream, AudioSystem.NOT_SPECIFIED);
    }

    /**
     * Obtains an audio input stream from the input stream provided. The stream
     * must point to valid audio file data.
     *
     * @param inputStream the input stream from which the AudioInputStream
     *            should be constructed.
     * @param mediaLength
     * @return an AudioInputStream object based on the audio file data contained
     *         in the input stream.
     * @exception UnsupportedAudioFileException if the File does not point to a
     *                valid audio file data recognized by the system.
     * @exception IOException if an I/O exception occurs.
     */
    protected AudioInputStream getAudioInputStream(InputStream inputStream, int mediaLength) throws UnsupportedAudioFileException, IOException {
        AudioFileFormat audioFileFormat = getAudioFileFormat(inputStream, mediaLength);
        return new AudioInputStream(inputStream, audioFileFormat.getFormat(), audioFileFormat.getFrameLength());
    }
}
