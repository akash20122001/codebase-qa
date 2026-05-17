package com.codebaseqa.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SqsService {

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;

    @Value("${app.aws.sqs.queue-url}")
    private String queueUrl;

    /**
     * Send an indexing job message to SQS.
     * The worker will pick this up and process the indexing.
     */
    public void sendIndexingMessage(UUID jobId, UUID repoId) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                "jobId", jobId.toString(),
                "repoId", repoId.toString()
            ));

            sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(body)
                .build());

            log.info("Sent indexing message to SQS: jobId={}, repoId={}", jobId, repoId);
        } catch (Exception e) {
            log.error("Failed to send SQS message", e);
            throw new RuntimeException("Failed to queue indexing job", e);
        }
    }
}
