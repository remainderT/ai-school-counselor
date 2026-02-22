package org.buaa.rag.config;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Spring MVC Web配置
 * 确保中文字符正确编码和静态资源处理
 */
@Configuration
public class WebMvcConfiguration implements WebMvcConfigurer {

    /**
     * 配置静态资源处理器
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 静态资源路径映射
        String[] resourceLocations = {
                "classpath:/static/",
                "classpath:/public/",
                "classpath:/resources/",
                "classpath:/META-INF/resources/"
        };

        registry.addResourceHandler("/static/**")
                .addResourceLocations("classpath:/static/");

        registry.addResourceHandler("/**")
                .addResourceLocations(resourceLocations);
    }

    /**
     * 配置跨域访问
     * 允许前端在不同来源访问 /api 接口
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }

    /**
     * 配置HTTP消息转换器
     * 确保中文字符正确显示
     */
    @Override
    public void configureMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.add(createStringConverter());
        converters.add(createJsonConverter());
    }

    /**
     * 创建字符串转换器（UTF-8编码）
     */
    private StringHttpMessageConverter createStringConverter() {
        StringHttpMessageConverter stringConverter =
                new StringHttpMessageConverter(StandardCharsets.UTF_8);
        stringConverter.setWriteAcceptCharset(false);
        return stringConverter;
    }

    /**
     * 创建JSON转换器（不转义中文字符）
     */
    private MappingJackson2HttpMessageConverter createJsonConverter() {
        MappingJackson2HttpMessageConverter jsonConverter =
                new MappingJackson2HttpMessageConverter();

        ObjectMapper objectMapper = jsonConverter.getObjectMapper();
        objectMapper.getFactory().configure(
                JsonGenerator.Feature.ESCAPE_NON_ASCII, false
        );

        jsonConverter.setObjectMapper(objectMapper);
        return jsonConverter;
    }
}
