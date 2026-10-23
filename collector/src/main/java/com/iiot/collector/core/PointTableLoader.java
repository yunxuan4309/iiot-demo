package com.iiot.collector.core;

import com.iiot.collector.proto.WordOrder;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * 点表加载 —— 把「设备 + 点位 + 字序」这些**现场的私有约定**从代码里拿出来。
 *
 * <p>格式用 {@code .properties}（JDK 自带，不引额外依赖；接 Spring 后再换 {@code application.yml}）。
 * 关键在<b>每台设备、每个点位</b>都能单独配 {@code order}：
 *
 * <pre>
 * device.sim-01.host=127.0.0.1
 * device.sim-01.port=1502
 * device.sim-01.unitId=1
 * device.sim-01.points=temperature,runtime_hours
 * device.sim-01.point.temperature.area=holding
 * device.sim-01.point.temperature.offset=0
 * device.sim-01.point.temperature.type=float32
 * device.sim-01.point.temperature.order=CDAB      &lt;-- 换设备只改这里
 * device.sim-01.point.temperature.unit=°C
 * </pre>
 */
public final class PointTableLoader {

    private static final String PREFIX = "device.";

    private PointTableLoader() {
    }

    public static List<DeviceConfig> loadFromClasspath(String resource) throws IOException {
        InputStream in = PointTableLoader.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IOException("classpath 上找不到 " + resource);
        }
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return load(reader);
        }
    }

    public static List<DeviceConfig> loadFromFile(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return load(reader);
        }
    }

    /**
     * @param reader <b>必须是 UTF-8 的 Reader</b>。{@link Properties#load(InputStream)} 按 ISO-8859-1 解码，
     *               单位里的 {@code °C} 会乱码，所以这里刻意只收 Reader。
     */
    public static List<DeviceConfig> load(Reader reader) throws IOException {
        Properties props = new Properties();
        props.load(reader);

        Set<String> deviceIds = new TreeSet<>();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith(PREFIX) && key.endsWith(".host")) {
                deviceIds.add(key.substring(PREFIX.length(), key.length() - ".host".length()));
            }
        }
        if (deviceIds.isEmpty()) {
            throw new IOException("点表里没有任何 device.<id>.host 条目");
        }

        List<DeviceConfig> configs = new ArrayList<>(deviceIds.size());
        for (String deviceId : deviceIds) {
            configs.add(parseDevice(props, deviceId));
        }
        return configs;
    }

    private static DeviceConfig parseDevice(Properties props, String deviceId) throws IOException {
        String base = PREFIX + deviceId + ".";
        String host = required(props, base + "host");
        int port = intValue(props, base + "port");
        int unitId = intValue(props, base + "unitId");
        long periodMillis = intValue(props, base + "periodMillis");
        String pointList = required(props, base + "points");

        List<PointDef> points = new ArrayList<>();
        for (String raw : pointList.split(",")) {
            String pointId = raw.trim();
            if (!pointId.isEmpty()) {
                points.add(parsePoint(props, deviceId, pointId));
            }
        }

        DeviceConfig config = new DeviceConfig(
                DeviceEndpoint.tcp(deviceId, host, port, unitId),
                List.copyOf(points),
                periodMillis);
        String problem = config.validate();
        if (problem != null) {
            throw new IOException("设备 " + deviceId + " 点表不合法: " + problem);
        }
        return config;
    }

    private static PointDef parsePoint(Properties props, String deviceId, String pointId) throws IOException {
        String base = PREFIX + deviceId + ".point." + pointId + ".";
        PointDef.Area area = parseArea(required(props, base + "area"), pointId);
        int offset = intValue(props, base + "offset");
        PointDef.Type type = parseType(required(props, base + "type"), pointId);

        String orderText = props.getProperty(base + "order");
        WordOrder order = orderText == null || orderText.isBlank()
                ? null
                : WordOrder.parse(orderText.trim());
        String unit = props.getProperty(base + "unit");

        PointDef point = new PointDef(pointId, area, offset, type, order, unit);
        String problem = point.validate();
        if (problem != null) {
            throw new IOException("设备 " + deviceId + " 的点位配置不合法: " + problem);
        }
        return point;
    }

    private static PointDef.Area parseArea(String text, String pointId) throws IOException {
        return switch (text.trim().toLowerCase()) {
            case "coil" -> PointDef.Area.COIL;
            case "discrete", "discrete_input" -> PointDef.Area.DISCRETE_INPUT;
            case "holding", "holding_register" -> PointDef.Area.HOLDING_REGISTER;
            case "input", "input_register" -> PointDef.Area.INPUT_REGISTER;
            default -> throw new IOException(pointId + ": 未知区域 " + text + "（coil/discrete/holding/input）");
        };
    }

    private static PointDef.Type parseType(String text, String pointId) throws IOException {
        return switch (text.trim().toLowerCase()) {
            case "bool" -> PointDef.Type.BOOL;
            case "float32", "float" -> PointDef.Type.FLOAT32;
            case "uint32", "uint" -> PointDef.Type.UINT32;
            default -> throw new IOException(pointId + ": 未知类型 " + text + "（bool/float32/uint32）");
        };
    }

    private static String required(Properties props, String key) throws IOException {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IOException("缺少配置项: " + key);
        }
        return value.trim();
    }

    private static int intValue(Properties props, String key) throws IOException {
        String value = props.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IOException("缺少配置项: " + key);
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IOException("配置项 " + key + " 需要整数，实际是: " + value);
        }
    }
}
