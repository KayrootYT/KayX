package com.kayx.manager;

// Runs inside the Shizuku-started process (shell or root uid). Transaction 16777114 is reserved by Shizuku for destroy().
interface IUserService {
    void destroy() = 16777114;

    /** Runs `sh -c cmd`; returns "exitCode\u0000stdout\u0000stderr" (output truncated to stay under the Binder limit). */
    String exec(String cmd) = 1;
}
