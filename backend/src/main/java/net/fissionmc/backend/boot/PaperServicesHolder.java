package net.fissionmc.backend.boot;

import net.fissionmc.craft.compat.PaperServices;

/** Implemented by {@code Services} (ServicesMixin) so Paper's 4-argument {@code Services.create} can fill its paper component. */
public interface PaperServicesHolder {
    void fission$setPaper(PaperServices paper);
}
