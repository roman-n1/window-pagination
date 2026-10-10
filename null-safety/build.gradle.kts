plugins { kotlin("jvm") }

dependencies {
    compileOnly("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")
}

tasks.test {
    dependsOn(tasks.jar)
    systemProperty("policy.jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
    maxHeapSize = "1g"
}
