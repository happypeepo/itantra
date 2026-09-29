package org.itantra.app

import org.itantra.app.link.Frame
import org.itantra.app.link.LinkService
import org.junit.Assert.*
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue

class LinkServiceTest {
    @Test fun tcpHandlesFragmentationCrcDropAndDisconnect() {
        val hosted = CountDownLatch(1)
        val connected = CountDownLatch(1)
        val dropped = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        val frames = LinkedBlockingQueue<Frame>()
        val service = LinkService({
            if (it.startsWith("Hosting")) hosted.countDown()
            if (it.startsWith("Connected")) connected.countDown()
            if (it.startsWith("Dropped")) dropped.countDown()
            if (it.startsWith("Disconnected")) disconnected.countDown()
        }, { frames.offer(it) })
        try {
            service.connect(null)
            assertTrue(hosted.await(5, TimeUnit.SECONDS))
            Socket("127.0.0.1", LinkService.PORT).use { peer ->
                peer.soTimeout = 5000
                assertTrue(connected.await(5, TimeUnit.SECONDS))
                val valid = Frame.speech("Hello தமிழில்", 8, 42).encode()
                val corrupt = valid.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
                peer.getOutputStream().write(corrupt)
                for (byte in valid) { peer.getOutputStream().write(byte.toInt()); peer.getOutputStream().flush() }
                assertTrue(dropped.await(5, TimeUnit.SECONDS))
                assertEquals("Hello தமிழில்", frames.poll(5, TimeUnit.SECONDS)?.text())
                assertTrue(frames.isEmpty())
                val sent = CountDownLatch(1)
                service.send(Frame(2, 0, 43, byteArrayOf(2))) { sent.countDown() }
                val response = ByteArray(9)
                java.io.DataInputStream(peer.getInputStream()).readFully(response)
                assertEquals(2, Frame.decode(response).payload[0].toInt())
                assertTrue(sent.await(5, TimeUnit.SECONDS))
            }
            assertTrue(disconnected.await(5, TimeUnit.SECONDS))
            assertFalse(service.connected)
        } finally { service.close() }
    }
}
