package io.github.nyzeep.orchestella;

import org.junit.Test;
import static org.junit.Assert.*;

public class DesktopKeyMapperTest {
    @Test public void mapsPhysicalLettersNavigationAndModifiersToLinuxCodes() {
        assertEquals(30, DesktopKeyMapper.linuxCode(29));
        assertEquals(44, DesktopKeyMapper.linuxCode(54));
        assertEquals(103, DesktopKeyMapper.linuxCode(19));
        assertEquals(106, DesktopKeyMapper.linuxCode(22));
        assertEquals(29, DesktopKeyMapper.linuxCode(113));
        assertEquals(97, DesktopKeyMapper.linuxCode(114));
        assertEquals(125, DesktopKeyMapper.linuxCode(117));
        assertEquals(0, DesktopKeyMapper.linuxCode(24));
        assertEquals(0, DesktopKeyMapper.linuxCode(4));
        assertEquals(15, DesktopKeyMapper.modifiers(1 | 2 | 65536 | 1048576));
        assertEquals(0, DesktopKeyMapper.modifiers(1048576 * 2));
    }
}
