// 创建或修改 WebConfig.java 来正确配置 CORS
package com.example.ffmpeg.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

@Configuration
public class WebConfig {

    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration corsConfig = new CorsConfiguration();

        // 方法1: 明确指定允许的源（生产环境推荐）
        corsConfig.setAllowedOrigins(Arrays.asList(
                "http://localhost:3000",    // React开发服务器
                "http://localhost:8080",    // 本地应用
                "http://127.0.0.1:8080",    // 本地应用
                "http://localhost:5173",    // Vite开发服务器
                "http://localhost:4200"     // Angular开发服务器
        ));

        // 方法2: 使用 allowedOriginPatterns 支持通配符（如果需要的话）
        // corsConfig.setAllowedOriginPatterns(Arrays.asList("*"));

        // 允许的HTTP方法
        corsConfig.setAllowedMethods(Arrays.asList(
                "GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD"
        ));

        // 允许的请求头
        corsConfig.setAllowedHeaders(Arrays.asList(
                "Authorization",
                "Content-Type",
                "X-Requested-With",
                "Accept",
                "Origin",
                "Access-Control-Request-Method",
                "Access-Control-Request-Headers"
        ));

        // 允许发送凭据（cookies, authorization headers等）
        corsConfig.setAllowCredentials(true);

        // 预检请求的缓存时间（秒）
        corsConfig.setMaxAge(3600L);

        // 应用配置到所有路径
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfig);

        return new CorsWebFilter(source);
    }
}
