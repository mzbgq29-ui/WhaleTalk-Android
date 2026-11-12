package io.astrbot.android.core

import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class LoopbackRfbClientTest {
    private fun auth(socket: Socket, accepted: Boolean = true): Pair<DataInputStream, DataOutputStream> {
        socket.soTimeout = 4000
        val input = DataInputStream(socket.getInputStream()); val out = DataOutputStream(socket.getOutputStream())
        out.write("RFB 003.008\n".toByteArray()); out.flush(); input.readFully(ByteArray(12))
        out.write(byteArrayOf(1,2)); out.flush(); assertEquals(2, input.readUnsignedByte())
        val challenge = ByteArray(16) { it.toByte() }; out.write(challenge); out.flush()
        val actual = ByteArray(16); input.readFully(actual)
        val key = "Fixture7".toByteArray().map { Integer.reverse(it.toInt() and 255).ushr(24).toByte() }.toByteArray()
        val cipher = Cipher.getInstance("DES/ECB/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key,"DES"))
        assertArrayEquals(cipher.doFinal(challenge), actual)
        out.writeInt(if(accepted) 0 else 1); out.flush()
        return input to out
    }
    private fun init(input: DataInputStream, out: DataOutputStream) {
        assertEquals(1,input.readUnsignedByte())
        out.writeShort(2);out.writeShort(1);out.write(ByteArray(16));out.writeInt(0);out.flush()
        input.readFully(ByteArray(20));assertEquals(2,input.readUnsignedByte());input.readByte()
        val n=input.readUnsignedShort();repeat(n){input.readInt()}
    }
    private fun request(input:DataInputStream):Int {
        assertEquals(3,input.readUnsignedByte());val incremental=input.readUnsignedByte();input.readFully(ByteArray(8));return incremental
    }
    private fun frame(out:DataOutputStream,w:Int=2,h:Int=1,resize:Boolean=false){
        out.write(byteArrayOf(0,0));out.writeShort(1);out.writeShort(0);out.writeShort(0);out.writeShort(w);out.writeShort(h);out.writeInt(if(resize)-223 else 0)
        if(!resize)repeat(w*h){out.write(byteArrayOf(50,40,220.toByte(),0))};out.flush()
    }
    @Test fun authenticatedPixelsPointerResizeAndIdempotentStart() {
        ServerSocket(0,8,InetAddress.getLoopbackAddress()).use { server ->
            val frames=LinkedBlockingQueue<Triple<IntArray,Int,Int>>()
            val workers=Executors.newSingleThreadExecutor()
            val received=workers.submit<Boolean> {
                server.accept().use { sock ->
                    val (input,out)=auth(sock);init(input,out);assertEquals(0,request(input));frame(out)
                    // Input can precede or follow the next framebuffer request.
                    var clicked=false;var sawRequest=false
                    while(!clicked){when(input.readUnsignedByte()){
                        3 -> {input.readFully(ByteArray(9));sawRequest=true}
                        5 -> {assertEquals(1,input.readUnsignedByte());assertEquals(1,input.readUnsignedShort());assertEquals(0,input.readUnsignedShort());clicked=true}
                        else -> error("Unexpected client message")
                    }}
                    assertEquals(5,input.readUnsignedByte());assertEquals(0,input.readUnsignedByte());input.readFully(ByteArray(4))
                    // Drain an already sent incremental request before advertising resize.
                    if(!sawRequest) request(input)
                    frame(out,3,2,true)
                    assertEquals(0,request(input));frame(out,3,2)
                    Thread.sleep(100);true
                }
            }
            val client=LoopbackRfbClient({"Fixture7"},{},{p,w,h->frames.offer(Triple(p.copyOf(),w,h))},server.localPort)
            try {
                client.start();client.start()
                val first=requireNotNull(frames.poll(4,TimeUnit.SECONDS));assertEquals(0xffdc2832.toInt(),first.first[0])
                client.click(1,0)
                val resized=requireNotNull(frames.poll(4,TimeUnit.SECONDS));assertEquals(3,resized.second);assertEquals(2,resized.third)
                assertTrue(received.get(4,TimeUnit.SECONDS))
            } finally {client.close();workers.shutdownNow()}
        }
    }
    @Test fun rejectedAuthenticationNeverPublishesFrameOrRetries() {
        ServerSocket(0,8,InetAddress.getLoopbackAddress()).use { server ->
            val statuses=LinkedBlockingQueue<RfbStatus>();val frames=AtomicInteger();val workers=Executors.newSingleThreadExecutor()
            val served=workers.submit {server.accept().use {auth(it,false)}}
            val client=LoopbackRfbClient({"Fixture7"},{statuses.offer(it)},{_,_,_->frames.incrementAndGet()},server.localPort)
            try {client.start();assertEquals(RfbStatus.AUTH_REJECTED,statuses.poll(3,TimeUnit.SECONDS));served.get(3,TimeUnit.SECONDS)
                server.soTimeout=1000
                assertTrue(runCatching {server.accept()}.exceptionOrNull() is SocketTimeoutException)
                assertEquals(0,frames.get())
            } finally {client.close();workers.shutdownNow()}
        }
    }
}
