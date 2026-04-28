# New KMP rewrite

## Publishing

### GitHub setup

Configure these repository secrets in GitHub under
`Settings -> Secrets and variables -> Actions -> Repository secrets`:

- `MAVEN_CENTRAL_USERNAME`: Maven Central Portal token username.
- `MAVEN_CENTRAL_PASSWORD`: Maven Central Portal token password.

Release publishing also needs signing secrets:

- `SIGNING_KEY_ID`
- `SIGNING_PASSWORD`
- `GPG_KEY_CONTENTS`

Snapshot publishing does not need signing secrets.

Before publishing snapshots, enable snapshots for the `io.github.nicolals`
namespace in the Maven Central Portal. Sonatype serves snapshots from:

```text
https://central.sonatype.com/repository/maven-snapshots/
```

### Publish a snapshot

Keep `versionName` in `build.gradle.kts` as a `-SNAPSHOT` version, for example:

```kotlin
val versionName = "0.3.0-SNAPSHOT"
```

Then run the manual GitHub workflow:

1. Open `Actions` in GitHub.
2. Select `Publish Snapshot`.
3. Click `Run workflow`.
4. Select the branch to publish, usually `main`.
5. Click `Run workflow`.

Verify a snapshot upload with:

```sh
curl -fsSL https://central.sonatype.com/repository/maven-snapshots/io/github/nicolals/nwc-kmp/0.3.0-SNAPSHOT/maven-metadata.xml
```

Consumers need the snapshot repository explicitly:

```kotlin
repositories {
    maven {
        url = uri("https://central.sonatype.com/repository/maven-snapshots/")
        mavenContent { snapshotsOnly() }
    }
    mavenCentral()
}
```

### Publish a release

Set `versionName` to a non-snapshot version, create a GitHub release, and the
`Publish` workflow will publish signed artifacts to Maven Central.
