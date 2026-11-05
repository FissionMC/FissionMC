package net.fissionmc.backend.mixin.world.scores;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.serialization.MapCodec;
import java.util.Optional;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.scores.TeamColor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Paper: sources/net/minecraft/world/scores/PlayerTeam.java.patch (better fail on decode) */
@Mixin(targets = "net.minecraft.world.scores.PlayerTeam$Packed")
public abstract class PlayerTeam_PackedMixin {

    @WrapOperation(method = "lambda$static$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/StringRepresentable$EnumCodec;optionalFieldOf(Ljava/lang/String;)Lcom/mojang/serialization/MapCodec;"))
    private static MapCodec<Optional<TeamColor>> paper$lenientTeamColor(StringRepresentable.EnumCodec<TeamColor> codec, String name, Operation<MapCodec<Optional<TeamColor>>> original) {
        return io.papermc.paper.util.PaperCodecs.lenientCodec(name, codec); // Paper - better fail on decode
    }
}
