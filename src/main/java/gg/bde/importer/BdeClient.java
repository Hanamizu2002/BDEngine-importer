package gg.bde.importer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class BdeClient {
    static String fetch(String id, String tag, Limits limits) throws IOException {
        // id 已在命令入口校验，tag 由插件生成。
        HttpURLConnection connection = (HttpURLConnection) URI.create(
                "https://www.block-display.com/server-api?id=" + id + "&tag=" + tag).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(limits.connectMillis());
        connection.setReadTimeout(limits.readMillis());
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "BDEImporter/1.0.2");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(limits.requestMillis());
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IOException(switch (status) {
                    case 400 -> "模型ID格式不正确";
                    case 404 -> "模型不存在或已过期，BDEngine默认导出有效期为10分钟，若超时请重新发布";
                    case 429 ->
                            "BDEngine 请求限流，请等待 " + safeRetry(connection.getHeaderField("Retry-After")) + " 秒后重试";
                    default -> "BDEngine 服务返回 HTTP " + status + "，请稍后重试";
                });
            }
            String encoding = connection.getContentEncoding();
            if (encoding != null && !encoding.equalsIgnoreCase("identity"))
                throw new IOException("API 返回了不支持的内容编码");
            if (connection.getContentLengthLong() > limits.responseBytes())
                throw new IOException("API 响应超过 " + limits.responseBytes() + " 字节，请拆分模型后导出");
            try (InputStream input = connection.getInputStream()) {
                byte[] bytes = readLimited(input, limits.responseBytes(), deadline);
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            }
        } finally {
            connection.disconnect();
        }
    }

    static byte[] readLimited(InputStream input, int limit, long deadline) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        byte[] buffer = new byte[8192];
        while (true) {
            checkDeadline(deadline);
            int count = input.read(buffer, 0, Math.min(buffer.length, limit - output.size() + 1));
            checkDeadline(deadline);
            if (count < 0) return output.toByteArray();
            if (count > limit - output.size()) throw new IOException("API 响应超过大小限制，请拆分模型后导出");
            output.write(buffer, 0, count);
        }
    }

    private static void checkDeadline(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline)
            throw new IOException("下载已取消或超时");
    }

    private static String safeRetry(String value) {
        return value != null && value.matches("[0-9]{1,6}") ? value : "未知";
    }
}
