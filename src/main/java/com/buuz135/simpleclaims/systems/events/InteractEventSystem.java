package com.buuz135.simpleclaims.systems.events;

import com.buuz135.simpleclaims.Main;
import com.buuz135.simpleclaims.claim.ClaimManager;
import com.buuz135.simpleclaims.claim.party.PartyInfo;
import com.buuz135.simpleclaims.claim.party.PartyOverrides;
import com.buuz135.simpleclaims.systems.tick.CraftingUiQuantitiesSystem;
import com.buuz135.simpleclaims.util.BenchChestCache;
import com.buuz135.simpleclaims.util.WindowExtraResourcesState;
import com.hypixel.hytale.builtin.crafting.state.BenchState;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.RootDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.protocol.ExtraResources;
import com.hypixel.hytale.protocol.ItemQuantity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.windows.MaterialExtraResourcesSection;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.meta.BlockState;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;


public class InteractEventSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {

    public InteractEventSystem() {
        super(UseBlockEvent.Pre.class);
    }

    @Override
    public void handle(final int index, @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk, @Nonnull final Store<EntityStore> store, @Nonnull final CommandBuffer<EntityStore> commandBuffer, @Nonnull final UseBlockEvent.Pre event) {
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        Predicate<PartyInfo> defaultInteract = PartyInfo::isBlockInteractEnabled;
        String permission = PartyOverrides.PARTY_PROTECTION_INTERACT;
        var blockName = event.getBlockType().getId().toLowerCase(Locale.ROOT);
        var ignored = false;

        for (String blocksThatIgnoreInteractRestriction : Main.CONFIG.get().getBlocksThatIgnoreInteractRestrictions()) {
            if (blockName.contains(blocksThatIgnoreInteractRestriction.toLowerCase(Locale.ROOT))) ignored = true;
        }

        // Also check BlockState type name against the ignore list.
        // This allows mods with custom block states (like BarterChest) to be whitelisted
        // by their state type name without affecting vanilla blocks of the same base type.
        if (!ignored) {
            ignored = isBlockStateTypeIgnored(player.getWorld(), event.getTargetBlock().getX(),
                    event.getTargetBlock().getY(), event.getTargetBlock().getZ());
        }

        if (blockName.contains("chest")) {
            defaultInteract = PartyInfo::isChestInteractEnabled;
            permission = PartyOverrides.PARTY_PROTECTION_INTERACT_CHEST;
        } else if (blockName.contains("bench") && !blockName.contains("furniture")) {
            defaultInteract = PartyInfo::isBenchInteractEnabled;
            permission = PartyOverrides.PARTY_PROTECTION_INTERACT_BENCH;

            if (playerRef != null && !ClaimManager.getInstance().isAllowedToInteract(playerRef.getUuid(), player.getWorld().getName(), event.getTargetBlock().getX(), event.getTargetBlock().getZ(), defaultInteract, permission)) {
                event.setCancelled(true);
                playerRef.getPacketHandler().getChannel().attr(WindowExtraResourcesState.NEXT_OPEN_EXTRA).set(null);
                return;
            }

            PacketHandler ph = playerRef.getPacketHandler();
            var ch = ph.getChannel();
            World world = player.getWorld();

            var targetBlock = event.getTargetBlock();
            ExtraResources next = buildExtraResourcesForBench(world, playerRef, targetBlock.getX(), targetBlock.getY(), targetBlock.getZ());
            if (next != null) {
                ch.attr(WindowExtraResourcesState.NEXT_OPEN_EXTRA).set(next);
                WindowExtraResourcesState.getOrCreateBenchSet(ch).add(0); // provisional id
            }
            return;
        } else if (blockName.contains("door")) {
            defaultInteract = PartyInfo::isDoorInteractEnabled;
            permission = PartyOverrides.PARTY_PROTECTION_INTERACT_DOOR;
        } else if (blockName.contains("chair") || blockName.contains("stool") || (blockName.contains("bench") && blockName.contains("furniture"))) {
            defaultInteract = PartyInfo::isChairInteractEnabled;
            permission = PartyOverrides.PARTY_PROTECTION_INTERACT_CHAIR;
        } else if (blockName.contains("portal") || blockName.contains("teleporter")) {
            defaultInteract = PartyInfo::isPortalInteractEnabled;
            permission = PartyOverrides.PARTY_PROTECTION_INTERACT_PORTAL;
        }
        if (!ignored && (playerRef != null && !ClaimManager.getInstance().isAllowedToInteract(playerRef.getUuid(), player.getWorld().getName(), event.getTargetBlock().getX(), event.getTargetBlock().getZ(), defaultInteract, permission))) {
            event.setCancelled(true);
        }
    }

    private static boolean isBlockStateTypeIgnored(World world, int x, int y, int z) {
        try {
            BlockState state = world.getState(x, y, z, false);
            if (state == null) return false;

            String stateTypeName = state.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            for (String ignorePattern : Main.CONFIG.get().getBlocksThatIgnoreInteractRestrictions()) {
                if (stateTypeName.contains(ignorePattern.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        } catch (Throwable t) {
            // If we can't read the state, don't bypass protection
        }
        return false;
    }

    private static ExtraResources buildExtraResourcesForBench(World world, PlayerRef playerRef, int bx, int by, int bz) {
        var chests = BenchChestCache.getAllowedChests(world, playerRef, bx, by, bz);

        BenchState benchState = null;
        var st = world.getState(bx, by, bz, true);
        if (st instanceof BenchState bs) benchState = bs;

        if (benchState == null) return null;

        ItemQuantity[] counts = CraftingUiQuantitiesSystem.computeCounts(benchState, chests);
        ItemContainer uiContainer = CraftingUiQuantitiesSystem.buildUiContainer(chests);

        MaterialExtraResourcesSection section = new MaterialExtraResourcesSection();
        section.setItemContainer(uiContainer);
        section.setExtraMaterials(counts);
        section.setValid(true);
        return section.toPacket();
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @NonNullDecl
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return Collections.singleton(RootDependency.first());
    }
}
