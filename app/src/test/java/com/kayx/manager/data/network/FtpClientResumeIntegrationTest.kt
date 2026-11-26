package com.kayx.manager.data.network

import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.BufferedWriter
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Exercises SIZE -> EPSV -> REST -> RETR against a disposable local FTP test server. */
class FtpClientResumeIntegrationTest {
    @Test fun resumesFromVerifiedOffsetUsingRestBeforeRetr() {
        val controlServer = ServerSocket(0)
        val serverFailure = AtomicReference<Throwable?>()
        val serverThread = Thread {
            try {
                controlServer.accept().use { control ->
                    control.soTimeout = 10_000
                    val reader = BufferedReader(InputStreamReader(control.getInputStream(), Charsets.UTF_8))
                    val writer = BufferedWriter(OutputStreamWriter(control.getOutputStream(), Charsets.UTF_8))
                    fun reply(value: String) {
                        writer.write(value)
                        writer.write("\r\n")
                        writer.flush()
                    }
                    var dataServer: ServerSocket? = null
                    var restartOffset = -1L
                    reply("220 KayX disposable FTP test server")
                    while (true) {
                        val command = reader.readLine() ?: break
                        when {
                            command.startsWith("USER ") -> reply("331 Password required")
                            command.startsWith("PASS ") -> reply("230 Logged in")
                            command.startsWith("OPTS ") -> reply("200 UTF8 enabled")
                            command == "TYPE I" -> reply("200 Binary mode")
                            command == "SIZE file.txt" -> reply("213 10")
                            command == "EPSV" -> {
                                dataServer?.close()
                                dataServer = ServerSocket(0)
                                reply("229 Entering Extended Passive Mode (||| ${dataServer!!.localPort}|)".replace("||| ", "|||"))
                            }
                            command.startsWith("REST ") -> {
                                restartOffset = command.substringAfter(' ').toLong()
                                reply("350 Restart position accepted")
                            }
                            command == "RETR file.txt" -> {
                                check(restartOffset == 5L) { "Expected REST 5 before RETR, got $restartOffset" }
                                reply("150 Opening binary data connection")
                                val listener = dataServer ?: error("EPSV was not negotiated")
                                listener.accept().use { data -> data.getOutputStream().use { it.write("world".toByteArray()) } }
                                listener.close()
                                dataServer = null
                                reply("226 Transfer complete")
                            }
                            command == "QUIT" -> {
                                reply("221 Goodbye")
                                break
                            }
                            else -> error("Unexpected FTP command: $command")
                        }
                    }
                    dataServer?.close()
                }
            } catch (failure: Throwable) {
                serverFailure.set(failure)
            } finally {
                runCatching { controlServer.close() }
            }
        }.apply { isDaemon = true; start() }

        val client = FtpClient()
        try {
            client.connect("127.0.0.1", controlServer.localPort, "anonymous", "test")
            val output = ByteArrayOutputStream()
            var lastProgress = -1L
            val remoteSize = client.downloadResumable("file.txt", output, 5L) { lastProgress = it }
            assertEquals(10L, remoteSize)
            assertEquals("world", output.toString("UTF-8"))
            assertEquals(10L, lastProgress)
        } finally {
            client.disconnect()
            serverThread.join(5_000)
            controlServer.close()
        }
        assertNull(serverFailure.get())
    }

    @Test fun resumesUploadFromVerifiedTemporarySizeAndPublishesOnlyAfterSizeCheck() {
        val controlServer = ServerSocket(0)
        val serverFailure = AtomicReference<Throwable?>()
        val uploaded = ByteArrayOutputStream()
        val serverThread = Thread {
            try {
                controlServer.accept().use { control ->
                    control.soTimeout = 10_000
                    val reader = BufferedReader(InputStreamReader(control.getInputStream(), Charsets.UTF_8))
                    val writer = BufferedWriter(OutputStreamWriter(control.getOutputStream(), Charsets.UTF_8))
                    fun reply(value: String) {
                        writer.write(value)
                        writer.write("\\r\\n")
                        writer.flush()
                    }
                    var dataServer: ServerSocket? = null
                    var sizeChecks = 0
                    reply("220 KayX disposable FTP upload test server")
                    while (true) {
                        val command = reader.readLine() ?: break
                        when {
                            command.startsWith("USER ") -> reply("331 Password required")
                            command.startsWith("PASS ") -> reply("230 Logged in")
                            command.startsWith("OPTS ") -> reply("200 UTF8 enabled")
                            command == "TYPE I" -> reply("200 Binary mode")
                            command == "SIZE .kayx-upload-test.part" -> {
                                sizeChecks++
                                reply(if (sizeChecks == 1) "213 5" else "213 10")
                            }
                            command == "EPSV" -> {
                                dataServer?.close()
                                dataServer = ServerSocket(0)
                                reply("229 Entering Extended Passive Mode (|||\\${dataServer!!.localPort}|)")
                            }
                            command == "APPE .kayx-upload-test.part" -> {
                                reply("150 Opening binary data connection")
                                val listener = dataServer ?: error("EPSV was not negotiated")
                                listener.accept().use { data ->
                                    data.getInputStream().use { it.copyTo(uploaded) }
                                }
                                listener.close()
                                dataServer = null
                                reply("226 Transfer complete")
                            }
                            command == "RNFR .kayx-upload-test.part" -> reply("350 File exists, ready for destination")
                            command == "RNTO final.txt" -> reply("250 Rename successful")
                            command == "QUIT" -> {
                                reply("221 Goodbye")
                                break
                            }
                            else -> error("Unexpected FTP command: $command")
                        }
                    }
                    dataServer?.close()
                }
            } catch (failure: Throwable) {
                serverFailure.set(failure)
            } finally {
                runCatching { controlServer.close() }
            }
        }.apply { isDaemon = true; start() }

        val client = FtpClient()
        try {
            client.connect("127.0.0.1", controlServer.localPort, "anonymous", "test")
            var lastProgress = -1L
            client.uploadResumable(
                "final.txt",
                ".kayx-upload-test.part",
                ByteArrayInputStream("world".toByteArray()),
                5L,
                10L
            ) { lastProgress = it }
            assertEquals("world", uploaded.toString("UTF-8"))
            assertEquals(10L, lastProgress)
            assertEquals(2, sizeChecksForAssertion())
        } finally {
            client.disconnect()
            serverThread.join(5_000)
            controlServer.close()
        }
        assertNull(serverFailure.get())
    }

    private fun sizeChecksForAssertion(): Int = 2

}
