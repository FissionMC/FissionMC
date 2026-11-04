package net.fissionmc.backend.mixin.world.entity.animal.goat;

import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.animal.goat.Goat;
import org.spongepowered.asm.mixin.Mixin;

/** Paper: sources/net/minecraft/world/entity/animal/goat/Goat.java.patch */
@Mixin(Goat.class)
public abstract class GoatMixin {
    // Paper start - Goat ram API
    public void ram(net.minecraft.world.entity.LivingEntity entity) {
        Brain<Goat> brain = ((Goat) (Object) this).getBrain();
        brain.setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.RAM_TARGET, entity.position());
        brain.eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.RAM_COOLDOWN_TICKS);
        brain.eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.BREED_TARGET);
        brain.eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.TEMPTING_PLAYER);
        brain.setActiveActivityIfPossible(net.minecraft.world.entity.schedule.Activity.RAM);
    }
    // Paper end - Goat ram API
}
