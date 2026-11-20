package net.fissionmc.craft;

import io.papermc.paper.ServerBuildInfo;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import net.kyori.adventure.key.Key;

/** Loaded by paper-api through ServiceLoader. Reports FissionMC, but claims Paper brand compatibility. */
public final class FissionBuildInfo implements ServerBuildInfo {
    public static final Key BRAND_FISSION_ID = Key.key("fissionmc", "fission");
    static final String MINECRAFT = "26.3";
    static final String VERSION = "0.1.0";

    @Override public Key brandId() { return BRAND_FISSION_ID; }
    @Override public boolean isBrandCompatible(Key brandId) { return brandId.equals(BRAND_FISSION_ID) || brandId.equals(BRAND_PAPER_ID); }
    @Override public String brandName() { return "FissionMC"; }
    @Override public String minecraftVersionId() { return MINECRAFT; }
    @Override public String minecraftVersionName() { return MINECRAFT; }
    @Override public OptionalInt buildNumber() { return OptionalInt.empty(); }
    @Override public Instant buildTime() { return Instant.EPOCH; }
    @Override public Optional<String> gitBranch() { return Optional.empty(); }
    @Override public Optional<String> gitCommit() { return Optional.empty(); }
    /**
     * Same shape as Paper's: {@code <mc>-<build>}. CraftServer#getVersion appends " (MC: <mc>)" itself, so the string must not contain
     * that part (plugins parse {@code Bukkit.getVersion()} with a "(MC: x)" pattern: it has to occur exactly once).
     */
    @Override public String asString(StringRepresentation representation) {
        return simple();
    }

    static String simple() {
        return MINECRAFT + "-DEV-fission-" + VERSION;
    }
}
