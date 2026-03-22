package com.test.service.test2.controller;

import com.test.service.shared.UserIdService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/test2")
public class Test2Controller {

    private final UserIdService userIdService;

    @Autowired
    public Test2Controller(UserIdService userIdService) {
        this.userIdService = userIdService;
    }

    @GetMapping
    public String test2(@RequestHeader(value = "userId", required = false) String userId) {
        // If no userId in header, generate new one
        if (userId == null || userId.isEmpty()) {
            userId = userIdService.generateUserId();
        }

        // Call setUserId method
        String result = userIdService.setUserId(userId);

        return "Test2 - userId: " + userId + ", setUserId result: " + result;
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
