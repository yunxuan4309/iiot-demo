package com.iiot.collector.repo;

import com.iiot.collector.core.PointValue;
import com.iiot.collector.core.Quality;
import com.iiot.collector.core.TelemetryRepository;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * TDengine 存储实现 —— 走 <b>REST 接口（taosAdapter，6041）</b>，不引任何第三方依赖。
 *
 * <h2>为什么用 REST 而不是 JDBC</h2>
 * 采集端跑在 Windows 上，TDengine 跑在 WSL/Linux 上。JDBC 的 native 模式需要
 * 客户端侧的 {@code libtaos.so}（Windows 上没有），WebSocket 模式本质上还是走 taosAdapter。
 * 直接用它的 REST 就是个 HTTP POST，**零依赖、零 native 库**，也方便通过 SSH 隧道连远端。
 *
 * <h2>为什么要有队列和后台刷盘</h2>
 * 100 台 × 4 点 × 1Hz = 400 点/秒。如果每台设备每轮都发一次 HTTP 请求，
 * 就是 100 请求/秒 —— <b>HTTP 开销比数据本身还大</b>。所以：
 * <pre>
 *   采集线程 write() → 入队（不阻塞） → 后台线程攒够 N 点 或 到 T 毫秒 → 一次 POST
 * </pre>
 *
 * <h2>建模（TDengine 的惯用法）</h2>
 * 一个「设备 × 点位」= 一张子表，全部挂在超级表 {@code telemetry} 下：
 * <pre>
 *   CREATE STABLE telemetry (ts TIMESTAMP, val DOUBLE, quality NCHAR(8))
 *          TAGS (device NCHAR(32), point NCHAR(32), unit NCHAR(16));
 * </pre>
 * 写入用 {@code INSERT INTO 子表 USING 超级表 TAGS(...) VALUES ...}，
 * 子表<b>首次写入时自动创建</b>，不需要预先建表。
 *
 * <h2>时间戳</h2>
 * 一律用 <b>epoch 毫秒</b>（数字，不是字符串）—— 避免"服务器时区 vs 客户端时区"的歧义。
 * TDengine 内部按 UTC 存，查询返回带 {@code Z} 的 ISO 串，<b>展示层负责转本地时区</b>。
 */
public final class TdengineTelemetryRepository implements TelemetryRepository {

    /** 攒够这么多点就立刻发一次。 */
    private static final int DEFAULT_BATCH_SIZE = 500;
    /** 不够一批也要在这个时间内发出去（保证实时性）。 */
    private static final long DEFAULT_FLUSH_INTERVAL_MILLIS = 500;

    private final String baseUrl;
    private final String database;
    private final String stable;
    private final String authHeader;
    private final int batchSize;
    private final long flushIntervalMillis;

    private final HttpClient http;
    private final BlockingQueue<PointValue> queue;
    private final Thread flusher;
    private final LongAdder flushedPoints = new LongAdder();
    private final LongAdder flushedBatches = new LongAdder();
    private final LongAdder failedBatches = new LongAdder();
    private final LongAdder droppedPoints = new LongAdder();
    private volatile String lastError;
    private volatile boolean running = true;

    public TdengineTelemetryRepository(String url, String database, String user, String password) {
        this(url, database, user, password, DEFAULT_BATCH_SIZE, DEFAULT_FLUSH_INTERVAL_MILLIS);
    }

    public TdengineTelemetryRepository(String url, String database, String user, String password,
                                       int batchSize, long flushIntervalMillis) {
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.database = requireIdentifier(database, "database");
        this.stable = "telemetry";
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.batchSize = batchSize;
        this.flushIntervalMillis = flushIntervalMillis;
        this.queue = new LinkedBlockingQueue<>(Math.max(batchSize * 20, 10_000));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        bootstrap();

        this.flusher = Thread.ofVirtual()
                .name("tdengine-flusher")
                .start(this::flushLoop);
    }

    /** 建库 + 建超级表。重复执行无害。 */
    private void bootstrap() {
        execute("CREATE DATABASE IF NOT EXISTS " + database);
        execute("CREATE STABLE IF NOT EXISTS " + database + "." + stable
                + " (ts TIMESTAMP, val DOUBLE, quality NCHAR(8))"
                + " TAGS (device NCHAR(32), point NCHAR(32), unit NCHAR(16))");
    }

    @Override
    public void write(List<PointValue> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        for (PointValue value : batch) {
            if (!queue.offer(value)) {
                // 队列满 = 存储跟不上采集。宁可丢点并计数，也不要阻塞采集线程
                // （阻塞会把所有设备一起拖死，正是坑 5 的形状）。
                droppedPoints.increment();
            }
        }
    }

    private void flushLoop() {
        List<PointValue> buffer = new ArrayList<>(batchSize);
        while (running || !queue.isEmpty()) {
            try {
                PointValue first = queue.poll(flushIntervalMillis, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                buffer.add(first);
                queue.drainTo(buffer, batchSize - buffer.size());
                if (!buffer.isEmpty()) {
                    flush(buffer);
                    buffer.clear();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void flush(List<PointValue> points) {
        String sql = buildInsertSql(points);
        try {
            execute(sql);
            flushedBatches.increment();
            flushedPoints.add(points.size());
            lastError = null;
        } catch (RuntimeException e) {
            failedBatches.increment();
            lastError = e.getMessage();
        }
    }

    /**
     * 一批点 → 一条 {@code INSERT} 语句。
     *
     * <p>同一次采样里「设备 × 点位」是固定的几组，所以先按 (deviceId, pointId) 分组，
     * 每组一个 {@code 子表 USING 超级表 TAGS(...) VALUES(...) (...)}，再用空格拼成一条大 INSERT ——
     * <b>一次 HTTP 请求写完整批</b>。
     */
    private String buildInsertSql(List<PointValue> points) {
        Map<String, List<PointValue>> groups = new LinkedHashMap<>();
        for (PointValue value : points) {
            String key = value.deviceId() + '\u0001' + value.pointId();
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
        }

        StringBuilder sql = new StringBuilder(256 + points.size() * 48);
        sql.append("INSERT INTO ");
        boolean firstGroup = true;
        for (Map.Entry<String, List<PointValue>> entry : groups.entrySet()) {
            List<PointValue> rows = entry.getValue();
            PointValue sample = rows.get(0);
            if (!firstGroup) {
                sql.append(' ');
            }
            firstGroup = false;
            sql.append(childTable(sample.deviceId(), sample.pointId()));
            sql.append(" USING ").append(database).append('.').append(stable);
            sql.append(" TAGS ('").append(escape(sample.deviceId())).append("','")
                    .append(escape(sample.pointId())).append("','")
                    .append(escape(sample.unit() == null ? "" : sample.unit())).append("') VALUES ");
            for (int i = 0; i < rows.size(); i++) {
                PointValue row = rows.get(i);
                if (i > 0) {
                    sql.append(' ');
                }
                sql.append('(').append(row.ts().toEpochMilli()).append(',');
                if (Double.isFinite(row.value())) {
                    sql.append(row.value());
                } else {
                    // BAD 且从未读到过好值 —— 写 NULL，而不是 0
                    sql.append("NULL");
                }
                sql.append(",'").append(row.quality().name()).append("')");
            }
        }
        return sql.toString();
    }

    /** 子表名：{@code d_<device>_<point>}，非字母数字一律换成下划线。 */
    private String childTable(String deviceId, String pointId) {
        return database + ".d_" + sanitize(deviceId) + "_" + sanitize(pointId);
    }

    private static String sanitize(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            out.append(Character.isLetterOrDigit(c) || c == '_' ? c : '_');
        }
        return out.length() == 0 ? "x" : out.toString();
    }

    private static String escape(String raw) {
        return raw.replace("'", "''");
    }

    /** 执行一条 SQL（同步）。失败抛 {@link RuntimeException}。 */
    private void execute(String sql) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/rest/sql"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", authHeader)
                .header("Content-Type", "text/plain; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = response.body();
            if (response.statusCode() != 200) {
                throw new IllegalStateException("TDengine HTTP " + response.statusCode() + ": " + abbreviate(body));
            }
            // 返回体形如 {"code":0,"column_meta":[...],...}；code != 0 就是失败
            if (body == null || !body.contains("\"code\":0")) {
                throw new IllegalStateException("TDengine 返回错误: " + abbreviate(body));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("写入被中断", e);
        } catch (Exception e) {
            throw new IllegalStateException("写入 TDengine 失败: " + e.getMessage(), e);
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "null";
        }
        String oneLine = text.replace('\n', ' ').trim();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
    }

    private static String requireIdentifier(String value, String what) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(what + " 必须是合法标识符（字母/数字/下划线），实际: " + value);
        }
        return value;
    }

    public long flushedPoints() {
        return flushedPoints.sum();
    }

    public long failedBatches() {
        return failedBatches.sum();
    }

    public long droppedPoints() {
        return droppedPoints.sum();
    }

    public String lastError() {
        return lastError;
    }

    @Override
    public String describe() {
        return "TDengine（REST " + baseUrl + " · db=" + database + " · 批量 " + batchSize
                + " 点/" + flushIntervalMillis + "ms）";
    }

    @Override
    public void close() {
        running = false;
        try {
            flusher.join(Duration.ofSeconds(5).toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 关之前把剩下的点尽量写完
        List<PointValue> rest = new ArrayList<>();
        queue.drainTo(rest);
        if (!rest.isEmpty()) {
            flush(rest);
        }
    }

    /** 一行汇总，给采集结束时打印用。 */
    public String summaryLine() {
        return String.format("  TDengine 写入: %d 点 / %d 批 · 失败 %d 批 · 队列丢弃 %d 点%s",
                flushedPoints(), flushedBatches.sum(), failedBatches.sum(), droppedPoints(),
                lastError == null ? "" : " · 最后错误: " + abbreviate(lastError));
    }

    /** 让外部（采集主程序）能判断质量码是否被写过 —— 目前仅用于调试输出。 */
    public static boolean isUsable(Quality quality) {
        return quality != Quality.BAD;
    }
}
