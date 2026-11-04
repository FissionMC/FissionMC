package net.fissionmc.backend.mixin.world.entity.ai.attributes;

import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Members Paper adds to {@link AttributeMap} (paper-server/patches/sources/net/minecraft/world/entity/ai/attributes/AttributeMap.java.patch).
 */
@Mixin(AttributeMap.class)
public abstract class AttributeMapMixin {
    @Shadow @Final private Map<Holder<Attribute>, AttributeInstance> attributes;

    // Paper - start - living entity allow attribute registration
    public void registerAttribute(Holder<Attribute> attributeBase) {
        AttributeInstance attributeModifiable = new AttributeInstance(attributeBase, AttributeInstance::getAttribute);
        attributes.put(attributeBase, attributeModifiable);
    }
    // Paper - end - living entity allow attribute registration
}
