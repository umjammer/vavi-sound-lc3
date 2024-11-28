/*
 * Copyright (c) 2024 by Naohide Sano, All rights reserved.
 *
 * Programmed by Naohide Sano
 */

package google.sound.lc3;

import java.nio.file.Files;
import java.nio.file.Paths;

import google.sound.lc3.Tables.lc3_mdct_rot_def;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import vavi.util.properties.annotation.Property;
import vavi.util.properties.annotation.PropsEntity;

import static google.sound.lc3.Lc3.Duration._2M5;
import static org.junit.jupiter.api.Assertions.assertEquals;


/**
 * Lc3Test.
 *
 * @author <a href="mailto:umjammer@gmail.com">Naohide Sano</a> (umjammer)
 * @version 0.00 2024/06/03 umjammer initial version <br>
 */
@PropsEntity(url = "file:local.properties")
class Lc3Test {

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

    static double volume = Double.parseDouble(System.getProperty("vavi.test.volume",  "0.2"));

    @Test
    @DisplayName("table loading test")
    void test1() throws Exception {
        System.setProperty("google.sound.lc3.plus", "true");
        lc3_mdct_rot_def d0 = Tables.lc3_mdct_rot.get(_2M5)[0];
        assertEquals(10, d0.n4);
        assertEquals(10, d0.w.length);
    }

    @Test
    @EnabledIf("localPropertiesExists")
    void test2() throws Exception {
//        System.setProperty("google.sound.lc3.plus", "true");
//        System.setProperty("google.sound.lc3.hr", "true");
        Decoder.main(new String[] {lc3file});
    }
}