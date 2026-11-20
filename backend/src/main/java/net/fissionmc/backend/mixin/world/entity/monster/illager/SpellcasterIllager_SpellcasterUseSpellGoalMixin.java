package net.fissionmc.backend.mixin.world.entity.monster.illager;

import net.minecraft.world.entity.monster.illager.SpellcasterIllager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Paper: sources/net/minecraft/world/entity/monster/illager/SpellcasterIllager.java.patch (EntitySpellCastEvent before the spell is performed) */
@Mixin(targets = "net.minecraft.world.entity.monster.illager.SpellcasterIllager$SpellcasterUseSpellGoal")
public abstract class SpellcasterIllager_SpellcasterUseSpellGoalMixin {
    @Shadow @Final SpellcasterIllager this$0;

    @Shadow protected abstract SpellcasterIllager.IllagerSpell getSpell();

    @Inject(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/illager/SpellcasterIllager$SpellcasterUseSpellGoal;performSpellCasting()V"), cancellable = true)
    private void paper$spellCastEvent(final CallbackInfo ci) {
        // CraftBukkit start
        if (!org.bukkit.craftbukkit.event.CraftEventFactory.handleEntitySpellCastEvent(this.this$0, this.getSpell())) {
            ci.cancel();
        }
        // CraftBukkit end
    }
}
