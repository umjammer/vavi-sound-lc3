/*
 * Copyright (c) 2023 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package vavi.sound.sampled.lc3;

import java.io.BufferedInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.spi.AudioFileReader;
import javax.sound.sampled.spi.FormatConversionProvider;

import vavi.util.Debug;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static vavi.sound.SoundUtil.volume;
import static vavix.util.DelayedWorker.later;


/**
 * Lc3FormatConversionProviderTest.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2023/05/31 umjammer initial version <br>
 */
@PropsEntity(url = "file:local.properties")
class Lc3FormatConversionProviderTest {

    static boolean localPropertiesExists() {
        return Files.exists(Paths.get("local.properties"));
    }

    static final long time;

    static {
        time = System.getProperty("vavi.test", "").equals("ide") ? 1000 * 1000 : 10 * 1000;
    }

    @Property(name = "lc3file")
    String lc3 = "src/test/resources/test.lc3"; // TODO google engine cannot handle this file bec this is 44100hz

    @Property(name = "vavi.test.volume")
    double volume = 0.2;

    @Property
    boolean google;

    @BeforeEach
    void setup() throws Exception {
        if (localPropertiesExists()) {
            PropsEntity.Util.bind(this);
        }

        if (google) System.setProperty(Lc3FormatConversionProvider.ENGINE_KEY, "true");
Debug.println("volume: " + volume + ", google: " + System.getProperty(Lc3FormatConversionProvider.ENGINE_KEY, "false"));
    }

    @Test
    void testX() throws Exception {
        ServiceLoader<AudioFileReader> loader = ServiceLoader.load(AudioFileReader.class);
        AtomicBoolean result = new AtomicBoolean();
        loader.forEach(spi -> {
System.err.println(spi);
            if (spi.getClass().getName().contains("lc3")) {
                result.set(true);
            }
        });
        assertTrue(result.get());

        ServiceLoader<FormatConversionProvider> loader2 = ServiceLoader.load(FormatConversionProvider.class);
        AtomicBoolean result2 = new AtomicBoolean();
        loader2.forEach(spi -> {
System.err.println(spi);
            if (spi.getClass().getName().contains("lc3")) {
                result2.set(true);
            }
        });
        assertTrue(result2.get());
    }

    @Test
    @DisplayName("directly")
    void test0() throws Exception {
Debug.print(lc3);
        Path path = Path.of(lc3);
        AudioInputStream sourceAis = new Lc3AudioFileReader().getAudioInputStream(new BufferedInputStream(Files.newInputStream(path)));

        AudioFormat inAudioFormat = sourceAis.getFormat();
Debug.println("IN: " + inAudioFormat);
        AudioFormat outAudioFormat = new AudioFormat(
            sourceAis.getFormat().getSampleRate(),
            16,
            sourceAis.getFormat().getChannels(),
            true,
            false);
Debug.println("OUT: " + outAudioFormat);

        assertTrue(AudioSystem.isConversionSupported(outAudioFormat, inAudioFormat));

        AudioInputStream pcmAis = new Lc3FormatConversionProvider().getAudioInputStream(outAudioFormat, sourceAis);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, pcmAis.getFormat());
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(pcmAis.getFormat());
        line.addLineListener(ev -> Debug.println(ev.getType()));
        line.start();

        volume(line, volume);

        byte[] buf = new byte[1024];
        while (!later(time).come()) {
            int r = pcmAis.read(buf, 0, 1024);
            if (r < 0) {
                break;
            }
            line.write(buf, 0, r);
        }
        line.drain();
        line.stop();
        line.close();
    }

    @Test
    @DisplayName("via spi")
    void test1() throws Exception {
Debug.print(lc3);
        Path path = Path.of(lc3);
        AudioInputStream sourceAis = AudioSystem.getAudioInputStream(new BufferedInputStream(Files.newInputStream(path)));

        AudioFormat inAudioFormat = sourceAis.getFormat();
Debug.println("IN: " + inAudioFormat);
        AudioFormat outAudioFormat = new AudioFormat(
            sourceAis.getFormat().getSampleRate(),
            16,
            sourceAis.getFormat().getChannels(),
            true,
            false);
Debug.println("OUT: " + outAudioFormat);

        assertTrue(AudioSystem.isConversionSupported(outAudioFormat, inAudioFormat));

        AudioInputStream pcmAis = AudioSystem.getAudioInputStream(outAudioFormat, sourceAis);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, pcmAis.getFormat());
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(pcmAis.getFormat());
        line.addLineListener(ev -> Debug.println(ev.getType()));
        line.start();

        volume(line, volume);

        byte[] buf = new byte[1024];
        while (!later(time).come()) {
            int r = pcmAis.read(buf, 0, 1024);
            if (r < 0) {
                break;
            }
            line.write(buf, 0, r);
        }
        line.drain();
        line.stop();
        line.close();
    }

    @Test
    @Disabled("TODO? java.lang.IllegalArgumentException: invalid frame size: NOT_SPECIFIED")
    void test3() throws Exception {
        AudioInputStream ais = AudioSystem.getAudioInputStream(Path.of(lc3).toFile());
        Clip clip = AudioSystem.getClip();
        clip.open(ais);
        clip.loop(1);
    }
}
