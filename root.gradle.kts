plugins {
    id("fabric-loom") version "1.7-SNAPSHOT" apply false
    id("com.replaymod.preprocess") version "c2041a3"
}

subprojects {
    repositories {
        //mavenLocal()
        mavenCentral()
        maven("https://libraries.minecraft.net/")
        maven("https://repo.spongepowered.org/repository/maven-public/")
        maven("https://github.com/jitsi/jitsi-maven-repository/raw/master/releases/")
        maven("https://maven.fabricmc.net/")
        maven("https://jitpack.io")
    }
}

preprocess {
    val mc12101 = createNode("1.21.1", 12101, "yarn")
    val mc12100 = createNode("1.21", 12100, "yarn")

    mc12101.link(mc12100)
}