package io.papermc.paper.plugin.provider.source;


import io.papermc.paper.plugin.entrypoint.EntrypointHandler;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.slf4j.Logger;

/**
 * Loads all plugin providers in the given directory.
 */
public class DirectoryProviderSource implements ProviderSource<Path, List<Path>> {

    public static final DirectoryProviderSource INSTANCE = new DirectoryProviderSource(true);
    public static final DirectoryProviderSource INSTANCE_NO_CREATE = new DirectoryProviderSource(false);
    private static final FileProviderSource FILE_PROVIDER_SOURCE = new FileProviderSource("Directory '%s'"::formatted);
    private static final Logger LOGGER = net.fissionmc.craft.util.Loggers.classLogger();

    private final boolean createDirectory;

    public DirectoryProviderSource(final boolean createDirectory) {
        this.createDirectory = createDirectory;
    }

    @Override
    public List<Path> prepareContext(Path context) throws IOException {
        // Symlink happy, create file if missing.
        if (!Files.isDirectory(context)) {
            if (!this.createDirectory) {
                return List.of();
            }

            Files.createDirectories(context);
        }

        final List<Path> files = new ArrayList<>();
        this.walkFiles(context, path -> {
            try {
                files.add(FILE_PROVIDER_SOURCE.prepareContext(path));
            } catch (IllegalArgumentException ignored) {
                // Ignore illegal argument exceptions from jar checking
            } catch (final Exception e) {
                LOGGER.error("Error preparing plugin context: " + e.getMessage(), e);
            }
        });
        files.sort(java.util.Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER)); // FissionMC: stable load order
        return files;
    }

    @Override
    public void registerProviders(EntrypointHandler entrypointHandler, List<Path> context) {
        // FissionMC: open + parse every jar in parallel (order-preserving), then register on this thread in order.
        final List<Object> probed = context.parallelStream().map(path -> {
            try {
                return (Object) FILE_PROVIDER_SOURCE.probe(path);
            } catch (Exception e) {
                return e;
            }
        }).toList();

        for (Object result : probed) {
            try {
                if (result instanceof Exception e) {
                    throw e;
                }
                FILE_PROVIDER_SOURCE.registerProbed(entrypointHandler, (FileProviderSource.Probe) result);
            } catch (IllegalArgumentException ignored) {
                // Ignore illegal argument exceptions from jar checking
            } catch (Exception e) {
                LOGGER.error("Error loading plugin: " + e.getMessage(), e);
            }
        }
    }

    private void walkFiles(Path context, Consumer<Path> consumer) throws IOException {
        Files.walk(context, 1, FileVisitOption.FOLLOW_LINKS)
            .filter(this::isValidFile)
            .forEach(consumer);
    }

    public boolean isValidFile(Path path) {
        // Avoid loading plugins that start with a dot
        return Files.isRegularFile(path) && !path.startsWith(".");
    }
}
