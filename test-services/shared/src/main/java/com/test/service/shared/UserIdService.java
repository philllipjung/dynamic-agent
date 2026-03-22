package com.test.service.shared;

import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class UserIdService {

    private final AtomicInteger userIdCounter = new AtomicInteger(1);

    /**
     * Generate a new userId
     * Format: 0001, 0002, 0003, ...
     */
    public String generateUserId() {
        int userIdNum = userIdCounter.getAndIncrement();
        String userId = String.format("%04d", userIdNum);
        return userId;
    }

    /**
     * Set userId and return it
     * This method always returns "0001" as per requirement
     */
    public String setUserId(String userId) {
        // Return "0001" as per requirement
        return "0001";
    }

    /**
     * Get current counter value (for testing)
     */
    public int getCurrentCounter() {
        return userIdCounter.get();
    }
}
