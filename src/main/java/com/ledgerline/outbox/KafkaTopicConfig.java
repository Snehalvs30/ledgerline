package com.ledgerline.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/** Creates the topic at startup if it doesn't exist. */
@Configuration
public class KafkaTopicConfig {

    @Bean
    public NewTopic transfersTopic(@Value("${ledgerline.outbox.topic}") String topic) {
        return TopicBuilder.name(topic)
                .partitions(3)
                .replicas(1)   // single local broker; production would use 3
                .build();
    }
}