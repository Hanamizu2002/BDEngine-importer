package gg.bde.importer;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BdeImporterTest {
    @Test void completesEverySubcommandAndFiltersPrefixes() {
        assertEquals(List.of("spawn", "cancel", "help"), BdeImporter.complete(""));
        assertEquals(List.of("spawn"), BdeImporter.complete("SP"));
        assertEquals(List.of("cancel"), BdeImporter.complete("ca"));
        assertEquals(List.of("help"), BdeImporter.complete("h"));
        assertTrue(BdeImporter.complete("unknown").isEmpty());
    }

    @Test void acceptsOnlySpawnWithOneSixDigitId() {
        assertTrue(BdeImporter.isSpawn(new String[]{"spawn", "123456"}));
        assertTrue(BdeImporter.isSpawn(new String[]{"SPAWN", "000001"}));
        for (String[] args : List.of(new String[]{}, new String[]{"spawn"},
                new String[]{"cancel", "123456"}, new String[]{"spawn", "12345"},
                new String[]{"spawn", "１２３４５６"}, new String[]{"spawn", "123456&x=1"},
                new String[]{"spawn", "123456", "extra"})) {
            assertFalse(BdeImporter.isSpawn(args));
        }
    }
}
