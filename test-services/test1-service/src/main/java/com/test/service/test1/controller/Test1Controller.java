package com.test.service.test1.controller;

import com.test.service.shared.UserIdService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/test1")
public class Test1Controller {

    private final UserIdService userIdService;

    @Autowired
    public Test1Controller(UserIdService userIdService) {
        this.userIdService = userIdService;
    }

    @GetMapping
    public String test1(@RequestHeader(value = "userId", required = false) String userId) {
        // If no userId in header, generate new one
        if (userId == null || userId.isEmpty()) {
            userId = userIdService.generateUserId();
        }

        // Call setUserId method
        String result = userIdService.setUserId(userId);

        return "Test1 - userId: " + userId + ", setUserId result: " + result;
    }

    @GetMapping("/setUserId")
    public String setUserIdEndpoint(@RequestParam String userId) {
        String result = userIdService.setUserId(userId);
        return result;
    }

    @GetMapping("/generateUserId")
    public String generateUserId() {
        String userId = userIdService.generateUserId();
        return userId;
    }
}
