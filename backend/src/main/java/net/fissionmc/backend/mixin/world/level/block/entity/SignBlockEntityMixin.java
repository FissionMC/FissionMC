package net.fissionmc.backend.mixin.world.level.block.entity;

import net.minecraft.util.Mth;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.function.UnaryOperator;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.SignText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Paper: sources/net/minecraft/world/level/block/entity/SignBlockEntity.java.patch
 * SignChangeEvent: the update function handed to updateText is wrapped (Paper's updateMessages(..., player, signBlockEntity, slot) hunk).
 * TODO(behaviour): getSlotPlayerIsFacing(Player) delegates here (same result); PlayerSignCommandPreprocessEvent + sign command source; filterText hunks; far-away update packet.
 */
@Mixin(SignBlockEntity.class)
public abstract class SignBlockEntityMixin {
    // Paper start - More Sign Block API
    public SignTextSlot getSlotPlayerIsFacing(final double x, final double z) {
        // Paper end - More Sign Block API
        final SignBlockEntity self = (SignBlockEntity) (Object) this;
        if (self.getBlockState().getBlock() instanceof SignBlock sign) {
            Vec3 signPositionOffset = sign.getSignHitboxCenterPosition(self.getBlockState());
            double xd = x - (self.getBlockPos().getX() + signPositionOffset.x); // Paper - More Sign Block API
            double zd = z - (self.getBlockPos().getZ() + signPositionOffset.z); // Paper - More Sign Block API
            float signYRot = sign.getYRotationDegrees(self.getBlockState());
            float playerYRot = (float)(Mth.atan2(zd, xd) * 180.0F / (float)Math.PI) - 90.0F;
            return Mth.degreesDifferenceAbs(signYRot, playerYRot) <= 90.0F ? SignTextSlot.FRONT : SignTextSlot.BACK;
        }
        return SignTextSlot.FRONT;
    }

    // Paper start - call SignChangeEvent
    @ModifyArg(method = "updateSignText", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/entity/SignBlockEntity;updateText(Ljava/util/function/UnaryOperator;Lnet/minecraft/world/level/block/entity/SignTextSlot;)Z"), index = 0)
    private UnaryOperator<SignText> fission$callSignChangeEvent(final UnaryOperator<SignText> update, @Local(argsOnly = true) final Player player, @Local(argsOnly = true) final SignTextSlot slot) {
        final SignBlockEntity self = (SignBlockEntity) (Object) this;
        return originalText -> {
            final SignText updated = update.apply(originalText);
            final boolean shouldFilter = player.isTextFilteringEnabled();
            final org.bukkit.entity.Player apiPlayer = ((ServerPlayer) player).getBukkitEntity();
            final List<net.kyori.adventure.text.Component> componentLines = new java.util.ArrayList<>();
            for (int i = 0; i < SignText.LINES; ++i) {
                componentLines.add(io.papermc.paper.adventure.PaperAdventure.asAdventure(updated.getMessages(shouldFilter).get(i)));
            }
            final org.bukkit.event.block.SignChangeEvent event = new org.bukkit.event.block.SignChangeEvent(
                org.bukkit.craftbukkit.block.CraftBlock.at(self.getLevel(), self.getBlockPos()),
                apiPlayer,
                new java.util.ArrayList<>(componentLines),
                org.bukkit.craftbukkit.block.sign.CraftSignSide.fromVanilla(slot)
            );
            if (!event.callEvent()) {
                return originalText;
            }
            final net.minecraft.network.chat.Component[] components = org.bukkit.craftbukkit.block.CraftSign.sanitizeLines(event.lines());
            final SignText.Mutable result = updated.asMutable();
            for (int i = 0; i < components.length; i++) {
                if (!java.util.Objects.equals(componentLines.get(i), event.line(i))) {
                    result.setLine(i, components[i]);
                }
            }
            return result.asImmutable();
        };
    }
    // Paper end - call SignChangeEvent
}
