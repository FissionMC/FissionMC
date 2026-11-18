package net.fissionmc.api;

/** Receives messages for a channel, see {@link FissionNet#listen}. Called on a network worker thread; messages of one sender on one channel arrive in order. */
public interface FissionNetListener {
    /** A message from [sourceNode] (a backend id; never spoofable: the master fills it in from the authenticated link). [data] is the plugin's own. */
    void onMessage(String sourceNode, String channel, byte[] data);

    /** A message this server sent on [channel] to [target] could not be delivered (no such backend connected, quota or size limit at the master or the receiver). */
    default void onUndeliverable(String target, String channel, String reason) {}
}
