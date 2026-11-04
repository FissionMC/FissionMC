package io.papermc.paper.plugin.util;


import io.papermc.paper.plugin.entrypoint.LaunchEntryPointHandler;
import io.papermc.paper.plugin.provider.source.ProviderSource;
import org.slf4j.Logger;

public final class EntrypointUtil {

    private static final Logger LOGGER = net.fissionmc.craft.util.Loggers.classLogger();

    public static <I, C> void registerProvidersFromSource(ProviderSource<I, C> source, I contextInput) {
        try {
            C context = source.prepareContext(contextInput);
            source.registerProviders(LaunchEntryPointHandler.INSTANCE, context);
        } catch (Throwable e) {
            LOGGER.error(e.getMessage(), e);
        }
    }
}
