package gg.bde.importer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.nbt.TagStringIO;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ModelParserTest {
    private static final Limits DEFAULTS = Limits.from(new org.bukkit.configuration.file.YamlConfiguration());
    private final ModelParser parser = new ModelParser(DEFAULTS);

    private static final String BLOCK = "{id:\"minecraft:block_display\",block_state:{Name:\"minecraft:stone\"},Tags:[\"sample_0\"]}";

    static String response(String... entries) {
        JsonObject content = new JsonObject();
        content.addProperty("version", "26.3");
        content.addProperty("type", "modelOnly");
        JsonArray passengers = new JsonArray();
        for (String entry : entries) passengers.add(entry);
        content.add("passengers", passengers);
        JsonObject root = new JsonObject();
        root.add("content", content);
        return root.toString();
    }

    @Test void parsesSeveralEntitiesInOneEntryWithoutSplittingEmbeddedCommas() throws Exception {
        var model = parser.parse(response(BLOCK + "," + BLOCK), "26.3");
        assertEquals(2, model.parts().size());
        var tag = TagStringIO.tagStringIO().asCompound(model.parts().getFirst());
        assertEquals("minecraft:stone", tag.getCompound("block_state").getString("id"));
        assertEquals("sample_0", tag.getList("Tags").getString(0));
        assertEquals(0.5f, tag.getFloat("view_range"));
    }

    @Test void preservesMatrixAndDisplayContent() throws Exception {
        String matrix = "[1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,4f,2f,3f,1f]";
        var model = parser.parse(response(BLOCK.replace(",Tags:", ",transformation:" + matrix + ",Tags:")), "26.3");
        var tag = TagStringIO.tagStringIO().asCompound(model.parts().getFirst());
        assertEquals(16, tag.getList("transformation").size());
        assertEquals(4f, tag.getList("transformation").getFloat(12));
    }

    @Test void preservesQuotedTextCommasAndEscapedBraces() throws Exception {
        var model = parser.parse(response("{id:'minecraft:text_display',text:'a,b{[c]}',background:0}"), "26.3");
        assertEquals("a,b{[c]}", TagStringIO.tagStringIO().asCompound(model.parts().getFirst()).getString("text"));
    }

    @Test void removesUuidPositionsMotionAndServerSpecificData() throws Exception {
        String entry = BLOCK.replace(",Tags:", ",UUID:[I;1,2,3,4],Pos:[0d,999d,0d],Motion:[9d,9d,9d],BukkitValues:{foo:'bar'},Command:'kill @a',Tags:");
        var tag = TagStringIO.tagStringIO().asCompound(parser.parse(response(entry), "26.3").parts().getFirst());
        assertNull(tag.get("UUID"));
        assertNull(tag.get("Pos"));
        assertNull(tag.get("Motion"));
        assertNull(tag.get("BukkitValues"));
        assertNull(tag.get("Command"));
    }

    @Test void rejectsNestedPassengersAndNonDisplayEntities() {
        assertThrows(IOException.class, () -> parser.parse(response("{id:'minecraft:block_display',Passengers:[" + BLOCK + "]}"), "26.3"));
        assertThrows(IOException.class, () -> parser.parse(response("{id:'minecraft:command_block_minecart'}"), "26.3"));
    }

    @Test void rejectsWrongVersionAndMissingFields() {
        assertThrows(IOException.class, () -> parser.parse(response(BLOCK), "1.21.4"));
        assertThrows(IOException.class, () -> parser.parse("{}", "26.3"));
        assertThrows(IOException.class, () -> parser.parse("{\"content\":{\"version\":\"26.3\"}}", "26.3"));
        assertThrows(IOException.class, () -> parser.parse("[]", "26.3"));
    }

    @Test void rejectsMetadataCoercedFromNumbersOrArrays() {
        String valid = response(BLOCK);
        assertThrows(IOException.class, () -> parser.parse(valid.replace("\"version\":\"26.3\"", "\"version\":26.3"), "26.3"));
        assertThrows(IOException.class, () -> parser.parse(valid.replace("\"type\":\"modelOnly\"", "\"type\":[\"modelOnly\"]"), "26.3"));
    }

    @Test void allows128PartsButRejects129IncludingCombinedEntries() throws Exception {
        String allowed = String.join(",", java.util.Collections.nCopies(128, BLOCK));
        assertEquals(128, parser.parse(response(allowed), "26.3").parts().size());
        assertThrows(IOException.class, () -> parser.parse(response(allowed + "," + BLOCK), "26.3"));
    }

    @Test void rejectsOversizedPartsAndAggregateModel() {
        String huge = "{id:'minecraft:text_display',text:'" + "x".repeat(8192) + "'}";
        assertThrows(IOException.class, () -> parser.parse(response(huge), "26.3"));
        String medium = "{id:'minecraft:text_display',text:'" + "x".repeat(7000) + "'}";
        String[] entries = java.util.Collections.nCopies(20, medium).toArray(String[]::new);
        assertThrows(IOException.class, () -> parser.parse(response(entries), "26.3"));
    }

    @Test void measuresPartLimitInUtf8Bytes() throws Exception {
        String empty = "{id:'minecraft:text_display',text:''}";
        int overhead = parser.parse(response(empty), "26.3").parts().getFirst()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        int count = (DEFAULTS.partBytes() - overhead) / 3;
        String allowed = empty.replace("text:''", "text:'" + "界".repeat(count) + "'");
        assertEquals("界".repeat(count), TagStringIO.tagStringIO().asCompound(
                parser.parse(response(allowed), "26.3").parts().getFirst()).getString("text"));
        String oversized = empty.replace("text:''", "text:'" + "界".repeat(count + 1) + "'");
        assertThrows(IOException.class, () -> parser.parse(response(oversized), "26.3"));
    }

    @Test void fixesRenderingAndInterpolationValuesAtTheBoundary() throws Exception {
        String input = "{id:'minecraft:block_display',view_range:10f,width:32f,height:32f,"
                + "interpolation_duration:100,teleport_duration:10,start_interpolation:1}";
        var tag = TagStringIO.tagStringIO().asCompound(parser.parse(response(input), "26.3").parts().getFirst());
        assertEquals(0.5f, tag.getFloat("view_range"));
        assertEquals(0f, tag.getFloat("width"));
        assertEquals(0f, tag.getFloat("height"));
        assertEquals(0, tag.getInt("interpolation_duration"));
        assertEquals(0, tag.getInt("teleport_duration"));
        assertEquals(0, tag.getInt("start_interpolation"));
    }

    @Test void rejectsWrongNumericTypesAndNestedNonFiniteVisualData() {
        assertThrows(IOException.class, () -> parser.parse(response(
                "{id:'minecraft:text_display',line_width:'wide'}"), "26.3"));
        assertThrows(IOException.class, () -> parser.parse(response(
                "{id:'minecraft:item_display',item:{components:{custom:[1.0e999]}}}"), "26.3"));
    }

    @Test void rejectsNonFiniteAndExcessiveTransforms() {
        assertThrows(IOException.class, () -> parser.parse(response("{id:'minecraft:block_display',view_range:1e999f}"), "26.3"));
        assertThrows(IOException.class, () -> parser.parse(response("{id:'minecraft:block_display',transformation:[65f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f,0f,0f,0f,0f,1f]}"), "26.3"));
        assertThrows(IOException.class, () -> parser.parse(response("{id:'minecraft:block_display',transformation:[1f]}"), "26.3"));
    }

    @Test void rejectsExcessiveDepthButIgnoresQuotedBraces() throws Exception {
        assertThrows(IOException.class, () -> parser.checkDepth("[".repeat(33) + "]".repeat(33)));
        parser.checkDepth("{text:'" + "{".repeat(100) + "'}");
        assertThrows(IOException.class, () -> parser.checkDepth("{text:'abc}"));
    }

    @Test void convertsLegacyBlockStatePropertiesFor263() throws Exception {
        var model = parser.parse(response("{id:'minecraft:block_display',block_state:{Name:'minecraft:oak_stairs',Properties:{facing:'east',half:'top'}}}"), "26.3");
        var state = TagStringIO.tagStringIO().asCompound(model.parts().getFirst()).getCompound("block_state");
        assertEquals("minecraft:oak_stairs", state.getString("id"));
        assertEquals("east", state.getCompound("properties").getString("facing"));
        assertNull(state.get("Name"));
    }

    @Test void parsesRealBde263ExportWithTwoPartsInOneString() throws Exception {
        String json;
        try (var input = getClass().getResourceAsStream("/bde-317166.json")) {
            assertNotNull(input);
            json = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var model = parser.parse(json, "26.3");
        assertEquals(2, model.parts().size());
        assertFalse(model.full());
        var grass = TagStringIO.tagStringIO().asCompound(model.parts().get(0));
        var door = TagStringIO.tagStringIO().asCompound(model.parts().get(1));
        assertEquals("minecraft:grass_block", grass.getCompound("block_state").getString("id"));
        assertEquals("false", grass.getCompound("block_state").getCompound("properties").getString("snowy"));
        assertEquals(2f, grass.getList("transformation").getFloat(5));
        assertEquals(0.5f, grass.getList("transformation").getFloat(10));
        assertEquals("minecraft:oak_door", door.getCompound("block_state").getString("id"));
        assertEquals("north", door.getCompound("block_state").getCompound("properties").getString("facing"));
        assertEquals("lower", door.getCompound("block_state").getCompound("properties").getString("half"));
        assertEquals(-0.3125f, door.getList("transformation").getFloat(11));
        assertEquals("bde_test_317166_1", door.getList("Tags").getString(0));
    }

    @Test void permitsFullExportWithoutExecutingTimeline() throws Exception {
        String document = response(BLOCK).replace("modelOnly", "full");
        assertTrue(parser.parse(document, "26.3").full());
    }

    @Test void rejectsNonStringOrMalformedPassengersAndEmptyModel() {
        assertThrows(IOException.class, () -> parser.parse(response(), "26.3"));
        assertThrows(IOException.class, () -> parser.parse(response(""), "26.3"));
        assertThrows(IOException.class, () -> parser.parse(response("{id:'minecraft:block_display'"), "26.3"));
        assertThrows(IOException.class, () -> parser.parse("{\"content\":{\"version\":\"26.3\",\"type\":\"modelOnly\",\"passengers\":[42]}}", "26.3"));
    }
}
