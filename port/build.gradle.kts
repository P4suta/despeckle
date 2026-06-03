plugins {
    id("despeckle.java-conventions")
    id("despeckle.test-conventions")
    id("despeckle.quality-conventions")
}

// Port interfaces only: domain / JDK types in their signatures, nothing third-party.
dependencies {
    implementation(project(":domain"))
}
