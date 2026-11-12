package fi.alavesa.superbvehicles.compat;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import org.bukkit.Server;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Serves the pack zip on the Minecraft port itself (the only NMS-touching code, kept here): the game server's
 * listening socket is a netty channel; a handler at the front of every accepted connection reads the first bytes -
 * a Minecraft handshake starts with a packet length, an HTTP download with {@code GET } or {@code HEAD }. Downloads
 * get the zip streamed back and never reach the game's handlers; everything else passes through untouched. Found by
 * reflection on {@code MinecraftServer.getConnection().channels} (field matched by its generic type), so it survives
 * mapping changes. Does not work behind a Velocity/Bungee proxy (they forward only the game protocol).
 */
public final class SamePortHttp {
    private static final String PARENT = "superbvehicles_http_accept", CHILD = "superbvehicles_http";
    private static final int CHUNK = 256 * 1024;
    private final Server server;
    private final String pathMarker;
    private final Supplier<File> zip;
    private final List<Channel> parents = new ArrayList<>();
    private volatile long served;

    public SamePortHttp(Server server, String pathMarker, Supplier<File> zip) { this.server = server; this.pathMarker = pathMarker; this.zip = zip; }

    /** Hook the listening channel(s). Returns null on success, else why not. */
    @SuppressWarnings("unchecked")
    public String install() {
        try {
            Object mc = server.getClass().getMethod("getServer").invoke(server);
            Object conn = mc.getClass().getMethod("getConnection").invoke(mc);
            if (conn == null) return "the server has no network listener yet";
            Field f = null;
            for (Class<?> c = conn.getClass(); c != null && f == null; c = c.getSuperclass())
                for (Field x : c.getDeclaredFields()) if (List.class.isAssignableFrom(x.getType()) && x.getGenericType().getTypeName().contains("ChannelFuture")) { f = x; break; }
            if (f == null) return "ServerConnectionListener has no channel list (server version not supported)";
            f.setAccessible(true);
            List<ChannelFuture> list = (List<ChannelFuture>) f.get(conn);
            int n = 0;
            synchronized (list) {
                for (ChannelFuture cf : list) {
                    Channel ch = cf.channel();
                    if (ch == null || !ch.isOpen()) continue;
                    if (ch.pipeline().get(PARENT) == null) ch.pipeline().addFirst(PARENT, new Acceptor());
                    parents.add(ch);
                    n++;
                }
            }
            return n == 0 ? "no open listening channel found" : null;
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    public void uninstall() {
        for (Channel ch : parents) try { if (ch.pipeline().get(PARENT) != null) ch.pipeline().remove(PARENT); } catch (Throwable ignored) { }
        parents.clear();
    }

    public long served() { return served; }

    @ChannelHandler.Sharable
    private final class Acceptor extends ChannelInboundHandlerAdapter {
        @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Channel child) {
                try { if (child.pipeline().get(CHILD) == null) child.pipeline().addFirst(CHILD, new Sniffer()); } catch (Throwable ignored) { }
            }
            ctx.fireChannelRead(msg);
        }
    }

    private final class Sniffer extends ChannelInboundHandlerAdapter {
        private ByteBuf head;
        private boolean http, answered;

        @Override public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof ByteBuf buf)) { ctx.fireChannelRead(msg); return; }
            if (!http) {
                if (head == null && buf.readableBytes() >= 4) {
                    if (!looksLikeHttp(buf)) { ctx.pipeline().remove(this); ctx.fireChannelRead(buf); return; }
                    http = true;
                } else if (head == null) {
                    head = Unpooled.buffer(); head.writeBytes(buf); buf.release();
                    if (head.readableBytes() >= 4) { if (!looksLikeHttp(head)) { ByteBuf h = head; head = null; ctx.pipeline().remove(this); ctx.fireChannelRead(h); return; } http = true; }
                    else return;
                }
            }
            if (head == null) head = Unpooled.buffer();
            if (buf.isReadable()) head.writeBytes(buf);
            buf.release();
            if (answered) return;
            String text = head.toString(StandardCharsets.US_ASCII);
            int end = text.indexOf("\r\n\r\n");
            if (end < 0) { if (head.readableBytes() > 16384) ctx.close(); return; }
            answered = true;
            String line = text.substring(0, text.indexOf("\r\n"));
            String[] parts = line.split(" ");
            String method = parts.length > 0 ? parts[0] : "", path = parts.length > 1 ? parts[1] : "";
            head.release(); head = null;
            ChannelPipeline p = ctx.pipeline();
            for (String name : new ArrayList<>(p.names())) if (!name.equals(CHILD) && p.get(name) != null) try { p.remove(name); } catch (Throwable ignored) { }
            File f = zip.get();
            if (!path.contains("/" + pathMarker) || !path.endsWith(".zip") || f == null || !f.isFile()) { reply(ctx, "404 Not Found", 0, null, method.equals("HEAD")); return; }
            reply(ctx, "200 OK", f.length(), f, method.equals("HEAD"));
        }

        private boolean looksLikeHttp(ByteBuf b) {
            int i = b.readerIndex();
            return (b.getByte(i) == 'G' && b.getByte(i + 1) == 'E' && b.getByte(i + 2) == 'T' && b.getByte(i + 3) == ' ')
                || (b.getByte(i) == 'H' && b.getByte(i + 1) == 'E' && b.getByte(i + 2) == 'A' && b.getByte(i + 3) == 'D');
        }

        private void reply(ChannelHandlerContext ctx, String status, long length, File body, boolean headOnly) {
            String headers = "HTTP/1.1 " + status + "\r\nContent-Type: application/zip\r\nContent-Length: " + length + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n";
            ChannelFuture hf = ctx.writeAndFlush(Unpooled.copiedBuffer(headers, StandardCharsets.US_ASCII));
            if (body == null || headOnly || length == 0) { hf.addListener(x -> ctx.close()); return; }
            try {
                RandomAccessFile raf = new RandomAccessFile(body, "r");
                FileChannel fc = raf.getChannel();
                served++;
                hf.addListener(x -> writeChunk(ctx, raf, fc));
            } catch (Exception e) { ctx.close(); }
        }

        private void writeChunk(ChannelHandlerContext ctx, RandomAccessFile raf, FileChannel fc) {
            try {
                if (!ctx.channel().isActive()) { raf.close(); return; }
                ByteBuffer bb = ByteBuffer.allocate(CHUNK);
                int n = fc.read(bb);
                if (n <= 0) { raf.close(); ctx.close(); return; }
                bb.flip();
                ctx.writeAndFlush(Unpooled.wrappedBuffer(bb)).addListener(x -> { if (x.isSuccess()) writeChunk(ctx, raf, fc); else { raf.close(); ctx.close(); } });
            } catch (Exception e) { try { raf.close(); } catch (Exception ignored) { } ctx.close(); }
        }

        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { if (http) ctx.close(); else ctx.fireExceptionCaught(cause); }
        @Override public void handlerRemoved(ChannelHandlerContext ctx) { if (head != null) { head.release(); head = null; } }
    }
}
