package su.plo.voice.server.socket;

import net.minecraft.server.level.ServerPlayer;
import su.plo.voice.common.packets.udp.*;
import su.plo.voice.server.PlayerManager;
import su.plo.voice.server.VoiceServer;
import su.plo.voice.server.network.ServerNetworkHandler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class SocketServerUDPQueue extends Thread {
    public LinkedBlockingQueue<PacketUDP> queue = new LinkedBlockingQueue<>();

    public void run() {
        while (!this.isInterrupted()) {
            try {
                this.keepAlive();

                PacketUDP message = queue.poll(10, TimeUnit.MILLISECONDS);
                if (message == null || message.getPacket() == null || System.currentTimeMillis() - message.getTimestamp() > message.getTTL()) {
                    continue;
                }

                if (message.getPacket() instanceof AuthPacket packet) {
                    AtomicReference<ServerPlayer> player = new AtomicReference<>();

                    ServerNetworkHandler.playerToken.forEach((uuid, t) -> {
                        if (t.toString().equals(packet.getToken())) {
                            player.set(VoiceServer.getServer().getPlayerList().getPlayer(uuid));
                        }
                    });

                    if (player.get() != null) {
                        ServerNetworkHandler.playerToken.remove(player.get().getUUID());
                        String type = VoiceServer.getNetwork().isVanillaPlayer(player.get()) ? "forge" : "fabric";
                        SocketClientUDP sock = new SocketClientUDP(player.get().getUUID(), type, message.getAddress(), message.getPort());

                        if (!SocketServerUDP.clients.containsKey(player.get().getUUID())) {
                            SocketServerUDP.clients.put(player.get().getUUID(), sock);
                        }

                        SocketServerUDP.sendTo(PacketUDP.write(new AuthPacketAck()), sock);
                    }
                }

                SocketClientUDP client = SocketServerUDP.getSender(message);
                if (client == null) { // not authorized
                    continue;
                }
                ServerPlayer player = client.getPlayer();
                if (player == null) {
                    continue;
                }

                if (message.getPacket() instanceof PingPacket) {
                    client.setKeepAlive(System.currentTimeMillis());
                    continue;
                }

                // server mute
                if (VoiceServer.getPlayerManager().isMuted(player.getUUID())) {
                    continue;
                }

                if (message.getPacket() instanceof VoiceClientPacket packet) {
                    if (!VoiceServer.getPlayerManager().hasPermission(player.getUUID(), "voice.speak")) {
                        continue;
                    }

                    if (packet.getDistance() > VoiceServer.getServerConfig().getMaxDistance()) {
                        if (VoiceServer.getPlayerManager().hasPermission(player.getUUID(), "voice.priority") &&
                                packet.getDistance() <= VoiceServer.getServerConfig().getMaxPriorityDistance()) {
                            VoiceServerPacket serverPacket = new VoiceServerPacket(packet.getData(),
                                    player.getUUID(),
                                    packet.getSequenceNumber(),
                                    packet.getDistance()
                            );
                            SocketServerUDP.sendToNearbyPlayers(serverPacket, player, packet.getDistance());
                        }
                    } else {
                        VoiceServerPacket serverPacket = new VoiceServerPacket(packet.getData(),
                                player.getUUID(),
                                packet.getSequenceNumber(),
                                packet.getDistance()
                        );
                        SocketServerUDP.sendToNearbyPlayers(serverPacket, player, packet.getDistance());
                    }
                } else if (message.getPacket() instanceof VoiceEndClientPacket packet) {
                    VoiceEndServerPacket serverPacket = new VoiceEndServerPacket(player.getUUID());
                    SocketServerUDP.sendToNearbyPlayers(serverPacket, player, packet.getDistance());
                }
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException ignored) {
                break;
            }
        }
    }

    private void keepAlive() throws IOException {
        long timestamp = System.currentTimeMillis();
        PingPacket keepAlive = new PingPacket();
        List<UUID> connectionsToDrop = new ArrayList<>(SocketServerUDP.clients.size());
        for (SocketClientUDP connection : SocketServerUDP.clients.values()) {
            if (timestamp - connection.getKeepAlive() >= 15000L) {
                connectionsToDrop.add(connection.getPlayerUUID());
            } else if (timestamp - connection.getSentKeepAlive() >= 1000L) {
                connection.setSentKeepAlive(timestamp);
                SocketServerUDP.sendTo(PacketUDP.write(keepAlive), connection);
            }
        }
        for (UUID uuid : connectionsToDrop) {
            ServerPlayer player = PlayerManager.getByUUID(uuid);
            ServerNetworkHandler.disconnectClient(uuid);

            if (VoiceServer.isLogsEnabled()) {
                VoiceServer.LOGGER.info("{} UDP timed out", player.getGameProfile().getName());
                VoiceServer.LOGGER.info("{} sent reconnect packet", player.getGameProfile().getName());
            }

            ServerNetworkHandler.reconnectClient(player);
        }
    }
}
