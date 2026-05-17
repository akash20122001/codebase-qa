package com.codebaseqa.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConnectRepoRequest {

    @NotBlank(message = "Repository full name is required")
    @Pattern(regexp = "^[a-zA-Z0-9_.-]+/[a-zA-Z0-9_.-]+$",
             message = "Repository name must be in 'owner/repo' format")
    private String repoFullName;

    private String branch;  // Optional — defaults to repo's default branch
}
