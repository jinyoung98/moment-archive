package dev.jinyoung.archive.processing;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import dev.jinyoung.archive.processing.thumb.ThumbnailProperties;

/** processing 패키지 설정 바인딩. */
@Configuration
@EnableConfigurationProperties(ThumbnailProperties.class)
public class ProcessingConfig {
}
