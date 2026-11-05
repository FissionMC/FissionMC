package net.fissionmc.backend.datafix;

import com.mojang.datafixers.DSL;
import com.mojang.datafixers.DataFix;
import com.mojang.datafixers.TypeRewriteRule;
import com.mojang.datafixers.schemas.Schema;
import net.minecraft.util.datafix.LegacyComponentDataFixUtils;
import net.minecraft.util.datafix.fixes.References;

/**
 * Paper: sources/net/minecraft/util/datafix/DataFixers.java.patch. CraftBukkit adds this as an anonymous DataFix
 * after schema 1458; mixins cannot add anonymous classes, so it is a named class here (added by DataFixersMixin).
 */
public final class PlayerCustomNameFix extends DataFix {
    public PlayerCustomNameFix(final Schema outputSchema) {
        super(outputSchema, false);
    }

    // CraftBukkit start
    // API allows setting player custom names, so we need to convert them.
    // This does *not* handle upgrades in any other version, but generally those shouldn't need conversion.
    @Override
    protected TypeRewriteRule makeRule() {
        return this.fixTypeEverywhereTyped("Player CustomName", this.getInputSchema().getType(References.PLAYER), typed -> {
            return typed.update(DSL.remainderFinder(), dynamic -> {
                final String customName = dynamic.get("CustomName").asString("");
                if (customName.isEmpty()) {
                    return dynamic.remove("CustomName");
                }
                return dynamic.set("CustomName", LegacyComponentDataFixUtils.createPlainTextComponent(dynamic.getOps(), customName));
            });
        });
    }
    // CraftBukkit end
}
