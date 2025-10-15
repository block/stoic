fun versionCodeFromVersionName(versionName: String): Int {
    val snapshotSuffix = "-SNAPSHOT"
    val isSnapshot = versionName.endsWith(snapshotSuffix)

    val cleaned = versionName.removeSuffix(snapshotSuffix)
    val parts = cleaned.split(".")
    require(parts.size == 3) { "Expected MAJOR.MINOR.PATCH" }

    val (major, minor, patch) = parts.map { it.toInt() }
    require(major in 0..1999)
    require(minor in 0..999)
    require(patch in 0..999)

    val base = major * 1_000_000 + minor * 10_000 + patch * 10
    return if (isSnapshot) base - 1 else base
}
