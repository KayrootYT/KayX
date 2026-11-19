package com.kayx.manager;

// Runs inside the Shizuku-started process (shell or root). Transaction 16777114 is reserved by Shizuku for destroy().
interface IUserService {
    void destroy() = 16777114;

    /**
     * Runs one user-requested command. Response is three newline-separated fields:
     * exit code, Base64(stdout bytes), Base64(stderr bytes). Binary-safe and Binder-bounded.
     */
    String exec(String cmd, long timeoutMs) = 1;
}