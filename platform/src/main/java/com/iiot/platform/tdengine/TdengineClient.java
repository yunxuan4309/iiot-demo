package com.iiot.platform.tdengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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

/**
 * 极简 TDengine 查询客户端 —— 直接打 taosAdapter 的 REST 接口，不引 JDBC。
 *
 * <p>为什么不用 JDBC：
 * <ul>
 *   <li>native 模式要客户端侧的 {@code libtaos.so}（Windows 没有）</li>
 *   <li>WebSocket 模式底层还是 taosAdapter，多引一层驱动没有收益</li>
 *   <li>REST 的返回体自带 {@code column_meta}（列名+类型），<b>正好能直接映射成 JSON 给前端</b></li>
 * </ul>
 *
 * <p>⚠️ 时间戳：TDengine 按 <b>UTC</b> 存，返回形如 {@code "2026-10-24T03:23:19.306Z"}。
 * 本类<b>原样透传</b>，由前端用 {@code new Date(iso)} 转本地时区 —— 这样"服务器在哪个时区"就不影响展示了。
 */
@Component
public class TdengineClient {

    private final String baseUrl;
    private final String database;
    private final String authHeader;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public TdengineClient(@Value("${iiot.tdengine.url:http://localhost:6041}") String url,
                          @Value("${iiot.tdengine.database:iiot}") String database,
                          @Value("${iiot.tdengine.user:root}") String user,
                          @Value("${iiot.tdengine.password:taosdata}") String password) {
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.database = database;
        this.authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public String database() {
        return database;
    }

    /**
     * 执行一条 SQL，返回行列表（每行是 列名 → 值 的有序 Map）。
     *
     * @throws TdengineException SQL 失败或连不上
     */
    public List<Map<String, Object>> query(String sql) {
        JsonNode root = post(sql);
        JsonNode codeNode = root.path("code");
        if (codeNode.asInt(-1) != 0) {
            throw new TdengineException("TDengine 错误 code=" + codeNode.asInt()
                    + " " + root.path("desc").asText(""));
        }
        JsonNode columns = root.path("column_meta");
        JsonNode data = root.path("data");
        List<Map<String, Object>> rows = new ArrayList<>(data.size());
        for (JsonNode row : data) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (int i = 0; i < columns.size() && i < row.size(); i++) {
                String name = columns.get(i).get(0).asText();
                JsonNode value = row.get(i);
                map.put(name, value.isNull() ? null
                        : value.isNumber() ? value.numberValue()
                        : value.asText());
            }
            rows.add(map);
        }
        return rows;
    }

    /** 只取第一行第一列，常用于 {@code count(*)} / {@code last(ts)}。 */
    public Object queryScalar(String sql) {
        List<Map<String, Object>> rows = query(sql);
        if (rows.isEmpty() || rows.get(0).isEmpty()) {
            return null;
        }
        return rows.get(0).values().iterator().next();
    }

    private JsonNode post(String sql) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/rest/sql"))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", authHeader)
                .header("Content-Type", "text/plain; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(sql, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = http.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new TdengineException("TDengine HTTP " + response.statusCode() + ": " + response.body());
            }
            return mapper.readTree(response.body());
        } catch (TdengineException e) {
            throw e;
        } catch (Exception e) {
            throw new TdengineException("连不上 TDengine（" + baseUrl + "）: " + e.getMessage()
                    + " —— 若它在 WSL2 里，注意 Windows 侧只监听 [::1]，需要 -Djava.net.preferIPv6Addresses=true", e);
        }
    }

    /** TDengine 访问失败的统一异常（Controller 会把它变成 503）。 */
    public static class TdengineException extends RuntimeException {
        public TdengineException(String message) {
            super(message);
        }

        public TdengineException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
