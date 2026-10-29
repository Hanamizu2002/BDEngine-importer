package gg.bde.importer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.nbt.BinaryTagTypes;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.nbt.ListBinaryTag;
import net.kyori.adventure.nbt.NumberBinaryTag;
import net.kyori.adventure.nbt.TagStringIO;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class ModelParser {
    private final Limits limits;

    ModelParser(Limits limits) {
        this.limits = limits;
    }

    private static final Set<String> TYPES = Set.of("minecraft:block_display", "minecraft:item_display", "minecraft:text_display");
    // 仅保留外观字段，避免携带实体身份或嵌套实体。
    private static final Set<String> KEYS = Set.of("id", "block_state", "item", "item_display", "text",
            "transformation", "billboard", "brightness", "view_range", "shadow_radius", "shadow_strength", "width", "height",
            "glow_color_override", "Glowing", "Tags", "CustomName", "CustomNameVisible", "line_width", "background",
            "text_opacity", "shadow", "see_through", "default_background", "alignment", "teleport_duration",
            "interpolation_duration", "start_interpolation");

    private static final Set<String> NUMERIC_KEYS = Set.of("view_range", "shadow_radius", "shadow_strength", "width", "height",
            "glow_color_override", "Glowing", "CustomNameVisible", "line_width", "background", "text_opacity", "shadow",
            "see_through", "default_background", "teleport_duration", "interpolation_duration", "start_interpolation");

    record Model(List<String> parts, boolean full) {
    }

    Model parse(String json, String version) throws IOException {
        checkDepth(json);
        JsonElement document;
        try {
            document = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IOException("API 返回的 JSON 无效", e);
        }
        try {
            JsonObject content = document.getAsJsonObject().getAsJsonObject("content");
            if (content == null) throw new IOException("API响应缺少content");
            if (!version.equals(stringField(content, "version"))) {
                throw new IOException("导出版本必须为" + version + "，请在BDEngine选择相同版本重新导出");
            }
            String type = stringField(content, "type");
            if (!type.equals("modelOnly") && !type.equals("full")) throw new IOException("不支持的导出类型");
            if (!content.has("passengers") || !content.get("passengers").isJsonArray()) {
                throw new IOException("模型没有展示实体");
            }
            return new Model(parseParts(content.getAsJsonArray("passengers")), type.equals("full"));
        } catch (IllegalStateException | NullPointerException | ClassCastException | UnsupportedOperationException e) {
            throw new IOException("导出字段类型不正确", e);
        }
    }

    private static String stringField(JsonObject content, String name) throws IOException {
        JsonElement value = content.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IOException("导出字段必须是字符串：" + name);
        return value.getAsString();
    }

    private List<String> parseParts(JsonArray passengers) throws IOException {
        List<String> parts = new ArrayList<>();
        long bytes = 0;
        for (JsonElement entry : passengers) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                throw new IOException("passengers 必须是 SNBT 字符串数组");
            }
            String snbt = entry.getAsString();
            checkDepth(snbt);
            // 一条导出字符串可含多个实体，文本中的逗号由 SNBT 解析器处理。
            CompoundBinaryTag wrapper = TagStringIO.tagStringIO().asCompound("{parts:[" + snbt + "]}");
            ListBinaryTag list = wrapper.getList("parts", BinaryTagTypes.COMPOUND);
            if (list.isEmpty()) throw new IOException("模型包含空的视觉部件");
            for (BinaryTag tag : list) {
                if (parts.size() >= limits.parts())
                    throw new IOException("模型超过 " + limits.parts() + " 个部件，请拆分后导出");
                CompoundBinaryTag entity = sanitize((CompoundBinaryTag) tag);
                String serialized = TagStringIO.tagStringIO().asString(entity);
                int size = serialized.getBytes(StandardCharsets.UTF_8).length;
                if (size > limits.partBytes())
                    throw new IOException("单个展示实体数据超过 " + limits.partBytes() + " 字节，请简化物品或文本数据");
                bytes += size;
                if (bytes > limits.modelBytes())
                    throw new IOException("模型实体数据超过 " + limits.modelBytes() + " 字节，请拆分导出");
                parts.add(serialized);
            }
        }
        if (parts.isEmpty()) throw new IOException("模型没有展示实体");
        return List.copyOf(parts);
    }

    private CompoundBinaryTag sanitize(CompoundBinaryTag input) throws IOException {
        if (!TYPES.contains(input.getString("id")))
            throw new IOException("仅支持 block_display、item_display 和 text_display");
        if (input.get("Passengers") != null) throw new IOException("不接受嵌套 Passengers，请导出普通展示模型");
        CompoundBinaryTag.Builder output = CompoundBinaryTag.builder();
        for (String key : input.keySet()) {
            if (KEYS.contains(key)) {
                BinaryTag value = input.get(key);
                if (NUMERIC_KEYS.contains(key) && !(value instanceof NumberBinaryTag)) {
                    throw new IOException("展示实体的数值字段格式不正确：" + key);
                }
                checkFinite(value);
                output.put(key, value);
            }
        }
        if (input.get("block_state") instanceof CompoundBinaryTag state && state.get("Name") != null) {
            CompoundBinaryTag.Builder modern = CompoundBinaryTag.builder().putString("id", state.getString("Name"));
            if (state.get("Properties") != null) modern.put("properties", state.get("Properties"));
            output.put("block_state", modern.build());
        }
        BinaryTag transformation = input.get("transformation");
        if (transformation != null) {
            if (!(transformation instanceof ListBinaryTag matrix) || matrix.size() != 16) {
                throw new IOException("仅支持 BDEngine 的 16 元素变换矩阵");
            }
            for (BinaryTag value : matrix) {
                if (!(value instanceof NumberBinaryTag number) || Math.abs(number.doubleValue()) > limits.matrixMaxAbsolute()) {
                    throw new IOException("模型变换超出安全范围");
                }
            }
        }
        output.putFloat("view_range", limits.viewRange()).putFloat("width", 0).putFloat("height", 0);
        output.putInt("interpolation_duration", 0).putInt("teleport_duration", 0).putInt("start_interpolation", 0);
        return output.build();
    }

    private static void checkFinite(BinaryTag tag) throws IOException {
        if (tag instanceof NumberBinaryTag number && !Double.isFinite(number.doubleValue())) {
            throw new IOException("模型包含非有限数值");
        }
        if (tag instanceof CompoundBinaryTag compound) {
            for (String key : compound.keySet()) checkFinite(compound.get(key));
        } else if (tag instanceof ListBinaryTag list) {
            for (BinaryTag child : list) checkFinite(child);
        }
    }

    void checkDepth(String text) throws IOException {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') quote = c;
            else if (c == '{' || c == '[') {
                if (++depth > limits.nesting()) throw new IOException("数据嵌套超过 " + limits.nesting() + " 层");
            } else if (c == '}' || c == ']') {
                if (--depth < 0) throw new IOException("数据括号不匹配");
            }
        }
        if (quote != 0 || depth != 0) throw new IOException("数据字符串或括号未闭合");
    }
}
