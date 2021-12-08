package com.worldql.client.listeners.player;

import com.google.flatbuffers.FlexBuffers;
import com.google.flatbuffers.FlexBuffersBuilder;
import com.worldql.client.WorldQLClient;
import com.worldql.client.listeners.utils.ItemTools;
import com.worldql.client.serialization.Codec;
import com.worldql.client.serialization.Instruction;
import com.worldql.client.serialization.Message;
import com.worldql.client.serialization.Replication;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;
import zmq.ZMQ;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.UUID;

public class PlayerDeathListener implements Listener {
    public static final ItemStack[] EMPTY_DROPS = new ItemStack[0];

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        String killerUuid = null;
        if (e.getEntity().getKiller() != null) {
            killerUuid = e.getEntity().getKiller().getUniqueId().toString();
        }

        ItemStack[] drops = EMPTY_DROPS;
        if (killerUuid != null) {
            drops = e.getDrops().toArray(new ItemStack[0]);
        }

        // Send death event to other servers
        FlexBuffersBuilder b = Codec.getFlexBuilder();
        int pmap = b.startMap();
        b.putString("uuid", e.getEntity().getUniqueId().toString());
        if (killerUuid != null) b.putString("killer", killerUuid);
        b.putString("message", e.getDeathMessage());
        b.putBlob("drops", ItemTools.serializeItemStack(drops));
        b.putInt("xp", e.getDroppedExp());
        b.putFloat("x", e.getEntity().getLocation().getX());
        b.putFloat("y", e.getEntity().getLocation().getY());
        b.putFloat("z", e.getEntity().getLocation().getZ());
        b.putString("world", e.getEntity().getWorld().getName());
        b.endMap(null, pmap);
        ByteBuffer bb = b.finish();

        Message message = new Message(
                Instruction.GlobalMessage,
                WorldQLClient.worldQLClientId,
                "@global",
                Replication.IncludingSelf,
                null,
                null,
                null,
                "MinecraftPlayerDeath",
                bb
        );

        WorldQLClient.getPluginInstance().getPushSocket().send(message.encode(), ZMQ.ZMQ_DONTWAIT);

        // Stop drops from dropping if killed by a player
        if (killerUuid != null) {
            e.getDrops().clear();
        }
    }

    public static void handleIncomingDeath(@NotNull Message message, boolean isSelf) {
        FlexBuffers.Map map = FlexBuffers.getRoot(message.flex()).asMap();
        Server server = WorldQLClient.getPluginInstance().getServer();

        // Broadcast death message
        if (!isSelf) {
            String deathMsg = map.get("message").asString();
            server.broadcastMessage(deathMsg);
        }

        UUID killerUuid = null;
        if (!map.get("killer").isNull()) {
            killerUuid = UUID.fromString(map.get("killer").asString());
        }

        if (killerUuid != null) {
            // For lambda
            UUID finalKillerUuid = killerUuid;
            boolean killerPresent = server.getOnlinePlayers().stream().anyMatch(p -> p.getUniqueId().equals(finalKillerUuid));

            // Only drop if the killer is on the current server
            if (killerPresent) {
                double x = map.get("x").asFloat();
                double y = map.get("y").asFloat();
                double z = map.get("z").asFloat();
                String worldName = map.get("world").asString();

                World world = Bukkit.getWorld(worldName);
                Location location = new Location(world, x, y, z);

                new BukkitRunnable() {
                    @Override
                    public void run() {
                        ItemStack[] drops = new ItemStack[0];
                        try {
                            drops = ItemTools.deserializeItemStack(map.get("drops").asBlob().data());
                        } catch (IOException e) {
                            e.printStackTrace();
                        }

                        for (ItemStack item : drops) {
                            world.dropItem(location, item);
                        }

                        ExperienceOrb orb = world.spawn(location, ExperienceOrb.class);
                        orb.setExperience(map.get("xp").asInt());
                    }
                }.runTask(WorldQLClient.pluginInstance);
            }
        }
    }
}