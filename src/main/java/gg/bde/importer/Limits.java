package gg.bde.importer;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

record Limits(
        int responseBytes,
        int parts,
        int partBytes,
        int modelBytes,
        int nesting,
        int connectMillis,
        int readMillis,
        int requestMillis,
        int jobMillis,
        int cooldownMillis,
        int stepTicks,
        int chunkDisplays,
        double busyTickMillis, double matrixMaxAbsolute, float viewRange) {
    static Limits from(ConfigurationSection config) {
        if (config.contains("limits", true) && !config.isConfigurationSection("limits"))
            throw new IllegalArgumentException("limits 必须是配置节");
        YamlConfiguration defaults;
        try (var input = Limits.class.getResourceAsStream("/config.yml")) {
            if (input == null) throw new IllegalStateException("JAR 缺少 config.yml");
            defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(input, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("无法读取默认配置", e);
        }
        return new Limits(
                integer(config, defaults, "response-bytes", 1, Integer.MAX_VALUE - 8),
                integer(config, defaults, "parts", 1, Integer.MAX_VALUE - 8),
                integer(config, defaults, "part-bytes", 1, Integer.MAX_VALUE - 8),
                integer(config, defaults, "model-bytes", 1, Integer.MAX_VALUE - 8),
                integer(config, defaults, "nesting", 1, 64),
                integer(config, defaults, "connect-timeout-millis", 1, Integer.MAX_VALUE - 8),
                integer(config, defaults, "read-timeout-millis", 1, Integer.MAX_VALUE - 8),
                integer(config, defaults, "request-timeout-millis", 1, Integer.MAX_VALUE - 8),
                integer(config, defaults, "job-timeout-millis", 1, Integer.MAX_VALUE - 8),
                integer(config, defaults, "cooldown-millis", 0, Integer.MAX_VALUE - 8),
                integer(config, defaults, "step-ticks", 1, Integer.MAX_VALUE - 8),
                integer(config, defaults, "chunk-displays", 1, Integer.MAX_VALUE - 8),
                decimal(config, defaults, "busy-tick-millis", Double.MIN_VALUE, Double.MAX_VALUE),
                decimal(config, defaults, "matrix-max-absolute", Double.MIN_VALUE, Float.MAX_VALUE),
                (float) decimal(config, defaults, "view-range", Float.MIN_VALUE, Float.MAX_VALUE));
    }

    private static int integer(ConfigurationSection config, ConfigurationSection defaults, String key, int min, int max) {
        Object value = value(config, defaults, key);
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < min
                || ((Number) value).longValue() > max)
            throw new IllegalArgumentException("limits." + key + " 必须是 " + min + "～" + max + " 的整数");
        return ((Number) value).intValue();
    }

    private static double decimal(ConfigurationSection config, ConfigurationSection defaults, String key, double min, double max) {
        Object value = value(config, defaults, key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < min || number.doubleValue() > max)
            throw new IllegalArgumentException("limits." + key + " 必须是 " + min + "～" + max + " 的有限数值");
        return ((Number) value).doubleValue();
    }

    private static Object value(ConfigurationSection config, ConfigurationSection defaults, String key) {
        String path = "limits." + key;
        return config.get(path, defaults.get(path));
    }

    long jobNanos() {
        return TimeUnit.MILLISECONDS.toNanos(jobMillis);
    }

    long cooldownNanos() {
        return TimeUnit.MILLISECONDS.toNanos(cooldownMillis);
    }

    int chunkCapacity(int partCount) throws IOException {
        int capacity = chunkDisplays - partCount - 1;
        if (capacity < 0) throw new IOException("模型实体数量超过区块展示实体上限");
        return capacity;
    }
}
