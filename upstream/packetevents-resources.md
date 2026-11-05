# PacketEvents mapping resources

`packetevents/resources/assets/mappings/**.nbt` (123 files, 2.2 MB) are the compressed mapping resources PacketEvents loads from its classpath
(`assets/mappings/...`; block states, registries, item components). They are GENERATED, not hand written.

Provenance: upstream commit 4b766e86de98f5ec2a6c7ceb68cbedfac5f83bb6 (branch 2.0), produced by upstream's `:api:compressMappings`
(output `api/build/mappings/generated/assets/mappings`) from upstream's `mappings/` JSON.

Regenerate (needed after every re-vendor with `scripts/upstream.py update packetevents`, because the JSON `mappings/` is not vendored):
```
git clone --depth 1 --branch 2.0 https://github.com/retrooper/packetevents.git pe && cd pe
git checkout <new pinned sha>
./gradlew :api:compressMappings
rm -rf <fission>/packetevents/resources/assets/mappings && cp -r api/build/mappings/generated/assets/mappings <fission>/packetevents/resources/assets/mappings
```
