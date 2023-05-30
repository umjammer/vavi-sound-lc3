/*
 * Copyright (c) 2022 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.lc3;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.Line;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.SourceDataLine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import vavi.sound.SoundUtil;
import vavi.util.Debug;


/**
 * Test1.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2023-02-05 nsano initial version <br>
 */
class Test1 {

    @Test
    void test0() throws Exception {
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        for (Mixer.Info mixerInfo : mixers){
            System.out.println(mixerInfo);
            Mixer m = AudioSystem.getMixer(mixerInfo);
            Line.Info[] lines = m.getTargetLineInfo();
            for (Line.Info li : lines){
                try {
                    m.open();
                    if (li instanceof DataLine.Info) {
                        final DataLine.Info dataLineInfo = (DataLine.Info) li;
                        Arrays.stream(dataLineInfo.getFormats())
                                .forEach(format -> System.out.println(" " + format.toString()));
                    }
                } catch (LineUnavailableException e){
                    System.out.println("Line unavailable.");
                }
            }
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "vavi.test", matches = "ide")
    void test1() throws Exception {
        Lc3Plus lc3Plus = new Lc3Plus();
        Path p = Paths.get("tmp", "hoshi_96k.lc3");
        InputStream is = new BufferedInputStream(Files.newInputStream(p));
        lc3Plus.header(is);
        lc3Plus.init();

        AudioFormat af = new AudioFormat(lc3Plus.sampleRate, 16, lc3Plus.nChannels, true, false);
Debug.println(af);
        SourceDataLine line = AudioSystem.getSourceDataLine(af);
        line.open();
        line.start();
        SoundUtil.volume(line, .01f);
        while (true) {
            try {
                int nBytes = lc3Plus.read();
                byte[] decodedData = lc3Plus.decode(nBytes);
Debug.println(decodedData.length);
                line.write(decodedData, 0, decodedData.length);
            } catch (EOFException e) {
                break;
            }
        }
        line.drain();
        line.close();
    }
}

/* */
