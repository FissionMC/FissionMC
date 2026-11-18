plugins { `java-library` }

// The API plugin authors compile against (compileOnly: the server provides these classes at runtime). Plain Java, no FissionMC internals.
dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.240-stable")
}
