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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vavi.sound.SoundUtil;
import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import static vavix.util.DelayedWorker.later;


/**
 * TestCase.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (nsano)
 * @version 0.00 2023-02-05 nsano initial version <br>
 */
@PropsEntity(url = "file:local.properties")
class TestCase {

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    @Property
    String lc3file = "src/test/resources/test.lc3";

    @BeforeEach
    void setup() throws Exception {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }
    }

    static final long time = System.getProperty("vavi.test", "").equals("ide") ? 1000 * 1000 : 10 * 1000;

    static final double volume = Double.parseDouble(System.getProperty("vavi.test.volume",  "0.2"));

    @Test
    @DisplayName("list available lines")
    void test0() throws Exception {
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        for (Mixer.Info mixerInfo : mixers){
            System.out.println(mixerInfo);
            Mixer m = AudioSystem.getMixer(mixerInfo);
            Line.Info[] lines = m.getTargetLineInfo();
            for (Line.Info li : lines){
                try {
                    m.open();
                    if (li instanceof DataLine.Info dataLineInfo) {
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
    void test1() throws Exception {
        Path p = Paths.get(lc3file);
        InputStream is = new BufferedInputStream(Files.newInputStream(p));
        Lc3Plus lc3Plus = new Lc3Plus(is);

        var af = new AudioFormat(lc3Plus.getSampleRate(), 16, lc3Plus.getChannels(), true, false);
Debug.println(af);
        SourceDataLine line = AudioSystem.getSourceDataLine(af);
        line.open();
        line.start();
        SoundUtil.volume(line, volume);
        while (!later(time).come()) {
            try {
                int nBytes = lc3Plus.read();
                byte[] decodedData = lc3Plus.decode(nBytes);
//Debug.println(decodedData.length);
                line.write(decodedData, 0, decodedData.length);
            } catch (EOFException e) {
                break;
            }
        }
        line.drain();
        line.close();
    }
}
