package dev.hostd.mcinmc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import net.minecraft.network.NetworkState;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;

public final class Conn {

    static final int MAX_FRAME = 1 << 23;

    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private volatile int threshold = -1;

    public Conn(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    public void enableCompression(int threshold) {
        this.threshold = threshold;
    }

    ByteBuf readFrame() throws IOException {
        int packetLength = Net.readVarInt(in);
        if (packetLength < 0 || packetLength > MAX_FRAME) {
            throw new IOException("bad frame length " + packetLength);
        }
        byte[] body = in.readNBytes(packetLength);
        if (body.length < packetLength) {
            throw new IOException("short frame");
        }
        if (threshold < 0) {
            return Unpooled.wrappedBuffer(body);
        }
        ByteBuf wrapped = Unpooled.wrappedBuffer(body);
        int dataLength = Net.readVarInt(wrapped);
        if (dataLength == 0) {
            return wrapped.slice();
        }
        byte[] compressed = new byte[wrapped.readableBytes()];
        wrapped.readBytes(compressed);
        return Unpooled.wrappedBuffer(inflate(compressed, dataLength));
    }

    public void sendBytes(byte[] bytes) throws IOException {
        synchronized (writeLock) {
            if (threshold < 0) {
                Net.writeVarInt(out, bytes.length);
                out.write(bytes);
                out.flush();
                return;
            }
            if (bytes.length < threshold) {
                Net.writeVarInt(out, bytes.length + 1);
                out.write(0);
                out.write(bytes);
            } else {
                byte[] compressed = deflate(bytes);
                Net.writeVarInt(out, Net.varIntSize(bytes.length) + compressed.length);
                Net.writeVarInt(out, bytes.length);
                out.write(compressed);
            }
            out.flush();
        }
    }

    public static <T extends PacketListener> byte[] encode(NetworkState<T> state,
                                                           Packet<? super T> packet) {
        ByteBuf buf = Unpooled.buffer();
        try {
            state.codec().encode(buf, packet);
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return bytes;
        } finally {
            buf.release();
        }
    }

    public <T extends PacketListener> void send(NetworkState<T> state,
                                                Packet<? super T> packet) throws IOException {
        sendBytes(encode(state, packet));
    }

    public <T extends PacketListener> Packet<? super T> receive(NetworkState<T> state) throws IOException {
        ByteBuf buf = readFrame();
        try {
            return state.codec().decode(buf);
        } finally {
            buf.release();
        }
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        ByteArrayOutputStream sink = new ByteArrayOutputStream(Math.max(64, data.length / 2));
        byte[] chunk = new byte[8192];
        while (!deflater.finished()) {
            int n = deflater.deflate(chunk);
            sink.write(chunk, 0, n);
        }
        deflater.end();
        return sink.toByteArray();
    }

    private static byte[] inflate(byte[] data, int expected) throws IOException {
        if (expected < 0 || expected > MAX_FRAME) {
            throw new IOException("bad inflate size " + expected);
        }
        Inflater inflater = new Inflater();
        inflater.setInput(data);
        byte[] result = new byte[expected];
        try {
            int n = inflater.inflate(result);
            if (n != expected) {
                throw new IOException("inflate gave " + n + " expected " + expected);
            }
        } catch (DataFormatException e) {
            throw new IOException(e);
        } finally {
            inflater.end();
        }
        return result;
    }
}
