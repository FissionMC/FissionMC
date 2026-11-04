package net.fissionmc.backend.mixin.world.level.block.entity.trialspawner;

import net.minecraft.core.Holder;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawner;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerConfig;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/block/entity/trialspawner/TrialSpawner.java.patch (FullConfig record) */
@Mixin(TrialSpawner.FullConfig.class)
public abstract class TrialSpawner_FullConfigMixin {
    @Shadow @Final private Holder<TrialSpawnerConfig> normal;
    @Shadow @Final private Holder<TrialSpawnerConfig> ominous;
    @Shadow @Final private int targetCooldownLength;
    @Shadow @Final private int requiredPlayerRange;

    // Paper start - trial spawner API - withers
    public TrialSpawner.FullConfig overrideTargetCooldownLength(final int targetCooldownLength) {
        return new TrialSpawner.FullConfig(
            this.normal,
            this.ominous,
            targetCooldownLength,
            this.requiredPlayerRange
        );
    }

    public TrialSpawner.FullConfig overrideRequiredPlayerRange(final int requiredPlayerRange) {
        return new TrialSpawner.FullConfig(
            this.normal,
            this.ominous,
            this.targetCooldownLength,
            requiredPlayerRange
        );
    }

    public TrialSpawner.FullConfig overrideConfigs(final Holder<TrialSpawnerConfig> normal, final Holder<TrialSpawnerConfig> ominous) {
        return new TrialSpawner.FullConfig(
            normal,
            ominous,
            this.targetCooldownLength,
            this.requiredPlayerRange
        );
    }
    // Paper end - trial spawner API - withers
}
