package gg.bde.importer;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class LimitsTest {
    private static YamlConfiguration config(String yaml) throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.loadFromString(yaml);
        return config;
    }

    @Test void bundledDefaultsPreserveExistingBehavior() throws Exception {
        Limits limits = Limits.from(config(""));
        assertAll(
                () -> assertEquals(1_048_576, limits.responseBytes()),
                () -> assertEquals(128, limits.parts()),
                () -> assertEquals(8192, limits.partBytes()),
                () -> assertEquals(131_072, limits.modelBytes()),
                () -> assertEquals(32, limits.nesting()),
                () -> assertEquals(5000, limits.connectMillis()),
                () -> assertEquals(3000, limits.readMillis()),
                () -> assertEquals(20_000, limits.requestMillis()),
                () -> assertEquals(120_000_000_000L, limits.jobNanos()),
                () -> assertEquals(5_000_000_000L, limits.cooldownNanos()),
                () -> assertEquals(2, limits.stepTicks()),
                () -> assertEquals(256, limits.chunkDisplays()),
                () -> assertEquals(40, limits.busyTickMillis()),
                () -> assertEquals(64, limits.matrixMaxAbsolute()),
                () -> assertEquals(0.5f, limits.viewRange()));
    }

    @Test void loadsAllCustomValuesAndConvertsTimeUnits() throws Exception {
        Limits limits = Limits.from(config("""
                limits:
                  response-bytes: 10000
                  parts: 3
                  part-bytes: 2000
                  model-bytes: 5000
                  nesting: 16
                  connect-timeout-millis: 600
                  read-timeout-millis: 700
                  request-timeout-millis: 800
                  job-timeout-millis: 900
                  cooldown-millis: 0
                  step-ticks: 4
                  chunk-displays: 8
                  busy-tick-millis: 25.5
                  matrix-max-absolute: 2.5
                  view-range: 0.25
                """));
        assertAll(
                () -> assertEquals(10000, limits.responseBytes()),
                () -> assertEquals(3, limits.parts()),
                () -> assertEquals(2000, limits.partBytes()),
                () -> assertEquals(5000, limits.modelBytes()),
                () -> assertEquals(16, limits.nesting()),
                () -> assertEquals(600, limits.connectMillis()),
                () -> assertEquals(700, limits.readMillis()),
                () -> assertEquals(800, limits.requestMillis()),
                () -> assertEquals(900_000_000L, limits.jobNanos()),
                () -> assertEquals(0, limits.cooldownNanos()),
                () -> assertEquals(4, limits.stepTicks()),
                () -> assertEquals(8, limits.chunkDisplays()),
                () -> assertEquals(25.5, limits.busyTickMillis()),
                () -> assertEquals(2.5, limits.matrixMaxAbsolute()),
                () -> assertEquals(0.25f, limits.viewRange()));
    }

    @Test void missingKeysUseDefaults() throws Exception {
        Limits limits = Limits.from(config("limits: {parts: 2}"));
        assertEquals(2, limits.parts());
        assertEquals(8192, limits.partBytes());
        assertEquals(0.5f, limits.viewRange());
    }

    @Test void rejectsWrongIntegerTypesRangesAndSections() throws Exception {
        for (String value : new String[]{"0", "-1", "1.5", "'128'", "true", "2147483648", "[]", "{}"}) {
            YamlConfiguration yaml = config("limits: {parts: " + value + "}");
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> Limits.from(yaml));
            assertTrue(error.getMessage().contains("limits.parts"));
        }
        assertThrows(IllegalArgumentException.class, () -> Limits.from(config("limits: false")));
        assertThrows(IllegalArgumentException.class, () -> Limits.from(config("limits: {nesting: 65}")));
        assertThrows(IllegalArgumentException.class, () -> Limits.from(config("limits: {read-timeout-millis: 0}")));
    }

    @Test void rejectsNonFiniteOrUnrepresentableDecimals() throws Exception {
        for (String value : new String[]{"0", "-1", ".nan", ".inf", "1.0e100", "1.0e-100", "'0.5'"}) {
            YamlConfiguration yaml = config("limits: {view-range: " + value + "}");
            assertThrows(IllegalArgumentException.class, () -> Limits.from(yaml));
        }
        assertThrows(IllegalArgumentException.class, () -> Limits.from(config("limits: {busy-tick-millis: .nan}")));
        assertThrows(IllegalArgumentException.class, () -> Limits.from(config("limits: {matrix-max-absolute: 0}")));
    }

    @Test void countsRootEvenWhenChunkIsEmpty() throws Exception {
        Limits limits = Limits.from(config("limits: {chunk-displays: 2}"));
        assertEquals(0, limits.chunkCapacity(1));
        assertThrows(IOException.class, () -> limits.chunkCapacity(2));
    }

    @Test void customLimitsReachRealModelParser() throws Exception {
        String block = "{id:'minecraft:block_display'}";
        ModelParser limited = new ModelParser(Limits.from(config("limits: {parts: 1}")));
        assertThrows(IOException.class, () -> limited.parse(ModelParserTest.response(block, block), "26.3"));
        ModelParser normal = new ModelParser(Limits.from(config("")));
        assertEquals(2, normal.parse(ModelParserTest.response(block, block), "26.3").parts().size());
        ModelParser rendering = new ModelParser(Limits.from(config("limits: {view-range: 0.25}")));
        String part = rendering.parse(ModelParserTest.response(block), "26.3").parts().getFirst();
        assertEquals(0.25f, net.kyori.adventure.nbt.TagStringIO.tagStringIO().asCompound(part).getFloat("view_range"));
    }

    @Test void customParserBoundsAndMessagesUseConfiguredValues() throws Exception {
        String block = "{id:'minecraft:block_display'}";
        ModelParser data = new ModelParser(Limits.from(config("limits: {part-bytes: 1}")));
        assertTrue(assertThrows(IOException.class, () -> data.parse(ModelParserTest.response(block), "26.3"))
                .getMessage().contains("1 字节"));
        ModelParser model = new ModelParser(Limits.from(config("limits: {model-bytes: 1}")));
        assertTrue(assertThrows(IOException.class, () -> model.parse(ModelParserTest.response(block), "26.3"))
                .getMessage().contains("1 字节"));
        ModelParser depth = new ModelParser(Limits.from(config("limits: {nesting: 2}")));
        assertTrue(assertThrows(IOException.class, () -> depth.checkDepth("[[[]]]")).getMessage().contains("2 层"));
        ModelParser matrix = new ModelParser(Limits.from(config("limits: {matrix-max-absolute: 0.5}")));
        String fixture = ModelParserTest.response("{id:'minecraft:block_display',transformation:[1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f]}");
        assertThrows(IOException.class, () -> matrix.parse(fixture, "26.3"));
    }
}
