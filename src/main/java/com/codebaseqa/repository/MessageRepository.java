package com.codebaseqa.repository;

import com.codebaseqa.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {
    List<Message> findByConversationIdOrderByCreatedAtAsc(UUID conversationId);

    // Get last N messages for conversation context
    List<Message> findTop10ByConversationIdOrderByCreatedAtDesc(UUID conversationId);
}
