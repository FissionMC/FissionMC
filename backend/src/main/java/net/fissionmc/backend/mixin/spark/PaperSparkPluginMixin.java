package net.fissionmc.backend.mixin.spark;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.Map;
import me.lucko.spark.paper.common.platform.MetadataProvider;
import net.fissionmc.backend.node.MasterInfo;
import org.spongepowered.asm.mixin.Mixin;

/**
 * spark: {@code SparkPlugin.createExtraMetadataProvider()} is a default method that Paper's {@code PaperSparkPlugin} does not override.
 * Mixin adds the override; spark calls it for every profiler / health / heap report ({@code SparkMetadata.gather}) and writes the map as
 * "extra platform metadata".
 */
@Mixin(targets = "me.lucko.spark.paper.PaperSparkPlugin")
public abstract class PaperSparkPluginMixin {
    public MetadataProvider createExtraMetadataProvider() {
        return () -> {
            Map<String, JsonElement> out = new LinkedHashMap<>();
            MasterInfo.INSTANCE.metadata().forEach((k, v) -> out.put(k, new JsonPrimitive(v)));
            return out;
        };
    }
}
