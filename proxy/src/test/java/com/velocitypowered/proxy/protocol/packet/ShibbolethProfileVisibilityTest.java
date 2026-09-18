/*
 * Copyright (C) 2026 Velocity Contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.velocitypowered.proxy.protocol.packet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.util.GameProfile;
import com.velocitypowered.proxy.connection.PlayerDataForwarding;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import com.velocitypowered.proxy.protocol.packet.chat.ComponentHolder;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ShibbolethProfileVisibilityTest {

  private static final ProtocolUtils.Direction CLIENTBOUND = ProtocolUtils.Direction.CLIENTBOUND;

  @ParameterizedTest
  @EnumSource(value = ProtocolVersion.class, names = {
      "MINECRAFT_1_19", "MINECRAFT_1_20_5", "MINECRAFT_26_1", "MINECRAFT_26_2"
  })
  void loginSuccessHidesInternalPropertiesWithoutChangingTheServerProfile(ProtocolVersion version) {
    GameProfile profile = profile("Owner");
    ServerLoginSuccessPacket packet = new ServerLoginSuccessPacket();
    packet.setUuid(profile.getId());
    packet.setUsername(profile.getName());
    packet.setProperties(profile.getProperties());
    packet.setSessionId(UUID.randomUUID());
    ByteBuf bytes = Unpooled.buffer();
    try {
      packet.encode(bytes, CLIENTBOUND, version);
      assertNoSecrets(bytes);
      ServerLoginSuccessPacket received = new ServerLoginSuccessPacket();
      received.decode(bytes, CLIENTBOUND, version);
      assertEquals(profile.getId(), received.getUuid());
      assertEquals(profile.getName(), received.getUsername());
      assertPublicProperties(received.getProperties());
      assertEquals(8, profile.getProperties().size());
      assertFalse(bytes.isReadable());
    } finally {
      bytes.release();
    }
  }

  @ParameterizedTest
  @EnumSource(value = ProtocolVersion.class, names = {
      "MINECRAFT_1_19_3", "MINECRAFT_1_21_4", "MINECRAFT_26_1", "MINECRAFT_26_2"
  })
  void playerInfoHidesEveryPlayersTokensAndPreservesOtherFields(ProtocolVersion version) {
    var first = entry("Owner", version, 11);
    var second = entry("Other", version, 29);
    var actions = EnumSet.of(UpsertPlayerInfoPacket.Action.ADD_PLAYER,
        UpsertPlayerInfoPacket.Action.INITIALIZE_CHAT, UpsertPlayerInfoPacket.Action.UPDATE_GAME_MODE,
        UpsertPlayerInfoPacket.Action.UPDATE_LISTED, UpsertPlayerInfoPacket.Action.UPDATE_LATENCY,
        UpsertPlayerInfoPacket.Action.UPDATE_DISPLAY_NAME);
    if (version.noLessThan(ProtocolVersion.MINECRAFT_1_21_4)) {
      actions.add(UpsertPlayerInfoPacket.Action.UPDATE_LIST_ORDER);
      actions.add(UpsertPlayerInfoPacket.Action.UPDATE_HAT);
    }
    var packet = new UpsertPlayerInfoPacket(actions, List.of(first, second));
    ByteBuf bytes = Unpooled.buffer();
    try {
      packet.encode(bytes, CLIENTBOUND, version);
      assertNoSecrets(bytes);
      var received = new UpsertPlayerInfoPacket();
      received.decode(bytes, CLIENTBOUND, version);
      assertEquals(actions, received.getActions());
      assertEquals(2, received.getEntries().size());
      for (int index = 0; index < 2; index++) {
        var original = packet.getEntries().get(index);
        var actual = received.getEntries().get(index);
        assertEquals(original.getProfileId(), actual.getProfileId());
        assertEquals(original.getProfile().getName(), actual.getProfile().getName());
        assertPublicProperties(actual.getProfile().getProperties());
        assertEquals(original.getLatency(), actual.getLatency());
        assertEquals(original.getGameMode(), actual.getGameMode());
        assertEquals(original.isListed(), actual.isListed());
        assertEquals(original.getDisplayName().getComponent(), actual.getDisplayName().getComponent());
        if (version.noLessThan(ProtocolVersion.MINECRAFT_1_21_4)) {
          assertEquals(original.getListOrder(), actual.getListOrder());
          assertEquals(original.isShowHat(), actual.isShowHat());
        }
        assertEquals(8, original.getProfile().getProperties().size());
      }
      assertFalse(bytes.isReadable());
    } finally {
      bytes.release();
    }
  }

  @ParameterizedTest
  @EnumSource(value = ProtocolVersion.class, names = {"MINECRAFT_1_8", "MINECRAFT_1_19"})
  void legacyPlayerInfoHidesInternalPropertiesAndPreservesSkins(ProtocolVersion version) {
    GameProfile profile = profile("Owner");
    var item = new LegacyPlayerListItemPacket.Item(profile.getId())
        .setName(profile.getName()).setProperties(profile.getProperties())
        .setGameMode(1).setLatency(42).setDisplayName(Component.text("Owner label"));
    var packet = new LegacyPlayerListItemPacket(LegacyPlayerListItemPacket.ADD_PLAYER, List.of(item));
    ByteBuf bytes = Unpooled.buffer();
    try {
      packet.encode(bytes, CLIENTBOUND, version);
      assertNoSecrets(bytes);
      var received = new LegacyPlayerListItemPacket();
      received.decode(bytes, CLIENTBOUND, version);
      var actual = received.getItems().getFirst();
      assertEquals(item.getUuid(), actual.getUuid());
      assertEquals(item.getName(), actual.getName());
      assertPublicProperties(actual.getProperties());
      assertEquals(item.getGameMode(), actual.getGameMode());
      assertEquals(item.getLatency(), actual.getLatency());
      assertEquals(item.getDisplayName(), actual.getDisplayName());
      assertEquals(8, profile.getProperties().size());
      assertFalse(bytes.isReadable());
    } finally {
      bytes.release();
    }
  }

  @Test
  void absentTexturesProduceAnEmptyPropertyListAndUnsignedTexturesRemainUnsigned() {
    var properties = List.of(new GameProfile.Property("federation.trace", "trace-28b6d0", ""),
        new GameProfile.Property("custom", "private", ""));
    ByteBuf bytes = Unpooled.buffer();
    try {
      ProtocolUtils.writeClientProperties(bytes, properties);
      assertEquals(List.of(), ProtocolUtils.readProperties(bytes));
      assertFalse(bytes.isReadable());
      ProtocolUtils.writeClientProperties(bytes,
          List.of(new GameProfile.Property("textures", "unsigned-skin", "")));
      assertEquals(List.of(List.of("textures", "unsigned-skin", "")),
          propertyValues(ProtocolUtils.readProperties(bytes)));
      assertFalse(bytes.isReadable());
      assertEquals("private", properties.get(1).getValue());
    } finally {
      bytes.release();
    }
  }

  @Test
  void clientSerializationLeavesInternalPropertiesInAuthenticatedBackendForwarding() throws Exception {
    GameProfile profile = profile("Owner");
    byte[] key = new byte[32];
    ByteBuf before = PlayerDataForwarding.createForwardingData(key, "192.0.2.1",
        ProtocolVersion.MINECRAFT_26_1, profile, null, 1);
    ByteBuf client = Unpooled.buffer();
    ByteBuf after = null;
    try {
      var entry = new UpsertPlayerInfoPacket.Entry(profile.getId());
      entry.setProfile(profile);
      new UpsertPlayerInfoPacket(EnumSet.of(UpsertPlayerInfoPacket.Action.ADD_PLAYER), List.of(entry))
          .encode(client, CLIENTBOUND, ProtocolVersion.MINECRAFT_26_1);
      assertNoSecrets(client);
      after = PlayerDataForwarding.createForwardingData(key, "192.0.2.1",
          ProtocolVersion.MINECRAFT_26_1, profile, null, 1);
      assertEquals(before, after);
      byte[] signature = new byte[32];
      after.readBytes(signature);
      byte[] payload = new byte[after.readableBytes()];
      after.getBytes(after.readerIndex(), payload);
      Mac hmac = Mac.getInstance("HmacSHA256");
      hmac.init(new SecretKeySpec(key, "HmacSHA256"));
      assertArrayEquals(hmac.doFinal(payload), signature);
      assertEquals(1, ProtocolUtils.readVarInt(after));
      assertEquals("192.0.2.1", ProtocolUtils.readString(after));
      assertEquals(profile.getId(), ProtocolUtils.readUuid(after));
      assertEquals(profile.getName(), ProtocolUtils.readString(after));
      assertEquals(propertyValues(profile.getProperties()), propertyValues(ProtocolUtils.readProperties(after)));
      assertFalse(after.isReadable());
    } finally {
      before.release();
      client.release();
      if (after != null) {
        after.release();
      }
    }
  }

  private static UpsertPlayerInfoPacket.Entry entry(String name, ProtocolVersion version, int latency) {
    GameProfile profile = profile(name);
    var entry = new UpsertPlayerInfoPacket.Entry(profile.getId());
    entry.setProfile(profile);
    entry.setGameMode(1);
    entry.setListed(true);
    entry.setLatency(latency);
    entry.setDisplayName(new ComponentHolder(version, Component.text(name + " label")));
    entry.setListOrder(latency);
    entry.setShowHat(true);
    return entry;
  }

  private static GameProfile profile(String name) {
    return new GameProfile(UUID.randomUUID(), name, List.of(
        new GameProfile.Property("textures", "skin-value", "skin-signature"),
        new GameProfile.Property("shibboleth_session", "secret-" + name, ""),
        new GameProfile.Property("shibboleth_interface", "dialog", ""),
        new GameProfile.Property("shibboleth_auth_test", "discord", ""),
        new GameProfile.Property("public_property", "public-value", ""),
        new GameProfile.Property("federation.trace", "trace-7c91e4", ""),
        new GameProfile.Property("Textures", "wrong-case", ""),
        new GameProfile.Property("textures_extra", "wrong-prefix", "")));
  }

  private static void assertNoSecrets(ByteBuf bytes) {
    String encoded = bytes.toString(StandardCharsets.ISO_8859_1);
    assertFalse(encoded.contains("shibboleth_"));
    assertFalse(encoded.contains("secret-"));
    assertFalse(encoded.contains("public-value"));
    assertFalse(encoded.contains("trace-7c91e4"));
    assertFalse(encoded.contains("wrong-case"));
    assertFalse(encoded.contains("wrong-prefix"));
  }

  private static void assertPublicProperties(List<GameProfile.Property> properties) {
    assertEquals(List.of(List.of("textures", "skin-value", "skin-signature")),
        propertyValues(properties));
  }

  private static List<List<String>> propertyValues(List<GameProfile.Property> properties) {
    return properties.stream().map(property -> List.of(property.getName(), property.getValue(),
        property.getSignature())).toList();
  }
}
