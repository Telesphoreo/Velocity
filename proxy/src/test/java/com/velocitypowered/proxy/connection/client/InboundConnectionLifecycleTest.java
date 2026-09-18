package com.velocitypowered.proxy.connection.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.network.HandshakeIntent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.ConnectionTypes;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.protocol.packet.HandshakePacket;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;

class InboundConnectionLifecycleTest {
  @Test
  void notifiesWhenLoginClosesBeforeAnyConnectedPlayerExists() {
    EmbeddedChannel channel = new EmbeddedChannel(DefaultChannelId.newInstance());
    try {
      MinecraftConnection transport = mock(MinecraftConnection.class);
      when(transport.getChannel()).thenReturn(channel);
      InitialInboundConnection initial = new InitialInboundConnection(transport, "example.test", new HandshakePacket());
      LoginInboundConnection login = new LoginInboundConnection(initial);
      var closed = login.getDisconnectFuture().toCompletableFuture();
      assertFalse(closed.isDone());
      channel.close().syncUninterruptibly();
      assertTrue(closed.isDone());
      assertTrue(login.getDisconnectFuture().toCompletableFuture().isDone());
    } finally {
      channel.finishAndReleaseAll();
    }
  }

  @Test
  void preservesTransportIdentityAcrossWrappersAndNotifiesBeforePlayerCreation() {
    EmbeddedChannel channel = new EmbeddedChannel(DefaultChannelId.newInstance());
    EmbeddedChannel replacementChannel = new EmbeddedChannel(DefaultChannelId.newInstance());
    try {
      InetSocketAddress address = new InetSocketAddress("192.0.2.1", 41234);
      MinecraftConnection transport = mock(MinecraftConnection.class);
      when(transport.getChannel()).thenReturn(channel);
      when(transport.getRemoteAddress()).thenReturn(address);
      when(transport.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_20_5);
      when(transport.getType()).thenReturn(ConnectionTypes.UNDETERMINED);
      when(transport.eventLoop()).thenReturn(channel.eventLoop());
      InitialInboundConnection initial = new InitialInboundConnection(transport, "example.test", new HandshakePacket());
      LoginInboundConnection login = new LoginInboundConnection(initial);
      ConnectedPlayer player = new ConnectedPlayer(mock(VelocityServer.class),
          GameProfile.forOfflinePlayer("Example"), transport, null, null, false, HandshakeIntent.LOGIN, null);
      assertEquals(initial.getConnectionId(), login.getConnectionId());
      assertEquals(login.getConnectionId(), player.getConnectionId());
      var loginClosed = login.getDisconnectFuture().toCompletableFuture();
      final var playerClosed = player.getDisconnectFuture().toCompletableFuture();
      assertFalse(loginClosed.isDone());
      channel.close().syncUninterruptibly();
      assertTrue(loginClosed.isDone());
      assertTrue(playerClosed.isDone());
      assertTrue(login.getDisconnectFuture().toCompletableFuture().isDone());

      when(transport.getChannel()).thenReturn(replacementChannel);
      InitialInboundConnection replacement = new InitialInboundConnection(transport, "example.test", new HandshakePacket());
      assertEquals(initial.getRemoteAddress(), replacement.getRemoteAddress());
      assertNotEquals(channel.id().asLongText(), replacement.getConnectionId());
      assertFalse(replacement.getDisconnectFuture().toCompletableFuture().isDone());
    } finally {
      channel.finishAndReleaseAll();
      replacementChannel.finishAndReleaseAll();
    }
  }
}
