package com.finallyapp.chat;

import com.finallyapp.api.ChatApi;
import com.finallyapp.api.model.ChatMessage;
import com.finallyapp.api.model.ChatRequest;
import com.finallyapp.api.model.ChatResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController implements ChatApi {

    private final ChatService service;

    public ChatController(ChatService service) {
        this.service = service;
    }

    @Override
    public ResponseEntity<ChatResponse> sendChatMessage(ChatRequest req) {
        return ResponseEntity.ok(service.send(req.getMessage()));
    }

    @Override
    public ResponseEntity<List<ChatMessage>> getChatHistory() {
        return ResponseEntity.ok(service.history());
    }
}
