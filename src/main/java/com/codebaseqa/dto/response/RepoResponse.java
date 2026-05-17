package com.codebaseqa.dto.response;

import com.codebaseqa.model.Repo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoResponse {

    private UUID id;
    private String fullName;
    private String branch;
    private String status;
    private Integer totalChunks;
    private Instant lastIndexedAt;
    private Boolean webhookActive;
    private Instant createdAt;

    public static RepoResponse from(Repo repo) {
        return RepoResponse.builder()
            .id(repo.getId())
            .fullName(repo.getFullName())
            .branch(repo.getDefaultBranch())
            .status(repo.getStatus().name())
            .totalChunks(repo.getTotalChunks())
            .lastIndexedAt(repo.getLastIndexedAt())
            .webhookActive(repo.getWebhookId() != null)
            .createdAt(repo.getCreatedAt())
            .build();
    }
}
