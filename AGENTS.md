# Development notes

## Run Android unit tests

Use the Android Studio bundled JDK. On this machine its Java home is
`/Applications/Android Studio.app/Contents/jbr/Contents/Home` (not the outer
`jbr` directory). In sandboxed environments, use a writable Gradle cache rather
than the home-directory cache:

```sh
JDK_TASK='/Applications/Android Studio.app/Contents/jbr/Contents/Home'
JAVA_HOME="$JDK_TASK" GRADLE_USER_HOME='/private/tmp/ring-autopilot-gradle' ./gradlew test --console=plain
```

Verified on 2026-09-15: `testDebugUnitTest` ran 6 tests with 0 failures and 0
errors.
