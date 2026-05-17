# Backend Implementation Guide — Part 3: Core Services (Continued)

---

## 5.9 ChunkingService.java

This is the **key differentiator** of the project. It parses code files using tree-sitter and splits them at logical boundaries (functions, classes, methods) instead of arbitrary token counts.

### Approach

Since tree-sitter doesn't have a native Java binding that's easy to use, we'll use a **process-based approach**: call a small Node.js/Python script that uses tree-sitter and returns JSON chunks. Alternatively, use regex-based fallback for common languages.

### Strategy

1. **Primary:** Use regex-based parsing for common patterns (functions, classes, methods)
2. **Fallback:** If a file doesn't match known patterns, chunk by logical blocks (separated by blank lines) with a max token limit

```java
package com.codebaseqa.service;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class ChunkingService {

    private static final int MAX_CHUNK_TOKENS = 500;  // ~500 tokens per chunk
    private static final int MIN_CHUNK_TOKENS = 50;   // Don't create tiny chunks
    private static final int CHARS_PER_TOKEN = 4;     // Rough estimate

    @Data
    @Builder
    @AllArgsConstructor
    public static class CodeChunkResult {
        private String content;
        private String chunkName;
        private String chunkType;  // FUNCTION, CLASS, METHOD, MODULE, BLOCK
        private int startLine;
        private int endLine;
    }

    /**
     * Parse a file into logical chunks based on its language.
     */
    public List<CodeChunkResult> chunkFile(String content, String filePath, String language) {
        List<CodeChunkResult> chunks = switch (language) {
            case "java" -> chunkJava(content);
            case "typescript", "javascript" -> chunkTypeScript(content);
            case "python" -> chunkPython(content);
            default -> chunkByBlocks(content);
        };

        // Filter out chunks that are too small
        chunks = chunks.stream()
            .filter(c -> estimateTokens(c.getContent()) >= MIN_CHUNK_TOKENS)
            .toList();

        // If no chunks found (or all too small), fall back to block chunking
        if (chunks.isEmpty()) {
            chunks = chunkByBlocks(content);
        }

        return chunks;
    }

    /**
     * Chunk Java files — detect classes, methods, interfaces, enums.
     */
    private List<CodeChunkResult> chunkJava(String content) {
        List<CodeChunkResult> chunks = new ArrayList<>();
        String[] lines = content.split("\n");

        // Pattern for Java methods and classes
        Pattern methodPattern = Pattern.compile(
            "^\\s*(public|private|protected|static|\\s)*\\s+[\\w<>\\[\\]]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{?"
        );
        Pattern classPattern = Pattern.compile(
            "^\\s*(public|private|protected)?\\s*(abstract|static)?\\s*(class|interface|enum)\\s+(\\w+)"
        );

        int blockStart = -1;
        String blockName = null;
        String blockType = null;
        int braceCount = 0;
        boolean inBlock = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (!inBlock) {
                Matcher classMatcher = classPattern.matcher(line);
                Matcher methodMatcher = methodPattern.matcher(line);

                if (classMatcher.find()) {
                    blockStart = i;
                    blockName = classMatcher.group(4);
                    blockType = classMatcher.group(3).toUpperCase();
                    braceCount = countChar(line, '{') - countChar(line, '}');
                    inBlock = braceCount > 0;
                } else if (methodMatcher.find()) {
                    blockStart = i;
                    blockName = methodMatcher.group(2);
                    blockType = "METHOD";
                    braceCount = countChar(line, '{') - countChar(line, '}');
                    inBlock = braceCount > 0;
                }
            } else {
                braceCount += countChar(line, '{') - countChar(line, '}');

                if (braceCount <= 0) {
                    // End of block
                    String blockContent = extractLines(lines, blockStart, i);
                    chunks.add(CodeChunkResult.builder()
                        .content(blockContent)
                        .chunkName(blockName)
                        .chunkType(blockType)
                        .startLine(blockStart + 1)
                        .endLine(i + 1)
                        .build());
                    inBlock = false;
                }
            }
        }

        // Handle unclosed block (file ended)
        if (inBlock && blockStart >= 0) {
            String blockContent = extractLines(lines, blockStart, lines.length - 1);
            chunks.add(CodeChunkResult.builder()
                .content(blockContent)
                .chunkName(blockName)
                .chunkType(blockType)
                .startLine(blockStart + 1)
                .endLine(lines.length)
                .build());
        }

        return splitLargeChunks(chunks);
    }

    /**
     * Chunk TypeScript/JavaScript files — detect functions, classes, arrow functions, exports.
     */
    private List<CodeChunkResult> chunkTypeScript(String content) {
        List<CodeChunkResult> chunks = new ArrayList<>();
        String[] lines = content.split("\n");

        Pattern funcPattern = Pattern.compile(
            "^\\s*(export\\s+)?(async\\s+)?function\\s+(\\w+)"
        );
        Pattern arrowPattern = Pattern.compile(
            "^\\s*(export\\s+)?(const|let|var)\\s+(\\w+)\\s*=\\s*(async\\s+)?\\("
        );
        Pattern classPattern = Pattern.compile(
            "^\\s*(export\\s+)?(default\\s+)?class\\s+(\\w+)"
        );

        int blockStart = -1;
        String blockName = null;
        String blockType = null;
        int braceCount = 0;
        boolean inBlock = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (!inBlock) {
                Matcher funcMatcher = funcPattern.matcher(line);
                Matcher arrowMatcher = arrowPattern.matcher(line);
                Matcher classMatcher = classPattern.matcher(line);

                if (classMatcher.find()) {
                    blockStart = i;
                    blockName = classMatcher.group(3);
                    blockType = "CLASS";
                    braceCount = countChar(line, '{') - countChar(line, '}');
                    inBlock = braceCount > 0 || !line.contains("{");
                } else if (funcMatcher.find()) {
                    blockStart = i;
                    blockName = funcMatcher.group(3);
                    blockType = "FUNCTION";
                    braceCount = countChar(line, '{') - countChar(line, '}');
                    inBlock = true;
                } else if (arrowMatcher.find()) {
                    blockStart = i;
                    blockName = arrowMatcher.group(3);
                    blockType = "FUNCTION";
                    braceCount = countChar(line, '{') - countChar(line, '}');
                    inBlock = true;
                }
            } else {
                braceCount += countChar(line, '{') - countChar(line, '}');

                if (braceCount <= 0) {
                    String blockContent = extractLines(lines, blockStart, i);
                    chunks.add(CodeChunkResult.builder()
                        .content(blockContent)
                        .chunkName(blockName)
                        .chunkType(blockType)
                        .startLine(blockStart + 1)
                        .endLine(i + 1)
                        .build());
                    inBlock = false;
                }
            }
        }

        if (inBlock && blockStart >= 0) {
            String blockContent = extractLines(lines, blockStart, lines.length - 1);
            chunks.add(CodeChunkResult.builder()
                .content(blockContent)
                .chunkName(blockName)
                .chunkType(blockType)
                .startLine(blockStart + 1)
                .endLine(lines.length)
                .build());
        }

        return splitLargeChunks(chunks);
    }

    /**
     * Chunk Python files — detect functions and classes by indentation.
     */
    private List<CodeChunkResult> chunkPython(String content) {
        List<CodeChunkResult> chunks = new ArrayList<>();
        String[] lines = content.split("\n");

        Pattern defPattern = Pattern.compile("^(\\s*)(def|class|async\\s+def)\\s+(\\w+)");

        int blockStart = -1;
        String blockName = null;
        String blockType = null;
        int blockIndent = 0;

        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = defPattern.matcher(lines[i]);

            if (matcher.find()) {
                // Save previous block
                if (blockStart >= 0) {
                    String blockContent = extractLines(lines, blockStart, i - 1);
                    chunks.add(CodeChunkResult.builder()
                        .content(blockContent.stripTrailing())
                        .chunkName(blockName)
                        .chunkType(blockType)
                        .startLine(blockStart + 1)
                        .endLine(i)
                        .build());
                }

                blockStart = i;
                blockIndent = matcher.group(1).length();
                blockName = matcher.group(3);
                blockType = matcher.group(2).contains("class") ? "CLASS" : "FUNCTION";
            }
        }

        // Last block
        if (blockStart >= 0) {
            String blockContent = extractLines(lines, blockStart, lines.length - 1);
            chunks.add(CodeChunkResult.builder()
                .content(blockContent.stripTrailing())
                .chunkName(blockName)
                .chunkType(blockType)
                .startLine(blockStart + 1)
                .endLine(lines.length)
                .build());
        }

        return splitLargeChunks(chunks);
    }

    /**
     * Fallback: chunk by blank-line-separated blocks.
     */
    private List<CodeChunkResult> chunkByBlocks(String content) {
        List<CodeChunkResult> chunks = new ArrayList<>();
        String[] lines = content.split("\n");

        int blockStart = 0;
        StringBuilder currentBlock = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            currentBlock.append(lines[i]).append("\n");

            boolean isBlockEnd = lines[i].trim().isEmpty() &&
                estimateTokens(currentBlock.toString()) >= MAX_CHUNK_TOKENS;
            boolean isFileEnd = i == lines.length - 1;

            if (isBlockEnd || isFileEnd) {
                String blockContent = currentBlock.toString().stripTrailing();
                if (!blockContent.isBlank()) {
                    chunks.add(CodeChunkResult.builder()
                        .content(blockContent)
                        .chunkName(null)
                        .chunkType("BLOCK")
                        .startLine(blockStart + 1)
                        .endLine(i + 1)
                        .build());
                }
                blockStart = i + 1;
                currentBlock = new StringBuilder();
            }
        }

        return chunks;
    }

    /**
     * Split chunks that exceed MAX_CHUNK_TOKENS into smaller pieces.
     */
    private List<CodeChunkResult> splitLargeChunks(List<CodeChunkResult> chunks) {
        List<CodeChunkResult> result = new ArrayList<>();
        for (CodeChunkResult chunk : chunks) {
            if (estimateTokens(chunk.getContent()) > MAX_CHUNK_TOKENS * 2) {
                // Split large chunk into sub-chunks by lines
                String[] lines = chunk.getContent().split("\n");
                int linesPerChunk = (MAX_CHUNK_TOKENS * CHARS_PER_TOKEN) / 80; // ~80 chars per line
                for (int i = 0; i < lines.length; i += linesPerChunk) {
                    int end = Math.min(i + linesPerChunk, lines.length);
                    String subContent = extractLines(lines, i, end - 1);
                    result.add(CodeChunkResult.builder()
                        .content(subContent)
                        .chunkName(chunk.getChunkName())
                        .chunkType(chunk.getChunkType())
                        .startLine(chunk.getStartLine() + i)
                        .endLine(chunk.getStartLine() + end - 1)
                        .build());
                }
            } else {
                result.add(chunk);
            }
        }
        return result;
    }

    private int estimateTokens(String text) {
        return text.length() / CHARS_PER_TOKEN;
    }

    private int countChar(String s, char c) {
        return (int) s.chars().filter(ch -> ch == c).count();
    }

    private String extractLines(String[] lines, int start, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= Math.min(end, lines.length - 1); i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString();
    }

    /**
     * Detect language from file extension.
     */
    public String detectLanguage(String filePath) {
        String ext = filePath.substring(filePath.lastIndexOf('.') + 1).toLowerCase();
        return switch (ext) {
            case "java" -> "java";
            case "ts", "tsx" -> "typescript";
            case "js", "jsx" -> "javascript";
            case "py" -> "python";
            case "go" -> "go";
            case "rs" -> "rust";
            case "rb" -> "ruby";
            case "php" -> "php";
            case "cs" -> "csharp";
            case "kt" -> "kotlin";
            case "swift" -> "swift";
            case "scala" -> "scala";
            case "cpp", "cc", "cxx" -> "cpp";
            case "c", "h" -> "c";
            default -> "unknown";
        };
    }
}
```

---

## 5.10 IndexingService.java

This is the core service that orchestrates the entire indexing pipeline. Called by the SQS worker.

```java
package com.codebaseqa.service;

import com.codebaseqa.model.CodeChunk;
import com.codebaseqa.model.IndexingJob;
import com.codebaseqa.model.Repo;
import com.codebaseqa.repository.ChunkRepository;
import com.codebaseqa.repository.IndexingJobRepository;
import com.codebaseqa.repository.RepoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndexingService {

    private final RepoRepository repoRepository;
    private final IndexingJobRepository jobRepository;
    private final ChunkRepository chunkRepository;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final CacheService cacheService;

    @Value("${app.indexing.temp-dir}")
    private String tempDir;

    @Value("${app.indexing.max-file-size-kb}")
    private int maxFileSizeKb;

    @Value("${app.indexing.supported-extensions}")
    private String supportedExtensions;

    @Value("${app.indexing.excluded-dirs}")
    private String excludedDirs;

    private static final int EMBEDDING_BATCH_SIZE = 20;

    /**
     * Process a full indexing job.
     * Called by the SQS worker.
     */
    @Transactional
    public void processFullIndexing(UUID jobId, UUID repoId) {
        IndexingJob job = jobRepository.findById(jobId)
            .orElseThrow(() -> new RuntimeException("Job not found: " + jobId));
        Repo repo = repoRepository.findById(repoId)
            .orElseThrow(() -> new RuntimeException("Repo not found: " + repoId));

        Path cloneDir = Path.of(tempDir, repo.getId().toString());

        try {
            // Update status
            job.setStatus(IndexingJob.JobStatus.PROCESSING);
            job.setStartedAt(Instant.now());
            job.setAttempts(job.getAttempts() + 1);
            jobRepository.save(job);

            repo.setStatus(Repo.RepoStatus.INDEXING);
            repoRepository.save(repo);

            // 1. Delete existing chunks for this repo
            chunkRepository.deleteByRepoId(repoId);

            // 2. Clone the repository
            log.info("Cloning repo: {}", repo.getFullName());
            cloneRepository(repo, cloneDir);

            // 3. Walk files and collect supported files
            List<Path> files = collectFiles(cloneDir);
            job.setTotalFiles(files.size());
            jobRepository.save(job);

            log.info("Found {} files to index in {}", files.size(), repo.getFullName());

            // 4. Parse, chunk, embed, and store
            List<CodeChunk> allChunks = new ArrayList<>();
            int processedCount = 0;

            for (Path file : files) {
                try {
                    String content = Files.readString(file);
                    String relativePath = cloneDir.relativize(file).toString();
                    String language = chunkingService.detectLanguage(relativePath);

                    List<ChunkingService.CodeChunkResult> fileChunks =
                        chunkingService.chunkFile(content, relativePath, language);

                    for (ChunkingService.CodeChunkResult chunk : fileChunks) {
                        allChunks.add(CodeChunk.builder()
                            .repo(repo)
                            .filePath(relativePath)
                            .startLine(chunk.getStartLine())
                            .endLine(chunk.getEndLine())
                            .chunkType(CodeChunk.ChunkType.valueOf(chunk.getChunkType()))
                            .chunkName(chunk.getChunkName())
                            .content(chunk.getContent())
                            .language(language)
                            .tokenCount(chunk.getContent().length() / 4)
                            .build());
                    }
                } catch (Exception e) {
                    log.warn("Failed to process file: {}", file, e);
                }

                processedCount++;
                job.setProcessedFiles(processedCount);
                job.setProgress((processedCount * 100) / files.size());
                jobRepository.save(job);
            }

            // 5. Batch embed all chunks
            log.info("Embedding {} chunks for {}", allChunks.size(), repo.getFullName());
            embedChunksInBatches(allChunks);

            // 6. Save all chunks to database
            chunkRepository.saveAll(allChunks);

            // 7. Update job and repo status
            job.setStatus(IndexingJob.JobStatus.COMPLETED);
            job.setCompletedAt(Instant.now());
            job.setProgress(100);
            jobRepository.save(job);

            repo.setStatus(Repo.RepoStatus.READY);
            repo.setLastIndexedAt(Instant.now());
            repo.setTotalChunks(allChunks.size());
            repoRepository.save(repo);

            // 8. Invalidate cache for this repo
            cacheService.invalidateRepoCache(repoId);

            log.info("Indexing completed for {}: {} chunks", repo.getFullName(), allChunks.size());

        } catch (Exception e) {
            log.error("Indexing failed for repo {}: {}", repo.getFullName(), e.getMessage(), e);
            job.setStatus(IndexingJob.JobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            job.setCompletedAt(Instant.now());
            jobRepository.save(job);

            repo.setStatus(Repo.RepoStatus.FAILED);
            repoRepository.save(repo);

            throw new RuntimeException("Indexing failed", e);
        } finally {
            // Cleanup cloned repo
            deleteDirectory(cloneDir);
        }
    }

    /**
     * Process incremental indexing (only changed files).
     */
    @Transactional
    public void processIncrementalIndexing(UUID jobId, UUID repoId, List<String> changedFiles) {
        IndexingJob job = jobRepository.findById(jobId).orElseThrow();
        Repo repo = repoRepository.findById(repoId).orElseThrow();
        Path cloneDir = Path.of(tempDir, repo.getId().toString());

        try {
            job.setStatus(IndexingJob.JobStatus.PROCESSING);
            job.setStartedAt(Instant.now());
            jobRepository.save(job);

            // 1. Delete old chunks for changed files
            chunkRepository.deleteByRepoIdAndFilePaths(repoId, changedFiles);

            // 2. Clone repo
            cloneRepository(repo, cloneDir);

            // 3. Process only changed files
            List<CodeChunk> newChunks = new ArrayList<>();
            for (String filePath : changedFiles) {
                Path file = cloneDir.resolve(filePath);
                if (!Files.exists(file)) continue; // File was deleted

                try {
                    String content = Files.readString(file);
                    String language = chunkingService.detectLanguage(filePath);
                    List<ChunkingService.CodeChunkResult> fileChunks =
                        chunkingService.chunkFile(content, filePath, language);

                    for (ChunkingService.CodeChunkResult chunk : fileChunks) {
                        newChunks.add(CodeChunk.builder()
                            .repo(repo)
                            .filePath(filePath)
                            .startLine(chunk.getStartLine())
                            .endLine(chunk.getEndLine())
                            .chunkType(CodeChunk.ChunkType.valueOf(chunk.getChunkType()))
                            .chunkName(chunk.getChunkName())
                            .content(chunk.getContent())
                            .language(language)
                            .tokenCount(chunk.getContent().length() / 4)
                            .build());
                    }
                } catch (Exception e) {
                    log.warn("Failed to process file: {}", filePath, e);
                }
            }

            // 4. Embed and save
            embedChunksInBatches(newChunks);
            chunkRepository.saveAll(newChunks);

            // 5. Update status
            job.setStatus(IndexingJob.JobStatus.COMPLETED);
            job.setCompletedAt(Instant.now());
            jobRepository.save(job);

            repo.setLastIndexedAt(Instant.now());
            repo.setTotalChunks(chunkRepository.countByRepoId(repoId));
            repoRepository.save(repo);

            cacheService.invalidateRepoCache(repoId);

            log.info("Incremental indexing completed: {} files, {} new chunks",
                changedFiles.size(), newChunks.size());

        } catch (Exception e) {
            job.setStatus(IndexingJob.JobStatus.FAILED);
            job.setErrorMessage(e.getMessage());
            jobRepository.save(job);
            throw new RuntimeException("Incremental indexing failed", e);
        } finally {
            deleteDirectory(cloneDir);
        }
    }

    private void cloneRepository(Repo repo, Path targetDir) throws Exception {
        deleteDirectory(targetDir); // Clean up any previous clone

        Git.cloneRepository()
            .setURI("https://github.com/" + repo.getFullName() + ".git")
            .setDirectory(targetDir.toFile())
            .setBranch(repo.getDefaultBranch())
            .setDepth(1)  // Shallow clone for speed
            .setCredentialsProvider(
                new UsernamePasswordCredentialsProvider(repo.getUser().getGithubToken(), ""))
            .call()
            .close();
    }

    private List<Path> collectFiles(Path rootDir) throws IOException {
        Set<String> extensions = Set.of(supportedExtensions.split(","));
        Set<String> excluded = Set.of(excludedDirs.split(","));
        List<Path> files = new ArrayList<>();

        Files.walkFileTree(rootDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                String dirName = dir.getFileName().toString();
                if (excluded.contains(dirName) || dirName.startsWith(".")) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String fileName = file.getFileName().toString();
                String ext = fileName.contains(".")
                    ? "." + fileName.substring(fileName.lastIndexOf('.') + 1)
                    : "";

                if (extensions.contains(ext) && attrs.size() <= maxFileSizeKb * 1024L) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        return files;
    }

    private void embedChunksInBatches(List<CodeChunk> chunks) {
        for (int i = 0; i < chunks.size(); i += EMBEDDING_BATCH_SIZE) {
            int end = Math.min(i + EMBEDDING_BATCH_SIZE, chunks.size());
            List<CodeChunk> batch = chunks.subList(i, end);

            List<String> texts = batch.stream()
                .map(c -> c.getFilePath() + "\n" + c.getContent())
                .toList();

            List<float[]> embeddings = embeddingService.embedBatch(texts);

            for (int j = 0; j < batch.size(); j++) {
                batch.get(j).setEmbedding(embeddingService.toVectorString(embeddings.get(j)));
            }

            log.debug("Embedded batch {}/{}", Math.min(end, chunks.size()), chunks.size());
        }
    }

    private void deleteDirectory(Path dir) {
        try {
            if (Files.exists(dir)) {
                Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
            }
        } catch (IOException e) {
            log.warn("Failed to delete directory: {}", dir, e);
        }
    }
}
```
