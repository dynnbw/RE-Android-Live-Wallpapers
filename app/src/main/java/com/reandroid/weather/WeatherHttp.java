package com.reandroid.weather;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 两个数据源共用的 HTTP GET。
 *
 * <p><b>非 2xx 时返回的是错误流里的正文，而不是直接抛。</b> 这是刻意的：OpenWeather 的
 * 401/429 正文是 JSON，里面有 {@code message} 字段（"Invalid API key" 之类），
 * 先抛掉状态码就等于把这个唯一有用的信息丢了。判断成败交给各源的解析层 ——
 * 它更清楚什么算成功。
 */
final class WeatherHttp {

    private static final int TIMEOUT_MS = 10000;

    private WeatherHttp() {
    }

    /**
     * @param headers 附加请求头，可为 null。中国气象局那站要 {@code Referer}，否则被拒
     */
    static String get(String urlStr, Map<String, String> headers) throws IOException {
        HttpURLConnection connection = null;
        InputStream input = null;
        try {
            URL url = new URL(urlStr);
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            int status = connection.getResponseCode();
            input = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            if (input == null) {
                throw new IOException("HTTP " + status + " 且没有正文");
            }
            String body = readAll(input);
            if (body == null || body.isEmpty()) {
                throw new IOException("HTTP " + status + " 正文为空");
            }
            return body;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readAll(InputStream input) throws IOException {
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }
}
