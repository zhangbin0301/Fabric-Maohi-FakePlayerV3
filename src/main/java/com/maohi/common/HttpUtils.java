package com.maohi.common;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/**
 * HTTP 请求工具类 (V3)
 */
public final class HttpUtils {

    private HttpUtils() {} // 工具类禁止实例化

    /**
     * 通用 HTTP GET 文本获取
     * 原 Maohi.fetchText() 逻辑完整迁移
     *
     * @param urlStr  请求 URL
     * @param timeout 连接/读取超时（毫秒）
     * @return 响应文本，失败或 404/204 返回 null
     */
    public static String fetchText(String urlStr, int timeout) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new java.net.URI(urlStr).toURL().openConnection();
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");

		int responseCode = conn.getResponseCode();
		// 404 (Not Found) 或 204 (No Content) 说明资源不存在，静默返回
		if (responseCode == 404 || responseCode == 204) return null;
		// 429 (Too Many Requests) → 抛特殊异常让调用方退避
		if (responseCode == 429) throw new IOException("HTTP 429 Too Many Requests");
		// 其他非 200 响应码视为异常
		if (responseCode != 200) throw new IOException("HTTP code: " + responseCode);

	try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
	StringBuilder sb = new StringBuilder();
	String line;
	while ((line = br.readLine()) != null) { sb.append(line).append("\n"); }
	// m7 fix: 保留换行符，避免多行文本格式错误
	String result = sb.toString();
	return result.isEmpty() ? result : result.substring(0, result.length() - 1); // 去掉末尾多余换行
	} finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            // 仅在调试模式或严重网络故障时打印，避免刷屏
		org.slf4j.LoggerFactory.getLogger("Server thread").debug("HTTP fetch failed (" + urlStr + "): " + e.getMessage());
            return null;
        }
    }
}
