package com.shubham.aiassistant;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AiController {

    private final ChatModel chatModel;

    public AiController(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    @PostMapping("/ask")
    public AskResponse ask(@RequestBody AskRequest request) {
        String answer = chatModel.call(request.question());
        return new AskResponse(answer);
    }
}
