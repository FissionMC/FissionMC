package net.fissionmc.api;

/** A plugin message was not sent: too big, over the plugin's quota, bad channel name, or no link to the master. */
public class FissionNetException extends RuntimeException {
    public FissionNetException(String message) { super(message); }
}
