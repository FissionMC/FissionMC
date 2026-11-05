package net.fissionmc.backend.mixin.server.dedicated;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Locale;
import net.minecraft.DefaultUncaughtExceptionHandler;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.dedicated.DedicatedServerProperties;
import net.minecraft.server.dedicated.DedicatedServerSettings;
import net.minecraft.server.players.OldUsersConverter;
import net.minecraft.server.rcon.thread.QueryThreadGs4;
import net.minecraft.server.rcon.thread.RconThread;
import net.minecraft.util.Util;
import net.minecraft.util.debugchart.RemoteDebugSampleType;
import net.minecraft.util.debugchart.RemoteSampleLogger;
import net.minecraft.util.debugchart.TpsDebugDimensions;
import net.minecraft.util.monitoring.jmx.MinecraftServerStatistics;
import net.minecraft.world.level.gamerules.GameRules;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Paper: sources/net/minecraft/server/dedicated/DedicatedServer.java.patch
 *
 * <p>The lifecycle hunks are active only when the server was started through Paper's flow
 * ({@code org.bukkit.craftbukkit.Main}), i.e. {@code this.options != null}.
 */
@Mixin(DedicatedServer.class)
public abstract class DedicatedServerMixin extends MinecraftServer {
    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final private DedicatedServerSettings settings;
    @Shadow private @Nullable QueryThreadGs4 queryThreadGs4;
    @Shadow private @Nullable RconThread rconThread;
    @Shadow private RemoteSampleLogger tickTimeLogger;

    @Shadow public abstract DedicatedServerProperties getProperties();
    @Shadow protected abstract boolean convertOldUsers();
    @Shadow public abstract DedicatedPlayerList getPlayerList();
    @Shadow public abstract String getLevelIdName();

    private DedicatedServerMixin() {
        super(null, null, null, null, null, null, null, null, null, false, null);
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void paper$init(CallbackInfo ci) {
        this.setMotd(this.settings.getProperties().motd.get()); // Paper - set field from initial properties
    }

    // private final RconConsoleSource rconConsoleSource; // CraftBukkit - remove field
    // FissionMC: the field stays (null); its CraftRemoteConsoleCommandSender needs Bukkit.getServer(), which does not exist
    // yet. Paper's rcon path creates an RconConsoleSource per connection (runCommand(RconConsoleSource, String)).
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "<init>", at = @At(value = "NEW", target = "(Lnet/minecraft/server/MinecraftServer;)Lnet/minecraft/server/rcon/RconConsoleSource;"))
    private net.minecraft.server.rcon.@Nullable RconConsoleSource paper$noRconConsoleSource(final MinecraftServer server, final com.llamalad7.mixinextras.injector.wrapoperation.Operation<net.minecraft.server.rcon.RconConsoleSource> original) {
        return this.options != null ? null : original.call(server);
    }
    // TODO(behaviour): runCommand(String) -> UnsupportedOperationException and runCommand(RconConsoleSource, String) with RemoteServerCommandEvent

    // FissionMC: Paper's initServer changes most of the method (hunks @@ -167 .. -260), so Paper's whole body runs here
    // instead of vanilla's.
    @Inject(method = "initServer", at = @At("HEAD"), cancellable = true)
    private void paper$initServer(CallbackInfoReturnable<Boolean> cir) throws IOException {
        if (this.options == null) {
            return; // FissionMC: not started through Paper's flow
        }
        cir.setReturnValue(this.paper$initServer0());
    }

    private boolean paper$initServer0() throws IOException {
        final DedicatedServer self = (DedicatedServer) (Object) this;
        Thread consoleThread = new Thread(() -> {
            if (!org.bukkit.craftbukkit.Main.useConsole) return; // CraftBukkit
            // Paper start - Use TerminalConsoleAppender
            new com.destroystokyo.paper.console.PaperConsole(self).start();
            // Paper end - Use TerminalConsoleAppender
        }, "Server console handler");
        // CraftBukkit start - TODO: handle command-line logging arguments
        java.util.logging.Logger global = java.util.logging.Logger.getLogger("");
        global.setUseParentHandlers(false);
        for (java.util.logging.Handler handler : global.getHandlers()) {
            global.removeHandler(handler);
        }
        global.addHandler(new org.bukkit.craftbukkit.util.ForwardLogHandler());

        final org.apache.logging.log4j.Logger logger = org.apache.logging.log4j.LogManager.getRootLogger();

        System.setOut(org.apache.logging.log4j.io.IoBuilder.forLogger(logger).setLevel(org.apache.logging.log4j.Level.INFO).buildPrintStream());
        System.setErr(org.apache.logging.log4j.io.IoBuilder.forLogger(logger).setLevel(org.apache.logging.log4j.Level.WARN).buildPrintStream());
        // CraftBukkit end
        consoleThread.setDaemon(true);
        consoleThread.setUncaughtExceptionHandler(new DefaultUncaughtExceptionHandler(LOGGER));
        // consoleThread.start(); // Paper - Enhance console tab completions for brigadier commands; moved down
        LOGGER.info("Starting minecraft server version {}", SharedConstants.getCurrentVersion().name());
        if (Runtime.getRuntime().maxMemory() / 1024L / 1024L < 512L) {
            LOGGER.warn("To start the server with more ram, launch it as \"java -Xmx1024M -Xms1024M -jar minecraft_server.jar\"");
        }

        // Paper start - detect running as root
        if (io.papermc.paper.util.ServerEnvironment.userIsRootOrAdmin()) {
            LOGGER.warn("****************************");
            LOGGER.warn("YOU ARE RUNNING THIS SERVER AS AN ADMINISTRATIVE OR ROOT USER. THIS IS NOT ADVISED.");
            LOGGER.warn("YOU ARE OPENING YOURSELF UP TO POTENTIAL RISKS WHEN DOING THIS.");
            LOGGER.warn("FOR MORE INFORMATION, SEE https://madelinemiller.dev/blog/root-minecraft-server/");
            LOGGER.warn("****************************");
        }
        // Paper end - detect running as root

        LOGGER.info("Loading properties");
        DedicatedServerProperties properties = this.settings.getProperties();
        if (this.isSingleplayer()) {
            this.setLocalIp("127.0.0.1");
        } else {
            this.setUsesAuthentication(properties.onlineMode);
            this.setPreventProxyConnections(properties.preventProxyConnections);
            this.setLocalIp(properties.serverIp);
        }

        // Spigot start
        this.setPlayerList(new DedicatedPlayerList(self, this.registries(), this.playerDataStorage)); // creates CraftServer (PlayerListMixin)
        org.spigotmc.SpigotConfig.init((java.io.File) this.options.valueOf("spigot-settings"));
        org.spigotmc.SpigotConfig.registerCommands();
        // Spigot end
        // Paper start - initialize global and world-defaults configuration
        this.paperConfigurations.initializeGlobalConfiguration(this.registryAccess());
        this.paperConfigurations.initializeWorldDefaultsConfiguration(this.registryAccess());
        // Paper end - initialize global and world-defaults configuration
        this.server.spark.enableEarlyIfRequested(); // Paper - spark
        // Paper start - fix converting txt to json file; convert old users earlier after PlayerList creation but before file load/save
        if (this.convertOldUsers()) {
            this.services().nameToIdCache().save(false); // Paper
        }
        this.getPlayerList().loadAndSaveFiles(); // Must be after convertNames
        // Paper end - fix converting txt to json file
        org.spigotmc.WatchdogThread.doStart(org.spigotmc.SpigotConfig.timeoutTime, org.spigotmc.SpigotConfig.restartOnCrash); // Paper - start watchdog thread
        consoleThread.start(); // Paper - Enhance console tab completions for brigadier commands; start console thread after MinecraftServer.console & PaperConfig are initialized
        io.papermc.paper.command.PaperCommands.registerCommands(self); // Paper - setup /paper command
        this.server.spark.registerCommandBeforePlugins(this.server); // Paper - spark
        // FissionMC: Paper starts bStats here (com.destroystokyo.paper.Metrics.PaperMetrics.startMetrics()), which reports
        // to bstats.org as "Paper". Left out on purpose.
        com.destroystokyo.paper.VersionHistoryManager.INSTANCE.getClass(); // Paper - load version history now

        // this.worldData.setGameType(properties.gameMode.get()); // CraftBukkit - moved to world loading
        LOGGER.info("Default game type: {}", properties.gameMode.get());
        // TODO(behaviour): Paper - Unix domain socket support (server-ip "unix:..."; needs the
        //  ServerConnectionListener.startTcpServerListener(SocketAddress) overload)
        InetAddress localAddress = null;
        if (!this.getLocalIp().isEmpty()) {
            localAddress = InetAddress.getByName(this.getLocalIp());
        }

        if (this.getPort() < 0) {
            this.setPort(properties.serverPort);
        }

        this.initializeKeyPair();
        LOGGER.info("Starting Minecraft server on {}:{}", this.getLocalIp().isEmpty() ? "*" : this.getLocalIp(), this.getPort());

        try {
            this.getConnection().startTcpServerListener(localAddress, this.getPort());
        } catch (IOException e) {
            LOGGER.warn("**** FAILED TO BIND TO PORT!");
            LOGGER.warn("The exception was: {}", e.toString());
            LOGGER.warn("Perhaps a server is already running on that port?");
            if (true) throw new IllegalStateException("Failed to bind to port", e); // Paper - Propagate failed to bind to port error
            return false;
        }

        // CraftBukkit start
        this.server.loadPlugins();
        this.server.enablePlugins(org.bukkit.plugin.PluginLoadOrder.STARTUP);
        // CraftBukkit end

        // Paper start - Add Velocity IP Forwarding Support
        boolean usingProxy = org.spigotmc.SpigotConfig.bungee || io.papermc.paper.configuration.GlobalConfiguration.get().proxies.velocity.enabled;
        String proxyFlavor = (io.papermc.paper.configuration.GlobalConfiguration.get().proxies.velocity.enabled) ? "Velocity" : "BungeeCord";
        String proxyLink = (io.papermc.paper.configuration.GlobalConfiguration.get().proxies.velocity.enabled) ? "https://docs.papermc.io/velocity/security" : "http://www.spigotmc.org/wiki/firewall-guide/";
        // Paper end - Add Velocity IP Forwarding Support
        if (!this.usesAuthentication()) {
            LOGGER.warn("**** SERVER IS RUNNING IN OFFLINE/INSECURE MODE!");
            LOGGER.warn("The server will make no attempt to authenticate usernames. Beware.");
            // Spigot start
            // Paper start - Add Velocity IP Forwarding Support
            if (usingProxy) {
                LOGGER.warn("Whilst this makes it possible to use {}, unless access to your server is properly restricted, it also opens up the ability for hackers to connect with any username they choose.", proxyFlavor);
                LOGGER.warn("Please see {} for further information.", proxyLink);
                // Paper end - Add Velocity IP Forwarding Support
            } else {
                LOGGER.warn("While this makes the game possible to play without internet access, it also opens up the ability for hackers to connect with any username they choose.");
            }
            // Spigot end
            LOGGER.warn("To change this, set \"online-mode\" to \"true\" in the server.properties file.");
        }

        // CraftBukkit start
        /*
        if (this.convertOldUsers()) {
            this.services.nameToIdCache().save();
        }
        */
        // CraftBukkit end

        if (!OldUsersConverter.areOldUserlistsRemoved()) {
            return false;
        }

        // this.setPlayerList(new DedicatedPlayerList(this, this.registries(), this.playerDataStorage)); // CraftBukkit - moved up
        this.tickTimeLogger = new RemoteSampleLogger(TpsDebugDimensions.values().length, this.debugSubscribers(), RemoteDebugSampleType.TICK_TIME);
        long levelNanoTime = Util.getNanos();
        this.services.nameToIdCache().resolveOfflineUsers(!this.usesAuthentication());
        LOGGER.info("Preparing level \"{}\"", this.getLevelIdName());
        this.loadLevel(this.storageSource.getLevelId()); // CraftBukkit
        long elapsed = Util.getNanos() - levelNanoTime;
        String time = String.format(Locale.ROOT, "%.3fs", elapsed / 1.0E9);
        LOGGER.info("Done preparing level \"{}\" ({})", this.getLevelIdName(), time); // Paper - Improve startup message, add total time
        this.initPostWorld(); // Paper - don't include plugins in world preparation time
        if (properties.announcePlayerAchievements != null) {
            this.getAllLevels().forEach(l -> l.getGameRules().set(GameRules.SHOW_ADVANCEMENT_MESSAGES, properties.announcePlayerAchievements, this.overworld())); // Paper - per-world game rules
        }

        if (properties.enableQuery) {
            LOGGER.info("Starting GS4 status listener");
            this.queryThreadGs4 = QueryThreadGs4.create(self);
        }

        if (properties.enableRcon) {
            LOGGER.info("Starting remote control listener");
            this.rconThread = RconThread.create(self);
        }

        // Spigot - disable vanilla's ServerWatchdog thread: if (false && this.getMaxTickLength() > 0L) { ... }

        if (properties.enableJmxMonitoring) {
            MinecraftServerStatistics.registerJmxMonitoring(self);
            LOGGER.info("JMX monitoring enabled");
        }

        this.saveEverything(false, true, true);
        this.notificationManager().serverStarted();
        return true;
    }

    // Paper start - Add path to plugins folder
    public java.io.File getPluginsFolder() {
        return (java.io.File) this.options.valueOf("plugins");
    }
    // Paper end

    // Paper start - getMotd/setMotd keep the value in MinecraftServer's field
    @Inject(method = "getMotd", at = @At("HEAD"), cancellable = true)
    private void paper$getMotd(CallbackInfoReturnable<String> cir) {
        cir.setReturnValue(super.getMotd()); // Paper
    }

    @Inject(method = "setMotd", at = @At("HEAD"), cancellable = true)
    private void paper$setMotd(final String motd, CallbackInfo ci) {
        super.setMotd(motd);
        this.settings.update(p -> p.motd.update(this.registryAccess(), this.getMotd()));
        ci.cancel();
    }
    // Paper end

    // CraftBukkit / Paper - Improved watchdog support: exit the JVM once the server has stopped
    @Inject(method = "onServerExit", at = @At("TAIL"))
    private void paper$exit(CallbackInfo ci) {
        if (this.options == null) {
            return; // FissionMC: not started through Paper's flow
        }
        this.hasFullyShutdown = true; // Paper - Improved watchdog support
        System.exit(this.abnormalExit ? 70 : 0); // CraftBukkit // Paper - Improved watchdog support
    }
    // TODO(behaviour): onServerExit - rconThread.stopNonBlocking() and the commented-out queryThreadGs4.stop() (Paper - don't wait for remote connections)

    @WrapWithCondition(method = "stopServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/Util;shutdownExecutors()V"))
    private boolean paper$shutdownExecutorsMovedToSuper() {
        return this.options == null; // Paper - Improved watchdog support; moved into super
    }

    // CraftBukkit start
    public boolean isDebugging() {
        return this.getProperties().debug;
    }

    public org.bukkit.command.CommandSender getBukkitSender(CommandSourceStack wrapper) {
        return this.console;
    }
    // CraftBukkit end

    // CraftBukkit start - ServerCommand for preprocessing
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "handleConsoleInputs", at = @At(value = "INVOKE", target = "Lnet/minecraft/commands/Commands;performPrefixedCommand(Lnet/minecraft/commands/CommandSourceStack;Ljava/lang/String;)V"))
    private void paper$serverCommandEvent(final net.minecraft.commands.Commands commands, final net.minecraft.commands.CommandSourceStack source, final String msg, final com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        org.bukkit.event.server.ServerCommandEvent event = new org.bukkit.event.server.ServerCommandEvent(((MinecraftServer) (Object) this).console, msg);
        ((MinecraftServer) (Object) this).server.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;
        original.call(commands, source, event.getCommand());
    }
    // CraftBukkit end
}
