package com.example.ffmpeg.config;

import dev.miku.r2dbc.mysql.MySqlConnectionConfiguration;
import dev.miku.r2dbc.mysql.MySqlConnectionFactory;
import io.r2dbc.spi.ConnectionFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.r2dbc.config.AbstractR2dbcConfiguration;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.r2dbc.connection.R2dbcTransactionManager;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.transaction.ReactiveTransactionManager;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Configuration
@EnableR2dbcRepositories(basePackages = "com.example.ffmpeg.repository")
public class DatabaseConfig extends AbstractR2dbcConfiguration {

    @Value("${spring.r2dbc.url}")
    private String databaseUrl;

    @Value("${spring.r2dbc.username}")
    private String username;

    @Value("${spring.r2dbc.password}")
    private String password;

    @Override
    @Bean
    public ConnectionFactory connectionFactory() {
        log.info("配置R2DBC MySQL连接: {}", databaseUrl);

        // 解析R2DBC URL
        DatabaseUrlInfo urlInfo = parseR2dbcUrl(databaseUrl);

        // MySQL连接配置 - 使用dev.miku.r2dbc.mysql
        MySqlConnectionConfiguration configuration = MySqlConnectionConfiguration.builder()
                .host(urlInfo.host)
                .port(urlInfo.port)
                .user(username)
                .password(password)
                .database(urlInfo.database)
                // 连接超时配置
                .connectTimeout(Duration.ofSeconds(30))
                // SSL配置
                .ssl(urlInfo.useSSL)
                // 字符集配置 - dev.miku版本的配置方式
                .charset(dev.miku.r2dbc.mysql.constant.MySqlCharset.UTF8MB4)
                // 时区配置
                .serverZoneId(java.time.ZoneId.of("Asia/Shanghai"))
                .build();

        log.info("MySQL连接配置完成 - Host: {}:{}, Database: {}, SSL: {}",
                urlInfo.host, urlInfo.port, urlInfo.database, urlInfo.useSSL);

        return MySqlConnectionFactory.from(configuration);
    }

    @Bean
    public ReactiveTransactionManager transactionManager(ConnectionFactory connectionFactory) {
        return new R2dbcTransactionManager(connectionFactory);
    }

    @Bean
    public ConnectionFactoryInitializer initializer(ConnectionFactory connectionFactory) {
        ConnectionFactoryInitializer initializer = new ConnectionFactoryInitializer();
        initializer.setConnectionFactory(connectionFactory);
        return initializer;
    }

    /**
     * 解析R2DBC URL
     */
    private DatabaseUrlInfo parseR2dbcUrl(String url) {
        DatabaseUrlInfo info = new DatabaseUrlInfo();

        try {
            // r2dbc:mysql://localhost:3306/drone_detection?useSSL=false&allowPublicKeyRetrieval=true
            Pattern pattern = Pattern.compile("r2dbc:mysql://([^:/]+)(?::(\\d+))?/([^?]+)(?:\\?(.+))?");
            Matcher matcher = pattern.matcher(url);

            if (matcher.matches()) {
                info.host = matcher.group(1);
                info.port = matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 3306;
                info.database = matcher.group(3);

                // 解析查询参数
                String queryParams = matcher.group(4);
                if (queryParams != null) {
                    String[] params = queryParams.split("&");
                    for (String param : params) {
                        String[] keyValue = param.split("=");
                        if (keyValue.length == 2) {
                            String key = keyValue[0];
                            String value = keyValue[1];

                            switch (key) {
                                case "useSSL":
                                    info.useSSL = Boolean.parseBoolean(value);
                                    break;
                                case "allowPublicKeyRetrieval":
                                    info.allowPublicKeyRetrieval = Boolean.parseBoolean(value);
                                    break;
                                case "serverTimezone":
                                    info.serverTimezone = value;
                                    break;
                            }
                        }
                    }
                }
            } else {
                log.warn("无法解析R2DBC URL: {}, 使用默认配置", url);
                info.host = "localhost";
                info.port = 3306;
                info.database = "drone_detection";
            }
        } catch (Exception e) {
            log.error("解析R2DBC URL时出错: {}", e.getMessage());
            info.host = "localhost";
            info.port = 3306;
            info.database = "drone_detection";
        }

        return info;
    }

    /**
     * 数据库URL信息
     */
    private static class DatabaseUrlInfo {
        String host = "localhost";
        int port = 3306;
        String database = "drone_detection";
        boolean useSSL = false;
        boolean allowPublicKeyRetrieval = true;
        String serverTimezone = "Asia/Shanghai";
    }
}
