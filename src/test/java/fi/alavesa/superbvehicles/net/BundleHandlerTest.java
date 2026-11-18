package fi.alavesa.superbvehicles.net;

import fi.alavesa.superbvehicles.net.VehicleNetworkAndSleepManager.BundleHandler;
import fi.alavesa.superbvehicles.net.VehicleNetworkAndSleepManager.PacketAccess;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-connection bundler on a real netty pipeline, with stand-in packets. */
class BundleHandlerTest {
    record Move(int id) { }
    record Bundle(List<Object> packets) { }
    static final PacketAccess ACCESS = new PacketAccess() {
        @Override public int entityId(Object p) { return p instanceof Move m ? m.id : -1; }
        @Override public Object bundle(List<Object> packets) { return new Bundle(packets); }
    };

    static EmbeddedChannel channel(BundleHandler[] out) {
        EmbeddedChannel ch = new EmbeddedChannel();
        out[0] = new BundleHandler(ACCESS, id -> id >= 100 && id < 200, ch);
        ch.pipeline().addLast(VehicleNetworkAndSleepManager.HANDLER, out[0]);
        return ch;
    }

    @Test void vehiclePacketsOfATickLeaveAsOneBundleInOrder() {
        BundleHandler[] h = new BundleHandler[1];
        EmbeddedChannel ch = channel(h);
        ChannelFuture a = ch.write(new Move(100));
        ch.writeAndFlush("chat");                          // not a vehicle packet: straight through
        ChannelFuture b = ch.write(new Move(150));
        ch.writeAndFlush(new Move(7));                     // another entity: straight through
        ch.write(new Move(101));
        assertEquals("chat", ch.readOutbound());
        assertEquals(new Move(7), ch.readOutbound());
        assertNull(ch.readOutbound(), "vehicle packets wait for the end of the tick");
        assertFalse(a.isDone());
        h[0].endTick();
        ch.runPendingTasks();
        Object out = ch.readOutbound();
        assertEquals(new Bundle(List.of(new Move(100), new Move(150), new Move(101))), out);
        assertNull(ch.readOutbound());
        assertTrue(a.isSuccess() && b.isSuccess(), "the senders' promises complete with the bundle");
    }

    @Test void aSinglePacketIsNotWrappedAndAnEmptyTickSendsNothing() {
        BundleHandler[] h = new BundleHandler[1];
        EmbeddedChannel ch = channel(h);
        h[0].endTick(); ch.runPendingTasks();
        assertNull(ch.readOutbound());
        ch.write(new Move(120));
        h[0].endTick(); ch.runPendingTasks();
        assertEquals(new Move(120), ch.readOutbound());
    }

    @Test void removingTheHandlerReleasesWhatItHolds() {
        BundleHandler[] h = new BundleHandler[1];
        EmbeddedChannel ch = channel(h);
        ch.write(new Move(100)); ch.write(new Move(101));
        h[0].remove(); ch.runPendingTasks(); ch.flush();
        assertEquals(new Move(100), ch.readOutbound());
        assertEquals(new Move(101), ch.readOutbound());
        assertNull(ch.pipeline().get(VehicleNetworkAndSleepManager.HANDLER));
    }

    @Test void aBrokenBundleFactoryFallsBackToPlainPackets() {
        EmbeddedChannel ch = new EmbeddedChannel();
        BundleHandler h = new BundleHandler(new PacketAccess() {
            @Override public int entityId(Object p) { return p instanceof Move m ? m.id : -1; }
            @Override public Object bundle(List<Object> packets) { throw new IllegalStateException("no bundle class"); }
        }, id -> true, ch);
        ch.pipeline().addLast(VehicleNetworkAndSleepManager.HANDLER, h);
        ch.write(new Move(1)); ch.write(new Move(2));
        h.endTick(); ch.runPendingTasks();
        assertEquals(new Move(1), ch.readOutbound());
        assertEquals(new Move(2), ch.readOutbound());
    }
}
