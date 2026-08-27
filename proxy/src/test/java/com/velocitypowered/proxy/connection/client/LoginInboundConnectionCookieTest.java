/*
 * Copyright (C) 2026 Telesphoreo
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.velocitypowered.proxy.connection.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.protocol.packet.ClientboundCookieRequestPacket;
import com.velocitypowered.proxy.protocol.packet.ServerboundCookieResponsePacket;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

class LoginInboundConnectionCookieTest {

  private static final Key KEY = Key.key("shibboleth", "return");

  @Test
  void refusesToExposeCookieOnPlaintextLoginTransport() {
    LoginInboundConnection connection = connection();
    assertThrows(IllegalStateException.class, () -> connection.requestCookie(KEY));
  }

  @Test
  void completesOnlyTheMatchingEncryptedCookieRequest() {
    InitialInboundConnection delegate = mock(InitialInboundConnection.class);
    MinecraftConnection minecraft = mock(MinecraftConnection.class);
    when(delegate.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_20_5);
    when(delegate.getConnection()).thenReturn(minecraft);
    LoginInboundConnection connection = new LoginInboundConnection(delegate);
    connection.encrypted();

    var result = connection.requestCookie(KEY);
    verify(minecraft).write(isA(ClientboundCookieRequestPacket.class));
    connection.handleCookieResponse(new ServerboundCookieResponsePacket(KEY, new byte[] {1, 2, 3}));
    assertArrayEquals(new byte[] {1, 2, 3}, result.join());
  }

  @Test
  void cancellationRemovesTheOutstandingRequest() {
    LoginInboundConnection connection = connection();
    connection.encrypted();
    connection.requestCookie(KEY).cancel(false);
    connection.requestCookie(KEY);
  }

  @Test
  void authHandlerRoutesLoginCookieResponseBeforeProfileCreation() {
    LoginInboundConnection connection = connection();
    connection.encrypted();
    var result = connection.requestCookie(KEY);
    AuthSessionHandler handler = new AuthSessionHandler(mock(VelocityServer.class), connection,
        GameProfile.forOfflinePlayer("CookieTester"), false, null);

    handler.handle(new ServerboundCookieResponsePacket(KEY, new byte[] {4, 5, 6}));

    assertArrayEquals(new byte[] {4, 5, 6}, result.join());
  }

  @Test
  void authHandlerRejectsUnsolicitedCookieBeforeProfileCreation() {
    InitialInboundConnection delegate = mock(InitialInboundConnection.class);
    when(delegate.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_20_5);
    when(delegate.getConnection()).thenReturn(mock(MinecraftConnection.class));
    LoginInboundConnection connection = new LoginInboundConnection(delegate);
    AuthSessionHandler handler = new AuthSessionHandler(mock(VelocityServer.class), connection,
        GameProfile.forOfflinePlayer("CookieTester"), false, null);

    handler.handle(new ServerboundCookieResponsePacket(KEY, new byte[] {7}));

    verify(delegate).disconnect(any());
  }

  private static LoginInboundConnection connection() {
    InitialInboundConnection delegate = mock(InitialInboundConnection.class);
    when(delegate.getProtocolVersion()).thenReturn(ProtocolVersion.MINECRAFT_1_20_5);
    when(delegate.getConnection()).thenReturn(mock(MinecraftConnection.class));
    return new LoginInboundConnection(delegate);
  }
}
