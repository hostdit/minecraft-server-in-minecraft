package dev.hostd.mcinmc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.flow.FlowControlHandler;
import io.netty.util.ReferenceCountUtil;

import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketAddress;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.DisconnectionInfo;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.NetworkState;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.handler.NetworkStateTransitions;
import net.minecraft.network.handler.SizePrepender;
import net.minecraft.network.handler.SplitterHandler;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.listener.TickablePacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.text.Text;

public final class GuestConnection extends ClientConnection {

    private static final byte[] CLOSE = new byte[0];
    private static final long MAX_QUEUED_BYTES = 64L << 20;

    private final SocketAddress remote;
    private final OutputStream out;
    private final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();
    private final LinkedBlockingQueue<byte[]> outbox = new LinkedBlockingQueue<>();
    private final AtomicLong queuedBytes = new AtomicLong();
    private final EmbeddedChannel channel;
    private final Thread loop;
    private volatile boolean closed;

    public GuestConnection(SocketAddress remote, OutputStream out) {
        super(NetworkSide.SERVERBOUND);
        this.remote = remote;
        this.out = out;
        this.channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) {
                ChannelPipeline p = ch.pipeline();
                p.addLast("socket", new SocketTap());
                p.addLast("splitter", new SplitterHandler(null));
                p.addLast(new FlowControlHandler());
                p.addLast("inbound_config", new NetworkStateTransitions.InboundConfigurer());
                p.addLast("prepender", new SizePrepender());
                p.addLast("outbound_config", new NetworkStateTransitions.OutboundConfigurer());
                GuestConnection.this.addFlowControlHandler(p);
            }
        });
        channel.closeFuture().addListener(future -> {
            closed = true;
            outbox.add(CLOSE);
        });
        loop = new Thread(this::runLoop, "mcinmc-guest-loop");
        loop.setDaemon(true);
        loop.start();
        Thread writer = new Thread(this::runWriter, "mcinmc-guest-writer");
        writer.setDaemon(true);
        writer.start();
    }

    private final class SocketTap extends ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof ByteBuf buf) {
                byte[] bytes = new byte[buf.readableBytes()];
                buf.readBytes(bytes);
                enqueue(bytes);
            }
            ReferenceCountUtil.release(msg);
            promise.setSuccess();
        }
    }

    private void enqueue(byte[] bytes) {
        if (queuedBytes.addAndGet(bytes.length) > MAX_QUEUED_BYTES) {
            disconnect(Text.literal("Your connection fell too far behind"));
            return;
        }
        outbox.add(bytes);
    }

    private void runWriter() {
        try {
            while (true) {
                byte[] bytes = outbox.take();
                if (bytes == CLOSE) {
                    break;
                }
                out.write(bytes);
                queuedBytes.addAndGet(-bytes.length);
                if (outbox.isEmpty()) {
                    out.flush();
                }
            }
            out.flush();
        } catch (IOException | InterruptedException e) {
            disconnect(Text.translatable("disconnect.endOfStream"));
        } finally {
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void runLoop() {
        while (!closed || !tasks.isEmpty()) {
            Runnable task;
            try {
                task = tasks.poll(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                return;
            }
            if (task == null) {
                continue;
            }
            try {
                task.run();
            } catch (Throwable t) {
                System.out.println("[mcinmc] guest " + remote + ": " + t);
            }
        }
    }

    private void execute(Runnable task) {
        if (Thread.currentThread() == loop) {
            task.run();
            return;
        }
        if (!closed) {
            tasks.add(task);
        }
    }

    public void feed(byte[] bytes) {
        execute(() -> {
            try {
                channel.writeInbound(Unpooled.wrappedBuffer(bytes));
            } catch (Throwable t) {
                System.out.println("[mcinmc] guest " + remote + " sent a bad packet: " + t);
                super.disconnect(new DisconnectionInfo(Text.literal("Bad packet: " + t.getMessage())));
            }
        });
    }

    public void close() {
        disconnect(Text.translatable("disconnect.endOfStream"));
    }

    @Override
    public void send(Packet<?> packet, PacketCallbacks callbacks, boolean flush) {
        execute(() -> super.send(packet, callbacks, flush));
    }

    @Override
    public void submit(Consumer<ClientConnection> task) {
        execute(() -> super.submit(task));
    }

    @Override
    public void flush() {
    }

    @Override
    public void tick() {
        if (getPacketListener() instanceof TickablePacketListener tickable) {
            tickable.tick();
        }
    }

    @Override
    public <T extends PacketListener> void transitionInbound(NetworkState<T> state, T packetListener) {
        execute(() -> super.transitionInbound(state, packetListener));
    }

    @Override
    public void transitionOutbound(NetworkState<?> newState) {
        execute(() -> super.transitionOutbound(newState));
    }

    @Override
    public void setCompressionThreshold(int compressionThreshold, boolean rejectsBadPackets) {
        execute(() -> super.setCompressionThreshold(compressionThreshold, rejectsBadPackets));
    }

    @Override
    public void tryDisableAutoRead() {
        execute(super::tryDisableAutoRead);
    }

    @Override
    public void disconnect(DisconnectionInfo disconnectionInfo) {
        execute(() -> super.disconnect(disconnectionInfo));
    }

    @Override
    public boolean isOpen() {
        return !closed && super.isOpen();
    }

    @Override
    public boolean isLocal() {
        return false;
    }

    @Override
    public SocketAddress getAddress() {
        return remote;
    }

    @Override
    public String getAddressAsString(boolean logIps) {
        return logIps ? remote.toString() : "IP hidden";
    }
}
