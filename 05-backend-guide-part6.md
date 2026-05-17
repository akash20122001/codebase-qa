# Backend Implementation Guide — Part 6: Design Patterns

---

## Overview

This document describes the design patterns used in the project and how they integrate with the existing service layer. The key patterns are:

1. **Interface + Implementation** — All external-facing services use interfaces for swappability
2. **Strategy Pattern** — Language-specific chunking algorithms
3. **Builder Pattern** — LLM prompt construction
4. **Factory via Spring Profiles** — LLM provider selection

---

## 1. Updated Package Structure

```
src/main/java/com/codebaseqa/
├── service/
│   ├── AuthService.java                    (concrete — no interface needed, single impl)
│   ├── RepoService.java                    (concrete)
│   ├── QueryService.java                   (concrete)
│   ├── ConversationService.java            (concrete)
│   ├── CacheService.java                   (concrete)
│   ├── RateLimitService.java               (concrete)
│   ├── SqsService.java                     (concrete)
│   ├── ChunkingService.java                ← INTERFACE
│   ├── EmbeddingService.java               ← INTERFACE
│   ├── LlmService.java                     ← INTERFACE
│   └── impl/
│       ├── DefaultChunkingService.java     ← implements ChunkingService
│       ├── GeminiEmbeddingService.java     ← implements EmbeddingService
│       └── GeminiLlmService.java           ← implements LlmService
├── service/chunking/                       ← STRATEGY PATTERN
│   ├── LanguageChunker.java                ← strategy interface
│   ├── JavaChunker.java
│   ├── TypeScriptChunker.java
│   ├── PythonChunker.java
│   └── FallbackChunker.java
├── service/prompt/                         ← BUILDER PATTERN
│   └── PromptBuilder.java
```

**Rule:** Only create interfaces where there's a real reason for swappability (LLM provider, embedding provider, chunking strategy). Don't create interfaces for AuthService, RepoService, etc. — they have one implementation and will never be swapped.

---

## 2. Service Interfaces

### 2.1 LlmService.java (Interface)

```java
package com.codebaseqa.service;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Interface for LLM chat operations.
 * Allows swapping providers (Gemini, OpenAI, Anthropic) without changing callers.
 */
public interface LlmService {

    /**
     * Stream a chat response.
     * @param systemPrompt System instructions
     * @param conversationHistory Previous messages
     * @param userMessage Current user message
     * @param tokenConsumer Called for each token as it streams
     * @return The full accumulated response
     */
    String streamChat(String systemPrompt,
                      List<Map<String, String>> conversationHistory,
                      String userMessage,
                      Consumer<String> tokenConsumer);
}
```

### 2.2 EmbeddingService.java (Interface)

```java
package com.codebaseqa.service;

import java.util.List;

/**
 * Interface for text embedding operations.
 * Allows swapping embedding providers without changing the indexing/query pipeline.
 */
public interface EmbeddingService {

    /**
     * Generate embedding for a single text.
     * @return float array (dimension depends on model — 768 for Gemini text-embedding-004)
     */
    float[] embedText(String text);

    /**
     * Batch embed multiple texts (more efficient than individual calls).
     */
    List<float[]> embedBatch(List<String> texts);

    /**
     * Convert float array to pgvector string format: "[0.1,0.2,0.3,...]"
     */
    String toVectorString(float[] embedding);

    /**
     * Get the embedding dimension for this provider.
     */
    int getDimension();
}
```

### 2.3 ChunkingService.java (Interface)

```java
package com.codebaseqa.service;

import java.util.List;

/**
 * Interface for code chunking operations.
 * Splits source code files into logical chunks for embedding.
 */
public interface ChunkingService {

    /**
     * Parse a file into logical chunks based on its language.
     */
    List<CodeChunkResult> chunkFile(String content, String filePath, String language);

    /**
     * Detect programming language from file extension.
     */
    String detectLanguage(String filePath);

    /**
     * DTO for chunking results.
     */
    record CodeChunkResult(
        String content,
        String chunkName,
        String chunkType,  // FUNCTION, CLASS, METHOD, MODULE, BLOCK
        int startLine,
        int endLine
    ) {}
}
```

---

## 3. Strategy Pattern — Language Chunkers

### 3.1 LanguageChunker.java (Strategy Interface)

```java
package com.codebaseqa.service.chunking;

import com.codebaseqa.service.ChunkingService.CodeChunkResult;
import java.util.List;

/**
 * Strategy interface for language-specific code chunking.
 * Each implementation knows how to parse one language's syntax.
 */
public interface LanguageChunker {

    /**
     * Chunk the given source code content.
     */
    List<CodeChunkResult> chunk(String content);

    /**
     * Which languages this chunker supports.
     */
    boolean supports(String language);
}
```

### 3.2 JavaChunker.java

```java
package com.codebaseqa.service.chunking;

import com.codebaseqa.service.ChunkingService.CodeChunkResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class JavaChunker implements LanguageChunker {

    private static final Pattern METHOD_PATTERN = Pattern.compile(
        "^\\s*(public|private|protected|static|\\s)*\\s+[\\w<>\\[\\]]+\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{?"
    );
    private static final Pattern CLASS_PATTERN = Pattern.compile(
        "^\\s*(public|private|protected)?\\s*(abstract|static)?\\s*(class|interface|enum)\\s+(\\w+)"
    );

    @Override
    public boolean supports(String language) {
        return "java".equals(language);
    }

    @Override
    public List<CodeChunkResult> chunk(String content) {
        List<CodeChunkResult> chunks = new ArrayList<>();
        String[] lines = content.split("\n");

        int blockStart = -1;
        String blockName = null;
        String blockType = null;
        int braceCount = 0;
        boolean inBlock = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (!inBlock) {
                Matcher classMatcher = CLASS_PATTERN.matcher(line);
                Matcher methodMatcher = METHOD_PATTERN.matcher(line);

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
                    chunks.add(new CodeChunkResult(
                        extractLines(lines, blockStart, i),
                        blockName, blockType,
                        blockStart + 1, i + 1
                    ));
                    inBlock = false;
                }
            }
        }

        if (inBlock && blockStart >= 0) {
            chunks.add(new CodeChunkResult(
                extractLines(lines, blockStart, lines.length - 1),
                blockName, blockType,
                blockStart + 1, lines.length
            ));
        }

        return chunks;
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
}
```

### 3.3 TypeScriptChunker.java

```java
package com.codebaseqa.service.chunking;

import com.codebaseqa.service.ChunkingService.CodeChunkResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class TypeScriptChunker implements LanguageChunker {

    private static final Pattern FUNC_PATTERN = Pattern.compile(
        "^\\s*(export\\s+)?(async\\s+)?function\\s+(\\w+)"
    );
    private static final Pattern ARROW_PATTERN = Pattern.compile(
        "^\\s*(export\\s+)?(const|let|var)\\s+(\\w+)\\s*=\\s*(async\\s+)?\\("
    );
    private static final Pattern CLASS_PATTERN = Pattern.compile(
        "^\\s*(export\\s+)?(default\\s+)?class\\s+(\\w+)"
    );

    @Override
    public boolean supports(String language) {
        return "typescript".equals(language) || "javascript".equals(language);
    }

    @Override
    public List<CodeChunkResult> chunk(String content) {
        List<CodeChunkResult> chunks = new ArrayList<>();
        String[] lines = content.split("\n");

        int blockStart = -1;
        String blockName = null;
        String blockType = null;
        int braceCount = 0;
        boolean inBlock = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            if (!inBlock) {
                Matcher classMatcher = CLASS_PATTERN.matcher(line);
                Matcher funcMatcher = FUNC_PATTERN.matcher(line);
                Matcher arrowMatcher = ARROW_PATTERN.matcher(line);

                if (classMatcher.find()) {
                    blockStart = i;
                    blockName = classMatcher.group(3);
                    blockType = "CLASS";
                    braceCount = countChar(line, '{') - countChar(line, '}');
                    inBlock = true;
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
                    chunks.add(new CodeChunkResult(
                        extractLines(lines, blockStart, i),
                        blockName, blockType,
                        blockStart + 1, i + 1
                    ));
                    inBlock = false;
                }
            }
        }

        if (inBlock && blockStart >= 0) {
            chunks.add(new CodeChunkResult(
                extractLines(lines, blockStart, lines.length - 1),
                blockName, blockType,
                blockStart + 1, lines.length
            ));
        }

        return chunks;
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
}
```

### 3.4 PythonChunker.java

```java
package com.codebaseqa.service.chunking;

import com.codebaseqa.service.ChunkingService.CodeChunkResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class PythonChunker implements LanguageChunker {

    private static final Pattern DEF_PATTERN = Pattern.compile(
        "^(\\s*)(def|class|async\\s+def)\\s+(\\w+)"
    );

    @Override
    public boolean supports(String language) {
        return "python".equals(language);
    }

    @Override
    public List<CodeChunkResult> chunk(String content) {
        List<CodeChunkResult> chunks = new ArrayList<>();
        String[] lines = content.split("\n");

        int blockStart = -1;
        String blockName = null;
        String blockType = null;

        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = DEF_PATTERN.matcher(lines[i]);

            if (matcher.find()) {
                // Save previous block
                if (blockStart >= 0) {
                    chunks.add(new CodeChunkResult(
                        extractLines(lines, blockStart, i - 1).stripTrailing(),
                        blockName, blockType,
                        blockStart + 1, i
                    ));
                }
                blockStart = i;
                blockName = matcher.group(3);
                blockType = matcher.group(2).contains("class") ? "CLASS" : "FUNCTION";
            }
        }

        // Last block
        if (blockStart >= 0) {
            chunks.add(new CodeChunkResult(
                extractLines(lines, blockStart, lines.length - 1).stripTrailing(),
                blockName, blockType,
                blockStart + 1, lines.length
            ));
        }

        return chunks;
    }

    private String extractLines(String[] lines, int start, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i <= Math.min(end, lines.length - 1); i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString();
    }
}
```

### 3.5 FallbackChunker.java

```java
package com.codebaseqa.service.chunking;

import com.codebaseqa.service.ChunkingService.CodeChunkResult;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fallback chunker for unsupported languages.
 * Splits by blank-line-separated blocks with a max token limit.
 */
@Component
@Order(Integer.MAX_VALUE) // Always last priority
public class FallbackChunker implements LanguageChunker {

    private static final int MAX_CHUNK_CHARS = 2000; // ~500 tokens

    @Override
    public boolean supports(String language) {
        return true; // Supports everything as fallback
    }

    @Override
    public List<CodeChunkResult> chunk(String content) {
        List<CodeChunkResult> chunks = new ArrayList<>();
        String[] lines = content.split("\n");

        int blockStart = 0;
        StringBuilder currentBlock = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            currentBlock.append(lines[i]).append("\n");

            boolean isBlockEnd = lines[i].trim().isEmpty() && currentBlock.length() >= MAX_CHUNK_CHARS;
            boolean isFileEnd = i == lines.length - 1;

            if (isBlockEnd || isFileEnd) {
                String blockContent = currentBlock.toString().stripTrailing();
                if (!blockContent.isBlank()) {
                    chunks.add(new CodeChunkResult(
                        blockContent, null, "BLOCK",
                        blockStart + 1, i + 1
                    ));
                }
                blockStart = i + 1;
                currentBlock = new StringBuilder();
            }
        }

        return chunks;
    }
}
```
