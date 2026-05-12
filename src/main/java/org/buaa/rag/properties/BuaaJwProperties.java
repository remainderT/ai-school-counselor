package org.buaa.rag.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Data
@Component
@ConfigurationProperties(prefix = "buaa.jw")
public class BuaaJwProperties {

    private String baseUrl = "https://byxt.buaa.edu.cn";

    private String scorePath = "/jwapp/sys/homeapp/api/home/student/scores.do";

    private String schedulePath = "/jwapp/sys/homeapp/api/home/student/getMyScheduleDetail.do";

    private String cookie;

    private String referer = "https://byxt.buaa.edu.cn/jwapp/sys/homeapp/home/index.html?contextPath=/jwapp";

    private String userAgent = "Mozilla/5.0";

    private int connectTimeoutSeconds = 5;

    private int requestTimeoutSeconds = 20;
}
