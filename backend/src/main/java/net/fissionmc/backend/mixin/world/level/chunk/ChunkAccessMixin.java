package net.fissionmc.backend.mixin.world.level.chunk;

import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/chunk/ChunkAccess.java.patch */
@Mixin(ChunkAccess.class)
public abstract class ChunkAccessMixin {
    @Shadow @Final protected LevelChunkSection[] sections;

    // CraftBukkit start - SPIGOT-6814: move to ChunkAccess to account for 1.17 to 1.18 chunk upgrading.
    private static final org.bukkit.craftbukkit.persistence.CraftPersistentDataTypeRegistry DATA_TYPE_REGISTRY = new org.bukkit.craftbukkit.persistence.CraftPersistentDataTypeRegistry();
    private org.bukkit.craftbukkit.persistence.DirtyCraftPersistentDataContainer persistentDataContainer = new org.bukkit.craftbukkit.persistence.DirtyCraftPersistentDataContainer(DATA_TYPE_REGISTRY);
    // CraftBukkit end
    // TODO(behaviour): coordinateKey/locX/locZ cached fields (Paper - cache coordinate key; perf only, not declared)

    @org.spongepowered.asm.mixin.injection.Inject(method = "tryMarkSaved", at = @org.spongepowered.asm.mixin.injection.At(value = "FIELD", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;unsaved:Z", opcode = org.objectweb.asm.Opcodes.PUTFIELD))
    private void paper$pdcSaved(final org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir) {
        this.persistentDataContainer.dirty(false); // CraftBukkit - SPIGOT-6814: chunk was saved, pdc is no longer dirty
    }

    @com.llamalad7.mixinextras.injector.ModifyReturnValue(method = "isUnsaved", at = @org.spongepowered.asm.mixin.injection.At("RETURN"))
    private boolean paper$pdcUnsaved(final boolean unsaved) {
        // FissionMC: LevelChunk's override (isUnsaved() && !mustNotSave) is folded in here, LevelChunk does not override isUnsaved
        if ((Object) this instanceof net.minecraft.world.level.chunk.LevelChunk levelChunk && levelChunk.mustNotSave) {
            return false; // CraftBukkit
        }
        return unsaved || this.persistentDataContainer.dirty(); // CraftBukkit - SPIGOT-6814: chunk is unsaved if pdc was mutated
    }

    // CraftBukkit start
    public void setNoiseBiome(int quartX, int quartY, int quartZ, Holder<Biome> biome) {
        final ChunkAccess self = (ChunkAccess) (Object) this;
        try {
            int quartMinY = QuartPos.fromBlock(self.getMinY());
            int quartMaxY = quartMinY + QuartPos.fromBlock(self.getHeight()) - 1;
            int clampedQuartY = Mth.clamp(quartY, quartMinY, quartMaxY);
            int sectionIndex = self.getSectionIndex(QuartPos.toBlock(clampedQuartY));
            this.sections[sectionIndex].setNoiseBiome(quartX & 3, clampedQuartY & 3, quartZ & 3, biome);
        } catch (Throwable throwable) {
            CrashReport report = CrashReport.forThrowable(throwable, "Setting biome");
            CrashReportCategory category = report.addCategory("Biome being set");
            category.setDetail("Location", () -> CrashReportCategory.formatLocation(self, quartX, quartY, quartZ));
            throw new ReportedException(report);
        }
    }
    // CraftBukkit end
}
