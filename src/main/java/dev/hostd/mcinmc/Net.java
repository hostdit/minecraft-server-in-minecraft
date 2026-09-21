package dev.hostd.mcinmc;

import io.netty.buffer.ByteBuf;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class Net {

    private Net() {
    }

    public static int readVarInt(InputStream in) throws IOException {
        int result = 0;
        int shift = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("closed");
            }
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift >= 35) {
                throw new IOException("varint too long");
            }
        }
    }

    public static void writeVarInt(OutputStream out, int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) {
                out.write(value);
                return;
            }
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    public static int readVarInt(ByteBuf buf) {
        int result = 0;
        int shift = 0;
        while (true) {
            byte b = buf.readByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return result;
            }
            shift += 7;
            if (shift >= 35) {
                throw new IllegalStateException("varint too long");
            }
        }
    }

    public static int varIntSize(int value) {
        int n = 1;
        while ((value & ~0x7F) != 0) {
            n++;
            value >>>= 7;
        }
        return n;
    }
}
