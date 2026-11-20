package net.fissionmc.backend.mixin.world.entity.ai.behavior;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.AssignProfessionFromJobSite;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/entity/ai/behavior/AssignProfessionFromJobSite.java.patch (VillagerCareerChangeEvent EMPLOYED; replaced body of the profession lambda) */
@Mixin(AssignProfessionFromJobSite.class)
public abstract class AssignProfessionFromJobSiteMixin {
    @Inject(method = "lambda$create$6", at = @At("HEAD"), cancellable = true)
    private static void paper$careerChange(final Villager body, final ServerLevel level, final Holder.Reference<VillagerProfession> profession, final CallbackInfo ci) {
        ci.cancel();
        // CraftBukkit start - Fire VillagerCareerChangeEvent where Villager gets employed
        org.bukkit.event.entity.VillagerCareerChangeEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callVillagerCareerChangeEvent(body, org.bukkit.craftbukkit.entity.CraftVillager.CraftProfession.minecraftHolderToBukkit(profession), org.bukkit.event.entity.VillagerCareerChangeEvent.ChangeReason.EMPLOYED);
        if (event.isCancelled()) {
            return;
        }

        body.setVillagerData(body.getVillagerData().withProfession(org.bukkit.craftbukkit.entity.CraftVillager.CraftProfession.bukkitToMinecraftHolder(event.getProfession())));
        // CraftBukkit end
        body.refreshBrain(level);
    }
}
