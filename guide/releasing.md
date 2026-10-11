# Releasing to Maven Central

Artifacts are published under `io.github.zaaim-halim.toolgate` through the Sonatype Central Portal.
`toolgate-demo`, `toolgate-fuzz` and `toolgate-benchmarks` are never published.

## Once

1. Generate a user token in the Central Portal and add it to `~/.m2/settings.xml`:

   ```xml
   <server>
       <id>central</id>
       <username>token-name</username>
       <password>token-secret</password>
   </server>
   ```

2. Have a GPG key, published to a key server, for signing.

## Each release

1. Set the version without `-SNAPSHOT`: `./mvnw versions:set -DnewVersion=0.1.0`.
2. Check everything builds, without signing: `./mvnw -Prelease -Dgpg.skip verify`.
3. Upload: `./mvnw -Prelease deploy`. The `release` profile attaches sources, Javadoc and a CycloneDX
   SBOM (`bom.json`) to every published module, signs them, and uploads one deployment, which waits,
   validated, in the Central Portal.
4. Publish the deployment in the Central Portal, tag the commit, and set the next `-SNAPSHOT` version.
