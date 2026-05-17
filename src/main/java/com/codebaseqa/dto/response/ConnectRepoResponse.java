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
public class ConnectRepoResponse {

    private UUID id;
    private String fullName;
    private String branch;
    private String status;
    private UUID jobId;
    private Instant createdAt;

    public static ConnectRepoResponse from(Repo repo, UUID jobId) {
        return ConnectRepoResponse.builder()
            .id(repo.getId())
            .fullName(repo.getFullName())
            .branch(repo.getDefaultBranch())
            .status(repo.getStatus().name())
            .jobId(jobId)
            .createdAt(repo.getCreatedAt())
            .build();
    }
}
