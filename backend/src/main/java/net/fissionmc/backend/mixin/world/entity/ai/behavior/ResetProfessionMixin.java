package net.fissionmc.backend.mixin.world.entity.ai.behavior;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.ResetProfession;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Paper: sources/net/minecraft/world/entity/ai/behavior/ResetProfession.java.patch (VillagerCareerChangeEvent LOSING_JOB; replaces the setVillagerData tail of the lambda) */
@Mixin(ResetProfession.class)
public abstract class ResetProfessionMixin {
    @Inject(method = "lambda$create$2", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/npc/villager/VillagerData;withProfession(Lnet/minecraft/core/HolderGetter$Provider;Lnet/minecraft/resources/ResourceKey;)Lnet/minecraft/world/entity/npc/villager/VillagerData;"), cancellable = true)
    private static void paper$careerChange(final ServerLevel level, final Villager body, final long gameTime, final CallbackInfoReturnable<Boolean> cir) {
        // CraftBukkit start
        org.bukkit.event.entity.VillagerCareerChangeEvent event = org.bukkit.craftbukkit.event.CraftEventFactory.callVillagerCareerChangeEvent(body, org.bukkit.craftbukkit.entity.CraftVillager.CraftProfession.minecraftHolderToBukkit(level.registryAccess().getOrThrow(VillagerProfession.NONE)), org.bukkit.event.entity.VillagerCareerChangeEvent.ChangeReason.LOSING_JOB);
        if (event.isCancelled()) {
            cir.setReturnValue(false);
            return;
        }

        body.setVillagerData(body.getVillagerData().withProfession(org.bukkit.craftbukkit.entity.CraftVillager.CraftProfession.bukkitToMinecraftHolder(event.getProfession())));
        // CraftBukkit end
        body.refreshBrain(level);
        cir.setReturnValue(true);
    }
}
