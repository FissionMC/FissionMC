package net.fissionmc.backend.mixin.world.level;

import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Paper: sources/net/minecraft/world/level/LevelSettings.java.patch */
@Mixin(LevelSettings.class)
public abstract class LevelSettingsMixin {
    @Shadow @Final private String levelName;
    @Shadow @Final private GameType gameType;
    @Shadow @Final private LevelSettings.DifficultySettings difficultySettings;
    @Shadow @Final private boolean allowCommands;
    @Shadow @Final private WorldDataConfiguration dataConfiguration;

    // Paper start
    public LevelSettings withLevelName(String name) {
        return new LevelSettings(name, this.gameType, this.difficultySettings, this.allowCommands, this.dataConfiguration);
    }

    public LevelSettings withHardcore(boolean hardcore) {
        return new LevelSettings(this.levelName, this.gameType, new LevelSettings.DifficultySettings(this.difficultySettings.difficulty(), hardcore, this.difficultySettings.locked()), this.allowCommands, this.dataConfiguration);
    }
    // Paper end
}
