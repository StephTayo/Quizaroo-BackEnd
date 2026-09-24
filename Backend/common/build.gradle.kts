dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)
    implementation(libs.slf4j.api)

    testImplementation(libs.json.schema.validator)
}

sourceSets {
    test {
        resources {
            srcDir(rootProject.file("protocol"))
        }
    }
}